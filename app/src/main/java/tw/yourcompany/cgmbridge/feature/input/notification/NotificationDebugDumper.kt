package tw.yourcompany.cgmbridge.feature.input.notification

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import tw.yourcompany.cgmbridge.core.logging.DebugCategory
import tw.yourcompany.cgmbridge.core.logging.DebugTrace

/**
 * Notification dump with two levels:
 *   - **Concise** (always on): one-line summary — negligible cost.
 *   - **Verbose** (controlled by the verbose flag in [DebugTrace]): full metadata + every
 *     extras key/value. Expensive string building is wrapped in [DebugTrace.v]
 *     lambdas so it is completely skipped when verbose is off (release default).
 */
object NotificationDebugDumper {

    // ---- concise (always) ------------------------------------------------

    @Suppress("DEPRECATION")
    fun dumpPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val extras = n.extras
        DebugTrace.t(DebugCategory.NOTIFICATION,
            "NL-DUMP",
            "pkg=${sbn.packageName} id=${sbn.id} ongoing=${sbn.isOngoing} " +
            "title=${extras?.getCharSequence(Notification.EXTRA_TITLE).orStr()} " +
            "text=${extras?.getCharSequence(Notification.EXTRA_TEXT).orStr()} " +
            "hasCV=${n.contentView != null}"
        )
        // verbose detail — lambda body never runs when DebugTrace.verbose == false
        if (DebugTrace.isCategoryEnabled(DebugCategory.NOTIFICATION)) dumpVerbose(sbn, n)
    }

    fun dumpRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        DebugTrace.t(DebugCategory.NOTIFICATION, "NL-REMOVED", "pkg=${sbn.packageName} id=${sbn.id}")
    }

    // ---- verbose (only when enabled) ------------------------------------

    @Suppress("DEPRECATION")
    private fun dumpVerbose(sbn: StatusBarNotification, n: Notification) {
        val extras = n.extras

        DebugTrace.v(DebugCategory.NOTIFICATION, "NL-V-META") {
            "tag=${sbn.tag} key=${sbn.key} groupKey=${sbn.groupKey} " +
            "postTime=${sbn.postTime} isClearable=${sbn.isClearable}"
        }

        DebugTrace.v(DebugCategory.NOTIFICATION, "NL-V-NOTIF") {
            "when=${n.`when`} channelId=${if (android.os.Build.VERSION.SDK_INT >= 26) n.channelId.orEmpty() else ""} " +
            "category=${n.category.orEmpty()} flags=${n.flags} " +
            "number=${n.number} visibility=${n.visibility} " +
            "timeoutAfter=${if (android.os.Build.VERSION.SDK_INT >= 26) n.timeoutAfter else ""}"
        }

        DebugTrace.v(DebugCategory.NOTIFICATION, "NL-V-TEXT") {
            "sub=${extras?.getCharSequence(Notification.EXTRA_SUB_TEXT).orStr()} " +
            "big=${extras?.getCharSequence(Notification.EXTRA_BIG_TEXT).orStr()} " +
            "summary=${extras?.getCharSequence(Notification.EXTRA_SUMMARY_TEXT).orStr()} " +
            "info=${extras?.getCharSequence(Notification.EXTRA_INFO_TEXT).orStr()} " +
            "ticker=${n.tickerText.orStr()}"
        }

        val lines = extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString(" | ") { it.toString() }.orEmpty()
        if (lines.isNotEmpty()) {
            DebugTrace.v(DebugCategory.NOTIFICATION, "NL-V-LINES") { "textLines=$lines" }
        }

        dumpExtras(extras)
    }

    @Suppress("DEPRECATION")
    private fun dumpExtras(extras: Bundle?) {
        if (extras == null) return
        val keys = extras.keySet().sorted()
        DebugTrace.v(DebugCategory.NOTIFICATION, "NL-V-KEYS") { keys.joinToString(",") }
        for (key in keys) {
            val value = extras.get(key)
            DebugTrace.v(DebugCategory.NOTIFICATION, "NL-V-EXTRA") { "$key=${safeStr(value)}" }
        }
    }

    // ---- helpers ---------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun safeStr(value: Any?): String = when (value) {
        null -> "null"
        is CharSequence -> value.toString()
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { safeStr(it) }
        is IntArray -> value.joinToString(prefix = "[", postfix = "]")
        is LongArray -> value.joinToString(prefix = "[", postfix = "]")
        is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
        is DoubleArray -> value.joinToString(prefix = "[", postfix = "]")
        is BooleanArray -> value.joinToString(prefix = "[", postfix = "]")
        is Bundle -> value.keySet().sorted().joinToString(prefix = "Bundle{", postfix = "}") {
            "$it=${safeStr(value.get(it))}"
        }
        else -> value.toString()
    }

    private fun CharSequence?.orStr(): String = this?.toString() ?: ""
}
