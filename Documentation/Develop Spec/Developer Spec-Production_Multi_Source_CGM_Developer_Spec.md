# Production Multi-Source CGM Specification for Developers

## 1. Document Purpose

This document defines the **production implementation specification** for upgrading the app from a single-source glucose input model to a **multi-source CGM input architecture**.

This specification is based on:

- the current uploaded Kotlin project structure and its existing single-source data, alarm, and chart flow
- the previously agreed product rules for Version 1
- the requirement that the app must be able to run continuously for a long time, potentially up to **one year**

The goal of this production specification is to give developers one clear implementation target so that database design, input pipeline design, chart behavior, alarm behavior, and reliability behavior are all consistent.

---

## 2. Production Goals

The production implementation must achieve the following goals:

1. Accept multiple glucose input sources.
2. Keep one normalized storage model for all sources.
3. Preserve source identity for every reading.
4. Let the user choose **one exact primary output source**.
5. Apply calibration **only** to that selected primary output source.
6. Use **only** that selected primary output source for alarm logic.
7. Plot **all** sources on the main graph.
8. Plot **only** the selected primary output source on the mini graph.
9. If the primary output source becomes stale for more than **30 minutes**, notify the user but do not automatically switch to another source.
10. Support long-term continuous running with stable storage, bounded queries, and migration-safe schema evolution.

---

## 3. Version 1 Scope

### 3.1 Included in production Version 1

- multi-source database storage
- one normalized shared importer pipeline
- notification input adapter implemented now
- reserved framework for Bluetooth, Broadcast, and Network input adapters
- per-source deduplication
- source registry
- one exact user-selected primary output source
- calibration only on the primary output source
- alarm evaluation only from the primary output source
- main graph multi-source plotting
- mini graph single-source plotting
- stale-primary notification after 30 minutes

### 3.2 Explicitly excluded from production Version 1

- automatic source failover
- automatic switching of primary output
- automatic grouping of multiple channels into one physical CGM identity
- automatic selection by priority
- automatic freshest-source selection

These future behaviors must not be silently introduced in Version 1.

---

## 4. Existing Application Constraints

The current uploaded project is effectively built around a **single-source design** and must be refactored carefully.

### 4.1 Existing single-source assumptions in the current codebase

The current project contains:

- one shared `bg_reading` table in Room
- `BgReadingEntity` using `timestampMs` as the primary key
- a unique index on `timestampMs`
- repository methods that query one shared reading stream
- alarm evaluation that reads the latest reading globally
- chart code that renders from one list of readings rather than grouped multi-source datasets
- global calibration settings stored in `AppPrefs`
- global alarm settings stored in `AppPrefs` citeturn1search1

### 4.2 Production consequence

Because the current schema uses `timestampMs` as the primary key and also enforces uniqueness on `timestampMs`, two different sources with the same reading time can collide. This is unacceptable for a multi-source design and must be corrected before production rollout. citeturn1search1

### 4.3 Migration consequence

The uploaded project currently documents use of destructive migration behavior in `AppDatabase`, which is acceptable only for debug-stage reset behavior and is not acceptable for long-term production retention. Production must use explicit Room migrations instead. citeturn1search1

---

## 5. Core Production Rules

## 5.1 Primary output rule

The user selects **one exact source channel** as the app's **Primary Output Source**.

The app stores that choice as:

```text
primaryOutputSourceId
```

Version 1 must unify these roles:

```text
primary output source
= calibration target
= alarm source
= mini graph source
```

This must be implemented as one explicit production rule.

## 5.2 Non-primary source rule

All non-primary sources must:

- still be accepted
- still be normalized
- still be stored
- still be shown on the main graph as raw data
- not be calibrated
- not drive alarms
- not appear on the mini graph

## 5.3 No automatic identity inference

The app must not automatically decide whether two source channels are physically the same CGM.

Even if two sources share the same vendor name or similar values, Version 1 must treat them as separate source channels unless the user explicitly selects one exact source as primary.

---

## 6. Multi-Data Source Handling

This section defines the production handling of multi-source data.

---

## 6.1 Source identity

### 6.1.1 Definition

A **source channel** is one concrete incoming glucose path.

Examples:

- `notification:aidex:default`
- `notification:ottai:phone_a`
- `bluetooth:dexcom:ab12`
- `network:nightscout:user01`

### 6.1.2 Identity fields

Every source channel must have structured identity metadata:

