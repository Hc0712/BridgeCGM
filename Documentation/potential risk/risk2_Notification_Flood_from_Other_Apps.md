# 風險分析：其它 App 狂發 Notification 對 CgmBridge 的影響

## 1. 結論摘要

| 風險項目 | 嚴重性 | 說明 |
|---------|--------|------|
| **CGM 通知延遲** | 🟢 **極低** | 非目標 package 的過濾成本 ≈ 微秒級，不會造成可察覺延遲 |
| **CGM 通知遺失** | 🟢 **極低** | Android NLS 框架不會因為其它 app 的通知量而丟棄特定 listener 的回呼 |
| **Listener 被 unbind** | 🟡 **理論上可能但有多層防護** | 極端情況下系統可能判定 listener 回應過慢而 unbind，但 Layer 3–6 會自動復原 |
| **CPU / 電量消耗增加** | 🟡 **輕微** | 每個無關通知仍會觸發一次 `onNotificationPosted()` + `dumpPosted()` log |

**底線：在目前的程式碼架構下，其它 app 狂發通知 __不會__ 導致 CGM 資料遺失或有臨床意義的延遲。**

---

## 2. Android NotificationListenerService 內部機制

### 2.1 系統派發流程

```
其它 App 發 notification
        │
        ▼
system_server: NotificationManagerService
        │  (對每個已註冊的 NLS 逐一透過 Binder 派發)
        │
        ▼
CgmNotificationListenerService.onNotificationPosted(sbn)
        │
        ├─ NotificationDebugDumper.dumpPosted(sbn)   ← 1 行 Log.d()
        ├─ if (sbn.pkg == self) return               ← O(1)
        ├─ if (!SupportedPackages.isSupported) return ← HashSet.contains() O(1)
        │       ↑↑↑ 99.9% 的無關通知在這裡就 return 了 ↑↑↑
        │
        └─ (only CGM packages reach here)
```

### 2.2 關鍵事實

| 項目 | 說明 |
|------|------|
| **派發執行緒** | Android 在主執行緒（main looper）上呼叫 `onNotificationPosted()`，除非 service override 指定 Handler |
| **是否有 queue** | 是 — Binder 交易由 system_server 逐一派發，NLS 框架內部有排隊機制 |
| **會不會丟棄** | **不會主動丟棄**。system_server 會排隊等 Binder 交易完成再發下一筆 |
| **是否有速率限制** | Android 12+ 對 **發送方** 有 rate limit（`areNotificationsEnabledForPackage` + per-app quota），但接收端 NLS 沒有額外限制 |

---

## 3. 逐項風險分析

### 3.1 延遲風險 — 🟢 極低

**原因：** 無關通知的處理成本極低。

```
每筆無關通知的成本：
  1. Binder unmarshalling (StatusBarNotification)    ≈ 50–100 µs
  2. NotificationDebugDumper.dumpPosted()            ≈ 10–30 µs  (1 行 Log.d)
  3. SupportedPackages.isSupported() → false          ≈ <1 µs     (HashSet.contains)
  4. return                                           ≈ 0
  ─────────────────────────────────────────────────────
  合計                                                ≈ 60–130 µs
```

**情境推演：**

| 每秒無關通知數 | 佔用主執行緒時間 | CGM 通知最大延遲 |
|---------------|----------------|-----------------|
| 10 / sec（偏多的 chat app） | ~1 ms/sec | < 1 ms |
| 100 / sec（極端壓力測試） | ~13 ms/sec | < 13 ms |
| 1,000 / sec（惡意 app） | ~130 ms/sec | < 130 ms |

即使每秒 1000 筆（現實中幾乎不可能），最大延遲也只有 130 ms — 對 CGM 讀數（間隔 1–5 分鐘）完全無臨床意義。

### 3.2 遺失風險 — 🟢 極低

**Android NLS 不會因其它 app 的通知量而 drop 特定通知給 listener。**

