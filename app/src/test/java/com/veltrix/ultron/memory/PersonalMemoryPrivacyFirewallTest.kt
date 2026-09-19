package com.veltrix.ultron.memory

import com.veltrix.ultron.planner.InMemoryOwnerPlannerPermissionStore
import com.veltrix.ultron.planner.OwnerPlannerPermission
import com.veltrix.ultron.planner.OwnerPlannerPermissionDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalMemoryPrivacyFirewallTest {
    private val ownerId = "owner"
    private val deviceId = "android-local"
    private val permissions = InMemoryOwnerPlannerPermissionStore()
    private val firewall = PersonalMemoryPrivacyFirewall(permissions, ownerId, deviceId)
    private val store = PolicyBoundPersonalMemoryStore(PersonalMemoryStore(), firewall)

    @Test
    fun privateMemoryRemainsUserVisibleButNeverModelVisible() {
        val record = MemoryRecord(
            kind = MemoryKind.FACT,
            key = "private-note",
            value = "user-only",
            source = "user",
            isPrivate = true
        )
        assertEquals(MemoryWriteState.STORED, store.remember(record).state)

        assertEquals(1, store.recallForUser().size)
        MemoryAudience.entries.forEach { audience ->
            assertTrue(store.recallForModel(audience).isEmpty())
        }
    }

    @Test
    fun positiveAudienceAllowListCanHideMemoryFromWorkerOnly() {
        val record = MemoryRecord(
            kind = MemoryKind.PREFERENCE,
            key = "tone",
            value = "concise",
            source = "user",
            visibleTo = setOf(MemoryAudience.MAIN_ASSISTANT, MemoryAudience.SEARCHER)
        )
        store.remember(record)

        assertEquals(1, store.recallForModel(MemoryAudience.MAIN_ASSISTANT).size)
        assertEquals(1, store.recallForModel(MemoryAudience.SEARCHER).size)
        assertTrue(store.recallForModel(MemoryAudience.WORKER).isEmpty())
        assertTrue(store.recallForModel(MemoryAudience.REPORTER).isEmpty())
    }

    @Test
    fun audienceSpecificHardDenyBlocksOnlyThatAudience() {
        deny(
            target = PersonalMemoryPrivacyScopes.ROOT,
            action = PersonalMemoryPrivacyFirewall.audienceObserveAction(MemoryAudience.WORKER),
            risk = PersonalMemoryPrivacyFirewall.LOW_RISK_CLASS
        )
        store.remember(
            MemoryRecord(
                kind = MemoryKind.FACT,
                key = "safe-fact",
                value = "value",
                source = "user"
            )
        )

        assertTrue(store.recallForModel(MemoryAudience.WORKER).isEmpty())
        assertEquals(1, store.recallForModel(MemoryAudience.MAIN_ASSISTANT).size)
        assertEquals(1, store.recallForModel(MemoryAudience.SEARCHER).size)
    }

    @Test
    fun genericObserveDenyHidesAllModelRecallButNotUserRecall() {
        deny(
            target = PersonalMemoryPrivacyScopes.ROOT,
            action = PersonalMemoryPrivacyFirewall.OBSERVE_ACTION_SCOPE,
            risk = PersonalMemoryPrivacyFirewall.LOW_RISK_CLASS
        )
        val delegate = PersonalMemoryStore().apply {
            remember(MemoryRecord(kind = MemoryKind.FACT, key = "existing", value = "local", source = "user"))
        }
        val policyBound = PolicyBoundPersonalMemoryStore(delegate, firewall)

        assertEquals(1, policyBound.recallForUser().size)
        MemoryAudience.entries.forEach { audience ->
            assertTrue(policyBound.recallForModel(audience).isEmpty())
        }
    }

    @Test
    fun storeDenyPreventsNewPersistence() {
        deny(
            target = PersonalMemoryPrivacyScopes.ROOT,
            action = PersonalMemoryPrivacyFirewall.STORE_ACTION_SCOPE,
            risk = PersonalMemoryPrivacyFirewall.LOW_RISK_CLASS
        )
        val result = store.remember(
            MemoryRecord(kind = MemoryKind.PROJECT, key = "veltrix", value = "blocked", source = "user")
        )

        assertEquals(MemoryWriteState.DENIED, result.state)
        assertTrue(store.recallForUser().isEmpty())
    }

    @Test
    fun recordScopedStoreDenyBlocksUpdateThroughFreshIncomingId() {
        val delegate = PersonalMemoryStore()
        val existing = delegate.remember(
            MemoryRecord(
                id = "stable-record-id",
                kind = MemoryKind.FACT,
                key = "protected",
                value = "old",
                source = "user"
            )
        )
        val policyBound = PolicyBoundPersonalMemoryStore(delegate, firewall)
        deny(
            target = requireNotNull(PersonalMemoryPrivacyScopes.recordScope(existing.id)),
            action = PersonalMemoryPrivacyFirewall.STORE_ACTION_SCOPE,
            risk = PersonalMemoryPrivacyFirewall.LOW_RISK_CLASS
        )

        val result = policyBound.remember(
            MemoryRecord(
                kind = MemoryKind.FACT,
                key = "protected",
                value = "new",
                source = "user"
            )
        )

        assertEquals(MemoryWriteState.DENIED, result.state)
        assertEquals("old", policyBound.recallForUser().single().value)
        assertEquals("stable-record-id", policyBound.recallForUser().single().id)
    }

    @Test
    fun duplicateUpdateCannotDeclassifyOrBroadenAudience() {
        val restricted = MemoryRecord(
            id = "restricted-record",
            kind = MemoryKind.PREFERENCE,
            key = "private-tone",
            value = "first",
            source = "user",
            isPrivate = true,
            visibleTo = setOf(MemoryAudience.MAIN_ASSISTANT)
        )
        assertEquals(MemoryWriteState.STORED, store.remember(restricted).state)

        val update = MemoryRecord(
            kind = MemoryKind.PREFERENCE,
            key = "private-tone",
            value = "second",
            source = "user"
        )
        assertEquals(MemoryWriteState.STORED, store.remember(update).state)

        val persisted = store.recallForUser().single()
        assertEquals("restricted-record", persisted.id)
        assertEquals("second", persisted.value)
        assertTrue(persisted.isPrivate)
        assertEquals(setOf(MemoryAudience.MAIN_ASSISTANT), persisted.visibleTo)
        MemoryAudience.entries.forEach { audience ->
            assertTrue(store.recallForModel(audience).isEmpty())
        }
    }

    @Test
    fun kindScopedDenyDoesNotHideOtherKinds() {
        deny(
            target = PersonalMemoryPrivacyScopes.kindScope(MemoryKind.FACT),
            action = PersonalMemoryPrivacyFirewall.OBSERVE_ACTION_SCOPE,
            risk = PersonalMemoryPrivacyFirewall.LOW_RISK_CLASS
        )
        val delegate = PersonalMemoryStore().apply {
            remember(MemoryRecord(kind = MemoryKind.FACT, key = "fact", value = "hidden", source = "user"))
            remember(MemoryRecord(kind = MemoryKind.PREFERENCE, key = "pref", value = "visible", source = "user"))
        }
        val policyBound = PolicyBoundPersonalMemoryStore(delegate, firewall)

        val visible = policyBound.recallForModel(MemoryAudience.MAIN_ASSISTANT)
        assertEquals(1, visible.size)
        assertEquals(MemoryKind.PREFERENCE, visible.single().kind)
    }

    @Test
    fun recordScopeHashesIdentifierBeforePolicyMetadata() {
        val raw = "secret-shaped-record-id:abc123"
        val scope = requireNotNull(PersonalMemoryPrivacyScopes.recordScope(raw))

        assertTrue(scope.startsWith("memory/personal/record/"))
        assertFalse(scope.contains(raw))
        assertFalse(scope.contains("abc123"))
    }

    @Test
    fun malformedRecordIdentifierFailsClosed() {
        val malformed = MemoryRecord(
            id = "bad\u0000id",
            kind = MemoryKind.FACT,
            key = "bad",
            value = "bad",
            source = "user"
        )

        assertFalse(firewall.canStore(malformed))
        assertFalse(firewall.canObserve(malformed, MemoryAudience.MAIN_ASSISTANT))
    }

    private fun deny(target: String, action: String = "*", risk: String = "*") {
        permissions.put(
            OwnerPlannerPermission(
                principalId = ownerId,
                deviceId = deviceId,
                targetScope = target,
                actionScope = action,
                riskClass = risk,
                decision = OwnerPlannerPermissionDecision.DENY
            )
        )
    }
}
