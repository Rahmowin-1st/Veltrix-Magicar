package com.veltrix.ultron.memory

import com.veltrix.ultron.planner.InMemoryOwnerPlannerPermissionStore
import com.veltrix.ultron.planner.OwnerPlannerPermission
import com.veltrix.ultron.planner.OwnerPlannerPermissionDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalMemoryRuntimeContextTest {
    private val ownerId = "owner"
    private val deviceId = "android-local"
    private val permissions = InMemoryOwnerPlannerPermissionStore()
    private val firewall = PersonalMemoryPrivacyFirewall(permissions, ownerId, deviceId)
    private val store = PolicyBoundPersonalMemoryStore(PersonalMemoryStore(), firewall)
    private val context = PersonalMemoryRuntimeContext(store)

    @Test
    fun audienceSpecificHintsNeverCrossRoleBoundary() {
        store.remember(
            MemoryRecord(
                kind = MemoryKind.PREFERENCE,
                key = "assistant-tone",
                value = "concise",
                source = "user",
                visibleTo = setOf(MemoryAudience.MAIN_ASSISTANT)
            )
        )
        store.remember(
            MemoryRecord(
                kind = MemoryKind.PROJECT,
                key = "search-project",
                value = "veltrix",
                source = "user",
                visibleTo = setOf(MemoryAudience.SEARCHER)
            )
        )
        store.remember(
            MemoryRecord(
                kind = MemoryKind.FACT,
                key = "private-note",
                value = "never-model-visible",
                source = "user",
                isPrivate = true
            )
        )

        val assistant = context.hintsFor(MemoryAudience.MAIN_ASSISTANT)
        val searcher = context.hintsFor(MemoryAudience.SEARCHER)
        val worker = context.hintsFor(MemoryAudience.WORKER)
        val reporter = context.hintsFor(MemoryAudience.REPORTER)

        assertEquals(1, assistant.size)
        assertTrue(assistant.single().contains("assistant-tone=concise"))
        assertFalse(assistant.single().contains("search-project"))
        assertEquals(1, searcher.size)
        assertTrue(searcher.single().contains("search-project=veltrix"))
        assertTrue(worker.isEmpty())
        assertTrue(reporter.isEmpty())
        listOf(assistant, searcher, worker, reporter).flatten().forEach { hint ->
            assertFalse(hint.contains("private-note"))
            assertFalse(hint.contains("never-model-visible"))
        }
    }

    @Test
    fun currentHardDenyIsReevaluatedBeforeEveryContextRead() {
        val stored = requireNotNull(
            store.remember(
                MemoryRecord(
                    id = "runtime-policy-record",
                    kind = MemoryKind.FACT,
                    key = "allowed-first",
                    value = "visible",
                    source = "user"
                )
            ).record
        )
        assertEquals(1, context.hintsFor(MemoryAudience.MAIN_ASSISTANT).size)

        deny(
            target = requireNotNull(PersonalMemoryPrivacyScopes.recordScope(stored.id)),
            action = PersonalMemoryPrivacyFirewall.audienceObserveAction(MemoryAudience.MAIN_ASSISTANT)
        )

        assertTrue(context.hintsFor(MemoryAudience.MAIN_ASSISTANT).isEmpty())
        assertEquals(1, context.hintsFor(MemoryAudience.SEARCHER).size)
    }

    @Test
    fun hintsAreBoundedRedactedAndMetadataMinimized() {
        store.remember(
            MemoryRecord(
                id = "sensitive-record-id-123",
                kind = MemoryKind.FACT,
                key = "credential-note",
                value = "token=supersecret123456789",
                source = "source-that-must-not-reach-model"
            )
        )
        repeat(20) { index ->
            store.remember(
                MemoryRecord(
                    kind = MemoryKind.FACT,
                    key = "fact-$index",
                    value = "value-$index",
                    source = "user"
                )
            )
        }

        val hints = context.hintsFor(MemoryAudience.MAIN_ASSISTANT, limit = Int.MAX_VALUE)

        assertEquals(PersonalMemoryRuntimeContext.MAX_HINTS, hints.size)
        hints.forEach { hint ->
            assertTrue(hint.startsWith("personal_memory:"))
            assertFalse(hint.contains("sensitive-record-id-123"))
            assertFalse(hint.contains("source-that-must-not-reach-model"))
            assertTrue(hint.length <= 1_024)
        }

        val credentialHint = context.hintsFor(
            audience = MemoryAudience.MAIN_ASSISTANT,
            query = "credential-note"
        ).single()
        assertTrue(credentialHint.contains("token=<redacted>"))
        assertFalse(credentialHint.contains("supersecret123456789"))
    }

    @Test
    fun zeroOrNegativeLimitDisclosesNothing() {
        store.remember(
            MemoryRecord(
                kind = MemoryKind.FACT,
                key = "fact",
                value = "value",
                source = "user"
            )
        )

        assertTrue(context.hintsFor(MemoryAudience.MAIN_ASSISTANT, limit = 0).isEmpty())
        assertTrue(context.hintsFor(MemoryAudience.MAIN_ASSISTANT, limit = -10).isEmpty())
    }

    private fun deny(target: String, action: String) {
        permissions.put(
            OwnerPlannerPermission(
                principalId = ownerId,
                deviceId = deviceId,
                targetScope = target,
                actionScope = action,
                riskClass = PersonalMemoryPrivacyFirewall.LOW_RISK_CLASS,
                decision = OwnerPlannerPermissionDecision.DENY
            )
        )
    }
}
