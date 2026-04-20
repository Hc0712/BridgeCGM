package tw.yourcompany.cgmbridge.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * bridge_v3 fix:
 * - Version bumped to 2.
 * - fallbackToDestructiveMigration enabled.
 * This intentionally clears old incompatible debug databases so the app will not crash.
 */
@Database(entities = [BgReadingEntity::class, EventLogEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bgReadingDao(): BgReadingDao
    abstract fun eventLogDao(): EventLogDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "cgm_bridge.db")
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
