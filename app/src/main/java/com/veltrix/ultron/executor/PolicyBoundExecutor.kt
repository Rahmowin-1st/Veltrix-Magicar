package com.veltrix.ultron.executor

import com.veltrix.ultron.core.PermissionDecision
import com.veltrix.ultron.core.PermissionEngine

fun interface AccessibilityActionTransport {
    fun dispatch(action: AccessibilityAction): AccessibilityActionResult
}

enum class PolicyExecutionState {
    DISPATCHED,
    NEEDS_USER_APPROVAL,
    DENIED,
    KILL_SWITCHED
}

data class PolicyExecutionRequest(
    val sourceId: String,
    val targetPackage: String?,
    val action: AccessibilityAction
)

data class PolicyExecutionResult(
    val state: PolicyExecutionState,
    val permissionDecision: PermissionDecision,
    val capability: String,
    val actionResult: AccessibilityActionResult? = null,
    val message: String? = null
)

/**
 * Mandatory policy boundary between mission planning and Android execution.
 * A shared ExecutionKillSwitch must be injected by the app runtime.
 */
class PolicyBoundExecutor(
    private val permissionEngine: PermissionEngine,
    private val killSwitch: ExecutionKillSwitch,
    private val transport: AccessibilityActionTransport
) {
    fun execute(request: PolicyExecutionRequest): PolicyExecutionResult {
        val capability = request.action.type.capability()
        val killState = killSwitch.snapshot()
        if (killState.active) {
            return PolicyExecutionResult(
                state = PolicyExecutionState.KILL_SWITCHED,
                permissionDecision = PermissionDecision.DENY,
                capability = capability,
                message = killState.reason ?: "Execution stopped"
            )
        }

        if (
            request.action.type == AccessibilityActionType.OPEN_APP &&
            request.action.packageName?.trim() != request.targetPackage?.trim()
        ) {
            return PolicyExecutionResult(
                state = PolicyExecutionState.DENIED,
                permissionDecision = PermissionDecision.DENY,
                capability = capability,
                message = "OPEN_APP target must match the permission scope"
            )
        }

        val decision = permissionEngine.authorizeAndConsume(
            sourceId = request.sourceId,
            capability = capability,
            target = request.targetPackage
        )

        return when (decision) {
            PermissionDecision.ALLOW_ONCE,
            PermissionDecision.ALWAYS_ALLOW -> PolicyExecutionResult(
                state = PolicyExecutionState.DISPATCHED,
                permissionDecision = decision,
                capability = capability,
                actionResult = transport.dispatch(request.action)
            )

            PermissionDecision.ASK_EVERY_TIME -> PolicyExecutionResult(
                state = PolicyExecutionState.NEEDS_USER_APPROVAL,
                permissionDecision = decision,
                capability = capability
            )

            PermissionDecision.DENY -> PolicyExecutionResult(
                state = PolicyExecutionState.DENIED,
                permissionDecision = decision,
                capability = capability
            )
        }
    }

    private fun AccessibilityActionType.capability(): String = when (this) {
        AccessibilityActionType.OPEN_APP -> "phone.open_app"
        AccessibilityActionType.CLICK_TEXT -> "phone.click"
        AccessibilityActionType.SET_TEXT_BY_VIEW_ID,
        AccessibilityActionType.SET_TEXT_SEMANTIC -> "phone.type"
        AccessibilityActionType.SCROLL_FORWARD,
        AccessibilityActionType.SCROLL_BACKWARD -> "phone.scroll"
        AccessibilityActionType.GLOBAL_BACK -> "phone.back"
        AccessibilityActionType.GLOBAL_HOME -> "phone.home"
        AccessibilityActionType.GLOBAL_RECENTS -> "phone.recents"
        AccessibilityActionType.TAP -> "phone.tap"
        AccessibilityActionType.DOUBLE_TAP,
        AccessibilityActionType.LONG_PRESS,
        AccessibilityActionType.SWIPE,
        AccessibilityActionType.DRAG -> "phone.gesture"
    }
}
