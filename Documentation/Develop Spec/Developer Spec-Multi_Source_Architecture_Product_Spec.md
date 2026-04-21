# Multi-Source CGM Input Architecture — Product Specification

## 1. Purpose

This document defines the first reliable multi-source version of the app.

The goal is to let the app:

- accept multiple incoming glucose data sources
- store all incoming data for plotting and comparison
- let the user choose **one exact primary input source**
- apply calibration only to that primary input source
- use only that primary input source for alarms
- show all sources on the main graph
- show only the primary input on the mini graph
- notify the user if the primary input source becomes stale for more than 30 minutes

This version intentionally avoids automatic source failover to keep the architecture simple, predictable, and reliable for long-running operation.

---

## 2. Scope of Version 1

### Included in Version 1

- multi-source input support
- normalized input pipeline
- notification input adapter implemented now
- Bluetooth, Broadcast, and Network adapter framework reserved for future work
- one user-selected **primary input source**
- calibration applied only to the primary input source
- alarms driven only by the calibrated primary input source
- main graph plots all sources
- main graph also plots both raw and calibrated data for the primary source when calibration is enabled
- mini graph plots only the primary input source
- stale-primary notification after 30 minutes without new primary data

### Explicitly excluded from Version 1

- automatic failover to another source
- automatic switching of alarm target
- logical grouping of multiple source channels into one physical CGM identity
- automatic detection of physically identical CGMs
- source-priority or freshest-source selection policies

---

## 3. Key Definitions

### 3.1 Source Channel
A **source channel** is one concrete incoming data path.

Examples:

- `notification:aidex:default`
- `notification:ottai:default`
- `bluetooth:dexcom:ab12`
- `network:nightscout:user01`

A source channel is the unit that the app can detect, store, and select.

### 3.2 Primary input Source
The **Primary input Source** is one exact source channel selected by the user.

It is stored internally as:

```text
primaryInputSourceId
```

### 3.3 Raw Data
Raw data is the parsed glucose value before calibration.

### 3.4 Calibrated Data
Calibrated data is the result of applying the existing calibration algorithm to the raw value.

In Version 1, calibration applies only to the primary input source.

### 3.5 Stale Primary Source
A primary source is considered **stale** when no new reading has been received from the selected `primaryInputSourceId` for more than **30 minutes**.

---

## 4. Core Product Rules

### 4.1 One exact primary input source
The user selects one exact source channel as the Primary input Source.

The UI may show sources using:

- vendor name
- transport type
- display name or origin hint

However, the saved selection must be the exact `sourceId`.

### 4.2 Unified source roles in Version 1
In Version 1, the following roles are locked together:

- primary input source
- calibration target
- alarm target
- mini graph source

That means:

```text
primaryInputSourceId
= calibration target
= alarm source
= mini graph source
```

### 4.3 Handling of non-primary sources
All non-primary sources must:

- continue to be parsed
- continue to be stored in the database
- continue to be shown on the main graph as raw data
- not receive calibration
- not drive the alarm engine
- not appear on the mini graph

### 4.4 Stale primary handling
If the primary input source has not updated for more than 30 minutes:

- the app sends one user notification for that stale episode
- the app does not auto-switch to another source
- the app continues to accept, store, and plot other sources as raw data

### 4.5 No automatic identity inference
The app must not assume that two sources are physically the same CGM simply because they share the same vendor or similar data.

Only the user decides which exact source channel should be treated as the Primary input Source.

---

## 5. Source Model

## 5.1 Source registry
Use a dedicated source registry table to describe each detected source channel.

Suggested fields:

```kotlin
data class CgmSourceEntity(
    val sourceId: String,
    val transportType: String,   // NOTIFICATION, BLUETOOTH, BROADCAST, NETWORK
    val vendorName: String,      // Aidex, Ottai, Dexcom, ...
    val originKey: String?,
    val displayName: String,
    val colorArgb: Int,
    val enabled: Boolean,
    val visibleOnMainGraph: Boolean,
    val lastSeenAtMs: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long
)
```

## 5.2 Source ID design rules
`sourceId` should be:

- stable after first creation
- lowercase only
- human-readable
- unique per source channel
- safe for database keys and logs

Recommended format:

```text
transporttype:vendorname:originkey
```

Examples:

- `notification:aidex:default`
- `notification:ottai:phone_a`
- `bluetooth:dexcom:ab12`

### Important rule
Even if `sourceId` expresses transport and vendor information, the app must still store:

- `transportType`
- `vendorName`
- `originKey`

as separate structured fields in the source registry.

---

## 6. Reading Model

## 6.1 `bg_reading` requirements
Use one shared `bg_reading` table for all sources.

Recommended fields:

```kotlin
data class BgReadingEntity(
    val id: Long,
    val sourceId: String,
    val timestampMs: Long,
    val calculatedValueMgdl: Int,
    val calibratedValueMgdl: Int,
    val direction: String,
    val rawText: String,
    val sensorStatus: String?,
    val alertText: String?
)
```

## 6.2 Database constraints
Use:

- auto-generated primary key `id`
- unique index on `(sourceId, timestampMs)`
- index on `timestampMs`
- index on `sourceId`

This allows different sources to store readings with the same timestamp without collision.

---

## 7. Input Pipeline

## 7.1 Normalized pipeline
All input adapters must feed one normalized pipeline.

### Implement now
- `NotificationInputAdapter`

