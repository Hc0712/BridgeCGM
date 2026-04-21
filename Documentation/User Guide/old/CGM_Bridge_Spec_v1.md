# 640G / EverSense vs Companion App — Protocol 差異說明

本文件說明 xDrip+ 的兩種 Hardware Data Source 模式的技術差異，
以及 CGM Bridge 為何選擇「輸入用 Companion App 邏輯、輸出用 640G/EverSense 廣播」的架構。

---

## 一、名詞定義

| 名詞 | 全稱 | 說明 |
|------|------|------|
| **640G** | Medtronic Guardian Connect / 640G | Medtronic 的 CGM 系統 |
| **EverSense** | Senseonics EverSense | 皮下植入式 CGM 系統 |
| **Companion App** | xDrip+ Companion App mode | xDrip+ 作為「伴侶 App」讀取其他 CGM App 通知的模式 |
| **NS_EMULATOR** | Nightscout Emulator | xDrip+ 內部用來模擬 Nightscout 上傳的廣播機制 |

---

## 二、640G / EverSense 模式

### 2.1 它是什麼？

640G / EverSense 是 xDrip+ 的一種 **Hardware Data Source** 設定選項。
選擇此模式後，xDrip+ 不會自己去連接任何藍牙硬體，
而是被動等待接收 **`com.eveningoutpost.dexdrip.NS_EMULATOR`** 廣播。

### 2.2 接收端原始碼

在 xDrip+ 原始碼中，接收邏輯位於：

```
xDrip+ source:
  app/src/main/java/com/eveningoutpost/dexdrip/NSEmulatorReceiver.java
```

`NSEmulatorReceiver` 是一個 `BroadcastReceiver`，在 `AndroidManifest.xml` 註冊監聽：

```xml
<receiver android:name=".NSEmulatorReceiver">
    <intent-filter>
        <action android:name="com.eveningoutpost.dexdrip.NS_EMULATOR" />
    </intent-filter>
</receiver>
```

### 2.3 廣播格式（Protocol）

| 項目 | 值 |
|------|----|
| **Action** | `com.eveningoutpost.dexdrip.NS_EMULATOR` |
| **Target Package** | `com.eveningoutpost.dexdrip`（Android 8+ 顯式廣播） |
| **Extra: `collection`** | `"entries"`（固定字串） |
| **Extra: `data`** | JSON Array 字串 |

#### JSON Payload 格式

```json
[
  {
    "type": "sgv",
    "date": 1712841600000,
    "sgv": 120.0,
    "direction": "Flat"
  }
]
```

| 欄位 | 類型 | 說明 |
|------|------|------|
| `type` | String | 固定 `"sgv"` |
| `date` | Long | Unix timestamp (毫秒) |
| `sgv` | Double | 血糖值 (mg/dL) |
| `direction` | String | 趨勢方向，對應 xDrip+ `BgReading.slopefromName()` |

#### 趨勢方向有效值

| direction 字串 | 含義 |
|----------------|------|
| `DoubleUp` | 快速上升 (> +3 mg/dL/min) |
| `SingleUp` | 上升 (+2 ~ +3) |
| `FortyFiveUp` | 緩慢上升 (+1 ~ +2) |
| `Flat` | 穩定 (-1 ~ +1) |
| `FortyFiveDown` | 緩慢下降 (-1 ~ -2) |
| `SingleDown` | 下降 (-2 ~ -3) |
| `DoubleDown` | 快速下降 (< -3) |
| `NOT COMPUTABLE` | 無法計算 |
| `RATE OUT OF RANGE` | 超出範圍 |

### 2.4 xDrip+ 收到後做什麼？

```
NSEmulatorReceiver.onReceive()
    → 取出 extras["data"] JSON string
    → 解析 JSONArray
    → 對每個 entry:
        sgv       = entry.getDouble("sgv")
        timestamp = entry.getLong("date")
        direction = entry.getString("direction")
    → 建立 BgReading:
        raw_data      = sgv
        filtered_data = sgv
    → 經過 Calibration Pipeline
    → 儲存到 SQLite
    → 更新 UI 顯示
```

### 2.5 特點

| 特性 | 說明 |
|------|------|
| **無藍牙連接** | 不與任何硬體設備直接通訊 |
| **被動接收** | 純粹等待 Intent 廣播 |
| **單向通訊** | 只收，不回應確認 |
| **跨 App 通訊** | 透過 Android Intent 機制在不同 App 之間傳資料 |
| **原始設計用途** | 讓 Nightscout 或第三方 App 能把血糖資料餵給 xDrip+ |

---

## 三、Companion App 模式

### 3.1 它是什麼？

