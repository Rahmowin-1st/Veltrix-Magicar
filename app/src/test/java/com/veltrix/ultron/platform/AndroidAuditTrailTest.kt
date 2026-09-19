package com.veltrix.ultron.platform

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AndroidAuditTrailTest {
    @Before
    fun before() {
        AndroidAuditTrail.resetForTests()
    }

    @After
    fun after() {
        AndroidAuditTrail.resetForTests()
    }

    @Test
    fun persistsAndRestoresAcrossProcessRestart() {
        val store = FakePersistence()
        AndroidAuditTrail.installPersistenceForTests(store)

        assertTrue(AndroidAuditTrail.record(input(AndroidAuditEventType.MISSION_SUBMITTED)))
        val original = AndroidAuditTrail.snapshot().single()

        AndroidAuditTrail.simulateProcessRestartForTests()

        val restored = AndroidAuditTrail.snapshot().single()
        assertEquals(original.id, restored.id)
        assertEquals(original.sequence, restored.sequence)
        assertEquals(AndroidAuditEventType.MISSION_SUBMITTED, restored.type)
    }

    @Test
    fun retentionIsBoundedAndSequenceRemainsMonotonic() {
        AndroidAuditTrail.installPersistenceForTests(FakePersistence())

        repeat(300) { index ->
            assertTrue(AndroidAuditTrail.record(input(AndroidAuditEventType.ACTION_DISPATCH_REQUESTED, result = "STEP_$index")))
        }

        val snapshot = AndroidAuditTrail.snapshot()
        assertEquals(256, snapshot.size)
        assertTrue(snapshot.zipWithNext().all { (a, b) -> b.sequence > a.sequence })
        assertEquals(300L, snapshot.last().sequence)
    }

    @Test
    fun arbitraryTargetAndRemotePrincipalArePersistedOnlyAsReferences() {
        AndroidAuditTrail.installPersistenceForTests(FakePersistence())
        val secretTarget = "https://user:password@example.com/private?token=super-secret"
        val principal = "remote-agent@example.com"

        assertTrue(
            AndroidAuditTrail.record(
                input(AndroidAuditEventType.ACTION_DISPATCH_REQUESTED).copy(
                    principalKind = "AGENT",
                    principalId = principal,
                    targetScope = secretTarget
                )
            )
        )

        val event = AndroidAuditTrail.snapshot().single()
        assertTrue(event.principalRef.startsWith("sha256:"))
        assertTrue(event.targetRef.orEmpty().startsWith("sha256:"))
        assertFalse(event.principalRef.contains(principal))
        assertFalse(event.targetRef.orEmpty().contains("password"))
        assertFalse(event.targetRef.orEmpty().contains("super-secret"))
    }

    @Test
    fun packageTargetMayRemainReadableSafeScope() {
        AndroidAuditTrail.installPersistenceForTests(FakePersistence())

        AndroidAuditTrail.record(
            input(AndroidAuditEventType.ACTION_DISPATCH_REQUESTED).copy(targetScope = "com.example.safe")
        )

        assertEquals("com.example.safe", AndroidAuditTrail.snapshot().single().targetRef)
    }

    @Test
    fun writeFailureFailsClosedAndMarksTrailDegraded() {
        val store = FakePersistence(failSave = true)
        AndroidAuditTrail.installPersistenceForTests(store)

        assertFalse(AndroidAuditTrail.record(input(AndroidAuditEventType.PLAN_ACCEPTED)))
        assertTrue(AndroidAuditTrail.snapshot().isEmpty())
        assertTrue(AndroidAuditTrail.isDegraded())
    }

    @Test
    fun corruptOrInvalidLoadedStateFailsClosed() {
        val invalid = sampleEvent(sequence = 1L).copy(createdAtEpochMs = -1L)
        val store = FakePersistence(stored = listOf(invalid))

        AndroidAuditTrail.installPersistenceForTests(store)

        assertTrue(AndroidAuditTrail.snapshot().isEmpty())
        assertTrue(AndroidAuditTrail.isDegraded())
        assertTrue(store.stored.isEmpty())
    }

    @Test
    fun schemaVersionGuardAcceptsCurrentAndRejectsUnknown() {
        AndroidAuditTrailSchema.requireSupportedVersion(AndroidAuditTrailSchema.VERSION)

        assertThrows(IllegalStateException::class.java) {
            AndroidAuditTrailSchema.requireSupportedVersion(AndroidAuditTrailSchema.VERSION + 1)
        }
    }

    @Test
    fun securityOperationalEventCoverageIsRepresentable() {
        AndroidAuditTrail.installPersistenceForTests(FakePersistence())
        val required = setOf(
            AndroidAuditEventType.MISSION_SUBMITTED,
            AndroidAuditEventType.PLAN_ACCEPTED,
            AndroidAuditEventType.PERMISSION_REQUESTED,
            AndroidAuditEventType.PERMISSION_ALLOW_ONCE,
            AndroidAuditEventType.PERMISSION_ALWAYS_ALLOW,
            AndroidAuditEventType.PERMISSION_DENIED,
            AndroidAuditEventType.ACTION_DISPATCH_REQUESTED,
            AndroidAuditEventType.ACTION_DISPATCH_ACCEPTED,
            AndroidAuditEventType.VERIFICATION_PASSED,
            AndroidAuditEventType.VERIFICATION_FAILED,
            AndroidAuditEventType.MISSION_PAUSED,
            AndroidAuditEventType.MISSION_RESUMED,
            AndroidAuditEventType.MISSION_CANCELLED,
            AndroidAuditEventType.USER_TAKE_OVER,
            AndroidAuditEventType.UNDO_CHECKPOINT_RECORDED,
            AndroidAuditEventType.UNDO_VERIFIED,
            AndroidAuditEventType.MISSION_FAILED
        )

        required.forEach { assertTrue(AndroidAuditTrail.record(input(it))) }

        assertEquals(required, AndroidAuditTrail.snapshot().map { it.type }.toSet())
    }

    @Test
    fun auditKeyAliasIsSeparatedFromUndoJournalKey() {
        assertNotEquals("veltrix.ultron.undo.journal.v1", AUDIT_KEY_ALIAS)
    }

    private fun input(type: AndroidAuditEventType, result: String = "OK") = AndroidAuditEventInput(
        missionId = "mission-123",
        principalKind = "OWNER",
        principalId = "owner",
        deviceId = "android-local",
        type = type,
        capability = "phone.open_app",
        actionType = "OPEN_APP",
        targetScope = "com.example.target",
        resultCode = result
    )

    private fun sampleEvent(sequence: Long) = AndroidAuditEvent(
        id = "event-$sequence",
        sequence = sequence,
        missionId = "mission-123",
        principalKind = "OWNER",
        principalRef = "owner",
        deviceId = "android-local",
        type = AndroidAuditEventType.PLAN_ACCEPTED,
        capability = "phone.open_app",
        actionType = "OPEN_APP",
        targetRef = "com.example.target",
        resultCode = "OK",
        createdAtEpochMs = 1_700_000_000_000L + sequence
    )

    private class FakePersistence(
        var stored: List<AndroidAuditEvent> = emptyList(),
        private val failLoad: Boolean = false,
        private val failSave: Boolean = false
    ) : AuditTrailPersistence {
        override fun load(): List<AndroidAuditEvent> {
            if (failLoad) error("load failed")
            return stored
        }

        override fun save(events: List<AndroidAuditEvent>) {
            if (failSave) error("save failed")
            stored = events
        }

        override fun clear() {
            stored = emptyList()
        }
    }
}
