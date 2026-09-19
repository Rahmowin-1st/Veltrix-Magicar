package com.veltrix.ultron.platform

import com.veltrix.ultron.core.ScreenObservation
import com.veltrix.ultron.executor.AccessibilityAction
import com.veltrix.ultron.executor.AccessibilityActionType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AndroidUndoJournalTest {
    @Before
    fun before() {
        AndroidUndoJournal.resetForTests()
    }

    @After
    fun after() {
        AndroidUndoJournal.resetForTests()
    }

    @Test
    fun verifiedOpenAppCreatesRestorableCheckpoint() {
        recordOpen("mission-open", "com.example.first", "com.example.second")

        val checkpoint = AndroidUndoJournal.latest()
        assertNotNull(checkpoint)
        assertEquals("com.example.second", checkpoint?.expectedCurrentPackage)
        assertEquals("com.example.first", checkpoint?.restorePackage)
    }

    @Test
    fun laterVerifiedNonReversibleActionInvalidatesCheckpoint() {
        recordOpen("mission-open", "com.example.first", "com.example.second")
        assertNotNull(AndroidUndoJournal.latest())

        AndroidUndoJournal.recordVerified(
            missionId = "mission-click",
            description = "Click destructive-unknown UI",
            action = AccessibilityAction(
                type = AccessibilityActionType.CLICK_TEXT,
                text = "Continue"
            ),
            before = ScreenObservation(packageName = "com.example.second"),
            after = ScreenObservation(packageName = "com.example.second")
        )

        assertNull(AndroidUndoJournal.latest())
    }

    @Test
    fun checkpointRequiresTwoValidDifferentPackages() {
        recordOpen("mission-noop", "com.example.same", "com.example.same")
        assertNull(AndroidUndoJournal.latest())
    }

    @Test
    fun onlyLatestExactCheckpointCanBeConsumed() {
        recordOpen("mission-open", "com.example.first", "com.example.second")
        val checkpoint = requireNotNull(AndroidUndoJournal.latest())

        assertFalse(AndroidUndoJournal.consume("wrong-id"))
        assertTrue(AndroidUndoJournal.consume(checkpoint.id))
        assertNull(AndroidUndoJournal.latest())
    }

    @Test
    fun persistedCheckpointRestoresAfterProcessRestart() {
        val store = FakePersistence()
        AndroidUndoJournal.installPersistenceForTests(store)
        recordOpen("mission-open", "com.example.first", "com.example.second")
        val original = requireNotNull(AndroidUndoJournal.latest())

        AndroidUndoJournal.simulateProcessRestartForTests()

        val restored = requireNotNull(AndroidUndoJournal.latest())
        assertEquals(original.id, restored.id)
        assertEquals("com.example.second", restored.expectedCurrentPackage)
        assertEquals("com.example.first", restored.restorePackage)
    }

    @Test
    fun consumedCheckpointDoesNotReturnAfterProcessRestart() {
        val store = FakePersistence()
        AndroidUndoJournal.installPersistenceForTests(store)
        recordOpen("mission-open", "com.example.first", "com.example.second")
        val checkpoint = requireNotNull(AndroidUndoJournal.latest())

        assertTrue(AndroidUndoJournal.consume(checkpoint.id))
        AndroidUndoJournal.simulateProcessRestartForTests()

        assertNull(AndroidUndoJournal.latest())
        assertTrue(store.stored.isEmpty())
    }

    @Test
    fun nonReversibleInvalidationDoesNotReturnAfterProcessRestart() {
        val store = FakePersistence()
        AndroidUndoJournal.installPersistenceForTests(store)
        recordOpen("mission-open", "com.example.first", "com.example.second")

        AndroidUndoJournal.recordVerified(
            missionId = "mission-click",
            description = "Click unknown state",
            action = AccessibilityAction(AccessibilityActionType.CLICK_TEXT, text = "Continue"),
            before = ScreenObservation(packageName = "com.example.second"),
            after = ScreenObservation(packageName = "com.example.second")
        )
        AndroidUndoJournal.simulateProcessRestartForTests()

        assertNull(AndroidUndoJournal.latest())
        assertTrue(store.stored.isEmpty())
    }

    @Test
    fun invalidDurableCheckpointFailsClosed() {
        val store = FakePersistence(
            listOf(
                AndroidUndoCheckpoint(
                    id = "checkpoint",
                    missionId = "mission",
                    description = "invalid",
                    expectedCurrentPackage = "not a package",
                    restorePackage = "com.example.first"
                )
            )
        )

        AndroidUndoJournal.installPersistenceForTests(store)

        assertNull(AndroidUndoJournal.latest())
        assertTrue(store.stored.isEmpty())
    }

    @Test
    fun persistenceWriteFailureDropsCheckpointInsteadOfLeavingStaleUndo() {
        val store = FakePersistence()
        AndroidUndoJournal.installPersistenceForTests(store)
        store.failSave = true

        recordOpen("mission-open", "com.example.first", "com.example.second")

        assertNull(AndroidUndoJournal.latest())
        assertTrue(store.stored.isEmpty())
    }

    @Test
    fun durableJournalRemainsBoundedToSixteenCheckpoints() {
        val store = FakePersistence()
        AndroidUndoJournal.installPersistenceForTests(store)

        repeat(20) { index ->
            recordOpen(
                missionId = "mission-$index",
                before = "com.example.app${index}",
                after = "com.example.app${index + 1}"
            )
        }

        assertEquals(16, store.stored.size)
        assertEquals("mission-4", store.stored.first().missionId)
        assertEquals("mission-19", store.stored.last().missionId)
    }

    private fun recordOpen(missionId: String, before: String, after: String) {
        AndroidUndoJournal.recordVerified(
            missionId = missionId,
            description = "Open second app",
            action = AccessibilityAction(
                type = AccessibilityActionType.OPEN_APP,
                packageName = after
            ),
            before = ScreenObservation(packageName = before),
            after = ScreenObservation(packageName = after)
        )
    }

    private class FakePersistence(initial: List<AndroidUndoCheckpoint> = emptyList()) : UndoJournalPersistence {
        var stored: List<AndroidUndoCheckpoint> = initial.map { it.copy() }
        var failSave: Boolean = false

        override fun load(): List<AndroidUndoCheckpoint> = stored.map { it.copy() }

        override fun save(checkpoints: List<AndroidUndoCheckpoint>) {
            if (failSave) error("simulated persistence failure")
            stored = checkpoints.map { it.copy() }
        }

        override fun clear() {
            stored = emptyList()
        }
    }
}