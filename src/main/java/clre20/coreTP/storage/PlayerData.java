package clre20.coreTP.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 單一玩家的記憶體資料。載入後只在主執行緒存取。 */
public final class PlayerData {

    public final UUID uuid;
    public String name;
    public boolean tpaOff;
    /** key 為小寫名稱。 */
    public final Map<String, Home> homes = new LinkedHashMap<>();
    /** index 0 是最新的一筆。 */
    public final List<BackEntry> back = new ArrayList<>();
    public boolean dirty;
    /** 建立時間，用來清掉登入失敗而沒被取走的資料。 */
    public long loadedAt = System.currentTimeMillis();

    public PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public Snapshot snapshot() {
        return new Snapshot(uuid, name, tpaOff, List.copyOf(homes.values()), List.copyOf(back));
    }

    /** 交給 I/O 執行緒寫入的不可變複本。 */
    public record Snapshot(UUID uuid, String name, boolean tpaOff, List<Home> homes, List<BackEntry> back) {
    }
}
