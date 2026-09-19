package com.veltrix.ultron.planner

enum class PlannerControlState {
    RUNNING,
    PAUSED,
    TAKEN_OVER,
    CANCELLED
}

data class PlannerControlSnapshot(
    val state: PlannerControlState,
    val generation: Long,
    val reason: String? = null
)

fun interface PlannerExecutionControlGate {
    fun snapshot(sessionId: String): PlannerControlSnapshot
}

/**
 * Thread-safe cooperative control registry. Control transitions intentionally do
 * not share the planner execution lock, so owner Pause/Cancel/Take Over can win
 * while an executor callback or settle window is still in flight.
 */
class PlannerExecutionControlRegistry : PlannerExecutionControlGate {
    private val lock = Any()
    private val states = linkedMapOf<String, PlannerControlSnapshot>()

    fun register(sessionId: String): PlannerControlSnapshot = synchronized(lock) {
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
        states.getOrPut(sessionId) { running(generation = 0L) }
    }

    fun pause(sessionId: String, reason: String = "Paused by owner"): PlannerControlSnapshot =
        transition(sessionId, PlannerControlState.PAUSED, reason)

    fun takeOver(sessionId: String, reason: String = "Owner took control"): PlannerControlSnapshot =
        transition(sessionId, PlannerControlState.TAKEN_OVER, reason)

    fun cancel(sessionId: String, reason: String = "Cancelled by owner"): PlannerControlSnapshot =
        transition(sessionId, PlannerControlState.CANCELLED, reason)

    fun resume(sessionId: String): PlannerControlSnapshot = synchronized(lock) {
        val current = states[sessionId] ?: running(generation = 0L)
        when (current.state) {
            PlannerControlState.CANCELLED -> current
            PlannerControlState.RUNNING -> current
            PlannerControlState.PAUSED,
            PlannerControlState.TAKEN_OVER -> running(generation = current.generation + 1L)
                .also { states[sessionId] = it }
        }
    }

    override fun snapshot(sessionId: String): PlannerControlSnapshot = synchronized(lock) {
        states[sessionId] ?: running(generation = 0L)
    }

    fun clear(sessionId: String) {
        synchronized(lock) { states.remove(sessionId) }
    }

    private fun transition(
        sessionId: String,
        target: PlannerControlState,
        reason: String
    ): PlannerControlSnapshot = synchronized(lock) {
        val current = states[sessionId] ?: running(generation = 0L)
        if (current.state == PlannerControlState.CANCELLED) return@synchronized current
        if (current.state == target) return@synchronized current
        val next = PlannerControlSnapshot(
            state = target,
            generation = current.generation + 1L,
            reason = reason.trim().take(160).ifBlank { target.name }
        )
        states[sessionId] = next
        next
    }

    private fun running(generation: Long): PlannerControlSnapshot =
        PlannerControlSnapshot(PlannerControlState.RUNNING, generation)
}

internal val AlwaysRunningPlannerExecutionControl = PlannerExecutionControlGate {
    PlannerControlSnapshot(PlannerControlState.RUNNING, generation = 0L)
}
