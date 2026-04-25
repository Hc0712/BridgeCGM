package com.north7.bridgecgm.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for source registry operations.
 *
 * This clean Version 1 DAO is multi-source-first. There is no compatibility layer for
 * an older schema. Every source channel is stored in [cgm_source] from the beginning.
 */
@Dao
interface CgmSourceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CgmSourceEntity)

    @Query("SELECT * FROM cgm_source WHERE sourceId = :sourceId LIMIT 1")
    suspend fun findById(sourceId: String): CgmSourceEntity?

    @Query("SELECT * FROM cgm_source ORDER BY vendorName ASC, transportType ASC, displayName ASC")
    fun all(): Flow<List<CgmSourceEntity>>

    @Query("SELECT * FROM cgm_source WHERE visibleOnMainGraph = 1 AND enabled = 1 ORDER BY vendorName ASC, transportType ASC, displayName ASC")
    suspend fun visibleSourcesOnce(): List<CgmSourceEntity>

    @Query("UPDATE cgm_source SET lastSeenAtMs = :timestampMs, updatedAtMs = :timestampMs WHERE sourceId = :sourceId")
    suspend fun touch(sourceId: String, timestampMs: Long)
}

/**
 * DAO for glucose rows.
 *
 * Design notes:
 * - global range methods remain useful for the main graph
 * - source-aware methods are used by the mini graph, primary-source evaluation,
 *   and per-source deduplication
 */
@Dao
interface BgReadingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: BgReadingEntity): Long

    /**
     * Legacy query kept so older code can still request a recent global timeline.
     * New production code should prefer source-aware methods below.
     */
    @Query("SELECT * FROM bg_reading WHERE timestampMs >= :fromMs ORDER BY timestampMs DESC LIMIT :limit")
    fun latestSince(fromMs: Long, limit: Int): Flow<List<BgReadingEntity>>

    /** Synchronous query to get latest N readings (for slope calculation). */
    @Query("SELECT * FROM bg_reading ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun latestN(limit: Int): List<BgReadingEntity>

    

    /** One-shot query: latest N readings (for export / diagnostics). */
    @Query("SELECT * FROM bg_reading ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun latestOnce(limit: Int): List<BgReadingEntity>
/** Range query for specific date selection (fromMs inclusive, toMs exclusive). */
    @Query("SELECT * FROM bg_reading WHERE timestampMs >= :fromMs AND timestampMs < :toMs ORDER BY timestampMs DESC LIMIT :limit")
    fun readingsInRange(fromMs: Long, toMs: Long, limit: Int): Flow<List<BgReadingEntity>>

    @Query("SELECT * FROM bg_reading WHERE sourceId = :sourceId ORDER BY timestampMs DESC LIMIT 1")
    suspend fun latestForSource(sourceId: String): BgReadingEntity?

    @Query("SELECT * FROM bg_reading WHERE sourceId = :sourceId AND timestampMs >= :fromMs AND timestampMs < :toMs ORDER BY timestampMs DESC LIMIT :limit")
    fun readingsInRangeForSource(sourceId: String, fromMs: Long, toMs: Long, limit: Int): Flow<List<BgReadingEntity>>

    @Query("SELECT * FROM bg_reading WHERE sourceId IN (:sourceIds) AND timestampMs >= :fromMs AND timestampMs < :toMs ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun readingsInRangeForSourcesOnce(sourceIds: List<String>, fromMs: Long, toMs: Long, limit: Int): List<BgReadingEntity>

    @Query("DELETE FROM bg_reading WHERE timestampMs < :olderThanMs")
    suspend fun deleteOlderThan(olderThanMs: Long)
}

@Dao
interface EventLogDao {
    @Insert
    suspend fun insert(entity: EventLogEntity)

    @Query("SELECT * FROM event_log ORDER BY timestampMs DESC LIMIT :limit")
    fun latest(limit: Int): Flow<List<EventLogEntity>>

    

    /** One-shot query: latest N log rows (for export / diagnostics). */
    @Query("SELECT * FROM event_log ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun latestOnce(limit: Int): List<EventLogEntity>

    @Query("DELETE FROM event_log WHERE timestampMs < :olderThanMs")
    suspend fun deleteOlderThan(olderThanMs: Long)
}
