package clre20.coreTP.gui;

import clre20.coreTP.CoreTP;
import clre20.coreTP.Module;
import clre20.coreTP.config.Messages;
import clre20.coreTP.form.BedrockForms;
import clre20.coreTP.module.TpaManager;
import clre20.coreTP.storage.BackEntry;
import clre20.coreTP.storage.Home;
import clre20.coreTP.storage.PlayerData;
import clre20.coreTP.storage.PublicHome;
import clre20.coreTP.storage.Warp;
import clre20.coreTP.util.Pos;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 所有選單的內容。每個開啟方法都會重新檢查權限與功能開關，
 * 選單內的動作最後都呼叫與指令相同的服務方法（那裡會再檢查一次權限）。
 */
public final class Menus {

    private final CoreTP plugin;
    private final ChestRenderer chest;
    private final boolean floodgate;

    public Menus(CoreTP plugin) {
        this.plugin = plugin;
        this.chest = new ChestRenderer(plugin);
        this.floodgate = Bukkit.getPluginManager().getPlugin("floodgate") != null;
    }

    private void show(Player p, MenuModel model) {
        if (floodgate && BedrockForms.isBedrock(p.getUniqueId())) {
            BedrockForms.open(plugin, p, model);
        } else {
            chest.open(p, model, 0);
        }
    }

    // ------------------------------------------------------------ 主選單

    public void openMain(Player p) {
        if (!p.hasPermission("coretp.use")) {
            plugin.messages().send(p, "general.no-permission");
            return;
        }
        MenuModel m = model("gui.main.title");
        PlayerData data = plugin.data().get(p.getUniqueId());
        if (on(Module.HOME) && p.hasPermission("coretp.home")) {
            int count = data == null ? 0 : data.homes.size();
            int limit = plugin.homes().limit(p);
            button(m, "homes", Material.RED_BED, "gui.main.homes", "gui.main.homes-lore", this::openHomes,
                    "count", count, "limit", limit);
        }
        if (on(Module.HOME) && p.hasPermission("coretp.home.visit")) {
            button(m, "public-homes", Material.LIME_BED, "gui.main.public-homes", "gui.main.public-homes-lore",
                    this::openPublicHomes);
        }
        if (on(Module.WARP) && p.hasPermission("coretp.warp")) {
            button(m, "warps", Material.BEACON, "gui.main.warps", "gui.main.warps-lore", this::openWarps);
        }
        if (on(Module.TPA) && (p.hasPermission("coretp.tpa") || p.hasPermission("coretp.tpahere"))) {
            button(m, "players", Material.PLAYER_HEAD, "gui.main.players", "gui.main.players-lore", this::openPlayers);
        }
        if (on(Module.TPA) && p.hasPermission("coretp.tpa.respond")) {
            button(m, "requests", Material.WRITABLE_BOOK, "gui.main.requests", "gui.main.requests-lore",
                    this::openRequests, "count", plugin.tpa().incomingCount(p.getUniqueId()));
        }
        if (on(Module.BACK) && p.hasPermission("coretp.back.history")) {
            button(m, "back", Material.RECOVERY_COMPASS, "gui.main.back", "gui.main.back-lore", this::openBack);
        }
        if (on(Module.RTP) && p.hasPermission("coretp.rtp")) {
            button(m, "rtp", Material.ENDER_PEARL, "gui.main.rtp", "gui.main.rtp-lore", pl -> {
                pl.closeInventory();
                plugin.rtp().rtp(pl);
            });
        }
        show(p, m);
    }

    // ------------------------------------------------------------ 家

