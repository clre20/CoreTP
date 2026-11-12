package clre20.coreTP.listener;

import clre20.coreTP.CoreTP;
import clre20.coreTP.Module;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.UUID;
import java.util.logging.Level;

public final class PlayerListener implements Listener {

    private final CoreTP plugin;

    public PlayerListener(CoreTP plugin) {
        this.plugin = plugin;
    }

    /** 登入前在非同步執行緒讀取資料，主執行緒不碰硬碟。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            plugin.data().preLogin(event.getUniqueId(), event.getName());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "無法載入玩家資料：" + event.getName(), e);
            // 寧可請玩家重新登入，也不要用空白資料蓋掉原本的資料
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    plugin.messages().text("general.data-load-failed"));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.data().join(player);
        if (plugin.settings().updateNotifyOnJoin && player.hasPermission("coretp.version")) {
            UUID id = player.getUniqueId();
            Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.updates().notifyIfOutdated(id), 60L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        plugin.teleports().quit(id);
        plugin.tpa().quit(id);
        plugin.data().quit(id);
    }

    /** 高頻事件：沒有人在等待傳送時第一行就返回。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (plugin.teleports().idle() || !event.hasChangedBlock()) {
            return;
        }
        plugin.teleports().onMove(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (plugin.teleports().idle() || !(event.getEntity() instanceof Player player)) {
            return;
        }
        plugin.teleports().onDamage(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!plugin.settings().module(Module.BACK).enabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (plugin.teleports().isSelfTeleporting(player.getUniqueId())) {
            // CoreTP 自己發起的傳送一定記錄出發點
            plugin.backs().record(player, event.getFrom(), false, true);
        } else if (plugin.settings().backCauses.contains(event.getCause())) {
            plugin.backs().record(player, event.getFrom(), false, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.settings().module(Module.BACK).enabled() || !plugin.settings().backRecordDeath) {
            return;
        }
        plugin.backs().record(event.getEntity(), event.getEntity().getLocation(), true, true);
    }
}
