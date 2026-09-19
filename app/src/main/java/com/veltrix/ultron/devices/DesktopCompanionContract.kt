package com.veltrix.ultron.devices

import java.time.Instant

data class DesktopCompanionHello(
    val deviceId: String,
    val platform: DevicePlatform,
    val companionVersion: String,
    val availableCapabilities: Set<DeviceCapability>,
    val grantedCapabilities: Set<DeviceCapability>,
    val publicIdentityThumbprint: String,
    val startedAt: Instant = Instant.now()
)

data class DesktopHeartbeat(
    val deviceId: String,
    val observedAt: Instant = Instant.now(),
    val foregroundApp: String? = null,
    val foregroundWindow: String? = null,
    val healthy: Boolean = true
)

data class DesktopEvidencePacket(
    val deviceId: String,
    val taskId: String,
    val evidence: List<String>,
    val createdAt: Instant = Instant.now()
)

/**
 * Transport abstraction for Windows/macOS/Linux companions. The real transport
 * can be local IPC or an authenticated encrypted gateway connection. Planner and
 * policy code never depends on the transport implementation.
 */
interface DesktopCompanionTransport {
    fun hello(): DesktopCompanionHello
    fun heartbeat(): DesktopHeartbeat
    fun observe(): DeviceObservation
    fun dispatch(action: UniversalAction): UniversalActionResult
}

class DesktopUniversalExecutorAdapter(
    private val transport: DesktopCompanionTransport
) : UniversalDeviceExecutorEndpoint {
    override val deviceId: String
        get() = transport.hello().deviceId

    override fun observe(): DeviceObservation = transport.observe()

    override fun dispatch(action: UniversalAction): UniversalActionResult = transport.dispatch(action)
}
