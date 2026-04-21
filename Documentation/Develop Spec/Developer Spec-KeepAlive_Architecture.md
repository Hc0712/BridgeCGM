# Multi-Layer Keep-Alive Architecture

## Overview
This document describes the 8-layer defense strategy that prevents the Android OS
from killing or silencing the `CgmNotificationListenerService`. The design is
modeled after xDrip+ 2025.09.05 source code.

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        ANDROID OS / OEM LAYER                          │
│  (Doze, App Standby, OEM battery killers, process death)               │
└────────┬──────────────────────────────┬─────────────────────┬──────────┘
         │ kills/unbinds                │ fires alarm          │ fires WorkManager
         ▼                              ▼                      ▼
┌─────────────────┐  ┌──────────────────────────┐  ┌────────────────────┐
│  LAYER 1        │  │  LAYER 3                 │  │  LAYER 5           │
│  Notification   │  │  AlarmManager Heartbeat  │  │  HealthCheckWorker │
│  Listener       │  │  (NotificationPoll       │  │  (WorkManager,     │
│  Service        │  │   Scheduler)             │  │   every 15 min)    │
│                 │  │  Exact alarm every 5 min │  │                    │
│ onNotification  │  │  Samsung: setAlarmClock() │  │  Liveness check    │
│ Posted()        │  │  Others: setExactAnd     │  │  >15min stale →    │
│                 │  │   AllowWhileIdle()       │  │  requestRebind()   │
│ ┌─────────────┐ │  │                          │  │                    │
│ │ LAYER 2     │ │  └───────────┬──────────────┘  └─────────┬──────────┘
│ │ WakeLock    │ │              │ fires                      │
│ │ during      │ │              ▼                            │
│ │ processing  │ │  ┌──────────────────────────┐             │
│ │ (10s max)   │ │  │  LAYER 4                 │             │
│ └─────────────┘ │  │  NotificationPollReceiver│             │
│                 │  │  (WakeLock Trampoline)    │             │
│ ┌─────────────┐ │  │                          │             │
│ │ Liveness    │ │  │  1. Acquire WakeLock     │             │
│ │ timestamp   │◄├──│  2. Check staleness      │             │
│ │ update      │ │  │  3. requestRebind() if   │             │
│ │ (AppPrefs)  │ │  │     stale (>7 min)       │             │
│ └─────────────┘ │  │  4. Restart guardian svc  │             │
│                 │  │  5. Re-arm next alarm     │             │
│ ┌─────────────┐ │  │  6. Release WakeLock     │             │
│ │ onListener  │ │  └──────────────────────────┘             │
│ │ Disconnected│ │                                           │
│ │ → rebind()  │ │   ┌─────────────────────────────┐         │
│ │ (LAYER 6)   │ │   │  Shared Liveness Store      │         │
│ └─────────────┘ │   │  (AppPrefs / SharedPrefs)    │◄────────┘
│                 │   │                              │  reads
│ ┌─────────────┐ │   │  lastNotificationTimestampMs │
│ │ onListener  │ │   │  lastListenerConnectedMs     │
│ │ Connected   │ │   └─────────────────────────────┘
│ │ → re-arm    │ │
│ │   alarm     │ │
│ └─────────────┘ │
└─────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│  LAYER 7: Battery Optimization Bypass                                  │
│  SetupActivity shows blocking dialog if not whitelisted.               │
│  Uses REQUEST_IGNORE_BATTERY_OPTIMIZATIONS intent.                     │
│  Reference: xDrip+ Home.checkBatteryOptimization()                     │
├─────────────────────────────────────────────────────────────────────────┤
│  LAYER 8: Guardian Foreground Service                                  │
│  CgmGuardianForegroundService runs as a persistent foreground service  │
│  with a visible notification (dataSync type). Elevates process to      │
│  foreground priority, making OEM killers much less likely to target it. │
│  Reference: xDrip+ DoNothingService + ForegroundServiceStarter         │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│  STARTUP ENTRY POINTS (all schedule the heartbeat alarm)               │
├────────────────────┬────────────────────┬───────────────────────────────┤
│  App Launch        │  Device Boot       │  Setup Complete               │
│  CgmBridgeApp     │  BootReceiver      │  SetupActivity                │
│  .onCreate()      │  .onReceive()      │  .completeSetup()             │
│       │           │       │            │       │                       │
│       └───────────┼───────┼────────────┼───────┘                       │
│                   ▼       ▼            ▼                               │
│           NotificationPollScheduler.schedule(context)                  │
└─────────────────────────────────────────────────────────────────────────┘
```

## Layer Summary

| Layer | Component | Interval | Purpose | xDrip+ Equivalent |
|-------|-----------|----------|---------|-------------------|
| 1 | `CgmNotificationListenerService` | Event-driven | Core data receiver | `UiBasedCollector` |
| 2 | WakeLock in `onNotificationPosted()` | Per-notification (10s) | Prevent CPU sleep during processing | `JoH.getWakeLock()` |
| 3 | `NotificationPollScheduler` | 5 min exact alarm | External heartbeat, fires even in Doze | `Notifications.scheduleWakeup()` + `JoH.wakeUpIntent()` |
| 4 | `NotificationPollReceiver` | Triggered by Layer 3 | WakeLock trampoline + staleness check + rebind | `WakeLockTrampoline` |
| 5 | `HealthCheckWorker` | ~15 min (WorkManager) | Secondary watchdog, re-arms alarm | `MissedReadingService` |
| 6 | `onListenerDisconnected()` → `requestRebind()` | On disconnect | Self-heal when OS unbinds | (xDrip+ does NOT override this) |
| 7 | Battery Optimization bypass dialog | Once (setup) | Prevent Doze/Standby from killing app | `Home.checkBatteryOptimization()` |
| 8 | `CgmGuardianForegroundService` | Persistent | Foreground priority, OEM kill resistance | `DoNothingService` |

## Staleness Detection Flow

```
Is lastNotificationTimestampMs stale?
    │
    ├─ NotificationPollReceiver (Layer 4): stale > 7 min → requestRebind()
    │   └─ Fires every 5 min via AlarmManager
    │
    └─ HealthCheckWorker (Layer 5): stale > 15 min → requestRebind()
        └─ Fires every ~15 min via WorkManager
