package com.north7.bridgecgm.feature.ui.shell

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.north7.bridgecgm.core.db.EventLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter used to display the latest debug log rows.
 */
class DebugLogAdapter : RecyclerView.Adapter<DebugLogAdapter.VH>() {

    /** In-memory list of currently displayed items. */
    private val items = mutableListOf<EventLogEntity>()

    /** Timestamp formatter for log rows. */
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    /**
     * Replaces the current list contents.
     */
    fun submit(list: List<EventLogEntity>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** Creates a very small one-line text row. */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
        return VH(tv)
    }

    /** Binds one event log row. */
    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        holder.tv.text = "${fmt.format(Date(e.timestampMs))} [${e.level}] ${e.tag}: ${e.message}"
    }

    /** Returns the row count. */
    override fun getItemCount(): Int = items.size

    /** Simple holder that wraps a single TextView. */
    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
}
