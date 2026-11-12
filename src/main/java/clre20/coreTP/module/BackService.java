package clre20.coreTP.module;

import clre20.coreTP.CoreTP;
import clre20.coreTP.Module;
import clre20.coreTP.storage.BackEntry;
import clre20.coreTP.storage.PlayerData;
import clre20.coreTP.teleport.TeleportService.Destination;
import clre20.coreTP.util.Pos;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;

/** 返回功能：記錄傳送前與死亡位置（沿用 Cback 的過濾方式），只寫記憶體並標記髒資料。 */
public final class BackService {

    private final CoreTP plugin;

    public BackService(CoreTP plugin) {
        this.plugin = plugin;
    }

    public void record(Player player, Location location, boolean death, boolean force) {
        PlayerData data = plugin.data().get(player.getUniqueId());
        Pos pos = Pos.of(location);
        if (data == null || pos == null) {
            return;
        }
        long now = System.currentTimeMillis();
        List<BackEntry> list = data.back;
        if (!force && !list.isEmpty()) {
            BackEntry last = list.get(0);
            if (now - last.timestamp() < plugin.settings().backSaveCooldownMs) {
                return; // 太密集，多半是基岩版延遲或防作弊拉回
            }
            Pos lp = last.pos();
            if (lp.world().equals(pos.world())) {
                double dx = lp.x() - pos.x();
                double dy = lp.y() - pos.y();
                double dz = lp.z() - pos.z();
                if (dx * dx + dy * dy + dz * dz < plugin.settings().backMinDistanceSq) {
                    return;
                }
            }
        }
        list.add(0, new BackEntry(pos, now, death));
        int max = plugin.settings().backMaxHistory;
        while (list.size() > max) {
            list.remove(list.size() - 1);
        }
        data.dirty = true;
    }

    public void teleport(Player p, int index) {
        if (!p.hasPermission("coretp.back")) {
            plugin.messages().send(p, "general.no-permission");
            return;
        }
        if (!plugin.settings().module(Module.BACK).enabled()) {
            plugin.messages().send(p, "general.module-disabled");
            return;
        }
        PlayerData data = plugin.data().get(p.getUniqueId());
        if (data == null) {
            plugin.messages().send(p, "general.data-not-loaded");
            return;
        }
        if (index < 0 || index >= data.back.size()) {
            plugin.messages().send(p, "back.none");
            return;
        }
        Location loc = data.back.get(index).pos().toLocation();
        if (loc == null) {
            plugin.messages().send(p, "general.world-unloaded");
            return;
        }
        plugin.teleports().start(p, p, Module.BACK, Destination.fixed(loc), null);
    }
}
