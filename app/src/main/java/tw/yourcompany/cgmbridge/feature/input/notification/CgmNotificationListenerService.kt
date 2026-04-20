/**
 * Clean-refactor note:
 * This file was migrated into a feature-oriented package so future contributors can
 * work on one functional area with fewer cross-package side effects. The runtime
 * behavior is intended to remain aligned with the original BridgeCGM implementation.
 */
package tw.yourcompany.cgmbridge.feature.input.notification

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tw.yourcompany.cgmbridge.core.data.Repository
import tw.yourcompany.cgmbridge.core.prefs.AppPrefs
import tw.yourcompany.cgmbridge.core.logging.DebugCategory
import tw.yourcompany.cgmbridge.core.logging.DebugTrace
import tw.yourcompany.cgmbridge.feature.output.xdrip.XDripBroadcastSender
import tw.yourcompany.cgmbridge.feature.keepalive.NotificationPollScheduler

/**
 * Reads CGM notifications and bridges them to xDrip+.
 *
 * Data extraction follows xDrip+ UiBasedCollector logic:
 *   1. Only process notifications from known CGM packages.
 *   2. Only process ongoing (persistent) notifications, unless the package is
 *      in SupportedPackages.shouldProcessAll().
 *   3. Primary data path: inflate [Notification.contentView] (RemoteViews) and
 *      walk the view tree to extract text from every visible TextView.
 *   4. Fallback data path: read standard extras (EXTRA_TITLE, EXTRA_TEXT, etc.).
 *
 * Reference: xDrip-2025.09.05  UiBasedCollector.java
 *
 * IMPORTANT troubleshooting:
 * - User must enable Notification Access for this app in Android settings.
 * - Some OEMs may block background operations; use the in-app buttons.
 */
class CgmNotificationListenerService : NotificationListenerService() {

    /** IO coroutine scope used for database writes and parser work. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Serializes the parse → gap-check → DB-insert → slope → broadcast pipeline.
     *
     * Without this lock, AiDEX's two rapid notifications (~960ms apart) spawn
     * two concurrent coroutines that BOTH call `repo.latestReadings(1)` before
     * either commits — a classic TOCTOU race. Both see the same stale "latest"
     * reading, both pass the 50-second gap check, and both insert. This causes:
     *
     *   Bug 1 (duplicate dots): two readings 960ms apart in the DB; if they
     *          straddle a minute boundary the chart dedup keeps both.
     *   Bug 2 (shifted gap reference): the extra reading shifts the baseline
     *          for the next gap check, potentially causing legitimate readings
     *          to be rejected as "too soon".
     *
     * xDrip+ avoids this by processing `handleNewValue()` synchronously on a
     * single thread. We achieve the same guarantee with a Mutex — the second
     * coroutine waits until the first finishes its DB insert, then correctly
     * sees the freshly inserted reading and gets rejected by the gap filter.
     */
    private val importMutex = Mutex()

    /** Repository for Room operations. */
    private lateinit var repo: Repository

    /** Import helper for normalized samples. */
    private lateinit var importer: BgReadingImporter

    /** Parser for CGM notification text. */
    private lateinit var parser: GenericCgmNotificationParser

    /** SharedPreferences wrapper for liveness tracking. */
    private lateinit var prefs: AppPrefs

    /** PowerManager for WakeLock acquisition during processing. */
    private lateinit var powerManager: PowerManager

    /**
     * Initializes repository, parser, and keep-alive infrastructure.
     *
     * Called once when the Android framework binds this listener service.
     * The [powerManager] is cached here for efficient WakeLock acquisition
     * in [onNotificationPosted] (Layer 2).
     */
    override fun onCreate() {
        super.onCreate()
        repo = Repository(applicationContext)
        prefs = AppPrefs(applicationContext)
        importer = BgReadingImporter(repo, prefs)
        parser = GenericCgmNotificationParser()
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        DebugTrace.t(DebugCategory.NOTIFICATION, "NL-CREATE", "Notification listener created")
    }

