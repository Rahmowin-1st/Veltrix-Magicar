package com.veltrix.ultron.planner

import com.veltrix.ultron.agents.Principal
import com.veltrix.ultron.agents.PrincipalKind
import com.veltrix.ultron.brain.BrainCapability
import com.veltrix.ultron.brain.BrainModel
import com.veltrix.ultron.brain.BrainRouter
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerCoreTest {
    private val device = DeviceDescriptor(
        id = "phone-1",
        ownerPrincipalId = "owner",
        kind = DeviceKind.PHONE,
        platform = DevicePlatform.ANDROID,
        displayName = "Phone",
        availableCapabilities = setOf(DeviceCapability.OPEN_APP, DeviceCapability.UI_CLICK),
        grantedCapabilities = setOf(DeviceCapability.OPEN_APP, DeviceCapability.UI_CLICK),
        controlProfile = ControlProfile.MAX_APPROVED,
        presence = DevicePresence.ONLINE
    )

    @Test
    fun validatorRejectsCapabilityRelabeling() {
        val graph = ActionGraph(
            objective = "Open app",
            narration = "Open target",
            nodes = listOf(
                ActionGraphNode(
                    id = "1",
                    description = "Open app",
                    action = UniversalAction(UniversalActionType.OPEN_APP, target = "com.example"),
                    requiredCapability = DeviceCapability.UI_CLICK,
                    targetScope = "com.example",
                    verification = VerificationRule(VerificationMode.APP, "com.example", "Target app observed")
                )
            )
        )

        val result = ActionGraphValidator().validate(graph, device)

        assertFalse(result.valid)
        assertTrue(result.issues.any { it.code == "CAPABILITY_MISMATCH" })
    }

    @Test
    fun ownerMaxApprovedExecutesMultiStepGraphAndRequiresEvidence() {
        val endpoint = StatefulEndpoint()
        val registry = UniversalExecutorRegistry().apply { attach(endpoint) }
        val planner = StaticPlanner(validProposal("fake", "planner"))
        val engine = PlannerExecutionEngine(
            executors = registry,
            policy = PlannerPolicyGate()
        )
        val context = context(endpoint.observe())
        val started = engine.start(
            Principal("owner", PrincipalKind.OWNER, "Owner"),
            context,
            planner
        )

        val result = engine.executeUntilBlocked(started.session)

        assertEquals(PlannerSessionState.DONE, result.session.state)
        assertEquals(2, endpoint.dispatchCount)
        assertTrue(result.session.evidence.any { it == "node:open" })
        assertTrue(result.session.evidence.any { it == "node:continue" })
    }

    @Test
    fun verifiedActionObserverRunsOnlyAfterNodeVerification() {
        val verifiedEndpoint = StatefulEndpoint()
        val verifiedRegistry = UniversalExecutorRegistry().apply { attach(verifiedEndpoint) }
        var verifiedCallbacks = 0
        val verifiedEngine = PlannerExecutionEngine(
            executors = verifiedRegistry,
            policy = PlannerPolicyGate(),
            verifiedActionObserver = PlannerVerifiedActionObserver { session, node, before, after ->
                verifiedCallbacks += 1
                assertEquals("open", node.id)
                assertEquals("com.veltrix.ultron", before.foregroundApp)
                assertEquals("com.example", after.foregroundApp)
                assertEquals("owner", session.principal.id)
            }
        )
        val verifiedStarted = verifiedEngine.start(
            Principal("owner", PrincipalKind.OWNER, "Owner"),
            context(verifiedEndpoint.observe()),
            StaticPlanner(oneStepProposal("fake", "planner"))
        )

        val verifiedResult = verifiedEngine.executeUntilBlocked(verifiedStarted.session)

        assertEquals(PlannerSessionState.DONE, verifiedResult.session.state)
        assertEquals(1, verifiedCallbacks)

        val failedEndpoint = NoChangeEndpoint()
        val failedRegistry = UniversalExecutorRegistry().apply { attach(failedEndpoint) }
        var failedCallbacks = 0
        val failedEngine = PlannerExecutionEngine(
            executors = failedRegistry,
            policy = PlannerPolicyGate(),
            verifiedActionObserver = PlannerVerifiedActionObserver { _, _, _, _ -> failedCallbacks += 1 }
        )
        val failedStarted = failedEngine.start(
            Principal("owner", PrincipalKind.OWNER, "Owner"),
            context(failedEndpoint.observe()),
            StaticPlanner(oneStepProposal("fake", "planner"))
        )

        val failedResult = failedEngine.executeUntilBlocked(failedStarted.session)

        assertEquals(PlannerSessionState.REPLAN_REQUIRED, failedResult.session.state)
        assertEquals(0, failedCallbacks)
    }

    @Test
    fun remoteAgentDoesNotInheritOwnersMaxApprovedProfile() {
        val endpoint = StatefulEndpoint()
        val registry = UniversalExecutorRegistry().apply { attach(endpoint) }
        val planner = StaticPlanner(oneStepProposal("fake", "planner"))
        val engine = PlannerExecutionEngine(registry, PlannerPolicyGate())
        val started = engine.start(
            Principal("frontend-agent", PrincipalKind.AGENT, "Frontend Agent"),
            context(endpoint.observe()),
            planner
        )

        val result = engine.executeUntilBlocked(started.session)

        assertEquals(PlannerSessionState.WAITING_USER, result.session.state)
        assertEquals(0, endpoint.dispatchCount)
    }

    @Test
    fun routedPlannerFallsBackToNextHealthyModel() {
        val router = BrainRouter().apply {
            register(
                BrainModel(
                    providerId = "a",
                    modelId = "first",
                    capabilities = setOf(BrainCapability.REASONING, BrainCapability.TOOL_USE),
                    freeTier = true,
                    priority = 1
                )
            )
            register(
                BrainModel(
                    providerId = "b",
                    modelId = "second",
                    capabilities = setOf(BrainCapability.REASONING, BrainCapability.TOOL_USE),
                    freeTier = true,
                    priority = 2
                )
            )
        }
        val routed = RoutedAiPlanner(router)
        routed.register("a", "first", AiPlanner { PlannerResult.Rejected(PlannerFailure("RATE_LIMIT", "limited", retryable = true)) })
        routed.register("b", "second", StaticPlanner(oneStepProposal("b", "second")))

        val result = routed.plan(context(DeviceObservation("phone-1", foregroundApp = "com.veltrix.ultron")))

        assertTrue(result is PlannerResult.Proposed)
        assertEquals("b", (result as PlannerResult.Proposed).proposal.providerId)
        assertEquals("second", result.proposal.modelId)
    }

    private fun context(observation: DeviceObservation) = PlannerContext(
        objective = "Open com.example and press Continue",
        constraints = emptyList(),
        device = device,
        observation = observation
    )

    private fun validProposal(provider: String, model: String) = PlannerProposal(
        graph = ActionGraph(
            objective = "Open com.example and press Continue",
            narration = "Open app, then continue",
            nodes = listOf(
                ActionGraphNode(
                    id = "open",
                    description = "Open target app",
                    action = UniversalAction(UniversalActionType.OPEN_APP, target = "com.example"),
                    requiredCapability = DeviceCapability.OPEN_APP,
                    targetScope = "com.example",
                    verification = VerificationRule(VerificationMode.APP, "com.example", "Target app observed")
                ),
                ActionGraphNode(
                    id = "continue",
                    description = "Press Continue",
                    action = UniversalAction(UniversalActionType.CLICK, text = "Continue"),
                    requiredCapability = DeviceCapability.UI_CLICK,
                    targetScope = "com.example",
                    verification = VerificationRule(VerificationMode.TEXT_PRESENT, "Done", "Done text observed")
                )
            )
        ),
        providerId = provider,
        modelId = model,
        confidence = 0.9,
        explanation = "test"
    )

    private fun oneStepProposal(provider: String, model: String) = PlannerProposal(
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
                    verification = VerificationRule(VerificationMode.APP, "com.example", "Target app observed")
                )
            )
        ),
        providerId = provider,
        modelId = model,
        confidence = 0.8,
        explanation = "test"
    )

    private class StaticPlanner(private val proposal: PlannerProposal) : AiPlanner {
        override fun plan(context: PlannerContext): PlannerResult = PlannerResult.Proposed(proposal)
    }

    private class StatefulEndpoint : UniversalDeviceExecutorEndpoint {
        override val deviceId: String = "phone-1"
        private var app = "com.veltrix.ultron"
        private var text = listOf("Home")
        var dispatchCount = 0

        override fun observe(): DeviceObservation = DeviceObservation(
            deviceId = deviceId,
            foregroundApp = app,
            foregroundWindow = "Main",
            visibleText = text
        )

        override fun dispatch(action: UniversalAction): UniversalActionResult {
            dispatchCount += 1
            when (action.type) {
                UniversalActionType.OPEN_APP -> {
                    app = action.target ?: app
                    text = listOf("Continue")
                }
                UniversalActionType.CLICK -> text = listOf("Done")
                else -> Unit
            }
            return UniversalActionResult(
                accepted = true,
                action = action.type,
                message = "accepted"
            )
        }
    }

    private class NoChangeEndpoint : UniversalDeviceExecutorEndpoint {
        override val deviceId: String = "phone-1"

        override fun observe(): DeviceObservation = DeviceObservation(
            deviceId = deviceId,
            foregroundApp = "com.veltrix.ultron",
            foregroundWindow = "Main",
            visibleText = listOf("Home")
        )

        override fun dispatch(action: UniversalAction): UniversalActionResult = UniversalActionResult(
            accepted = true,
            action = action.type,
            message = "accepted without state change"
        )
    }
}
