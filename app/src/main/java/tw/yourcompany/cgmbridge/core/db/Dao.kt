package tw.yourcompany.cgmbridge.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BgReadingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: BgReadingEntity): Long

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