    public void openHomes(Player p) {
        PlayerData data = guard(p, Module.HOME, "coretp.home");
        if (data == null) {
            return;
        }
        Messages msg = plugin.messages();
        MenuModel m = model("gui.homes.title").back(this::openMain);
        for (Home h : List.copyOf(data.homes.values())) {
            String name = h.name();
            Pos pos = h.pos();
            Supplier<ItemStack> icon = () -> chest.named(
                    plugin.settings().icon(h.isPublic() ? "home-public" : "home-private",
                            h.isPublic() ? Material.LIME_BED : Material.RED_BED),
                    msg.item("gui.homes.item", "name", name),
                    msg.lore("gui.homes.item-lore", "state", state(h.isPublic()), "world", pos.world(),
                            "x", pos.blockX(), "y", pos.blockY(), "z", pos.blockZ()));
            m.add(icon, () -> msg.plain("form.home", "name", name, "state", state(h.isPublic())),
                    pl -> openHomeDetail(pl, name));
        }
        show(p, m);
    }

    public void openHomeDetail(Player p, String name) {
        PlayerData data = guard(p, Module.HOME, "coretp.home");
        if (data == null) {
            return;
        }
        Home h = data.homes.get(clre20.coreTP.storage.DataStore.key(name));
        if (h == null) {
            plugin.messages().send(p, "home.not-found", "name", name);
            openHomes(p);
            return;
        }
        MenuModel m = model("gui.home-detail.title", "name", h.name()).back(this::openHomes);
        button(m, "teleport", Material.ENDER_PEARL, "gui.home-detail.teleport", null, pl -> {
            pl.closeInventory();
            plugin.homes().teleport(pl, name);
        });
        if (p.hasPermission("coretp.home.public")) {
            if (h.isPublic()) {
                button(m, "toggle-public", Material.OAK_DOOR, "gui.home-detail.make-private",
                        "gui.home-detail.make-private-lore", pl -> {
                            plugin.homes().togglePublic(pl, name);
                            openHomeDetail(pl, name);
                        });
            } else {
                button(m, "toggle-public", Material.OAK_DOOR, "gui.home-detail.make-public",
                        "gui.home-detail.make-public-lore", pl -> {
                            plugin.homes().togglePublic(pl, name);
                            openHomeDetail(pl, name);
                        });
            }
        }
        if (p.hasPermission("coretp.home.delete")) {
            button(m, "delete", Material.BARRIER, "gui.home-detail.delete", null, pl -> openHomeDelete(pl, name));
        }
        show(p, m);
    }

    private void openHomeDelete(Player p, String name) {
        MenuModel m = model("gui.home-delete.title", "name", name).back(pl -> openHomeDetail(pl, name));
        button(m, "confirm", Material.LIME_CONCRETE, "gui.home-delete.confirm", null, pl -> {
            plugin.homes().delete(pl, name);
            openHomes(pl);
        });
        button(m, "cancel", Material.RED_CONCRETE, "gui.home-delete.cancel", null, pl -> openHomeDetail(pl, name));
        show(p, m);
    }

    public void openPublicHomes(Player p) {
        if (guard(p, Module.HOME, "coretp.home.visit") == null) {
            return;
        }
        Messages msg = plugin.messages();
        MenuModel m = model("gui.public-homes.title").back(this::openMain);
        for (PublicHome h : plugin.data().publicHomes()) {
            Pos pos = h.pos();
            Supplier<ItemStack> icon = () -> chest.named(plugin.settings().icon("home-public", Material.LIME_BED),
                    msg.item("gui.public-homes.item", "name", h.name()),
                    msg.lore("gui.public-homes.item-lore", "owner", h.ownerName(), "world", pos.world(),
                            "x", pos.blockX(), "y", pos.blockY(), "z", pos.blockZ()));
            UUID owner = h.owner();
            String name = h.name();
            m.add(icon, () -> msg.plain("form.public-home", "name", name, "owner", h.ownerName()), pl -> {
                pl.closeInventory();
                plugin.homes().visit(pl, owner, name);
            });
        }
        show(p, m);
    }

    // ------------------------------------------------------------ 地標

