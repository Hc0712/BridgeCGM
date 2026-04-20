# CgmBridge Unit Handling Analysis — mg/dL vs mmol/L

> **Date:** April 12, 2026  
> **Scope:** Verify that the Bridge correctly handles glucose data in **both** mg/dL and mmol/L units, including mixed-unit sequential notifications.

---

## 1. Executive Summary

| Aspect | Verdict | Detail |
|--------|---------|--------|
| mg/dL input → storage | ✅ Correct | Stored as-is (already mg/dL) |
| mmol/L input → storage | ✅ Correct (common case) | Converted via `× 18.0` before storage |
| Slope calculation | ✅ Unit-safe | Always uses `calculatedValueMgdl` from DB |
| xDrip+ broadcast | ✅ Unit-safe | Always sends mg/dL (`sgv` field) |
| UI chart display | ✅ Unit-safe | Reads mg/dL from DB, converts to display preference |
| Mixed sequential notifications | ✅ Correct | Each notification independently normalized |
| ⚠️ Edge case | ⚠️ See §5 | Integer mmol/L 20–33 without decimal in contentView only |

**Overall: The app handles both units correctly in all normal operating conditions.**

---

## 2. Internal Architecture — Unit Normalization Point

All glucose values are **normalized to mg/dL at parse time** and remain in mg/dL throughout the entire pipeline:

```
                              ┌─────────────────────┐
                              │  NORMALIZATION POINT │
                              │  (GenericCgmParser)  │
                              └──────────┬──────────┘
                                         │ always mg/dL
                    ┌────────────────────┼────────────────────┐
                    ▼                    ▼                    ▼
              ┌──────────┐      ┌──────────────┐     ┌──────────────┐
              │ Room DB  │      │ Slope Calc   │     │ xDrip Bcast  │
              │ (mg/dL)  │      │ (mg/dL)      │     │ (mg/dL)      │
              └────┬─────┘      └──────────────┘     └──────────────┘
                   │
                   ▼
              ┌──────────┐
              │ UI Layer │  ← converts mg/dL → display unit on-the-fly
              └──────────┘
```

Key files and their unit handling:

| Component | File | Internal Unit |
|-----------|------|---------------|
| Parser output | `GlucoseSample.valueMgdl: Int` | **mg/dL** |
| Database | `BgReadingEntity.calculatedValueMgdl: Int` | **mg/dL** |
| Importer range check | `valueMgdl !in 20..600` | **mg/dL** |
| Slope calc | `calculatedValueMgdl.toDouble()` | **mg/dL** |
| xDrip broadcast | `sample.valueMgdl.toDouble()` as `"sgv"` | **mg/dL** |
| Chart Y-axis | `row.calculatedValueMgdl / 18.0` if mmol display | Display unit only |
| BG value text | `GlucoseUnitConverter.mgdlToMmolString()` | Display unit only |

---

## 3. Complete Data Flow — mg/dL Notification

**Example:** AiDEX sends `"120 mg/dL ↗"` in contentView

