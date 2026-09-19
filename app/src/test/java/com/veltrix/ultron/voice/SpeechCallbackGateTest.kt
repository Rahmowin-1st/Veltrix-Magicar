package com.veltrix.ultron.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechCallbackGateTest {
    @Test
    fun `new generation rejects callbacks from replaced recognizer`() {
        val gate = SpeechCallbackGate()
        val first = gate.begin()
        val second = gate.begin()

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun `cancel invalidation rejects late result`() {
        val gate = SpeechCallbackGate()
        val active = gate.begin()

        gate.invalidate()

        assertFalse(gate.consume(active))
    }

    @Test
    fun `terminal callback can be consumed only once`() {
        val gate = SpeechCallbackGate()
        val active = gate.begin()

        assertTrue(gate.consume(active))
        assertFalse(gate.consume(active))
    }
}
