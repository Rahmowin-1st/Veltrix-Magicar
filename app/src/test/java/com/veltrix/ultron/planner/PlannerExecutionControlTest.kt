package com.veltrix.ultron.planner

import com.veltrix.ultron.agents.Principal
import com.veltrix.ultron.agents.PrincipalKind
import com.veltrix.ultron.devices.ControlProfile
import com.veltrix.ultron.devices.DeviceCapability
import com.veltrix.ultron.devices.DeviceDescriptor
import com.veltrix.ultron.devices.DeviceKind
import com.veltrix.ultron.devices.DeviceObservation
import com.veltrix.ultron.devices.DevicePlatform
import com.veltrix.ultron.devices.DevicePresence
import com.veltrix.ultron.devices.UniversalAction
import com.veltrix.ultron.devices.UniversalActionResult
import com.veltrix.ultron.devices.UniversalActionType
import com.veltrix.ultron.devices.UniversalDeviceExecutorEndpoint
import com.veltrix.ultron.devices.UniversalExecutorRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerExecutionControlTest {
    private val owner = Principal("owner", PrincipalKind.OWNER, "Owner")
    private val device = DeviceDescriptor(
        id = "phone-control",
        ownerPrincipalId = "owner",
        kind = DeviceKind.PHONE,
        platform = DevicePlatform.ANDROID,
        displayName = "Phone",
        availableCapabilities = setOf(DeviceCapability.OPEN_APP),
        grantedCapabilities = setOf(DeviceCapability.OPEN_APP),
        controlProfile = ControlProfile.MAX_APPROVED,
        presence = DevicePresence.ONLINE
    )

    @Test
    fun registryUsesMonotonicGenerationsAndCancelIsTerminal() {
        val control = PlannerExecutionControlRegistry()

        val initial = control.register("session")
        val paused = control.pause("session")
        val resumed = control.resume("session")
        val taken = control.takeOver("session")
        val cancelled = control.cancel("session")
        val resumeAfterCancel = control.resume("session")

        assertEquals(PlannerControlState.RUNNING, initial.state)
        assertEquals(PlannerControlState.PAUSED, paused.state)
        assertTrue(paused.generation > initial.generation)
        assertEquals(PlannerControlState.RUNNING, resumed.state)
        assertTrue(resumed.generation > paused.generation)
        assertEquals(PlannerControlState.TAKEN_OVER, taken.state)
        assertTrue(taken.generation > resumed.generation)
        assertEquals(PlannerControlState.CANCELLED, cancelled.state)
        assertTrue(cancelled.generation > taken.generation)
        assertEquals(cancelled, resumeAfterCancel)
    }

    @Test
    fun pauseDuringPlanningPreventsAnyDispatch() {
        val control = PlannerExecutionControlRegistry()
        val endpoint = StatefulEndpoint()
        val registry = UniversalExecutorRegistry().apply { attach(endpoint) }
        val sessionId = "planning-pause"
        control.register(sessionId)
        val planner = AiPlanner {
            control.pause(sessionId)
            PlannerResult.Proposed(proposal())
        }
        val engine = PlannerExecutionEngine(
            executors = registry,
            policy = PlannerPolicyGate(),
            executionControl = control
        )

        val started = engine.start(owner, context(endpoint.observe()), planner, sessionId)

        assertEquals(PlannerSessionState.PAUSED, started.session.state)
        assertEquals(0, endpoint.dispatchCount)
    }

    @Test
    fun pauseDuringSettleWithholdsVerifiedDone() {
        val result = interruptDuringSettle(PlannerControlState.PAUSED)

        assertEquals(PlannerSessionState.PAUSED, result.session.state)
        assertNotEquals(PlannerSessionState.DONE, result.session.state)
        assertTrue(result.session.evidence.contains("node:open"))
    }

    @Test
    fun takeOverDuringSettleWithholdsVerifiedDone() {
        val result = interruptDuringSettle(PlannerControlState.TAKEN_OVER)

        assertEquals(PlannerSessionState.TAKEN_OVER, result.session.state)
        assertNotEquals(PlannerSessionState.DONE, result.session.state)
        assertTrue(result.session.evidence.contains("node:open"))
    }

    @Test
    fun cancelDuringSettleIsTerminalAndWithholdsVerifiedDone() {
        val result = interruptDuringSettle(PlannerControlState.CANCELLED)

        assertEquals(PlannerSessionState.CANCELLED, result.session.state)
        assertNotEquals(PlannerSessionState.DONE, result.session.state)
        assertTrue(result.session.evidence.contains("node:open"))
    }

    @Test
    fun pauseThenResumeDuringOldCallbackStillRequiresRevalidation() {
        val control = PlannerExecutionControlRegistry()
        val endpoint = StatefulEndpoint()
        val registry = UniversalExecutorRegistry().apply { attach(endpoint) }
        val sessionId = "stale-generation"
        control.register(sessionId)
        val engine = PlannerExecutionEngine(
            executors = registry,
            policy = PlannerPolicyGate(),
            settler = PlannerSettler { _, _ ->
                control.pause(sessionId)
                control.resume(sessionId)
            },
            executionControl = control
        )
        val started = engine.start(owner, context(endpoint.observe()), StaticPlanner(proposal()), sessionId)

        val result = engine.executeUntilBlocked(started.session)

        assertEquals(1, endpoint.dispatchCount)
        assertEquals(PlannerControlState.RUNNING, control.snapshot(sessionId).state)
        assertEquals(PlannerSessionState.PAUSED, result.session.state)
        assertTrue(result.session.message.contains("generation", ignoreCase = true))
        assertNotEquals(PlannerSessionState.DONE, result.session.state)
    }

    private fun interruptDuringSettle(target: PlannerControlState): PlannerExecutionResult {
        val control = PlannerExecutionControlRegistry()
        val endpoint = StatefulEndpoint()
        val registry = UniversalExecutorRegistry().apply { attach(endpoint) }
        val sessionId = "settle-${target.name.lowercase()}"
        control.register(sessionId)
        val engine = PlannerExecutionEngine(
            executors = registry,
            policy = PlannerPolicyGate(),
            settler = PlannerSettler { _, _ ->
                when (target) {
                    PlannerControlState.PAUSED -> control.pause(sessionId)
                    PlannerControlState.TAKEN_OVER -> control.takeOver(sessionId)
                    PlannerControlState.CANCELLED -> control.cancel(sessionId)
                    PlannerControlState.RUNNING -> Unit
                }
            },
            executionControl = control
        )
        val started = engine.start(owner, context(endpoint.observe()), StaticPlanner(proposal()), sessionId)
        val result = engine.executeUntilBlocked(started.session)
        assertEquals(1, endpoint.dispatchCount)
        return result
    }

    private fun context(observation: DeviceObservation) = PlannerContext(
        objective = "Open com.example",
        constraints = emptyList(),
        device = device,
        observation = observation
    )

    private fun proposal() = PlannerProposal(
        graph = ActionGraph(
            objective = "Open com.example",
            narration = "Open app",
            nodes = listOf(
                ActionGraphNode(
                    id = "open",
                    description = "Open target app",
                    action = UniversalAction(UniversalActionType.OPEN_APP, target = "com.example"),
                    requiredCapability = DeviceCapability.OPEN_APP,
                    targetScope = "com.example",
                    verification = VerificationRule(
                        VerificationMode.APP,
                        "com.example",
                        "Target app observed"
                    )
                )
            )
        ),
        providerId = "test",
        modelId = "test",
        confidence = 1.0,
        explanation = "test"
    )

    private class StaticPlanner(private val proposal: PlannerProposal) : AiPlanner {
        override fun plan(context: PlannerContext): PlannerResult = PlannerResult.Proposed(proposal)
    }

    private class StatefulEndpoint : UniversalDeviceExecutorEndpoint {
        override val deviceId: String = "phone-control"
        private var app = "com.veltrix.ultron"
        var dispatchCount = 0

        override fun observe(): DeviceObservation = DeviceObservation(
            deviceId = deviceId,
            foregroundApp = app,
            foregroundWindow = "Main",
            visibleText = listOf("Home")
        )

        override fun dispatch(action: UniversalAction): UniversalActionResult {
            dispatchCount += 1
            if (action.type == UniversalActionType.OPEN_APP) {
                app = action.target ?: app
            }
            return UniversalActionResult(
                accepted = true,
                action = action.type,
                message = "accepted"
            )
        }
    }
}
