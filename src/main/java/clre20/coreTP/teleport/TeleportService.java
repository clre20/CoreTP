package clre20.coreTP.teleport;

import clre20.coreTP.CoreTP;
import clre20.coreTP.Module;
import clre20.coreTP.config.ModuleSettings;
import clre20.coreTP.economy.Economy;
import clre20.coreTP.economy.VaultEconomy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 所有傳送共用的流程：世界停用檢查 → 冷卻 → 費用 → 等待倒數 → 取得目的地 → 安全檢查 → 扣款 → 傳送。
 * 只存 UUID，不長期持有 Player。全部在主執行緒執行。
 */
public final class TeleportService {

    /** 取得目的地的結果；loc 為 null 時以 failKey 的訊息告知玩家。 */
    public record Resolved(@Nullable Location loc, @Nullable String failKey) {
        public static Resolved ok(Location loc) {
            return new Resolved(loc, null);
        }

        public static Resolved fail(String key) {
            return new Resolved(null, key);
        }
    }

    public interface Destination {
        /** 事前檢查用的目的地；還不知道時回傳 null（例如隨機傳送）。 */
        @Nullable Location peek();

        /** 等待結束後才呼叫，取得實際要傳送的位置。 */
        CompletableFuture<Resolved> resolve();

        static Destination fixed(Location loc) {
            return new Destination() {
                @Override
                public Location peek() {
                    return loc;
                }

                @Override
                public CompletableFuture<Resolved> resolve() {
                    return CompletableFuture.completedFuture(Resolved.ok(loc));
                }
            };
        }
    }

    private static final class Warmup {
        Module module;
        UUID payer;
        Destination dest;
        Runnable onDone;
        int remaining;
        UUID world;
        int bx, by, bz;
        BukkitTask task;
    }

    private static final int MODULES = Module.values().length;

    private final CoreTP plugin;
    private final Map<UUID, Warmup> warmups = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();
    private final Set<UUID> selfTeleporting = new HashSet<>();
    private final Map<UUID, long[]> cooldowns = new HashMap<>();
    private final boolean vaultPresent;
    private Economy economy;

    public TeleportService(CoreTP plugin) {
        this.plugin = plugin;
        this.vaultPresent = Bukkit.getPluginManager().getPlugin("Vault") != null;
    }

    private @Nullable Economy economy() {
        if (!vaultPresent) {
            return null;
        }
        if (economy == null) {
            economy = VaultEconomy.find();
        }
        return economy;
    }

    // ---------------------------------------------------------------- 對外

    /** 沒有任何人在等待傳送時，高頻事件可以直接略過。 */
    public boolean idle() {
        return warmups.isEmpty();
    }

    /** 這次傳送是否由本插件發起（返回功能用來判斷要不要強制記錄）。 */
    public boolean isSelfTeleporting(UUID uuid) {
        return !selfTeleporting.isEmpty() && selfTeleporting.contains(uuid);
    }

    public boolean isTeleporting(UUID uuid) {
        return warmups.containsKey(uuid) || busy.contains(uuid);
    }

    /** 發送請求前的檢查（世界、冷卻），不通過時已經告知玩家。 */
    public boolean precheck(Player mover, Player payer, Module module, @Nullable Location peek) {
        ModuleSettings s = plugin.settings().module(module);
        return checkWorlds(mover, payer, s, peek) && checkCooldown(mover, payer, module);
    }

