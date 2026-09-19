package com.veltrix.ultron.agents

import org.junit.Assert.assertEquals
import org.junit.Test

class DelegatedPermissionStoreTest {
    @Test
    fun defaultsToAskAndResolvesScopedDecision() {
        val store = DelegatedPermissionStore()
        assertEquals(
            DelegationDecision.ASK,
            store.resolve("frontend", "telegram", "send_message", "medium")
        )

        store.put(
            DelegatedPermission(
                principalId = "frontend",
                appScope = "telegram",
                actionScope = "send_message",
                riskClass = "medium",
                decision = DelegationDecision.ALLOW_ONCE
            )
        )

        assertEquals(
            DelegationDecision.ALLOW_ONCE,
            store.resolve("frontend", "telegram", "send_message", "medium")
        )
        assertEquals(
            DelegationDecision.ASK,
            store.resolve("backend", "telegram", "send_message", "medium")
        )
    }
}
