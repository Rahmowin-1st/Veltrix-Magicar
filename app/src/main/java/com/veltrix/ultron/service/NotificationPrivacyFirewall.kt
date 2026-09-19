package com.veltrix.ultron.service

import com.veltrix.ultron.planner.OwnerPlannerPermissionStore
import java.security.MessageDigest

/**
 * Canonical notification privacy resource scopes.
 *
 * Package metadata is retained as the parent scope so an owner can deny all
 * notifications from one app. Notification keys are irreversibly hashed before
 * becoming policy metadata, so a specific item can be denied without persisting
 * the raw StatusBarNotification key.
 */
object NotificationPrivacyScopes {
    private const val PREFIX = "notification"
    private const val ITEM = "item"

    fun packageScope(packageName: String): String? {
        val clean = packageName.trim()
        if (clean.isEmpty() || clean.length > 255 || '/' in clean || '\u0000' in clean) return null
        return "$PREFIX/$clean"
    }

    fun itemScope(packageName: String, notificationKey: String): String? {
        val parent = packageScope(packageName) ?: return null
        val cleanKey = notificationKey.trim()
        if (cleanKey.isEmpty() || cleanKey.length > 2_048 || '\u0000' in cleanKey) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(cleanKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "$parent/$ITEM/$digest"
    }
}

/**
 * Local fail-closed boundary between Android notification access and every future
 * AI/search/report consumer.
 *
 * HARD DENY is checked using metadata only, before title/text are extracted from
 * the Notification extras. The same predicate is re-evaluated when snapshots are
 * read, so a newly-added deny immediately hides already-buffered notifications.
 */
class NotificationPrivacyFirewall(
    private val ownerPermissions: OwnerPlannerPermissionStore,
    private val ownerPrincipalId: String,
    private val deviceId: String
) {
    fun isVisible(packageName: String, notificationKey: String): Boolean {
        val packageScope = NotificationPrivacyScopes.packageScope(packageName) ?: return false
        val itemScope = NotificationPrivacyScopes.itemScope(packageName, notificationKey) ?: return false

        if (isDenied(packageScope)) return false
        if (isDenied(itemScope)) return false
        return true
    }

    private fun isDenied(targetScope: String): Boolean = ownerPermissions.isHardDenied(
        ownerPrincipalId = ownerPrincipalId,
        deviceId = deviceId,
        targetScope = targetScope,
        actionScope = OBSERVE_ACTION_SCOPE,
        riskClass = OBSERVE_RISK_CLASS
    )

    companion object {
        const val OBSERVE_ACTION_SCOPE = "observe"
        const val OBSERVE_RISK_CLASS = "low"
    }
}
