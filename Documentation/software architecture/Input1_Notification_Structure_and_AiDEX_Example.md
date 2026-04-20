# Android Notification 結構與 AiDEX 血糖資料對應說明

---

## 一、Android Notification 物件層次結構

當任何 App 發送一個通知（例如 AiDEX 顯示血糖值），Android 系統會包裝成一個 **`StatusBarNotification`（SBN）** 物件送給所有已註冊的 `NotificationListenerService`。

整個物件的層次結構如下：

```
StatusBarNotification (sbn)
│
├── packageName          ─ "com.microtech.aidexx"  （發送通知的 App package）
├── id                   ─ 1                       （通知的數字 ID）
├── tag                  ─ null 或字串              （可選的通知標籤）
├── key                  ─ "0|com.microtech.aidexx|1|null|10088"
├── postTime             ─ 1744176300000            （通知發送時間，epoch ms）
├── isOngoing            ─ true                     （是否為持續性通知）
├── isClearable          ─ false                    （使用者能否滑掉）
│
└── notification         ─ Notification 物件
    │
    ├── when             ─ 1744176300000            （通知時間戳，epoch ms）
    ├── channelId        ─ "bg_reading_channel"     （通知頻道 ID）
    ├── category         ─ null 或 "status"
    ├── flags            ─ 0x62 (FLAG_ONGOING_EVENT | FLAG_NO_CLEAR | ...)
    ├── number           ─ 0
    ├── visibility        ─ 0 (VISIBILITY_PRIVATE)
    ├── tickerText       ─ "120 mg/dL ↗"           （跑馬燈文字，可能為 null）
    │
    ├── contentView      ─ RemoteViews 物件（★ 主要資料來源）
    │   └── (inflate 後)
    │       └── ViewGroup (LinearLayout / FrameLayout)
    │           ├── TextView  →  "AiDEX"           （App 名稱）
    │           ├── TextView  →  "120"              （★ 血糖數值）
    │           ├── TextView  →  "mg/dL"            （單位）
    │           ├── TextView  →  "↗"               （趨勢箭頭）
    │           └── TextView  →  "14:05"            （時間）
    │
    └── extras           ─ Bundle（★ 備用資料來源）
        ├── android.title       →  "AiDEX"          (EXTRA_TITLE)
        ├── android.text        →  "120 mg/dL ↗"    (EXTRA_TEXT)
        ├── android.subText     →  null 或 "14:05"   (EXTRA_SUB_TEXT)
        ├── android.bigText     →  null              (EXTRA_BIG_TEXT)
        ├── android.infoText    →  null              (EXTRA_INFO_TEXT)
        ├── android.summaryText →  null              (EXTRA_SUMMARY_TEXT)
        ├── android.textLines   →  null              (EXTRA_TEXT_LINES, CharSequence[])
        ├── android.icon        →  (App icon resource)
        ├── android.largeIcon   →  (Bitmap)
        ├── android.showChronometer → false
        └── android.template    →  "android.app.Notification$BigTextStyle"
```

---

## 二、兩條資料擷取路徑（對應 xDrip+ UiBasedCollector）

我們的 Bridge App 模仿 xDrip+ 的 `UiBasedCollector.java`，有 **兩條** 資料擷取路徑：

### 路徑 1：PRIMARY — contentView (RemoteViews) 展開

```
notification.contentView  (RemoteViews)
        ↓
RemoteViewsTextExtractor.extractTexts()
        ↓
  inflate 成真實 View 樹
        ↓
  遞迴走訪所有 visible TextView
        ↓
  收集所有文字 → List<String>
```

**這是最準確的路徑**，因為它拿到的是使用者在狀態列/通知欄實際看到的每一個文字元素。

以 AiDEX 為例，inflate 後會得到類似：
```
contentViewTexts = ["AiDEX", "120", "mg/dL", "↗", "14:05"]
```

### 路徑 2：FALLBACK — Notification Extras

```
notification.extras (Bundle)
        ↓
  讀取 EXTRA_TITLE, EXTRA_TEXT, EXTRA_SUB_TEXT, etc.
        ↓
  組合成一個文字字串
```

以 AiDEX 為例：
```
title   = "AiDEX"
text    = "120 mg/dL ↗"
subText = null
```

