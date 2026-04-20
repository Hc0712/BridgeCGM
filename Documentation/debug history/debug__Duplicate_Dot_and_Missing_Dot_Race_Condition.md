# Bug Analysis: Duplicate Dots & Missing Dots — TOCTOU Race + Float Precision

## 1. Symptoms

| Bug | Screenshot | Description |
|-----|-----------|-------------|
| **Duplicate dots** | multi-dot.png, multi-dot2.png | Two dots plotted at the same x-axis position (e.g. 20:56, 21:20) |
| **Missing dots** | missing.png | Only ~4 dots from 20:41 to 20:50 (expect ~9–10 for 1-min AiDEX) |

---

## 2. Root Cause: TOCTOU Race in `BgReadingImporter`

### What is TOCTOU?

**T**ime-**O**f-**C**heck **T**o **T**ime-**O**f-**U**se — a class of concurrency bug where the
state read at "check" time has changed by "use" time because another thread
modified it in between.

### How it happened

`CgmNotificationListenerService.onNotificationPosted()` launches a **new coroutine**
(via `scope.launch`) for every incoming notification. AiDEX fires **two** notifications
per CGM cycle, ~960ms apart. Both coroutines run concurrently on `Dispatchers.IO`
(thread pool):

```
AiDEX cycle at 20:41:
────────────────────────────────────────────────────────────
Coroutine A (postTime = T)        Coroutine B (postTime = T+960ms)
  │                                 │
  ├─ repo.latestReadings(1)         │
  │  → returns reading from 20:40   │
  │                                 ├─ repo.latestReadings(1)
  │                                 │  → ALSO returns 20:40 reading
  │                                 │    (A hasn't committed yet!)
  ├─ gap = T − 20:40 ≈ 60s         │
  │  60s > 50s → PASS               ├─ gap = (T+960ms) − 20:40 ≈ 61s
  │                                 │  61s > 50s → PASS  ← SHOULD BE BLOCKED
  ├─ DB INSERT (ts = T)             │
  │  → OK                           ├─ DB INSERT (ts = T+960ms)
  │                                 │  → OK (different PK!)
────────────────────────────────────────────────────────────
Result: TWO readings 960ms apart stored in DB
```

### How this causes Bug 1 (duplicate dots)

Two readings ~960ms apart are stored. If they straddle a minute boundary
(e.g., `20:55:59.500` and `20:56:00.460`), the chart dedup in `ChartHelper`:

```kotlin
val min = r.timestampMs / MS_PER_MIN   // integer minute division
if (min != lastMinute) { deduped.add(r) }
```

…produces **different** minute values → both are kept → **two dots at same visual x**.

### How this causes Bug 2 (missing dots)

The extra 960ms-apart reading shifts the gap-check baseline for the next cycle:

```
Race inserts both T and T+960ms
Next real reading at T+60000ms:
  latestReadings(1) → T+960ms
  gap = 60000 − 960 = 59040ms → passes (>50s)  ✓

But in some timing, the shifted baseline CAN cause a legitimate reading
to appear "too soon" (< 50s gap from the extra duplicate), especially if
AiDEX cycle jitter makes the next real reading arrive slightly early.
```

Additionally, every extra DB row from the race means the slope calculator
may pick sub-optimal reading pairs, potentially producing noisy deltas.

---

## 3. xDrip+ Comparison

xDrip+ `UiBasedCollector.handleNewValue()` processes everything **synchronously**
on a single locked code path — it never runs two notification handlers concurrently.
This makes the gap check (and DB insert) atomic from a concurrency perspective.

| Aspect | CgmBridge (before fix) | xDrip+ |
|--------|----------------------|--------|
| Processing model | Concurrent coroutines per notification | Single-thread synchronized |
| Gap check → insert atomic? | ❌ No — race window ~960ms | ✅ Yes |
| Duplicate dots possible? | ✅ Yes | ❌ No |

---

## 4. Fix: `Mutex` Serialization

Added a `kotlinx.coroutines.sync.Mutex` named `importMutex` in
`CgmNotificationListenerService`. The entire pipeline inside `scope.launch`
is now wrapped in `importMutex.withLock { … }`:

```kotlin
private val importMutex = Mutex()

scope.launch {
    try {
        importMutex.withLock {
            // parse → gap-check → DB insert → slope → broadcast
            // ALL serialized — second coroutine waits here
        }
    } catch { … }
}
```

### After fix:

```
Coroutine A (postTime T)           Coroutine B (postTime T+960ms)
  │                                  │
  ├─ importMutex.withLock {          ├─ importMutex.withLock {
  │    latestReadings → 20:40        │    ⏳ SUSPENDED (mutex held by A)
  │    gap = 60s → PASS              │    ...
  │    DB INSERT (ts = T) → OK       │    ...
  │  }                               │  }
  │                                  ├─ (mutex released, B resumes)
  │                                  ├─ latestReadings → T  ← FRESH!
  │                                  ├─ gap = 960ms < 50s → IgnoredTooSoon ✓
```

**Result:** Exactly 1 reading per AiDEX cycle. No duplicate dots. No shifted baselines.

### Performance impact

| Metric | Impact |
|--------|--------|
| Lock contention | Only on AiDEX rapid duplicates (~960ms apart); real readings (60s+) never contend |
| Lock hold time | ~5–20ms (parse + DB write + slope + broadcast) |
| Throughput | Unchanged for normal operation |
| Unrelated notifications | Not affected — mutex is only inside `scope.launch`, not in `onNotificationPosted()` |

