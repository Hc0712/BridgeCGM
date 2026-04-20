# CgmBridge Notification Logic Analysis — xDrip+ Concurrency Impact

> **Date:** April 12, 2026  
> **Scope:** Will running this Bridge app **with or without xDrip+** installed/running change the Bridge app's behavior?

---

## 1. Complete Notification Data Flow

```
CGM App (AiDEX / Dexcom / OTTAi)
        │
        ▼  Android posts StatusBarNotification
┌─────────────────────────────────────────┐
│  CgmNotificationListenerService         │
│  (NotificationListenerService)          │
│                                         │
│  1. NotificationDebugDumper.dumpPosted()│
│  2. Ignore own package (self)           │
│  3. SupportedPackages.isSupported()     │
│  4. Check isOngoing / shouldProcessAll  │
│  5. Extract contentView texts           │
│  6. Extract extras (title/text/sub/big) │
│  7. GenericCgmNotificationParser.parse()│
│  8. BgReadingImporter.import()          │
│  9. SlopeDirectionCalculator.calculate()│
│  10. XDripBroadcastSender.sendGlucose() │ ──► xDrip+ (if installed)
└─────────────────────────────────────────┘
```

---

## 2. Key Finding: xDrip+ Is NOT a Supported Package

The `SupportedPackages.kt` filter list is:

| Vendor | Packages |
|--------|----------|
| **Dexcom** | `com.dexcom.g6`, `com.dexcom.g6.region1.mmol` … `com.dexcom.g7`, `com.dexcom.dexcomone`, `com.dexcom.stelo`, `com.dexcom.d1plus` |
| **AiDEX** | `com.microtech.aidexx`, `com.microtech.aidexx.mgdl`, `com.microtech.aidexx.equil.mmoll` |
| **OTTAi** | `com.ottai.seas`, `com.ottai.tag` |

**`com.eveningoutpost.dexdrip` (xDrip+) is NOT in the list.**

This means:
- ✅ **Notifications from xDrip+ are silently ignored** at line 98–100 of `CgmNotificationListenerService.kt` (`if (!SupportedPackages.isSupported(sbn.packageName)) return`).
- ✅ The Bridge app will **never** parse or store xDrip+ notifications.
- ✅ No risk of double-counting glucose data from xDrip+'s notifications.

---

## 3. Scenario Analysis: With vs. Without xDrip+ Running

### Scenario A: Bridge App Running ALONE (Without xDrip+)

```
CGM App → Notification → Bridge reads → Stores in Room DB → UI displays chart
                                       └→ Sends broadcast to xDrip+ (NS_EMULATOR)
                                          └→ xDrip+ NOT installed → broadcast is silently dropped by Android
```

**Behavior:**
- Bridge captures CGM notifications normally.
- `XDripBroadcastSender.sendGlucose()` still fires the broadcast `com.eveningoutpost.dexdrip.NS_EMULATOR` with `setPackage("com.eveningoutpost.dexdrip")`.
- Since xDrip+ is not installed, Android **silently drops** the explicit broadcast. No error, no crash.
- **No behavior change** for data capture and storage.

### Scenario B: Bridge App Running WITH xDrip+ Installed

```
CGM App → Notification → Bridge reads → Stores in Room DB → UI displays chart
    │                                  └→ Sends broadcast to xDrip+ ──► xDrip+ receives glucose data
    │
    └────→ Notification → xDrip+ also reads (if UiBasedCollector enabled)
                          xDrip+ sends its OWN notification (ongoing, shows BG value)
                          └→ Bridge sees xDrip+ notification
                             └→ SupportedPackages.isSupported("com.eveningoutpost.dexdrip") → FALSE
                                └→ Silently ignored ✅
```

**Behavior:**
- Bridge captures the **same** CGM notifications and processes them identically.
- xDrip+ receives the Bridge's broadcast AND may also independently read the same CGM notifications.
- xDrip+'s own notifications are **ignored** by the Bridge (not in supported packages).
- **No behavior change** for Bridge's data capture.

### Scenario C: Potential Duplicate in xDrip+

If xDrip+ has its own UiBasedCollector **enabled** AND receives the Bridge's NS_EMULATOR broadcast:
- xDrip+ might receive **two** copies of the same glucose reading (once from its own collector, once from Bridge's broadcast).
- **This is an xDrip+ side concern**, not a Bridge concern. xDrip+ has its own dedup logic.
- **Bridge behavior is unaffected.**

---

## 4. Self-Notification Protection

```kotlin
// Line 92-95 of CgmNotificationListenerService.kt
if (sbn.packageName == applicationContext.packageName) {
    DebugTrace.t("NL-IGNORE-SELF", "Ignored own notification id=${sbn.id}")
    return
}
```

The Bridge's own foreground notification (`CgmGuardianForegroundService`) is correctly filtered out. Without this, the Bridge would see its own ongoing notification and attempt to parse it.

---

## 5. Broadcast Direction (One-Way Only)

```
Bridge ────broadcast────► xDrip+    (one-way, explicit package)
Bridge ◄───────────────── xDrip+    (NO reverse path)
```

- The Bridge **sends** to xDrip+ via `NS_EMULATOR` broadcast.
- xDrip+ does **NOT** send anything back to the Bridge.
- There is **no** BroadcastReceiver in the Bridge's `AndroidManifest.xml` that listens for xDrip+ broadcasts.
- The relationship is strictly **one-directional**.

---

## 6. Edge Cases Verified

| Edge Case | Safe? | Explanation |
|-----------|-------|-------------|
| xDrip+ not installed | ✅ | Explicit broadcast is silently dropped by Android |
| xDrip+ installed but not running | ✅ | NS_EMULATOR receiver in xDrip+ will be woken if registered |
| xDrip+ installed + UiBasedCollector ON | ✅ | Bridge ignores xDrip+ package; xDrip+ handles its own dedup |
| xDrip+ uninstalled while Bridge runs | ✅ | Next broadcast silently fails; no crash |
| CGM app reinstalled / updated | ✅ | Package name doesn't change; Bridge still recognizes it |
| Multiple CGM apps installed | ✅ | Each CGM's notification is processed independently by package |
| Bridge's own foreground notification | ✅ | Filtered by `sbn.packageName == applicationContext.packageName` check |

---

## 7. Conclusion

**Running this Bridge app with or without xDrip+ does NOT change the Bridge app's behavior.** Specifically:

1. **Data source is identical**: The Bridge only reads notifications from the hardcoded list of CGM packages (Dexcom, AiDEX, OTTAi). xDrip+ (`com.eveningoutpost.dexdrip`) is not in this list.

2. **No feedback loop**: The Bridge sends a one-way broadcast to xDrip+. There is no reverse channel. xDrip+ cannot influence what the Bridge captures or stores.

3. **No self-interference**: The Bridge correctly ignores its own notifications.

4. **Broadcast is fire-and-forget**: If xDrip+ is not installed, the broadcast vanishes silently. If xDrip+ is installed, it receives the data. Either way, Bridge's local DB and UI are unaffected.

5. **Only potential concern is on the xDrip+ side**: If xDrip+ has its own UiBasedCollector enabled AND also receives Bridge's NS_EMULATOR broadcast, xDrip+ might see duplicate readings — but that's xDrip+'s problem to solve with its own dedup logic, not Bridge's.

