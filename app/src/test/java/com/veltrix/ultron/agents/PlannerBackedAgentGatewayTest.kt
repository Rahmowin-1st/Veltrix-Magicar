package com.veltrix.ultron.agents

import com.veltrix.ultron.devices.ControlProfile
import com.veltrix.ultron.runtime.CommandApproval
import com.veltrix.ultron.runtime.CommandOutcome
import com.veltrix.ultron.runtime.CommandOutcomeState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerBackedAgentGatewayTest {
    @Test
    fun authenticatedPrincipalConstraintsAndStableSessionReachPlannerRuntime() {
        val runtime = FakeAgentCommandRuntime(
            submitOutcome = CommandOutcome(
                state = CommandOutcomeState.NEEDS_APPROVAL,
                message = "Owner approval required"
            )
        )
        val gateway = PlannerBackedAgentGateway(runtime)
        val principal = Principal("frontend", PrincipalKind.AGENT, "Frontend Agent")

        val receipt = gateway.submit(
            AgentTask(
                id = "task-1",
                principal = principal,
                objective = "Open Telegram",
                constraints = listOf("Do not send"),
                requestedCapabilities = setOf("OPEN_APP"),
                controlProfileOverride = ControlProfile.ASK_EACH_ACTION
            )
        )

        assertEquals("task-1", runtime.lastSubmittedSession)
        assertEquals(principal, runtime.lastPrincipal)
        assertEquals(listOf("Do not send"), runtime.lastConstraints)
        assertEquals(ControlProfile.ASK_EACH_ACTION, runtime.lastControlProfileOverride)
        assertEquals(TaskState.WAITING_FOR_USER, receipt.state)
        assertEquals(principal, gateway.principalForTask("task-1"))
    }

    @Test
    fun activeTaskStatusExposesOwnerSafeMetadataAndDropsTerminalTask() {
        val runtime = FakeAgentCommandRuntime(
            submitOutcome = CommandOutcome(
                state = CommandOutcomeState.NEEDS_APPROVAL,
                message = "Owner approval required"
            ),
            approveOutcome = CommandOutcome(
                state = CommandOutcomeState.VERIFIED_DONE,
                message = "Verified done",
                evidence = listOf("screen:verified")
            )
        )
        val gateway = PlannerBackedAgentGateway(runtime)
        val principal = Principal("frontend", PrincipalKind.AGENT, "Frontend Agent")
        gateway.submit(
            AgentTask(
                id = "task-ui",
                principal = principal,
                objective = "Inspect current screen"
            )
        )

        val active = gateway.activeTaskStatuses().single()
        assertEquals("task-ui", active.taskId)
        assertEquals(principal, active.principal)
        assertEquals("Inspect current screen", active.objective)
        assertEquals(TaskState.WAITING_FOR_USER, active.state)

        gateway.approve("task-ui", CommandApproval.ALLOW_ONCE)
        assertTrue(gateway.activeTaskStatuses().isEmpty())
    }

    @Test
    fun verifiedDoneWithoutEvidenceIsRejectedAsFailed() {
        val gateway = PlannerBackedAgentGateway(
            FakeAgentCommandRuntime(
                submitOutcome = CommandOutcome(
                    state = CommandOutcomeState.VERIFIED_DONE,
                    message = "Done",
                    evidence = emptyList()
                )
            )
        )

        val receipt = gateway.submit(task("task-proof"))

        assertEquals(TaskState.FAILED, receipt.state)
        assertEquals("Proof-of-done evidence missing", receipt.narration)
    }

    @Test
    fun approvalContinuesStablePlannerSessionAndReturnsEvidence() {
        val runtime = FakeAgentCommandRuntime(
            submitOutcome = CommandOutcome(
                state = CommandOutcomeState.NEEDS_APPROVAL,
                message = "Approval required"
            ),
            approveOutcome = CommandOutcome(
                state = CommandOutcomeState.VERIFIED_DONE,
                message = "Verified done",
                evidence = listOf("package:org.telegram.messenger")
            )
        )
        val gateway = PlannerBackedAgentGateway(runtime)
        gateway.submit(task("task-approve"))

        val receipt = gateway.approve("task-approve", CommandApproval.ALWAYS_ALLOW)

        assertEquals("task-approve", runtime.lastApprovedSession)
        assertEquals(CommandApproval.ALWAYS_ALLOW, runtime.lastApproval)
        assertEquals(TaskState.VERIFIED_DONE, receipt.state)
        assertEquals(listOf("package:org.telegram.messenger"), receipt.evidence)
    }

    @Test
    fun pausedWaitingTaskCanResumeWithoutLosingStableSession() {
        val runtime = FakeAgentCommandRuntime(
            submitOutcome = CommandOutcome(
                state = CommandOutcomeState.NEEDS_APPROVAL,
                message = "Approval required"
            )
        )
        val gateway = PlannerBackedAgentGateway(runtime)
        gateway.submit(task("task-pause"))

        assertEquals(TaskState.PAUSED, gateway.pause("task-pause").state)
        assertEquals("task-pause", runtime.lastPausedSession)
        assertEquals(TaskState.WAITING_FOR_USER, gateway.resume("task-pause").state)
        assertEquals("task-pause", runtime.lastResumedSession)
        assertTrue(gateway.capabilities().supportsCancellation)
    }

    @Test
    fun takeOverIsDistinctBackendControlAndRemainsPausedUntilResume() {
        val runtime = FakeAgentCommandRuntime(
            submitOutcome = CommandOutcome(
                state = CommandOutcomeState.NEEDS_APPROVAL,
                message = "Approval required"
            )
        )
        val gateway = PlannerBackedAgentGateway(runtime)
        gateway.submit(task("task-takeover"))

        val taken = gateway.takeOver("task-takeover")

        assertEquals(TaskState.PAUSED, taken.state)
        assertEquals("task-takeover", runtime.lastTakeOverSession)
        assertEquals(TaskState.WAITING_FOR_USER, gateway.resume("task-takeover").state)
    }

    @Test
    fun pauseWinsAgainstLateVerifiedSubmitCallback() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = FakeAgentCommandRuntime(
            submitOutcome = verifiedDone(),
            submitStarted = started,
            submitRelease = release
        )
        val gateway = PlannerBackedAgentGateway(runtime)
        var submitReceipt: AgentTaskReceipt? = null
        val worker = thread(start = true) {
            submitReceipt = gateway.submit(task("task-race-pause"))
        }

        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertEquals(TaskState.PAUSED, gateway.pause("task-race-pause").state)
        release.countDown()
        worker.join(2_000)

        assertNotNull(submitReceipt)
        assertEquals(TaskState.PAUSED, submitReceipt?.state)
        assertEquals(TaskState.PAUSED, gateway.get("task-race-pause")?.state)
        assertEquals("task-race-pause", runtime.lastPausedSession)
    }

    @Test
    fun takeOverWinsAgainstLateVerifiedSubmitCallback() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = FakeAgentCommandRuntime(
            submitOutcome = verifiedDone(),
            submitStarted = started,
            submitRelease = release
        )
        val gateway = PlannerBackedAgentGateway(runtime)
        var submitReceipt: AgentTaskReceipt? = null
        val worker = thread(start = true) {
            submitReceipt = gateway.submit(task("task-race-takeover"))
        }

        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertEquals(TaskState.PAUSED, gateway.takeOver("task-race-takeover").state)
        release.countDown()
        worker.join(2_000)

        assertNotNull(submitReceipt)
        assertEquals(TaskState.PAUSED, submitReceipt?.state)
        assertEquals(TaskState.PAUSED, gateway.get("task-race-takeover")?.state)
        assertEquals("task-race-takeover", runtime.lastTakeOverSession)
    }

    @Test
    fun cancelWinsAgainstLateVerifiedSubmitCallback() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = FakeAgentCommandRuntime(
            submitOutcome = verifiedDone(),
            submitStarted = started,
            submitRelease = release
        )
        val gateway = PlannerBackedAgentGateway(runtime)
        var submitReceipt: AgentTaskReceipt? = null
        val worker = thread(start = true) {
            submitReceipt = gateway.submit(task("task-race-cancel"))
        }

        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertEquals(TaskState.CANCELLED, gateway.cancel("task-race-cancel").state)
        release.countDown()
        worker.join(2_000)

        assertNotNull(submitReceipt)
        assertEquals(TaskState.CANCELLED, submitReceipt?.state)
        assertEquals(TaskState.CANCELLED, gateway.get("task-race-cancel")?.state)
        assertEquals("task-race-cancel", runtime.lastCancelledSession)
    }

    private fun task(id: String) = AgentTask(
        id = id,
        principal = Principal("frontend", PrincipalKind.AGENT, "Frontend Agent"),
        objective = "Inspect current screen"
    )

    private fun verifiedDone() = CommandOutcome(
        state = CommandOutcomeState.VERIFIED_DONE,
        message = "Verified done",
        evidence = listOf("proof:verified")
    )
}