    public void openWarps(Player p) {
        if (!allowed(p, Module.WARP, "coretp.warp")) {
            return;
        }
        Messages msg = plugin.messages();
        MenuModel m = model("gui.warps.title").back(this::openMain);
        for (Warp w : List.copyOf(plugin.data().warps())) {
            Pos pos = w.pos();
            String name = w.name();
            Supplier<ItemStack> icon = () -> {
                Material mat = w.icon().isEmpty() ? null : Material.matchMaterial(w.icon());
                if (mat == null || !mat.isItem() || mat.isAir()) {
                    mat = plugin.settings().icon("warp-default", Material.LODESTONE);
                }
                return chest.named(mat, msg.item("gui.warps.item", "name", name),
                        msg.lore("gui.warps.item-lore", "world", pos.world(),
                                "x", pos.blockX(), "y", pos.blockY(), "z", pos.blockZ()));
            };
            m.add(icon, () -> msg.plain("form.warp", "name", name, "world", pos.world(),
                    "x", pos.blockX(), "y", pos.blockY(), "z", pos.blockZ()), pl -> {
                pl.closeInventory();
                plugin.warps().teleport(pl, name);
            });
        }
        show(p, m);
    }

    // ------------------------------------------------------------ 玩家互傳

    public void openPlayers(Player p) {
        if (!on(Module.TPA)) {
            plugin.messages().send(p, "general.module-disabled");
            return;
        }
        if (!p.hasPermission("coretp.tpa") && !p.hasPermission("coretp.tpahere")) {
            plugin.messages().send(p, "general.no-permission");
            return;
        }
        Messages msg = plugin.messages();
        MenuModel m = model("gui.players.title").back(this::openMain);
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getUniqueId().equals(p.getUniqueId()) || !p.canSee(other)) {
                continue;
            }
            UUID id = other.getUniqueId();
            String name = other.getName();
            m.add(() -> head(id, msg.item("gui.players.item", "player", name), msg.lore("gui.players.item-lore")),
                    () -> msg.plain("form.player", "player", name), pl -> openTpaType(pl, id));
        }
        show(p, m);
    }

    private void openTpaType(Player p, UUID targetId) {
        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !p.canSee(target)) {
            plugin.messages().send(p, "tpa.target-offline");
            openPlayers(p);
            return;
        }
        String name = target.getName();
        MenuModel m = model("gui.tpa-type.title", "player", name).back(this::openPlayers);
        if (p.hasPermission("coretp.tpa")) {
            button(m, "tpa", Material.ENDER_PEARL, "gui.tpa-type.tpa", null, pl -> {
                pl.closeInventory();
                plugin.tpa().send(pl, name, false);
            });
        }
        if (p.hasPermission("coretp.tpahere")) {
            button(m, "tpahere", Material.ENDER_EYE, "gui.tpa-type.tpahere", null, pl -> {
                pl.closeInventory();
                plugin.tpa().send(pl, name, true);
            });
        }
        show(p, m);
    }

    public void openRequests(Player p) {
        if (!allowed(p, Module.TPA, "coretp.tpa.respond")) {
            return;
        }
        Messages msg = plugin.messages();
        MenuModel m = model("gui.requests.title").back(this::openMain);
        for (TpaManager.Request r : plugin.tpa().incoming(p.getUniqueId())) {
            Player from = Bukkit.getPlayer(r.from());
            if (from == null) {
                continue;
            }
            UUID id = r.from();
            String name = from.getName();
            boolean here = r.here();
            m.add(() -> head(id, msg.item("gui.requests.item", "player", name),
                            msg.lore(here ? "gui.requests.item-lore-here" : "gui.requests.item-lore-tpa")),
                    () -> msg.plain(here ? "form.request-here" : "form.request-tpa", "player", name),
                    pl -> openRequestConfirm(pl, name));
        }
        show(p, m);
    }

    private void openRequestConfirm(Player p, String fromName) {
        MenuModel m = model("gui.request-confirm.title", "player", fromName).back(this::openRequests);
        button(m, "confirm", Material.LIME_CONCRETE, "gui.request-confirm.accept", null, pl -> {
            pl.closeInventory();
            plugin.tpa().accept(pl, fromName);
        });
        button(m, "cancel", Material.RED_CONCRETE, "gui.request-confirm.deny", null, pl -> {
            pl.closeInventory();
            plugin.tpa().deny(pl, fromName);
        });
        show(p, m);
    }

    // ------------------------------------------------------------ 返回

    public void openBack(Player p) {
        PlayerData data = guard(p, Module.BACK, "coretp.back.history");
        if (data == null) {
            return;
        }
        if (data.back.isEmpty()) {
            plugin.messages().send(p, "back.none");
            return;
        }
        Messages msg = plugin.messages();
        MenuModel m = model("gui.back.title").back(this::openMain);
        List<BackEntry> list = List.copyOf(data.back);
        for (int i = 0; i < list.size(); i++) {
            BackEntry e = list.get(i);
            Pos pos = e.pos();
            int index = i;
            Object[] vars = {"index", i + 1, "type", msg.text(e.death() ? "back.type-death" : "back.type-teleport"),
                    "world", pos.world(), "x", pos.blockX(), "y", pos.blockY(), "z", pos.blockZ(),
                    "time", msg.time(e.timestamp())};
            m.add(() -> chest.named(
                            plugin.settings().icon(e.death() ? "back-death" : "back-teleport",
                                    e.death() ? Material.SKELETON_SKULL : Material.COMPASS),
                            msg.item("gui.back.item", vars), msg.lore("gui.back.item-lore", vars)),
                    () -> msg.plain("form.back-entry", vars), pl -> {
                        pl.closeInventory();
                        // 以時間戳確認還是同一筆，清單在選單開著時可能已經變動
                        PlayerData now = plugin.data().get(pl.getUniqueId());
                        if (now != null && index < now.back.size() && now.back.get(index).timestamp() == e.timestamp()) {
                            plugin.backs().teleport(pl, index);
                        } else {
                            plugin.messages().send(pl, "back.none");
                        }
                    });
        }
        show(p, m);
    }

    // ------------------------------------------------------------ 共用

    private boolean on(Module module) {
        return plugin.settings().module(module).enabled();
    }

    private boolean allowed(Player p, Module module, String permission) {
        if (!p.hasPermission(permission)) {
            plugin.messages().send(p, "general.no-permission");
            return false;
        }
        if (!on(module)) {
            plugin.messages().send(p, "general.module-disabled");
            return false;
        }
        return true;
    }

    private @Nullable PlayerData guard(Player p, Module module, String permission) {
        if (!allowed(p, module, permission)) {
            return null;
        }
        PlayerData data = plugin.data().get(p.getUniqueId());
        if (data == null) {
            plugin.messages().send(p, "general.data-not-loaded");
        }
        return data;
    }

    private MenuModel model(String titleKey, Object... vars) {
        return new MenuModel(plugin.messages().text(titleKey, vars), plugin.messages().plain(titleKey, vars));
    }

    private void button(MenuModel m, String iconKey, Material fallback, String nameKey, @Nullable String loreKey,
                        java.util.function.Consumer<Player> action, Object... vars) {
        Messages msg = plugin.messages();
        m.add(() -> chest.named(plugin.settings().icon(iconKey, fallback), msg.item(nameKey, vars),
                loreKey == null ? null : msg.lore(loreKey, vars)), () -> msg.plain(nameKey, vars), action);
    }

    private net.kyori.adventure.text.Component state(boolean isPublic) {
        return plugin.messages().text(isPublic ? "home.state-public" : "home.state-private");
    }

    /** 在線玩家的頭顱（已有材質資料，不會觸發額外的網路查詢）。 */
    private ItemStack head(UUID id, net.kyori.adventure.text.Component name, List<net.kyori.adventure.text.Component> lore) {
        Material configured = plugin.settings().icon("players", Material.PLAYER_HEAD);
        ItemStack item = chest.named(configured, name, lore);
        Player online = Bukkit.getPlayer(id);
        if (configured == Material.PLAYER_HEAD && online != null && item.getItemMeta() instanceof SkullMeta skull) {
            skull.setPlayerProfile(online.getPlayerProfile());
            item.setItemMeta(skull);
        }
        return item;
    }
}
