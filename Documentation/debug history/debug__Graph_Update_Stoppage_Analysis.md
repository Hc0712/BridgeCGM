# Graph Update Stoppage — Root Cause Analysis

## Symptom
The app plots the first received glucose reading, then the chart never updates 
despite continuous notification reception (logcat shows PARSE-OUT and BCAST-SENT 
for every new reading).

## Root Cause: Stale `to` Bound in Room Flow Query

**File:** `MainViewModel.kt` → `computeDayRange()`

### The Buggy Code
```kotlin
val end = if (dayOff == 0) System.currentTimeMillis() else start + 24L * 3600_000L
```

### What Happens
1. App starts → ViewModel creates `switchMap` → `computeDayRange(0)` captures 
   `to = System.currentTimeMillis()` (e.g., 15:22:10 = epoch `1775978530000`)
2. Room Flow query: `SELECT * WHERE timestampMs >= dayStart AND timestampMs < 1775978530000`
3. First reading arrives at 15:22:24 → `ts = 1775978544159` → barely fits `< to` → **chart shows 1 point**
4. Second reading at 15:22:35 → `ts = 1775978555536` → `> to` → **excluded!**
5. Room re-runs query on table change but uses **same stale parameters** → new data never appears

### Evidence (from locat2.txt)
- Main thread (4813-4813): `SLOPE-CALC Only 1 reading → Flat, minutesAgo=0/1/2/3` (3+ minutes stuck)
- Worker threads (4813-4959/4960/5430): Proper multi-reading slope calculations (DB has the data)
- `CHART-RENDER input=1 deduped=1` from 15:22 to 15:25 — never grows

## xDrip+ Comparison
xDrip+ avoids this entirely:
- Does NOT use Room Flow for chart data
- Uses `BroadcastReceiver` → `updateCurrentBgInfo("new data")` → rebuilds graph from scratch
- Each rebuild calls `BgGraphBuilder` with **fresh** `System.currentTimeMillis()`
- Also listens to system `ACTION_TIME_TICK` (every minute) for "minutes ago" refresh

## Fix Applied
**Changed `computeDayRange()`** to always use end-of-day (midnight tomorrow) as the upper bound:

```kotlin
val end = start + 24L * 3600_000L   // covers the full day including future readings
```

**Changed `computeRange()`** for today + sub-24h window: left edge uses `now - h hours`, 
right edge stays at end-of-day so Room Flow always includes newly arriving readings.

This works because:
- All readings arriving today have `timestampMs < midnight_tomorrow` ✓
- Room Flow re-emits when the table changes, and new readings now match the range ✓
- Chart X-axis rendering already uses fresh `System.currentTimeMillis()` at render time ✓

## Files Modified
- `app/src/main/java/tw/yourcompany/cgmbridge/ui/MainViewModel.kt`
  - `computeDayRange()` — fixed upper bound
  - `computeRange()` — fixed today + sub-24h window logic

