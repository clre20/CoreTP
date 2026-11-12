package clre20.coreTP.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 一個選單的內容，與顯示方式無關：Java 版畫成箱子介面，基岩版畫成表單。
 * 動作只接收點擊的玩家，不會把 Player 存在選單裡。
 */
public final class MenuModel {

    /** 圖示與文字用到時才建立：分頁只會建立當頁的物品。 */
    public record Entry(Supplier<ItemStack> icon, Supplier<String> label, Consumer<Player> action) {
    }

    private final Component title;
    private final String plainTitle;
    private final List<Entry> entries = new ArrayList<>();
    private @Nullable Consumer<Player> back;

    public MenuModel(Component title, String plainTitle) {
        this.title = title;
        this.plainTitle = plainTitle;
    }

    public MenuModel add(Supplier<ItemStack> icon, Supplier<String> label, Consumer<Player> action) {
        entries.add(new Entry(icon, label, action));
        return this;
    }

    public MenuModel back(Consumer<Player> back) {
        this.back = back;
        return this;
    }

    public Component title() {
        return title;
    }

    public String plainTitle() {
        return plainTitle;
    }

    public List<Entry> entries() {
        return entries;
    }

    public @Nullable Consumer<Player> back() {
        return back;
    }
}
