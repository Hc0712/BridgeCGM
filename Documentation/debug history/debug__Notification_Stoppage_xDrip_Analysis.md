# Notification Stoppage — xDrip+ Source Code Analysis & Fix Plan

> **Date**: 2026-04-12
> **Based on**: xDrip+ 2025.09.05 source code @ `D:\Workspace\AndroidStudioProjects\xDrip-2025.09.05`

---

## 1. Problem Statement

The CGM Bridge app frequently stops receiving notifications from the CGM app (AiDEX).
The user sees "19 min ago" on screen despite the CGM transmitter sending every 1 minute.

Catlog analysis confirmed:
- Notifications were flowing normally (13:46 – 13:51) then stopped after log capture ended
- No `NL-DISCONNECTED` trace was captured in the log
- `HealthCheckWorker` runs every 15 minutes but only restarts the guardian foreground service — it does NOT verify or recover the `NotificationListenerService`

---

## 2. How xDrip+ Solves This Problem

### 2.1 Key Finding: xDrip+ UiBasedCollector Has NO `onListenerDisconnected`

```java
// UiBasedCollector.java — extends NotificationListenerService
// Does NOT override:
//   - onListenerConnected()
//   - onListenerDisconnected()
//   - onCreate()
//   - onDestroy()
```

xDrip+ does **not** attempt to self-heal the `NotificationListenerService` binding from within the service itself. Instead, it relies on **external defense mechanisms** to keep the entire process alive so the OS never gets a chance to kill the listener.

### 2.2 xDrip+'s Multi-Layer Keep-Alive Strategy

| Layer | Mechanism | xDrip+ Code Location | Bridge Current State |
|-------|-----------|----------------------|---------------------|
| **1. Battery Optimization Bypass** | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog on startup | `Home.checkBatteryOptimization()` (line 639) | ✅ Exists in SetupActivity but user can skip |
| **2. Foreground Service** | Ongoing notification with `startForeground()` + `START_STICKY` | `ForegroundServiceStarter.start()` + `DoNothingService` | ✅ `CgmGuardianForegroundService` exists |
| **3. AlarmManager Exact Heartbeat** | `setExactAndAllowWhileIdle()` every ≤6 min; Samsung: `setAlarmClock()` | `Notifications.scheduleWakeup()` + `DoNothingService.setFailOverTimer()` | ❌ **NOT IMPLEMENTED** |
| **4. WakeLock During Processing** | `PARTIAL_WAKE_LOCK` for 60s during notification/service work | `JoH.getWakeLock()` in `DoNothingService.onStartCommand()`, `Notifications.onHandleIntent()`, `MissedReadingService.onHandleIntent()` | ❌ **NOT IMPLEMENTED** |
| **5. WakeLockTrampoline** | Broadcast receiver trampoline to prevent framework wakelock release | `WakeLockTrampoline.java` | ❌ **NOT IMPLEMENTED** |
| **6. MissedReadingService (Watchdog)** | Detects stale data, aggressively restarts collector service | `MissedReadingService.java` + `Notifications.java` | ❌ **NOT IMPLEMENTED** |
| **7. `requestRebind()` on disconnect** | N/A | N/A — xDrip+ doesn't do this | ✅ Bridge already has this (extra safety) |

### 2.3 Critical Code Paths in xDrip+

#### Battery Optimization (Layer 1)
```java
// Home.java line 639-677
private boolean checkBatteryOptimization() {
    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
    if (!pm.isIgnoringBatteryOptimizations(packageName)) {
        // Show dialog → ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + packageName));
        startActivityForResult(intent, REQ_CODE_BATTERY_OPTIMIZATION);
    }
}
```

#### AlarmManager Heartbeat (Layer 3)
```java
// Notifications.java line 497-524
private synchronized void scheduleWakeup(Context context, boolean unclearAlert) {
    long wakeTime = ...;  // ≤6 minutes from now
    JoH.wakeUpIntent(context, wakeTime - now, wakeIntent);
}

// JoH.java line 1458-1482
public static long wakeUpIntent(Context context, long delayMs, PendingIntent pendingIntent) {
    if (buggy_samsung && Pref.getBoolean("allow_samsung_workaround", true)) {
        alarm.setAlarmClock(new AlarmClockInfo(wakeTime, null), pendingIntent);
    } else {
        alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeTime, pendingIntent);
    }
}
```

#### WakeLock During Processing (Layer 4)
```java
// DoNothingService.java line 96
val wl = JoH.getWakeLock("donothing-follower", 60000);
// ... processing ...
JoH.releaseWakeLock(wl);
```

#### WakeLockTrampoline Pattern (Layer 5)
```java
// WakeLockTrampoline.java — BroadcastReceiver
@Override
public void onReceive(Context context, Intent broadcastIntent) {
    JoH.getWakeLock(TAG, 1000); // deliberately not released (1s)
    // Extract target service class from intent extra
    // Start service (foreground if needed)
}
```

---

## 3. Root Cause Analysis

The Bridge app lacks **Layers 3, 4, 5, and 6** from xDrip+'s defense stack:

1. **No AlarmManager heartbeat**: When Doze mode activates, the CPU sleeps. Without `setExactAndAllowWhileIdle()`, no code runs to keep the process warm. WorkManager's 15-minute interval is too infrequent and can be deferred by the OS up to hours.

2. **No WakeLock during processing**: Even when a notification arrives, the CPU can sleep mid-processing (between notification receipt and coroutine DB write completion).

3. **No liveness monitoring**: Nobody checks whether the NotificationListenerService is still receiving. The `HealthCheckWorker` only restarts the guardian service.

