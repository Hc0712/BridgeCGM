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
import tw.yourcompany.cgmbridge.core.db.CgmSourceEntity
import java.util.Calendar
import java.util.GregorianCalendar

/**
 * ViewModel for the main graph screen.
 *
 * Besides the existing date-window queries, this file now also exposes the source registry so the
 * activity can render a true multi-source main graph and a primary-only mini graph.
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

    /** Overview / mini-graph backing data — full day for the selected date. */
    val overviewReadings: LiveData<List<BgReadingEntity>> = dateOffset.switchMap { dayOff ->
        val (from, to) = computeDayRange(dayOff)
        repo.readingsInRange(from, to).asLiveData()
    }

    /**
     * Live stream of all registered sources.
     *
     * The activity uses this to:
     * - look up stable source colors,
     * - know which source is visible/enabled,
     * - filter the mini graph to the selected primary source.
     */
    val allSources: LiveData<List<CgmSourceEntity>> = repo.allSources().asLiveData()

    /** Live stream of latest debug log rows. */
    val latestLogs = repo.latestLogs(50).asLiveData()

    fun observe3h() { hours.value = 3 }
    fun observe6h() { hours.value = 6 }
    fun observe12h() { hours.value = 12 }
    fun observe24h() { hours.value = 24 }

    fun selectDate(offset: Int) { dateOffset.value = offset.coerceIn(0, 6) }

    fun currentHours(): Int = hours.value ?: 24
    fun currentDateOffset(): Int = dateOffset.value ?: 0

    /** Returns 00:00 (midnight) of the selected day in ms. */
    fun dayStartMs(): Long = dayStart(dateOffset.value ?: 0)

    fun isToday(): Boolean = (dateOffset.value ?: 0) == 0

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
     * The upper bound always uses end-of-day instead of `System.currentTimeMillis()` so Room Flow
     * queries keep matching new rows that arrive later.
     */
    private fun computeDayRange(dayOff: Int): Pair<Long, Long> {
        val start = dayStart(dayOff)
        val end = start + 24L * 3600_000L
        return Pair(start, end)
    }

    /**
     * Detail data range = day range narrowed by time window.
     *
     * Today keeps an open right edge at end-of-day so newly arriving readings are still inside the
     * same observed Room query.
     */
    private fun computeRange(dayOff: Int, h: Int): Pair<Long, Long> {
        val (dayStart, dayEnd) = computeDayRange(dayOff)
        if (h >= 24) return Pair(dayStart, dayEnd)

        if (dayOff == 0) {
            val now = System.currentTimeMillis()
            val windowStart = maxOf(dayStart, now - h * 3600_000L)
            return Pair(windowStart, dayEnd)
        }

        val windowStart = dayEnd - h * 3600_000L
        return Pair(maxOf(dayStart, windowStart), dayEnd)
    }
}
