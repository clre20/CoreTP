package clre20.coreTP.module;

import clre20.coreTP.CoreTP;
import clre20.coreTP.Module;
import clre20.coreTP.storage.Warp;
import clre20.coreTP.teleport.TeleportService.Destination;
import clre20.coreTP.util.Names;
import clre20.coreTP.util.Pos;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

public final class WarpService {

    private final CoreTP plugin;

    public WarpService(CoreTP plugin) {
        this.plugin = plugin;
    }

    private boolean ready(CommandSender sender, String permission) {
        if (!sender.hasPermission(permission)) {
            plugin.messages().send(sender, "general.no-permission");
            return false;
        }
        if (!plugin.settings().module(Module.WARP).enabled()) {
            plugin.messages().send(sender, "general.module-disabled");
            return false;
        }
        return true;
    }

    public void set(Player p, String name, @Nullable String iconName) {
        if (!ready(p, "coretp.warp.set")) {
            return;
        }
        int maxLength = plugin.settings().warpNameLength;
        if (!Names.valid(name, maxLength)) {
            plugin.messages().send(p, "general.invalid-name", "max", maxLength);
            return;
        }
        Warp before = plugin.data().warp(name);
        String icon = before != null ? before.icon() : "";
        if (iconName != null) {
            Material m = iconName.length() <= 64 ? Material.matchMaterial(iconName) : null;
            if (m == null || !m.isItem() || m.isAir()) {
                plugin.messages().send(p, "warp.invalid-icon", "icon", iconName.length() > 64 ? iconName.substring(0, 64) : iconName);
                return;
            }
            icon = m.name();
        }
        Pos pos = Pos.of(p.getLocation());
        if (pos == null) {
            plugin.messages().send(p, "teleport.failed");
            return;
        }
        Warp after = new Warp(before != null ? before.name() : name, pos, icon,
                before != null ? before.created() : System.currentTimeMillis());
        plugin.data().putWarp(after);
        plugin.messages().send(p, before == null ? "warp.set" : "warp.updated", "name", after.name());
    }

    public void delete(CommandSender sender, String name) {
        if (!ready(sender, "coretp.warp.delete")) {
            return;
        }
        Warp w = plugin.data().warp(name);
        if (w == null || !plugin.data().removeWarp(name)) {
            plugin.messages().send(sender, "warp.not-found", "name", name);
            return;
        }
        plugin.messages().send(sender, "warp.deleted", "name", w.name());
    }

    public void teleport(Player p, String name) {
        if (!ready(p, "coretp.warp")) {
            return;
        }
        Warp w = plugin.data().warp(name);
        if (w == null) {
            plugin.messages().send(p, "warp.not-found", "name", name);
            return;
        }
        Location loc = w.pos().toLocation();
        if (loc == null) {
            plugin.messages().send(p, "general.world-unloaded");
            return;
        }
        plugin.teleports().start(p, p, Module.WARP, Destination.fixed(loc), null);
    }
}
