package clre20.coreTP.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;

/** 讀取設定檔並自動補齊缺少的項目（保留用戶原有的值）。 */
public final class YamlFiles {

    private YamlFiles() {
    }

    /**
     * @param resource jar 內與資料夾內相同的相對路徑（固定字串，不接受外部輸入）
     */
    public static YamlConfiguration load(JavaPlugin plugin, String resource) {
        YamlConfiguration defaults = readDefaults(plugin, resource);
        File file = new File(plugin.getDataFolder(), resource);
        if (!file.exists()) {
            plugin.saveResource(resource, false);
        }

        YamlConfiguration user = new YamlConfiguration();
        try {
            user.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            // 格式壞掉：備份原檔後改用預設值，避免把用戶的內容直接蓋掉
            File broken = new File(file.getParentFile(), file.getName() + ".broken-" + System.currentTimeMillis());
            try {
                Files.move(file.toPath(), broken.toPath(), StandardCopyOption.REPLACE_EXISTING);
                plugin.getLogger().warning(resource + " 格式錯誤，已備份為 " + broken.getName() + " 並改用預設值。");
            } catch (IOException moveError) {
                plugin.getLogger().log(Level.WARNING, "無法備份格式錯誤的 " + resource, moveError);
            }
            plugin.saveResource(resource, true);
            user = new YamlConfiguration();
            try {
                user.load(file);
            } catch (IOException | InvalidConfigurationException again) {
                plugin.getLogger().log(Level.SEVERE, "無法讀取 " + resource + "，將使用內建預設值。", again);
                return defaults;
            }
        }

        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) {
                continue;
            }
            if (!user.isSet(key)) {
                user.set(key, defaults.get(key));
                user.setComments(key, defaults.getComments(key));
                changed = true;
            }
        }
        if (changed) {
            try {
                user.save(file);
                plugin.getLogger().info(resource + " 已自動補齊缺少的項目。");
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "無法寫回 " + resource, e);
            }
        }
        user.setDefaults(defaults);
        return user;
    }

    private static YamlConfiguration readDefaults(JavaPlugin plugin, String resource) {
        try (InputStream in = plugin.getResource(resource)) {
            if (in != null) {
                return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "無法讀取內建的 " + resource, e);
        }
        return new YamlConfiguration();
    }
}
