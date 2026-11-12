package clre20.coreTP.module;

import clre20.coreTP.CoreTP;
import clre20.coreTP.Module;
import clre20.coreTP.storage.DataStore;
import clre20.coreTP.storage.Home;
import clre20.coreTP.storage.PlayerData;
import clre20.coreTP.storage.PublicHome;
import clre20.coreTP.teleport.TeleportService.Destination;
import clre20.coreTP.util.Names;
import clre20.coreTP.util.Pos;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class HomeService {

    private static final String LIMIT_PREFIX = "coretp.home.limit.";

    private final CoreTP plugin;

    public HomeService(CoreTP plugin) {
        this.plugin = plugin;
    }

    /** 通過權限與功能開關檢查才回傳資料，否則已經告知玩家。 */
    private @Nullable PlayerData ready(Player p, String permission) {
        if (!p.hasPermission(permission)) {
            plugin.messages().send(p, "general.no-permission");
            return null;
        }
        if (!plugin.settings().module(Module.HOME).enabled()) {
            plugin.messages().send(p, "general.module-disabled");
            return null;
        }
        PlayerData data = plugin.data().get(p.getUniqueId());
        if (data == null) {
            plugin.messages().send(p, "general.data-not-loaded");
        }
        return data;
    }

    public void set(Player p, String name) {
        PlayerData data = ready(p, "coretp.home.set");
        if (data == null) {
            return;
        }
        int maxLength = plugin.settings().homeNameLength;
        if (!Names.valid(name, maxLength)) {
            plugin.messages().send(p, "general.invalid-name", "max", maxLength);
            return;
        }
        if (plugin.settings().module(Module.HOME).worldDisabled(p.getWorld().getName())) {
            plugin.messages().send(p, "home.world-disabled");
            return;
        }
        Pos pos = Pos.of(p.getLocation());
        if (pos == null) {
            plugin.messages().send(p, "teleport.failed");
            return;
        }
        String key = DataStore.key(name);
        Home before = data.homes.get(key);
        if (before == null) {
            int limit = limit(p);
            if (data.homes.size() >= limit) {
                plugin.messages().send(p, "home.limit", "limit", limit);
                return;
            }
        }
        Home after = before == null
                ? new Home(name, pos, false, System.currentTimeMillis())
                : new Home(before.name(), pos, before.isPublic(), before.created());
        data.homes.put(key, after);
        plugin.data().homeChanged(data, before, after);
        plugin.messages().send(p, before == null ? "home.set" : "home.updated", "name", after.name());
    }

    public void delete(Player p, String name) {
        PlayerData data = ready(p, "coretp.home.delete");
        if (data == null) {
            return;
        }
        Home removed = data.homes.remove(DataStore.key(name));
        if (removed == null) {
            plugin.messages().send(p, "home.not-found", "name", name);
            return;
        }
        plugin.data().homeChanged(data, removed, null);
        plugin.messages().send(p, "home.deleted", "name", removed.name());
    }

    public void teleport(Player p, String name) {
        PlayerData data = ready(p, "coretp.home");
        if (data == null) {
            return;
        }
        Home home = data.homes.get(DataStore.key(name));
        if (home == null) {
            plugin.messages().send(p, "home.not-found", "name", name);
            return;
        }
        go(p, home.pos());
    }

    public void togglePublic(Player p, String name) {
        PlayerData data = ready(p, "coretp.home.public");
        if (data == null) {
            return;
        }
        String key = DataStore.key(name);
        Home before = data.homes.get(key);
        if (before == null) {
            plugin.messages().send(p, "home.not-found", "name", name);
            return;
        }
        Home after = new Home(before.name(), before.pos(), !before.isPublic(), before.created());
        data.homes.put(key, after);
        plugin.data().homeChanged(data, before, after);
        plugin.messages().send(p, after.isPublic() ? "home.now-public" : "home.now-private", "name", after.name());
    }

    /** 前往其他玩家公開的家。每次都重新查索引，改成私人或刪除後就不能去。 */
    public void visit(Player p, UUID owner, String name) {
        if (!p.hasPermission("coretp.home.visit")) {
            plugin.messages().send(p, "general.no-permission");
            return;
        }
        if (!plugin.settings().module(Module.HOME).enabled()) {
            plugin.messages().send(p, "general.module-disabled");
            return;
        }
        PublicHome home = plugin.data().publicHome(owner, name);
        if (home == null) {
            plugin.messages().send(p, "home.public-gone");
            return;
        }
        go(p, home.pos());
    }

    private void go(Player p, Pos pos) {
        Location loc = pos.toLocation();
        if (loc == null) {
            plugin.messages().send(p, "general.world-unloaded");
            return;
        }
        plugin.teleports().start(p, p, Module.HOME, Destination.fixed(loc), null);
    }

    /** 可設定的家數量：預設值與 coretp.home.limit.<數量> 權限取最大，再受 max-limit 限制。 */
    public int limit(Player p) {
        int best = plugin.settings().homeDefaultLimit;
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            if (!info.getValue()) {
                continue;
            }
            String perm = info.getPermission();
            if (perm.length() > LIMIT_PREFIX.length() && perm.regionMatches(true, 0, LIMIT_PREFIX, 0, LIMIT_PREFIX.length())) {
                try {
                    best = Math.max(best, Integer.parseInt(perm.substring(LIMIT_PREFIX.length())));
                } catch (NumberFormatException ignored) {
                    // 不是數字的節點略過
                }
            }
        }
        return Math.min(best, plugin.settings().homeMaxLimit);
    }

    public List<String> names(Player p) {
        PlayerData data = plugin.data().get(p.getUniqueId());
        List<String> out = new ArrayList<>();
        if (data != null) {
            for (Home h : data.homes.values()) {
                out.add(h.name());
            }
        }
        return out;
    }
}
