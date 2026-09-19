package com.veltrix.ultron.privacy

import com.veltrix.ultron.agents.Principal
import com.veltrix.ultron.agents.PrincipalKind
import com.veltrix.ultron.planner.InMemoryOwnerPlannerPermissionStore
import com.veltrix.ultron.planner.OwnerPlannerPermission
import com.veltrix.ultron.planner.OwnerPlannerPermissionDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerPrivacyPolicyBrokerTest {
    private val owner = Principal("owner", PrincipalKind.OWNER, "Owner")
    private val user = Principal("owner", PrincipalKind.USER, "Owner")
    private val agent = Principal("agent", PrincipalKind.AGENT, "Agent")
    private val store = InMemoryOwnerPlannerPermissionStore()
    private val broker = OwnerPrivacyPolicyBroker(
        ownerPrincipalId = owner.id,
        deviceId = "android-local",
        store = store
    )

    @Test
    fun directOwnerCanInstallHardDeny() {
        val result = broker.apply(
            owner,
            mutation(
                operation = PrivacyPolicyOperation.ADD_HARD_DENY,
                target = "com.bank.app"
            )
        )

        assertEquals(PrivacyPolicyMutationState.ENFORCED, result.state)
        assertTrue(result.effectiveDenied)
        assertTrue(broker.isHardDenied("com.bank.app", "click", "medium"))
    }

    @Test
    fun delegatedAgentCannotInstallOrRemoveOwnerPolicy() {
        val deniedAdd = broker.apply(
            agent,
            mutation(PrivacyPolicyOperation.ADD_HARD_DENY, "com.bank.app")
        )
        assertEquals(PrivacyPolicyMutationState.REJECTED, deniedAdd.state)

        broker.apply(owner, mutation(PrivacyPolicyOperation.ADD_HARD_DENY, "com.bank.app"))
        val deniedRemove = broker.apply(
            agent,
            mutation(PrivacyPolicyOperation.REMOVE_HARD_DENY, "com.bank.app")
        )
        assertEquals(PrivacyPolicyMutationState.REJECTED, deniedRemove.state)
        assertTrue(broker.isHardDenied("com.bank.app", "click", "medium"))
    }

    @Test
    fun modelOriginCannotMutateEvenWhenSessionPrincipalIsOwner() {
        val result = broker.apply(
            owner,
            PrivacyPolicyMutation(
                operation = PrivacyPolicyOperation.ADD_HARD_DENY,
                targetScope = "com.bank.app",
                origin = PrivacyPolicyMutationOrigin.MODEL_OR_AGENT
            )
        )

        assertEquals(PrivacyPolicyMutationState.REJECTED, result.state)
        assertFalse(store.isHardDenied(owner.id, "android-local", "com.bank.app", "click", "medium"))
    }

    @Test
    fun ownerUserPrincipalWithSameIdentityCanUseTrustedSettingsUi() {
        val result = broker.apply(
            user,
            PrivacyPolicyMutation(
                operation = PrivacyPolicyOperation.ADD_HARD_DENY,
                targetScope = "notification/com.mail.app",
                actionScope = "observe",
                riskClass = "low",
                origin = PrivacyPolicyMutationOrigin.TRUSTED_SETTINGS_UI
            )
        )

        assertEquals(PrivacyPolicyMutationState.ENFORCED, result.state)
        assertTrue(broker.isHardDenied("notification/com.mail.app", "observe", "low"))
        assertFalse(broker.isHardDenied("notification/com.mail.app", "store", "low"))
    }

    @Test
    fun removingHardDenyNeverCreatesAlwaysAllow() {
        broker.apply(owner, mutation(PrivacyPolicyOperation.ADD_HARD_DENY, "com.bank.app"))

        val removed = broker.apply(
            owner,
            mutation(PrivacyPolicyOperation.REMOVE_HARD_DENY, "com.bank.app")
        )

        assertEquals(PrivacyPolicyMutationState.REMOVED, removed.state)
        assertFalse(removed.effectiveDenied)
        assertEquals(
            OwnerPlannerPermissionDecision.ASK,
            store.resolve(owner.id, "android-local", "com.bank.app", "*", "*")
        )
    }

    @Test
    fun allDeviceDenyUsesOwnerWildcardAndCoversOtherDevices() {
        val result = broker.apply(
            owner,
            mutation(
                operation = PrivacyPolicyOperation.ADD_HARD_DENY,
                target = "memory/conversation",
                allDevices = true
            )
        )

        assertEquals(PrivacyPolicyMutationState.ENFORCED, result.state)
        assertEquals(
            OwnerPlannerPermissionDecision.DENY,
            store.resolve(owner.id, OWNER_PRIVACY_WILDCARD, "memory/conversation", "*", "*")
        )
        assertTrue(store.isHardDenied(owner.id, "laptop-main", "memory/conversation", "observe", "low"))
    }

    @Test
    fun removingNarrowDenyDoesNotOverrideBroaderParentDeny() {
        store.put(
            OwnerPlannerPermission(
                principalId = owner.id,
                deviceId = "android-local",
                targetScope = "notification/com.bank.app",
                actionScope = "*",
                riskClass = "*",
                decision = OwnerPlannerPermissionDecision.DENY
            )
        )
        broker.apply(
            owner,
            mutation(
                operation = PrivacyPolicyOperation.ADD_HARD_DENY,
                target = "notification/com.bank.app/channel/otp",
                action = "observe",
                risk = "low"
            )
        )

        val removed = broker.apply(
            owner,
            mutation(
                operation = PrivacyPolicyOperation.REMOVE_HARD_DENY,
                target = "notification/com.bank.app/channel/otp",
                action = "observe",
                risk = "low"
            )
        )

        assertEquals(PrivacyPolicyMutationState.REMOVED, removed.state)
        assertTrue(removed.effectiveDenied)
        assertTrue(broker.isHardDenied("notification/com.bank.app/channel/otp", "observe", "low"))
    }

    @Test
    fun malformedOrTraversalLikeScopesFailClosedWithoutMutation() {
        listOf("", "/", "notification/../secret", "notification//secret", "bad\u0000scope").forEach { target ->
            val result = broker.apply(
                owner,
                mutation(PrivacyPolicyOperation.ADD_HARD_DENY, target)
            )
            assertEquals(PrivacyPolicyMutationState.REJECTED, result.state)
            assertTrue(result.effectiveDenied)
        }
    }

    @Test
    fun policyLeavesAreNormalizedButCannotBecomeHierarchy() {
        val result = broker.apply(
            owner,
            mutation(
                operation = PrivacyPolicyOperation.ADD_HARD_DENY,
                target = "com.example.app",
                action = " ObSeRvE ",
                risk = " LOW "
            )
        )
        assertEquals(PrivacyPolicyMutationState.ENFORCED, result.state)
        assertEquals("observe", result.actionScope)
        assertEquals("low", result.riskClass)

        val invalid = broker.apply(
            owner,
            mutation(
                operation = PrivacyPolicyOperation.ADD_HARD_DENY,
                target = "com.example.other",
                action = "observe/store"
            )
        )
        assertEquals(PrivacyPolicyMutationState.REJECTED, invalid.state)
    }

    private fun mutation(
        operation: PrivacyPolicyOperation,
        target: String,
        action: String = "*",
        risk: String = "*",
        allDevices: Boolean = false
    ) = PrivacyPolicyMutation(
        operation = operation,
        targetScope = target,
        actionScope = action,
        riskClass = risk,
        allOwnerDevices = allDevices,
        origin = PrivacyPolicyMutationOrigin.DIRECT_USER_INTERACTION
    )
}
