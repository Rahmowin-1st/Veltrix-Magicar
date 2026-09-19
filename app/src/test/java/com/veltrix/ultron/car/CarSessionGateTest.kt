package com.veltrix.ultron.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarSessionGateTest {
    @Test
    fun `idle gate denies mutation until explicit user wake`() {
        val gate = CarSessionGate(clock = { 10L }, idFactory = { "s1" })
        assertFalse(gate.current().active)
        assertFalse(gate.allowsUiMutation())
        assertEquals(CarSessionState.IDLE, gate.current().state)
    }

    @Test
    fun `user wake opens bounded session and finish revokes it`() {
        var now = 100L
        val gate = CarSessionGate(clock = { now }, idFactory = { "s1" })
        assertEquals(CarSessionState.LISTENING, gate.begin(CarWakeSource.DIRECT_COMMAND).state)
        assertTrue(gate.allowsUiMutation())
        now = 110L
        assertEquals(CarSessionState.EXECUTING, gate.executing().state)
        now = 120L
        assertEquals(CarSessionState.VERIFYING, gate.verifying().state)
        now = 130L
        assertEquals(CarSessionState.IDLE, gate.finish().state)
        assertFalse(gate.allowsUiMutation())
    }
}
