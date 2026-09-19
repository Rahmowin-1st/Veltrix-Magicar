package com.veltrix.ultron.agents

import com.veltrix.ultron.devices.ControlProfile
import com.veltrix.ultron.runtime.AndroidAiCommandRuntime
import com.veltrix.ultron.runtime.CommandApproval
import com.veltrix.ultron.runtime.CommandOutcome
import com.veltrix.ultron.runtime.CommandOutcomeState
import java.time.Instant

interface AgentCommandRuntime {
    fun submit(
        sessionId: String,
        objective: String,
        principal: Principal,
        constraints: List<String>,
        controlProfileOverride: ControlProfile? = null
    ): CommandOutcome
    fun approve(sessionId: String, approval: CommandApproval): CommandOutcome
    fun pause(sessionId: String): CommandOutcome
    fun takeOver(sessionId: String): CommandOutcome
    fun resume(sessionId: String): CommandOutcome
    fun cancel(sessionId: String): CommandOutcome
}

class AndroidAgentCommandRuntime(
    private val delegate: AndroidAiCommandRuntime
) : AgentCommandRuntime {
    override fun submit(
        sessionId: String,
        objective: String,
        principal: Principal,
        constraints: List<String>,
        controlProfileOverride: ControlProfile?
    ): CommandOutcome = delegate.submitForPrincipal(
        objective = objective,
        principal = principal,
        constraints = constraints,
        sessionId = sessionId,
        controlProfileOverride = controlProfileOverride
    )

    override fun approve(sessionId: String, approval: CommandApproval): CommandOutcome =
        delegate.approve(sessionId, approval)

    override fun pause(sessionId: String): CommandOutcome = delegate.pause(sessionId)

    override fun takeOver(sessionId: String): CommandOutcome = delegate.takeOver(sessionId)

    override fun resume(sessionId: String): CommandOutcome = delegate.resume(sessionId)

    override fun cancel(sessionId: String): CommandOutcome = delegate.cancel(sessionId)
}

data class AgentTaskStatus(
    val taskId: String,
    val principal: Principal,
    val objective: String,
    val state: TaskState,
    val narration: String,
    val evidence: List<String>
)

/**
 * AgentGateway implementation backed by the same policy-bound ActionGraph runtime
 * used by owner commands. Requested capabilities remain advisory; actual execution
 * authority comes only from device grants + PlannerPolicyGate.
 *
 * Map mutations use a short private lock. Runtime/model/executor calls never hold
 * that lock, so owner Pause/Take Over/Cancel can win while submit or a callback is
 * in flight. The remote task id is also the planner session id, giving controls a
 * stable target before planner execution returns.
 */
