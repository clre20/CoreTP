package clre20.coreTP.module;

import clre20.coreTP.CoreTP;
import clre20.coreTP.Module;
import clre20.coreTP.config.Settings.RtpRange;
import clre20.coreTP.teleport.Safety;
import clre20.coreTP.teleport.TeleportService.Destination;
import clre20.coreTP.teleport.TeleportService.Resolved;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 隨機傳送：在玩家目前的世界找安全落點。
 * 區塊以非同步方式載入，並限制同時搜尋的人數與每次嘗試的次數，避免大量產生區塊拖慢伺服器。
 */
public final class RtpService {

    /** 找不到位置後的短暫冷卻，避免連續觸發區塊載入。 */
    private static final int FAIL_COOLDOWN_SECONDS = 5;

    private final CoreTP plugin;
    private int active;

    public RtpService(CoreTP plugin) {
        this.plugin = plugin;
    }

    public void rtp(Player p) {
        if (!p.hasPermission("coretp.rtp")) {
            plugin.messages().send(p, "general.no-permission");
            return;
        }
        UUID id = p.getUniqueId();
        plugin.teleports().start(p, p, Module.RTP, new Destination() {
            @Override
            public @Nullable Location peek() {
                return null; // 目的地就在玩家所在的世界，世界檢查已經涵蓋
            }

            @Override
            public CompletableFuture<Resolved> resolve() {
                Player online = Bukkit.getPlayer(id);
                if (online == null) {
                    return CompletableFuture.completedFuture(Resolved.fail("teleport.failed"));
                }
                return search(online);
            }
        }, null);
    }

    private CompletableFuture<Resolved> search(Player p) {
        if (active >= plugin.settings().rtpMaxConcurrent) {
            return CompletableFuture.completedFuture(Resolved.fail("rtp.busy"));
        }
        active++;
        plugin.messages().send(p, "rtp.searching");
        CompletableFuture<Resolved> result = new CompletableFuture<>();
        UUID id = p.getUniqueId();
        World world = p.getWorld();
        attempt(world, plugin.settings().rtpRange(world.getName()), plugin.settings().rtpMaxAttempts, result);
        return result.whenComplete((res, err) -> {
            active--;
            if (res == null || res.loc() == null) {
                plugin.teleports().setCooldown(id, Module.RTP, FAIL_COOLDOWN_SECONDS);
            }
        });
    }

    private void attempt(World world, RtpRange range, int left, CompletableFuture<Resolved> result) {
        if (left <= 0 || !plugin.isEnabled()) {
            result.complete(Resolved.fail("rtp.no-safe-location"));
            return;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        // 面積均勻分布
        double min = range.minRadius();
        double max = range.maxRadius();
        double radius = Math.sqrt(rnd.nextDouble() * (max * max - min * min) + min * min);
        double angle = rnd.nextDouble() * Math.PI * 2;
        int x = range.centerX() + (int) Math.round(Math.cos(angle) * radius);
        int z = range.centerZ() + (int) Math.round(Math.sin(angle) * radius);
        if (!world.getWorldBorder().isInside(new Location(world, x + 0.5, 64, z + 0.5))) {
            // 超出世界邊界不載入區塊，直接換下一個
            attempt(world, range, left - 1, result);
            return;
        }
        world.getChunkAtAsync(x >> 4, z >> 4, true).whenComplete((chunk, err) -> {
            if (err != null || chunk == null) {
                result.complete(Resolved.fail("rtp.no-safe-location"));
                return;
            }
            Runnable next = () -> {
                Location found = findStand(world, x, z);
                if (found != null) {
                    result.complete(Resolved.ok(found));
                } else {
                    attempt(world, range, left - 1, result);
                }
            };
            if (Bukkit.isPrimaryThread()) {
                next.run();
            } else if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, next);
            } else {
                result.complete(Resolved.fail("teleport.failed"));
            }
        });
    }

    /** 區塊已載入時才呼叫。只掃單一直行，成本固定。 */
    private @Nullable Location findStand(World world, int x, int z) {
        int groundY;
        if (world.getEnvironment() == World.Environment.NETHER) {
            groundY = Integer.MIN_VALUE;
            int top = Math.min(world.getLogicalHeight(), world.getMaxHeight()) - 3;
            for (int y = top; y > world.getMinHeight(); y--) {
                if (Safety.isSafeStand(world.getBlockAt(x, y, z))) {
                    groundY = y;
                    break;
                }
            }
            if (groundY == Integer.MIN_VALUE) {
                return null;
            }
        } else {
            groundY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (groundY <= world.getMinHeight()) {
                return null;
            }
            Block ground = world.getBlockAt(x, groundY, z);
            if (!Safety.isSafeStand(ground)) {
                return null;
            }
        }
        return new Location(world, x + 0.5, groundY + 1, z + 0.5, ThreadLocalRandom.current().nextFloat() * 360f, 0f);
    }
}
