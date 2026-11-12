package clre20.coreTP.config;

import clre20.coreTP.Module;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 所有設定檔讀取後的快照；重載時整個換新，讀取時不碰硬碟。 */
public final class Settings {

    public record RtpRange(int centerX, int centerZ, int minRadius, int maxRadius) {
    }

    private final YamlConfiguration config;
    private final Map<Module, ModuleSettings> modules = new EnumMap<>(Module.class);
    private final Map<String, Material> icons = new HashMap<>();

    public final int saveIntervalSeconds;
    public final boolean safetyCheck;
    public final boolean countdownActionbar;
    public final boolean updateCheckOnStartup;
    public final boolean updateNotifyOnJoin;
    public final Material filler;

    public final int tpaTimeoutSeconds;
    public final int tpaIntervalSeconds;
    public final int homeDefaultLimit;
    public final int homeMaxLimit;
    public final int homeNameLength;
    public final int warpNameLength;
    public final int backMaxHistory;
    public final int backExpireDays;
    public final long backSaveCooldownMs;
    public final double backMinDistanceSq;
    public final boolean backRecordDeath;
    public final Set<TeleportCause> backCauses;
    public final int rtpMaxAttempts;
    public final int rtpMaxConcurrent;
    private final Map<String, RtpRange> rtpRanges = new HashMap<>();
    private final RtpRange rtpDefault;

    public Settings(JavaPlugin plugin) {
        config = YamlFiles.load(plugin, "config.yml");
        saveIntervalSeconds = clamp(config.getInt("storage.save-interval-seconds", 60), 10, 3600);
        safetyCheck = config.getBoolean("safety.check-destination", true);
        countdownActionbar = config.getBoolean("countdown.actionbar", true);
        updateCheckOnStartup = config.getBoolean("update-checker.check-on-startup", true);
        updateNotifyOnJoin = config.getBoolean("update-checker.notify-on-join", true);
        filler = material(config.getString("gui.filler"), Material.GRAY_STAINED_GLASS_PANE);
        ConfigurationSection iconSection = config.getConfigurationSection("gui.icons");
        if (iconSection != null) {
            for (String key : iconSection.getKeys(false)) {
                icons.put(key, material(iconSection.getString(key), Material.PAPER));
            }
        }

        Map<Module, YamlConfiguration> files = new EnumMap<>(Module.class);
        for (Module m : Module.values()) {
            YamlConfiguration yml = YamlFiles.load(plugin, "modules/" + m.id() + ".yml");
            files.put(m, yml);
            modules.put(m, ModuleSettings.from(yml));
        }

        tpaTimeoutSeconds = clamp(files.get(Module.TPA).getInt("request-timeout-seconds", 60), 5, 3600);

        tpaIntervalSeconds = clamp(files.get(Module.TPA).getInt("request-interval-seconds", 5), 0, 3600);

        YamlConfiguration home = files.get(Module.HOME);
        homeMaxLimit = clamp(home.getInt("max-limit", 100), 1, 1000);
        homeDefaultLimit = clamp(home.getInt("default-limit", 3), 0, homeMaxLimit);
        homeNameLength = clamp(home.getInt("max-name-length", 16), 1, 32);
        warpNameLength = clamp(files.get(Module.WARP).getInt("max-name-length", 16), 1, 32);

        YamlConfiguration back = files.get(Module.BACK);
        backMaxHistory = clamp(back.getInt("max-history", 10), 1, 45);
        backExpireDays = clamp(back.getInt("history-expire-days", 30), 0, 3650);
        backSaveCooldownMs = Math.max(0, Math.min(600_000L, back.getLong("save-cooldown-ms", 1000)));
        double minDistance = back.getDouble("min-distance", 3.0);
        minDistance = Double.isFinite(minDistance) ? Math.max(0, Math.min(1000, minDistance)) : 3.0;
        backMinDistanceSq = minDistance * minDistance;
        backRecordDeath = back.getBoolean("record-death", true);
        EnumSet<TeleportCause> causes = EnumSet.noneOf(TeleportCause.class);
        for (String name : back.getStringList("allowed-causes")) {
            try {
                causes.add(TeleportCause.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException | NullPointerException e) {
                plugin.getLogger().warning("modules/back.yml 的 allowed-causes 有無法辨識的項目：" + name);
            }
        }
        backCauses = causes;

        YamlConfiguration rtp = files.get(Module.RTP);
        rtpMaxAttempts = clamp(rtp.getInt("max-attempts", 12), 1, 50);
        rtpMaxConcurrent = clamp(rtp.getInt("max-concurrent", 2), 1, 20);
        RtpRange fallback = new RtpRange(0, 0, 500, 5000);
        ConfigurationSection worlds = rtp.getConfigurationSection("worlds");
        RtpRange def = fallback;
        if (worlds != null) {
            for (String key : worlds.getKeys(false)) {
                ConfigurationSection w = worlds.getConfigurationSection(key);
                if (w == null) {
                    continue;
                }
                int max = clamp(w.getInt("max-radius", 5000), 16, 29_000_000);
                int min = clamp(w.getInt("min-radius", 500), 0, max);
                RtpRange range = new RtpRange(
                        clamp(w.getInt("center-x", 0), -29_000_000, 29_000_000),
                        clamp(w.getInt("center-z", 0), -29_000_000, 29_000_000), min, max);
                if (key.equalsIgnoreCase("default")) {
                    def = range;
                } else {
                    rtpRanges.put(key.toLowerCase(Locale.ROOT), range);
                }
            }
        }
        rtpDefault = def;
    }

    public ModuleSettings module(Module m) {
        return modules.get(m);
    }

    public RtpRange rtpRange(String worldName) {
        return rtpRanges.getOrDefault(worldName.toLowerCase(Locale.ROOT), rtpDefault);
    }

    public Material icon(String key, Material fallback) {
        return icons.getOrDefault(key, fallback);
    }

    private static Material material(String name, Material fallback) {
        if (name == null) {
            return fallback;
        }
        Material m = Material.matchMaterial(name);
        return m != null && m.isItem() && !m.isAir() ? m : fallback;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
