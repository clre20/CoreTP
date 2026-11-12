package clre20.coreTP.storage;

import clre20.coreTP.util.Pos;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite 存取。所有 SQL 都在唯一一條 I/O 執行緒上依序執行（不會無限開執行緒），
 * 全部使用參數化查詢。
 */
public final class Database {

    private final Logger log;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CoreTP-IO");
        t.setDaemon(true);
        return t;
    });
    private Connection conn;

    public Database(Logger log) {
        this.log = log;
    }

    public void open(File file) throws Exception {
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA synchronous = NORMAL");
            st.execute("CREATE TABLE IF NOT EXISTS players (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, tpa_off INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS homes (uuid TEXT NOT NULL, name TEXT NOT NULL, world TEXT NOT NULL,"
                    + " x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL,"
                    + " is_public INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, PRIMARY KEY (uuid, name))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_homes_public ON homes (is_public)");
            st.execute("CREATE TABLE IF NOT EXISTS warps (name TEXT PRIMARY KEY, world TEXT NOT NULL,"
                    + " x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL,"
                    + " icon TEXT NOT NULL, created INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS back (uuid TEXT NOT NULL, idx INTEGER NOT NULL, world TEXT NOT NULL,"
                    + " x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL,"
                    + " ts INTEGER NOT NULL, death INTEGER NOT NULL, PRIMARY KEY (uuid, idx))");
        }
    }

    /** 把工作排進 I/O 執行緒。 */
    public <T> CompletableFuture<T> submit(Callable<T> job) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            io.execute(() -> {
                try {
                    future.complete(job.call());
                } catch (Throwable t) {
                    log.log(Level.SEVERE, "資料庫操作失敗", t);
                    future.completeExceptionally(t);
                }
            });
        } catch (RuntimeException rejected) {
            future.completeExceptionally(rejected);
        }
        return future;
    }

    /** 等待所有排隊中的寫入完成後關閉。 */
    public void close() {
        io.shutdown();
        try {
            if (!io.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warning("資料寫入逾時，部分資料可能沒有存檔。");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (conn != null) {
                conn.close();
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "關閉資料庫時發生錯誤", e);
        }
    }

    // ---- 以下方法只能在 I/O 執行緒呼叫（透過 submit） ----

    public PlayerData loadPlayer(UUID uuid, String name, int maxBack) throws SQLException {
        PlayerData data = new PlayerData(uuid, name);
        String id = uuid.toString();
        try (PreparedStatement ps = conn.prepareStatement("SELECT tpa_off FROM players WHERE uuid = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    data.tpaOff = rs.getInt(1) != 0;
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT name, world, x, y, z, yaw, pitch, is_public, created FROM homes WHERE uuid = ? ORDER BY created")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Pos pos = pos(rs, 2);
                    if (pos.valid()) {
                        Home home = new Home(rs.getString(1), pos, rs.getInt(8) != 0, rs.getLong(9));
                        data.homes.put(home.name().toLowerCase(java.util.Locale.ROOT), home);
                    }
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT world, x, y, z, yaw, pitch, ts, death FROM back WHERE uuid = ? ORDER BY idx LIMIT ?")) {
            ps.setString(1, id);
            ps.setInt(2, maxBack);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Pos pos = pos(rs, 1);
                    if (pos.valid()) {
                        data.back.add(new BackEntry(pos, rs.getLong(7), rs.getInt(8) != 0));
                    }
                }
            }
        }
        return data;
    }

    /** 一次交易寫入多位玩家（批次寫入）。 */
    public void savePlayers(Collection<PlayerData.Snapshot> snapshots) throws SQLException {
        if (snapshots.isEmpty()) {
            return;
        }
        conn.setAutoCommit(false);
        try (PreparedStatement upsert = conn.prepareStatement(
                "INSERT INTO players (uuid, name, tpa_off) VALUES (?, ?, ?)"
                        + " ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, tpa_off = excluded.tpa_off");
             PreparedStatement delHomes = conn.prepareStatement("DELETE FROM homes WHERE uuid = ?");
             PreparedStatement insHome = conn.prepareStatement(
                     "INSERT INTO homes (uuid, name, world, x, y, z, yaw, pitch, is_public, created) VALUES (?,?,?,?,?,?,?,?,?,?)");
             PreparedStatement delBack = conn.prepareStatement("DELETE FROM back WHERE uuid = ?");
             PreparedStatement insBack = conn.prepareStatement(
                     "INSERT INTO back (uuid, idx, world, x, y, z, yaw, pitch, ts, death) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            for (PlayerData.Snapshot s : snapshots) {
                String id = s.uuid().toString();
                upsert.setString(1, id);
                upsert.setString(2, s.name());
                upsert.setInt(3, s.tpaOff() ? 1 : 0);
                upsert.executeUpdate();

                delHomes.setString(1, id);
                delHomes.executeUpdate();
                for (Home h : s.homes()) {
                    insHome.setString(1, id);
                    insHome.setString(2, h.name());
                    bind(insHome, 3, h.pos());
                    insHome.setInt(9, h.isPublic() ? 1 : 0);
                    insHome.setLong(10, h.created());
                    insHome.addBatch();
                }
                insHome.executeBatch();

                delBack.setString(1, id);
                delBack.executeUpdate();
                int idx = 0;
                for (BackEntry b : s.back()) {
                    insBack.setString(1, id);
                    insBack.setInt(2, idx++);
                    bind(insBack, 3, b.pos());
                    insBack.setLong(9, b.timestamp());
                    insBack.setInt(10, b.death() ? 1 : 0);
                    insBack.addBatch();
                }
                insBack.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    /** 只在玩家沒有任何歷史位置時匯入（Cback 遷移用）。回傳實際匯入筆數。 */
    public int importBack(UUID uuid, List<BackEntry> entries) throws SQLException {
        String id = uuid.toString();
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM back WHERE uuid = ? LIMIT 1")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return 0;
                }
            }
        }
        conn.setAutoCommit(false);
        try (PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO back (uuid, idx, world, x, y, z, yaw, pitch, ts, death) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            int idx = 0;
            for (BackEntry b : entries) {
                ins.setString(1, id);
                ins.setInt(2, idx++);
                bind(ins, 3, b.pos());
                ins.setLong(9, b.timestamp());
                ins.setInt(10, b.death() ? 1 : 0);
                ins.addBatch();
            }
            ins.executeBatch();
            conn.commit();
            return idx;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public List<Warp> loadWarps() throws SQLException {
        List<Warp> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name, world, x, y, z, yaw, pitch, icon, created FROM warps ORDER BY name")) {
            while (rs.next()) {
                Pos pos = pos(rs, 2);
                if (pos.valid()) {
                    list.add(new Warp(rs.getString(1), pos, rs.getString(8), rs.getLong(9)));
                }
            }
        }
        return list;
    }

    public void saveWarp(Warp w) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO warps (name, world, x, y, z, yaw, pitch, icon, created) VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, w.name());
            bind(ps, 2, w.pos());
            ps.setString(8, w.icon());
            ps.setLong(9, w.created());
            ps.executeUpdate();
        }
    }

    public void deleteWarp(String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM warps WHERE name = ?")) {
            ps.setString(1, name);
            ps.executeUpdate();
        }
    }

    public List<PublicHome> loadPublicHomes() throws SQLException {
        List<PublicHome> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT h.uuid, COALESCE(p.name, '?'), h.name, h.world, h.x, h.y, h.z, h.yaw, h.pitch"
                     + " FROM homes h LEFT JOIN players p ON p.uuid = h.uuid WHERE h.is_public = 1")) {
            while (rs.next()) {
                Pos pos = pos(rs, 4);
                if (!pos.valid()) {
                    continue;
                }
                try {
                    list.add(new PublicHome(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), pos));
                } catch (IllegalArgumentException ignored) {
                    // 無效的 UUID 直接略過
                }
            }
        }
        return list;
    }

    /** 刪除過期的歷史位置，避免資料庫無限增長。 */
    public int purgeBack(long olderThanMillis) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM back WHERE ts < ?")) {
            ps.setLong(1, olderThanMillis);
            return ps.executeUpdate();
        }
    }

    private static Pos pos(ResultSet rs, int start) throws SQLException {
        return new Pos(rs.getString(start), rs.getDouble(start + 1), rs.getDouble(start + 2), rs.getDouble(start + 3),
                rs.getFloat(start + 4), rs.getFloat(start + 5));
    }

    private static void bind(PreparedStatement ps, int start, Pos p) throws SQLException {
        ps.setString(start, p.world());
        ps.setDouble(start + 1, p.x());
        ps.setDouble(start + 2, p.y());
        ps.setDouble(start + 3, p.z());
        ps.setFloat(start + 4, p.yaw());
        ps.setFloat(start + 5, p.pitch());
    }
}