**為什麼需要兩條路徑？**
- 有些 CGM App（例如某些 Dexcom 版本）的 `contentView` 為 null（不提供自訂佈局），只能靠 extras。
- 有些 CGM App 的 extras 資訊不完整（例如趨勢箭頭只出現在 contentView 裡）。
- xDrip+ 的做法是：**優先用 contentView，失敗才用 extras**。我們完全照搬。

---

## 三、Logcat 範例：AiDEX 血糖通知的完整 Dump

以下是 `NotificationDebugDumper` 在 **verbose 模式** 下會輸出的典型 AiDEX 血糖通知 logcat（以 120 mg/dL、趨勢微升為例）：

### 3.1 一行式簡要 Dump（always-on，所有 build 都會輸出）

```
D/BridgeCGMTrace: TRACE_POINT:NL-DUMP | pkg=com.microtech.aidexx id=1 ongoing=true title=AiDEX text=120 mg/dL ↗ hasCV=true
```

**欄位對應：**

| logcat 欄位 | 來源 | 說明 |
|-------------|------|------|
| `pkg=com.microtech.aidexx` | `sbn.packageName` | AiDEX 的 Android package name |
| `id=1` | `sbn.id` | 通知 ID（AiDEX 通常用固定 ID 持續更新同一個通知） |
| `ongoing=true` | `sbn.isOngoing` | 持續性通知（常駐在通知欄） |
| `title=AiDEX` | `extras.EXTRA_TITLE` | 通知標題 |
| `text=120 mg/dL ↗` | `extras.EXTRA_TEXT` | 通知正文（★ 血糖值+單位+趨勢） |
| `hasCV=true` | `notification.contentView != null` | 是否有自訂 RemoteViews 佈局 |

### 3.2 Verbose 詳細 Dump（只在 debug + verboseDump=true 時輸出）

```
D/BridgeCGMTrace: VERBOSE:NL-V-META | tag=null key=0|com.microtech.aidexx|1|null|10088 groupKey=0|com.microtech.aidexx|1|null|10088 postTime=1744176300000 isClearable=false
D/BridgeCGMTrace: VERBOSE:NL-V-NOTIF | when=1744176300000 channelId=bg_channel category= flags=98 number=0 visibility=0 timeoutAfter=0
D/BridgeCGMTrace: VERBOSE:NL-V-TEXT | sub= big= summary= info= ticker=120 mg/dL ↗
D/BridgeCGMTrace: VERBOSE:NL-V-KEYS | android.icon,android.largeIcon,android.showChronometer,android.text,android.title
D/BridgeCGMTrace: VERBOSE:NL-V-EXTRA | android.icon=2131230xxx
D/BridgeCGMTrace: VERBOSE:NL-V-EXTRA | android.largeIcon=android.graphics.Bitmap@xxxxxxx
D/BridgeCGMTrace: VERBOSE:NL-V-EXTRA | android.showChronometer=false
D/BridgeCGMTrace: VERBOSE:NL-V-EXTRA | android.text=120 mg/dL ↗
D/BridgeCGMTrace: VERBOSE:NL-V-EXTRA | android.title=AiDEX
```

**欄位對應：**

| logcat 行 | 說明 |
|-----------|------|
| `NL-V-META` | SBN 層級的元資料（tag、key、postTime） |
| `NL-V-NOTIF` | Notification 物件自身屬性（when、channelId、flags） |
| `NL-V-TEXT` | 所有標準文字欄位（subText、bigText、summaryText、infoText、ticker） |
| `NL-V-KEYS` | extras Bundle 裡所有 key 的列表 |
| `NL-V-EXTRA` | 每個 key 對應的值（逐行輸出） |

### 3.3 RemoteViews 展開結果

```
D/BridgeCGMTrace: VERBOSE:RV-EXTRACT | Found 5 text views in contentView
D/BridgeCGMTrace: VERBOSE:RV-TV | text=>AiDEX< desc=><
D/BridgeCGMTrace: VERBOSE:RV-TV | text=>120< desc=><
D/BridgeCGMTrace: VERBOSE:RV-TV | text=>mg/dL< desc=><
D/BridgeCGMTrace: VERBOSE:RV-TV | text=>↗< desc=><
D/BridgeCGMTrace: VERBOSE:RV-TV | text=>14:05< desc=><
```

