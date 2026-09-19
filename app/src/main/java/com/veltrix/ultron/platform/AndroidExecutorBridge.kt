package com.veltrix.ultron.platform

import com.veltrix.ultron.core.ScreenObservation
import com.veltrix.ultron.core.ScreenObserver
import com.veltrix.ultron.executor.AccessibilityAction
import com.veltrix.ultron.executor.AccessibilityActionResult
import com.veltrix.ultron.executor.AccessibilityActionTransport

interface AndroidExecutorEndpoint {
    fun snapshot(): ScreenObservation
    fun dispatch(action: AccessibilityAction): AccessibilityActionResult
}

/**
 * Process-local bridge to the currently connected AccessibilityService.
 * If the service is unavailable, execution fails closed instead of queuing
 * privileged actions for later surprise execution.
 */
object AndroidExecutorBridge {
    @Volatile
    private var endpoint: AndroidExecutorEndpoint? = null

    fun attach(candidate: AndroidExecutorEndpoint) {
        endpoint = candidate
    }

    fun detach(candidate: AndroidExecutorEndpoint) {
        if (endpoint === candidate) endpoint = null
    }

    fun isConnected(): Boolean = endpoint != null

    val observer: ScreenObserver = ScreenObserver {
        endpoint?.snapshot() ?: ScreenObservation(
            packageName = null,
            visibleText = emptyList()
        )
    }

    val transport: AccessibilityActionTransport = AccessibilityActionTransport { action ->
        endpoint?.dispatch(action) ?: AccessibilityActionResult(
            accepted = false,
            action = action.type,
            message = "Android executor is not connected"
        )
    }
}