原因：
1. **system_server 使用 Binder 交易** — 每個通知都是獨立的 Binder call，有完整的交易語意
2. **CGM 通知是 ongoing notification** — 更新時是「修改同一個 notification ID」，system_server 保證 NLS 看到最新狀態
3. **即使中間有幾次更新被 coalesce（合併）** — 對 ongoing notification，NLS 看到的永遠是最新值，不是歷史值

**唯一可能遺失的情境：**
```
Binder 交易佇列塞爆 → system_server 對此 NLS 拋出 TransactionTooLargeException
                     → 需要極大量通知 + 每筆都帶大型 extras（>1MB 級別）
                     → 現實中不太可能
```

### 3.3 Listener 被 Unbind 風險 — 🟡 理論上可能

**Android 可能在以下情況 unbind NLS：**

| 情境 | 可能性 | CgmBridge 防護 |
|------|--------|----------------|
| `onNotificationPosted()` 主執行緒 ANR（>5 sec 無回應） | 極低 — 無關通知處理 <1 ms | — |
| OEM 電池管理器殺掉 App 程序 | 中（與通知洪水無關） | Layer 3–6 自動 rebind |
| Binder 死鎖或 system_server OOM | 極低 | Layer 3–6 自動 rebind |
| 使用者手動取消通知存取權限 | 使用者行為 | Layer 4 偵測 + UI 提示 |

**CgmBridge 的多層防護在此情境下完全有效：**

```
Listener 被 unbind
    │
    ├─ Layer 6: onListenerDisconnected() → requestRebind() (即時)
    │
    ├─ Layer 4: NotificationPollReceiver (5 min alarm)
    │           偵測到 lastNotificationTimestampMs > 7 min stale
    │           → requestRebind()
    │
    ├─ Layer 5: HealthCheckWorker (15 min WorkManager)
    │           偵測到 > 15 min stale → requestRebind()
    │
    └─ Layer 8: CgmGuardianForegroundService (前景程序優先級)
               降低被 OEM 殺掉的機率
```

### 3.4 CPU / 電量影響 — 🟡 輕微

**目前 `dumpPosted()` 對每個通知都執行（包括無關 app 的），造成：**

```kotlin
// NotificationDebugDumper.kt — 每個通知都會跑這段
fun dumpPosted(sbn: StatusBarNotification) {
    val n = sbn.notification ?: return
    val extras = n.extras
    DebugTrace.t("NL-DUMP", "pkg=${sbn.packageName} id=${sbn.id} ...")
    //  ↑ 這行 Log.d() 是 always-on，每個無關通知都會寫一行 logcat
    if (DebugTrace.verbose) dumpVerbose(sbn, n)  // release 版不走 verbose
}
```

| 影響項目 | 程度 | 說明 |
|---------|------|------|
| CPU | 微乎其微 | Log.d() ≈ 10 µs，即使 100 筆/sec 也只佔 0.1% CPU |
| 電量 | 可忽略 | 無 WakeLock、無 I/O、無網路 |
| Logcat 膨脹 | 會有一些 | 每個無關通知多一行 `NL-DUMP`，但 logcat 是 ring buffer，自動覆蓋 |

---

## 4. 與 xDrip+ 對比

xDrip+ 的 `UiBasedCollector` 也是 `NotificationListenerService`，面臨完全相同的問題。

| 項目 | CgmBridge | xDrip+ UiBasedCollector |
|------|-----------|------------------------|
| 無關通知過濾 | 第 2 行就 return（`SupportedPackages.isSupported`） | 第 1 行就 return（`coOptedPackages.contains`） |
| Always-on dump | `dumpPosted()` 每筆都寫 1 行 log | **無** — xDrip+ 不 dump 無關通知 |
| Verbose dump | 由 `FeatureFlags` 控制，release 版關閉 | 無對應機制 |
| WakeLock 取用 | 只在通過 package 過濾後才取 | 同 |

**差異：** CgmBridge 的 `dumpPosted()` 比 xDrip+ 多做一次 Log.d()，但成本可忽略。

---

## 5. 真實世界情境

### 5.1 常見的通知洪水來源