---

## 5. Files Modified

| File | Change |
|------|--------|
| `CgmNotificationListenerService.kt` | Added `Mutex` import, `importMutex` field, wrapped pipeline in `withLock` |
| `ChartHelper.kt` | Fixed Float precision: relative x-axis values (minutes since midnight); added `CHART-DOT` verbose logging |
| `GlucoseMarkerView.kt` | Added `xReferenceMs` property; reconstruct absolute timestamp from relative x-value |

---

## 6. Verification Checklist

After deploying this fix:

- [ ] Run app with AiDEX for 10+ minutes
- [ ] Count dots on 3h chart — should be 1 per minute (no duplicates, no gaps)
- [ ] Check logcat for `IMPORT-SKIP` messages — rapid duplicates should show `IgnoredTooSoon`
- [ ] Check logcat for `CHART-DOT` messages — each dot should have unique, monotonically increasing x values ~1.0 apart
- [ ] Verify Delta shows reasonable values (not -624 mg/dL)
- [ ] Verify direction arrow is correct (not DoubleDown for stable glucose)
- [ ] Tap a dot on the chart — verify crosshair shows correct HH:mm time and MM/dd date

---

## 7. Root Cause 2: IEEE 754 Float Precision Loss in Chart X-axis

### Discovery

After the Mutex fix (Section 4), multi-dot rendering persisted (see multi-dot2.png at 21:20).
The TOCTOU race was correctly fixed — only 1 reading per cycle entered the DB.
The visual bug had a **separate, independent root cause** in ChartHelper.

### The problem: `Float` cannot represent epoch-minutes with 1-minute resolution

MPAndroidChart's `Entry` class stores x/y as **32-bit float** (IEEE 754 single precision,
23-bit mantissa ≈ 7.2 decimal digits).

The original code computed x-axis values as absolute epoch-minutes:

```kotlin
val xMin = row.timestampMs.toFloat() / MS_PER_MIN  // BUG!
```

For April 2026 timestamps:

| Value | Magnitude | Float ULP |
|-------|-----------|-----------|
| `timestampMs` | ~1.776 × 10¹² ms | 2¹⁸ = **262,144 ms ≈ 4.37 min** |
| `timestampMs / 60000` | ~29,601,440 min | 2¹ = **2 min** |

**At the ms level:** Two readings 60 seconds apart (60,000 ms difference) are
**indistinguishable** — both map to the same `Float` value because the ULP
exceeds the gap.

**At the minutes level (after division):** Even if we divide first as Long
(`timestampMs / MS_PER_MIN`), the result ~29.6M has ULP = 2, meaning odd and
even minute values alias to the nearest representable Float. Half the readings
would collapse onto the same x-position as a neighbor.

### Visual impact

```
Readings at minutes 1280, 1281, 1282, 1283, 1284 (relative to midnight)
With absolute epoch-minutes (~29,601,280 .. 29,601,284):

  Float(29601280) = 29601280   ─┐
  Float(29601281) = 29601282    │ readings 1280 & 1281 → same or off-by-1 float
  Float(29601282) = 29601282   ─┘ DUPLICATE DOT at this x-position
  Float(29601283) = 29601284   ─┐
  Float(29601284) = 29601284   ─┘ DUPLICATE DOT at this x-position

Result: 5 readings → only 2–3 distinct x positions → visual multi-dots + gaps
```

### Fix: relative x-axis values

Subtract `dayStartMs` (midnight of the viewed day) **before** converting to Float:

```kotlin
private fun toXValue(timestampMs: Long, referenceMs: Long): Float =
    ((timestampMs - referenceMs).toDouble() / MS_PER_MIN).toFloat()
```

Now x-values range from **0 to 1440** (minutes in a day):

| Value | Magnitude | Float ULP |
|-------|-----------|-----------|
| `(timestampMs - dayStartMs)` | 0–86,400,000 ms | ≤ 8 ms |
| x-value (minutes) | 0–1440 | ~0.0001 min |

Every 1-minute reading gets a **distinct, correctly spaced** Float x-value.

### Additional changes

1. **`applyXAxis()`** — axis range bounds also use `toXValue()` with the same reference.
2. **`ValueFormatter`** — reconstructs absolute epoch-ms: `(value.toDouble() * MS_PER_MIN + referenceMs).toLong()`.
3. **`GlucoseMarkerView`** — added `var xReferenceMs: Long`, set by ChartHelper before each render.
4. **Debug logging** — `CHART-DOT` verbose logs print each dot's x, y, timestamp, and mg/dL
   (gated by `cgmbridge.verboseDump` / `FeatureFlags.verboseDump`).

### Why the TOCTOU fix alone wasn't enough

The Mutex correctly prevents duplicate DB entries. But even with a single reading
per minute, the **Float x-axis conversion** was destroying the positional information,
making correctly-stored readings appear at wrong or overlapping chart positions.
Both fixes are necessary:

| Layer | Problem | Fix |
|-------|---------|-----|
| Data layer | TOCTOU race → 2 DB rows per AiDEX cycle | Mutex serialization |
| Rendering layer | Float precision → x-values collapse | Relative timestamp offset |

