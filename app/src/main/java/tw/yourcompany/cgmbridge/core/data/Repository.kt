package tw.yourcompany.cgmbridge.core.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import tw.yourcompany.cgmbridge.core.db.AppDatabase
import tw.yourcompany.cgmbridge.core.db.BgReadingEntity
import tw.yourcompany.cgmbridge.core.db.CgmSourceEntity
import tw.yourcompany.cgmbridge.core.db.EventLogEntity

/**
 * Clean multi-source repository.
 *
 * This repository now assumes the production Version 1 schema from day one.
 * There is no legacy single-source compatibility layer inside the database access code.
 */
class Repository(context: Context) {
    private val db = AppDatabase.get(context)
    private val bgDao = db.bgReadingDao()
    private val sourceDao = db.cgmSourceDao()
    private val eventDao = db.eventLogDao()

    /** Returns a stream of recent glucose readings. */
    fun latestReadingsSince(fromMs: Long, limit: Int): Flow<List<BgReadingEntity>> =
        bgDao.latestSince(fromMs, limit)

    /** Returns a stream of readings within a specific time range [fromMs, toMs). */
    fun readingsInRange(fromMs: Long, toMs: Long, limit: Int = 2000): Flow<List<BgReadingEntity>> =
        bgDao.readingsInRange(fromMs, toMs, limit)

    suspend fun latestReadings(limit: Int): List<BgReadingEntity> = bgDao.latestN(limit)

    suspend fun latestReadingForSource(sourceId: String): BgReadingEntity? =
        bgDao.latestForSource(sourceId)

    fun readingsInRangeForSource(sourceId: String, fromMs: Long, toMs: Long, limit: Int = 2000): Flow<List<BgReadingEntity>> =
        bgDao.readingsInRangeForSource(sourceId, fromMs, toMs, limit)

    suspend fun readingsInRangeForSourcesOnce(sourceIds: List<String>, fromMs: Long, toMs: Long, limit: Int = 4000): List<BgReadingEntity> =
        if (sourceIds.isEmpty()) emptyList() else bgDao.readingsInRangeForSourcesOnce(sourceIds, fromMs, toMs, limit)

    suspend fun insertReading(entity: BgReadingEntity): Long = bgDao.insertIgnore(entity)

    suspend fun upsertSource(entity: CgmSourceEntity) = sourceDao.upsert(entity)

    suspend fun touchSource(sourceId: String, timestampMs: Long) = sourceDao.touch(sourceId, timestampMs)

    fun allSources(): Flow<List<CgmSourceEntity>> = sourceDao.all()

    suspend fun visibleSourcesOnce(): List<CgmSourceEntity> = sourceDao.visibleSourcesOnce()

    fun latestLogs(limit: Int): Flow<List<EventLogEntity>> = eventDao.latest(limit)

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