**欄位對應：**

| 文字 | 意義 | 在 Parser 中的用途 |
|------|------|-------------------|
| `AiDEX` | App 名稱 | 被 `CgmStringFilter` 過濾掉（不是數字） |
| `120` | ★ **血糖值**（mg/dL） | `tryExtractFiltered()` → 匹配 `reBareInt` → 取得 120 |
| `mg/dL` | 單位文字 | 被 `CgmStringFilter.basicFilter()` 移除 |
| `↗` | 趨勢箭頭 | `extractDirection()` → `TrendDirectionMapper.map("↗")` → `"FortyFiveUp"` |
| `14:05` | 讀數時間 | `NotificationTimestampExtractor` → 今天 14:05 的 epoch ms |

### 3.4 解析與廣播的 Trace

```
D/BridgeCGMTrace: TRACE_POINT:NL-ON_POSTED | pkg=com.microtech.aidexx id=1 ongoing=true
D/BridgeCGMTrace: TRACE_POINT:PARSE-IN | pkg=com.microtech.aidexx cvTexts=5 title=AiDEX
D/BridgeCGMTrace: TRACE_POINT:PARSE-OUT | ts=1744176300000 v=120 dir=FortyFiveUp status=null alert=null
D/BridgeCGMTrace: TRACE_POINT:BCAST-SENT | Sent to xDrip NS_EMULATOR
```

---

## 四、完整資料流圖（從 CGM 到 xDrip+）

```
┌───────────────┐     Bluetooth    ┌──────────────────┐
│   CGM         │  ─────────────→  │  AiDEX App       │
│  (on body)    │                  │ (com.micro-      │
└───────────────┘                  │  tech.aidexx)    │
                                   └───────┬──────────┘
                                           │
                                    Android 系統
                                    NotificationManager
                                    .notify(id, notification)
                                           │
                                           ▼
                              ┌────────────────────────────┐
                              │  StatusBarNotification     │
                              │  ┌──────────────────────┐  │
                              │  │  Notification        │  │
                              │  │  ├─ contentView      │  │  ← RemoteViews (主要資料)
                              │  │  │  └─ TextViews:    │  │
                              │  │  │     "120"         │  │  ← ★ 血糖值
                              │  │  │     "mg/dL"       │  │
                              │  │  │     "↗"           │  │  ← ★ 趨勢
                              │  │  │     "14:05"       │  │  ← ★ 時間
                              │  │  │                   │  │
                              │  │  └─ extras (Bundle)  │  │  ← 備用資料
                              │  │     title="AiDEX"    │  │
                              │  │     text="120 mg/dL" │  │
                              │  └──────────────────────┘  │
                              └────────────┬───────────────┘
                                           │
                                   Android 推送給
                                   NotificationListenerService
                                           │
                                           ▼
                              ┌──────────────────────────┐
                              │  CgmNotificationListener │  ← 我們的 Bridge App
                              │  Service                 │
                              │                          │
                              │  1. NotificationDebug    │  → logcat dump
                              │     Dumper.dumpPosted()  │
                              │                          │
                              │  2. SupportedPackages    │  → 過濾只收 AiDEX/
                              │     .isSupported()       │    OTTAI/Dexcom
                              │                          │
                              │  3. RemoteViewsText      │  → inflate contentView
                              │     Extractor            │    取出 ["120","↗"...]
                              │     .extractTexts()      │
                              │                          │
                              │  4. GenericCgmNotifi-    │  → 解析血糖值+趨勢+時間
                              │     cationParser         │
                              │     .parse()             │
                              │                          │
                              │  5. BgReadingImporter    │  → 存入 Room DB
                              │     .import()            │    (去重、驗證)
                              │                          │
                              │  6. XDripBroadcast       │  → 發送 Intent 廣播
                              │     Sender               │
                              │     .sendGlucose()       │
                              └────────────┬─────────────┘
                                           │
                                    Intent broadcast:
                                    action = "com.eveningoutpost.dexdrip.NS_EMULATOR"
                                    package = "com.eveningoutpost.dexdrip"
                                    extras:
                                      collection = "entries"
                                      data = '[{"type":"sgv","date":1744176300000,"sgv":120.0,"direction":"FortyFiveUp"}]'
                                           │
                                           ▼
                              ┌──────────────────────────┐
                              │  xDrip+                  │
                              │  Hardware Data Source:   │
                              │  640G / EverSense        │
                              │                          │
                              │  NSEmulatorReceiver      │
                              │  → Parse JSON            │
                              │  → Write BgReading       │
                              │  → Plot & Warning        │
                              └──────────────────────────┘
```
### Step
Function/Class Example
Purpose
#### 1. Data Reception
onReceive() / onNotificationPosted()
Listen for incoming data
#### 2. Parsing
parseNotification()
Extract glucose info
#### 3. Validation
filterValidReadings()
Ensure data is valid
#### 4. Save to DB "BgReadingEntity"
insertReading()
Persist data
#### 5. Retrieve from DB
getReadingsForPeriod()
Fetch data for graph
#### 6. Prepare for Chart
renderChart()
Deduplicate, format, prep data
#### 7. Plot on Graph
chart.setData() / chart.invalidate()
Display on UI

