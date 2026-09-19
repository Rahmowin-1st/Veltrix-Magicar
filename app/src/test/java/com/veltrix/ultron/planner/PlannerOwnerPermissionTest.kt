package com.veltrix.ultron.planner

import com.veltrix.ultron.agents.DelegatedPermission
import com.veltrix.ultron.agents.DelegatedPermissionStore
import com.veltrix.ultron.agents.DelegationDecision
import com.veltrix.ultron.agents.Principal
import com.veltrix.ultron.agents.PrincipalKind
import com.veltrix.ultron.devices.ControlProfile
import com.veltrix.ultron.devices.DeviceCapability
import com.veltrix.ultron.devices.DeviceDescriptor
import com.veltrix.ultron.devices.DeviceKind
import com.veltrix.ultron.devices.DevicePlatform
import com.veltrix.ultron.devices.UniversalAction
import com.veltrix.ultron.devices.UniversalActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerOwnerPermissionTest {
    private val owner = Principal("owner", PrincipalKind.OWNER, "Owner")

    @Test
    fun alwaysAllowIsExactScopedAndDoesNotSpreadAcrossTargets() {
        val store = InMemoryOwnerPlannerPermissionStore()
        val policy = PlannerPolicyGate(ownerPermissions = store)
        val device = device(setOf(DeviceCapability.UI_CLICK))
        val telegram = node("org.telegram.messenger", PlannerRisk.MEDIUM)
        val browser = node("com.android.chrome", PlannerRisk.MEDIUM)

        assertTrue(policy.rememberOwnerAlwaysAllow(owner, device, telegram))
        assertEquals(
            OwnerPlannerPermissionDecision.ALWAYS_ALLOW,
            store.resolve(owner.id, device.id, "org.telegram.messenger", "click", "medium")
        )
        assertEquals(
            OwnerPlannerPermissionDecision.ASK,
            store.resolve(owner.id, device.id, "com.android.chrome", "click", "medium")
        )
    }

    @Test
    fun askEachActionOverridesRememberedOwnerAlwaysAllow() {
        val store = InMemoryOwnerPlannerPermissionStore()
        val policy = PlannerPolicyGate(ownerPermissions = store)
        val device = device(setOf(DeviceCapability.UI_CLICK), ControlProfile.ASK_EACH_ACTION)
        val telegram = node("org.telegram.messenger", PlannerRisk.MEDIUM)

        assertTrue(policy.rememberOwnerAlwaysAllow(owner, device, telegram))
        assertEquals(PlannerPolicyDecision.ASK_USER, policy.evaluate(owner, device, telegram).decision)
    }

    @Test
    fun askEachActionOverridesDelegatedAlwaysAllow() {
        val delegated = DelegatedPermissionStore()
        val policy = PlannerPolicyGate(delegatedPermissions = delegated)
        val device = device(setOf(DeviceCapability.UI_CLICK), ControlProfile.ASK_EACH_ACTION)
        val telegram = node("org.telegram.messenger", PlannerRisk.MEDIUM)
        val agent = Principal("agent-1", PrincipalKind.AGENT, "Agent")
        delegated.put(
            DelegatedPermission(
                principalId = agent.id,
                appScope = "org.telegram.messenger",
                actionScope = "click",
                riskClass = "medium",
                decision = DelegationDecision.ALWAYS_ALLOW
            )
        )

        assertEquals(PlannerPolicyDecision.ASK_USER, policy.evaluate(agent, device, telegram).decision)
    }

    @Test
    fun criticalActionCanNeverBePersistentlyAutoApproved() {
        val policy = PlannerPolicyGate(ownerPermissions = InMemoryOwnerPlannerPermissionStore())
        val device = device(setOf(DeviceCapability.UI_CLICK))
        val critical = node("org.telegram.messenger", PlannerRisk.CRITICAL)

        assertFalse(policy.rememberOwnerAlwaysAllow(owner, device, critical))
        assertEquals(PlannerPolicyDecision.ASK_USER, policy.evaluate(owner, device, critical).decision)
    }

    @Test
    fun ownerPermissionCannotCreateMissingDeviceCapability() {
        val policy = PlannerPolicyGate(ownerPermissions = InMemoryOwnerPlannerPermissionStore())
        val device = device(emptySet())
        val node = node("org.telegram.messenger", PlannerRisk.LOW)

        assertFalse(policy.rememberOwnerAlwaysAllow(owner, device, node))
        assertEquals(PlannerPolicyDecision.DENY, policy.evaluate(owner, device, node).decision)
    }

    private fun device(
        granted: Set<DeviceCapability>,
        controlProfile: ControlProfile = ControlProfile.ASK_EACH_ACTION
    ) = DeviceDescriptor(
        id = "phone",
        ownerPrincipalId = owner.id,
        kind = DeviceKind.PHONE,
        platform = DevicePlatform.ANDROID,
        displayName = "Phone",
        availableCapabilities = setOf(DeviceCapability.UI_CLICK),
        grantedCapabilities = granted,
        controlProfile = controlProfile
    )

    private fun node(target: String, risk: PlannerRisk) = ActionGraphNode(
        id = "click-$target",
        description = "Click target",
        action = UniversalAction(
            type = UniversalActionType.CLICK,
            target = target,
            text = "Continue"
        ),
        requiredCapability = DeviceCapability.UI_CLICK,
        targetScope = target,
        risk = risk,
        verification = VerificationRule(
            mode = VerificationMode.ACTION_ACCEPTED,
            description = "Click accepted"
        )
    )
}
