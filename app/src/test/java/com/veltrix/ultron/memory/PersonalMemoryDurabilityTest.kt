package com.veltrix.ultron.memory

import com.veltrix.ultron.planner.InMemoryOwnerPlannerPermissionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PersonalMemoryDurabilityTest {
    private val now = Instant.parse("2026-08-21T12:00:00Z")

    @Test
    fun codecRoundTripPreservesVersionedBoundedRecord() {
        val record = MemoryRecord(
            id = "record-1",
            kind = MemoryKind.PROJECT,
            key = "veltrix",
            value = "encrypted local memory",
            confidence = 0.9,
            source = "user",
            createdAt = now,
            updatedAt = now.plusSeconds(5),
            expiresAt = now.plusSeconds(3_600),
            isPrivate = false,
            visibleTo = setOf(MemoryAudience.MAIN_ASSISTANT, MemoryAudience.SEARCHER)
        )

        val decoded = PersonalMemorySnapshotCodec.decode(
            PersonalMemorySnapshotCodec.encode(listOf(record))
        )

        assertEquals(listOf(record), decoded)
    }

    @Test
    fun processRestartReloadsDurableSnapshot() {
        val persistence = FakePersonalMemoryPersistence()
        val first = DurablePersonalMemoryStore(persistence) { now }
        val record = safeRecord(id = "stable", key = "project", value = "veltrix")

        assertNotNull(first.rememberIf(record) { true })

        val restarted = DurablePersonalMemoryStore(persistence) { now.plusSeconds(10) }
        val restored = restarted.recall()
        assertEquals(1, restored.size)
        assertEquals("stable", restored.single().id)
        assertEquals("veltrix", restored.single().value)
    }

    @Test
    fun unfinishedMutationFailsClosedAcrossRestart() {
        val persistence = FakePersonalMemoryPersistence(
            snapshot = listOf(safeRecord(id = "existing", key = "k", value = "v")),
            pending = true
        )
        val store = DurablePersonalMemoryStore(persistence) { now }

        assertTrue(store.recall().isEmpty())
        assertTrue(store.isCompromisedForTest())
        assertNull(store.rememberIf(safeRecord(id = "new", key = "new", value = "new")) { true })
        assertEquals(0, persistence.saveCount)
    }

    @Test
    fun corruptLoadFailsClosedWithoutClearingOrOverwriting() {
        val original = safeRecord(id = "existing", key = "k", value = "v")
        val persistence = FakePersonalMemoryPersistence(snapshot = listOf(original), failLoad = true)
        val store = DurablePersonalMemoryStore(persistence) { now }

        assertTrue(store.recall().isEmpty())
        assertTrue(store.isCompromisedForTest())
        assertNull(store.rememberIf(safeRecord(id = "new", key = "new", value = "new")) { true })
        assertEquals(listOf(original), persistence.snapshot)
        assertEquals(0, persistence.saveCount)
    }

    @Test
    fun writeFailureLeavesMutationMarkerAndBlocksFurtherWrites() {
        val persistence = FakePersonalMemoryPersistence(failSave = true)
        val store = DurablePersonalMemoryStore(persistence) { now }

        assertNull(store.rememberIf(safeRecord(id = "new", key = "new", value = "new")) { true })
        assertTrue(store.isCompromisedForTest())
        assertTrue(persistence.pending)
        assertEquals(1, persistence.saveCount)

        persistence.failSave = false
        assertNull(store.rememberIf(safeRecord(id = "second", key = "second", value = "second")) { true })
        assertEquals(1, persistence.saveCount)
    }

    @Test
    fun retentionOverflowFailsClosedBeforeSecondSideEffect() {
        val records = (0 until PersonalMemorySnapshotCodec.MAX_RECORDS).map { index ->
            safeRecord(id = "id-$index", key = "key-$index", value = "value-$index")
        }
        val persistence = FakePersonalMemoryPersistence(snapshot = records)
        val store = DurablePersonalMemoryStore(persistence) { now }

        assertNull(store.rememberIf(safeRecord(id = "overflow", key = "overflow", value = "overflow")) { true })
        assertTrue(store.isCompromisedForTest())
        assertEquals(0, persistence.saveCount)
        assertEquals(PersonalMemorySnapshotCodec.MAX_RECORDS, persistence.snapshot.size)
    }

    @Test
    fun policyBoundPersistenceRedactsSecretShapedContentBeforeStorage() {
        val privacy = PersonalMemoryPrivacyFirewall(
            ownerPermissions = InMemoryOwnerPlannerPermissionStore(),
            ownerPrincipalId = "owner",
            deviceId = "android-local"
        )
        val store = PolicyBoundPersonalMemoryStore(PersonalMemoryStore(), privacy)
        val result = store.remember(
            MemoryRecord(
                id = "safe-id",
                kind = MemoryKind.FACT,
                key = "login",
                value = "password=supersecret token=abcdefghi code 123456",
                source = "user"
            )
        )

        assertEquals(MemoryWriteState.STORED, result.state)
        val persisted = store.recallForUser().single()
        assertFalse(persisted.value.contains("supersecret"))
        assertFalse(persisted.value.contains("abcdefghi"))
        assertFalse(persisted.value.contains("123456"))
        assertTrue(persisted.value.contains("<redacted>"))
        assertTrue(persisted.value.contains("<redacted-6-digit-code>"))
    }

    @Test
    fun invalidVersionAndUnsafeDecodedContentAreRejected() {
        var versionRejected = false
        try {
            PersonalMemorySnapshotCodec.decode("VELTRIX_PERSONAL_MEMORY|99")
        } catch (_: IllegalStateException) {
            versionRejected = true
        }
        assertTrue(versionRejected)

        val unsafe = safeRecord(id = "unsafe", key = "credential", value = "password=rawsecret")
        var unsafeRejected = false
        try {
            PersonalMemorySnapshotCodec.encode(listOf(unsafe))
        } catch (_: IllegalStateException) {
            unsafeRejected = true
        }
        assertTrue(unsafeRejected)
    }

    private fun safeRecord(id: String, key: String, value: String): MemoryRecord = MemoryRecord(
        id = id,
        kind = MemoryKind.FACT,
        key = key,
        value = value,
        source = "user",
        createdAt = now,
        updatedAt = now
    )
}

private class FakePersonalMemoryPersistence(
    var snapshot: List<MemoryRecord> = emptyList(),
    var pending: Boolean = false,
    var failLoad: Boolean = false,
    var failSave: Boolean = false
) : PersonalMemoryPersistence {
    var saveCount: Int = 0

    override fun hasUnfinishedMutation(): Boolean = pending

    override fun beginMutation() {
        pending = true
    }

    override fun finishMutation() {
        pending = false
    }

    override fun load(): List<MemoryRecord> {
        if (failLoad) error("corrupt snapshot")
        return snapshot.toList()
    }

    override fun save(records: List<MemoryRecord>) {
        saveCount += 1
        if (failSave) error("write failed")
        snapshot = records.toList()
    }
}