---

## 五、Notification 結構中每個欄位的詳細解說

### 5.1 StatusBarNotification（SBN）層

| 欄位 | 型別 | 說明 | AiDEX 範例 |
|------|------|------|-----------|
| `packageName` | String | 發送通知的 App 的 package name | `"com.microtech.aidexx"` |
| `id` | int | 通知的數字 ID，同 package + 同 id 會更新同一通知 | `1` |
| `tag` | String? | 可選的字串標籤，與 id 一起構成唯一識別 | `null` |
| `key` | String | 系統分配的唯一 key | `"0\|com.microtech.aidexx\|1\|null\|10088"` |
| `postTime` | Long | 通知發布的時間（epoch ms） | `1744176300000` |
| `isOngoing` | Boolean | 是否為持續性通知（FLAG_ONGOING_EVENT） | `true` |
| `isClearable` | Boolean | 使用者能否手動清除 | `false` |
| `groupKey` | String | 通知群組的 key | 同 key |

**為什麼 `isOngoing` 很重要？**

CGM App 通常會用一個 **ongoing（持續性）通知** 常駐在通知欄，每次新血糖讀數就 **更新** 同一個通知（同 id）。xDrip+ 的 `UiBasedCollector` 只處理 ongoing 通知（除了少數例外），我們也照做。AiDEX 被列在 `processAll` 集合中，所以即使是非 ongoing 的通知也會處理。

### 5.2 Notification 層

| 欄位 | 型別 | 說明 | AiDEX 範例 |
|------|------|------|-----------|
| `when` | Long | 通知自帶的時間戳 | `1744176300000` |
| `channelId` | String | Android 8+ 通知頻道 ID | `"bg_channel"` |
| `category` | String? | 通知分類（如 "status", "alarm"） | `null` |
| `flags` | int | 位元旗標組合 | `0x62` |
| `tickerText` | CharSequence? | 舊式跑馬燈文字 | `"120 mg/dL ↗"` |
| `contentView` | RemoteViews? | ★ 自訂通知佈局（主要資料來源） | 非 null |
| `bigContentView` | RemoteViews? | 展開式佈局 | 可能為 null |
| `extras` | Bundle | ★ 標準通知附加資料（備用資料來源） | 見下表 |

### 5.3 Notification.extras Bundle

| Key 常數 | extras 中的 key 字串 | 型別 | AiDEX 範例 |
|----------|---------------------|------|-----------|
| `EXTRA_TITLE` | `"android.title"` | CharSequence | `"AiDEX"` |
| `EXTRA_TEXT` | `"android.text"` | CharSequence | `"120 mg/dL ↗"` |
| `EXTRA_SUB_TEXT` | `"android.subText"` | CharSequence? | `null` |
| `EXTRA_BIG_TEXT` | `"android.bigText"` | CharSequence? | `null` |
| `EXTRA_INFO_TEXT` | `"android.infoText"` | CharSequence? | `null` |
| `EXTRA_SUMMARY_TEXT` | `"android.summaryText"` | CharSequence? | `null` |
| `EXTRA_TEXT_LINES` | `"android.textLines"` | CharSequence[]? | `null` |
| `EXTRA_TEMPLATE` | `"android.template"` | String | `"android.app.Notification$BigTextStyle"` |

