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
import org.junit.Test

class PlannerAlwaysAllowExecutionTest {
    private val owner = Principal("owner", PrincipalKind.OWNER, "Owner")
    private val device = DeviceDescriptor(
        id = "phone",
        ownerPrincipalId = owner.id,
        kind = DeviceKind.PHONE,
        platform = DevicePlatform.ANDROID,
        displayName = "Phone",
        availableCapabilities = setOf(DeviceCapability.UI_CLICK),
        grantedCapabilities = setOf(DeviceCapability.UI_CLICK),
        controlProfile = ControlProfile.ASK_EACH_ACTION,
        presence = DevicePresence.ONLINE
    )

    @Test
    fun askEachActionNeverLetsRememberedApprovalSuppressTheNextPrompt() {
        val executors = UniversalExecutorRegistry().apply { attach(fakeExecutor()) }
        val policy = PlannerPolicyGate(ownerPermissions = InMemoryOwnerPlannerPermissionStore())
        val engine = PlannerExecutionEngine(executors, policy)
        val planner = plannerFor("org.telegram.messenger", PlannerRisk.MEDIUM)
        val context = context("Do it")

        val first = engine.start(owner, context, planner)
        val blocked = engine.executeUntilBlocked(first.session)
        assertEquals(PlannerSessionState.WAITING_USER, blocked.session.state)

        val approved = engine.executeNext(
            session = blocked.session,
            approved = true,
            rememberApproval = true
        )
        assertEquals(PlannerSessionState.DONE, approved.session.state)

        val second = engine.start(owner, context, planner)
        val stillBlocked = engine.executeUntilBlocked(second.session)
        assertEquals(PlannerSessionState.WAITING_USER, stillBlocked.session.state)

        val otherTarget = engine.start(
            owner,
            context("Other"),
            plannerFor("com.android.chrome", PlannerRisk.MEDIUM)
        )
        val otherBlocked = engine.executeUntilBlocked(otherTarget.session)
        assertEquals(PlannerSessionState.WAITING_USER, otherBlocked.session.state)
    }

    @Test
    fun criticalApprovalIsNeverRemembered() {
        val executors = UniversalExecutorRegistry().apply { attach(fakeExecutor()) }
        val policy = PlannerPolicyGate(ownerPermissions = InMemoryOwnerPlannerPermissionStore())
        val engine = PlannerExecutionEngine(executors, policy)
        val planner = plannerFor("org.telegram.messenger", PlannerRisk.CRITICAL)
        val context = context("Critical")

        val first = engine.executeUntilBlocked(engine.start(owner, context, planner).session)
        assertEquals(PlannerSessionState.WAITING_USER, first.session.state)
        val approved = engine.executeNext(first.session, approved = true, rememberApproval = true)
        assertEquals(PlannerSessionState.DONE, approved.session.state)

        val second = engine.executeUntilBlocked(engine.start(owner, context, planner).session)
        assertEquals(PlannerSessionState.WAITING_USER, second.session.state)
    }

    private fun context(objective: String) = PlannerContext(
        objective = objective,
        constraints = emptyList(),
        device = device,
        observation = DeviceObservation(deviceId = device.id, foregroundApp = "com.veltrix.ultron")
    )

    private fun plannerFor(target: String, risk: PlannerRisk) = AiPlanner {
        PlannerResult.Proposed(
            PlannerProposal(
                graph = ActionGraph(
                    objective = "Click",
                    narration = "Click target",
                    nodes = listOf(
                        ActionGraphNode(
                            id = "click",
                            description = "Click Continue",
                            action = UniversalAction(
                                type = UniversalActionType.CLICK,
                                target = target,
                                text = "Continue"
                            ),
                            requiredCapability = DeviceCapability.UI_CLICK,
                            targetScope = target,
                            risk = risk,
                            verification = VerificationRule(
                                mode = VerificationMode.ACTION_ACCEPTED,
                                description = "Action accepted"
                            )
                        )
                    )
                ),
                providerId = "test",
                modelId = "test",
                confidence = 1.0,
                explanation = "test"
            )
        )
    }

    private fun fakeExecutor() = object : UniversalDeviceExecutorEndpoint {
        override val deviceId: String = device.id
        override fun observe(): DeviceObservation = DeviceObservation(
            deviceId = device.id,
            foregroundApp = "org.telegram.messenger"
        )

        override fun dispatch(action: UniversalAction): UniversalActionResult = UniversalActionResult(
            accepted = true,
            action = action.type,
            message = "accepted"
        )
    }
}
