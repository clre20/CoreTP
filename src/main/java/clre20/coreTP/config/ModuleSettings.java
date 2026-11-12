package clre20.coreTP.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 每個傳送功能共通的設定（不可變）。 */
public record ModuleSettings(
        boolean enabled,
        Set<String> disabledWorlds,
        int warmupSeconds,
        boolean cancelOnMove,
        boolean cancelOnDamage,
        int cooldownSeconds,
        boolean distanceEnabled,
        double distanceBase,
        double distancePerBlock,
        double distanceMin,
        double distanceMax,
        boolean crossWorldEnabled,
        double crossWorldPrice) {

    public static ModuleSettings from(ConfigurationSection c) {
        Set<String> worlds = new HashSet<>();
        for (String w : c.getStringList("disabled-worlds")) {
            if (w != null) {
                worlds.add(w.toLowerCase(Locale.ROOT));
            }
        }
        double min = money(c.getDouble("cost.distance.min", 0));
        double max = Math.max(min, money(c.getDouble("cost.distance.max", 1000)));
        return new ModuleSettings(
                c.getBoolean("enabled", true),
                Set.copyOf(worlds),
                clamp(c.getInt("warmup-seconds", 3), 0, 3600),
                c.getBoolean("cancel-on-move", true),
                c.getBoolean("cancel-on-damage", true),
                clamp(c.getInt("cooldown-seconds", 0), 0, 86_400 * 30),
                c.getBoolean("cost.distance.enabled", false),
                money(c.getDouble("cost.distance.base", 0)),
                money(c.getDouble("cost.distance.per-block", 0.01)),
                min,
                max,
                c.getBoolean("cost.cross-world.enabled", false),
                money(c.getDouble("cost.cross-world.price", 100)));
    }

    public boolean worldDisabled(String worldName) {
        return !disabledWorlds.isEmpty() && disabledWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 金額一律是有限的非負數。 */
    private static double money(double v) {
        return Double.isFinite(v) && v > 0 ? v : 0;
    }
}