### 5.4 contentView (RemoteViews) 展開後的 View 樹

`RemoteViews` 是 Android 跨行程 UI 的機制。通知的自訂佈局是在 CGM App 的行程中建立 RemoteViews，透過系統傳給通知欄顯示。

我們的 `RemoteViewsTextExtractor` 所做的事：

```kotlin
// 1. 拿到 RemoteViews
val contentView: RemoteViews = notification.contentView

// 2. inflate 成真實 View（在我們自己的行程中）
val root = contentView.apply(context, null).rootView

// 3. 遞迴走訪 View 樹，收集所有 visible 的 TextView
collectTextViews(out, root)

// 4. 取出每個 TextView 的 text 屬性
tvs.mapNotNull { it.text?.toString() }
```

AiDEX 通知的 View 樹（示意）：

```
LinearLayout (root, VISIBLE)
├── ImageView (App icon, VISIBLE) ← 忽略，不是 TextView
├── LinearLayout (VISIBLE)
│   ├── TextView: "AiDEX"        ← 收集 ✓
│   └── TextView: "14:05"        ← 收集 ✓
├── LinearLayout (VISIBLE)
│   ├── TextView: "120"           ← 收集 ✓ ★ 血糖值！
│   ├── TextView: "mg/dL"         ← 收集 ✓
│   └── TextView: "↗"            ← 收集 ✓ ★ 趨勢！
└── ImageView (GONE) ← visibility=GONE，被忽略
```

收集結果：`["AiDEX", "14:05", "120", "mg/dL", "↗"]`

---

## 六、Parser 如何從收集到的文字中提取血糖值

`GenericCgmNotificationParser` 的 **Primary Path**（contentView 路徑）處理流程：

```
contentViewTexts = ["AiDEX", "14:05", "120", "mg/dL", "↗"]

 對每個 text 執行：
 ┌─────────────────────────────────────────────────────────────┐
 │  "AiDEX"                                                   │
 │    → CgmStringFilter.filter("AiDEX")                       │
 │    → arrowFilter → basicFilter → trim                      │
 │    → "AiDEX"                                               │
 │    → reBareInt (^\-?\d{2,3}$) 不匹配                        │
 │    → isValidMmol? No                                       │
 │    → return -1  （不是血糖值）                                │
 ├─────────────────────────────────────────────────────────────┤
 │  "14:05"                                                   │
 │    → filter → "14:05"                                      │
 │    → reBareInt 不匹配                                       │
 │    → return -1                                             │
 ├─────────────────────────────────────────────────────────────┤
 │  "120"                                                     │
 │    → filter → "120"                                        │
 │    → reBareInt (^\-?\d{2,3}$) ✓ 匹配！                      │
 │    → return 120  ★ 找到血糖值！                              │
 ├─────────────────────────────────────────────────────────────┤
 │  "mg/dL"                                                   │
 │    → filter → basicFilter 移除 "mg/dL" → ""                │
 │    → 空字串 → return -1                                     │
 ├─────────────────────────────────────────────────────────────┤
 │  "↗"                                                      │
 │    → filter → arrowFilter 移除 ↗ → ""                      │
 │    → 空字串 → return -1                                     │
 └─────────────────────────────────────────────────────────────┘

 結果：matches=1, mgdl=120, 在 20..600 範圍內 ✓

 趨勢提取：
   extrasCombined = "AiDEX 120 mg/dL ↗"
   reTrend.find() → "↗"
   TrendDirectionMapper.map("↗") → "FortyFiveUp"

 時間提取：
   extrasCombined 中找到 "14:05"
   NotificationTimestampExtractor → 今天 14:05 的 epoch ms

 最終結果：
   GlucoseSample(
     timestampMs = 1744176300000,
     valueMgdl   = 120,
     direction   = "FortyFiveUp",
     sourcePackage = "com.microtech.aidexx",
     rawText     = "AiDEX 120 mg/dL ↗",
     sensorStatus = null,
     alertText    = null
   )
```

---

## 七、原始問題：為什麼 notification 收不到？

最初版本收不到 AiDEX notification 資料的原因有幾個關鍵問題，修復如下：

