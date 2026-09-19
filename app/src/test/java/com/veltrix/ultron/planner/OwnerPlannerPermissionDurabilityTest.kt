package com.veltrix.ultron.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerPlannerPermissionDurabilityTest {

    @Test
    fun snapshotCodecRoundTripsDeterministically() {
        val first = permission(
            target = "org.telegram.messenger",
            action = "*",
            risk = "*",
            decision = OwnerPlannerPermissionDecision.DENY
        )
        val second = permission(
            target = "com.android.chrome",
            action = "open_app",
            risk = "low",
            decision = OwnerPlannerPermissionDecision.ALWAYS_ALLOW
        )

        val encoded = OwnerPlannerPermissionSnapshotCodec.encode(listOf(first, second))
        val reversed = OwnerPlannerPermissionSnapshotCodec.encode(listOf(second, first))

        assertEquals(encoded, reversed)
        assertEquals(setOf(first, second), OwnerPlannerPermissionSnapshotCodec.decode(encoded).toSet())
    }

    @Test(expected = IllegalStateException::class)
    fun unsupportedSnapshotVersionIsRejected() {
        OwnerPlannerPermissionSnapshotCodec.decode("VELTRIX_OWNER_POLICY|999")
    }

    @Test(expected = IllegalStateException::class)
    fun duplicateScopeIsRejected() {
        val item = permission(
            target = "org.telegram.messenger",
            action = "click",
            risk = "medium",
            decision = OwnerPlannerPermissionDecision.DENY
        )
        OwnerPlannerPermissionSnapshotCodec.encode(listOf(item, item))
    }

    @Test
    fun persistedPermissionSurvivesRepositoryRecreation() {
        val persistence = FakePersistence()
        val first = DurableOwnerPlannerPermissionStore(persistence)
        val allow = permission(
            target = "com.android.chrome",
            action = "open_app",
            risk = "low",
            decision = OwnerPlannerPermissionDecision.ALWAYS_ALLOW
        )

        first.put(allow)
        assertFalse(first.isCompromisedForTest())

        val restored = DurableOwnerPlannerPermissionStore(persistence)
        assertEquals(
            OwnerPlannerPermissionDecision.ALWAYS_ALLOW,
            restored.resolve(
                allow.principalId,
                allow.deviceId,
                allow.targetScope,
                allow.actionScope,
                allow.riskClass
            )
        )
    }

    @Test
    fun interruptedMutationFailsClosedAcrossRepositoryRecreation() {
        val persistence = FakePersistence(pending = true)
        val store = DurableOwnerPlannerPermissionStore(persistence)

        assertEquals(
            OwnerPlannerPermissionDecision.DENY,
            store.resolve("owner", "phone-a", "com.android.chrome", "open_app", "low")
        )
        assertTrue(
            store.isHardDenied("owner", "phone-a", "com.android.chrome", "open_app", "low")
        )
        assertTrue(store.isCompromisedForTest())
    }

    @Test
    fun corruptedLoadFailsClosedInsteadOfFallingBackToAsk() {
        val persistence = FakePersistence(loadFailure = IllegalStateException("bad tag"))
        val store = DurableOwnerPlannerPermissionStore(persistence)

        assertEquals(
            OwnerPlannerPermissionDecision.DENY,
            store.resolve("owner", "phone-a", "com.android.chrome", "open_app", "low")
        )
        assertTrue(store.isCompromisedForTest())
    }

    @Test
    fun failedWriteLeavesDurableMutationMarkerAndNextRepositoryDenies() {
        val persistence = FakePersistence(failSave = true)
        val store = DurableOwnerPlannerPermissionStore(persistence)

        store.put(
            permission(
                target = "org.telegram.messenger",
                action = "*",
                risk = "*",
                decision = OwnerPlannerPermissionDecision.DENY
            )
        )

        assertTrue(store.isCompromisedForTest())
        assertTrue(persistence.pending)

        val afterRestart = DurableOwnerPlannerPermissionStore(persistence)
        assertEquals(
            OwnerPlannerPermissionDecision.DENY,
            afterRestart.resolve("owner", "phone-a", "anything", "click", "low")
        )
    }

    @Test
    fun failedAllowRevocationCannotSilentlyRestoreExecution() {
        val persistence = FakePersistence()
        val store = DurableOwnerPlannerPermissionStore(persistence)
        val allow = permission(
            target = "com.android.chrome",
            action = "open_app",
            risk = "low",
            decision = OwnerPlannerPermissionDecision.ALWAYS_ALLOW
        )
        store.put(allow)
        assertEquals(
            OwnerPlannerPermissionDecision.ALWAYS_ALLOW,
            store.resolve("owner", "phone-a", "com.android.chrome", "open_app", "low")
        )

        persistence.failSave = true
        assertFalse(
            store.revoke("owner", "phone-a", "com.android.chrome", "open_app", "low")
        )
        assertTrue(persistence.pending)

        val afterRestart = DurableOwnerPlannerPermissionStore(persistence)
        assertEquals(
            OwnerPlannerPermissionDecision.DENY,
            afterRestart.resolve("owner", "phone-a", "com.android.chrome", "open_app", "low")
        )
    }

    @Test
    fun legacyEntryCodecPreservesExactScopeAndDecision() {
        val fields = listOf("owner", "phone-a", "org.telegram.messenger", "*", "*")
        val encodedKey = "owner_scope." + fields.joinToString(".") { encodeLegacySegment(it) }

        assertEquals(
            permission(
                target = "org.telegram.messenger",
                action = "*",
                risk = "*",
                decision = OwnerPlannerPermissionDecision.DENY
            ),
            LegacyOwnerPlannerPermissionCodec.decode(encodedKey, "DENY")
        )
    }

    private fun permission(
        target: String,
        action: String,
        risk: String,
        decision: OwnerPlannerPermissionDecision
    ) = OwnerPlannerPermission(
        principalId = "owner",
        deviceId = "phone-a",
        targetScope = target,
        actionScope = action,
        riskClass = risk,
        decision = decision
    )

    private fun encodeLegacySegment(value: String): String = java.util.Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    private class FakePersistence(
        var pending: Boolean = false,
        private var loadFailure: Throwable? = null,
        var failSave: Boolean = false
    ) : OwnerPlannerPermissionPersistence {
        private var records = emptyList<OwnerPlannerPermission>()

        override fun hasUnfinishedMutation(): Boolean = pending

        override fun beginMutation() {
            pending = true
        }

        override fun finishMutation() {
            pending = false
        }

        override fun load(): List<OwnerPlannerPermission> {
            loadFailure?.let { throw it }
            return records.toList()
        }

        override fun save(permissions: List<OwnerPlannerPermission>) {
            if (failSave) error("disk full")
            records = permissions.toList()
        }
    }
}
