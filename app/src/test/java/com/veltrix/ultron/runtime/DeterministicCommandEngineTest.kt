package com.veltrix.ultron.runtime

import com.veltrix.ultron.core.ScreenObservation
import com.veltrix.ultron.executor.AccessibilityAction
import com.veltrix.ultron.executor.AccessibilityActionResult
import com.veltrix.ultron.executor.AccessibilityActionType
import com.veltrix.ultron.platform.AndroidExecutorBridge
import com.veltrix.ultron.platform.AndroidExecutorEndpoint
import com.veltrix.ultron.platform.AndroidUndoJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicCommandEngineTest {
    @Test
    fun openSettingsRequiresPermissionThenVerifiesAndConsumesAllowOnce() {
        val endpoint = StatefulEndpoint()
        AndroidUndoJournal.clear()
        AndroidExecutorBridge.attach(endpoint)
        try {
            val engine = DeterministicCommandEngine(settleMs = 0L)

            val first = engine.submit("open settings")
            assertEquals(CommandOutcomeState.NEEDS_APPROVAL, first.state)
            assertEquals("phone.open_app", first.capability)
            assertEquals("com.android.settings", first.target)

            val approved = engine.approve(
                missionId = requireNotNull(first.missionId),
                approval = CommandApproval.ALLOW_ONCE
            )
            assertEquals(CommandOutcomeState.VERIFIED_DONE, approved.state)
            assertTrue(approved.evidence.contains("package:com.android.settings"))
            assertEquals(1, endpoint.dispatchCount)

            endpoint.packageName = "com.veltrix.ultron"
            val second = engine.submit("open settings")
            assertEquals(CommandOutcomeState.NEEDS_APPROVAL, second.state)
            assertEquals(1, endpoint.dispatchCount)
        } finally {
            AndroidExecutorBridge.detach(endpoint)
            AndroidUndoJournal.clear()
        }
    }

    @Test
    fun verifiedOpenAppCanBeUndoneThroughPermissionAndVerificationGate() {
        val endpoint = StatefulEndpoint(packageName = "com.example.first")
        AndroidUndoJournal.clear()
        AndroidExecutorBridge.attach(endpoint)
        try {
            val engine = DeterministicCommandEngine(settleMs = 0L)

            val open = engine.submit("open package com.example.second")
            assertEquals(CommandOutcomeState.NEEDS_APPROVAL, open.state)
            val opened = engine.approve(requireNotNull(open.missionId), CommandApproval.ALLOW_ONCE)
            assertEquals(CommandOutcomeState.VERIFIED_DONE, opened.state)
            assertEquals("com.example.second", endpoint.packageName)
            assertTrue(engine.canUndo())
            assertEquals(1, endpoint.dispatchCount)

            val undo = engine.submit("undo")
            assertEquals(CommandOutcomeState.NEEDS_APPROVAL, undo.state)
            assertEquals("phone.open_app", undo.capability)
            assertEquals("com.example.first", undo.target)
            assertEquals(1, endpoint.dispatchCount)

            val restored = engine.approve(requireNotNull(undo.missionId), CommandApproval.ALLOW_ONCE)
            assertEquals(CommandOutcomeState.VERIFIED_DONE, restored.state)
            assertEquals("Undo verified", restored.message)
            assertTrue(restored.evidence.contains("package:com.example.first"))
            assertTrue(restored.evidence.contains("rollback:verified"))
            assertEquals("com.example.first", endpoint.packageName)
            assertEquals(2, endpoint.dispatchCount)
            assertFalse(engine.canUndo())
        } finally {
            AndroidExecutorBridge.detach(endpoint)
            AndroidUndoJournal.clear()
        }
    }

    @Test
    fun cancelNeverDispatches() {
        val endpoint = StatefulEndpoint()
        AndroidUndoJournal.clear()
        AndroidExecutorBridge.attach(endpoint)
        try {
            val engine = DeterministicCommandEngine(settleMs = 0L)
            val pending = engine.submit("open settings")

            val cancelled = engine.cancel(requireNotNull(pending.missionId))

            assertEquals(CommandOutcomeState.CANCELLED, cancelled.state)
            assertEquals(0, endpoint.dispatchCount)
        } finally {
            AndroidExecutorBridge.detach(endpoint)
            AndroidUndoJournal.clear()
        }
    }

    private class StatefulEndpoint(
        var packageName: String = "com.veltrix.ultron"
    ) : AndroidExecutorEndpoint {
        var dispatchCount: Int = 0

        override fun snapshot(): ScreenObservation = ScreenObservation(
            packageName = packageName,
            className = if (packageName == "com.android.settings") "Settings" else "MainActivity",
            visibleText = listOf(packageName)
        )

        override fun dispatch(action: AccessibilityAction): AccessibilityActionResult {
            dispatchCount += 1
            if (action.type == AccessibilityActionType.OPEN_APP) {
                packageName = action.packageName ?: packageName
            }
            return AccessibilityActionResult(
                accepted = true,
                action = action.type,
                message = "dispatched",
                evidence = mapOf("package" to packageName)
            )
        }
    }
}
