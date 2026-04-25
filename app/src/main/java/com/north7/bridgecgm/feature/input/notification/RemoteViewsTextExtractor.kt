package com.north7.bridgecgm.feature.input.notification

import android.app.Notification
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import android.widget.TextView
import com.north7.bridgecgm.core.logging.DebugCategory
import com.north7.bridgecgm.core.logging.DebugTrace

/**
 * Extracts visible text from a Notification's contentView (RemoteViews).
 * Mirrors xDrip+ UiBasedCollector.processRemote() + getTextViews().
 */
object RemoteViewsTextExtractor {

    @Suppress("DEPRECATION")
    fun extractTexts(context: Context, notification: Notification): List<String> {
        val contentView: RemoteViews = notification.contentView ?: return emptyList()
        return try {
            val root = contentView.apply(context, null).rootView
            val tvs = mutableListOf<TextView>()
            collectTextViews(tvs, root)
            DebugTrace.v(
                DebugCategory.NOTIFICATION,
                "RV-EXTRACT"
            ) { "Found ${tvs.size} text views in contentView" }
            tvs.mapNotNull { tv ->
                val text = tv.text?.toString()
                DebugTrace.v(
                    DebugCategory.NOTIFICATION,
                    "RV-TV"
                ) {
                    "text=>${text.orEmpty()}< desc=>${tv.contentDescription?.toString().orEmpty()}<"
                }
                text?.takeIf(String::isNotBlank)
            }
        } catch (e: Exception) {
            DebugTrace.e(
                DebugCategory.NOTIFICATION,
                "RV-ERR",
                "contentView inflate failed: ${e.message}"
            )
            emptyList()
        }
    }

    private fun collectTextViews(out: MutableList<TextView>, view: View) {
        if (view.visibility != View.VISIBLE) return
        when (view) {
            is TextView -> out.add(view)
            is ViewGroup -> (0 until view.childCount).forEach { collectTextViews(out, view.getChildAt(it)) }
        }
    }
}
