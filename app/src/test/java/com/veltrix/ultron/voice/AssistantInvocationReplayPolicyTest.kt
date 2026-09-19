package com.veltrix.ultron.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantInvocationReplayPolicyTest {
    @Test
    fun `issued sequence advances monotonically`() {
        assertEquals(1L, AssistantInvocationReplayPolicy.nextIssuedSequence(0L))
        assertEquals(42L, AssistantInvocationReplayPolicy.nextIssuedSequence(41L))
    }

    @Test
    fun `newer invocation is consumed`() {
        assertTrue(AssistantInvocationReplayPolicy.shouldConsume(2L, 1L))
    }

    @Test
    fun `same and older invocation are rejected as replay`() {
        assertFalse(AssistantInvocationReplayPolicy.shouldConsume(2L, 2L))
        assertFalse(AssistantInvocationReplayPolicy.shouldConsume(1L, 2L))
    }

    @Test
    fun `invalid or corrupted sequence fails closed`() {
        assertFalse(AssistantInvocationReplayPolicy.shouldConsume(0L, 0L))
        assertFalse(AssistantInvocationReplayPolicy.shouldConsume(-1L, 0L))
        assertFalse(AssistantInvocationReplayPolicy.shouldConsume(1L, -1L))
        assertNull(AssistantInvocationReplayPolicy.nextIssuedSequence(-1L))
    }

    @Test
    fun `sequence overflow fails closed`() {
        assertNull(AssistantInvocationReplayPolicy.nextIssuedSequence(Long.MAX_VALUE))
    }
}