```

## Files Modified for Keep-Alive

| File | Change Type | Description |
|------|-------------|-------------|
| `NotificationPollScheduler.kt` | **NEW** | AlarmManager exact alarm scheduler (Samsung workaround) |
| `NotificationPollReceiver.kt` | **NEW** | WakeLock trampoline + liveness check + rebind recovery |
| `CgmNotificationListenerService.kt` | **MODIFIED** | Added WakeLock, liveness timestamp, alarm re-arm |
| `BridgeCGMApplication.kt` | **MODIFIED** | Starts heartbeat alarm on app launch |
| `BootReceiver.kt` | **MODIFIED** | Starts heartbeat alarm on device boot |
| `HealthCheckWorker.kt` | **MODIFIED** | Added liveness check (>15min → rebind), re-arms alarm |
| `SetupActivity.kt` | **MODIFIED** | Added blocking battery optimization dialog |
| `AppPrefs.kt` | **MODIFIED** | Added `lastNotificationTimestampMs`, `lastListenerConnectedMs` |
| `AndroidManifest.xml` | **MODIFIED** | Added `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, registered receiver |

## Logcat Tags for Monitoring

| Tag | Description |
|-----|-------------|
| `POLL-SCHED` | Heartbeat alarm scheduled/rescheduled |
| `POLL-BEAT` | Heartbeat fired (healthy) |
| `POLL-STALE` | No CGM notification for >7 min (recovery triggered) |
| `POLL-REBIND` | requestRebind() called from poll receiver |
| `POLL-NO-ACCESS` | Notification access permission revoked |
| `HC-STALE` | HealthCheckWorker detected staleness (>15 min) |
| `HC-REBIND` | requestRebind() called from health check |
| `NL-CONNECTED` | Listener connected/reconnected |
| `NL-DISCONNECTED` | Listener disconnected (rebind requested) |
| `NL-CREATE` | Listener service created |
| `NL-OUTER-EX` | Exception during notification data extraction |

