package com.veltrix.ultron.gateway

import com.veltrix.ultron.agents.AgentGateway
import com.veltrix.ultron.agents.AgentTask
import com.veltrix.ultron.agents.Principal
import com.veltrix.ultron.devices.AuthenticatedExecutionContext
import com.veltrix.ultron.devices.ControlProfile
import com.veltrix.ultron.devices.DeviceCapability
import com.veltrix.ultron.devices.DeviceDescriptor
import com.veltrix.ultron.devices.DeviceRegistry
import com.veltrix.ultron.devices.DeviceRouteState
import com.veltrix.ultron.devices.DeviceTargetPreference
import com.veltrix.ultron.devices.UniversalTaskIntent
import com.veltrix.ultron.devices.UniversalTaskRouter

/**
 * Identity is established by MCP transport auth/OAuth middleware, never by tool arguments.
 */
data class UltronMcpSession(
    val principal: Principal,
    val deviceOwnerPrincipalId: String,
    val allowedDeviceIds: Set<String> = emptySet()
)

data class UltronMcpCall(
    val tool: String,
    val arguments: Map<String, String> = emptyMap()
)

data class UltronMcpResult(
    val ok: Boolean,
    val state: String,
    val message: String,
    val taskId: String? = null,
    val deviceId: String? = null,
    val evidence: List<String> = emptyList(),
    val data: Map<String, String> = emptyMap(),
    val ownerApprovalRequired: Boolean = false
)

