package com.veltrix.ultron.remote

/**
 * Process-local enrollment attempt arbiter.
 *
 * Concurrent callers join the same active attempt, terminal delivery happens once,
 * and stale late completions from an older attempt are ignored after cancellation
 * or a newer attempt starts.
 */
internal class SecureEnrollmentAttemptCoordinator<T>(
    initialSnapshot: SecureEnrollmentSnapshot
) {
    data class Start(
        val attemptId: Long,
        val started: Boolean
    )

    private var nextAttemptId = 0L
    private var activeAttemptId: Long? = null
    private val callbacks = mutableListOf<(Result<T>) -> Unit>()
    private var currentSnapshot = initialSnapshot

    @Synchronized
    fun snapshot(): SecureEnrollmentSnapshot = currentSnapshot

    @Synchronized
    fun joinIfActive(callback: (Result<T>) -> Unit): Long? {
        val active = activeAttemptId ?: return null
        callbacks += callback
        return active
    }

    @Synchronized
    fun start(callback: (Result<T>) -> Unit): Start {
        activeAttemptId?.let { active ->
            callbacks += callback
            return Start(active, false)
        }
        val attemptId = ++nextAttemptId
        activeAttemptId = attemptId
        callbacks += callback
        currentSnapshot = SecureEnrollmentSnapshot(SecureEnrollmentState.ENROLLING)
        return Start(attemptId, true)
    }

    @Synchronized
    fun isActive(attemptId: Long): Boolean = activeAttemptId == attemptId

    /**
     * Runs a local credential commit only while this attempt still owns enrollment.
     * Holding the coordinator monitor makes owner cancellation authoritative: cancel
     * either wins before this block and skips it, or waits and clears after it.
     */
    @Synchronized
    fun commitIfActive(attemptId: Long, block: () -> Unit): Boolean {
        if (activeAttemptId != attemptId) return false
        block()
        return true
    }

    @Synchronized
    fun complete(
        attemptId: Long,
        terminalSnapshot: SecureEnrollmentSnapshot
    ): List<(Result<T>) -> Unit> {
        if (activeAttemptId != attemptId) return emptyList()
        activeAttemptId = null
        currentSnapshot = terminalSnapshot
        val pending = callbacks.toList()
        callbacks.clear()
        return pending
    }

    @Synchronized
    fun cancelActive(idleSnapshot: SecureEnrollmentSnapshot = SecureEnrollmentSnapshot(SecureEnrollmentState.IDLE)):
        List<(Result<T>) -> Unit> {
        if (activeAttemptId == null) {
            currentSnapshot = idleSnapshot
            callbacks.clear()
            return emptyList()
        }
        activeAttemptId = null
        currentSnapshot = idleSnapshot
        val pending = callbacks.toList()
        callbacks.clear()
        return pending
    }

    @Synchronized
    fun markConfigured() {
        if (activeAttemptId == null) {
            currentSnapshot = SecureEnrollmentSnapshot(SecureEnrollmentState.CONFIGURED)
        }
    }
}