### Reserve for future
- `BluetoothInputAdapter`
- `BroadcastInputAdapter`
- `NetworkInputAdapter`

## 7.2 Normalized reading contract
Each adapter should convert its source-specific input into one shared internal model.

Suggested contract:

```kotlin
data class NormalizedReading(
    val sourceId: String,
    val timestampMs: Long,
    val valueMgdl: Int,
    val direction: String,
    val rawText: String,
    val sensorStatus: String?,
    val alertText: String?
)
```

## 7.3 Shared pipeline stages
After normalization, the common pipeline should perform:

1. source registration or source update
2. per-source deduplication
3. primary-source check
4. calibration decision
5. database insert
6. alarm evaluation if applicable
7. stale-primary state update
8. UI refresh / chart refresh

---

## 8. Deduplication Rules

## 8.1 Deduplicate by source
Deduplication must be scoped by `sourceId`.

### Required rule
- deduplicate only within the same `sourceId`
- do not deduplicate across different sources

This preserves distinct source lines even when two sources produce very similar readings.

---

## 9. Calibration Rules

## 9.1 Version 1 calibration scope
Calibration applies only to the selected `primaryInputSourceId`.

## 9.2 Insert-time behavior
### If `reading.sourceId == primaryInputSourceId`
- if calibration is enabled:
  - `calculatedValueMgdl = raw parsed value`
  - `calibratedValueMgdl = calibrated result`
- if calibration is disabled:
  - `calculatedValueMgdl = raw parsed value`
  - `calibratedValueMgdl = raw parsed value`

### If `reading.sourceId != primaryInputSourceId`
- `calculatedValueMgdl = raw parsed value`
- `calibratedValueMgdl = raw parsed value`

This means non-primary sources are stored without additional handling.

---

## 10. Alarm Rules

## 10.1 Version 1 alarm source
Only the calibrated primary input source may drive the alarm engine.

## 10.2 Alarm flow
```text
primary input source
→ calibration applied if enabled
→ calibrated primary input
→ existing alarm logic evaluates calibrated primary input
```

## 10.3 Non-primary sources
Non-primary sources must never trigger alarms in Version 1.

---

## 11. Graph Requirements

## 11.1 Main graph
The main graph must plot:

- all visible raw sources from different vendor names and transport types
- the raw line of the selected primary source
- the calibrated line of the selected primary source only if calibration is enabled

### Main graph behavior rules
- All non-primary sources appear as raw data only.
- The selected primary source always appears as raw data.
- If calibration is enabled, the selected primary source also appears as a separate calibrated overlay.
- If calibration is disabled, the app must not plot a duplicate calibrated line identical to the raw primary line.

## 11.2 Mini graph
The mini graph must plot only the primary input source.

### Mini graph behavior rules
- If calibration is enabled: plot calibrated primary input data.
- If calibration is disabled: plot raw primary input data.
- No non-primary source appears on the mini graph.

## 11.3 Visual styling recommendations
To keep the graph readable:

- primary calibrated line: thicker solid line
- primary raw line: thinner dashed line
- other sources: thinner solid lines with stable per-source colors
- legend labels should clearly distinguish raw vs calibrated for the primary source

Example legend labels:

- `Aidex / Notification / Raw`
- `Aidex / Notification / Calibrated`
- `Ottai / Bluetooth / Raw`

---

## 12. User Selection Flow

## 12.1 User-facing selection logic
The user should select the primary input source from a list of detected source channels.

Each selectable item should show:

- vendor name
- transport type
- display name or origin hint
- last updated time
- active or stale state

## 12.2 Internal persistence
The final selection must be saved as:

```text
primaryInputSourceId
```

## 12.3 Source Management screen
Use a dedicated source-selection screen rather than putting all logic directly on the root settings page.

Suggested flow:

- `SettingsMenuActivity`
  - opens **Source Management** or **Primary input Selection** screen
- user selects one exact source
- user taps **Set as Primary input**

---

## 13. Stale Primary Notification Policy

## 13.1 Stale threshold
A primary source is stale if it has not updated for more than **30 minutes**.

## 13.2 Notification behavior
Use one-notification-per-stale-episode behavior:

- when primary first becomes stale → send one notification
- while it remains stale → do not repeatedly spam notifications
- when primary becomes fresh again → clear stale state
- if it becomes stale again later → send a new notification

## 13.3 Non-switching rule
A stale primary source does not cause automatic switching in Version 1.

---

## 14. Reliability and Long-Running Operation

## 14.1 Version 1 reliability goal
The app should be able to run continuously for a very long time.

### Therefore:
- preserve schema compatibility with real migrations
- avoid destructive database resets in production
- use bounded graph queries by time range
- keep source roles explicit and simple
- avoid automatic source switching in Version 1
- keep stale handling user-visible and deterministic

## 14.2 Storage retention
Retention policy may keep:

- glucose readings for up to one year
- logs for a shorter debug-focused window

Retention should remain configurable if product requirements change.

---

## 15. Version 1 Summary

Version 1 uses a simple but reliable architecture:

- one normalized multi-source input pipeline
- one exact primary input source selected by the user
- calibration only on the primary input source
- alarms only from the calibrated primary input source
- main graph shows all raw sources plus calibrated overlay for the primary source when enabled
- mini graph shows only the primary input source
- stale primary for over 30 minutes causes notification only
- no automatic source failover

This design keeps the software architecture understandable and stable while leaving room for future expansion.
