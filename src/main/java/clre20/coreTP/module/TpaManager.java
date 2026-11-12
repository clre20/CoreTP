package clre20.coreTP.module;

import clre20.coreTP.CoreTP;
import clre20.coreTP.Module;
import clre20.coreTP.config.Messages;
import clre20.coreTP.storage.PlayerData;
import clre20.coreTP.teleport.TeleportService.Destination;
import clre20.coreTP.teleport.TeleportService.Resolved;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 玩家互傳請求。每位玩家同時只能有一個送出的請求，所以請求總數不會超過在線人數；
 * 只存 UUID，玩家離線時全部清除。
 */
public final class TpaManager {

    public record Request(UUID from, UUID to, boolean here, BukkitTask expiry) {
    }

    private final CoreTP plugin;
    private final Map<UUID, Request> outgoing = new HashMap<>();
    private final Map<UUID, Map<UUID, Request>> incoming = new HashMap<>();
    /** 上次發送請求的時間，用來限制發送頻率；玩家離線時清除。 */
    private final Map<UUID, Long> lastSent = new HashMap<>();

    public TpaManager(CoreTP plugin) {
        this.plugin = plugin;
    }

    public void send(Player from, String targetName, boolean here) {
        Messages msg = plugin.messages();
        if (!from.hasPermission(here ? "coretp.tpahere" : "coretp.tpa")) {
            msg.send(from, "general.no-permission");
            return;
        }
        if (!plugin.settings().module(Module.TPA).enabled()) {
            msg.send(from, "general.module-disabled");
            return;
        }
        Player to = findVisible(from, targetName);
        if (to == null) {
            msg.send(from, "general.player-not-found", "player", clip(targetName));
            return;
        }
        if (to.getUniqueId().equals(from.getUniqueId())) {
            msg.send(from, "tpa.self");
            return;
        }
        if (outgoing.containsKey(from.getUniqueId())) {
            msg.send(from, "tpa.pending-exists");
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastSent.get(from.getUniqueId());
        long wait = last == null ? 0 : last + plugin.settings().tpaIntervalSeconds * 1000L - now;
        if (wait > 0) {
            msg.send(from, "tpa.too-fast", "seconds", (wait + 999) / 1000);
            return;
        }
        PlayerData targetData = plugin.data().get(to.getUniqueId());
        if (targetData != null && targetData.tpaOff && !from.hasPermission("coretp.bypass.toggle")) {
            msg.send(from, "tpa.toggled-off", "player", to.getName());
            return;
        }
        Player mover = here ? to : from;
        Player other = here ? from : to;
        if (plugin.teleports().isTeleporting(from.getUniqueId())) {
            msg.send(from, "teleport.already");
            return;
        }
        // 只把結果告訴發出請求的人，避免用來騷擾對方
        if (!plugin.teleports().precheck(from, from, Module.TPA, null)
                || !precheckWorlds(from, mover, other.getLocation())) {
            return;
        }

        int timeout = plugin.settings().tpaTimeoutSeconds;
        UUID fromId = from.getUniqueId();
        UUID toId = to.getUniqueId();
        BukkitTask expiry = Bukkit.getScheduler().runTaskLater(plugin, () -> expire(fromId, toId), timeout * 20L);
        Request request = new Request(fromId, toId, here, expiry);
        outgoing.put(fromId, request);
        lastSent.put(fromId, now);
        incoming.computeIfAbsent(toId, k -> new LinkedHashMap<>()).put(fromId, request);

        msg.send(from, here ? "tpa.sent-here" : "tpa.sent", "player", to.getName(), "seconds", timeout);
        msg.send(to, here ? "tpa.received-here" : "tpa.received", "player", from.getName());
        String buttons = msg.raw("tpa.buttons");
        if (!buttons.isEmpty()) {
            Component accept = msg.text("tpa.button-accept")
                    .clickEvent(ClickEvent.runCommand("/tpaccept " + from.getName()))
                    .hoverEvent(HoverEvent.showText(msg.text("tpa.button-accept-hover", "player", from.getName())));
            Component deny = msg.text("tpa.button-deny")
                    .clickEvent(ClickEvent.runCommand("/tpdeny " + from.getName()))
                    .hoverEvent(HoverEvent.showText(msg.text("tpa.button-deny-hover", "player", from.getName())));
            to.sendMessage(msg.prefixed(msg.render(buttons, "accept", accept, "deny", deny)));
        }
    }

    /** 世界停用的事前檢查，訊息只給發出請求的人。 */
    private boolean precheckWorlds(Player requester, Player mover, Location dest) {
        var s = plugin.settings().module(Module.TPA);
        if (s.worldDisabled(mover.getWorld().getName())) {
            plugin.messages().send(requester, "teleport.world-disabled-from");
            return false;
        }
        if (dest.getWorld() != null && s.worldDisabled(dest.getWorld().getName())) {
            plugin.messages().send(requester, "teleport.world-disabled-to");
            return false;
        }
        return true;
    }

    public void accept(Player target, @Nullable String fromName) {
        Request request = respond(target, fromName);
        if (request == null) {
            return;
        }
        Player from = Bukkit.getPlayer(request.from());
        if (from == null) {
            plugin.messages().send(target, "tpa.target-offline");
            return;
        }
        plugin.messages().send(target, "tpa.accepted", "player", from.getName());
        plugin.messages().send(from, "tpa.accepted-notify", "player", target.getName());

        Player mover = request.here() ? target : from;
        UUID otherId = request.here() ? from.getUniqueId() : target.getUniqueId();
        Location peek = (request.here() ? from : target).getLocation();
        plugin.teleports().start(mover, from, Module.TPA, new Destination() {
            @Override
            public Location peek() {
                return peek;
            }

            @Override
            public CompletableFuture<Resolved> resolve() {
                // 等待結束後才取對方當下的位置
                Player other = Bukkit.getPlayer(otherId);
                return CompletableFuture.completedFuture(other == null
                        ? Resolved.fail("tpa.target-offline") : Resolved.ok(other.getLocation()));
            }
        }, null);
    }

    public void deny(Player target, @Nullable String fromName) {
        Request request = respond(target, fromName);
        if (request == null) {
            return;
        }
        Player from = Bukkit.getPlayer(request.from());
        plugin.messages().send(target, "tpa.denied", "player", from != null ? from.getName() : "?");
        if (from != null) {
            plugin.messages().send(from, "tpa.denied-notify", "player", target.getName());
        }
    }

    /** 取出並移除要回應的請求；沒有指定名稱時取最新的一筆。 */
    private @Nullable Request respond(Player target, @Nullable String fromName) {
        if (!target.hasPermission("coretp.tpa.respond")) {
            plugin.messages().send(target, "general.no-permission");
            return null;
        }
        Map<UUID, Request> map = incoming.get(target.getUniqueId());
        if (map == null || map.isEmpty()) {
            plugin.messages().send(target, "tpa.no-request");
            return null;
        }
        Request request = null;
        if (fromName == null) {
            for (Request r : map.values()) {
                request = r;
            }
        } else {
            Player from = Bukkit.getPlayerExact(fromName);
            request = from == null ? null : map.get(from.getUniqueId());
            if (request == null) {
                plugin.messages().send(target, "tpa.no-request-from", "player", clip(fromName));
                return null;
            }
        }
        if (request != null) {
            remove(request);
        }
        return request;
    }

    public void cancel(Player from) {
        if (!from.hasPermission("coretp.tpa") && !from.hasPermission("coretp.tpahere")) {
            plugin.messages().send(from, "general.no-permission");
            return;
        }
        Request request = outgoing.get(from.getUniqueId());
        if (request == null) {
            plugin.messages().send(from, "tpa.nothing-to-cancel");
            return;
        }
        remove(request);
        Player to = Bukkit.getPlayer(request.to());
        plugin.messages().send(from, "tpa.cancelled", "player", to != null ? to.getName() : "?");
        if (to != null) {
            plugin.messages().send(to, "tpa.cancelled-notify", "player", from.getName());
        }
    }

    public void toggle(Player p) {
        if (!p.hasPermission("coretp.tptoggle")) {
            plugin.messages().send(p, "general.no-permission");
            return;
        }
        PlayerData data = plugin.data().get(p.getUniqueId());
        if (data == null) {
            plugin.messages().send(p, "general.data-not-loaded");
            return;
        }
        data.tpaOff = !data.tpaOff;
        data.dirty = true;
        plugin.messages().send(p, data.tpaOff ? "tpa.toggle-off" : "tpa.toggle-on");
    }

    /** 等待 target 回應的請求（新到舊）。 */
    public List<Request> incoming(UUID target) {
        Map<UUID, Request> map = incoming.get(target);
        if (map == null || map.isEmpty()) {
            return List.of();
        }
        List<Request> list = new ArrayList<>(map.values());
        java.util.Collections.reverse(list);
        return list;
    }

    public int incomingCount(UUID target) {
        Map<UUID, Request> map = incoming.get(target);
        return map == null ? 0 : map.size();
    }

    public void quit(UUID id) {
        lastSent.remove(id);
        Request out = outgoing.get(id);
        if (out != null) {
            remove(out);
        }
        Map<UUID, Request> map = incoming.remove(id);
        if (map != null) {
            for (Request r : map.values()) {
                r.expiry().cancel();
                outgoing.remove(r.from());
            }
        }
    }

    public void shutdown() {
        for (Request r : outgoing.values()) {
            r.expiry().cancel();
        }
        outgoing.clear();
        incoming.clear();
        lastSent.clear();
    }

    private void expire(UUID fromId, UUID toId) {
        Request request = outgoing.get(fromId);
        if (request == null || !request.to().equals(toId)) {
            return;
        }
        remove(request);
        Player from = Bukkit.getPlayer(fromId);
        Player to = Bukkit.getPlayer(toId);
        if (from != null) {
            plugin.messages().send(from, "tpa.expired", "player", to != null ? to.getName() : "?");
        }
        if (to != null) {
            plugin.messages().send(to, "tpa.expired-notify", "player", from != null ? from.getName() : "?");
        }
    }

    private void remove(Request request) {
        request.expiry().cancel();
        outgoing.remove(request.from(), request);
        Map<UUID, Request> map = incoming.get(request.to());
        if (map != null) {
            map.remove(request.from(), request);
            if (map.isEmpty()) {
                incoming.remove(request.to());
            }
        }
    }

    private static @Nullable Player findVisible(Player viewer, String name) {
        if (name.isEmpty() || name.length() > 16) {
            return null;
        }
        Player p = Bukkit.getPlayerExact(name);
        return p != null && viewer.canSee(p) ? p : null;
    }

    /** 回顯玩家輸入時限制長度。 */
    private static String clip(String s) {
        return s.length() > 32 ? s.substring(0, 32) : s;
    }
}
