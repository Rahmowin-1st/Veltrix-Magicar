package com.veltrix.ultron.car

import java.util.UUID

enum class CarSessionState { IDLE, LISTENING, EXECUTING, VERIFYING }
enum class CarWakeSource { WAKE_WORD, ASSISTANT_INVOCATION, DIRECT_COMMAND, USER_APPROVAL, OVERLAY_TAP }

data class CarSessionSnapshot(
    val id: String?,
    val state: CarSessionState,
    val wakeSource: CarWakeSource?,
    val startedAtEpochMs: Long?,
    val lastTransitionAtEpochMs: Long
) {
    val active: Boolean get() = state != CarSessionState.IDLE && id != null
}

class CarSessionGate(
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {
    private var snapshot = CarSessionSnapshot(null, CarSessionState.IDLE, null, null, clock())

    @Synchronized
    fun begin(source: CarWakeSource): CarSessionSnapshot {
        val now = clock()
        if (snapshot.active) {
            snapshot = snapshot.copy(state = CarSessionState.LISTENING, wakeSource = source, lastTransitionAtEpochMs = now)
            return snapshot
        }
        snapshot = CarSessionSnapshot(idFactory(), CarSessionState.LISTENING, source, now, now)
        return snapshot
    }

    @Synchronized fun executing(): CarSessionSnapshot = transitionActive(CarSessionState.EXECUTING)
    @Synchronized fun verifying(): CarSessionSnapshot = transitionActive(CarSessionState.VERIFYING)

    @Synchronized
    fun finish(): CarSessionSnapshot {
        snapshot = CarSessionSnapshot(null, CarSessionState.IDLE, null, null, clock())
        return snapshot
    }

    @Synchronized fun current(): CarSessionSnapshot = snapshot
    @Synchronized fun allowsUiMutation(): Boolean = snapshot.active

    private fun transitionActive(target: CarSessionState): CarSessionSnapshot {
        if (!snapshot.active) return snapshot
        snapshot = snapshot.copy(state = target, lastTransitionAtEpochMs = clock())
        return snapshot
    }
}

object CarSessionRuntime {
    private val gate = CarSessionGate()

    fun beginUserSession(source: CarWakeSource): CarSessionSnapshot = gate.begin(source)
    fun markExecuting(): CarSessionSnapshot = gate.executing()
    fun markVerifying(): CarSessionSnapshot = gate.verifying()
    fun finish(): CarSessionSnapshot = gate.finish()
    fun snapshot(): CarSessionSnapshot = gate.current()
    fun allowsUiMutation(): Boolean = gate.allowsUiMutation()

    /** Boot/ACC restores readiness but never grants action authority. */
    fun noteSystemAwake(): CarSessionSnapshot = gate.current()
}
