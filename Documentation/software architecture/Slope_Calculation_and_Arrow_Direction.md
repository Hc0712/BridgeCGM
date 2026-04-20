# Slope 計算與血糖趨勢箭頭判定規則

本文件說明 CGM Bridge 如何計算血糖變化斜率 (slope)，
以及如何將斜率對應為血糖數值旁顯示的趨勢箭頭 (direction arrow)。

---

## 一、總覽：資料流

```
CGM 通知 → 解析血糖值 → 寫入 Room DB → 取最近兩筆 → 計算 slope → 判定箭頭方向
                                                                      ↓
                                                              UI 顯示箭頭
                                                              xDrip+ 廣播
```

核心元件位於 `tw.yourcompany.cgmbridge.parser` 套件：

| 類別 | 職責 |
|------|------|
| `SlopeDirectionCalculator` | 從 DB 讀數計算 slope 並判定箭頭方向 |
| `TrendDirectionMapper` | 將通知文字中的箭頭符號 (↑↗→↘↓) 映射為 xDrip 方向字串 |
| `GenericCgmNotificationParser` | 從通知中解析血糖值與原始趨勢符號 |

---

## 二、Slope（斜率）計算

### 2.1 計算公式

Slope 的計算完全鏡像 xDrip+ 2025.09.05 版的 `BgReading.find_slope()` 邏輯：

```
slope (mg/dL per minute) = (current_value − previous_value) / (current_time − previous_time)
```

- **current** = DB 中最新的一筆血糖讀數
- **previous** = DB 中第二新的一筆血糖讀數
- 時間差以**分鐘**為單位
- 血糖值以 **mg/dL** 為單位（DB 統一存 mg/dL）
- slope 為正數代表血糖**上升**，負數代表**下降**

### 2.2 Delta（五分鐘正規化差值）

Delta 用於 UI 顯示「過去 5 分鐘血糖變化了多少」：

```
deltaPerFiveMin = slopePerMinute × 5.0
```

此邏輯鏡像 xDrip+ `BestGlucose.unitizedDeltaString()` (line 318)。

### 2.3 有效性判定

| 條件 | 行為 |
|------|------|
| DB 中只有 0 筆讀數 | 回傳 `SlopeResult.EMPTY`（isValid = false） |
| DB 中只有 1 筆讀數 | slope = 0，方向 = `Flat`，isValid = true |
| 兩筆時間差 ≤ 0 | 回傳 `SlopeResult.EMPTY`（isValid = false） |
| 兩筆時間差 > 20 分鐘 | slope 仍正常計算，但 delta 設為 `NaN`（UI 顯示 `???`） |
| 兩筆時間差 ≤ 20 分鐘 | slope 和 delta 均有效 |

20 分鐘的最大間隔常數 (`MAX_DELTA_GAP_MS`) 與 xDrip+ 一致。

---

## 三、箭頭方向判定規則

### 3.1 Slope → Direction 對照表

方向判定鏡像 xDrip+ `Dex_Constants.TREND_ARROW_VALUES.getTrend()` 的閾值：

| slope (mg/dL per minute) | Direction 名稱 | Unicode 箭頭 | 意義 |
|:------------------------:|:--------------:|:------------:|:----:|
| > 3.5                    | `DoubleUp`     | ⇈ (`\u21C8`) | 急速上升 |
| > 2.0                    | `SingleUp`     | ↑ (`\u2191`) | 快速上升 |
| > 1.0                    | `FortyFiveUp`  | ↗ (`\u2197`) | 緩慢上升 |
| > −1.0                   | `Flat`         | → (`\u2192`) | 平穩 |
| > −2.0                   | `FortyFiveDown`| ↘ (`\u2198`) | 緩慢下降 |
| > −3.5                   | `SingleDown`   | ↓ (`\u2193`) | 快速下降 |
| ≤ −3.5                   | `DoubleDown`   | ⇊ (`\u21CA`) | 急速下降 |

判定邏輯為**由上往下逐一比對**，第一個滿足 `slope > threshold` 的即為結果。
若全部都不滿足（slope ≤ −3.5），回傳 `DoubleDown`。

### 3.2 程式碼對照

