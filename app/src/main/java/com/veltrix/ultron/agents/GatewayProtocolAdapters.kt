package com.veltrix.ultron.agents

/** Canonical remote API routes. A cloud/desktop edge can expose these over HTTPS. */
object UltronTaskApi {
    const val SUBMIT = "POST /v1/tasks"
    const val GET = "GET /v1/tasks/{taskId}"
    const val PAUSE = "POST /v1/tasks/{taskId}/pause"
    const val RESUME = "POST /v1/tasks/{taskId}/resume"
    const val CANCEL = "POST /v1/tasks/{taskId}/cancel"
    const val CAPABILITIES = "GET /v1/capabilities"
}

/** MCP tool names intentionally mirror the canonical task API, not Android primitives. */
object UltronMcpTools {
    const val SUBMIT_TASK = "ultron.submit_task"
    const val GET_TASK = "ultron.get_task"
    const val PAUSE_TASK = "ultron.pause_task"
    const val RESUME_TASK = "ultron.resume_task"
    const val CANCEL_TASK = "ultron.cancel_task"
    const val CAPABILITIES = "ultron.capabilities"

    val all: Set<String> = setOf(
        SUBMIT_TASK,
        GET_TASK,
        PAUSE_TASK,
        RESUME_TASK,
        CANCEL_TASK,
        CAPABILITIES
    )
}

data class GatewayToolResult(
    val ok: Boolean,
    val data: Map<String, Any?> = emptyMap(),
    val error: String? = null
)

/**
 * Thin MCP-facing adapter.
 *
 * The authenticated principal is supplied by transport/auth middleware and is
 * never trusted from tool arguments. This prevents a remote agent from
 * impersonating another principal.
 */
class McpAgentGatewayAdapter(private val gateway: AgentGateway) {
    fun call(
        authenticatedPrincipal: Principal,
        toolName: String,
        arguments: Map<String, Any?> = emptyMap()
    ): GatewayToolResult = runCatching {
        when (toolName) {
            UltronMcpTools.SUBMIT_TASK -> submit(authenticatedPrincipal, arguments)
            UltronMcpTools.GET_TASK -> taskReceipt(requireTaskId(arguments))
            UltronMcpTools.PAUSE_TASK -> receipt(gateway.pause(requireTaskId(arguments)))
            UltronMcpTools.RESUME_TASK -> receipt(gateway.resume(requireTaskId(arguments)))
            UltronMcpTools.CANCEL_TASK -> receipt(gateway.cancel(requireTaskId(arguments)))
            UltronMcpTools.CAPABILITIES -> capabilities()
            else -> GatewayToolResult(ok = false, error = "Unknown tool: $toolName")
        }
    }.getOrElse { error ->
        GatewayToolResult(ok = false, error = error.message ?: error.javaClass.simpleName)
    }

    private fun submit(principal: Principal, arguments: Map<String, Any?>): GatewayToolResult {
        val objective = arguments["objective"] as? String
            ?: return GatewayToolResult(ok = false, error = "objective is required")
        val constraints = stringList(arguments["constraints"])
        val requestedCapabilities = stringList(arguments["requestedCapabilities"]).toSet()
        val idempotencyKey = (arguments["idempotencyKey"] as? String)?.takeIf { it.isNotBlank() }

        val task = AgentTask(
            principal = principal,
            objective = objective,
            constraints = constraints,
            requestedCapabilities = requestedCapabilities,
            idempotencyKey = idempotencyKey
        )
        return receipt(gateway.submit(task))
    }

    private fun taskReceipt(taskId: String): GatewayToolResult {
        val task = gateway.get(taskId)
            ?: return GatewayToolResult(ok = false, error = "Unknown task: $taskId")
        return receipt(task)
    }

    private fun receipt(receipt: AgentTaskReceipt): GatewayToolResult = GatewayToolResult(
        ok = true,
        data = mapOf(
            "taskId" to receipt.taskId,
            "state" to receipt.state.name,
            "narration" to receipt.narration,
            "evidence" to receipt.evidence,
            "updatedAt" to receipt.updatedAt.toString()
        )
    )

    private fun capabilities(): GatewayToolResult {
        val capabilities = gateway.capabilities()
        return GatewayToolResult(
            ok = true,
            data = mapOf(
                "operations" to capabilities.operations.sorted(),
                "taskPrincipals" to capabilities.taskPrincipals.map { it.name }.sorted(),
                "supportsIdempotency" to capabilities.supportsIdempotency,
                "supportsCancellation" to capabilities.supportsCancellation,
                "supportsLongRunningTasks" to capabilities.supportsLongRunningTasks
            )
        )
    }

    private fun requireTaskId(arguments: Map<String, Any?>): String =
        (arguments["taskId"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("taskId is required")

    private fun stringList(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is String -> listOf(value)
        is Iterable<*> -> value.mapNotNull { it as? String }
        else -> throw IllegalArgumentException("Expected string list")
    }
}
