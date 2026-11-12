package clre20.coreTP.gui;

import clre20.coreTP.CoreTP;
import clre20.coreTP.config.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.function.Consumer;

/** 把 MenuModel 畫成箱子介面。項目少時置中顯示，多時分頁。 */
public final class ChestRenderer {

    private static final int[][] CENTERED = {
            {}, {13}, {11, 15}, {11, 13, 15}, {10, 12, 14, 16}, {11, 12, 13, 14, 15},
            {10, 11, 12, 14, 15, 16}, {10, 11, 12, 13, 14, 15, 16}};
    private static final int PAGE_SIZE = 45;

    private final CoreTP plugin;
    private final NamespacedKey guiKey;

    public ChestRenderer(CoreTP plugin) {
        this.plugin = plugin;
        this.guiKey = new NamespacedKey(plugin, "gui");
    }

    public void open(Player player, MenuModel model, int page) {
        List<MenuModel.Entry> entries = model.entries();
        MenuHolder holder = new MenuHolder(plugin);
        Inventory inv;
        ItemStack filler = named(plugin.settings().filler, Component.empty(), null);

        if (entries.size() <= 7) {
            inv = Bukkit.createInventory(holder, 27, model.title());
            fill(inv, filler, 0, 27);
            int[] slots = CENTERED[entries.size()];
            for (int i = 0; i < slots.length; i++) {
                inv.setItem(slots[i], entries.get(i).icon().get());
                holder.action(slots[i], entries.get(i).action());
            }
            control(inv, holder, 22, model);
        } else {
            int pages = (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE;
            int current = Math.max(0, Math.min(page, pages - 1));
            inv = Bukkit.createInventory(holder, 54, model.title());
            fill(inv, filler, PAGE_SIZE, 54);
            int start = current * PAGE_SIZE;
            for (int i = 0; i < PAGE_SIZE && start + i < entries.size(); i++) {
                inv.setItem(i, entries.get(start + i).icon().get());
                holder.action(i, entries.get(start + i).action());
            }
            Messages msg = plugin.messages();
            List<Component> pageLore = msg.lore("gui.page-lore", "page", current + 1, "pages", pages);
            if (current > 0) {
                inv.setItem(45, named(plugin.settings().icon("previous", Material.ARROW), msg.item("gui.previous"), pageLore));
                holder.action(45, p -> open(p, model, current - 1));
            }
            if (current < pages - 1) {
                inv.setItem(53, named(plugin.settings().icon("next", Material.ARROW), msg.item("gui.next"), pageLore));
                holder.action(53, p -> open(p, model, current + 1));
            }
            control(inv, holder, 49, model);
        }
        holder.bind(inv);
        player.openInventory(inv);
    }

    /** 返回（有上一層時）或關閉按鈕。 */
    private void control(Inventory inv, MenuHolder holder, int slot, MenuModel model) {
        Messages msg = plugin.messages();
        Consumer<Player> back = model.back();
        if (back != null) {
            inv.setItem(slot, named(plugin.settings().icon("back-button", Material.OAK_SIGN), msg.item("gui.back-button"), null));
            holder.action(slot, back);
        } else {
            inv.setItem(slot, named(Material.BARRIER, msg.item("gui.close"), null));
            holder.action(slot, Player::closeInventory);
        }
    }

    private static void fill(Inventory inv, ItemStack item, int from, int to) {
        for (int i = from; i < to; i++) {
            inv.setItem(i, item);
        }
    }

    /** 建立帶有 CoreTP 標記（PDC coretp:gui）的介面物品。 */
    public ItemStack named(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (lore != null && !lore.isEmpty()) {
                meta.lore(lore);
            }
            meta.getPersistentDataContainer().set(guiKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }
}
