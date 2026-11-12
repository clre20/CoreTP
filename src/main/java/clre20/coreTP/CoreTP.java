package clre20.coreTP;

import clre20.coreTP.command.BlockedCommand;
import clre20.coreTP.command.Commands;
import clre20.coreTP.command.CoreCommand;
import clre20.coreTP.config.Messages;
import clre20.coreTP.config.Settings;
import clre20.coreTP.gui.MenuListener;
import clre20.coreTP.gui.Menus;
import clre20.coreTP.listener.PlayerListener;
import clre20.coreTP.migration.CbackMigrator;
import clre20.coreTP.module.BackService;
import clre20.coreTP.module.HomeService;
import clre20.coreTP.module.RtpService;
import clre20.coreTP.module.TpaManager;
import clre20.coreTP.module.WarpService;
import clre20.coreTP.storage.DataStore;
import clre20.coreTP.storage.Database;
import clre20.coreTP.teleport.TeleportService;
import clre20.coreTP.update.UpdateChecker;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.logging.Level;

public final class CoreTP extends JavaPlugin {

    private static final String[] PLAYER_COMMANDS = {
            "tpa", "tpahere", "tpaccept", "tpdeny", "tpcancel", "tptoggle",
            "home", "sethome", "delhome", "homes",
            "warp", "setwarp", "delwarp", "warps",
            "back", "eback", "rtp"};

    private Messages messages;
    private Settings settings;
    private Database database;
    private DataStore data;
    private TeleportService teleports;
    private TpaManager tpa;
    private HomeService homes;
    private WarpService warps;
    private BackService backs;
    private RtpService rtp;
    private Menus menus;
    private UpdateChecker updates;
    private boolean running;

    @Override
    public void onEnable() {
        messages = new Messages(this);

        // 舊的 Cback 還裝著：不遷移、不載入任何資料，所有指令只提示
        if (CbackMigrator.cbackInstalled()) {
            getLogger().severe(messages.console("general.blocked"));
            TabExecutor blocked = new BlockedCommand(messages);
            register("coretp", blocked);
            for (String name : PLAYER_COMMANDS) {
                register(name, blocked);
            }
            return;
        }

        database = new Database(getLogger());
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
                throw new IllegalStateException("無法建立資料夾 " + getDataFolder());
            }
            database.open(new File(getDataFolder(), "data.db"));
            // 遷移只在開機時處理（重載不會執行），而且要在讀取設定之前
            CbackMigrator.run(this, database);
            settings = new Settings(this);
            data = new DataStore(this, database);
            data.start();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "CoreTP 啟動失敗，已停用。", e);
            database.close();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        teleports = new TeleportService(this);
        tpa = new TpaManager(this);
        homes = new HomeService(this);
        warps = new WarpService(this);
        backs = new BackService(this);
        rtp = new RtpService(this);
        menus = new Menus(this);
        updates = new UpdateChecker(this);

        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new MenuListener(this), this);

        register("coretp", new CoreCommand(this));
        TabExecutor commands = new Commands(this);
        for (String name : PLAYER_COMMANDS) {
            register(name, commands);
        }

        // 每 5 分鐘清一次過期的冷卻紀錄
        Bukkit.getScheduler().runTaskTimer(this, teleports::pruneCooldowns, 6000L, 6000L);

        if (settings.updateCheckOnStartup) {
            updates.logOnStartup();
        }
        running = true;
    }

    @Override
    public void onDisable() {
        if (!running) {
            return;
        }
        running = false;
        MenuListener.closeAll();
        teleports.shutdown();
        tpa.shutdown();
        Bukkit.getScheduler().cancelTasks(this);
        data.shutdown(); // 統一存檔並等待寫入完成
    }

    /** 重新讀取 config.yml、messages.yml 與 modules/*.yml（會自動補齊缺少的項目）。 */
    public void reload() {
        data.flush();
        teleports.cancelAll();
        MenuListener.closeAll();
        messages = new Messages(this);
        settings = new Settings(this);
        data.scheduleFlush();
    }

    private void register(String name, TabExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("plugin.yml 缺少指令：" + name);
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    public Messages messages() {
        return messages;
    }

    public Settings settings() {
        return settings;
    }

    public DataStore data() {
        return data;
    }

    public TeleportService teleports() {
        return teleports;
    }

    public TpaManager tpa() {
        return tpa;
    }

    public HomeService homes() {
        return homes;
    }

    public WarpService warps() {
        return warps;
    }

    public BackService backs() {
        return backs;
    }

    public RtpService rtp() {
        return rtp;
    }

    public Menus menus() {
        return menus;
    }

    public UpdateChecker updates() {
        return updates;
    }
}