class PlannerBackedAgentGateway(
    private val runtime: AgentCommandRuntime
) : AgentGateway {
    private val lock = Any()
    private val tasks = linkedMapOf<String, AgentTask>()
    private val receipts = linkedMapOf<String, AgentTaskReceipt>()
    private val idempotency = linkedMapOf<String, String>()
    private val sessionByTask = linkedMapOf<String, String>()

    override fun submit(task: AgentTask): AgentTaskReceipt {
        require(task.objective.isNotBlank()) { "Task objective must not be blank" }
        require(task.principal.id.isNotBlank()) { "Principal id must not be blank" }
        require(task.requestedCapabilities.size <= 64) { "Too many requested capabilities" }

        val reserved = synchronized(lock) {
            task.idempotencyKey?.takeIf(String::isNotBlank)?.let { key ->
                val dedupeKey = "${task.principal.id}|$key"
                idempotency[dedupeKey]?.let { existing -> return@synchronized Reservation.Existing(requireReceiptLocked(existing)) }
                idempotency[dedupeKey] = task.id
            }
            receipts[task.id]?.let { return@synchronized Reservation.Existing(it) }
            tasks[task.id] = task
            sessionByTask[task.id] = task.id
            val receipt = AgentTaskReceipt(
                taskId = task.id,
                state = TaskState.RECEIVED,
                narration = "Accepted",
                updatedAt = Instant.now()
            )
            receipts[task.id] = receipt
            Reservation.New(receipt)
        }
        if (reserved is Reservation.Existing) return reserved.receipt

        val outcome = runtime.submit(
            sessionId = task.id,
            objective = task.objective,
            principal = task.principal,
            constraints = task.constraints,
            controlProfileOverride = task.controlProfileOverride
        )
        return synchronized(lock) {
            val current = requireReceiptLocked(task.id)
            when {
                current.state == TaskState.CANCELLED -> current
                current.state == TaskState.PAUSED && outcome.state != CommandOutcomeState.CANCELLED -> current
                else -> outcome.toReceipt(task.id).also { next ->
                    receipts[task.id] = next
                    if (next.state.isTerminal()) sessionByTask.remove(task.id)
                }
            }
        }
    }

    override fun get(taskId: String): AgentTaskReceipt? = synchronized(lock) { receipts[taskId] }

    override fun principalForTask(taskId: String): Principal? = synchronized(lock) { tasks[taskId]?.principal }

    fun activeTaskStatuses(): List<AgentTaskStatus> = synchronized(lock) {
        tasks.values.mapNotNull { task ->
            val receipt = receipts[task.id] ?: return@mapNotNull null
            if (receipt.state.isTerminal()) return@mapNotNull null
            AgentTaskStatus(
                taskId = task.id,
                principal = task.principal,
                objective = task.objective,
                state = receipt.state,
                narration = receipt.narration,
                evidence = receipt.evidence
            )
        }
    }

    override fun pause(taskId: String): AgentTaskReceipt {
        val sessionId = synchronized(lock) {
            val current = requireReceiptLocked(taskId)
            require(!current.state.isTerminal()) { "Cannot pause terminal task" }
            if (current.state != TaskState.PAUSED) {
                receipts[taskId] = current.copy(
                    state = TaskState.PAUSED,
                    narration = "Paused",
                    updatedAt = Instant.now()
                )
            }
            sessionByTask[taskId] ?: taskId
        }
        val outcome = runtime.pause(sessionId)
        return reconcileControlOutcome(taskId, outcome, preservePaused = true)
    }

    override fun takeOver(taskId: String): AgentTaskReceipt {
        val sessionId = synchronized(lock) {
            val current = requireReceiptLocked(taskId)
            require(!current.state.isTerminal()) { "Cannot take over terminal task" }
            receipts[taskId] = current.copy(
                state = TaskState.PAUSED,
                narration = "Owner took control",
                updatedAt = Instant.now()
            )
            sessionByTask[taskId] ?: taskId
        }
        val outcome = runtime.takeOver(sessionId)
        return reconcileControlOutcome(taskId, outcome, preservePaused = true)
    }

    override fun cancel(taskId: String): AgentTaskReceipt {
        val sessionId = synchronized(lock) {
            val current = requireReceiptLocked(taskId)
            if (current.state == TaskState.VERIFIED_DONE || current.state == TaskState.CANCELLED) return current
            val cancelled = current.copy(
                state = TaskState.CANCELLED,
                narration = "Cancelled",
                updatedAt = Instant.now()
            )
            receipts[taskId] = cancelled
            sessionByTask[taskId] ?: taskId
        }
        runtime.cancel(sessionId)
        return synchronized(lock) { requireReceiptLocked(taskId) }
    }

    override fun resume(taskId: String): AgentTaskReceipt {
        val sessionId = synchronized(lock) {
            val current = requireReceiptLocked(taskId)
            require(current.state == TaskState.PAUSED) { "Only paused tasks can be resumed" }
            sessionByTask[taskId] ?: taskId
        }
        val outcome = runtime.resume(sessionId)
        return synchronized(lock) {
            val current = requireReceiptLocked(taskId)
            if (current.state == TaskState.CANCELLED) return@synchronized current
            outcome.toReceipt(taskId).also { next ->
                receipts[taskId] = next
                if (next.state.isTerminal()) sessionByTask.remove(taskId)
            }
        }
    }

    fun approve(taskId: String, approval: CommandApproval): AgentTaskReceipt {
        val sessionId = synchronized(lock) {
            val current = requireReceiptLocked(taskId)
            require(current.state == TaskState.WAITING_FOR_USER) {
                "Task is not waiting for approval"
            }
            requireNotNull(sessionByTask[taskId]) { "Task has no planner session" }
        }
        val outcome = runtime.approve(sessionId, approval)
        return synchronized(lock) {
            val current = requireReceiptLocked(taskId)
            if (current.state == TaskState.CANCELLED || current.state == TaskState.PAUSED) return@synchronized current
            outcome.toReceipt(taskId).also { next ->
                receipts[taskId] = next
                if (next.state.isTerminal()) sessionByTask.remove(taskId)
            }
        }
    }

    override fun capabilities(): GatewayCapabilities = GatewayCapabilities(
        operations = setOf("submit_task", "get_task", "pause_task", "resume_task", "cancel_task", "capabilities"),
        taskPrincipals = PrincipalKind.entries.toSet(),
        supportsIdempotency = true,
        supportsCancellation = true,
        supportsLongRunningTasks = true
    )

    private fun reconcileControlOutcome(
        taskId: String,
        outcome: CommandOutcome,
        preservePaused: Boolean
    ): AgentTaskReceipt = synchronized(lock) {
        val current = requireReceiptLocked(taskId)
        if (current.state == TaskState.CANCELLED) return@synchronized current
        val mapped = outcome.toReceipt(taskId)
        val next = if (preservePaused && mapped.state != TaskState.CANCELLED) {
            current.copy(
                state = TaskState.PAUSED,
                narration = mapped.narration,
                evidence = (current.evidence + mapped.evidence).distinct(),
                updatedAt = Instant.now()
            )
        } else mapped
        receipts[taskId] = next
        if (next.state.isTerminal()) sessionByTask.remove(taskId)
        next
    }

    private fun requireReceiptLocked(taskId: String): AgentTaskReceipt =
        requireNotNull(receipts[taskId]) { "Unknown task: $taskId" }

    private fun CommandOutcome.toReceipt(taskId: String): AgentTaskReceipt {
        val mapped = when (state) {
            CommandOutcomeState.NEEDS_APPROVAL -> TaskState.WAITING_FOR_USER
            CommandOutcomeState.PAUSED,
            CommandOutcomeState.TAKEN_OVER -> TaskState.PAUSED
            CommandOutcomeState.VERIFIED_DONE -> if (evidence.isNotEmpty()) TaskState.VERIFIED_DONE else TaskState.FAILED
            CommandOutcomeState.CANCELLED -> TaskState.CANCELLED
            CommandOutcomeState.FAILED,
            CommandOutcomeState.UNSUPPORTED -> TaskState.FAILED
        }
        val safeNarration = if (state == CommandOutcomeState.VERIFIED_DONE && evidence.isEmpty()) {
            "Proof-of-done evidence missing"
        } else message
        return AgentTaskReceipt(
            taskId = taskId,
            state = mapped,
            narration = safeNarration,
            evidence = evidence,
            updatedAt = Instant.now()
        )
    }

    private fun TaskState.isTerminal(): Boolean =
        this == TaskState.VERIFIED_DONE || this == TaskState.FAILED || this == TaskState.CANCELLED

    private sealed interface Reservation {
        data class Existing(val receipt: AgentTaskReceipt) : Reservation
        data class New(val receipt: AgentTaskReceipt) : Reservation
    }
}
