package tw.yourcompany.cgmbridge.core.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import tw.yourcompany.cgmbridge.core.db.AppDatabase
import tw.yourcompany.cgmbridge.core.db.BgReadingEntity
import tw.yourcompany.cgmbridge.core.db.EventLogEntity

/**
 * Repository layer that coordinates access to Room.
 */
class Repository(context: Context) {

    private val db = AppDatabase.get(context)
    private val bgDao = db.bgReadingDao()
    private val eventDao = db.eventLogDao()

    /** Returns a stream of recent glucose readings. */
    fun latestReadingsSince(fromMs: Long, limit: Int): Flow<List<BgReadingEntity>> =
        bgDao.latestSince(fromMs, limit)

    /** Returns a stream of readings within a specific time range [fromMs, toMs). */
    fun readingsInRange(fromMs: Long, toMs: Long, limit: Int = 2000): Flow<List<BgReadingEntity>> =
        bgDao.readingsInRange(fromMs, toMs, limit)

    /** Returns the latest N readings synchronously (for slope calculation). */
    suspend fun latestReadings(n: Int): List<BgReadingEntity> = bgDao.latestN(n)

    /** Returns a stream of recent debug log rows. */
    fun latestLogs(limit: Int): Flow<List<EventLogEntity>> = eventDao.latest(limit)

    /** Inserts one glucose reading row. */
    suspend fun insertReading(entity: BgReadingEntity): Long = bgDao.insertIgnore(entity)

    /** Inserts one event log row. */
    suspend fun log(level: String, tag: String, message: String) {
        eventDao.insert(
            EventLogEntity(
                timestampMs = System.currentTimeMillis(),
                level = level,
                tag = tag,
                message = message
            )
        )
    }

    /** Purges old database rows. */
    suspend fun purgeOldData(bgOlderThanMs: Long, logOlderThanMs: Long) {
        bgDao.deleteOlderThan(bgOlderThanMs)
        eventDao.deleteOlderThan(logOlderThanMs)
    }
}