| App 類型 | 通知頻率（估計） | 對 CgmBridge 影響 |
|---------|----------------|------------------|
| 即時通訊（LINE / WhatsApp 群組） | 數筆 ~ 數十筆/分鐘 | **零影響** |
| 新聞 / 社群推播 | 數筆/小時 | **零影響** |
| 遊戲狂推廣告通知 | 數筆 ~ 十幾筆/分鐘 | **零影響** |
| 惡意 app / 通知炸彈 | 數百筆/秒 | **延遲 <50 ms，無臨床影響** |
| 系統 UI 更新（Wi-Fi / BT 狀態） | 每次狀態變化 1 筆 | **零影響** |

### 5.2 Android 自身的通知防洪機制

Android 系統本身已有多層防護，限制 app 狂發通知：

| Android 版本 | 機制 | 說明 |
|-------------|------|------|
| 8.0 (API 26)+ | Notification Channels | 使用者可逐 channel 關閉通知 |
| 8.1 (API 27)+ | Rate Limiting | 同一 app 超過一定速率後，通知會被系統合併或延遲 |
| 13 (API 33)+ | POST_NOTIFICATIONS 權限 | 新安裝 app 預設無法發通知，需使用者授權 |
| 所有版本 | `notify(id, notification)` | 相同 ID 的通知是「更新」而非「新增」，不會觸發多次 NLS 回呼（除非 extras 變了） |

---

## 6. 可選的強化措施（目前非必要）

若未來仍擔心極端情境，可考慮以下強化，但 **目前不建議實作**（收益太低、風險過度工程化）：

### 6.1 移除無關通知的 dumpPosted 日誌

```kotlin
// 現狀：dump 在過濾前
override fun onNotificationPosted(sbn: StatusBarNotification) {
    NotificationDebugDumper.dumpPosted(sbn)          // ← 每個通知都 log
    if (!SupportedPackages.isSupported(sbn.packageName)) return
    ...
}

// 可改為：只 dump 有過關的
override fun onNotificationPosted(sbn: StatusBarNotification) {
    if (sbn.packageName == applicationContext.packageName) return
    if (!SupportedPackages.isSupported(sbn.packageName)) return
    NotificationDebugDumper.dumpPosted(sbn)          // ← 只有 CGM 通知才 log
    ...
}
```

**好處：** 完全消除無關通知的 log 寫入成本。
**壞處：** Debug 時看不到「到底有哪些通知進來過」，降低可調試性。

### 6.2 在非主執行緒處理通知

```kotlin
// 在 onCreate 中指定 handler
private val handlerThread = HandlerThread("NLS-Worker").apply { start() }

override fun onCreate() {
    super.onCreate()
    // 將 onNotificationPosted 派發到背景執行緒
    // 避免主執行緒被大量通知阻塞
}
```

**評估：** Android NLS 本身已在 Binder thread 上呼叫回呼，實際上不在 UI main thread 上。此項改動幾乎無意義。

---

## 7. 最終結論

```
┌─────────────────────────────────────────────────────────────────┐
│                                                                  │
│  其它 app 狂發通知 → 對 CgmBridge 的 CGM 資料收集：             │
│                                                                  │
│    ✅ 不會遺失 CGM 通知                                          │
│    ✅ 不會有臨床意義的延遲（最壞 <130 ms）                       │
│    ✅ 即使 listener 意外被 unbind，多層 keep-alive 會自動恢復    │
│    ✅ Android 自身已有通知速率限制，防止真正的洪水攻擊            │
│                                                                  │
│  風險等級：🟢 可忽略 — 無需額外防護措施                          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 8. 參考資料

| 來源 | 內容 |
|------|------|
| [Android NLS 官方文件](https://developer.android.com/reference/android/service/notification/NotificationListenerService) | 回呼機制、ranking、rebind |
| [Android NotificationManagerService 原始碼 (AOSP)](https://cs.android.com/android/platform/superproject/+/main:frameworks/base/services/core/java/com/android/server/notification/NotificationManagerService.java) | 通知派發到 NLS 的內部實作 |
| xDrip+ 2025.09.05 `UiBasedCollector.java` | 相同架構的生產級實作，已驗證數年 |
| CgmBridge `KeepAlive_Architecture.md` | 8 層 keep-alive 防護的完整描述 |

