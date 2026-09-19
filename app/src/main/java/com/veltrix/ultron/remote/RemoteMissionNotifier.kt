package com.veltrix.ultron.remote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.veltrix.ultron.MainActivity
import com.veltrix.ultron.agents.AgentTaskStatus
import com.veltrix.ultron.agents.TaskState

/**
 * Privacy-safe user alerting for remote missions that are blocked on explicit
 * owner/user approval. Notification content deliberately omits the objective.
 */
internal class RemoteMissionNotifier(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)
    private val postedTaskIds = linkedSetOf<String>()

    init {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Remote mission approvals",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when a remote Veltrix mission needs your approval"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    @Synchronized
    fun reconcile(tasks: List<AgentTaskStatus>) {
        val waiting = tasks.filter { it.state == TaskState.WAITING_FOR_USER }
        val waitingIds = waiting.mapTo(linkedSetOf()) { it.taskId }

        val stale = postedTaskIds.filterNot(waitingIds::contains)
        stale.forEach { taskId ->
            manager.cancel(notificationId(taskId))
            postedTaskIds.remove(taskId)
        }

        if (!notificationsAllowed()) return

        waiting.forEach { task ->
            if (postedTaskIds.add(task.taskId)) {
                manager.notify(notificationId(task.taskId), buildNotification(task))
            }
        }
    }

    @Synchronized
    fun clearAll() {
        postedTaskIds.forEach { taskId -> manager.cancel(notificationId(taskId)) }
        postedTaskIds.clear()
    }

    private fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun buildNotification(task: AgentTaskStatus): Notification {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_INITIAL_PAGE, MainActivity.PAGE_MISSIONS)
            putExtra(MainActivity.EXTRA_REMOTE_TASK_ID, task.taskId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            notificationId(task.taskId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Veltrix approval required")
            .setContentText("Open Missions to review the requested action.")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun notificationId(taskId: String): Int =
        NOTIFICATION_BASE + (taskId.hashCode() and 0x0FFFFFFF)

    private companion object {
        const val CHANNEL_ID = "veltrix_remote_approvals"
        const val NOTIFICATION_BASE = 10_000
    }
}
