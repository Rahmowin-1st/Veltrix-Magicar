package com.veltrix.ultron.executor

data class KillSwitchSnapshot(
    val active: Boolean,
    val reason: String? = null,
    val changedAtEpochMs: Long = System.currentTimeMillis()
)

class ExecutionKillSwitch {
    private var current = KillSwitchSnapshot(active = false)

    @Synchronized
    fun activate(reason: String = "User stop"): KillSwitchSnapshot {
        current = KillSwitchSnapshot(active = true, reason = reason)
        return current
    }

    @Synchronized
    fun reset(): KillSwitchSnapshot {
        current = KillSwitchSnapshot(active = false)
        return current
    }

    @Synchronized
    fun snapshot(): KillSwitchSnapshot = current
}
