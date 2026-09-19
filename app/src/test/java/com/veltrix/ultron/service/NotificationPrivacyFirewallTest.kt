package com.veltrix.ultron.service

import com.veltrix.ultron.planner.InMemoryOwnerPlannerPermissionStore
import com.veltrix.ultron.planner.OwnerPlannerPermission
import com.veltrix.ultron.planner.OwnerPlannerPermissionDecision
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPrivacyFirewallTest {
    private val ownerId = "owner"
    private val deviceId = "android-local"

    @Test
    fun packageHardDenyHidesEveryNotificationFromThatApp() {
        val store = InMemoryOwnerPlannerPermissionStore().apply {
            deny(NotificationPrivacyScopes.packageScope("com.bank.app")!!)
        }
        val firewall = NotificationPrivacyFirewall(store, ownerId, deviceId)

        assertFalse(firewall.isVisible("com.bank.app", "bank-key-1"))
        assertFalse(firewall.isVisible("com.bank.app", "bank-key-2"))
        assertTrue(firewall.isVisible("org.telegram.messenger", "chat-key"))
    }

    @Test
    fun oneNotificationDenyDoesNotHideSiblingNotifications() {
        val first = NotificationPrivacyScopes.itemScope("org.telegram.messenger", "item-a")!!
        val second = NotificationPrivacyScopes.itemScope("org.telegram.messenger", "item-b")!!
        assertNotEquals(first, second)

        val store = InMemoryOwnerPlannerPermissionStore().apply { deny(first) }
        val firewall = NotificationPrivacyFirewall(store, ownerId, deviceId)

        assertFalse(firewall.isVisible("org.telegram.messenger", "item-a"))
        assertTrue(firewall.isVisible("org.telegram.messenger", "item-b"))
    }

    @Test
    fun notificationKeyIsHashedBeforeItBecomesPolicyMetadata() {
        val rawKey = "0|com.bank.app|42|otp-secret-shaped-metadata|10001"
        val scope = NotificationPrivacyScopes.itemScope("com.bank.app", rawKey)!!

        assertTrue(scope.startsWith("notification/com.bank.app/item/"))
        assertFalse(scope.contains(rawKey))
        assertFalse(scope.contains("otp-secret-shaped-metadata"))
    }

    @Test
    fun malformedMetadataFailsClosed() {
        val firewall = NotificationPrivacyFirewall(
            InMemoryOwnerPlannerPermissionStore(),
            ownerId,
            deviceId
        )

        assertFalse(firewall.isVisible("", "key"))
        assertFalse(firewall.isVisible("com.example/bad", "key"))
        assertFalse(firewall.isVisible("com.example", ""))
    }

    @Test
    fun explicitObserveDenyIsEnoughToHideNotification() {
        val target = NotificationPrivacyScopes.packageScope("com.mail.app")!!
        val store = InMemoryOwnerPlannerPermissionStore().apply {
            put(
                OwnerPlannerPermission(
                    principalId = ownerId,
                    deviceId = deviceId,
                    targetScope = target,
                    actionScope = NotificationPrivacyFirewall.OBSERVE_ACTION_SCOPE,
                    riskClass = NotificationPrivacyFirewall.OBSERVE_RISK_CLASS,
                    decision = OwnerPlannerPermissionDecision.DENY
                )
            )
        }

        assertFalse(NotificationPrivacyFirewall(store, ownerId, deviceId).isVisible("com.mail.app", "mail-1"))
    }

    private fun InMemoryOwnerPlannerPermissionStore.deny(targetScope: String) {
        put(
            OwnerPlannerPermission(
                principalId = ownerId,
                deviceId = deviceId,
                targetScope = targetScope,
                actionScope = "*",
                riskClass = "*",
                decision = OwnerPlannerPermissionDecision.DENY
            )
        )
    }
}
