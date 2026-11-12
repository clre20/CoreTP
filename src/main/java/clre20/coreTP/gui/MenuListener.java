package clre20.coreTP.gui;

import clre20.coreTP.CoreTP;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.util.UUID;
import java.util.function.Consumer;

/** 箱子介面的點擊處理：一律取消原始事件，只接受左鍵。 */
public final class MenuListener implements Listener {

    private static final long CLICK_INTERVAL_NANOS = 150_000_000L;

    private final CoreTP plugin;

    public MenuListener(CoreTP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof MenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClick() != ClickType.LEFT || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) {
            return;
        }
        Consumer<Player> action = holder.action(slot);
        if (action == null) {
            return;
        }
        long now = System.nanoTime();
        if (now - holder.lastClick < CLICK_INTERVAL_NANOS) {
            return; // 連點保護
        }
        holder.lastClick = now;
        // 下一個 tick 再執行，避免在點擊事件中開關介面
        UUID id = player.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                action.accept(p);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    /** 插件停用或重載時關閉所有 CoreTP 介面。 */
    public static void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof MenuHolder) {
                p.closeInventory();
            }
        }
    }
}
