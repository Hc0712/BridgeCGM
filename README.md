# BridgeCGM

BridgeCGM 通知讀取 → xDrip+ 640G/EverSense 廣播橋接 App。

支援 AiDEX、OTTAI、Dexcom (Not yet verified) 三款 CGM。

如果同時有兩個以上CGM在發訊息，主圖應能顯示出三條曲線，小圖只畫一條 Primary Input，
如果開啟校正，則顯示條校正過的Primary Input

---

## 功能簡介

| 功能 | 說明 |
|------|------|
| 通知擷取      | 讀取 CGM Companion App 發出的 Notification，解析血糖值、趨勢、時間戳 |
| 廣播轉發      | 以 xDrip+ 640G/EverSense 格式發送 `Intent` broadcast，供 xDrip+ 接收 |
| 本地儲存      | Room DB 存歷史讀數，支援未來圖表與分享功能 |
| 校正數值      | 水平、 線性映射 (尚未完成) |
| 同時多顆CGM   | 支援接收兩顆以上CGM數值   | 

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

## Feature Flags 開關系統

### 核心概念

> **所有功能開關只需編輯一個檔案：`gradle.properties`（專案根目錄）。**
>
> - 不需要改任何 `.kt` 原始碼
> - 不需要改 `build.gradle.kts`
> - 改完後重新 Build 即生效

### Logcat 自行除錯  `gradle.properties` 在哪裡？

```
bridge/                        ← 專案根目錄
├── gradle.properties          ← ★ 這個就是唯一的設定檔 ★
├── build.gradle.kts
├── settings.gradle.kts
└── app/
    └── ...
```

在 Android Studio 左側 **Project 面板** → 展開專案根目錄 → 直接點開 `gradle.properties`。

### 設定檔長什麼樣？

打開 `gradle.properties`，底部可以看到所有 Feature Flags：

```properties
# ======================================================================
#  BridgeCGM Feature Flags
#
#  每個 flag 有兩個值：
#    .debug   = Build Variant 選 debug 時使用的值
#    .release = Build Variant 選 release 時使用的值
#
#  改完存檔 → 重新 Build → 自動生效
# ======================================================================

# 詳細 log 開關（控制 DebugTrace.v() 是否輸出）
cgmbridge.verboseDump.debug=true
cgmbridge.verboseDump.release=false

# BG 圖表顯示（預留，未來使用）
cgmbridge.chartEnabled.debug=true
cgmbridge.chartEnabled.release=true

# WiFi / Bluetooth 資料分享（預留，未來使用）
cgmbridge.sharingEnabled.debug=false
cgmbridge.sharingEnabled.release=false
```

###  Logcat debug message 開關總覽

| Flag | gradle.properties key | Debug | Release | 用途 |
|------|-----------------------|-------|---------|------|
| verboseDump | `cgmbridge.verboseDump` | `true` | `false` | 詳細 log（`DebugTrace.v()`） |
| chartEnabled | `cgmbridge.chartEnabled` | `true` | `true` | BG 圖表顯示（預留） |
| sharingEnabled | `cgmbridge.sharingEnabled` | `false` | `false` | WiFi/BT 資料分享（預留） |

### 如何修改（日常操作）

**只需要 2 步：**

1. **打開 `gradle.properties`**，找到要改的 flag，改 `true` 或 `false`
2. **重新 Build**（Build → Build APK 或 Ctrl+F9）

#### 範例 1：Debug 時關掉 verbose log

```properties
cgmbridge.verboseDump.debug=false      ← 改成 false
cgmbridge.verboseDump.release=false
```

#### 範例 2：Debug 時開啟 verbose log（預設值）

```properties
cgmbridge.verboseDump.debug=true       ← 改成 true
cgmbridge.verboseDump.release=false
```

#### 範例 3：Release 時也開啟某功能

```properties
cgmbridge.sharingEnabled.debug=true
cgmbridge.sharingEnabled.release=true  ← release 也開
```

### 資料流（內部原理，不需要手動操作）

```
gradle.properties                    ← 你只需要編輯這裡
    cgmbridge.verboseDump.debug=true
    cgmbridge.verboseDump.release=false
                ↓
build.gradle.kts                     自動讀取，注入 BuildConfig（不需要改）
                ↓
BuildConfig.VERBOSE_DUMP             編譯時自動產生的常數（不需要改）
                ↓
FeatureFlags.verboseDump             Kotlin val 唯讀（不需要改）
                ↓
DebugTrace.verbose                   程式內部使用（不需要改）
```

