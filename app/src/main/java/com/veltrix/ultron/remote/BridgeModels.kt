package com.veltrix.ultron.remote

import com.veltrix.ultron.devices.ControlProfile

data class BridgeCapabilitySnapshot(
    val platform: String,
    val availableCapabilities: Set<String>,
    val grantedCapabilities: Set<String>
)

enum class RemotePrincipalKind { OWNER, USER, AGENT, SYSTEM }

enum class RemoteTaskState {
    RECEIVED,
    RUNNING,
    WAITING_FOR_USER,
    WAITING_FOR_DEVICE,
    PAUSED,
    VERIFIED_DONE,
    FAILED,
    CANCELLED
}

data class RemoteTaskLease(
    val taskId: String,
    val principalId: String,
    val principalKind: RemotePrincipalKind,
    val principalDisplayName: String,
    val objective: String,
    val constraints: List<String>,
    val requiredCapabilities: Set<String>,
    val controlProfile: ControlProfile
)

data class RemoteTaskControl(
    val taskId: String,
    val state: RemoteTaskState,
    val narration: String
)

data class RemoteTaskReport(
    val state: RemoteTaskState,
    val narration: String,
    val evidence: List<String> = emptyList()
)

interface RemoteBridgeTransport {
    fun heartbeat(snapshot: BridgeCapabilitySnapshot)
    fun claimTask(): RemoteTaskLease?
    /**
     * Battery-friendly wait primitive. Transports without long-poll support keep
     * compatibility by falling back to one immediate claim.
     */
    fun waitForTask(timeoutMs: Int): RemoteTaskLease? = claimTask()
    fun inspectTask(taskId: String): RemoteTaskControl
    fun reportTask(taskId: String, report: RemoteTaskReport)
}
