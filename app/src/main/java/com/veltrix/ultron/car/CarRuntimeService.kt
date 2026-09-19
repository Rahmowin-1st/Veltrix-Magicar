package com.veltrix.ultron.car

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
import androidx.core.content.ContextCompat
import com.veltrix.ultron.MainActivity
import com.veltrix.ultron.R

class CarRuntimeService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        CarSessionRuntime.noteSystemAwake()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CarSessionRuntime.noteSystemAwake()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Veltrix Magicar", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the local wake/session shell ready while the head unit is on"
                setShowBadge(false)
            }
        )
    }

    private fun notification(): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ultron_quick_tile)
            .setContentTitle("Veltrix Magicar")
            .setContentText("Ready — idle until you wake it")
            .setContentIntent(pending)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "veltrix_magicar_runtime"
        private const val NOTIFICATION_ID = 4301

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CarRuntimeService::class.java))
        }
    }
}