```kotlin
// SlopeDirectionCalculator.kt

private val THRESHOLDS = listOf(
    Threshold(3.5, "DoubleUp"),       // slope >  3.5
    Threshold(2.0, "SingleUp"),       // slope >  2.0
    Threshold(1.0, "FortyFiveUp"),    // slope >  1.0
    Threshold(-1.0, "Flat"),          // slope > -1.0
    Threshold(-2.0, "FortyFiveDown"), // slope > -2.0
    Threshold(-3.5, "SingleDown")     // slope > -3.5
    // 其餘 → DoubleDown
)

fun slopeToDirection(slopePerMinute: Double): String {
    for (t in THRESHOLDS) {
        if (slopePerMinute > t.minSlope) return t.name
    }
    return "DoubleDown"
}
```

### 3.3 等效 5 分鐘 Delta 對照

由於 `delta = slope × 5`，以下為等效的 5 分鐘 delta 對照表：

| 5min Delta (mg/dL) | Direction | 箭頭 |
|:-------------------:|:---------:|:----:|
| > +17.5             | DoubleUp      | ⇈ |
| > +10.0             | SingleUp      | ↑ |
| > +5.0              | FortyFiveUp   | ↗ |
| > −5.0              | Flat          | → |
| > −10.0             | FortyFiveDown | ↘ |
| > −17.5             | SingleDown    | ↓ |
| ≤ −17.5             | DoubleDown    | ⇊ |

---

## 四、兩套方向來源與優先順序

本系統存在**兩套**趨勢方向來源：

### 4.1 來源 A：通知原始箭頭（Notification-based）

CGM App 的通知文字中可能包含箭頭符號（例如 AiDEX 通知裡的 `↑`、`→`、`↘`）。
`GenericCgmNotificationParser` 會用正規表達式 `reTrend` 擷取這些符號：

```kotlin
val reTrend = Regex(
    "(↑↑|↑|↗|→|↘|↓↓|↓|DoubleUp|SingleUp|FortyFiveUp|Flat|FortyFiveDown|SingleDown|DoubleDown)",
    RegexOption.IGNORE_CASE
)
```

擷取後由 `TrendDirectionMapper` 映射為標準方向名稱：

| 原始符號 / 文字 | 映射結果 |
|:---------------:|:--------:|
| `↑↑` 或 `doubleup` | `DoubleUp` |
| `↓↓` 或 `doubledown` | `DoubleDown` |
| `↑` 或 `singleup` | `SingleUp` |
| `↗` 或 `fortyfiveup` | `FortyFiveUp` |
| `→` 或 `flat` | `Flat` |
| `↘` 或 `fortyfivedown` | `FortyFiveDown` |
| `↓` 或 `singledown` | `SingleDown` |
| 其他 / 空白 | `NONE` |

### 4.2 來源 B：Slope 計算（Calculation-based）

即本文件第二、三節描述的 `SlopeDirectionCalculator.calculate()` 結果。

### 4.3 優先順序

在 `CgmNotificationListenerService.onNotificationPosted()` 中，當新的血糖讀數被成功寫入 DB 後：

```kotlin
val latest2 = repo.latestReadings(2)
val slopeResult = SlopeDirectionCalculator.calculate(latest2)
val effectiveDir = if (slopeResult.isValid && slopeResult.directionName != "NONE") {
    slopeResult.directionName    // ← Slope 計算優先
} else {
    sample.direction              // ← 退回通知原始箭頭
}
```

**規則：Slope 計算結果優先；僅在 slope 無效或方向為 NONE 時，才使用通知中的原始箭頭。**

此設計鏡像 xDrip+ `UiBasedCollector` 的行為：先插入讀數，再呼叫 `find_slope()` 覆寫方向。

---

## 五、UI 顯示

### 5.1 主畫面 (MainActivity)

主畫面頂部以大字體顯示：

```
[分鐘前]      [血糖值] [箭頭]
[Delta]
```

箭頭的渲染流程：

1. 取得當前時間窗口內的所有讀數（`latestReadingsCache`）
2. 呼叫 `SlopeDirectionCalculator.calculate(list)` 取得 `SlopeResult`
3. 呼叫 `SlopeDirectionCalculator.directionToArrow(slope.directionName)` 轉為 Unicode 箭頭
4. 設定到 `directionArrowText` (36sp, 與血糖值同色)

