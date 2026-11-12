# CoreTP

CoreTP 是一個 Paper 26.x 的整合式傳送插件，把玩家互傳（TPA）、家、地標、返回與隨機傳送放在同一個插件裡。每個功能都有自己的設定檔，可以單獨開關、設定等待時間、冷卻、停用的世界與收費方式。Java 版玩家使用箱子介面，基岩版（Floodgate）玩家使用原生表單。

CoreTP 是 [Cback](https://github.com/clre20/CoreTP/releases/tag/v2.0.1) 的後繼版本，第一次啟動時會自動匯入 Cback 的歷史位置。

## 功能特色

- **玩家互傳**：`/tpa`、`/tpahere`，請求會自動過期，聊天室有可點擊的 [接受] [拒絕] 按鈕
  - 每位玩家同時只能送出一個請求
  - `/tptoggle` 可以關閉接收請求
- **家**：可設定多個家，數量上限用權限節點分級（`coretp.home.limit.<數量>`）
  - 玩家可以在選單裡把每個家設為「公開」或「私人」
  - 公開的家會出現在公開清單，擁有者離線時其他玩家也能前往
- **地標**：由管理員建立，可指定選單圖示；預設只有管理員可以使用
- **返回**：記錄傳送前的位置與死亡點，每位玩家保留最近 10 筆（可調整）
  - `/back` 回到最近一筆，`/eback` 開啟歷史清單
  - 內建時間間隔與最小距離過濾，避免基岩版延遲或防作弊拉回蓋掉真正的位置
- **隨機傳送**：在玩家目前所在的世界內尋找安全落點，各世界可設定不同範圍
- **每個功能獨立設定**（`modules/tpa.yml`、`home.yml`、`warp.yml`、`back.yml`、`rtp.yml`）
  - 等待秒數、移動或受傷是否取消、冷卻秒數
  - 停用的世界：出發地或目的地任一方在清單內就不傳送
  - 收費：依距離、跨世界固定費用，兩者可分別開關，都關閉就是免費（需要 Vault）
- **雙端介面**：Java 版箱子介面（只接受左鍵），基岩版原生表單
- **效能**：資料只在記憶體讀寫，定時批次寫入 SQLite；區塊一律非同步載入
- **自動補齊設定**：更新版本後缺少的設定與訊息會自動補上，原有的值不會被改掉
- **更新檢查**：啟動時與 `/coretp version` 向 GitHub Releases 檢查新版本

## 指令與權限

| 指令 | 執行者 | 權限節點 | 預設 |
| :--- | :--- | :--- | :--- |
| `/coretp` | 玩家 | `coretp.use` | 所有人 |
| `/coretp help` | 玩家／後台 | `coretp.use` | 所有人 |
| `/coretp reload` | 玩家／後台 | `coretp.reload` | 管理員 |
| `/coretp version` | 玩家／後台 | `coretp.version` | 管理員 |
| `/tpa <玩家>` | 玩家 | `coretp.tpa` | 所有人 |
| `/tpahere <玩家>` | 玩家 | `coretp.tpahere` | 所有人 |
| `/tpaccept [玩家]`、`/tpdeny [玩家]` | 玩家 | `coretp.tpa.respond` | 所有人 |
| `/tpcancel` | 玩家 | `coretp.tpa` 或 `coretp.tpahere` | 所有人 |
| `/tptoggle` | 玩家 | `coretp.tptoggle` | 所有人 |
| `/home [名稱]`、`/homes` | 玩家 | `coretp.home` | 所有人 |
| `/homes public` | 玩家 | `coretp.home.visit` | 所有人 |
| `/sethome [名稱]` | 玩家 | `coretp.home.set` | 所有人 |
| `/delhome <名稱>` | 玩家 | `coretp.home.delete` | 所有人 |
| （選單內切換公開／私人） | 玩家 | `coretp.home.public` | 所有人 |
| `/warp <名稱>`、`/warps` | 玩家 | `coretp.warp` | 管理員 |
| `/setwarp <名稱> [圖示]` | 玩家 | `coretp.warp.set` | 管理員 |
| `/delwarp <名稱>` | 玩家／後台 | `coretp.warp.delete` | 管理員 |
| `/back` | 玩家 | `coretp.back` | 所有人 |
| `/eback` | 玩家 | `coretp.back.history` | 所有人 |
| `/rtp` | 玩家 | `coretp.rtp` | 所有人 |

> `/ctp` 是 `/coretp` 的別名。

> 家的數量：`coretp.home.limit.<數量>`（例如 `coretp.home.limit.10`），沒有時使用 `home.yml` 的 `default-limit`。

> 略過限制的權限預設沒有人擁有（包含管理員），需要時請另外給予：`coretp.bypass.warmup`（不用等待）、`coretp.bypass.cooldown`（沒有冷卻）、`coretp.bypass.cost`（不收費）、`coretp.bypass.toggle`（可對關閉請求的玩家發送）。

## 使用範例

### 1. 把地標開放給所有玩家

地標預設只有管理員可以使用。要讓玩家也能傳送，用權限插件給予 `coretp.warp`：

```bash
/setwarp spawn BEACON
/lp group default permission set coretp.warp true
```

### 2. 設定隨機傳送收費並停用地獄

編輯 `plugins/CoreTP/modules/rtp.yml`：

```yaml
disabled-worlds:
  - world_nether
cost:
  distance:
    enabled: true
    base: 10.0
    per-block: 0.01
    min: 10.0
    max: 100.0
```

存檔後執行：

```bash
/ctp reload
```

- 收費需要安裝 Vault 與任何一個經濟插件
- 玩家身在 `world_nether` 時無法使用 `/rtp`

### 3. 從 Cback 升級

1. 關閉伺服器
2. 移除 Cback 的 jar，放入 CoreTP 的 jar（`plugins/Cback/` 資料夾留著）
3. 啟動伺服器

CoreTP 會匯入歷史位置，把整個 `Cback` 資料夾壓縮成 `plugins/CoreTP/Cback-backup-<時間>.zip`，確認備份完成後才刪除原資料夾。

- 如果 Cback 的 jar 還在，CoreTP 會暫停所有功能並提示先移除 Cback
- 舊的自訂訊息不會搬移，請到 `messages.yml` 重新調整

## 可自訂訊息

所有文字都在 `plugins/CoreTP/messages.yml`，使用 `&` 顏色代碼，`{大括號}` 是變數。

| 區段 | 說明 |
| :--- | :--- |
| `general` | 前綴、沒有權限、功能未開啟、重載完成等共用訊息 |
| `help` | `/coretp help` 顯示的說明 |
| `teleport` | 等待倒數、取消、冷卻、餘額不足、世界停用等傳送流程訊息 |
| `tpa`、`home`、`warp`、`back`、`rtp` | 各功能的訊息 |
| `update` | 更新檢查結果 |
| `gui` | 箱子介面的標題、物品名稱與說明 |
| `form` | 基岩版表單的文字 |

---

# CoreTP

CoreTP is an all-in-one teleport plugin for Paper 26.x: player-to-player requests (TPA), homes, warps, back, and random teleport. Every feature has its own config file, so each one can be switched on or off and given its own warmup, cooldown, disabled worlds, and pricing. Java players get chest menus; Bedrock (Floodgate) players get native forms.

CoreTP is the successor to [Cback](https://github.com/clre20/CoreTP/releases/tag/v2.0.1) and imports Cback's location history on first start.

## Features

- **TPA**: `/tpa` and `/tpahere` with expiring requests and clickable [Accept] [Deny] chat buttons
  - One outgoing request per player
  - `/tptoggle` to stop receiving requests
- **Homes**: multiple homes, limit set by permission (`coretp.home.limit.<number>`)
  - Each home can be switched between public and private from the menu
  - Public homes are listed for everyone and work while the owner is offline
- **Warps**: created by admins, with a custom menu icon; admin-only by default
- **Back**: records the location before a teleport and on death, last 10 per player (configurable)
  - `/back` returns to the latest entry, `/eback` opens the history
  - Time and distance filters stop Bedrock lag or anti-cheat setbacks from overwriting real locations
- **Random teleport**: finds a safe spot in the player's current world, with per-world ranges
- **Per-feature config** (`modules/tpa.yml`, `home.yml`, `warp.yml`, `back.yml`, `rtp.yml`)
  - Warmup, cancel on move or damage, cooldown
  - Disabled worlds: the teleport is refused if either the origin or the destination is listed
  - Pricing: by distance and a flat cross-world fee, each switchable; both off means free (needs Vault)
- **Two interfaces**: chest menus for Java (left click only), native forms for Bedrock
- **Performance**: data is read and written in memory and batch-saved to SQLite; chunks load asynchronously
- **Config auto-fill**: missing settings and messages are added after an update, existing values are kept
- **Update check**: on startup and with `/coretp version`, against GitHub Releases

## Commands and Permissions

| Command | Executor | Permission | Default |
| :--- | :--- | :--- | :--- |
| `/coretp` | Player | `coretp.use` | Everyone |
| `/coretp help` | Player / Console | `coretp.use` | Everyone |
| `/coretp reload` | Player / Console | `coretp.reload` | Operator |
| `/coretp version` | Player / Console | `coretp.version` | Operator |
| `/tpa <player>` | Player | `coretp.tpa` | Everyone |
| `/tpahere <player>` | Player | `coretp.tpahere` | Everyone |
| `/tpaccept [player]`, `/tpdeny [player]` | Player | `coretp.tpa.respond` | Everyone |
| `/tpcancel` | Player | `coretp.tpa` or `coretp.tpahere` | Everyone |
| `/tptoggle` | Player | `coretp.tptoggle` | Everyone |
| `/home [name]`, `/homes` | Player | `coretp.home` | Everyone |
| `/homes public` | Player | `coretp.home.visit` | Everyone |
| `/sethome [name]` | Player | `coretp.home.set` | Everyone |
| `/delhome <name>` | Player | `coretp.home.delete` | Everyone |
| (public / private switch in the menu) | Player | `coretp.home.public` | Everyone |
| `/warp <name>`, `/warps` | Player | `coretp.warp` | Operator |
| `/setwarp <name> [icon]` | Player | `coretp.warp.set` | Operator |
| `/delwarp <name>` | Player / Console | `coretp.warp.delete` | Operator |
| `/back` | Player | `coretp.back` | Everyone |
| `/eback` | Player | `coretp.back.history` | Everyone |
| `/rtp` | Player | `coretp.rtp` | Everyone |

> `/ctp` is an alias of `/coretp`.

> Home limit: `coretp.home.limit.<number>` (for example `coretp.home.limit.10`); without it, `default-limit` in `home.yml` applies.

> Bypass permissions are granted to nobody by default, operators included: `coretp.bypass.warmup`, `coretp.bypass.cooldown`, `coretp.bypass.cost`, `coretp.bypass.toggle`.

## Usage Examples

### 1. Open warps to every player

Warps are admin-only by default. Grant `coretp.warp` with your permission plugin:

```bash
/setwarp spawn BEACON
/lp group default permission set coretp.warp true
```

### 2. Charge for random teleport and disable it in the Nether

Edit `plugins/CoreTP/modules/rtp.yml`:

```yaml
disabled-worlds:
  - world_nether
cost:
  distance:
    enabled: true
    base: 10.0
    per-block: 0.01
    min: 10.0
    max: 100.0
```

Then run:

```bash
/ctp reload
```

- Pricing needs Vault and an economy plugin
- Players standing in `world_nether` cannot use `/rtp`

### 3. Upgrade from Cback

1. Stop the server
2. Remove the Cback jar and add the CoreTP jar (keep the `plugins/Cback/` folder)
3. Start the server

CoreTP imports the location history, zips the whole `Cback` folder to `plugins/CoreTP/Cback-backup-<time>.zip`, and deletes the original folder only after the backup is verified.

- If the Cback jar is still installed, CoreTP pauses every feature and asks you to remove Cback first
- Old custom messages are not carried over; adjust them in `messages.yml`

## Configurable Messages

All text lives in `plugins/CoreTP/messages.yml`, using `&` colour codes; `{braces}` are variables.

| Section | Description |
| :--- | :--- |
| `general` | Prefix, no permission, feature disabled, reload done, and other shared messages |
| `help` | The text shown by `/coretp help` |
| `teleport` | Countdown, cancellation, cooldown, not enough money, disabled world |
| `tpa`, `home`, `warp`, `back`, `rtp` | Messages for each feature |
| `update` | Update check results |
| `gui` | Chest menu titles, item names, and lore |
| `form` | Bedrock form text |