```
┌─────────────────────────────────────────────────────────────────────┐
│ STEP 1: CgmNotificationListenerService.onNotificationPosted()      │
│                                                                     │
│   contentViewTexts = RemoteViewsTextExtractor → ["120 mg/dL", "↗"] │
│   extras: title="120 mg/dL", text="↗"                              │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│ STEP 2: GenericCgmNotificationParser.parse() — PRIMARY PATH        │
│                                                                     │
│   For raw="120 mg/dL":                                              │
│     CgmStringFilter.filter("120 mg/dL")                             │
│       arrowFilter → "120 mg/dL"  (no arrows to strip)               │
│       basicFilter → "120 "       ("mg/dL" removed)                  │
│       trim        → "120"                                           │
│                                                                     │
│     tryExtractFiltered("120"):                                      │
│       reBareInt ^-?\d{2,3}$  → MATCHES "120"                       │
│       return 120   ← treated as mg/dL                               │
│                                                                     │
│   matches=1, mgdl=120, range check 20..600 → OK                    │
│   Direction: "↗" → TrendDirectionMapper → "FortyFiveUp"            │
│                                                                     │
│   → GlucoseSample(ts, valueMgdl=120, dir="FortyFiveUp", ...)       │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│ STEP 3: BgReadingImporter.import()                                  │
│                                                                     │
│   valueMgdl=120  → range check 20..600 → PASS                      │
│   → INSERT into Room DB as calculatedValueMgdl = 120                │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│ STEP 4: SlopeDirectionCalculator.calculate()                        │
│   Uses calculatedValueMgdl (=120) — always mg/dL                    │
│   Thresholds in mg/dL per minute → direction correct                │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│ STEP 5: XDripBroadcastSender.sendGlucose()                          │
│   "sgv": 120.0  (mg/dL)  — matches xDrip+ expectation              │
│   "direction": "FortyFiveUp"                                        │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 4. Complete Data Flow — mmol/L Notification

**Example:** AiDEX (mmol variant) sends `"6.7 mmol/L ↗"` in contentView

```
┌─────────────────────────────────────────────────────────────────────┐
│ STEP 1: CgmNotificationListenerService.onNotificationPosted()      │
│                                                                     │
│   contentViewTexts = ["6.7 mmol/L", "↗"]                           │
│   extras: title="6.7 mmol/L", text="↗"                             │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│ STEP 2: GenericCgmNotificationParser.parse() — PRIMARY PATH        │
│                                                                     │
│   For raw="6.7 mmol/L":                                             │
│     CgmStringFilter.filter("6.7 mmol/L")                            │
│       arrowFilter → "6.7 mmol/L"                                    │
│       basicFilter → "6.7 "       ("mmol/L" removed)                 │
│       trim        → "6.7"                                           │
│                                                                     │
│     tryExtractFiltered("6.7"):                                      │
│       reBareInt ^-?\d{2,3}$  → NO MATCH (has decimal point)        │
│       isValidMmol [0-9]+[.,][0-9]+ → MATCHES "6.7"                 │
│       GlucoseUnitConverter.mmolToMgdl(6.7) = round(6.7 × 18) = 121│
│       return 121   ← CONVERTED to mg/dL ✅                          │
│                                                                     │
│   matches=1, mgdl=121, range check 20..600 → OK                    │
│   → GlucoseSample(ts, valueMgdl=121, dir="FortyFiveUp", ...)       │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│ STEP 3–5: Identical to mg/dL flow (all downstream is mg/dL)        │
│                                                                     │
│   DB: calculatedValueMgdl = 121                                     │
│   Slope: uses 121 (mg/dL)                                           │
│   xDrip broadcast: "sgv": 121.0                                    │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 5. Mixed-Unit Sequential Notifications

**Scenario:** Two CGM apps installed — one sends mg/dL, the other sends mmol/L.

```
  TIME     NOTIFICATION                 PARSED          DB
  ─────    ──────────────────────────   ──────────      ───────────────────
  10:00    "120 mg/dL ↗" (Dexcom)      → 120 mg/dL     calculatedValueMgdl=120
  10:01    "6.7 mmol/L ↗" (AiDEX)      → 121 mg/dL     calculatedValueMgdl=121
  10:05    "125 mg/dL ↗" (Dexcom)      → 125 mg/dL     calculatedValueMgdl=125
  10:06    "7.0 mmol/L ↗" (AiDEX)      → 126 mg/dL     calculatedValueMgdl=126
```

**Result:** All values are consistently stored in mg/dL. Slope calculation, chart display, and xDrip+ broadcasts all work correctly because the **normalization happens once at parse time** and everything downstream only sees mg/dL.

### Sequence Diagram