Companion App 是 xDrip+ 的另一種 **Hardware Data Source** 設定選項。
選擇此模式後，xDrip+ 會啟動 **`UiBasedCollector`**，利用 Android 的
**`NotificationListenerService`** 讀取其他 CGM App 通知欄中顯示的血糖資料。

### 3.2 接收端原始碼

在 xDrip+ 原始碼中，主要邏輯位於：

```
xDrip+ source:
  app/src/main/java/com/eveningoutpost/dexdrip/ui/helpers/UiBasedCollector.java
```

### 3.3 工作原理

```
Android System 發出通知
    → UiBasedCollector (NotificationListenerService) 攔截
    → 過濾：只處理 coOptedPackages 清單中的 App
    → 過濾：只處理 ongoing 通知 (或 coOptedPackagesAll 中的所有通知)
    → 主要路徑：inflate notification.contentView (RemoteViews)
        → 遞迴遍歷 view tree
        → 從每個 TextView 提取文字
    → 備用路徑：讀取 notification extras (EXTRA_TITLE, EXTRA_TEXT 等)
    → 用正則表達式解析血糖值、趨勢、sensor 狀態
    → 建立 BgReading 儲存到資料庫
```

### 3.4 支援的 CGM App（coOptedPackages）

xDrip+ 的 `UiBasedCollector.java` 中 hardcode 了一份支援的 package 清單，
包含數十種 CGM App。CGM Bridge 精簡後只保留三個品牌：

| 品牌 | Package Names |
|------|--------------|
| **Dexcom** | `com.dexcom.g6`, `com.dexcom.g6.region1.mmol` ~ `region11`, `com.dexcom.g7`, `com.dexcom.dexcomone`, `com.dexcom.stelo`, `com.dexcom.d1plus` |
| **AiDEX (Microtech)** | `com.microtech.aidexx`, `com.microtech.aidexx.mgdl`, `com.microtech.aidexx.equil.mmoll` |
| **OTTAi** | `com.ottai.seas`, `com.ottai.tag` |

### 3.5 特點

| 特性 | 說明 |
|------|------|
| **需要 Notification Access 權限** | 必須在系統設定中授權 |
| **依賴 RemoteViews 解析** | 從通知 UI 元件中提取文字 |
| **正則解析** | 用正則表達式從文字中擷取血糖數值 |
| **OEM 依賴性高** | 不同 Android 版本 / 手機廠商的通知 UI 可能不同 |
| **資料已校正** | CGM App 通知上顯示的是原廠校正過的最終值 |
| **無需 CGM 藍牙配對** | 不直接跟 sensor 硬體通訊 |

---

## 四、兩者核心差異比較

| 比較項目 | 640G / EverSense | Companion App |
|---------|-----------------|---------------|
| **資料來源** | 來自 Intent 廣播 (NS_EMULATOR) | 來自系統通知 (NotificationListener) |
| **資料格式** | 結構化 JSON (`sgv`, `date`, `direction`) | 非結構化文字（需正則解析） |
| **傳輸機制** | Android `sendBroadcast()` | Android `NotificationListenerService` |
| **觸發方式** | 外部 App 主動發送 | 系統通知被動截取 |
| **格式穩定性** | ✅ 高 — JSON schema 明確定義 | ⚠️ 中 — CGM App 更新可能改通知格式 |
| **解析複雜度** | 低（JSON parse） | 高（RemoteViews inflate + 正則） |
| **權限需求** | 無特殊權限（廣播接收） | 需要 Notification Access |
| **Android 版本影響** | 小 | 大（RemoteViews API 各版本差異） |
| **Calibration** | 可能需要（如果送的是 raw data） | 通常不需要（已校正值） |
| **延遲** | 低（Intent 直達） | 稍高（通知 → 解析 → 處理） |

---

## 五、CGM Bridge 的架構選擇

### 為什麼輸入用 Companion App 邏輯？

CGM Bridge 的定位是：在手機上已安裝 CGM 原廠 App（AiDEX / OTTAi / Dexcom）的情況下，
擷取這些 App 的通知來轉發。

這些 CGM App 不會發送 `NS_EMULATOR` 廣播，它們只會在 Android 通知欄顯示血糖值。
因此 **只能** 用 NotificationListenerService 的方式來擷取。

```
CGM Sensor ─── Bluetooth ──→ CGM 原廠 App ─── Notification ──→ CGM Bridge
                                                                    │
                                                              (攔截通知)
```

### 為什麼輸出用 640G / EverSense 廣播？

xDrip+ 選 640G / EverSense 模式時，會啟用 `NSEmulatorReceiver`，
被動等待 `NS_EMULATOR` 廣播。這是 **最乾淨、最穩定** 的方式把資料餵進 xDrip+：