    /**
     * ── KEEP-ALIVE: Alarm re-arm on reconnect ──
     *
     * Called by the Android framework when the listener connection is active.
     * This can happen at:
     * - Initial bind (after user grants Notification Access)
     * - Rebind after `requestRebind()` (from Layers 4, 5, or 6)
     * - System reconnect after temporary unbind
     *
     * We use this callback to:
     * 1. Record the connection time for diagnostics.
     * 2. Re-arm the heartbeat alarm — the alarm might have been lost if the
     *    process was killed and restarted by the system.
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        if (!AppPrefs(applicationContext).disclaimerAccepted) return
        prefs.lastListenerConnectedMs = System.currentTimeMillis()
        DebugTrace.t(DebugCategory.NOTIFICATION, "NL-CONNECTED", "Notification listener connected")
        // Re-arm the watchdog alarm whenever the listener (re)connects.
        NotificationPollScheduler.schedule(applicationContext)
    }

    /**
     * ── KEEP-ALIVE LAYER 6: Self-healing on disconnect ──
     *
     * Called by the Android framework when the listener disconnects.
     * Immediately requests a rebind to restore the connection.
     *
     * ### Important caveat:
     * This callback is UNRELIABLE as a sole defense. When aggressive OEMs
     * (Samsung, Xiaomi, Huawei) kill the entire app process, this callback
     * never fires because the process is already dead. This is why Layers
     * 3–5 (external watchdogs) exist.
     *
     * xDrip+ UiBasedCollector does NOT even override this method — it relies
     * entirely on external defense layers. We add it as a "nice to have" for
     * cases where the system gracefully unbinds without killing the process.
     */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        DebugTrace.e(DebugCategory.NOTIFICATION, "NL-DISCONNECTED", "Notification listener disconnected; requesting rebind")
        requestRebind(ComponentName(this, CgmNotificationListenerService::class.java))
    }

    /**
     * Called whenever any notification is posted.
     *
     * Filtering matches xDrip+ UiBasedCollector.onNotificationPosted():
     *   1. Package must be in coOptedPackages.
     *   2. Notification must be ongoing OR package must be in coOptedPackagesAll.
     */
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!AppPrefs(applicationContext).disclaimerAccepted) return
        // Debug dump first, before any filtering.
        NotificationDebugDumper.dumpPosted(sbn)

        // Ignore this app's own ongoing foreground notification.
        if (sbn.packageName == applicationContext.packageName) {
            DebugTrace.t(DebugCategory.NOTIFICATION, "NL-IGNORE-SELF", "Ignored own notification id=${sbn.id}")
            return
        }

        // Only parse known supported packages (exact match, like xDrip+ coOptedPackages).
        if (!SupportedPackages.isSupported(sbn.packageName)) {
            return  // silent — avoid flooding log for every unrelated notification
        }

        // xDrip+ only processes ongoing notifications OR packages in coOptedPackagesAll.
        val isOngoing = sbn.isOngoing
        if (!isOngoing && !SupportedPackages.shouldProcessAll(sbn.packageName)) {
            DebugTrace.t(DebugCategory.NOTIFICATION, "NL-SKIP-NOT-ONGOING", "Skipped non-ongoing notification from ${sbn.packageName} id=${sbn.id}")
            return
        }

        DebugTrace.t(DebugCategory.NOTIFICATION, "NL-ON_POSTED", "pkg=${sbn.packageName} id=${sbn.id} ongoing=$isOngoing")

        // ── Update liveness timestamp for heartbeat watchdog ──
        // This proves the listener is still connected and receiving CGM notifications.
        prefs.lastNotificationTimestampMs = System.currentTimeMillis()

        val notification: Notification = sbn.notification ?: return

        // ── Acquire WakeLock to prevent CPU sleep during processing ──
        // Reference: xDrip+ JoH.getWakeLock() in DoNothingService / Notifications
        // Acquired before data extraction so the CPU stays awake through the entire
        // pipeline: extract → parse → DB write → broadcast.
        val wl = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CgmBridge:NotifProcess"
        ).apply { acquire(10_000L) }  // 10s max — parse + DB write + broadcast

        // All data extraction + coroutine launch wrapped in try/finally to guarantee
        // WakeLock release even if an exception occurs before the coroutine starts.
        try {

        // ---------------------------------------------------------------
        // PRIMARY DATA PATH: inflate contentView and extract text.
        // Reference: xDrip+ processRemote(notification.contentView)
        // ---------------------------------------------------------------
        val contentViewTexts = RemoteViewsTextExtractor.extractTexts(applicationContext, notification)

        // ---------------------------------------------------------------
        // FALLBACK DATA: standard notification extras.
        // Reference: xDrip+ processNotification() else branch
        // ---------------------------------------------------------------
        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val textLines = extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString(" | ") { it.toString() }
        val tickerText = notification.tickerText?.toString()

        val input = NotificationParseInput(
            sourcePackage = sbn.packageName,
            title = title,
            text = text,
            subText = subText,
            bigText = bigText,
            textLines = textLines,
            tickerText = tickerText,
            postTimeMs = sbn.postTime,
            contentViewTexts = contentViewTexts,
            isOngoing = isOngoing
        )

        scope.launch {
            try {
                // ── Serialize the entire import pipeline ──
                // Prevents TOCTOU race when AiDEX fires two notifications ~960ms apart.
                // The second coroutine blocks here until the first finishes its DB insert,
                // then sees the fresh reading and gets correctly rejected by the gap filter.
                importMutex.withLock {
                DebugTrace.t(DebugCategory.PARSING, "PARSE-IN", "pkg=${input.sourcePackage} cvTexts=${contentViewTexts.size} title=${title.orEmpty()}")
                DebugTrace.v(DebugCategory.PARSING, "PARSE-V-EXTRAS") {
                    "pkg=${input.sourcePackage} " +
                    "title=[${title.orEmpty()}] " +
                    "text=[${text.orEmpty()}] " +
                    "subText=[${subText.orEmpty()}] " +
                    "bigText=[${bigText.orEmpty()}] " +
                    "textLines=[${textLines.orEmpty()}] " +
                    "ticker=[${tickerText.orEmpty()}]"
                }
                DebugTrace.v(DebugCategory.PARSING, "PARSE-V-CV") {
                    "contentViewTexts(${contentViewTexts.size})=$contentViewTexts"
                }
                val sample = parser.parse(input)
                if (sample == null) {
                    repo.log(
                        "D",
                        "Parser",
                        "No parse result for pkg=${input.sourcePackage} cvTexts=${contentViewTexts.joinToString("|")} rawTitle=${title.orEmpty()} rawText=${text.orEmpty()}"
                    )
                    return@withLock
                }

                DebugTrace.t(DebugCategory.PARSING, "PARSE-OUT", "ts=${sample.timestampMs} v=${sample.valueMgdl} dir=${sample.direction} status=${sample.sensorStatus} alert=${sample.alertText}")

                when (val result = importer.import(sample)) {
                    is BgReadingImporter.ImportResult.Inserted -> {
                        // After insert, compute slope-based direction from latest readings
                        // (mirrors xDrip UiBasedCollector: insert → find_slope)
                        // Fetch 5 readings so slope calculator can skip rapid duplicates
                        // that may still exist in the DB from before Plan C was deployed.
                        val latest2 = repo.latestReadings(5)
                        val slopeResult = SlopeDirectionCalculator.calculate(latest2)
                        val effectiveDir = if (slopeResult.isValid && slopeResult.directionName != "NONE") {
                            slopeResult.directionName
                        } else {
                            sample.direction
                        }
                        val broadcastSample = sample.copy(valueMgdl = result.entity.CalibratedValueMgdl, direction = effectiveDir)

                        repo.log(
                            "D",
                            "Importer",
                            "Inserted BG ${result.entity.calculatedValueMgdl} " +
                                "notifDir=${sample.direction} slopeDir=${slopeResult.directionName} " +
                                "from ${result.entity.sourcePackage}"
                        )
                        DebugTrace.v(DebugCategory.DATABASE, "INSERT-DETAIL") {
                            "[DATABASE] BgReadingEntity: " +
                            "timestampMs=${result.entity.timestampMs}, " +
                            "calculatedValueMgdl=${result.entity.calculatedValueMgdl}, " +
                            "CalibratedValueMgdl=${result.entity.CalibratedValueMgdl}, " +
                            "direction=${result.entity.direction}, " +
                            "sourcePackage=${result.entity.sourcePackage}, " +
                            "rawText=${result.entity.rawText}, " +
                            "sensorStatus=${result.entity.sensorStatus}, " +
                            "alertText=${result.entity.alertText}"
                        }
                        XDripBroadcastSender.sendGlucose(applicationContext, broadcastSample)
                        DebugTrace.t(DebugCategory.NOTIFICATION, "BCAST-SENT", "Sent to xDrip NS_EMULATOR dir=$effectiveDir")
                    }
                    is BgReadingImporter.ImportResult.IgnoredDuplicate -> {
                        repo.log("D", "Importer", "Duplicate timestamp=${result.timestampMs}")
                    }
                    is BgReadingImporter.ImportResult.IgnoredTooSoon -> {
                        DebugTrace.v(DebugCategory.PARSING, "IMPORT-SKIP") {
                            "Rapid duplicate skipped: ts=${result.timestampMs} gap=${result.gapMs}ms"
                        }
                    }
                    is BgReadingImporter.ImportResult.InvalidTimestamp -> {
                        repo.log("W", "Importer", "Invalid timestamp=${result.timestampMs}")
                    }
                    is BgReadingImporter.ImportResult.InvalidValue -> {
                        repo.log("W", "Importer", "Invalid value=${result.valueMgdl}")
                    }
                    is BgReadingImporter.ImportResult.StatusOnly -> {
                        repo.log("D", "Status", "StatusOnly status=${result.status} alert=${result.alert}")
                    }
                }
                } // end importMutex.withLock
            } catch (throwable: Throwable) {
                DebugTrace.e(DebugCategory.NOTIFICATION, "NL-EX", "Exception while processing notification", throwable)
                try {
                    repo.log("E", "NotificationListener", "Exception: ${throwable.message}")
                } catch (_: Throwable) {
                    // Intentionally ignore secondary logging failures.
                }
            } finally {
                // Release the WakeLock acquired before coroutine launch.
                try { if (wl.isHeld) wl.release() } catch (_: Exception) {}
            }
        }

        } catch (outerEx: Throwable) {
            // Exception during data extraction (before coroutine launch).
            // WakeLock acquired but coroutine never started — release here.
            DebugTrace.e(DebugCategory.NOTIFICATION, "NL-OUTER-EX", "Exception during notification extraction", outerEx)
            try { if (wl.isHeld) wl.release() } catch (_: Exception) {}
        }
    }

    /**
     * Called whenever a notification is removed.
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        NotificationDebugDumper.dumpRemoved(sbn)
    }
}
