package com.veltrix.ultron.planner

import com.veltrix.ultron.devices.DeviceCapability
import com.veltrix.ultron.devices.DeviceDescriptor
import com.veltrix.ultron.devices.UniversalAction
import com.veltrix.ultron.devices.UniversalActionType

data class GraphValidationIssue(
    val nodeId: String?,
    val code: String,
    val message: String
)

data class GraphValidationResult(
    val valid: Boolean,
    val issues: List<GraphValidationIssue>
)

/**
 * Hard boundary between model output and device execution.
 * The model cannot relabel an action as a weaker capability or invent a capability
 * the owner did not grant on the selected device. Coordinate gesture fallback is
 * admitted only when the runtime currently exposes the one-shot SCREEN_CAPTURE
 * capability, which Android grants only for a fresh user-consented vision frame.
 */
class ActionGraphValidator(
    private val maxNodes: Int = 32
) {
    fun validate(graph: ActionGraph, device: DeviceDescriptor): GraphValidationResult {
        val issues = mutableListOf<GraphValidationIssue>()
        if (graph.objective.isBlank()) issues += issue(null, "EMPTY_OBJECTIVE", "Graph objective is required")
        if (graph.nodes.isEmpty()) issues += issue(null, "EMPTY_GRAPH", "Action graph must contain at least one node")
        if (graph.nodes.size > maxNodes) issues += issue(null, "GRAPH_TOO_LARGE", "Action graph exceeds $maxNodes nodes")

        val duplicateIds = graph.nodes.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        duplicateIds.forEach { id -> issues += issue(id, "DUPLICATE_NODE_ID", "Node id must be unique") }

        graph.nodes.forEach { node ->
            if (node.id.isBlank()) issues += issue(node.id, "EMPTY_NODE_ID", "Node id is required")
            if (node.description.isBlank()) issues += issue(node.id, "EMPTY_DESCRIPTION", "Node description is required")
            if (node.maxAttempts !in 1..5) issues += issue(node.id, "INVALID_ATTEMPTS", "maxAttempts must be 1..5")

            val canonical = node.action.type.defaultCapability()
            if (canonical != node.requiredCapability) {
                issues += issue(
                    node.id,
                    "CAPABILITY_MISMATCH",
                    "${node.action.type} requires $canonical, not ${node.requiredCapability}"
                )
            }
            if (canonical !in device.effectiveCapabilities) {
                issues += issue(
                    node.id,
                    "CAPABILITY_NOT_GRANTED",
                    "$canonical is not granted on ${device.displayName}"
                )
            }

            validateAction(node.id, node.action, node.targetScope, device, issues)
            validateVerification(node, issues)
            if (node.action.type == UniversalActionType.TAP && node.verification.mode == VerificationMode.ACTION_ACCEPTED) {
                issues += issue(
                    node.id,
                    "TAP_VERIFICATION_TOO_WEAK",
                    "Coordinate TAP requires observable post-action verification"
                )
            }
        }

        return GraphValidationResult(valid = issues.isEmpty(), issues = issues)
    }

    private fun validateAction(
        nodeId: String,
        action: UniversalAction,
        targetScope: String?,
        device: DeviceDescriptor,
        issues: MutableList<GraphValidationIssue>
    ) {
        when (action.type) {
            UniversalActionType.OPEN_APP -> {
                requireText(nodeId, action.target, "OPEN_APP_TARGET", "OPEN_APP requires a target app/package", issues)
                if (!targetScope.isNullOrBlank() && action.target?.trim() != targetScope.trim()) {
                    issues += issue(nodeId, "TARGET_SCOPE_MISMATCH", "OPEN_APP target must match its declared target scope")
                }
            }
            UniversalActionType.CLICK ->
                requireText(nodeId, action.text ?: action.target, "CLICK_TARGET", "CLICK requires visible text or a target", issues)
            UniversalActionType.TYPE_TEXT -> {
                requireText(nodeId, action.target, "TYPE_TARGET", "TYPE_TEXT requires an input target", issues)
                if (action.value == null && action.text == null) {
                    issues += issue(nodeId, "TYPE_VALUE", "TYPE_TEXT requires a value")
                }
            }
            UniversalActionType.TAP -> {
                if (DeviceCapability.SCREEN_CAPTURE !in device.effectiveCapabilities) {
                    issues += issue(
                        nodeId,
                        "TAP_REQUIRES_FRESH_VISION",
                        "Coordinate gesture requires a fresh user-consented vision frame"
                    )
                }
                val x = action.x
                val y = action.y
                if (x == null || y == null || !x.isFinite() || !y.isFinite() || x < 0f || y < 0f || x > 100_000f || y > 100_000f) {
                    issues += issue(nodeId, "TAP_COORDINATES", "TAP requires bounded finite non-negative x/y coordinates")
                }
                val gesture = action.metadata["gesture"]?.lowercase()
                if (gesture != null && gesture !in setOf("tap", "double_tap", "long_press", "swipe", "drag", "drag_drop")) {
                    issues += issue(nodeId, "GESTURE_KIND", "Unsupported gesture metadata")
                }
                if (gesture in setOf("swipe", "drag", "drag_drop")) {
                    val x2 = action.x2
                    val y2 = action.y2
                    if (x2 == null || y2 == null || !x2.isFinite() || !y2.isFinite() || x2 < 0f || y2 < 0f || x2 > 100_000f || y2 > 100_000f) {
                        issues += issue(nodeId, "GESTURE_END_COORDINATES", "Swipe/drag requires bounded finite x2/y2 coordinates")
                    }
                }
                if (action.durationMs != null && action.durationMs !in 40L..8_000L) {
                    issues += issue(nodeId, "GESTURE_DURATION", "Gesture duration must be between 40 and 8000 ms")
                }
            }
            UniversalActionType.BROWSER_NAVIGATE -> {
                val target = action.target?.trim().orEmpty()
                if (!target.startsWith("https://") && !target.startsWith("http://localhost") && !target.startsWith("http://127.0.0.1")) {
                    issues += issue(nodeId, "UNSAFE_URI", "Browser navigation requires HTTPS or loopback HTTP")
                }
            }
            UniversalActionType.FILE_READ,
            UniversalActionType.FILE_WRITE,
            UniversalActionType.FILE_UPLOAD,
            UniversalActionType.FILE_DOWNLOAD,
            UniversalActionType.WINDOW_FOCUS ->
                requireText(nodeId, action.target, "ACTION_TARGET", "${action.type} requires a target", issues)
            UniversalActionType.KEYBOARD_SHORTCUT -> {
                val keys = action.metadata["keys"]?.trim().orEmpty()
                if (keys.isEmpty()) issues += issue(nodeId, "SHORTCUT_KEYS", "KEYBOARD_SHORTCUT requires metadata.keys")
            }
            UniversalActionType.SCROLL,
            UniversalActionType.BACK,
            UniversalActionType.HOME -> Unit
        }
    }

    private fun validateVerification(node: ActionGraphNode, issues: MutableList<GraphValidationIssue>) {
        when (node.verification.mode) {
            VerificationMode.APP,
            VerificationMode.WINDOW,
            VerificationMode.TEXT_PRESENT,
            VerificationMode.TEXT_ABSENT,
            VerificationMode.URI_PREFIX -> {
                if (node.verification.expected.isNullOrBlank()) {
                    issues += issue(node.id, "VERIFICATION_EXPECTED", "${node.verification.mode} requires expected value")
                }
            }
            VerificationMode.CONTENT_CHANGED,
            VerificationMode.ACTION_ACCEPTED -> Unit
        }
        if (node.verification.description.isBlank()) {
            issues += issue(node.id, "VERIFICATION_DESCRIPTION", "Verification description is required")
        }
    }

    private fun requireText(
        nodeId: String,
        value: String?,
        code: String,
        message: String,
        issues: MutableList<GraphValidationIssue>
    ) {
        if (value.isNullOrBlank()) issues += issue(nodeId, code, message)
    }

    private fun issue(nodeId: String?, code: String, message: String) =
        GraphValidationIssue(nodeId = nodeId, code = code, message = message)
}
