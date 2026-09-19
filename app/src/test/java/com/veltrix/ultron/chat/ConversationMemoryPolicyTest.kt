package com.veltrix.ultron.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMemoryPolicyTest {
    @Test
    fun sanitizeRedactsCredentialsAndCodesBeforePersistence() {
        val raw = "Bearer abcdefghijklmnop sk-secret123 password=hunter2 code 123456 card 4111 1111 1111 1111"
        val safe = ConversationMemoryPolicy.sanitize(raw)

        assertFalse(safe.contains("abcdefghijklmnop"))
        assertFalse(safe.contains("sk-secret123"))
        assertFalse(safe.contains("hunter2"))
        assertFalse(safe.contains("123456"))
        assertFalse(safe.contains("4111 1111 1111 1111"))
        assertTrue(safe.contains("<redacted-token>"))
        assertTrue(safe.contains("<redacted-api-key>"))
        assertTrue(safe.contains("password=<redacted>"))
    }

    @Test
    fun boundedConversationKeepsOnlyMostRecentMessages() {
        val messages = (1..100).map { index ->
            ChatMessage(role = MessageRole.USER, text = "message-$index")
        }

        val bounded = ConversationMemoryPolicy.bounded(messages)

        assertEquals(ConversationMemoryPolicy.MAX_MESSAGES, bounded.size)
        assertEquals("message-21", bounded.first().text)
        assertEquals("message-100", bounded.last().text)
    }

    @Test
    fun plannerHintsAreBoundedAndNeverContainRawSensitiveValue() {
        val messages = (1..30).map { index ->
            ChatMessage(
                role = if (index % 2 == 0) MessageRole.VELTRIX else MessageRole.USER,
                text = if (index == 29) "token=supersecretvalue" else "message-$index"
            )
        }
        val hints = ConversationMemoryPolicy.plannerHints(
            messages = messages,
            followUp = FollowUpContext(lastIntent = "open Telegram"),
            limit = ConversationMemoryPolicy.MAX_HINTS
        )

        assertTrue(hints.size <= ConversationMemoryPolicy.MAX_HINTS)
        assertTrue(hints.any { it.contains("<redacted>") })
        assertFalse(hints.any { it.contains("supersecretvalue") })
    }
}
