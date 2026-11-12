package clre20.coreTP.gui;

import clre20.coreTP.CoreTP;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * CoreTP 箱子介面的簽署：其他插件可以用
 * {@code inventory.getHolder(false) instanceof MenuHolder} 或 {@link #getPlugin()} 辨識來源。
 */
public final class MenuHolder implements InventoryHolder {

    private final CoreTP plugin;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();
    private Inventory inventory;
    long lastClick;

    MenuHolder(CoreTP plugin) {
        this.plugin = plugin;
    }

    void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    void action(int slot, Consumer<Player> action) {
        actions.put(slot, action);
    }

    Consumer<Player> action(int slot) {
        return actions.get(slot);
    }

    public Plugin getPlugin() {
        return plugin;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
