package com.veltrix.ultron.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGatewayV1Test {
    @Test
    fun samePrincipalAndIdempotencyKeyReturnsSameTask() {
        val gateway = MissionBackedAgentGateway()
        val principal = Principal("frontend", PrincipalKind.AGENT, "Frontend Agent")

        val first = gateway.submit(
            AgentTask(
                principal = principal,
                objective = "Open app and verify UI",
                idempotencyKey = "run-42"
            )
        )
        val second = gateway.submit(
            AgentTask(
                principal = principal,
                objective = "This duplicate objective must not create another task",
                idempotencyKey = "run-42"
            )
        )

        assertEquals(first.taskId, second.taskId)
        assertEquals(TaskState.RECEIVED, second.state)
    }

    @Test
    fun mcpAdapterUsesAuthenticatedPrincipalNotArguments() {
        val gateway = MissionBackedAgentGateway()
        val adapter = McpAgentGatewayAdapter(gateway)
        val authenticated = Principal("frontend", PrincipalKind.AGENT, "Frontend Agent")

        val result = adapter.call(
            authenticatedPrincipal = authenticated,
            toolName = UltronMcpTools.SUBMIT_TASK,
            arguments = mapOf(
                "objective" to "Inspect current screen",
                "principalId" to "owner",
                "requestedCapabilities" to listOf("screen.observe")
            )
        )

        assertTrue(result.ok)
        val taskId = result.data["taskId"] as String
        val receipt = gateway.get(taskId)
        assertEquals(TaskState.RECEIVED, receipt?.state)
    }

    @Test
    fun delegatedAllowOnceIsConsumedExactlyOnce() {
        val store = DelegatedPermissionStore()
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
            store.authorizeAndConsume("frontend", "telegram", "send_message", "medium")
        )
        assertEquals(
            DelegationDecision.ASK,
            store.authorizeAndConsume("frontend", "telegram", "send_message", "medium")
        )
        assertFalse(store.revoke("frontend", "telegram", "send_message", "medium"))
    }

    @Test
    fun gatewayAdvertisesTaskLevelOperationsOnly() {
        val operations = MissionBackedAgentGateway().capabilities().operations

        assertTrue("submit_task" in operations)
        assertTrue("cancel_task" in operations)
        assertFalse("tap" in operations)
        assertFalse("type_text" in operations)
    }
}