private class FakeAgentCommandRuntime(
    private val submitOutcome: CommandOutcome,
    private val approveOutcome: CommandOutcome = submitOutcome,
    private val pauseOutcome: CommandOutcome = CommandOutcome(CommandOutcomeState.PAUSED, "Paused"),
    private val takeOverOutcome: CommandOutcome = CommandOutcome(CommandOutcomeState.TAKEN_OVER, "Owner took control"),
    private val resumeOutcome: CommandOutcome = submitOutcome,
    private val cancelOutcome: CommandOutcome = CommandOutcome(CommandOutcomeState.CANCELLED, "Cancelled"),
    private val submitStarted: CountDownLatch? = null,
    private val submitRelease: CountDownLatch? = null
) : AgentCommandRuntime {
    var lastSubmittedSession: String? = null
    var lastPrincipal: Principal? = null
    var lastConstraints: List<String> = emptyList()
    var lastControlProfileOverride: ControlProfile? = null
    var lastApprovedSession: String? = null
    var lastApproval: CommandApproval? = null
    var lastPausedSession: String? = null
    var lastTakeOverSession: String? = null
    var lastResumedSession: String? = null
    var lastCancelledSession: String? = null

    override fun submit(
        sessionId: String,
        objective: String,
        principal: Principal,
        constraints: List<String>,
        controlProfileOverride: ControlProfile?
    ): CommandOutcome {
        lastSubmittedSession = sessionId
        lastPrincipal = principal
        lastConstraints = constraints
        lastControlProfileOverride = controlProfileOverride
        submitStarted?.countDown()
        submitRelease?.await(2, TimeUnit.SECONDS)
        return submitOutcome.copy(missionId = sessionId)
    }

    override fun approve(sessionId: String, approval: CommandApproval): CommandOutcome {
        lastApprovedSession = sessionId
        lastApproval = approval
        return approveOutcome.copy(missionId = sessionId)
    }

    override fun pause(sessionId: String): CommandOutcome {
        lastPausedSession = sessionId
        return pauseOutcome.copy(missionId = sessionId)
    }

    override fun takeOver(sessionId: String): CommandOutcome {
        lastTakeOverSession = sessionId
        return takeOverOutcome.copy(missionId = sessionId)
    }

    override fun resume(sessionId: String): CommandOutcome {
        lastResumedSession = sessionId
        return resumeOutcome.copy(missionId = sessionId)
    }

    override fun cancel(sessionId: String): CommandOutcome {
        lastCancelledSession = sessionId
        return cancelOutcome.copy(missionId = sessionId)
    }
}
