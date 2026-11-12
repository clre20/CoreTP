package clre20.coreTP.command;

import clre20.coreTP.CoreTP;
import clre20.coreTP.config.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /coretp（/ctp）：主選單、help、reload、version。 */
public final class CoreCommand implements TabExecutor {

    private final CoreTP plugin;

    public CoreCommand(CoreTP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        Messages msg = plugin.messages();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> {
                if (sender instanceof Player p) {
                    plugin.menus().openMain(p);
                } else {
                    help(sender);
                }
            }
            case "reload" -> {
                if (!sender.hasPermission("coretp.reload")) {
                    msg.send(sender, "general.no-permission");
                    return true;
                }
                plugin.reload();
                plugin.messages().send(sender, "general.reloaded");
            }
            case "version" -> {
                if (!sender.hasPermission("coretp.version")) {
                    msg.send(sender, "general.no-permission");
                    return true;
                }
                msg.send(sender, "update.checking");
                plugin.updates().report(sender);
            }
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender sender) {
        if (!sender.hasPermission("coretp.use")) {
            plugin.messages().send(sender, "general.no-permission");
            return;
        }
        Messages msg = plugin.messages();
        sender.sendMessage(msg.text("help.header", "version", plugin.getPluginMeta().getVersion()));
        for (String line : msg.rawList("help.lines")) {
            sender.sendMessage(msg.render(line));
        }
        if (sender.hasPermission("coretp.reload") || sender.hasPermission("coretp.warp.set")) {
            for (String line : msg.rawList("help.admin-lines")) {
                sender.sendMessage(msg.render(line));
            }
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                                      @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length != 1) {
            return out;
        }
        String typed = args[0].toLowerCase(Locale.ROOT);
        if (sender.hasPermission("coretp.use") && "help".startsWith(typed)) {
            out.add("help");
        }
        if (sender.hasPermission("coretp.reload") && "reload".startsWith(typed)) {
            out.add("reload");
        }
        if (sender.hasPermission("coretp.version") && "version".startsWith(typed)) {
            out.add("version");
        }
        return out;
    }
}
