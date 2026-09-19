package com.veltrix.ultron.voice

/**
 * Monotonic one-shot gate for SpeechRecognizer callbacks.
 * A cancelled/replaced recognizer can never publish a late terminal result.
 */
internal class SpeechCallbackGate {
    private var generation: Long = 0L

    fun begin(): Long {
        if (generation == Long.MAX_VALUE) return 0L
        generation += 1L
        return generation
    }

    fun invalidate() {
        if (generation < Long.MAX_VALUE) generation += 1L
    }

    fun isCurrent(token: Long): Boolean = token > 0L && token == generation

    fun consume(token: Long): Boolean {
        if (!isCurrent(token)) return false
        invalidate()
        return true
    }
}