### 問題 1：isOngoing 過濾太嚴格

**原因**：早期版本只處理 `isOngoing = true` 的通知，但 AiDEX 的某些通知（例如告警、校正提醒）不是 ongoing 的。

**修復**：加入 `SupportedPackages.shouldProcessAll()` 判斷，AiDEX 的所有 package 變體都被列入 `processAll` 集合，這樣即使非 ongoing 的通知也會被處理（模仿 xDrip+ 的 `coOptedPackagesAll`）：

```kotlin
// CgmNotificationListenerService.kt — 第 101-109 行
val isOngoing = sbn.isOngoing
if (!isOngoing && !SupportedPackages.shouldProcessAll(sbn.packageName)) {
    return  // 只有不在 processAll 的非 ongoing 通知才跳過
}
```

### 問題 2：contentView inflate 失敗後沒有 Fallback

**原因**：某些情況下 `notification.contentView` 可能為 `null`（例如 Android 系統版本差異、App 版本差異），或 inflate 時拋出異常。早期版本沒有 Fallback 機制。

**修復**：
1. `RemoteViewsTextExtractor.extractTexts()` 加入 try-catch，失敗時回傳空 list 而非 crash
2. `GenericCgmNotificationParser.parse()` 在 contentViewTexts 為空時，自動走 **Fallback Path**（用 extras 的 title/text 解析）

### 問題 3：Package Name 未完整涵蓋

**原因**：AiDEX 有多個變體（`com.microtech.aidexx`、`com.microtech.aidexx.mgdl`、`com.microtech.aidexx.equil.mmoll`），早期可能遺漏。

**修復**：`SupportedPackages` 完整列出所有已知 package 變體，包含 mg/dL 與 mmol/L 版本。

### 問題 4：NotificationListenerService 連線斷開後未重連

**原因**：Android 系統在某些情況下（省電、記憶體不足）會斷開 NotificationListener 連線。

**修復**：
```kotlin
override fun onListenerDisconnected() {
    super.onListenerDisconnected()
    requestRebind(ComponentName(this, CgmNotificationListenerService::class.java))
}
```

---

## 八、不同 CGM App 的 Notification 差異比較

| 特性 | AiDEX | OTTAI | Dexcom G6/G7 |
|------|-------|-------|-------------|
| contentView 有無 | ✓ 有 | ✓ 有 | 有些版本有，有些 null |
| isOngoing | ✓ 通常 true | ✓ 通常 true | ✓ true |
| extras.EXTRA_TEXT 格式 | `"120 mg/dL ↗"` | `"5.6 mmol/L →"` | `"120 mg/dL"` |
| 趨勢箭頭位置 | extras text 或 contentView | extras text 或 contentView | 通常在 contentView |
| 在 processAll 中？ | ✓ 是 | ✓ 是 | 只有 ONE/Stelo/D1Plus |
| 時間戳 | contentView 或 postTime | contentView 或 postTime | postTime |

---

## 九、如何自行查看 AiDEX 的通知 Dump

### 步驟 1：確認 verbose dump 已開啟

在 `gradle.properties` 確認：
```properties
cgmbridge.verboseDump.debug=true
```

### 步驟 2：安裝 debug build 並授予通知存取權限

### 步驟 3：在 logcat 過濾

```bash
# 看所有通知 dump
adb logcat -s CgmBridgeTrace | grep "NL-DUMP\|NL-V-\|RV-"

# 只看 AiDEX 的通知
adb logcat -s CgmBridgeTrace | grep "aidexx"

# 看完整解析流程
adb logcat -s CgmBridgeTrace | grep "NL-DUMP\|PARSE-IN\|PARSE-OUT\|BCAST-SENT"
```

---

## 附錄：Notification Flags 位元說明

```
flags=0x62 (十進位 98) 拆解：
  0x02 = FLAG_ONGOING_EVENT      → 持續性通知
  0x20 = FLAG_NO_CLEAR           → 不可手動清除
  0x40 = FLAG_FOREGROUND_SERVICE → 來自前景服務

常見組合：
  0x62 = 持續性 + 不可清除 + 前景服務 （典型 CGM 血糖通知）
  0x10 = FLAG_HIGH_PRIORITY      （高優先級，用於告警）
```
