package clre20.coreTP.command;

import clre20.coreTP.config.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** 封鎖模式（舊的 Cback 還裝著）：所有指令只提示，不執行任何動作。 */
public final class BlockedCommand implements TabExecutor {

    private final Messages messages;

    public BlockedCommand(Messages messages) {
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        messages.send(sender, "general.blocked");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                                      @NotNull String[] args) {
        return List.of();
    }
}
