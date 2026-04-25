package com.north7.bridgecgm.feature.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.north7.bridgecgm.R
import com.north7.bridgecgm.feature.ui.shell.MainActivity
import com.north7.bridgecgm.core.logging.DebugCategory
import com.north7.bridgecgm.core.logging.DebugTrace

/**
 * Lightweight foreground service.
 *
 * Why we keep it:
 * - NotificationListenerService is managed by the OS and usually stays active.
 * - However, some OEMs / aggressive power managers can still disrupt it.
 * - A minimal foreground service with an ongoing notification is an
 *   Android-supported pattern for long-running user-visible tasks.
 *
 * We keep CPU usage near-zero: no loops, no polling.
 */
class CgmGuardianForegroundService : Service() {

    /** Starts the foreground notification immediately after service creation. */
    override fun onCreate() {
        super.onCreate()
        DebugTrace.t(DebugCategory.KEEPALIVE, "FGS-CREATE", "Guardian service created")
        startAsForeground()
    }

    /** Called whenever the service is started. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugTrace.t(DebugCategory.KEEPALIVE, "FGS-START", "onStartCommand")
        return START_STICKY
    }

    /** This service does not provide binding. */
    override fun onBind(intent: Intent?): IBinder? = null

    /** Creates the notification channel and enters foreground mode. */
    private fun startAsForeground() {
        val channelId = "cgm_bridge_guardian"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(channelId, "CgmBridge", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.guardian_notification_title))
            .setContentText(getString(R.string.guardian_notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }
}
