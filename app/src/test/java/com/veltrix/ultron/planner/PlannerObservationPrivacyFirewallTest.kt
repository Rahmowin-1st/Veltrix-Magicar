package com.veltrix.ultron.planner

import com.veltrix.ultron.agents.Principal
import com.veltrix.ultron.agents.PrincipalKind
import com.veltrix.ultron.devices.ControlProfile
import com.veltrix.ultron.devices.DeviceCapability
import com.veltrix.ultron.devices.DeviceDescriptor
import com.veltrix.ultron.devices.DeviceKind
import com.veltrix.ultron.devices.DeviceObservation
import com.veltrix.ultron.devices.DevicePlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerObservationPrivacyFirewallTest {
    private val owner = Principal("owner", PrincipalKind.OWNER, "Owner")
    private val device = DeviceDescriptor(
        id = "phone-a",
        ownerPrincipalId = owner.id,
        kind = DeviceKind.PHONE,
        platform = DevicePlatform.ANDROID,
        displayName = "Phone",
        availableCapabilities = setOf(DeviceCapability.SCREEN_OBSERVE, DeviceCapability.SCREEN_CAPTURE),
        grantedCapabilities = setOf(DeviceCapability.SCREEN_OBSERVE, DeviceCapability.SCREEN_CAPTURE),
        controlProfile = ControlProfile.MAX_APPROVED
    )

    @Test
    fun hardDeniedForegroundIsRemovedBeforeModelContext() {
        val store = InMemoryOwnerPlannerPermissionStore()
        assertTrue(
            PlannerPolicyGate(ownerPermissions = store)
                .rememberOwnerHardDeny(owner, device, "com.bank.app")
        )
        val firewall = PlannerObservationPrivacyFirewall(store)
        val context = privateContext()

        val filtered = firewall.filter(context)

        assertEquals(context.objective, filtered.objective)
        assertEquals(context.device, filtered.device)
        assertNull(filtered.observation.foregroundApp)
        assertNull(filtered.observation.foregroundWindow)
        assertNull(filtered.observation.uri)
        assertTrue(filtered.observation.visibleText.isEmpty())
        assertNull(filtered.visionFrame)
        assertTrue(filtered.recentEvidence.isEmpty())
        assertTrue(filtered.memoryHints.isEmpty())
        assertTrue(filtered.failedStepDescriptions.isEmpty())
        assertTrue(
            PlannerObservationPrivacyFirewall.PRIVACY_BOUNDARY_CONSTRAINT in filtered.constraints
        )
    }

    @Test
    fun observationWithoutHardDenyPassesThroughUnchanged() {
        val context = privateContext()
        val filtered = PlannerObservationPrivacyFirewall(InMemoryOwnerPlannerPermissionStore()).filter(context)
        assertSame(context, filtered)
    }

    @Test
    fun deniedVisionSourceAlsoRemovesFrameWhenSemanticForegroundDiffers() {
        val store = InMemoryOwnerPlannerPermissionStore().apply {
            put(
                OwnerPlannerPermission(
                    principalId = owner.id,
                    deviceId = device.id,
                    targetScope = "com.bank.app",
                    actionScope = "*",
                    riskClass = "*",
                    decision = OwnerPlannerPermissionDecision.DENY
                )
            )
        }
        val context = privateContext().copy(
            observation = privateContext().observation.copy(foregroundApp = "com.android.settings"),
            visionFrame = PlannerVisionFrame(
                bytes = byteArrayOf(9, 8, 7),
                mimeType = "image/png",
                sourcePackage = "com.bank.app",
                capturedAtEpochMs = 1234L
            )
        )

        val filtered = PlannerObservationPrivacyFirewall(store).filter(context)

        assertNull(filtered.visionFrame)
        assertNull(filtered.observation.foregroundApp)
        assertTrue(filtered.observation.visibleText.isEmpty())
    }

    @Test
    fun actionOnlyDenyDoesNotSilentlyExpandIntoObservationDeny() {
        val store = InMemoryOwnerPlannerPermissionStore().apply {
            put(
                OwnerPlannerPermission(
                    principalId = owner.id,
                    deviceId = device.id,
                    targetScope = "com.bank.app",
                    actionScope = "click",
                    riskClass = "medium",
                    decision = OwnerPlannerPermissionDecision.DENY
                )
            )
        }
        val context = privateContext()
        assertSame(context, PlannerObservationPrivacyFirewall(store).filter(context))
    }

    @Test
    fun androidSemanticPlannerRunsPrivacyFilterAfterLocalCatalogHint() {
        val store = InMemoryOwnerPlannerPermissionStore().apply {
            put(
                OwnerPlannerPermission(
                    principalId = owner.id,
                    deviceId = device.id,
                    targetScope = "com.bank.app",
                    actionScope = "*",
                    riskClass = "*",
                    decision = OwnerPlannerPermissionDecision.DENY
                )
            )
        }
        val firewall = PlannerObservationPrivacyFirewall(store)
        var modelContext: PlannerContext? = null
        val delegate = AiPlanner { context ->
            modelContext = context
            PlannerResult.Rejected(PlannerFailure("TEST", "captured"))
        }
        val catalog = object : AndroidAppTargetResolver {
            override fun resolve(raw: String): String? = raw
            override fun plannerHint(limit: Int): String = "installed_android_apps=[Private Bank=>com.bank.app]"
            override fun filterPlannerContext(context: PlannerContext): PlannerContext = firewall.filter(context)
        }

        AndroidSemanticPlanner(delegate, catalog).plan(privateContext())

        val captured = requireNotNull(modelContext)
        assertTrue(captured.memoryHints.isEmpty())
        assertTrue(captured.recentEvidence.isEmpty())
        assertTrue(captured.observation.visibleText.isEmpty())
        assertNull(captured.observation.foregroundApp)
        assertNull(captured.visionFrame)
    }

    private fun privateContext() = PlannerContext(
        objective = "Open my calendar",
        constraints = listOf("normal constraint"),
        device = device,
        observation = DeviceObservation(
            deviceId = device.id,
            foregroundApp = "com.bank.app",
            foregroundWindow = "Account details",
            visibleText = listOf("Private balance", "Private transaction"),
            uri = "bank://private/account",
            observedAtEpochMs = 1000L
        ),
        recentEvidence = listOf("app:com.bank.app", "verification:TEXT_PRESENT"),
        memoryHints = listOf("private bank memory"),
        failedStepDescriptions = listOf("private bank step"),
        visionFrame = PlannerVisionFrame(
            bytes = byteArrayOf(1, 2, 3),
            mimeType = "image/png",
            sourcePackage = "com.bank.app",
            capturedAtEpochMs = 1001L
        )
    )
}
