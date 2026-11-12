package clre20.coreTP.form;

import clre20.coreTP.CoreTP;
import clre20.coreTP.gui.MenuModel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** 基岩版原生表單。只有在確定 Floodgate 已安裝時才會被載入。 */
public final class BedrockForms {

    /** 表單按鈕太多基岩版會很難操作，超過就截斷。 */
    private static final int MAX_BUTTONS = 100;

    private BedrockForms() {
    }

    public static boolean isBedrock(UUID uuid) {
        return FloodgateApi.getInstance().isFloodgatePlayer(uuid);
    }

    public static void open(CoreTP plugin, Player player, MenuModel model) {
        List<MenuModel.Entry> all = model.entries();
        List<MenuModel.Entry> entries = all.size() > MAX_BUTTONS ? all.subList(0, MAX_BUTTONS) : all;
        Consumer<Player> back = model.back();

        SimpleForm.Builder form = SimpleForm.builder()
                .title(model.plainTitle())
                .content(plugin.messages().plain(entries.isEmpty() ? "form.empty" : "form.content"));
        for (MenuModel.Entry entry : entries) {
            form.button(entry.label().get());
        }
        if (back != null) {
            form.button(plugin.messages().plain("form.back"));
        }
        UUID id = player.getUniqueId();
        int count = entries.size();
        form.validResultHandler(response -> {
            int clicked = response.clickedButtonId();
            Consumer<Player> action = clicked >= 0 && clicked < count ? entries.get(clicked).action()
                    : clicked == count ? back : null;
            if (action == null || !plugin.isEnabled()) {
                return;
            }
            // 表單回應不一定在主執行緒，一律排回主執行緒
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    action.accept(p);
                }
            });
        });
        FloodgateApi.getInstance().sendForm(id, form.build());
    }
}
