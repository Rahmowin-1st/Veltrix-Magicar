package com.veltrix.ultron.remote

import com.veltrix.ultron.agents.AgentGateway
import com.veltrix.ultron.agents.AgentTask
import com.veltrix.ultron.agents.AgentTaskReceipt
import com.veltrix.ultron.agents.Principal
import com.veltrix.ultron.agents.PrincipalKind
import com.veltrix.ultron.agents.TaskState

data class BridgeSyncResult(
    val claimedTaskId: String? = null,
    val trackedTasks: Int,
    val terminalTasks: List<String> = emptyList()
)

enum class LocalControlIntent { PAUSE, TAKE_OVER, RESUME, CANCEL }

private enum class LocalControlPhase { STARTED, READY }

private data class PendingLocalControl(
    val intent: LocalControlIntent,
    val phase: LocalControlPhase,
    val resumePreambleSent: Boolean = false
)

/**
 * Objective-level bridge only. Remote requested capabilities are advisory and
 * never become Android permissions. Every claimed task still enters the normal
 * Mission -> Permission -> Executor -> Verification pipeline through AgentGateway.
 *
 * Owner-local controls are fenced before touching the gateway. While such a
 * control is in flight, stale cloud state is not allowed to reverse it. The
 * resulting local receipt is pushed to the cloud before normal reconciliation
 * resumes. This preserves owner > agent authority across concurrent long-poll
 * and UI/voice control paths.
 */
