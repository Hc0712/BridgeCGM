package com.north7.bridgecgm.feature.keepalive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.north7.bridgecgm.core.data.Repository

/**
 * Daily cleanup worker for old Room data.
 */
class DatabaseMaintenanceWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    /**
     * Deletes old glucose rows and old debug log rows.
     */
    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        Repository(applicationContext).purgeOldData(
            now - 365L * 24 * 60 * 60 * 1000,
            now - 30L * 24 * 60 * 60 * 1000
        )
        return Result.success()
    }
}