### 如何確認設定有生效？

App 啟動時會自動在 logcat 印出所有 flag 目前的值：

```
D/BridgeCGMTrace: TRACE_POINT:APP-INIT | FeatureFlags { verboseDump=true, chartEnabled=true, sharingEnabled=false }
```

在 Android Studio 的 **Logcat** 視窗輸入過濾：

```
tag:BridgeCGMTrace APP-INIT
```

或用 adb 指令：

```bash
adb logcat -s BridgeCGMTrace | grep APP-INIT
```

### 如何選擇 Debug / Release Build？

在 Android Studio：

1. **View → Tool Windows → Build Variants**（或左側面板點 **Build Variants**）
2. `:app` 模組的下拉選單選 `debug` 或 `release`
3. 正常 Build / Run 即可

或用指令：

```bash
./gradlew assembleDebug      # 使用所有 .debug 的值
./gradlew assembleRelease    # 使用所有 .release 的值
```

---

## 新增一個 Flag（給開發者）

### 步驟 1：在 `gradle.properties` 加兩行

```properties
cgmbridge.myNewFeature.debug=true
cgmbridge.myNewFeature.release=false
```

### 步驟 2：在 `app/build.gradle.kts` 加一行讀取 + 兩行注入

```kotlin
val (myNewFeatureD, myNewFeatureR) = flag("myNewFeature", true, false)

// 在 debug block 內加：
buildConfigField("boolean", "MY_NEW_FEATURE", "$myNewFeatureD")
// 在 release block 內加：
buildConfigField("boolean", "MY_NEW_FEATURE", "$myNewFeatureR")
```

### 步驟 3：在 `FeatureFlags.kt` 加一個 val

```kotlin
val myNewFeature: Boolean = BuildConfig.MY_NEW_FEATURE
```

完成後，未來只需要在 `gradle.properties` 改值即可。

---

## DebugTrace 使用方式

`DebugTrace` 是本專案的集中式 log 工具。

`verbose` 開關由 `FeatureFlags.verboseDump` 決定，而 `verboseDump` 的值來自 `gradle.properties`。

### Log 等級一覽

| 方法 | 用途 | 受 verbose 開關控制？ |
|------|------|----------------------|
| `DebugTrace.t(pointId, msg)` | **Always-on** 追蹤點 | ❌ 永遠輸出 |
| `DebugTrace.v(pointId) { … }` | **Verbose** 詳細 dump | ✅ verbose=false 時完全不執行 |
| `DebugTrace.w(pointId, msg)` | 警告 | ❌ 永遠輸出 |
| `DebugTrace.e(pointId, msg)` | 錯誤 | ❌ 永遠輸出 |

> **關鍵設計**：`v()` 是 `inline fun`，當 `verbose = false` 時傳入的 lambda 根本不會被呼叫，
> 不會產生任何字串拼接或物件分配，**release 效能零影響**。

想開/關 verbose log？**去 `gradle.properties` 改 `cgmbridge.verboseDump.debug`，重新 Build。**

---

## Logcat 過濾指令

```bash
# 所有 DebugTrace 輸出
adb logcat -s BridgeCGMTrace

# 只看 verbose 詳細 dump
adb logcat -s BridgeCGMTrace | grep "VERBOSE:"

# 只看 always-on 追蹤點
adb logcat -s BridgeCGMTrace | grep "TRACE_POINT:"

# 只看啟動時 flag 狀態
adb logcat -s BridgeCGMTrace | grep "APP-INIT"
```

---

## 相關檔案索引

| 檔案 | 角色 | 需要手動編輯？ |
|------|------|---------------|
| `gradle.properties` | **唯一設定檔**：所有 flag 的 debug / release 值 | ✅ **改這裡就好** |
| `config/FeatureFlags.kt` | 集中式功能開關（唯讀 val，來自 BuildConfig） | ❌ 不需要改 |
| `util/DebugTrace.kt` | Log 工具（verbose 由 FeatureFlags 決定） | ❌ 不需要改 |
| `app/build.gradle.kts` | 讀取 gradle.properties → 注入 BuildConfig | ❌ 除非新增 flag |
| `BridgeCGMApplication.kt` | App 啟動時印出 flag summary | ❌ 不需要改 |