/** Dedicated MCP surface for AI agents, tools and external automation. */
class UltronMcpGateway(
    private val agentGateway: AgentGateway,
    private val deviceRegistry: DeviceRegistry,
    private val router: UniversalTaskRouter = UniversalTaskRouter(deviceRegistry)
) {
    fun tools(): Set<String> = setOf(
        TOOL_SUBMIT_TASK,
        TOOL_GET_TASK,
        TOOL_PAUSE_TASK,
        TOOL_RESUME_TASK,
        TOOL_CANCEL_TASK,
        TOOL_LIST_DEVICES,
        TOOL_DEVICE_CAPABILITIES,
        TOOL_REQUEST_CONTROL,
        TOOL_CAPABILITIES
    )

    fun invoke(session: UltronMcpSession, call: UltronMcpCall): UltronMcpResult = when (call.tool) {
        TOOL_SUBMIT_TASK -> submit(session, call.arguments)
        TOOL_GET_TASK -> taskRead(session, call.arguments, Operation.GET)
        TOOL_PAUSE_TASK -> taskRead(session, call.arguments, Operation.PAUSE)
        TOOL_RESUME_TASK -> taskRead(session, call.arguments, Operation.RESUME)
        TOOL_CANCEL_TASK -> taskRead(session, call.arguments, Operation.CANCEL)
        TOOL_LIST_DEVICES -> listDevices(session)
        TOOL_DEVICE_CAPABILITIES -> deviceCapabilities(session, call.arguments)
        TOOL_REQUEST_CONTROL -> requestControl(session, call.arguments)
        TOOL_CAPABILITIES -> capabilities()
        else -> UltronMcpResult(false, "UNKNOWN_TOOL", "Unknown ULTRON MCP tool")
    }

    private fun submit(session: UltronMcpSession, args: Map<String, String>): UltronMcpResult {
        val objective = args["objective"]?.trim().orEmpty()
        if (objective.isEmpty()) return UltronMcpResult(false, "INVALID", "objective is required")

        val required = parseCapabilities(args["required_capabilities"])
        val targetPreference = when (args["target"]?.lowercase()) {
            null, "", "auto" -> DeviceTargetPreference.AUTO
            "phone" -> DeviceTargetPreference.PHONE
            "desktop" -> DeviceTargetPreference.DESKTOP
            "cloud" -> DeviceTargetPreference.CLOUD
            else -> return UltronMcpResult(false, "INVALID", "target must be auto, phone, desktop or cloud")
        }

        val route = router.route(
            auth = AuthenticatedExecutionContext(
                principal = session.principal,
                deviceOwnerPrincipalId = session.deviceOwnerPrincipalId,
                allowedDeviceIds = session.allowedDeviceIds
            ),
            task = UniversalTaskIntent(
                objective = objective,
                requiredCapabilities = required,
                targetPreference = targetPreference,
                targetDeviceId = args["device_id"]?.takeIf(String::isNotBlank)
            )
        )

        if (route.state == DeviceRouteState.DENIED) {
            return UltronMcpResult(false, "DENIED", route.message)
        }

        val receipt = agentGateway.submit(
            AgentTask(
                principal = session.principal,
                objective = objective,
                constraints = args["constraints"]
                    ?.split('\n')
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)
                    ?: emptyList(),
                requestedCapabilities = required.mapTo(linkedSetOf()) { capability ->
                    "device.${capability.name.lowercase()}"
                },
                idempotencyKey = args["idempotency_key"]?.takeIf(String::isNotBlank),
                metadata = buildMap {
                    route.device?.id?.let { put("device_id", it) }
                    put("device_route_state", route.state.name)
                    put("device_owner_principal", session.deviceOwnerPrincipalId)
                }
            )
        )

        return UltronMcpResult(
            ok = true,
            state = if (route.state == DeviceRouteState.READY) receipt.state.name else "WAITING_FOR_DEVICE",
            message = route.message,
            taskId = receipt.taskId,
            deviceId = route.device?.id,
            evidence = receipt.evidence
        )
    }

    private enum class Operation { GET, PAUSE, RESUME, CANCEL }

    private fun taskRead(
        session: UltronMcpSession,
        args: Map<String, String>,
        operation: Operation
    ): UltronMcpResult {
        val taskId = args["task_id"]?.trim().orEmpty()
        if (taskId.isEmpty()) return UltronMcpResult(false, "INVALID", "task_id is required")

        val owner = agentGateway.principalForTask(taskId)
            ?: return UltronMcpResult(false, "NOT_FOUND", "Task not found")
        if (owner.id != session.principal.id) {
            return UltronMcpResult(false, "DENIED", "Task belongs to another authenticated principal")
        }

        val receipt = runCatching {
            when (operation) {
                Operation.GET -> agentGateway.get(taskId)
                Operation.PAUSE -> agentGateway.pause(taskId)
                Operation.RESUME -> agentGateway.resume(taskId)
                Operation.CANCEL -> agentGateway.cancel(taskId)
            }
        }.getOrElse { error ->
            return UltronMcpResult(false, "FAILED", error.message ?: "Task operation failed")
        } ?: return UltronMcpResult(false, "NOT_FOUND", "Task not found")

        return UltronMcpResult(
            ok = true,
            state = receipt.state.name,
            message = receipt.narration,
            taskId = receipt.taskId,
            evidence = receipt.evidence
        )
    }

    private fun listDevices(session: UltronMcpSession): UltronMcpResult {
        val visible = deviceRegistry.listForOwner(session.deviceOwnerPrincipalId)
            .filter { session.allowedDeviceIds.isEmpty() || it.id in session.allowedDeviceIds }
        return UltronMcpResult(
            ok = true,
            state = "OK",
            message = "${visible.size} device(s)",
            data = visible.associate { device ->
                device.id to "${device.kind}:${device.platform}:${device.presence}:${device.controlProfile}"
            }
        )
    }

    private fun deviceCapabilities(session: UltronMcpSession, args: Map<String, String>): UltronMcpResult {
        val device = authorizedDevice(session, args["device_id"])
            ?: return UltronMcpResult(false, "DENIED", "Device is not visible to this authenticated session")
        return UltronMcpResult(
            ok = true,
            state = "OK",
            message = device.displayName,
            deviceId = device.id,
            data = mapOf(
                "kind" to device.kind.name,
                "platform" to device.platform.name,
                "presence" to device.presence.name,
                "control_profile" to device.controlProfile.name,
                "available" to device.availableCapabilities.joinToString(",") { it.name },
                "granted" to device.grantedCapabilities.joinToString(",") { it.name },
                "effective" to device.effectiveCapabilities.joinToString(",") { it.name }
            )
        )
    }

    private fun requestControl(session: UltronMcpSession, args: Map<String, String>): UltronMcpResult {
        val deviceId = args["device_id"]?.trim().orEmpty()
        val device = authorizedDevice(session, deviceId)
            ?: return UltronMcpResult(false, "DENIED", "Device is not visible to this authenticated session")
        val requested = when (args["profile"]?.uppercase()) {
            "READ_ONLY" -> ControlProfile.READ_ONLY
            "ASK_EACH_ACTION" -> ControlProfile.ASK_EACH_ACTION
            "MAX_APPROVED" -> ControlProfile.MAX_APPROVED
            else -> return UltronMcpResult(false, "INVALID", "Unknown control profile")
        }
        val receipt = deviceRegistry.requestControlProfile(
            authenticatedPrincipalId = session.principal.id,
            deviceId = device.id,
            profile = requested,
            reason = args["reason"] ?: "Requested through ULTRON MCP"
        )
        return UltronMcpResult(
            ok = true,
            state = if (receipt.ownerApprovalRequired) "WAITING_FOR_OWNER" else "UPDATED",
            message = receipt.message,
            deviceId = receipt.deviceId,
            ownerApprovalRequired = receipt.ownerApprovalRequired,
            data = mapOf("effective_profile" to receipt.profile.name)
        )
    }

    private fun capabilities(): UltronMcpResult = UltronMcpResult(
        ok = true,
        state = "OK",
        message = "ULTRON dedicated MCP",
        data = mapOf(
            "tools" to tools().sorted().joinToString(","),
            "raw_executor_exposed" to "false",
            "supports_phone" to "true",
            "supports_desktop" to "true",
            "supports_long_tasks" to agentGateway.capabilities().supportsLongRunningTasks.toString()
        )
    )

    private fun authorizedDevice(session: UltronMcpSession, rawId: String?): DeviceDescriptor? {
        val id = rawId?.trim().orEmpty()
        if (id.isEmpty()) return null
        val device = deviceRegistry.get(id) ?: return null
        if (device.ownerPrincipalId != session.deviceOwnerPrincipalId) return null
        if (session.allowedDeviceIds.isNotEmpty() && id !in session.allowedDeviceIds) return null
        return device
    }

    private fun parseCapabilities(raw: String?): Set<DeviceCapability> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(',')
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull { name -> DeviceCapability.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
            .toSet()
    }

    companion object {
        const val TOOL_SUBMIT_TASK = "ultron.submit_task"
        const val TOOL_GET_TASK = "ultron.get_task"
        const val TOOL_PAUSE_TASK = "ultron.pause_task"
        const val TOOL_RESUME_TASK = "ultron.resume_task"
        const val TOOL_CANCEL_TASK = "ultron.cancel_task"
        const val TOOL_LIST_DEVICES = "ultron.list_devices"
        const val TOOL_DEVICE_CAPABILITIES = "ultron.device_capabilities"
        const val TOOL_REQUEST_CONTROL = "ultron.request_control"
        const val TOOL_CAPABILITIES = "ultron.capabilities"
    }
}
