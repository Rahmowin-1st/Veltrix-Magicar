package com.veltrix.ultron.platform

import com.veltrix.ultron.core.ScreenObservation
import com.veltrix.ultron.executor.AccessibilityAction
import com.veltrix.ultron.executor.AccessibilityActionResult
import com.veltrix.ultron.executor.AccessibilityActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidExecutorBridgeTest {
    @Test
    fun disconnectedBridgeFailsClosed() {
        val endpoint = FakeEndpoint()
        AndroidExecutorBridge.detach(endpoint)

        val result = AndroidExecutorBridge.transport.dispatch(
            AccessibilityAction(AccessibilityActionType.GLOBAL_HOME)
        )

        assertFalse(result.accepted)
        assertEquals("Android executor is not connected", result.message)
    }

    @Test
    fun attachedEndpointProvidesObservationAndDispatchUntilDetached() {
        val endpoint = FakeEndpoint()
        AndroidExecutorBridge.attach(endpoint)

        assertTrue(AndroidExecutorBridge.isConnected())
        assertEquals("com.example", AndroidExecutorBridge.observer.observe().packageName)
        assertTrue(
            AndroidExecutorBridge.transport.dispatch(
                AccessibilityAction(AccessibilityActionType.GLOBAL_BACK)
            ).accepted
        )

        AndroidExecutorBridge.detach(endpoint)
        assertFalse(AndroidExecutorBridge.isConnected())
    }

    private class FakeEndpoint : AndroidExecutorEndpoint {
        override fun snapshot(): ScreenObservation = ScreenObservation(
            packageName = "com.example",
            className = "ExampleActivity",
            visibleText = listOf("Example")
        )

        override fun dispatch(action: AccessibilityAction): AccessibilityActionResult =
            AccessibilityActionResult(
                accepted = true,
                action = action.type,
                message = "fake dispatched"
            )
    }
}
