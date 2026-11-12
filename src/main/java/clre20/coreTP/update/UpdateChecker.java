package clre20.coreTP.update;

import clre20.coreTP.CoreTP;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * 向 GitHub Releases 檢查更新。結果快取 10 分鐘（失敗 1 分鐘），沒有定時輪詢，
 * 同一時間最多只有一個請求在進行。
 */
public final class UpdateChecker {

    public enum Status { UP_TO_DATE, OUTDATED, AHEAD, FAILED }

    public record Result(Status status, String latest, long at) {
    }

    private static final String DEFAULT_API = "https://api.github.com/repos/clre20/CoreTP/releases/latest";
    private static final long CACHE_MS = 10 * 60_000L;
    private static final long FAIL_CACHE_MS = 60_000L;
    private static final int MAX_BODY = 512 * 1024;
    private static final Pattern VERSION = Pattern.compile("^\\d{1,6}(\\.\\d{1,6}){0,3}$");

    private final CoreTP plugin;
    private final String current;
    private final String api;
    private Result cached;
    private CompletableFuture<Result> inFlight;

    public UpdateChecker(CoreTP plugin) {
        this.plugin = plugin;
        this.current = plugin.getPluginMeta().getVersion();
        // 測試用：-Dcoretp.update-api=<網址>
        this.api = System.getProperty("coretp.update-api", DEFAULT_API);
    }

    /** 取得結果；快取還有效就直接回傳，不會發出請求。 */
    public synchronized CompletableFuture<Result> check() {
        long now = System.currentTimeMillis();
        if (cached != null) {
            long ttl = cached.status() == Status.FAILED ? FAIL_CACHE_MS : CACHE_MS;
            if (now - cached.at() < ttl) {
                return CompletableFuture.completedFuture(cached);
            }
        }
        if (inFlight != null) {
            return inFlight;
        }
        CompletableFuture<Result> future = new CompletableFuture<>();
        inFlight = future;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Result result = fetch();
            synchronized (this) {
                cached = result;
                inFlight = null;
            }
            future.complete(result);
        });
        return future;
    }

    private Result fetch() {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(api))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "CoreTP/" + current)
                    .GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            if (response.statusCode() != 200 || body == null || body.length() > MAX_BODY) {
                return new Result(Status.FAILED, "", System.currentTimeMillis());
            }
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) {
                return new Result(Status.FAILED, "", System.currentTimeMillis());
            }
            JsonObject obj = root.getAsJsonObject();
            JsonElement tagElement = obj.get("tag_name");
            if (tagElement == null || !tagElement.isJsonPrimitive()) {
                return new Result(Status.FAILED, "", System.currentTimeMillis());
            }
            String latest = strip(tagElement.getAsString());
            // 遠端資料不可信：只接受純數字版本號
            if (!VERSION.matcher(latest).matches()) {
                return new Result(Status.FAILED, "", System.currentTimeMillis());
            }
            int cmp = compare(strip(current), latest);
            Status status = cmp == 0 ? Status.UP_TO_DATE : cmp < 0 ? Status.OUTDATED : Status.AHEAD;
            return new Result(status, latest, System.currentTimeMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Status.FAILED, "", System.currentTimeMillis());
        } catch (Exception e) {
            return new Result(Status.FAILED, "", System.currentTimeMillis());
        }
    }

    /** 啟動時在後台輸出結果。 */
    public void logOnStartup() {
        check().thenAccept(r -> plugin.getLogger().info(plugin.messages().console(key(r), vars(r))));
    }

    /** /coretp version：把結果告訴執行指令的人。 */
    public void report(CommandSender sender) {
        UUID id = sender instanceof Player p ? p.getUniqueId() : null;
        check().thenAccept(r -> runSync(() -> {
            CommandSender target = id == null ? Bukkit.getConsoleSender() : Bukkit.getPlayer(id);
            if (target != null) {
                plugin.messages().send(target, key(r), vars(r));
            }
        }));
    }

    /** 登入提醒：只有確定有新版本時才通知。 */
    public void notifyIfOutdated(UUID id) {
        check().thenAccept(r -> {
            if (r.status() != Status.OUTDATED) {
                return;
            }
            runSync(() -> {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.hasPermission("coretp.version")) {
                    plugin.messages().send(p, key(r), vars(r));
                }
            });
        });
    }

    private void runSync(Runnable r) {
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, r);
        }
    }

    private static String key(Result r) {
        return switch (r.status()) {
            case UP_TO_DATE -> "update.up-to-date";
            case OUTDATED -> "update.outdated";
            case AHEAD -> "update.ahead";
            case FAILED -> "update.failed";
        };
    }

    private Object[] vars(Result r) {
        return new Object[]{"current", current, "latest", r.latest()};
    }

    /** 去掉標籤開頭的 v。 */
    static String strip(String version) {
        String v = version == null ? "" : version.trim();
        return v.startsWith("v") || v.startsWith("V") ? v.substring(1) : v;
    }

    /** 逐段比較數字；非數字的段落當作 0。 */
    static int compare(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            long x = i < pa.length ? number(pa[i]) : 0;
            long y = i < pb.length ? number(pb[i]) : 0;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        return 0;
    }

    private static long number(String part) {
        int end = 0;
        while (end < part.length() && end < 9 && Character.isDigit(part.charAt(end))) {
            end++;
        }
        return end == 0 ? 0 : Long.parseLong(part.substring(0, end));
    }
}