class RemoteBridgeCoordinator(
    private val transport: RemoteBridgeTransport,
    private val gateway: AgentGateway
) {
    private val tracked = linkedSetOf<String>()
    private val pendingLocalControls = linkedMapOf<String, PendingLocalControl>()

    @Synchronized
    fun sync(snapshot: BridgeCapabilitySnapshot): BridgeSyncResult {
        transport.heartbeat(snapshot)
        val terminal = reconcileTracked().toMutableList()
        return processLease(transport.claimTask(), terminal)
    }

    /**
     * Keeps the network wait outside the coordinator monitor so approval/cancel
     * paths are not blocked by a 25-second long-poll request.
     */
    fun waitAndSync(snapshot: BridgeCapabilitySnapshot, timeoutMs: Int = 25_000): BridgeSyncResult {
        transport.heartbeat(snapshot)
        val terminalBeforeWait = synchronized(this) { reconcileTracked().toMutableList() }
        val lease = transport.waitForTask(timeoutMs)
        return synchronized(this) { processLease(lease, terminalBeforeWait) }
    }

    @Synchronized
    fun syncTrackedOnly(): BridgeSyncResult {
        val terminal = reconcileTracked()
        return BridgeSyncResult(trackedTasks = tracked.size, terminalTasks = terminal)
    }

    fun pauseLocal(taskId: String): AgentTaskReceipt =
        runLocalControl(taskId, LocalControlIntent.PAUSE) { gateway.pause(taskId) }

    fun takeOverLocal(taskId: String): AgentTaskReceipt =
        runLocalControl(taskId, LocalControlIntent.TAKE_OVER) { gateway.takeOver(taskId) }

    fun resumeLocal(taskId: String): AgentTaskReceipt =
        runLocalControl(taskId, LocalControlIntent.RESUME) { gateway.resume(taskId) }

    fun cancelLocal(taskId: String): AgentTaskReceipt =
        runLocalControl(taskId, LocalControlIntent.CANCEL) { gateway.cancel(taskId) }

    private fun runLocalControl(
        taskId: String,
        intent: LocalControlIntent,
        action: () -> AgentTaskReceipt
    ): AgentTaskReceipt {
        synchronized(this) {
            requireNotNull(gateway.get(taskId)) { "Unknown local task: $taskId" }
            pendingLocalControls[taskId] = PendingLocalControl(intent, LocalControlPhase.STARTED)
        }

        val receipt = try {
            action()
        } catch (error: Throwable) {
            synchronized(this) { pendingLocalControls.remove(taskId) }
            throw error
        }

        synchronized(this) {
            pendingLocalControls[taskId] = PendingLocalControl(intent, LocalControlPhase.READY)
            // Local owner authority is already effective. A network/reporting
            // failure must not make the local safety control appear to fail.
            // Keep the pending fence so the next sync retries before accepting
            // any stale remote state.
            runCatching { flushPendingLocalControl(taskId) }
        }
        return receipt
    }

    private fun processLease(lease: RemoteTaskLease?, terminal: MutableList<String>): BridgeSyncResult {
        if (lease != null && tracked.add(lease.taskId)) {
            gateway.submit(
                AgentTask(
                    id = lease.taskId,
                    principal = Principal(
                        id = lease.principalId,
                        kind = lease.principalKind.toLocalKind(),
                        displayName = lease.principalDisplayName
                    ),
                    objective = lease.objective,
                    constraints = lease.constraints,
                    requestedCapabilities = lease.requiredCapabilities,
                    controlProfileOverride = lease.controlProfile,
                    idempotencyKey = lease.taskId,
                    metadata = mapOf("transport" to "remote-bridge")
                )
            )
            terminal += reconcileOne(lease.taskId)
        }

        return BridgeSyncResult(
            claimedTaskId = lease?.taskId,
            trackedTasks = tracked.size,
            terminalTasks = terminal.filter { it.isNotEmpty() }.distinct()
        )
    }

    private fun reconcileTracked(): List<String> =
        tracked.toList().mapNotNull { taskId -> reconcileOne(taskId).takeIf { it.isNotEmpty() } }

    private fun reconcileOne(taskId: String): String {
        val pending = pendingLocalControls[taskId]
        if (pending != null) {
            if (pending.phase == LocalControlPhase.STARTED) return ""
            val local = gateway.get(taskId)
            if (local == null) {
                pendingLocalControls.remove(taskId)
                tracked.remove(taskId)
                return taskId
            }
            if (runCatching { flushPendingLocalControl(taskId) }.isFailure) return ""
            return if (local.state.isTerminal()) taskId else ""
        }

        val remote = transport.inspectTask(taskId)
        var local = gateway.get(taskId) ?: return ""

        when (remote.state) {
            RemoteTaskState.PAUSED -> if (local.state != TaskState.PAUSED && !local.state.isTerminal()) {
                local = gateway.pause(taskId)
            }
            RemoteTaskState.CANCELLED -> {
                if (!local.state.isTerminal()) gateway.cancel(taskId)
                tracked.remove(taskId)
                return taskId
            }
            RemoteTaskState.RUNNING -> if (local.state == TaskState.PAUSED) {
                local = gateway.resume(taskId)
            }
            RemoteTaskState.VERIFIED_DONE,
            RemoteTaskState.FAILED -> {
                tracked.remove(taskId)
                return taskId
            }
            else -> Unit
        }

        val report = local.toRemoteReport()
        if (report.state == RemoteTaskState.VERIFIED_DONE && report.evidence.isEmpty()) {
            throw IllegalStateException("Local VERIFIED_DONE task must contain evidence")
        }
        transport.reportTask(taskId, report)
        if (local.state.isTerminal()) {
            tracked.remove(taskId)
            return taskId
        }
        return ""
    }

    /** Caller must hold this coordinator's monitor. */
    private fun flushPendingLocalControl(taskId: String): Boolean {
        var pending = pendingLocalControls[taskId] ?: return true
        if (pending.phase != LocalControlPhase.READY) return false
        val local = gateway.get(taskId) ?: run {
            pendingLocalControls.remove(taskId)
            tracked.remove(taskId)
            return true
        }

        if (pending.intent == LocalControlIntent.RESUME && !pending.resumePreambleSent) {
            transport.reportTask(
                taskId,
                RemoteTaskReport(
                    state = RemoteTaskState.RUNNING,
                    narration = "Resumed by device owner",
                    evidence = local.evidence
                )
            )
            pending = pending.copy(resumePreambleSent = true
            )
            pendingLocalControls[taskId] = pending
        }

        val report = local.toRemoteReport()
        if (report.state == RemoteTaskState.VERIFIED_DONE && report.evidence.isEmpty()) {
            throw IllegalStateException("Local VERIFIED_DONE task must contain evidence")
        }
        transport.reportTask(taskId, report)
        pendingLocalControls.remove(taskId)
        if (local.state.isTerminal()) tracked.remove(taskId)
        return true
    }

    private fun RemotePrincipalKind.toLocalKind(): PrincipalKind = when (this) {
        RemotePrincipalKind.OWNER -> PrincipalKind.OWNER
        RemotePrincipalKind.USER -> PrincipalKind.USER
        RemotePrincipalKind.AGENT -> PrincipalKind.AGENT
        RemotePrincipalKind.SYSTEM -> PrincipalKind.SYSTEM
    }

    private fun AgentTaskReceipt.toRemoteReport(): RemoteTaskReport =
        RemoteTaskReport(
            state = when (state) {
                TaskState.RECEIVED, TaskState.RUNNING -> RemoteTaskState.RUNNING
                TaskState.WAITING_FOR_USER -> RemoteTaskState.WAITING_FOR_USER
                TaskState.PAUSED -> RemoteTaskState.PAUSED
                TaskState.VERIFIED_DONE -> RemoteTaskState.VERIFIED_DONE
                TaskState.FAILED -> RemoteTaskState.FAILED
                TaskState.CANCELLED -> RemoteTaskState.CANCELLED
            },
            narration = narration.ifBlank { state.name },
            evidence = evidence
        )

    private fun TaskState.isTerminal(): Boolean =
        this == TaskState.VERIFIED_DONE || this == TaskState.FAILED || this == TaskState.CANCELLED
}
