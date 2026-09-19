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

class PlannerHardDenyTest {
    private val owner = Principal("owner", PrincipalKind.OWNER, "Owner")
    private val agent = Principal("agent", PrincipalKind.AGENT, "Agent")

    @Test
    fun hardDenyOverridesMaxApprovedOwnerAndLeavesOtherTargetsAlone() {
        val store = InMemoryOwnerPlannerPermissionStore()
        val policy = PlannerPolicyGate(ownerPermissions = store)
        val device = device("phone-a", ControlProfile.MAX_APPROVED)
        val telegram = node("org.telegram.messenger")
        val browser = node("com.android.chrome")

        assertEquals(PlannerPolicyDecision.ALLOW, policy.evaluate(owner, device, telegram).decision)
        assertTrue(policy.rememberOwnerHardDeny(owner, device, "org.telegram.messenger"))
        assertEquals(PlannerPolicyDecision.DENY, policy.evaluate(owner, device, telegram).decision)
        assertEquals(PlannerPolicyDecision.ALLOW, policy.evaluate(owner, device, browser).decision)
    }

    @Test
    fun hardDenyOverridesDelegatedAlwaysAllow() {
        val store = InMemoryOwnerPlannerPermissionStore()
        val delegated = DelegatedPermissionStore().apply {
            put(
                DelegatedPermission(
                    principalId = agent.id,
                    appScope = "org.telegram.messenger",
                    actionScope = "click",
                    riskClass = "medium",
                    decision = DelegationDecision.ALWAYS_ALLOW
                )
            )
        }
        val policy = PlannerPolicyGate(
            delegatedPermissions = delegated,
            ownerPermissions = store
        )
        val device = device("phone-a", ControlProfile.MAX_APPROVED)
        val telegram = node("org.telegram.messenger")

        assertEquals(PlannerPolicyDecision.ALLOW, policy.evaluate(agent, device, telegram).decision)
        assertTrue(policy.rememberOwnerHardDeny(owner, device, "org.telegram.messenger"))
        assertEquals(PlannerPolicyDecision.DENY, policy.evaluate(agent, device, telegram).decision)
    }

    @Test
    fun delegatedDenyCannotBeWeakenedIntoAskByAskEachActionProfile() {
        val delegated = DelegatedPermissionStore().apply {
            put(
                DelegatedPermission(
                    principalId = agent.id,
                    appScope = "org.telegram.messenger",
                    actionScope = "click",
                    riskClass = "medium",
                    decision = DelegationDecision.DENY
                )
            )
        }
        val policy = PlannerPolicyGate(
            delegatedPermissions = delegated,
            ownerPermissions = InMemoryOwnerPlannerPermissionStore()
        )
        val askDevice = device("phone-a", ControlProfile.ASK_EACH_ACTION)

        assertEquals(
            PlannerPolicyDecision.DENY,
            policy.evaluate(agent, askDevice, node("org.telegram.messenger")).decision
        )
    }

    @Test
    fun parentResourceDenyBlocksDescendantResource() {
        val policy = PlannerPolicyGate(ownerPermissions = InMemoryOwnerPlannerPermissionStore())
        val device = device("phone-a", ControlProfile.MAX_APPROVED)

        assertTrue(
            policy.rememberOwnerHardDeny(
                owner,
                device,
                "notification/com.bank.app"
            )
        )

        assertEquals(
            PlannerPolicyDecision.DENY,
            policy.evaluate(
                owner,
                device,
                node("notification/com.bank.app/channel/otp")
            ).decision
        )
        assertEquals(
            PlannerPolicyDecision.ALLOW,
            policy.evaluate(
                owner,
                device,
                node("notification/org.telegram.messenger/channel/general")
            ).decision
        )
    }

    @Test
    fun revokeHardDenyRestoresPriorAskPolicy() {
        val policy = PlannerPolicyGate(ownerPermissions = InMemoryOwnerPlannerPermissionStore())
        val device = device("phone-a", ControlProfile.ASK_EACH_ACTION)
        val target = node("org.telegram.messenger")

        assertTrue(policy.rememberOwnerHardDeny(owner, device, "org.telegram.messenger"))
        assertEquals(PlannerPolicyDecision.DENY, policy.evaluate(owner, device, target).decision)
        assertTrue(policy.revokeOwnerHardDeny(owner, device, "org.telegram.messenger"))
        assertEquals(PlannerPolicyDecision.ASK_USER, policy.evaluate(owner, device, target).decision)
    }

    @Test
    fun allOwnerDevicesDenyAppliesToSecondDevice() {
        val store = InMemoryOwnerPlannerPermissionStore()
        val policy = PlannerPolicyGate(ownerPermissions = store)
        val first = device("phone-a", ControlProfile.MAX_APPROVED)
        val second = device("phone-b", ControlProfile.MAX_APPROVED)
        val target = node("org.telegram.messenger")

        assertTrue(
            policy.rememberOwnerHardDeny(
                owner,
                first,
                "org.telegram.messenger",
                allOwnerDevices = true
            )
        )
        assertEquals(PlannerPolicyDecision.DENY, policy.evaluate(owner, first, target).decision)
        assertEquals(PlannerPolicyDecision.DENY, policy.evaluate(owner, second, target).decision)
    }

    @Test
    fun delegatedAgentCannotInstallOrRemoveOwnerHardDeny() {
        val policy = PlannerPolicyGate(ownerPermissions = InMemoryOwnerPlannerPermissionStore())
        val device = device("phone-a", ControlProfile.MAX_APPROVED)

        assertFalse(policy.rememberOwnerHardDeny(agent, device, "org.telegram.messenger"))
        assertFalse(policy.revokeOwnerHardDeny(agent, device, "org.telegram.messenger"))
        assertEquals(
            PlannerPolicyDecision.ALLOW,
            policy.evaluate(owner, device, node("org.telegram.messenger")).decision
        )
    }

    @Test
    fun hardDenyBeatsRememberedAlwaysAllow() {
        val store = InMemoryOwnerPlannerPermissionStore()
        val policy = PlannerPolicyGate(ownerPermissions = store)
        val device = device("phone-a", ControlProfile.ASK_EACH_ACTION)
        val target = node("org.telegram.messenger")

        assertTrue(policy.rememberOwnerAlwaysAllow(owner, device, target))
        assertEquals(PlannerPolicyDecision.ASK_USER, policy.evaluate(owner, device, target).decision)
        assertTrue(policy.rememberOwnerHardDeny(owner, device, "org.telegram.messenger"))
        assertEquals(PlannerPolicyDecision.DENY, policy.evaluate(owner, device, target).decision)
    }

    private fun device(id: String, profile: ControlProfile) = DeviceDescriptor(
        id = id,
        ownerPrincipalId = owner.id,
        kind = DeviceKind.PHONE,
        platform = DevicePlatform.ANDROID,
        displayName = id,
        availableCapabilities = setOf(DeviceCapability.UI_CLICK),
        grantedCapabilities = setOf(DeviceCapability.UI_CLICK),
        controlProfile = profile
    )

    private fun node(target: String) = ActionGraphNode(
        id = "click-$target",
        description = "Click target",
        action = UniversalAction(
            type = UniversalActionType.CLICK,
            target = target,
            text = "Continue"
        ),
        requiredCapability = DeviceCapability.UI_CLICK,
        targetScope = target,
        risk = PlannerRisk.MEDIUM,
        verification = VerificationRule(
            mode = VerificationMode.ACTION_ACCEPTED,
            description = "Click accepted"
        )
    )
}
