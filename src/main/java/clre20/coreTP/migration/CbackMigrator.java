package clre20.coreTP.migration;

import clre20.coreTP.CoreTP;
import clre20.coreTP.config.YamlFiles;
import clre20.coreTP.storage.BackEntry;
import clre20.coreTP.storage.Database;
import clre20.coreTP.util.Pos;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 舊插件 Cback 的資料遷移，只在伺服器開機時執行一次：
 * 匯入歷史位置與設定 → 把整個 Cback 資料夾壓縮備份到 CoreTP 資料夾 → 確認備份無誤後才刪除。
 * 任何一步失敗都會保留原資料夾。
 */
public final class CbackMigrator {

    private static final String MARKER = "cback-imported.marker";
    private static final long MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_ENTRIES = 45;

    private CbackMigrator() {
    }

    /** 舊的 Cback 插件是否還裝在伺服器上。 */
    public static boolean cbackInstalled() {
        return Bukkit.getPluginManager().getPlugin("Cback") != null;
    }

    public static void run(CoreTP plugin, Database db) {
        Path dataFolder = plugin.getDataFolder().toPath();
        Path cback = dataFolder.resolveSibling("Cback");
        if (!Files.isDirectory(cback, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return; // 沒有資料夾（或是符號連結）就不處理
        }
        var log = plugin.getLogger();
        var msg = plugin.messages();
        log.info(msg.console("migration.start"));
        try {
            Files.createDirectories(dataFolder);
            Path marker = dataFolder.resolve(MARKER);
            if (!Files.exists(marker)) {
                importConfig(plugin, cback.resolve("config.yml"));
                int[] counts = importData(db, cback.resolve("data"), log);
                Files.writeString(marker, LocalDateTime.now().toString(), StandardCharsets.UTF_8);
                log.info(msg.console("migration.imported", "players", counts[0], "entries", counts[1]));
            }

            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path zip = dataFolder.resolve("Cback-backup-" + stamp + ".zip");
            int written = zip(cback, zip);
            int verified;
            try (ZipFile check = new ZipFile(zip.toFile())) {
                verified = check.size();
            }
            if (verified != written) {
                Files.deleteIfExists(zip);
                throw new IOException("備份檔案數量不符（" + verified + " / " + written + "）");
            }
            log.info(msg.console("migration.zipped", "file", zip.getFileName().toString()));

            deleteTree(cback);
            Files.deleteIfExists(marker);
            log.info(msg.console("migration.deleted"));
        } catch (Exception e) {
            log.log(Level.SEVERE, msg.console("migration.failed", "reason", String.valueOf(e.getMessage())), e);
        }
    }

    // ---------------------------------------------------------------- 匯入

    private static Yaml safeYaml() {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit((int) MAX_FILE_BYTES * 2);
        options.setMaxAliasesForCollections(10);
        options.setNestingDepthLimit(20);
        options.setAllowDuplicateKeys(false);
        // SafeConstructor 只會建立基本型別，不會依檔案內容建立任意物件
        return new Yaml(new SafeConstructor(options));
    }

    private static Object readYaml(Path file) throws IOException {
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_FILE_BYTES) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return safeYaml().load(reader);
        } catch (RuntimeException e) {
            return null; // 格式壞掉的檔案略過（仍會被壓縮備份）
        }
    }

    /** @return {玩家數, 歷史位置筆數} */
    private static int[] importData(Database db, Path dataDir, java.util.logging.Logger log) throws Exception {
        Map<UUID, List<BackEntry>> parsed = new HashMap<>();
        if (Files.isDirectory(dataDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try (var stream = Files.newDirectoryStream(dataDir, "*.yml")) {
                for (Path file : stream) {
                    String fileName = file.getFileName().toString();
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(fileName.substring(0, fileName.length() - 4));
                    } catch (IllegalArgumentException e) {
                        continue; // 檔名不是 UUID
                    }
                    List<BackEntry> entries = parseEntries(readYaml(file));
                    if (!entries.isEmpty()) {
                        parsed.put(uuid, entries);
                    }
                }
            }
        }
        if (parsed.isEmpty()) {
            return new int[]{0, 0};
        }
        return db.submit(() -> {
            int players = 0;
            int total = 0;
            for (Map.Entry<UUID, List<BackEntry>> e : parsed.entrySet()) {
                int n = db.importBack(e.getKey(), e.getValue());
                if (n > 0) {
                    players++;
                    total += n;
                }
            }
            return new int[]{players, total};
        }).get(5, TimeUnit.MINUTES);
    }

    private static List<BackEntry> parseEntries(Object root) {
        List<BackEntry> out = new ArrayList<>();
        if (!(root instanceof Map<?, ?> map) || !(map.get("locations") instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            if (out.size() >= MAX_ENTRIES) {
                break;
            }
            if (!(o instanceof Map<?, ?> entry) || !(entry.get("location") instanceof Map<?, ?> loc)) {
                continue;
            }
            if (!(loc.get("world") instanceof String world)) {
                continue;
            }
            Double x = number(loc.get("x"));
            Double y = number(loc.get("y"));
            Double z = number(loc.get("z"));
            if (x == null || y == null || z == null) {
                continue;
            }
            Double yaw = number(loc.get("yaw"));
            Double pitch = number(loc.get("pitch"));
            Pos pos = new Pos(world, x, y, z, yaw == null ? 0f : yaw.floatValue(), pitch == null ? 0f : pitch.floatValue());
            if (!pos.valid()) {
                continue;
            }
            Double ts = number(entry.get("timestamp"));
            long timestamp = ts == null || ts < 0 ? System.currentTimeMillis() : ts.longValue();
            out.add(new BackEntry(pos, timestamp, Boolean.TRUE.equals(entry.get("isDeath"))));
        }
        return out;
    }

    private static Double number(Object o) {
        return o instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : null;
    }

    /** 把舊 config.yml 中對應得上的數值帶進 modules/back.yml（舊的自訂訊息不搬移）。 */
    private static void importConfig(CoreTP plugin, Path oldConfig) throws IOException {
        if (!(readYaml(oldConfig) instanceof Map<?, ?> old)) {
            return;
        }
        YamlConfiguration back = YamlFiles.load(plugin, "modules/back.yml");
        boolean changed = false;
        if (old.get("teleport-delay-seconds") instanceof Number n) {
            back.set("warmup-seconds", Math.max(0, Math.min(3600, n.intValue())));
            changed = true;
        }
        if (old.get("cancel-teleport-on-move") instanceof Boolean b) {
            back.set("cancel-on-move", b);
            changed = true;
        }
        if (old.get("cancel-teleport-on-damage") instanceof Boolean b) {
            back.set("cancel-on-damage", b);
            changed = true;
        }
        if (old.get("save-cooldown-ms") instanceof Number n) {
            back.set("save-cooldown-ms", Math.max(0L, Math.min(600_000L, n.longValue())));
            changed = true;
        }
        Double minDistance = number(old.get("min-distance"));
        if (minDistance != null) {
            back.set("min-distance", Math.max(0, Math.min(1000, minDistance)));
            changed = true;
        }
        if (old.get("record-death") instanceof Boolean b) {
            back.set("record-death", b);
            changed = true;
        }
        if (old.get("allowed-causes") instanceof List<?> causes) {
            List<String> valid = new ArrayList<>();
            for (Object c : causes) {
                if (c instanceof String s) {
                    try {
                        valid.add(TeleportCause.valueOf(s.trim().toUpperCase(Locale.ROOT)).name());
                    } catch (IllegalArgumentException ignored) {
                        // 無法辨識的項目略過
                    }
                }
            }
            if (!valid.isEmpty()) {
                back.set("allowed-causes", valid);
                changed = true;
            }
        }
        if (changed) {
            back.save(new File(plugin.getDataFolder(), "modules/back.yml"));
        }
    }

    // ---------------------------------------------------------------- 備份與刪除

    /** 壓縮整個資料夾（不跟隨符號連結）。先寫暫存檔，完成後才改名。 */
    private static int zip(Path source, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        int[] count = {0};
        try (OutputStream out = Files.newOutputStream(tmp);
             ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isRegularFile()) {
                        String name = "Cback/" + source.relativize(file).toString().replace(File.separatorChar, '/');
                        zos.putNextEntry(new ZipEntry(name));
                        Files.copy(file, zos);
                        zos.closeEntry();
                        count[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        return count[0];
    }

    /** 由內而外刪除；符號連結只刪連結本身，不會刪到資料夾以外的東西。 */
    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
