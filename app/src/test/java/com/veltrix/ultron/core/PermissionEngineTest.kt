package com.veltrix.ultron.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionEngineTest {
    @Test
    fun specificAlwaysAllowRuleWinsForMatchingAgentAndTarget() {
        val engine = PermissionEngine(
            listOf(
                PermissionRule(
                    sourceId = "frontend-agent",
                    capability = "phone.open_app",
                    target = "com.example.target",
                    decision = PermissionDecision.ALWAYS_ALLOW
                )
            )
        )

        assertEquals(
            PermissionDecision.ALWAYS_ALLOW,
            engine.evaluate("frontend-agent", "phone.open_app", "com.example.target")
        )
        assertEquals(
            PermissionDecision.ASK_EVERY_TIME,
            engine.evaluate("other-agent", "phone.open_app", "com.example.target")
        )
    }

    @Test
    fun allowOnceIsConsumedAfterFirstAuthorization() {
        val engine = PermissionEngine(
            listOf(
                PermissionRule(
                    sourceId = "owner",
                    capability = "phone.tap",
                    target = "com.example.target",
                    decision = PermissionDecision.ALLOW_ONCE
                )
            )
        )

        assertEquals(
            PermissionDecision.ALLOW_ONCE,
            engine.authorizeAndConsume("owner", "phone.tap", "com.example.target")
        )
        assertEquals(
            PermissionDecision.ASK_EVERY_TIME,
            engine.authorizeAndConsume("owner", "phone.tap", "com.example.target")
        )
    }
}
