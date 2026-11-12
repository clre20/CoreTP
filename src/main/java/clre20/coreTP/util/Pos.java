package clre20.coreTP.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

/** 不持有 World 的位置資料（只存世界名稱與座標）。 */
public record Pos(String world, double x, double y, double z, float yaw, float pitch) {

    public static @Nullable Pos of(@Nullable Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return null;
        }
        Pos pos = new Pos(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        return pos.valid() ? pos : null;
    }

    /** 座標是否為有限且合理的數值。 */
    public boolean valid() {
        return world != null && !world.isEmpty() && world.length() <= 128
                && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Float.isFinite(yaw) && Float.isFinite(pitch)
                && Math.abs(x) <= 30_000_000 && Math.abs(z) <= 30_000_000 && Math.abs(y) <= 100_000;
    }

    /** 世界沒有載入時回傳 null。 */
    public @Nullable Location toLocation() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z, yaw, pitch);
    }

    public int blockX() {
        return (int) Math.floor(x);
    }

    public int blockY() {
        return (int) Math.floor(y);
    }

    public int blockZ() {
        return (int) Math.floor(z);
    }
}
