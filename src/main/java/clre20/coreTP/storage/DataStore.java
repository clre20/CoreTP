package clre20.coreTP.storage;

import clre20.coreTP.CoreTP;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * 記憶體快取 + 髒資料標記 + 定時批次寫入。
 * 指令與事件只讀寫記憶體；硬碟寫入集中在 I/O 執行緒。
 */
public final class DataStore {

    private static final long PENDING_TTL_MS = 5 * 60_000;

    private final CoreTP plugin;
    private final Database db;
    private final Map<UUID, PlayerData> players = new HashMap<>();
    /** 登入前（非同步）載入、等玩家真正進入時取走。 */
    private final Map<UUID, PlayerData> pending = new ConcurrentHashMap<>();
    private final Map<String, Warp> warps = new TreeMap<>();
    private final Map<String, PublicHome> publicHomes = new HashMap<>();
    private List<PublicHome> publicSorted;
    private BukkitTask flushTask;

    public DataStore(CoreTP plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    /** 啟動時載入共用資料（地標、公開的家索引）與已在線的玩家。 */
    public void start() throws Exception {
        int expireDays = plugin.settings().backExpireDays;
        if (expireDays > 0) {
            long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(expireDays);
            db.submit(() -> db.purgeBack(cutoff));
        }
        for (Warp w : db.submit(db::loadWarps).get(30, TimeUnit.SECONDS)) {
            warps.put(key(w.name()), w);
        }
        for (PublicHome h : db.submit(db::loadPublicHomes).get(30, TimeUnit.SECONDS)) {
            publicHomes.put(publicKey(h.owner(), h.name()), h);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            join(p);
        }
        scheduleFlush();
    }

    public void scheduleFlush() {
        if (flushTask != null) {
            flushTask.cancel();
        }
        long ticks = plugin.settings().saveIntervalSeconds * 20L;
        flushTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flush, ticks, ticks);
    }

    /** 在 AsyncPlayerPreLoginEvent（非同步執行緒）呼叫。 */
    public void preLogin(UUID uuid, String name) throws Exception {
        int maxBack = plugin.settings().backMaxHistory;
        PlayerData data = db.submit(() -> db.loadPlayer(uuid, name, maxBack)).get(10, TimeUnit.SECONDS);
        pending.put(uuid, data);
    }

    public @Nullable PlayerData join(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerData data = pending.remove(uuid);
        if (data == null) {
            // 插件在玩家在線時被載入（例如重載）才會走到這裡
            try {
                int maxBack = plugin.settings().backMaxHistory;
                String name = player.getName();
                data = db.submit(() -> db.loadPlayer(uuid, name, maxBack)).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "無法載入玩家資料：" + player.getName(), e);
                return null;
            }
        }
        if (!player.getName().equals(data.name)) {
            data.name = player.getName();
            data.dirty = true;
        }
        for (Home h : data.homes.values()) {
            if (h.isPublic()) {
                putPublic(new PublicHome(uuid, data.name, h.name(), h.pos()));
            }
        }
        players.put(uuid, data);
        return data;
    }

    public void quit(UUID uuid) {
        PlayerData data = players.remove(uuid);
        if (data != null && pending.containsKey(uuid)) {
            // 同一個帳號正在從別處登入：新連線在這次離線之前就讀了資料庫，
            // 直接把記憶體裡最新的資料交給它，避免還沒寫入的變動被舊資料蓋掉
            data.loadedAt = System.currentTimeMillis();
            pending.put(uuid, data);
        }
        if (data != null && data.dirty) {
            data.dirty = false;
            List<PlayerData.Snapshot> one = List.of(data.snapshot());
            db.submit(() -> {
                db.savePlayers(one);
                return null;
            });
        }
    }

    public @Nullable PlayerData get(UUID uuid) {
        return players.get(uuid);
    }

    /** 批次寫入所有有變動的玩家；沒有變動時幾乎沒有成本。 */
    public void flush() {
        long now = System.currentTimeMillis();
        pending.values().removeIf(d -> now - d.loadedAt > PENDING_TTL_MS);
        List<PlayerData.Snapshot> batch = null;
        for (PlayerData data : players.values()) {
            if (data.dirty) {
                data.dirty = false;
                if (batch == null) {
                    batch = new ArrayList<>();
                }
                batch.add(data.snapshot());
            }
        }
        if (batch == null) {
            return;
        }
        List<PlayerData.Snapshot> toSave = batch;
        db.submit(() -> {
            db.savePlayers(toSave);
            return null;
        }).exceptionally(error -> {
            // 寫入失敗：下次再試，不要默默丟掉
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (PlayerData.Snapshot s : toSave) {
                        PlayerData d = players.get(s.uuid());
                        if (d != null) {
                            d.dirty = true;
                        }
                    }
                });
            }
            return null;
        });
    }

    public void shutdown() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        flush();
        players.clear();
        pending.clear();
        db.close();
    }

    // ---- 家 ----

    public void homeChanged(PlayerData data, @Nullable Home before, @Nullable Home after) {
        data.dirty = true;
        if (before != null && before.isPublic()) {
            publicHomes.remove(publicKey(data.uuid, before.name()));
            publicSorted = null;
        }
        if (after != null && after.isPublic()) {
            putPublic(new PublicHome(data.uuid, data.name, after.name(), after.pos()));
        }
    }

    private void putPublic(PublicHome h) {
        publicHomes.put(publicKey(h.owner(), h.name()), h);
        publicSorted = null;
    }

    public @Nullable PublicHome publicHome(UUID owner, String name) {
        return publicHomes.get(publicKey(owner, name));
    }

    /** 依擁有者名稱排序的清單（有變動才重排）。 */
    public List<PublicHome> publicHomes() {
        if (publicSorted == null) {
            List<PublicHome> list = new ArrayList<>(publicHomes.values());
            list.sort(Comparator.comparing((PublicHome h) -> h.ownerName().toLowerCase(Locale.ROOT))
                    .thenComparing(h -> h.name().toLowerCase(Locale.ROOT)));
            publicSorted = List.copyOf(list);
        }
        return publicSorted;
    }

    // ---- 地標 ----

    public Collection<Warp> warps() {
        return warps.values();
    }

    public @Nullable Warp warp(String name) {
        return warps.get(key(name));
    }

    public void putWarp(Warp w) {
        warps.put(key(w.name()), w);
        db.submit(() -> {
            db.saveWarp(w);
            return null;
        });
    }

    public boolean removeWarp(String name) {
        Warp removed = warps.remove(key(name));
        if (removed == null) {
            return false;
        }
        db.submit(() -> {
            db.deleteWarp(removed.name());
            return null;
        });
        return true;
    }

    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String publicKey(UUID owner, String name) {
        return owner + ":" + key(name);
    }
}
