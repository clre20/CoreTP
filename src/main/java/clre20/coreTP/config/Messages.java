package clre20.coreTP.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** messages.yml 的快取。變數以 Component 方式替換，玩家輸入的內容不會被當成顏色代碼解析。 */
public final class Messages {

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();

    private final YamlConfiguration yml;
    private final Component prefix;
    private DateTimeFormatter timeFormat;

    public Messages(JavaPlugin plugin) {
        yml = YamlFiles.load(plugin, "messages.yml");
        prefix = AMP.deserialize(raw("general.prefix"));
        try {
            timeFormat = DateTimeFormatter.ofPattern(raw("general.time-format")).withZone(ZoneId.systemDefault());
        } catch (IllegalArgumentException e) {
            timeFormat = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault());
        }
    }

    /** 原始字串；找不到時回傳內建預設值，再找不到回傳空字串。 */
    public String raw(String key) {
        String s = yml.getString(key);
        return s == null ? "" : s;
    }

    public Component text(String key, Object... vars) {
        return render(raw(key), vars);
    }

    /** 物品名稱與 Lore 用：取消預設斜體。 */
    public Component item(String key, Object... vars) {
        return render(raw(key), vars).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public List<Component> lore(String key, Object... vars) {
        List<String> lines = yml.getStringList(key);
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(render(line, vars).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        return out;
    }

    public List<String> rawList(String key) {
        return yml.getStringList(key);
    }

    /** 基岩版表單用的純字串（§ 顏色代碼）。 */
    public String plain(String key, Object... vars) {
        return SECTION.serialize(render(raw(key), vars));
    }

    /** 後台紀錄用的無顏色文字。 */
    public String console(String key, Object... vars) {
        return PlainTextComponentSerializer.plainText().serialize(render(raw(key), vars));
    }

    public void send(CommandSender to, String key, Object... vars) {
        String raw = raw(key);
        if (raw.isEmpty()) {
            return;
        }
        to.sendMessage(prefix.append(render(raw, vars)));
    }

    public Component prefixed(Component body) {
        return prefix.append(body);
    }

    public String time(long epochMillis) {
        return timeFormat.format(Instant.ofEpochMilli(epochMillis));
    }

    public Component render(String raw, Object... vars) {
        Component c = AMP.deserialize(raw);
        for (int i = 0; i + 1 < vars.length; i += 2) {
            String token = "{" + vars[i] + "}";
            if (!raw.contains(token)) {
                continue;
            }
            Object value = vars[i + 1];
            Component replacement = value instanceof Component comp ? comp : Component.text(String.valueOf(value));
            c = c.replaceText(b -> b.matchLiteral(token).replacement(replacement));
        }
        return c;
    }
}
