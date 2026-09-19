package com.veltrix.ultron.agents

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentGatewayContractTest {
    @Test
    fun taskIdentityKeepsPrincipalBoundToObjective() {
        val principal = Principal("frontend", PrincipalKind.AGENT, "Frontend Agent")
        val task = AgentTask(
            principal = principal,
            objective = "Open app and verify UI",
            requestedCapabilities = setOf("phone.open_app", "screen.verify")
        )

        assertEquals("frontend", task.principal.id)
        assertEquals(PrincipalKind.AGENT, task.principal.kind)
        assertEquals("Open app and verify UI", task.objective)
    }
}
