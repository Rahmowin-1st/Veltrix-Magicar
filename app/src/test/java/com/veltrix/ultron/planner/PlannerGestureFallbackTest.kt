package com.veltrix.ultron.planner

import com.veltrix.ultron.devices.ControlProfile
import com.veltrix.ultron.devices.DeviceCapability
import com.veltrix.ultron.devices.DeviceDescriptor
import com.veltrix.ultron.devices.DeviceKind
import com.veltrix.ultron.devices.DevicePlatform
import com.veltrix.ultron.devices.DevicePresence
import com.veltrix.ultron.devices.UniversalAction
import com.veltrix.ultron.devices.UniversalActionType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerGestureFallbackTest {
    @Test
    fun coordinateTapRequiresFreshUserConsentedVisionCapability() {
        val withoutVision = ActionGraphValidator().validate(tapGraph(), device(withVision = false))
        assertFalse(withoutVision.valid)
        assertTrue(withoutVision.issues.any { it.code == "TAP_REQUIRES_FRESH_VISION" })

        val withVision = ActionGraphValidator().validate(tapGraph(), device(withVision = true))
        assertTrue(withVision.valid)
    }

    @Test
    fun coordinateTapCannotUseActionAcceptedAsProofOfDone() {
        val result = ActionGraphValidator().validate(
            tapGraph(VerificationRule(VerificationMode.ACTION_ACCEPTED, description = "Gesture accepted")),
            device(withVision = true)
        )
        assertFalse(result.valid)
        assertTrue(result.issues.any { it.code == "TAP_VERIFICATION_TOO_WEAK" })
    }

    private fun tapGraph(
        verification: VerificationRule = VerificationRule(
            VerificationMode.CONTENT_CHANGED,
            description = "UI changed after conservative gesture"
        )
    ) = ActionGraph(
        objective = "Tap a vision-located control",
        narration = "Use conservative coordinate fallback",
        nodes = listOf(
            ActionGraphNode(
                id = "tap",
                description = "Tap the vision-located control",
                action = UniversalAction(UniversalActionType.TAP, x = 100f, y = 200f),
                requiredCapability = DeviceCapability.UI_GESTURE,
                targetScope = "com.example",
                verification = verification
            )
        )
    )

    private fun device(withVision: Boolean): DeviceDescriptor {
        val available = setOf(DeviceCapability.UI_GESTURE, DeviceCapability.SCREEN_CAPTURE)
        val granted = if (withVision) available else setOf(DeviceCapability.UI_GESTURE)
        return DeviceDescriptor(
            id = "phone",
            ownerPrincipalId = "owner",
            kind = DeviceKind.PHONE,
            platform = DevicePlatform.ANDROID,
            displayName = "Phone",
            availableCapabilities = available,
            grantedCapabilities = granted,
            controlProfile = ControlProfile.MAX_APPROVED,
            presence = DevicePresence.ONLINE
        )
    }
}