```kotlin
// MainActivity.kt - renderBgInfo()
val slope = SlopeDirectionCalculator.calculate(list)
val arrow = SlopeDirectionCalculator.directionToArrow(slope.directionName)
binding.directionArrowText.text = arrow
binding.directionArrowText.setTextColor(bgColor)
```

### 5.2 箭頭顏色

箭頭顏色與血糖值顏色一致，基於當前血糖值範圍：

| 血糖值 (mg/dL) | 顏色 | 色碼 |
|:--------------:|:----:|:----:|
| < 70           | 紅色（低血糖） | `#FF5252` |
| 70 – 170       | 綠色（正常範圍）| `#4CAF50` |
| > 170          | 黃色（高血糖） | `#FFC107` |

### 5.3 Delta 顯示

| 情境 | 顯示內容 |
|------|----------|
| slope 無效 或 delta 為 NaN（間隔 > 20 分鐘） | `Delta: ???` |
| 單位為 mg/dL | `Delta: +3.5 mg/dL`（正負號+一位小數） |
| 單位為 mmol/L | `Delta: +0.19 mmol/L`（正負號+兩位小數，delta ÷ 18） |

### 5.4 xDrip+ 廣播

箭頭方向以字串形式（如 `"SingleUp"`、`"Flat"`）放入 NS_EMULATOR 廣播的 JSON payload：

```json
[{
  "type": "sgv",
  "date": 1712900000000,
  "sgv": 120.0,
  "direction": "Flat"
}]
```

xDrip+ 接收後會呼叫 `BgReading.slopefromName(direction)` 反推 slope 值。

---

## 六、計算範例

### 範例 1：平穩

| 項目 | 值 |
|------|-----|
| 前一筆 | 118 mg/dL @ 10:00 |
| 當前 | 120 mg/dL @ 10:05 |
| slope | (120 − 118) / 5 = **0.4 mg/dL/min** |
| delta (5min) | 0.4 × 5 = **+2.0 mg/dL** |
| 方向 | 0.4 > −1.0 → **Flat** → `→` |

### 範例 2：快速上升

| 項目 | 值 |
|------|-----|
| 前一筆 | 100 mg/dL @ 10:00 |
| 當前 | 115 mg/dL @ 10:05 |
| slope | (115 − 100) / 5 = **3.0 mg/dL/min** |
| delta (5min) | 3.0 × 5 = **+15.0 mg/dL** |
| 方向 | 3.0 > 2.0 → **SingleUp** → `↑` |

### 範例 3：急速下降

| 項目 | 值 |
|------|-----|
| 前一筆 | 150 mg/dL @ 10:00 |
| 當前 | 120 mg/dL @ 10:05 |
| slope | (120 − 150) / 5 = **−6.0 mg/dL/min** |
| delta (5min) | −6.0 × 5 = **−30.0 mg/dL** |
| 方向 | −6.0 ≤ −3.5 → **DoubleDown** → `⇊` |

### 範例 4：間隔過長

| 項目 | 值 |
|------|-----|
| 前一筆 | 110 mg/dL @ 09:30 |
| 當前 | 115 mg/dL @ 10:05 |
| 時間差 | 35 分鐘（> 20 分鐘上限） |
| slope | (115 − 110) / 35 = **0.14 mg/dL/min** |
| 方向 | 0.14 > −1.0 → **Flat** → `→` |
| delta | **NaN**（因超過 20 分鐘間隔），UI 顯示 `Delta: ???` |

---

## 七、與 xDrip+ 原始碼對照

| 本專案 | xDrip+ 2025.09.05 原始碼 |
|--------|--------------------------|
| `SlopeDirectionCalculator.calculate()` | `BgReading.find_slope()` |
| `SlopeDirectionCalculator.slopeToDirection()` | `Dex_Constants.TREND_ARROW_VALUES.getTrend()` |
| `SlopeDirectionCalculator.directionToArrow()` | 自行實作 Unicode 對應 |
| `TrendDirectionMapper.map()` | 內建於 `UiBasedCollector` 的箭頭解析 |
| `MAX_DELTA_GAP_MS` (20 min) | `BestGlucose` line 315 的 20 分鐘間隔限制 |
| `deltaPerFiveMin = slope × 5` | `BestGlucose.unitizedDeltaString()` line 318 |
| NS_EMULATOR broadcast `direction` 欄位 | `NSEmulatorReceiver` → `BgReading.slopefromName()` |

---



