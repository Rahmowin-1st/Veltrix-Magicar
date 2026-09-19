package com.veltrix.ultron.executor

import com.veltrix.ultron.core.PermissionDecision
import com.veltrix.ultron.core.PermissionEngine
import com.veltrix.ultron.core.PermissionRule
import org.junit.Assert.assertEquals
import org.junit.Test

class PolicyBoundExecutorTest {
    @Test
    fun askDoesNotDispatchToAndroidTransport() {
        var dispatchCount = 0
        val executor = PolicyBoundExecutor(
            permissionEngine = PermissionEngine(),
            killSwitch = ExecutionKillSwitch(),
            transport = AccessibilityActionTransport { action ->
                dispatchCount += 1
                AccessibilityActionResult(true, action.type, "dispatched")
            }
        )

        val result = executor.execute(
            PolicyExecutionRequest(
                sourceId = "agent",
                targetPackage = "com.example",
                action = AccessibilityAction(AccessibilityActionType.TAP, x = 10f, y = 20f)
            )
        )

        assertEquals(PolicyExecutionState.NEEDS_USER_APPROVAL, result.state)
        assertEquals(0, dispatchCount)
    }

    @Test
    fun allowOnceDispatchesExactlyOnce() {
        var dispatchCount = 0
        val engine = PermissionEngine(
            listOf(
                PermissionRule(
                    sourceId = "owner",
                    capability = "phone.tap",
                    target = "com.example",
                    decision = PermissionDecision.ALLOW_ONCE
                )
            )
        )
        val executor = PolicyBoundExecutor(
            permissionEngine = engine,
            killSwitch = ExecutionKillSwitch(),
            transport = AccessibilityActionTransport { action ->
                dispatchCount += 1
                AccessibilityActionResult(true, action.type, "dispatched")
            }
        )
        val request = PolicyExecutionRequest(
            sourceId = "owner",
            targetPackage = "com.example",
            action = AccessibilityAction(AccessibilityActionType.TAP, x = 10f, y = 20f)
        )

        assertEquals(PolicyExecutionState.DISPATCHED, executor.execute(request).state)
        assertEquals(PolicyExecutionState.NEEDS_USER_APPROVAL, executor.execute(request).state)
        assertEquals(1, dispatchCount)
    }

    @Test
    fun openAppCannotEscapeItsPermissionTarget() {
        var dispatchCount = 0
        val engine = PermissionEngine(
            listOf(
                PermissionRule(
                    sourceId = "owner",
                    capability = "phone.open_app",
                    target = "com.example.allowed",
                    decision = PermissionDecision.ALWAYS_ALLOW
                )
            )
        )
        val executor = PolicyBoundExecutor(
            permissionEngine = engine,
            killSwitch = ExecutionKillSwitch(),
            transport = AccessibilityActionTransport { action ->
                dispatchCount += 1
                AccessibilityActionResult(true, action.type, "dispatched")
            }
        )

        val result = executor.execute(
            PolicyExecutionRequest(
                sourceId = "owner",
                targetPackage = "com.example.allowed",
                action = AccessibilityAction(
                    type = AccessibilityActionType.OPEN_APP,
                    packageName = "com.example.other"
                )
            )
        )

        assertEquals(PolicyExecutionState.DENIED, result.state)
        assertEquals(0, dispatchCount)
    }

    @Test
    fun denyNeverDispatches() {
        var dispatchCount = 0
        val engine = PermissionEngine(
            listOf(
                PermissionRule(
                    sourceId = null,
                    capability = "phone.home",
                    target = null,
                    decision = PermissionDecision.DENY
                )
            )
        )
        val executor = PolicyBoundExecutor(
            permissionEngine = engine,
            killSwitch = ExecutionKillSwitch(),
            transport = AccessibilityActionTransport { action ->
                dispatchCount += 1
                AccessibilityActionResult(true, action.type, "dispatched")
            }
        )

        val result = executor.execute(
            PolicyExecutionRequest(
                sourceId = "agent",
                targetPackage = null,
                action = AccessibilityAction(AccessibilityActionType.GLOBAL_HOME)
            )
        )

        assertEquals(PolicyExecutionState.DENIED, result.state)
        assertEquals(0, dispatchCount)
    }

    @Test
    fun killSwitchBlocksWithoutConsumingAllowOnce() {
        var dispatchCount = 0
        val engine = PermissionEngine(
            listOf(
                PermissionRule(
                    sourceId = "owner",
                    capability = "phone.tap",
                    target = "com.example",
                    decision = PermissionDecision.ALLOW_ONCE
                )
            )
        )
        val killSwitch = ExecutionKillSwitch().apply { activate("Emergency stop") }
        val executor = PolicyBoundExecutor(
            permissionEngine = engine,
            killSwitch = killSwitch,
            transport = AccessibilityActionTransport { action ->
                dispatchCount += 1
                AccessibilityActionResult(true, action.type, "dispatched")
            }
        )
        val request = PolicyExecutionRequest(
            sourceId = "owner",
            targetPackage = "com.example",
            action = AccessibilityAction(AccessibilityActionType.TAP, x = 10f, y = 20f)
        )

        assertEquals(PolicyExecutionState.KILL_SWITCHED, executor.execute(request).state)
        assertEquals(0, dispatchCount)

        killSwitch.reset()
        assertEquals(PolicyExecutionState.DISPATCHED, executor.execute(request).state)
        assertEquals(1, dispatchCount)
    }
}