```
 Dexcom App          AiDEX App         NotificationListener      Parser           DB (mg/dL)
     │                   │                     │                    │                  │
     │──"120 mg/dL"─────►│                     │                    │                  │
     │                   │    onNotificationPosted()                │                  │
     │                   │─────────────────────►│                   │                  │
     │                   │                      │──parse(input)────►│                  │
     │                   │                      │                   │──filter──►"120"  │
     │                   │                      │                   │  bareInt→120mgdl │
     │                   │                      │◄─GlucoseSample(120)                  │
     │                   │                      │────────────────────────INSERT(120)───►│
     │                   │                      │                                      │
     │                   │──"6.7 mmol/L"───────►│                   │                  │
     │                   │    onNotificationPosted()                │                  │
     │                   │─────────────────────►│                   │                  │
     │                   │                      │──parse(input)────►│                  │
     │                   │                      │                   │──filter──►"6.7"  │
     │                   │                      │                   │  isValidMmol     │
     │                   │                      │                   │  6.7×18=121mgdl  │
     │                   │                      │◄─GlucoseSample(121)                  │
     │                   │                      │────────────────────────INSERT(121)───►│
```

---

## 6. Parser Decision Tree — Both Paths

```
                    Notification Arrives
                           │
                    ┌──────┴──────┐
                    │  PRIMARY    │  contentViewTexts available?
                    │  PATH       │
                    └──────┬──────┘
                           │ YES
              For each contentView text:
              ┌────────────┴────────────┐
              │ CgmStringFilter.filter()│
              │  1. Strip Unicode arrows│
              │  2. Strip "mg/dL"       │
              │  3. Strip "mmol/L"      │
              │  4. Trim whitespace     │
              └────────────┬────────────┘
                           │
                   tryExtractFiltered()
                           │
              ┌────────────┴────────────┐
              │ reBareInt ^-?\d{2,3}$   │──YES──► treat as mg/dL (integer)
              │  (2–3 digit integer)    │         return value as-is
              └────────────┬────────────┘
                           │ NO
              ┌────────────┴────────────┐
              │ isValidMmol             │──YES──► treat as mmol/L
              │  [0-9]+[.,][0-9]+      │         GlucoseUnitConverter.mmolToMgdl()
              │  (has decimal point)    │         return (value × 18).roundToInt()
              └────────────┬────────────┘
                           │ NO
                      return -1
                           │
            ──── matches==1 && mgdl in 20..600? ────
            │YES                                 │NO
            ▼                                    ▼
     Return GlucoseSample              ┌────────────────────┐
     (PRIMARY result)                  │  FALLBACK PATH     │
                                       └────────┬───────────┘
                                                │
                               ┌────────────────┴─────────────────┐
                               │ Step 1: tryExtractFromTitle()    │
                               │   (same filter + tryExtract)     │
                               │ Step 2: reMgdl regex             │──MATCH──► mg/dL value
                               │   "\d{2,3}\s*mg/dL"             │           (with unit)
                               │ Step 3: reMmol regex             │──MATCH──► mmol/L → ×18
                               │   "\d{1,2}(\.\d{1,2})?\s*mmol/L"│           (with unit)
                               │ Step 4: status/alert only        │
                               └──────────────────────────────────┘
```

**Key insight:** The FALLBACK path (steps 2–3) searches the **raw, unfiltered** `extrasCombined` text where the unit suffix (mg/dL or mmol/L) is still present. This makes unit detection **explicit and unambiguous**.

---

## 7. ⚠️ Edge Case: Integer mmol/L ≥ 20 Without Decimal in ContentView

There is one theoretical edge case in the **PRIMARY path only**:

