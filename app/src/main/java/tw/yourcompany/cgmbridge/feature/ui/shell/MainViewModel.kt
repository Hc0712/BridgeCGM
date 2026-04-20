package tw.yourcompany.cgmbridge.feature.ui.shell

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.switchMap
import tw.yourcompany.cgmbridge.core.data.Repository
import tw.yourcompany.cgmbridge.core.db.BgReadingEntity
import java.util.Calendar
import java.util.GregorianCalendar

/**
 * ViewModel — supports date selection (7 days back) + time window (3/6/12/24h).
 *
 * ALL dates use calendar-day boundaries:
 *   Today  → today 00:00 … now
 *   Past   → that day 00:00 … next day 00:00
 *
 * Time window (3/6/12/24h) further narrows within the day.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repository(app)

    /** Selected time window in hours (3, 6, 12, 24). */
    private val hours = MutableLiveData(24)

    /** Days ago: 0 = today, 1 = yesterday, … 6 = 6 days ago. */
    private val dateOffset = MutableLiveData(0)

    /** Combined trigger — fires whenever hours OR dateOffset changes. */
    private val selection = MediatorLiveData<Pair<Int, Int>>().apply {
        fun update() {
            value = Pair(dateOffset.value ?: 0, hours.value ?: 24)
        }
        addSource(hours) { update() }
        addSource(dateOffset) { update() }
    }

    /** Detail chart data — filtered by date + time window. */
    val latestReadings: LiveData<List<BgReadingEntity>> = selection.switchMap { (dayOff, h) ->
        val (from, to) = computeRange(dayOff, h)
        repo.readingsInRange(from, to).asLiveData()
    }

    /** Overview chart data — full day for selected date. */
    val overviewReadings: LiveData<List<BgReadingEntity>> = dateOffset.switchMap { dayOff ->
        val (from, to) = computeDayRange(dayOff)
        repo.readingsInRange(from, to).asLiveData()
    }

    /** Live stream of latest debug log rows. */
    val latestLogs = repo.latestLogs(50).asLiveData()

    // ─── Time window selection ──────────────────────────────────────────

    fun observe3h()  { hours.value = 3 }
    fun observe6h()  { hours.value = 6 }
    fun observe12h() { hours.value = 12 }
    fun observe24h() { hours.value = 24 }

    fun selectDate(offset: Int) { dateOffset.value = offset.coerceIn(0, 6) }

    fun currentHours(): Int = hours.value ?: 24
    fun currentDateOffset(): Int = dateOffset.value ?: 0

    // ─── Public helpers for chart axis ──────────────────────────────────

    /** Returns 00:00 (midnight) of the selected day in ms. */
    fun dayStartMs(): Long = dayStart(dateOffset.value ?: 0)

    fun isToday(): Boolean = (dateOffset.value ?: 0) == 0

    // ─── Range computation ──────────────────────────────────────────────

    /** 00:00 of the day that is `dayOff` days ago. */
    private fun dayStart(dayOff: Int): Long {
        return GregorianCalendar().apply {
            add(Calendar.DAY_OF_YEAR, -dayOff)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /**
     * Full-day data range.
     *
     * Always uses end-of-day (midnight tomorrow) as the upper bound — even for
     * today.  This is critical because Room Flow captures query parameters once
     * and re-runs with the **same** values whenever the table changes.  Using
     * `System.currentTimeMillis()` made the upper bound stale instantly, so
     * every reading arriving after the ViewModel was created fell outside the
     * query range and the chart never updated.
     *
     * Reference: xDrip+ rebuilds its graph from scratch on every new-data
     * broadcast with a fresh "now" timestamp, so it never hits this problem.
     * Our Room/Flow architecture needs a stable, future-proof upper bound
     * instead.
     */
    private fun computeDayRange(dayOff: Int): Pair<Long, Long> {
        val start = dayStart(dayOff)
        val end = start + 24L * 3600_000L   // midnight → midnight (covers the full day)
        return Pair(start, end)
    }

    /**
     * Detail data range = day range narrowed by time window.
     * 24h → full day range.
     * Today + smaller window → (now − h hours) … end-of-day.
     *   The open right edge ensures newly arriving readings always appear.
     * Past  + smaller window → last h hours of that day (fixed window).
     */
    private fun computeRange(dayOff: Int, h: Int): Pair<Long, Long> {
        val (dayStart, dayEnd) = computeDayRange(dayOff)
        if (h >= 24) return Pair(dayStart, dayEnd)

        if (dayOff == 0) {
            // Today: slide the left edge based on "now" but keep the right edge
            // at end-of-day so Room Flow always includes new readings.
            val now = System.currentTimeMillis()
            val windowStart = maxOf(dayStart, now - h * 3600_000L)
            return Pair(windowStart, dayEnd)
        }

        // Past day: last h hours of the day.
        val windowStart = dayEnd - h * 3600_000L
        return Pair(maxOf(dayStart, windowStart), dayEnd)
    }
}
