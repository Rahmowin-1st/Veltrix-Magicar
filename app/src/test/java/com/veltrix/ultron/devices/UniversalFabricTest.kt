package com.veltrix.ultron.devices

import com.veltrix.ultron.agents.MissionBackedAgentGateway
import com.veltrix.ultron.agents.Principal
import com.veltrix.ultron.agents.PrincipalKind
import com.veltrix.ultron.gateway.UltronMcpCall
import com.veltrix.ultron.gateway.UltronMcpGateway
import com.veltrix.ultron.gateway.UltronMcpSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalFabricTest {
    private val owner = Principal("owner", PrincipalKind.OWNER, "Owner")
    private val agentA = Principal("frontend-agent", PrincipalKind.AGENT, "Frontend Agent")
    private val agentB = Principal("qa-agent", PrincipalKind.AGENT, "QA Agent")

    @Test
    fun maxApprovedOnlyUsesCapabilitiesActuallyGrantedByDeviceOwner() {
        val device = DeviceDescriptor(
            id = "phone-1",
            ownerPrincipalId = owner.id,
            kind = DeviceKind.PHONE,
            platform = DevicePlatform.ANDROID,
            displayName = "Phone",
            availableCapabilities = setOf(DeviceCapability.OPEN_APP, DeviceCapability.UI_CLICK),
            grantedCapabilities = setOf(DeviceCapability.OPEN_APP),
            controlProfile = ControlProfile.MAX_APPROVED,
            presence = DevicePresence.ONLINE
        )

        assertEquals(setOf(DeviceCapability.OPEN_APP), device.effectiveCapabilities)
        assertTrue(device.supports(setOf(DeviceCapability.OPEN_APP)))
        assertFalse(device.supports(setOf(DeviceCapability.UI_CLICK)))
    }

    @Test
    fun remoteAgentCannotElevateDeviceToMaxApproved() {
        val registry = DeviceRegistry()
        registry.enroll(
            DeviceDescriptor(
                id = "desktop-1",
                ownerPrincipalId = owner.id,
                kind = DeviceKind.DESKTOP,
                platform = DevicePlatform.WINDOWS,
                displayName = "Desktop",
                availableCapabilities = setOf(DeviceCapability.DESKTOP_AUTOMATION),
                grantedCapabilities = setOf(DeviceCapability.DESKTOP_AUTOMATION),
                controlProfile = ControlProfile.ASK_EACH_ACTION
            )
        )

        val receipt = registry.requestControlProfile(
            authenticatedPrincipalId = agentA.id,
            deviceId = "desktop-1",
            profile = ControlProfile.MAX_APPROVED,
            reason = "Need control"
        )

        assertTrue(receipt.ownerApprovalRequired)
        assertEquals(ControlProfile.ASK_EACH_ACTION, registry.get("desktop-1")?.controlProfile)
    }

    @Test
    fun routerChoosesOnlineDesktopWithRequiredGrantedCapabilities() {
        val registry = DeviceRegistry()
        registry.enroll(
            DeviceDescriptor(
                id = "phone-1",
                ownerPrincipalId = owner.id,
                kind = DeviceKind.PHONE,
                platform = DevicePlatform.ANDROID,
                displayName = "Phone",
                availableCapabilities = setOf(DeviceCapability.OPEN_APP),
                grantedCapabilities = setOf(DeviceCapability.OPEN_APP),
                presence = DevicePresence.OFFLINE
            )
        )
        registry.enroll(
            DeviceDescriptor(
                id = "desktop-1",
                ownerPrincipalId = owner.id,
                kind = DeviceKind.DESKTOP,
                platform = DevicePlatform.WINDOWS,
                displayName = "Desktop",
                availableCapabilities = setOf(DeviceCapability.DESKTOP_AUTOMATION, DeviceCapability.OPEN_APP),
                grantedCapabilities = setOf(DeviceCapability.DESKTOP_AUTOMATION, DeviceCapability.OPEN_APP),
                controlProfile = ControlProfile.MAX_APPROVED,
                presence = DevicePresence.ONLINE
            )
        )

        val route = UniversalTaskRouter(registry).route(
            auth = AuthenticatedExecutionContext(
                principal = agentA,
                deviceOwnerPrincipalId = owner.id
            ),
            task = UniversalTaskIntent(
                objective = "Open the desktop app and verify it",
                requiredCapabilities = setOf(DeviceCapability.DESKTOP_AUTOMATION),
                targetPreference = DeviceTargetPreference.AUTO
            )
        )

        assertEquals(DeviceRouteState.READY, route.state)
        assertEquals("desktop-1", route.device?.id)
    }

    @Test
    fun dedicatedMcpExposesTaskToolsNotRawExecutorPrimitives() {
        val gateway = UltronMcpGateway(MissionBackedAgentGateway(), DeviceRegistry())
        val tools = gateway.tools()

        assertTrue("ultron.submit_task" in tools)
        assertTrue("ultron.list_devices" in tools)
        assertFalse(tools.any { it.contains("tap", ignoreCase = true) })
        assertFalse(tools.any { it.contains("type", ignoreCase = true) })
    }

    @Test
    fun mcpTaskOwnershipIsBoundToAuthenticatedPrincipal() {
        val registry = DeviceRegistry()
        registry.enroll(
            DeviceDescriptor(
                id = "phone-1",
                ownerPrincipalId = owner.id,
                kind = DeviceKind.PHONE,
                platform = DevicePlatform.ANDROID,
                displayName = "Phone",
                availableCapabilities = setOf(DeviceCapability.OPEN_APP),
                grantedCapabilities = setOf(DeviceCapability.OPEN_APP),
                controlProfile = ControlProfile.MAX_APPROVED,
                presence = DevicePresence.ONLINE
            )
        )
        val mcp = UltronMcpGateway(MissionBackedAgentGateway(), registry)
        val sessionA = UltronMcpSession(agentA, deviceOwnerPrincipalId = owner.id)
        val sessionB = UltronMcpSession(agentB, deviceOwnerPrincipalId = owner.id)

        val submitted = mcp.invoke(
            sessionA,
            UltronMcpCall(
                tool = "ultron.submit_task",
                arguments = mapOf(
                    "objective" to "Open an app",
                    "target" to "phone",
                    "required_capabilities" to "OPEN_APP"
                )
            )
        )
        assertTrue(submitted.ok)
        val taskId = requireNotNull(submitted.taskId)

        val stolenRead = mcp.invoke(
            sessionB,
            UltronMcpCall(
                tool = "ultron.get_task",
                arguments = mapOf("task_id" to taskId)
            )
        )

        assertFalse(stolenRead.ok)
        assertEquals("DENIED", stolenRead.state)
    }
}
