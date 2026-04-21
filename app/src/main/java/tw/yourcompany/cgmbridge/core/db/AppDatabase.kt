package tw.yourcompany.cgmbridge.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Clean Version 1 Room database for the multi-source-first project.
 *
 * Important simplification:
 * - this project is treated as brand new
 * - we intentionally do not preserve or migrate old single-source rows
 * - therefore the database starts directly at version 1 with the final schema
 *
 * This makes the database layer smaller, easier to review, and easier to maintain.
 */
@Database(
    entities = [CgmSourceEntity::class, BgReadingEntity::class, EventLogEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cgmSourceDao(): CgmSourceDao
    abstract fun bgReadingDao(): BgReadingDao
    abstract fun eventLogDao(): EventLogDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        private const val DB_NAME = "cgm_bridge.db"

        /**
         * Returns the singleton Room instance.
         *
         * Because this is now a clean Version 1 schema, there is no migration block and
         * no legacy table-copy logic. The project starts from the final multi-source shape.
         */
        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                ).build().also { INSTANCE = it }
            }
        }
    }
}
