# DatabaseMaintenanceWorker — 規格說明

## 1. 概述

`DatabaseMaintenanceWorker` 是一個 Android WorkManager 定期任務，負責清除 Room 資料庫中的過期資料，防止資料庫無限膨脹。

## 2. 排程方式

```
BridgeCGMApplication.onCreate()
  └─ WorkManager.enqueueUniquePeriodicWork(
         "database-maintenance",
         ExistingPeriodicWorkPolicy.KEEP,
         PeriodicWorkRequestBuilder<DatabaseMaintenanceWorker>(1, TimeUnit.DAYS)
     )
```

| 項目 | 值 |
|------|---|
| 排程間隔 | **每 24 小時** |
| 排程策略 | `KEEP` — 若已排程則不重複建立 |
| 啟動時機 | App 任何元件首次啟動時（Application.onCreate） |
| 執行緒 | `CoroutineWorker` — WorkManager 背景執行緒 |

## 3. 清除規則

```kotlin
override suspend fun doWork(): Result {
    val now = System.currentTimeMillis()
    Repository(applicationContext).purgeOldData(
        bgOlderThanMs  = now - 365 days,   // 血糖資料保留 1 年
        logOlderThanMs = now - 30 days      // Debug log 保留 30 天
    )
    return Result.success()
}
```

### 3.1 血糖資料 (`bg_reading` 表)

| 項目 | 值 |
|------|---|
| 保留時長 | **365 天（1 年）** |
| 刪除條件 | `timestampMs < now - 365 days` |
| 刪除方式 | `DELETE FROM bg_reading WHERE timestampMs < :olderThanMs` |

### 3.2 事件日誌 (`event_log` 表)

| 項目 | 值 |
|------|---|
| 保留時長 | **30 天** |
| 刪除條件 | `timestampMs < now - 30 days` |
| 刪除方式 | `DELETE FROM event_log WHERE timestampMs < :olderThanMs` |

## 4. 資料量估算（Plan C: 固定 50 秒 dedup）

### 4.1 AiDEX（1 分鐘間隔）

| 週期 | 筆數 | 大小 |
|------|------|------|
| 每天 | 1,440 筆 (60 × 24) | ~183 KB |
| 每月 | 43,200 筆 | ~5.5 MB |
| 每年（保留上限）| 525,600 筆 | ~66 MB |

### 4.2 Dexcom（5 分鐘間隔）

| 週期 | 筆數 | 大小 |
|------|------|------|
| 每天 | 288 筆 (12 × 24) | ~37 KB |
| 每月 | 8,640 筆 | ~1.1 MB |
| 每年（保留上限）| 105,120 筆 | ~13 MB |

### 4.3 每筆大小估算

```
BgReadingEntity:
  timestampMs: Long           →  8 bytes
  calculatedValueMgdl: Int    →  4 bytes
  direction: String           → ~10 bytes
  sourcePackage: String       → ~25 bytes
  rawText: String             → ~20 bytes
  sensorStatus: String?       →  ~5 bytes
  alertText: String?          →  ~5 bytes
  + SQLite overhead (index, row header) → ~50 bytes
  ──────────────────────────────────────
  每筆合計                     → ~130 bytes
```

## 5. 安全性分析

| 風險 | 分析 |
|------|------|
| DB 過大 | AiDEX 最大 66 MB/年 — 手機通常有數 GB 可用空間 |
| SQLite 效能 | 52 萬筆/年 — 遠低於百萬筆效能瓶頸 |
| 清除遺漏 | WorkManager `KEEP` 策略確保排程不重複但也不遺漏 |
| 清除失敗 | `doWork()` 回傳 `Result.success()` — 即使刪除 0 筆也算成功 |

## 6. 涉及檔案

| 檔案 | 角色 |
|------|------|
| `worker/DatabaseMaintenanceWorker.kt` | Worker 本體 |
| `BridgeCGMApplication.kt` | 排程註冊（line 57-61）|
| `data/Repository.kt` | `purgeOldData()` 方法 |
| `data/db/Dao.kt` | `BgReadingDao.deleteOlderThan()` + `EventLogDao.deleteOlderThan()` |

## 7. 資料流圖

```
WorkManager (每 24 小時觸發)
  │
  ▼
DatabaseMaintenanceWorker.doWork()
  │
  ├─ Repository.purgeOldData(bgOlderThanMs, logOlderThanMs)
  │    │
  │    ├─ BgReadingDao.deleteOlderThan(now - 365 days)
  │    │    └─ DELETE FROM bg_reading WHERE timestampMs < ?
  │    │
  │    └─ EventLogDao.deleteOlderThan(now - 30 days)
  │         └─ DELETE FROM event_log WHERE timestampMs < ?
  │
  └─ return Result.success()
```

## 8. 與 xDrip+ 對比

| 項目 | CgmBridge | xDrip+ |
|------|-----------|--------|
| 清除機制 | WorkManager 定期任務 | 手動或自動清除（不同模組各自管理）|
| 血糖資料保留 | 365 天 | 無統一設定（依 SD 卡備份）|
| Log 保留 | 30 天 | 無統一設定 |
| 清除頻率 | 每天一次 | 不定 |

## 9. 未來考量

- **使用者自訂保留天數**：目前硬編碼 365 天 / 30 天，可改為 SharedPreferences 可調
- **匯出功能**：清除前可提供 CSV/JSON 匯出選項
- **清除通知**：可加入清除完成的 debug log（目前無）
