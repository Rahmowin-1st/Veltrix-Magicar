package com.veltrix.ultron.agents

import com.veltrix.ultron.core.InterruptCommand
import com.veltrix.ultron.core.MissionCoordinator
import com.veltrix.ultron.core.MissionRequest
import com.veltrix.ultron.core.MissionSource
import com.veltrix.ultron.core.MissionSourceKind
import com.veltrix.ultron.core.MissionState
import com.veltrix.ultron.devices.ControlProfile
import java.time.Instant
import java.util.UUID

enum class PrincipalKind { OWNER, USER, AGENT, SYSTEM }
enum class TaskState { RECEIVED, RUNNING, WAITING_FOR_USER, PAUSED, VERIFIED_DONE, FAILED, CANCELLED }

data class Principal(
    val id: String,
    val kind: PrincipalKind,
    val displayName: String
)

data class AgentTask(
    val id: String = UUID.randomUUID().toString(),
    val principal: Principal,
    val objective: String,
    val constraints: List<String> = emptyList(),
    /** Advisory only. Requested capabilities never grant permission by themselves. */
    val requestedCapabilities: Set<String> = emptySet(),
    /** Trusted bridge policy ceiling. Untrusted task text must never populate this field. */
    val controlProfileOverride: ControlProfile? = null,
    val idempotencyKey: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Instant = Instant.now()
)

data class AgentTaskReceipt(
    val taskId: String,
    val state: TaskState,
    val narration: String,
    val evidence: List<String> = emptyList(),
    val updatedAt: Instant = Instant.now()
)

data class GatewayCapabilities(
    val operations: Set<String>,
    val taskPrincipals: Set<PrincipalKind>,
    val supportsIdempotency: Boolean,
    val supportsCancellation: Boolean,
    val supportsLongRunningTasks: Boolean
)

interface AgentGateway {
    fun submit(task: AgentTask): AgentTaskReceipt
    fun get(taskId: String): AgentTaskReceipt?
    /** Auth adapters use this to enforce principal isolation before task reads/mutations. */
    fun principalForTask(taskId: String): Principal?
    fun pause(taskId: String): AgentTaskReceipt
    /** Owner-local control. Remote protocol adapters intentionally do not expose this as an agent tool. */
    fun takeOver(taskId: String): AgentTaskReceipt
    fun cancel(taskId: String): AgentTaskReceipt
    fun resume(taskId: String): AgentTaskReceipt
    fun capabilities(): GatewayCapabilities
}

/**
 * Canonical in-process gateway. Remote HTTP/MCP adapters terminate into this
 * contract instead of talking to Android executors directly.
 *
 * Security invariant: a remote principal can request an objective/capability,
 * but execution still flows through Mission + Permission + Verification.
 */
class MissionBackedAgentGateway(
    private val coordinator: MissionCoordinator = MissionCoordinator()
) : AgentGateway {
    private val tasks = linkedMapOf<String, AgentTask>()
    private val idempotency = linkedMapOf<String, String>()

    @Synchronized
    override fun submit(task: AgentTask): AgentTaskReceipt {
        require(task.objective.isNotBlank()) { "Task objective must not be blank" }
        require(task.principal.id.isNotBlank()) { "Principal id must not be blank" }
        require(task.requestedCapabilities.size <= 64) { "Too many requested capabilities" }

        task.idempotencyKey?.takeIf { it.isNotBlank() }?.let { key ->
            val dedupeKey = "${task.principal.id}|$key"
            val existingId = idempotency[dedupeKey]
            if (existingId != null) return requireNotNull(get(existingId))
            idempotency[dedupeKey] = task.id
        }

        if (tasks.containsKey(task.id)) return requireNotNull(get(task.id))
        tasks[task.id] = task

        coordinator.receive(
            MissionRequest(
                objective = task.objective,
                source = MissionSource(
                    kind = task.principal.kind.toMissionSourceKind(),
                    id = task.principal.id,
                    displayName = task.principal.displayName
                ),
                constraints = task.constraints,
                id = task.id
            )
        )
        return requireNotNull(get(task.id))
    }

    @Synchronized
    override fun get(taskId: String): AgentTaskReceipt? {
        if (!tasks.containsKey(taskId)) return null
        val mission = coordinator.get(taskId) ?: return null
        return AgentTaskReceipt(
            taskId = taskId,
            state = mission.state.toTaskState(),
            narration = mission.statusMessage,
            evidence = mission.evidence
        )
    }

    @Synchronized
    override fun principalForTask(taskId: String): Principal? = tasks[taskId]?.principal

    @Synchronized
    override fun pause(taskId: String): AgentTaskReceipt {
        requireTask(taskId)
        coordinator.interrupt(taskId, InterruptCommand.PAUSE)
        return requireNotNull(get(taskId))
    }

    @Synchronized
    override fun takeOver(taskId: String): AgentTaskReceipt {
        requireTask(taskId)
        coordinator.interrupt(taskId, InterruptCommand.TAKE_OVER)
        return requireNotNull(get(taskId))
    }

    @Synchronized
    override fun cancel(taskId: String): AgentTaskReceipt {
        requireTask(taskId)
        coordinator.interrupt(taskId, InterruptCommand.CANCEL)
        return requireNotNull(get(taskId))
    }

    @Synchronized
    override fun resume(taskId: String): AgentTaskReceipt {
        requireTask(taskId)
        val current = requireNotNull(coordinator.get(taskId))
        require(current.state == MissionState.PAUSED || current.state == MissionState.WAITING_USER) {
            "Only paused/waiting tasks can be resumed"
        }
        coordinator.interrupt(taskId, InterruptCommand.RESUME)
        return requireNotNull(get(taskId))
    }

    override fun capabilities(): GatewayCapabilities = GatewayCapabilities(
        operations = setOf("submit_task", "get_task", "pause_task", "resume_task", "cancel_task", "capabilities"),
        taskPrincipals = PrincipalKind.entries.toSet(),
        supportsIdempotency = true,
        supportsCancellation = true,
        supportsLongRunningTasks = true
    )

    private fun requireTask(taskId: String): AgentTask =
        requireNotNull(tasks[taskId]) { "Unknown task: $taskId" }

    private fun PrincipalKind.toMissionSourceKind(): MissionSourceKind = when (this) {
        PrincipalKind.OWNER, PrincipalKind.USER -> MissionSourceKind.USER
        PrincipalKind.AGENT -> MissionSourceKind.AGENT
        PrincipalKind.SYSTEM -> MissionSourceKind.SYSTEM
    }

    private fun MissionState.toTaskState(): TaskState = when (this) {
        MissionState.RECEIVED, MissionState.UNDERSTANDING, MissionState.PLANNED -> TaskState.RECEIVED
        MissionState.EXECUTING, MissionState.VERIFYING -> TaskState.RUNNING
        MissionState.WAITING_PERMISSION, MissionState.WAITING_USER -> TaskState.WAITING_FOR_USER
        MissionState.PAUSED -> TaskState.PAUSED
        MissionState.DONE -> TaskState.VERIFIED_DONE
        MissionState.FAILED -> TaskState.FAILED
        MissionState.CANCELLED -> TaskState.CANCELLED
    }
}