1. **格式明確** — JSON schema 有明確定義，不會因 xDrip+ 版本更新而改變
2. **無需額外權限** — 廣播接收不需要特殊 permission
3. **低耦合** — CGM Bridge 和 xDrip+ 之間沒有 binding 或依賴
4. **高可靠** — Intent 是 Android 基礎通訊機制，穩定性極高

```
CGM Bridge ─── NS_EMULATOR Broadcast ──→ xDrip+ (640G/EverSense mode)
                                              │
                                        NSEmulatorReceiver
                                              │
                                         BgReading 存入 DB
                                              │
                                         UI 顯示血糖值
```

### 完整資料流

```
┌─────────────┐    Bluetooth    ┌──────────────┐   Notification   ┌────────────-------------------┐
│  CGM Sensor │ ──────────────→ │ CGM      App │ ───────────────→ │ CGM Bridge                    │
│ (AiDEX/     │                 │ (AiDEX/      │                  │                               │
│  OTTAI/     │                 │  OTTAI/      │                  │ 1. intercept notification     │
│  Dexcom)    │                 │  Dexcom)     │                  │ 2. save glucose data in DB    │
└─────────────┘                 └──────────────┘                  │ 3. brocast                    │
                                                                  │  xDrip+ (640G/EverSense mode) │                      │
                                                                  └─────┬-------------------──────┘
                                                                        │
                                                           NS_EMULATOR broadcast
                                                                        │
                                                                        ▼
                                                                  ┌────────────┐
                                                                  │  xDrip+    │
                                                                  │ (640G /    │
                                                                  │ EverSense) │
                                                                  │            │
                                                                  └────────────┘
```

---

## 六、為什麼不直接用 Companion App 模式？

使用者可能會問：「既然 xDrip+ 自己就有 Companion App 模式可以讀通知，
為什麼還需要 CGM Bridge？」

原因有以下幾點：

| 問題 | 說明 |
|------|------|
| **單一 App 限制** | Android 的 NotificationListenerService 允許多個 App 同時監聽通知，但 xDrip+ 的 Companion App 模式把讀取和處理綁在一起。如果你想在 xDrip+ 之外也處理這些資料（如自製圖表、分享），需要另一個 bridge |
| **擴充性** | CGM Bridge 設計上預留了圖表繪製、WiFi/BT 資料分享等擴充接口，不受 xDrip+ 架構限制 |
| **穩定性** | 將通知讀取和 xDrip+ 解耦，CGM Bridge 可以獨立持續運行並儲存資料，即使 xDrip+ 暫時當掉也不會遺失數據 |
| **資料備份** | CGM Bridge 有自己的 Room 資料庫，資料存兩份（Bridge + xDrip+） |
| **相容性** | 640G/EverSense 的 NS_EMULATOR 協議穩定且簡潔，比讓 xDrip+ 自己用 Companion App 模式解析通知更可靠 |

---

## 七、CGM Bridge 原始碼對應

| 功能 | CGM Bridge 檔案 | 對應 xDrip+ 參考 |
|------|-----------------|------------------|
| 通知攔截 | `notification/CgmNotificationListenerService.kt` | `UiBasedCollector.java` |
| 通知 UI 文字提取 | `notification/RemoteViewsTextExtractor.kt` | `UiBasedCollector.processRemote()` |
| 支援的 CGM package 清單 | `parser/SupportedPackages.kt` | `UiBasedCollector` static initializer |
| 血糖值解析 | `parser/GenericCgmNotificationParser.kt` | `UiBasedCollector` regex logic |
| 趨勢方向對應 | `parser/TrendDirectionMapper.kt` | `BgReading.slopefromName()` |
| 廣播發送 (NS_EMULATOR) | `xdrip/XDripBroadcastSender.kt` | — (這是給 xDrip+ 接收的) |
| 廣播接收 | — (xDrip+ 端) | `NSEmulatorReceiver.java` |

---

## 八、使用者設定摘要

### CGM Bridge 端

1. 安裝並開啟 CGM Bridge
2. 授予 **Notification Access** 權限
3. 排除電池優化

### xDrip+ 端

1. **Settings → Hardware Data Source → 640G / EverSense**
2. **Settings → Less Common Settings → Advanced Calibration → Calibration Plugin → OFF**
3. 排除電池優化

> ⚠️ 不要選 Companion App，否則 xDrip+ 會同時自己讀通知，可能產生衝突或重複資料。

---

## 九、參考資料

| 資源 | 說明 |
|------|------|
| `Incoming_Glucose_Broadcast.md` | NS_EMULATOR 廣播規格文件 |
| `xDrip_Calibration_Guide.md` | Calibration 校正設定指南 |
| xDrip+ 原始碼 `NSEmulatorReceiver.java` | 640G/EverSense 模式的接收端 |
| xDrip+ 原始碼 `UiBasedCollector.java` | Companion App 模式的通知讀取端 |