4. **Battery optimization may not be whitelisted**: The SetupActivity allows users to skip the battery optimization step. On aggressive OEMs (Samsung, Xiaomi, Huawei, Oppo), this is fatal.

---

## 4. Implementation Plan

### 4.1 Files to Modify
| File | Change |
|------|--------|
| `AppPrefs.kt` | Add `lastNotificationTimestampMs` property |
| `NotificationPollScheduler.kt` | Implement AlarmManager scheduling (modeled after xDrip+ `JoH.wakeUpIntent()`) |
| `NotificationPollReceiver.kt` | Implement WakeLockTrampoline pattern (modeled after xDrip+ `WakeLockTrampoline`) |
| `CgmNotificationListenerService.kt` | Add WakeLock + liveness timestamp update |
| `AndroidManifest.xml` | Add `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM`, register `NotificationPollReceiver` |
| `BridgeCGMApplication.kt` | Start alarm scheduler on app launch |
| `BootReceiver.kt` | Start alarm scheduler on boot |
| `HealthCheckWorker.kt` | Add listener liveness check |
| `SetupActivity.kt` | Enforce battery optimization acceptance |

### 4.2 Not Copied from xDrip+
- `BlueTails.immortality()` — Dexcom BLE specific, not applicable
- `DoNothingService` follower timer — Our guardian service already covers this role
- `MissedReadingService` full implementation — We implement a simpler version in `NotificationPollReceiver`

---

## 5. Permission Requirements

| Permission | API Level | Purpose |
|------------|-----------|---------|
| `WAKE_LOCK` | All | WakeLock acquisition during processing |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 23+ | Battery optimization bypass dialog |
| `SCHEDULE_EXACT_ALARM` | 31-32 | Exact alarm scheduling (requires user grant) |
| `USE_EXACT_ALARM` | 33+ | Exact alarm scheduling (auto-granted for health/alarm apps) |
| `RECEIVE_BOOT_COMPLETED` | All | Already present |
| `FOREGROUND_SERVICE` | All | Already present |



## 6. Implementation Summary (Completed)

All changes modeled after xDrip+ source code. Build verified: **BUILD SUCCESSFUL**.

### Files Modified

| File | Change |
|------|--------|
| `AppPrefs.kt` | Added `lastNotificationTimestampMs` and `lastListenerConnectedMs` properties |
| `NotificationPollScheduler.kt` | **New** — AlarmManager exact alarm scheduling (5-min heartbeat), Samsung `setAlarmClock()` workaround |
| `NotificationPollReceiver.kt` | **New** — WakeLock trampoline, liveness check, `requestRebind()` recovery, re-arms alarm |
| `CgmNotificationListenerService.kt` | Added `PARTIAL_WAKE_LOCK` during processing, liveness timestamp update, alarm re-arm on connect |
| `AndroidManifest.xml` | Added `SCHEDULE_EXACT_ALARM` + `USE_EXACT_ALARM` permissions, registered `NotificationPollReceiver` |
| `BridgeCGMApplication.kt` | Starts heartbeat alarm on process launch |
| `BootReceiver.kt` | Starts heartbeat alarm on boot/update |
| `HealthCheckWorker.kt` | Added listener liveness check (>15min stale → rebind), re-arms alarm |
| `SetupActivity.kt` | Blocking dialog if battery optimization not whitelisted, alarm start on setup complete |

### Defense Layers Now Active

```
Layer 1: Battery Optimization Bypass         ✅ (enforced in SetupActivity with blocking dialog)
Layer 2: Foreground Service + START_STICKY   ✅ (existing CgmGuardianForegroundService)
Layer 3: AlarmManager Exact Heartbeat (5min) ✅ (NEW — NotificationPollScheduler + NotificationPollReceiver)
Layer 4: WakeLock During Processing          ✅ (NEW — PARTIAL_WAKE_LOCK in onNotificationPosted)
Layer 5: WakeLock Trampoline Pattern         ✅ (NEW — NotificationPollReceiver acquires WakeLock before work)
Layer 6: Liveness Monitoring + Recovery      ✅ (NEW — timestamps in AppPrefs, stale detection, requestRebind)
Layer 7: requestRebind() on disconnect       ✅ (existing onListenerDisconnected)
Layer 8: WorkManager Safety Net (15min)      ✅ (enhanced HealthCheckWorker with liveness check)
```

### How the Watchdog Works

```
Every 5 minutes (exact alarm, even in Doze):
  NotificationPollReceiver fires
    ├─ Acquire PARTIAL_WAKE_LOCK (30s)
    ├─ Read lastNotificationTimestampMs from SharedPreferences
    ├─ If stale (>7min):
    │   ├─ Log POLL-STALE warning
    │   └─ Call requestRebind() to reconnect listener
    ├─ Ensure guardian foreground service is alive
    ├─ Re-schedule next alarm (self-sustaining chain)
    └─ Release WakeLock

Every CGM notification received:
  CgmNotificationListenerService.onNotificationPosted()
    ├─ Update lastNotificationTimestampMs = now  (proves liveness)
    ├─ Acquire PARTIAL_WAKE_LOCK (10s)
    ├─ Parse → Import → Broadcast (in coroutine)
    └─ Release WakeLock in finally block

Every 15 minutes (WorkManager, secondary safety net):
  HealthCheckWorker
    ├─ Check lastNotificationTimestampMs
    ├─ If stale (>15min): requestRebind()
    └─ Re-arm AlarmManager heartbeat (in case alarm was lost)
```
