package com.veltrix.ultron.devices

enum class UniversalActionType {
    OPEN_APP,
    CLICK,
    TYPE_TEXT,
    SCROLL,
    TAP,
    BACK,
    HOME,
    WINDOW_FOCUS,
    KEYBOARD_SHORTCUT,
    BROWSER_NAVIGATE,
    FILE_READ,
    FILE_WRITE,
    FILE_UPLOAD,
    FILE_DOWNLOAD
}

data class UniversalAction(
    val type: UniversalActionType,
    val target: String? = null,
    val text: String? = null,
    val value: String? = null,
    val x: Float? = null,
    val y: Float? = null,
    val x2: Float? = null,
    val y2: Float? = null,
    val durationMs: Long? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class DeviceSemanticNode(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val text: String? = null,
    val hint: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val enabled: Boolean = false,
    val focused: Boolean = false
)

data class DeviceObservation(
    val deviceId: String,
    val foregroundApp: String? = null,
    val foregroundWindow: String? = null,
    val visibleText: List<String> = emptyList(),
    val uri: String? = null,
    val semanticNodes: List<DeviceSemanticNode> = emptyList(),
    val screenFingerprint: String? = null,
    val observedAtEpochMs: Long = System.currentTimeMillis()
)

data class UniversalActionResult(
    val accepted: Boolean,
    val action: UniversalActionType,
    val message: String,
    val evidence: Map<String, String> = emptyMap()
)

/** Implemented by Android, Windows, macOS or Linux local companions. */
interface UniversalDeviceExecutorEndpoint {
    val deviceId: String
    fun observe(): DeviceObservation
    fun dispatch(action: UniversalAction): UniversalActionResult
}

/**
 * Process-local endpoint registry. Cloud/device transports can provide remote
 * endpoint implementations later without changing planner semantics.
 */
class UniversalExecutorRegistry {
    private val endpoints = linkedMapOf<String, UniversalDeviceExecutorEndpoint>()

    @Synchronized
    fun attach(endpoint: UniversalDeviceExecutorEndpoint) {
        endpoints[endpoint.deviceId] = endpoint
    }

    @Synchronized
    fun detach(endpoint: UniversalDeviceExecutorEndpoint) {
        if (endpoints[endpoint.deviceId] === endpoint) endpoints.remove(endpoint.deviceId)
    }

    @Synchronized
    fun isConnected(deviceId: String): Boolean = endpoints.containsKey(deviceId)

    @Synchronized
    fun observe(deviceId: String): DeviceObservation? = endpoints[deviceId]?.observe()

    @Synchronized
    fun dispatch(deviceId: String, action: UniversalAction): UniversalActionResult =
        endpoints[deviceId]?.dispatch(action) ?: UniversalActionResult(
            accepted = false,
            action = action.type,
            message = "Device executor is not connected"
        )
}
