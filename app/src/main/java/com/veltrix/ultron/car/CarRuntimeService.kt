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
import com.veltrix.ultron.runtime.UltronCommandRuntime
import com.veltrix.ultron.voice.MagicarAssistantOrchestrator

class CarRuntimeService : Service() {
    private lateinit var assistant: MagicarAssistantOrchestrator

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        UltronCommandRuntime.initialize(applicationContext)
        assistant = MagicarAssistantOrchestrator(applicationContext)
        CarSessionRuntime.noteSystemAwake()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CarSessionRuntime.noteSystemAwake()
        when (intent?.action) {
            ACTION_WAKE -> assistant.wake(
                intent.getStringExtra(EXTRA_WAKE_SOURCE)
                    ?.let { runCatching { CarWakeSource.valueOf(it) }.getOrNull() }
                    ?: CarWakeSource.ASSISTANT_INVOCATION
            )
            ACTION_STOP_ASSISTANT -> assistant.stop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (::assistant.isInitialized) assistant.destroy()
        super.onDestroy()
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
        private const val ACTION_WAKE = "com.veltrix.magicar.runtime.WAKE"
        private const val ACTION_STOP_ASSISTANT = "com.veltrix.magicar.runtime.STOP_ASSISTANT"
        private const val EXTRA_WAKE_SOURCE = "wake_source"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CarRuntimeService::class.java))
        }

        fun wake(
            context: Context,
            source: CarWakeSource = CarWakeSource.ASSISTANT_INVOCATION
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CarRuntimeService::class.java)
                    .setAction(ACTION_WAKE)
                    .putExtra(EXTRA_WAKE_SOURCE, source.name)
            )
        }

        fun stopAssistant(context: Context) {
            context.startService(
                Intent(context, CarRuntimeService::class.java)
                    .setAction(ACTION_STOP_ASSISTANT)
            )
        }
    }
}
