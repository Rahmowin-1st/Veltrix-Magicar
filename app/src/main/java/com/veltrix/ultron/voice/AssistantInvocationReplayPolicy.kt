package com.veltrix.ultron.voice

/** Pure replay policy for transient assistant voice activation. */
object AssistantInvocationReplayPolicy {
    const val STORE_NAME = "veltrix_assistant_invocation_replay_v2"
    const val KEY_LAST_ISSUED_SEQUENCE = "last_issued_sequence"
    const val KEY_LAST_CONSUMED_SEQUENCE = "last_consumed_sequence"

    fun nextIssuedSequence(lastIssuedSequence: Long): Long? {
        if (lastIssuedSequence < 0L || lastIssuedSequence == Long.MAX_VALUE) return null
        return lastIssuedSequence + 1L
    }

    fun shouldConsume(invocationSequence: Long, lastConsumedSequence: Long): Boolean {
        if (invocationSequence <= 0L || lastConsumedSequence < 0L) return false
        return invocationSequence > lastConsumedSequence
    }
}
