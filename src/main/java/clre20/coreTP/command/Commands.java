package clre20.coreTP.command;

import clre20.coreTP.CoreTP;
import clre20.coreTP.Module;
import clre20.coreTP.module.TpaManager;
import clre20.coreTP.storage.PlayerData;
import clre20.coreTP.storage.Warp;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 所有玩家用的短指令。權限在各服務方法內檢查，這裡只負責解析參數。 */
public final class Commands implements TabExecutor {

    private static final int MAX_SUGGESTIONS = 50;
    private static final String DEFAULT_HOME = "home";

    private final CoreTP plugin;

    public Commands(CoreTP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("delwarp")) {
            // 唯一可以從後台使用的指令
            if (args.length < 1) {
                plugin.messages().send(sender, "warp.delete-usage");
            } else {
                plugin.warps().delete(sender, args[0]);
            }
            return true;
        }
        if (!(sender instanceof Player p)) {
            plugin.messages().send(sender, "general.only-players");
            return true;
        }
        switch (name) {
            case "tpa", "tpahere" -> {
                if (args.length < 1) {
                    plugin.menus().openPlayers(p);
                } else {
                    plugin.tpa().send(p, args[0], name.equals("tpahere"));
                }
            }
            case "tpaccept" -> plugin.tpa().accept(p, args.length > 0 ? args[0] : null);
            case "tpdeny" -> plugin.tpa().deny(p, args.length > 0 ? args[0] : null);
            case "tpcancel" -> plugin.tpa().cancel(p);
            case "tptoggle" -> plugin.tpa().toggle(p);
            case "home" -> home(p, args);
            case "sethome" -> plugin.homes().set(p, args.length > 0 ? args[0] : DEFAULT_HOME);
            case "delhome" -> {
                if (args.length < 1) {
                    plugin.menus().openHomes(p);
                } else {
                    plugin.homes().delete(p, args[0]);
                }
            }
            case "homes" -> {
                if (args.length > 0 && args[0].equalsIgnoreCase("public")) {
                    plugin.menus().openPublicHomes(p);
                } else {
                    plugin.menus().openHomes(p);
                }
            }
            case "warp" -> {
                if (args.length < 1) {
                    plugin.menus().openWarps(p);
                } else {
                    plugin.warps().teleport(p, args[0]);
                }
            }
            case "warps" -> plugin.menus().openWarps(p);
            case "setwarp" -> {
                if (args.length < 1) {
                    plugin.messages().send(p, "warp.set-usage");
                } else {
                    plugin.warps().set(p, args[0], args.length > 1 ? args[1] : null);
                }
            }
            case "back" -> plugin.backs().teleport(p, 0);
            case "eback" -> plugin.menus().openBack(p);
            case "rtp" -> plugin.rtp().rtp(p);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** /home：有指定名稱就去那個家；沒指定時只有一個家就直接去，否則開選單。 */
    private void home(Player p, String[] args) {
        if (args.length > 0) {
            plugin.homes().teleport(p, args[0]);
            return;
        }
        PlayerData data = plugin.data().get(p.getUniqueId());
        if (data != null && data.homes.size() == 1) {
            plugin.homes().teleport(p, data.homes.values().iterator().next().name());
        } else if (data != null && data.homes.isEmpty() && p.hasPermission("coretp.home")
                && plugin.settings().module(Module.HOME).enabled()) {
            plugin.messages().send(p, "home.none");
        } else {
            plugin.menus().openHomes(p);
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                                      @NotNull String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (args.length == 0) {
            return List.of();
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (name.equals("delwarp") && args.length == 1 && sender.hasPermission("coretp.warp.delete")) {
            for (Warp w : plugin.data().warps()) {
                add(out, w.name(), typed);
            }
            return out;
        }
        if (!(sender instanceof Player p)) {
            return out;
        }
        switch (name) {
            case "tpa", "tpahere" -> {
                if (args.length == 1 && p.hasPermission("coretp." + name)) {
                    for (Player other : Bukkit.getOnlinePlayers()) {
                        if (other != p && p.canSee(other)) {
                            add(out, other.getName(), typed);
                        }
                    }
                }
            }
            case "tpaccept", "tpdeny" -> {
                if (args.length == 1 && p.hasPermission("coretp.tpa.respond")) {
                    for (TpaManager.Request r : plugin.tpa().incoming(p.getUniqueId())) {
                        Player from = Bukkit.getPlayer(r.from());
                        if (from != null) {
                            add(out, from.getName(), typed);
                        }
                    }
                }
            }
            case "home", "delhome" -> {
                if (args.length == 1 && p.hasPermission(name.equals("home") ? "coretp.home" : "coretp.home.delete")) {
                    for (String home : plugin.homes().names(p)) {
                        add(out, home, typed);
                    }
                }
            }
            case "homes" -> {
                if (args.length == 1 && p.hasPermission("coretp.home.visit")) {
                    add(out, "public", typed);
                }
            }
            case "warp" -> {
                if (args.length == 1 && p.hasPermission("coretp.warp")) {
                    for (Warp w : plugin.data().warps()) {
                        add(out, w.name(), typed);
                    }
                }
            }
            case "setwarp" -> {
                if (p.hasPermission("coretp.warp.set")) {
                    if (args.length == 1) {
                        for (Warp w : plugin.data().warps()) {
                            add(out, w.name(), typed);
                        }
                    } else if (args.length == 2 && !typed.isEmpty()) {
                        for (Material m : Material.values()) {
                            if (out.size() >= MAX_SUGGESTIONS) {
                                break;
                            }
                            if (m.isItem() && !m.isAir() && !m.isLegacy()) {
                                add(out, m.name().toLowerCase(Locale.ROOT), typed);
                            }
                        }
                    }
                }
            }
            default -> {
            }
        }
        return out;
    }

    private static void add(List<String> out, String candidate, String typed) {
        if (out.size() < MAX_SUGGESTIONS && candidate.toLowerCase(Locale.ROOT).startsWith(typed)) {
            out.add(candidate);
        }
    }
}
