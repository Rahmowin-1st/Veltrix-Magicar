package com.veltrix.ultron.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.veltrix.ultron.planner.AndroidOwnerPlannerPermissionStore

/**
 * Minimal notification observation foundation with a mandatory owner privacy boundary.
 *
 * Notification content is never logged. HARD DENY is evaluated from package/key metadata
 * before title/text extras are extracted, and buffered snapshots are re-filtered on read so
 * a newly-added deny immediately hides previously retained content from every future consumer.
 */
class UltronNotificationListenerService : NotificationListenerService() {

    data class NotificationSnapshot(
        val key: String,
        val packageName: String,
        val postedAt: Long,
        val title: String?,
        val text: String?
    )

    override fun onCreate() {
        super.onCreate()
        privacyFirewall = NotificationPrivacyFirewall(
            ownerPermissions = AndroidOwnerPlannerPermissionStore(applicationContext),
            ownerPrincipalId = LOCAL_OWNER_PRINCIPAL_ID,
            deviceId = LOCAL_ANDROID_DEVICE_ID
        )
    }

    override fun onDestroy() {
        connected = false
        privacyFirewall = null
        synchronized(recent) { recent.clear() }
        super.onDestroy()
    }

    override fun onListenerConnected() {
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val key = sbn.key?.trim()?.takeIf(String::isNotEmpty) ?: return
        val packageName = sbn.packageName?.trim()?.takeIf(String::isNotEmpty) ?: return
        val firewall = privacyFirewall ?: return

        // Privacy is checked before any user-visible notification payload is read.
        if (!firewall.isVisible(packageName, key)) {
            synchronized(recent) { recent.removeAll { it.key == key } }
            return
        }

        val extras = sbn.notification.extras
        val snapshot = NotificationSnapshot(
            key = key,
            packageName = packageName,
            postedAt = sbn.postTime,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        )

        synchronized(recent) {
            recent.removeAll { it.key == snapshot.key }
            recent.addFirst(snapshot)
            while (recent.size > MAX_RECENT) recent.removeLast()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        synchronized(recent) { recent.removeAll { it.key == key } }
    }

    companion object {
        private const val MAX_RECENT = 40
        private const val LOCAL_OWNER_PRINCIPAL_ID = "owner"
        private const val LOCAL_ANDROID_DEVICE_ID = "android-local"

        @Volatile private var connected: Boolean = false
        @Volatile private var privacyFirewall: NotificationPrivacyFirewall? = null
        private val recent = ArrayDeque<NotificationSnapshot>()

        fun isConnected(): Boolean = connected

        fun snapshot(): List<NotificationSnapshot> {
            val firewall = privacyFirewall ?: return emptyList()
            return synchronized(recent) {
                recent.removeAll { snapshot ->
                    !firewall.isVisible(snapshot.packageName, snapshot.key)
                }
                recent.toList()
            }
        }
    }
}