- `sourceId`
- `transportType`
- `vendorName`
- `originKey`
- `displayName`

### 6.1.3 Why this is required

The same vendor may appear in multiple physical devices, and one physical device may be received through multiple transport types. Therefore, vendor name alone is not a safe production identity key.

### 6.1.4 Source ID design requirements

`sourceId` must be:

- stable after first creation
- lowercase only
- human-readable
- unique per source channel
- safe for Room keys, logging, and debugging

Recommended format:

```text
transporttype:vendorname:originkey
```

### 6.1.5 Important design rule

Although `sourceId` is human-readable, production logic must not rely on repeatedly parsing `sourceId` strings to recover metadata. The source registry must store `transportType`, `vendorName`, and `originKey` as separate fields.

---

## 6.2 Storage

### 6.2.1 Production storage strategy

Use **one Room database** for all glucose sources.

Do **not** create four separate databases.

Reason:

- one shared database makes graph queries simpler
- one shared schema avoids duplicated migration work
- one shared store supports a variable number of sources instead of hardcoding exactly four

### 6.2.2 Required production tables

Production must introduce or update these core tables:

1. `cgm_source`
2. `bg_reading`
3. existing `event_log` may remain for diagnostics

### 6.2.3 `cgm_source` table

Suggested production entity:

```kotlin
@Entity(tableName = "cgm_source")
data class CgmSourceEntity(
    @PrimaryKey val sourceId: String,
    val transportType: String,
    val vendorName: String,
    val originKey: String?,
    val displayName: String,
    val colorArgb: Int,
    val enabled: Boolean = true,
    val visibleOnMainGraph: Boolean = true,
    val lastSeenAtMs: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long
)
```

### 6.2.4 `bg_reading` table

Production `bg_reading` must no longer use `timestampMs` as the primary key.

Suggested production entity:

