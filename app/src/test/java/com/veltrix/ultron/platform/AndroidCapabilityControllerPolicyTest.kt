package com.veltrix.ultron.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCapabilityControllerPolicyTest {
    @Test
    fun optionalNotificationListenerDoesNotBlockMaxApprovedReadiness() {
        val status = readyStatus(notificationAccessEnabled = false)

        assertTrue(status.maxApprovedReady)
    }

    @Test
    fun requiredAccessibilityStillBlocksMaxApprovedReadiness() {
        val status = readyStatus(accessibilityEnabled = false)

        assertFalse(status.maxApprovedReady)
    }

    @Test
    fun availableAssistantRoleMustStillBeHeld() {
        val status = readyStatus(assistantRoleAvailable = true, assistantRoleHeld = false)

        assertFalse(status.maxApprovedReady)
    }

    private fun readyStatus(
        assistantRoleAvailable: Boolean = false,
        assistantRoleHeld: Boolean = false,
        overlayAllowed: Boolean = true,
        accessibilityEnabled: Boolean = true,
        notificationAccessEnabled: Boolean = false,
        notificationPermissionGranted: Boolean = true,
        microphonePermissionGranted: Boolean = true
    ) = AndroidCapabilityController.Status(
        assistantRoleAvailable = assistantRoleAvailable,
        assistantRoleHeld = assistantRoleHeld,
        assistantServiceActive = false,
        overlayAllowed = overlayAllowed,
        accessibilityEnabled = accessibilityEnabled,
        executorConnected = accessibilityEnabled,
        notificationAccessEnabled = notificationAccessEnabled,
        notificationPermissionGranted = notificationPermissionGranted,
        microphonePermissionGranted = microphonePermissionGranted,
        screenCaptureState = ScreenCaptureState.IDLE,
        freshScreenFrameReady = false
    )
}