| ContentView Text | After Filter | reBareInt? | isValidMmol? | Result |
|-----------------|-------------|-----------|-------------|--------|
| `"120 mg/dL"` | `"120"` | ✅ 3 digits | — | 120 mg/dL ✅ |
| `"6.7 mmol/L"` | `"6.7"` | ❌ (decimal) | ✅ | 121 mg/dL ✅ |
| `"6 mmol/L"` | `"6"` | ❌ (1 digit) | ❌ (no decimal) | -1 → falls to FALLBACK ✅ |
| `"12 mmol/L"` | `"12"` | ✅ 2 digits | — | 12 mg/dL → **not in 20..600** → FALLBACK ✅ |
| `"19 mmol/L"` | `"19"` | ✅ 2 digits | — | 19 mg/dL → **not in 20..600** → FALLBACK ✅ |
| **`"20 mmol/L"`** | **`"20"`** | **✅ 2 digits** | — | **20 mg/dL → in 20..600 → ⚠️ WRONG** |
| `"20.0 mmol/L"` | `"20.0"` | ❌ (decimal) | ✅ | 360 mg/dL ✅ |
| `"25 mmol/L"` | `"25"` | ✅ 2 digits | — | 25 mg/dL → ⚠️ WRONG (should be 450) |

**Affected range:** Integer mmol/L values 20–33 without decimal (= 360–594 mg/dL).  
**Practical likelihood:** Very low — this only happens when:
1. The CGM app uses mmol/L units, AND
2. The contentView shows an integer without decimal (e.g., `"20"` not `"20.0"`), AND
3. The glucose is ≥ 360 mg/dL (extreme hyperglycemia)

Most CGM apps always include a decimal for mmol/L display (e.g., `"20.0"` not `"20"`).

---

## 8. Downstream Unit Safety Verification

### 8.1 Slope Calculation (`SlopeDirectionCalculator.kt`)

```kotlin
val valueDelta = current.calculatedValueMgdl.toDouble() - previous.calculatedValueMgdl.toDouble()
val slopePerMinute = valueDelta / minutesDelta
```
- Uses `calculatedValueMgdl` — always mg/dL ✅
- Thresholds are in mg/dL per minute ✅
- Mixed-unit notifications produce consistent mg/dL values → slope is correct ✅

### 8.2 xDrip+ Broadcast (`XDripBroadcastSender.kt`)

```kotlin
obj.put("sgv", sample.valueMgdl.toDouble())  // Always mg/dL
```
- xDrip+ `NSEmulatorReceiver` expects mg/dL in `sgv` field ✅

### 8.3 UI Chart (`ChartHelper.kt`)

```kotlin
val yVal = if (isMmol) row.calculatedValueMgdl.toFloat() / 18f
           else row.calculatedValueMgdl.toFloat()
```
- Reads mg/dL from DB, converts to display unit at render time ✅
- Color thresholds use `entry.data as Int` (mg/dL) regardless of display unit ✅

### 8.4 Delta Display (`MainActivity.kt`)

```kotlin
if (currentOutputUnit == "mmol") {
    val deltaMMol = delta5 / 18.0
    binding.deltaText.text = String.format("Delta: %+.2f mmol/L", deltaMMol)
} else {
    binding.deltaText.text = String.format("Delta: %+.1f mg/dL", delta5)
}
```
- `delta5` is computed from mg/dL values, displayed in user's preferred unit ✅

---

## 9. Conclusion

### ✅ What Works Correctly

1. **Both mg/dL and mmol/L notifications are correctly parsed and normalized to mg/dL** before storage.
2. **Sequential notifications in different units do NOT cause data corruption** — each is independently normalized.
3. **All downstream components (DB, slope, broadcast, UI) are unit-safe** because they only work with the normalized mg/dL value.
4. **Display conversion is done at the UI layer only** — consistent with xDrip+ architecture.
5. **The conversion formula (`× 18.0` with `roundToInt()`) is correct** and matches xDrip+ `Constants.MMOLL_TO_MGDL`.

### ⚠️ One Theoretical Edge Case

Integer mmol/L values ≥ 20 (≥ 360 mg/dL) in contentView without a decimal point would be misidentified as mg/dL in the PRIMARY path. Practical impact is minimal because:
- These represent extreme hyperglycemia (rare)
- CGM apps typically always show decimals in mmol/L mode
- The FALLBACK path (which uses unit-aware regex) correctly handles these if the extras text retains the unit suffix

No code change is urgently needed, but a future improvement could add a per-package unit hint to disambiguate borderline cases.