    /**
     * 開始一次傳送。
     *
     * @param mover 實際被傳送的玩家
     * @param payer 付費與計算冷卻的玩家（通常與 mover 相同）
     * @return 是否通過事前檢查並開始（不代表傳送一定成功）
     */
    public boolean start(Player mover, Player payer, Module module, Destination dest, @Nullable Runnable onDone) {
        UUID id = mover.getUniqueId();
        if (isTeleporting(id)) {
            plugin.messages().send(mover, "teleport.already");
            return false;
        }
        ModuleSettings s = plugin.settings().module(module);
        if (!s.enabled()) {
            notify(mover, payer, "general.module-disabled");
            return false;
        }
        Location peek = dest.peek();
        if (!checkWorlds(mover, payer, s, peek) || !checkCooldown(mover, payer, module)) {
            return false;
        }
        if (peek != null && !canAfford(mover, payer, cost(s, payer, mover.getLocation(), peek))) {
            return false;
        }

        int seconds = mover.hasPermission("coretp.bypass.warmup") ? 0 : s.warmupSeconds();
        if (seconds <= 0) {
            execute(mover, payer.getUniqueId(), module, dest, onDone);
            return true;
        }

        Warmup w = new Warmup();
        w.module = module;
        w.payer = payer.getUniqueId();
        w.dest = dest;
        w.onDone = onDone;
        w.remaining = seconds;
        Location at = mover.getLocation();
        w.world = mover.getWorld().getUID();
        w.bx = at.getBlockX();
        w.by = at.getBlockY();
        w.bz = at.getBlockZ();
        warmups.put(id, w);
        plugin.messages().send(mover, "teleport.warmup-start", "seconds", seconds);
        showCountdown(mover, seconds);
        w.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(id), 20L, 20L);
        return true;
    }

    /** PlayerMoveEvent：呼叫前已確認 idle() 為 false 且換了方塊。 */
    public void onMove(Player player) {
        Warmup w = warmups.get(player.getUniqueId());
        if (w != null && plugin.settings().module(w.module).cancelOnMove()) {
            cancel(player.getUniqueId(), "teleport.cancelled-move");
        }
    }

    public void onDamage(Player player) {
        Warmup w = warmups.get(player.getUniqueId());
        if (w != null && plugin.settings().module(w.module).cancelOnDamage()) {
            cancel(player.getUniqueId(), "teleport.cancelled-damage");
        }
    }

    public void cancel(UUID id, @Nullable String messageKey) {
        Warmup w = warmups.remove(id);
        if (w == null) {
            return;
        }
        if (w.task != null) {
            w.task.cancel();
        }
        if (messageKey != null) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                plugin.messages().send(p, messageKey);
            }
        }
    }

    public void quit(UUID id) {
        cancel(id, null);
        busy.remove(id);
        selfTeleporting.remove(id);
        long[] cd = cooldowns.get(id);
        if (cd != null && expired(cd, System.currentTimeMillis())) {
            cooldowns.remove(id);
        }
    }

    /** 清掉已經過期的冷卻紀錄，避免離線玩家的資料一直留在記憶體。 */
    public void pruneCooldowns() {
        if (cooldowns.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        cooldowns.values().removeIf(cd -> expired(cd, now));
    }

    public void setCooldown(UUID id, Module module, int seconds) {
        if (seconds <= 0) {
            return;
        }
        long until = System.currentTimeMillis() + seconds * 1000L;
        long[] cd = cooldowns.computeIfAbsent(id, k -> new long[MODULES]);
        if (until > cd[module.ordinal()]) {
            cd[module.ordinal()] = until;
        }
    }

    public void shutdown() {
        for (Warmup w : warmups.values()) {
            if (w.task != null) {
                w.task.cancel();
            }
        }
        warmups.clear();
        busy.clear();
        selfTeleporting.clear();
        cooldowns.clear();
    }

    /** 重載設定時取消所有等待中的傳送。 */
    public void cancelAll() {
        for (UUID id : new HashSet<>(warmups.keySet())) {
            cancel(id, "teleport.cancelled");
        }
    }

    // ---------------------------------------------------------------- 流程

    private void tick(UUID id) {
        Warmup w = warmups.get(id);
        if (w == null) {
            return;
        }
        Player p = Bukkit.getPlayer(id);
        if (p == null) {
            cancel(id, null);
            return;
        }
        // 等待期間被其他方式移走（傳送、換世界）也算移動
        if (plugin.settings().module(w.module).cancelOnMove()) {
            Location at = p.getLocation();
            if (!p.getWorld().getUID().equals(w.world) || Math.abs(at.getBlockX() - w.bx) > 1
                    || Math.abs(at.getBlockY() - w.by) > 1 || Math.abs(at.getBlockZ() - w.bz) > 1) {
                cancel(id, "teleport.cancelled-move");
                return;
            }
        }
        if (--w.remaining > 0) {
            showCountdown(p, w.remaining);
            return;
        }
        warmups.remove(id);
        w.task.cancel();
        execute(p, w.payer, w.module, w.dest, w.onDone);
    }

    private void showCountdown(Player p, int seconds) {
        if (plugin.settings().countdownActionbar) {
            p.sendActionBar(plugin.messages().text("teleport.countdown", "seconds", seconds));
        } else {
            plugin.messages().send(p, "teleport.countdown", "seconds", seconds);
        }
    }

    private void execute(Player mover, UUID payerId, Module module, Destination dest, @Nullable Runnable onDone) {
        UUID id = mover.getUniqueId();
        busy.add(id);
        CompletableFuture<Resolved> future;
        try {
            future = dest.resolve();
        } catch (RuntimeException e) {
            busy.remove(id);
            plugin.messages().send(mover, "teleport.failed");
            return;
        }
        future.whenComplete((res, err) -> sync(() -> guarded(id, () -> afterResolve(id, payerId, module, res, err, onDone))));
    }

    /** 流程中任何例外（例如經濟插件出錯）都要解除「傳送中」狀態，不能讓玩家卡住。 */
    private void guarded(UUID id, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            busy.remove(id);
            selfTeleporting.remove(id);
            plugin.getLogger().log(java.util.logging.Level.WARNING, "傳送流程發生錯誤", e);
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                plugin.messages().send(p, "teleport.failed");
            }
        }
    }

    private void afterResolve(UUID id, UUID payerId, Module module, @Nullable Resolved res, @Nullable Throwable err,
                              @Nullable Runnable onDone) {
        Player mover = Bukkit.getPlayer(id);
        if (mover == null) {
            busy.remove(id);
            return;
        }
        Player payer = payerId.equals(id) ? mover : Bukkit.getPlayer(payerId);
        if (payer == null) {
            busy.remove(id);
            plugin.messages().send(mover, "teleport.payer-offline");
            return;
        }
        if (err != null || res == null || res.loc() == null || res.loc().getWorld() == null) {
            busy.remove(id);
            notify(mover, payer, res != null && res.failKey() != null ? res.failKey() : "teleport.failed");
            return;
        }
        Location loc = res.loc();
        ModuleSettings s = plugin.settings().module(module);
        // 等待期間玩家可能換了世界，設定也可能被重載，再檢查一次
        if (!s.enabled() || !checkWorlds(mover, payer, s, loc)) {
            busy.remove(id);
            return;
        }
        boolean checkSafety = plugin.settings().safetyCheck
                && (module == Module.HOME || module == Module.WARP || module == Module.BACK);
        if (!checkSafety) {
            finish(mover, payer, module, s, loc, onDone);
            return;
        }
        loc.getWorld().getChunkAtAsync(loc).whenComplete((chunk, chunkErr) -> sync(() -> {
            Player m = Bukkit.getPlayer(id);
            Player pay = payerId.equals(id) ? m : Bukkit.getPlayer(payerId);
            if (m == null || pay == null) {
                busy.remove(id);
                return;
            }
            guarded(id, () -> {
                if (chunkErr != null || !Safety.isSafeDestination(loc)) {
                    busy.remove(id);
                    notify(m, pay, chunkErr != null ? "teleport.failed" : "teleport.unsafe");
                    return;
                }
                finish(m, pay, module, s, loc, onDone);
            });
        }));
    }

    private void finish(Player mover, Player payer, Module module, ModuleSettings s, Location loc,
                        @Nullable Runnable onDone) {
        UUID id = mover.getUniqueId();
        UUID payerId = payer.getUniqueId();
        double cost = cost(s, payer, mover.getLocation(), loc);
        Economy econ = economy();
        if (cost > 0 && econ != null) {
            if (!canAfford(mover, payer, cost)) {
                busy.remove(id);
                return;
            }
            if (!econ.withdraw(payer, cost)) {
                busy.remove(id);
                notify(mover, payer, "teleport.failed");
                return;
            }
        } else {
            cost = 0;
        }
        double charged = cost;
        boolean skipCooldown = payer.hasPermission("coretp.bypass.cooldown");

        selfTeleporting.add(id);
        CompletableFuture<Boolean> teleport;
        try {
            teleport = mover.teleportAsync(loc, TeleportCause.PLUGIN);
        } catch (RuntimeException e) {
            teleport = CompletableFuture.failedFuture(e);
        }
        teleport.whenComplete((ok, err) -> sync(() -> {
            selfTeleporting.remove(id);
            busy.remove(id);
            Player m = Bukkit.getPlayer(id);
            Player pay = Bukkit.getPlayer(payerId);
            if (err == null && Boolean.TRUE.equals(ok)) {
                if (!skipCooldown) {
                    setCooldown(payerId, module, s.cooldownSeconds());
                }
                if (m != null) {
                    plugin.messages().send(m, "teleport.success");
                }
                if (charged > 0 && pay != null && econ != null) {
                    plugin.messages().send(pay, "teleport.charged", "cost", econ.format(charged));
                }
                if (onDone != null) {
                    onDone.run();
                }
            } else {
                // 傳送沒有成功：退款
                if (charged > 0 && econ != null) {
                    econ.deposit(Bukkit.getOfflinePlayer(payerId), charged);
                }
                if (m != null) {
                    plugin.messages().send(m, "teleport.failed");
                }
            }
        }));
    }

    // ---------------------------------------------------------------- 檢查

    private boolean checkWorlds(Player mover, Player payer, ModuleSettings s, @Nullable Location dest) {
        if (s.worldDisabled(mover.getWorld().getName())) {
            notify(mover, payer, "teleport.world-disabled-from");
            return false;
        }
        if (dest != null && dest.getWorld() != null && s.worldDisabled(dest.getWorld().getName())) {
            notify(mover, payer, "teleport.world-disabled-to");
            return false;
        }
        return true;
    }

    private boolean checkCooldown(Player mover, Player payer, Module module) {
        if (payer.hasPermission("coretp.bypass.cooldown")) {
            return true;
        }
        long[] cd = cooldowns.get(payer.getUniqueId());
        if (cd == null) {
            return true;
        }
        long left = cd[module.ordinal()] - System.currentTimeMillis();
        if (left <= 0) {
            return true;
        }
        notify(mover, payer, "teleport.cooldown", "seconds", (left + 999) / 1000);
        return false;
    }

    private boolean canAfford(Player mover, Player payer, double cost) {
        Economy econ = economy();
        if (cost <= 0 || econ == null || econ.has(payer, cost)) {
            return true;
        }
        notify(mover, payer, "teleport.not-enough-money",
                "cost", econ.format(cost), "balance", econ.format(econ.balance(payer)));
        return false;
    }

    private double cost(ModuleSettings s, Player payer, Location from, Location to) {
        if (economy() == null || payer.hasPermission("coretp.bypass.cost")) {
            return 0;
        }
        double cost;
        if (from.getWorld() != null && from.getWorld().equals(to.getWorld())) {
            if (!s.distanceEnabled()) {
                return 0;
            }
            cost = s.distanceBase() + s.distancePerBlock() * from.distance(to);
            cost = Math.max(s.distanceMin(), Math.min(s.distanceMax(), cost));
        } else {
            if (!s.crossWorldEnabled()) {
                return 0;
            }
            cost = s.crossWorldPrice();
        }
        if (!Double.isFinite(cost) || cost <= 0) {
            return 0;
        }
        return Math.round(cost * 100.0) / 100.0;
    }

    private void notify(Player mover, Player payer, String key, Object... vars) {
        plugin.messages().send(mover, key, vars);
        if (!payer.getUniqueId().equals(mover.getUniqueId())) {
            plugin.messages().send(payer, key, vars);
        }
    }

    private void sync(Runnable r) {
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, r);
        }
    }

    private static boolean expired(long[] cd, long now) {
        for (long until : cd) {
            if (until > now) {
                return false;
            }
        }
        return true;
    }
}