```kotlin
@Entity(
    tableName = "bg_reading",
    indices = [
        Index(value = ["sourceId", "timestampMs"], unique = true),
        Index(value = ["timestampMs"]),
        Index(value = ["sourceId"])
    ]
)
data class BgReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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

### 6.2.5 Why the new reading structure is mandatory

The current reading structure stores `sourcePackage` but still treats time as the unique identity anchor for the table. In a multi-source production system, timestamps are not globally unique. Different sources must be allowed to store the same timestamp independently. citeturn1search1

---

## 6.3 Graph

### 6.3.1 Main graph rule

The **main graph** must plot:

- all visible raw source datasets from all vendors and transport types
- the raw dataset of the selected primary output source
- the calibrated dataset of the selected primary output source **only if calibration is enabled**

### 6.3.2 Mini graph rule

The **mini graph** must plot only the selected primary output source.

Behavior:

- if calibration is enabled → show calibrated primary output
- if calibration is disabled → show raw primary output

### 6.3.3 Readability rule

If calibration is disabled, the main graph must **not** draw a second primary calibrated line identical to the primary raw line.

### 6.3.4 Styling rule

Recommended production styling:

- primary calibrated: thicker solid line
- primary raw: thinner dashed line
- other sources: thinner solid lines using stable source colors

### 6.3.5 Legend rule

Legend labels should include enough identity for users to tell lines apart.

Recommended label format:

```text
Vendor / Transport / Raw
Vendor / Transport / Calibrated
```

Example:

- `Aidex / Notification / Raw`
- `Aidex / Notification / Calibrated`
- `Ottai / Bluetooth / Raw`

### 6.3.6 Query split rule

Production must separate chart queries by purpose:

- **main graph query** → all visible sources within the selected time window
- **mini graph query** → only `primaryOutputSourceId` within the selected time window

This is necessary because the current chart flow is based on one shared list of readings and must be upgraded to explicitly source-aware datasets. citeturn1search1

---

## 6.4 Calibration

### 6.4.1 Production calibration scope

In Version 1, calibration applies **only** to the selected `primaryOutputSourceId`.

### 6.4.2 Insert rule for primary source

If:

```text
reading.sourceId == primaryOutputSourceId
```

then:

- `calculatedValueMgdl` = parsed raw glucose value
- if calibration enabled:
  - `calibratedValueMgdl` = calibrated result
- else:
  - `calibratedValueMgdl` = raw value

### 6.4.3 Insert rule for non-primary sources

If:

```text
reading.sourceId != primaryOutputSourceId
```

then:

- `calculatedValueMgdl` = parsed raw glucose value
- `calibratedValueMgdl` = parsed raw glucose value

This preserves a stable schema while ensuring only the primary source is functionally calibrated.

### 6.4.4 Reason for this design

The current project stores calibration settings globally in `AppPrefs`, so production must avoid ambiguous “calibrate some but not all” behavior by defining one exact source-scoped rule. citeturn1search1

---

## 6.5 Alarm

### 6.5.1 Production alarm rule

Only the selected primary output source may drive alarm evaluation.

### 6.5.2 Alarm relation rule

Production must implement this flow exactly:

```text
primary output source
→ calibration applied if enabled
→ calibrated primary output
→ existing alarm engine evaluates calibrated primary output
```

### 6.5.3 Non-primary alarm exclusion

Non-primary sources must never trigger alarms in Version 1.

### 6.5.4 Why this production rule is required

The current project's alarm design is global-single-source in behavior, with settings in `AppPrefs` and evaluation against the latest reading path. A multi-source production rollout must explicitly choose one source for alarm responsibility rather than let all sources compete. citeturn1search1

---

## 6.6 Disconnection of primary input

### 6.6.1 Stale threshold

A primary source is considered stale if no new reading has been received from `primaryOutputSourceId` for more than:

```text
30 minutes
```

### 6.6.2 Stale behavior

When the primary source becomes stale:

- send a user notification
- do not auto-switch to another source
- continue receiving and storing other sources
- continue plotting other sources on the main graph as raw data

### 6.6.3 Notification spam protection

Use one-notification-per-stale-episode behavior:

- when primary first becomes stale → send one notification
- while primary remains stale → do not repeat continuously
- when primary becomes fresh again → clear the stale state
- if it later becomes stale again → send a new notification

### 6.6.4 Why Version 1 does not auto-switch

Automatic fallback requires either source grouping or policy-based identity handling, which is intentionally excluded from Version 1 to keep behavior simple and reliable.

---

## 7. Deduplication — Mandatory Per-Source Design

### 7.1 Production rule

Deduplication must become **per source**.

### 7.2 Required logic

Deduplicate using at least:

- `sourceId`
- timestamp window

Optional future enhancements may include same-value heuristics, but Version 1 must first guarantee source-scoped deduplication.

### 7.3 Absolute rule

Do not deduplicate across different sources.

Reason:

Two separate source channels may legitimately carry similar or identical glucose values at the same time, and the user may want to compare them visually.

### 7.4 Current code impact

The current notification importer and reading flow were designed around single-source notification handling. Production must refactor this into source-scoped deduplication before enabling multi-source storage. citeturn1search1

---

## 8. Normalized Pipeline for All Inputs

### 8.1 Production objective

All input paths must converge into one normalized pipeline.

### 8.2 Input adapters

#### Implement now
- `NotificationInputAdapter`

#### Reserve framework now
- `BluetoothInputAdapter`
- `BroadcastInputAdapter`
- `NetworkInputAdapter`

### 8.3 Adapter responsibility

Each adapter must only:

- receive transport-specific input
- perform vendor-specific parsing
- identify the source channel
- produce one normalized reading contract

### 8.4 Shared normalized reading contract

Suggested production contract:

```kotlin
data class NormalizedReading(
    val sourceId: String,
    val transportType: String,
    val vendorName: String,
    val originKey: String?,
    val timestampMs: Long,
    val valueMgdl: Int,
    val direction: String,
    val rawText: String,
    val sensorStatus: String?,
    val alertText: String?
)
```

### 8.5 Shared pipeline stages

The shared importer pipeline must run the following steps in order:

1. validate normalized reading
2. upsert source registry entry
3. update source last-seen timestamp
4. run per-source deduplication
5. check whether source is the current primary output source
6. compute calibrated value if applicable
7. insert into `bg_reading`
8. evaluate alarm only if the reading belongs to the primary source
9. update stale-primary state if needed
10. notify UI and graph observers

### 8.6 Production benefit

A normalized pipeline avoids scattering business logic into transport-specific services and keeps future Bluetooth, Broadcast, and Network support consistent with the same storage and chart behavior.

---

## 9. Relation Between Primary Input, Calibration, and Alarms

This relation must be treated as one explicit production contract.

## 9.1 Contract

```text
selected primaryOutputSourceId
→ optional calibration
→ calibrated primary output
→ alarm evaluation
→ mini graph display
```

## 9.2 Consequence

There must be no separate production selector for:

- alarm source
- calibration source
- mini graph source

Version 1 must bind them to one exact source choice.

## 9.3 Main graph relation

The main graph still shows all available raw sources, plus the calibrated overlay for the primary source when calibration is enabled.

---

## 10. Repository and DAO Requirements

### 10.1 New source-aware queries

Production repository and DAO layers must add source-aware methods.

Required examples:

```kotlin
suspend fun latestReadingForSource(sourceId: String): BgReadingEntity?
fun readingsInRangeForSources(sourceIds: List<String>, fromMs: Long, toMs: Long): Flow<List<BgReadingEntity>>
fun readingsInRangeForSource(sourceId: String, fromMs: Long, toMs: Long): Flow<List<BgReadingEntity>>
```

### 10.2 Reason

The current repository only exposes global time-based reading flows and latest-reading queries, which is insufficient for production multi-source plotting and source-specific alarm evaluation. citeturn1search1

---

## 11. UI and Source Selection Requirements

### 11.1 Selection storage

The selected source must be persisted as:

```text
primaryOutputSourceId
```

### 11.2 Selection UI

Production should provide a dedicated source selection screen.

Recommended entry path:

- `SettingsMenuActivity`
  - opens `SourceManagementActivity` or equivalent

### 11.3 Source list item content

Each listed source should show:

- vendor name
- transport type
- display name or origin hint
- last updated time
- active or stale status

### 11.4 User action

User action should be simple:

- **Set as Primary Output**

Version 1 should avoid exposing multiple overlapping role toggles.

---

## 12. Long-Term Running Support

### 12.1 Production requirement

The app must be able to run continuously for a long time, potentially up to one year.

### 12.2 Required production measures

#### Database schema safety
- remove destructive migration behavior in production
- provide explicit Room migration paths
- test schema upgrade from prior versions

#### Query safety
- keep all chart queries time-bounded
- avoid unbounded full-table graph loads
- keep source-specific indexes in place

#### Retention safety
- retain glucose readings up to configured retention target
- purge old logs more aggressively than glucose readings if needed

#### Background reliability
- preserve current keep-alive foundations where appropriate
- ensure multi-source additions do not break existing notification listener stability model

### 12.3 Existing project support that can be reused

The uploaded project already contains:

- WorkManager-based health check and maintenance workers
- alarm-based keep-alive polling
- database maintenance cleanup behavior
- notification listener infrastructure citeturn1search1

These can be extended rather than replaced, as long as production changes do not reintroduce destructive reset behavior or unbounded data processing. citeturn1search1

---

## 13. Implementation Order Recommendation

Production rollout should follow this order:

### Phase 1 — Schema foundation
- add `cgm_source`
- change `bg_reading` primary key to auto-generated `id`
- add unique index on `(sourceId, timestampMs)`
- create Room migration

### Phase 2 — Source-aware data model
- introduce source registry handling
- add `primaryOutputSourceId` persistence
- add source-aware DAO and repository methods

### Phase 3 — Normalized pipeline
- refactor notification input into normalized reading flow
- implement source upsert and per-source dedupe
- implement primary-source routing

### Phase 4 — Calibration and alarm relation
- apply calibration only for primary source
- route alarm evaluation only from primary source
- add stale-primary notification logic

### Phase 5 — Graph upgrade
- main graph multi-source datasets
- primary calibrated overlay on main graph
- mini graph single-source rendering

### Phase 6 — UI
- source management screen
- primary output selection UX
- stale-primary visual status

---

## 14. Non-Negotiable Production Rules

The following production rules are mandatory:

1. Deduplication must be per source.
2. `bg_reading` must no longer use `timestampMs` as the primary key.
3. All sources must be stored in one normalized database model.
4. Only one exact `primaryOutputSourceId` may drive calibration, alarms, and mini graph.
5. Non-primary sources must remain raw-only in Version 1.
6. Main graph must show all sources; mini graph must show only primary output.
7. Primary source disconnection for more than 30 minutes must notify the user but must not auto-switch.
8. Production must not use destructive migration for long-term running builds.

---

## 15. Production Summary

This production specification defines a simple but reliable Version 1 multi-source architecture:

- one shared source registry
- one normalized reading table
- per-source deduplication
- one exact user-selected primary output source
- calibration only on primary output
- alarms only from calibrated primary output
- main graph for all sources
- mini graph for primary output only
- stale-primary notification only, no auto failover
- migration-safe, long-running production storage behavior

This is the approved production direction for developers to implement.
