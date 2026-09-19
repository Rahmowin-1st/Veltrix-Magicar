package com.veltrix.ultron.remote

import com.veltrix.ultron.agents.AgentGateway
import com.veltrix.ultron.agents.AgentTask
import com.veltrix.ultron.agents.AgentTaskReceipt
import com.veltrix.ultron.agents.GatewayCapabilities
import com.veltrix.ultron.agents.Principal
import com.veltrix.ultron.agents.PrincipalKind
import com.veltrix.ultron.agents.TaskState
import com.veltrix.ultron.devices.ControlProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBridgeCoordinatorTest {
    @Test
    fun authenticatedRemotePrincipalAndCapabilitiesReachGatewayWithoutGrantingAnything() {
        val transport = FakeTransport(
            lease = RemoteTaskLease(
                taskId = "task-1",
                principalId = "owner-1",
                principalKind = RemotePrincipalKind.OWNER,
                principalDisplayName = "Owner",
                objective = "Open Telegram and inspect the chat",
                constraints = listOf("Do not send"),
                requiredCapabilities = setOf("OPEN_APP", "UI_CLICK"),
                controlProfile = ControlProfile.ASK_EACH_ACTION
            )
        )
        val gateway = FakeGateway()
        val coordinator = RemoteBridgeCoordinator(transport, gateway)

        val result = coordinator.sync(snapshot())

        assertEquals("task-1", result.claimedTaskId)
        assertEquals(PrincipalKind.OWNER, gateway.lastSubmitted?.principal?.kind)
        assertEquals("owner-1", gateway.lastSubmitted?.principal?.id)
        assertEquals(setOf("OPEN_APP", "UI_CLICK"), gateway.lastSubmitted?.requestedCapabilities)
        assertEquals(ControlProfile.ASK_EACH_ACTION, gateway.lastSubmitted?.controlProfileOverride)
        assertEquals("remote-bridge", gateway.lastSubmitted?.metadata?.get("transport"))
        assertTrue(transport.reports.any { it.second.state == RemoteTaskState.RUNNING })
    }

    @Test
    fun longPollWaitClaimsTaskThroughSamePolicyGateway() {
        val transport = FakeTransport(lease = lease("task-wake"))
        val gateway = FakeGateway()
        val coordinator = RemoteBridgeCoordinator(transport, gateway)

        val result = coordinator.waitAndSync(snapshot(), timeoutMs = 12_345)

        assertEquals(1, transport.waitCalls)
        assertEquals(12_345, transport.lastWaitTimeoutMs)
        assertEquals("task-wake", result.claimedTaskId)
        assertEquals("task-wake", gateway.lastSubmitted?.id)
        assertEquals(ControlProfile.ASK_EACH_ACTION, gateway.lastSubmitted?.controlProfileOverride)
        assertTrue(transport.reports.any { it.first == "task-wake" })
    }

    @Test
    fun remotePauseCancelAndResumeReachLocalGateway() {
        val transport = FakeTransport(lease = lease("task-control"))
        val gateway = FakeGateway()
        val coordinator = RemoteBridgeCoordinator(transport, gateway)
        coordinator.sync(snapshot())

        transport.controls["task-control"] = RemoteTaskControl("task-control", RemoteTaskState.PAUSED, "Paused remotely")
        coordinator.syncTrackedOnly()
        assertEquals(TaskState.PAUSED, gateway.get("task-control")?.state)

        transport.controls["task-control"] = RemoteTaskControl("task-control", RemoteTaskState.RUNNING, "Resume remotely")
        coordinator.syncTrackedOnly()
        assertEquals(TaskState.RUNNING, gateway.get("task-control")?.state)

        transport.controls["task-control"] = RemoteTaskControl("task-control", RemoteTaskState.CANCELLED, "Cancel remotely")
        coordinator.syncTrackedOnly()
        assertEquals(TaskState.CANCELLED, gateway.get("task-control")?.state)
    }

    @Test
    fun localPauseWinsAgainstStaleRemoteRunningState() {
        val transport = FakeTransport(lease = lease("task-local-pause"))
        val gateway = FakeGateway()
        val coordinator = RemoteBridgeCoordinator(transport, gateway)
        coordinator.sync(snapshot())

        val paused = coordinator.pauseLocal("task-local-pause")

        assertEquals(TaskState.PAUSED, paused.state)
        assertEquals(RemoteTaskState.PAUSED, transport.controls["task-local-pause"]?.state)
        coordinator.syncTrackedOnly()
        assertEquals(TaskState.PAUSED, gateway.get("task-local-pause")?.state)
    }

    @Test
    fun localTakeOverWinsAgainstStaleRemoteRunningState() {
        val transport = FakeTransport(lease = lease("task-take-over"))
        val gateway = FakeGateway()
        val coordinator = RemoteBridgeCoordinator(transport, gateway)
        coordinator.sync(snapshot())

        val takenOver = coordinator.takeOverLocal("task-take-over")

        assertEquals(TaskState.PAUSED, takenOver.state)
        assertEquals("Owner took control", takenOver.narration)
        assertEquals(RemoteTaskState.PAUSED, transport.controls["task-take-over"]?.state)
        coordinator.syncTrackedOnly()
        assertEquals(TaskState.PAUSED, gateway.get("task-take-over")?.state)
    }

    @Test
    fun ownerPauseRemainsAuthoritativeWhenFirstCloudReportFails() {
        val transport = FakeTransport(lease = lease("task-report-retry"))
        val gateway = FakeGateway()
        val coordinator = RemoteBridgeCoordinator(transport, gateway)
        coordinator.sync(snapshot())
        transport.failNextReports = 1

        val paused = coordinator.pauseLocal("task-report-retry")

        assertEquals(TaskState.PAUSED, paused.state)
        assertEquals(RemoteTaskState.RUNNING, transport.controls["task-report-retry"]?.state)
        coordinator.syncTrackedOnly()
        assertEquals(TaskState.PAUSED, gateway.get("task-report-retry")?.state)
        assertEquals(RemoteTaskState.PAUSED, transport.controls["task-report-retry"]?.state)
    }

    @Test
    fun localResumePublishesRunningFenceBeforeNewApprovalState() {
        val transport = FakeTransport(lease = lease("task-resume-fence"))
        val gateway = FakeGateway()
        val coordinator = RemoteBridgeCoordinator(transport, gateway)
        coordinator.sync(snapshot())
        transport.controls["task-resume-fence"] = RemoteTaskControl(
            "task-resume-fence",
            RemoteTaskState.PAUSED,
            "Paused remotely"
        )
        coordinator.syncTrackedOnly()
        gateway.resumeState = TaskState.WAITING_FOR_USER
        val reportStart = transport.reports.size

        val resumed = coordinator.resumeLocal("task-resume-fence")

        assertEquals(TaskState.WAITING_FOR_USER, resumed.state)
        val states = transport.reports.drop(reportStart).map { it.second.state }
        assertEquals(listOf(RemoteTaskState.RUNNING, RemoteTaskState.WAITING_FOR_USER), states)
        assertEquals(RemoteTaskState.WAITING_FOR_USER, transport.controls["task-resume-fence"]?.state)
    }

    @Test
    fun verifiedDoneIsReportedOnlyWithEvidence() {
        val transport = FakeTransport(lease = lease("task-done"))
        val gateway = FakeGateway()
        val coordinator = RemoteBridgeCoordinator(transport, gateway)
        coordinator.sync(snapshot())

        gateway.set(
            "task-done",
            AgentTaskReceipt(
                taskId = "task-done",
                state = TaskState.VERIFIED_DONE,
                narration = "Verified done",
                evidence = listOf("screen_state:target_visible")
            )
        )
        coordinator.syncTrackedOnly()

        val report = transport.reports.last { it.first == "task-done" }.second
        assertEquals(RemoteTaskState.VERIFIED_DONE, report.state)
        assertEquals(listOf("screen_state:target_visible"), report.evidence)
    }

    private fun snapshot() = BridgeCapabilitySnapshot(
        platform = "ANDROID-37",
        availableCapabilities = setOf("OPEN_APP", "UI_CLICK"),
        grantedCapabilities = setOf("OPEN_APP")
    )

    private fun lease(id: String) = RemoteTaskLease(
        taskId = id,
        principalId = "frontend",
        principalKind = RemotePrincipalKind.AGENT,
        principalDisplayName = "Frontend Agent",
        objective = "Inspect the current screen",
        constraints = emptyList(),
        requiredCapabilities = setOf("OPEN_APP"),
        controlProfile = ControlProfile.ASK_EACH_ACTION
    )
}

private class FakeTransport(
    private var lease: RemoteTaskLease? = null
) : RemoteBridgeTransport {
    val controls = mutableMapOf<String, RemoteTaskControl>()
    val reports = mutableListOf<Pair<String, RemoteTaskReport>>()
    var waitCalls: Int = 0
        private set
    var lastWaitTimeoutMs: Int? = null
        private set
    var failNextReports: Int = 0

    override fun heartbeat(snapshot: BridgeCapabilitySnapshot) = Unit

    override fun claimTask(): RemoteTaskLease? = lease.also { lease = null }

    override fun waitForTask(timeoutMs: Int): RemoteTaskLease? {
        waitCalls += 1
        lastWaitTimeoutMs = timeoutMs
        return claimTask()
    }

    override fun inspectTask(taskId: String): RemoteTaskControl =
        controls[taskId] ?: RemoteTaskControl(taskId, RemoteTaskState.RUNNING, "Running")

    override fun reportTask(taskId: String, report: RemoteTaskReport) {
        if (failNextReports > 0) {
            failNextReports -= 1
            throw IllegalStateException("Synthetic report failure")
        }
        reports += taskId to report
        controls[taskId] = RemoteTaskControl(taskId, report.state, report.narration)
    }
}

private class FakeGateway : AgentGateway {
    private val tasks = linkedMapOf<String, AgentTask>()
    private val receipts = linkedMapOf<String, AgentTaskReceipt>()
    var lastSubmitted: AgentTask? = null
        private set
    var resumeState: TaskState = TaskState.RUNNING

    override fun submit(task: AgentTask): AgentTaskReceipt {
        lastSubmitted = task
        tasks[task.id] = task
        return AgentTaskReceipt(task.id, TaskState.RUNNING, "Running").also { receipts[task.id] = it }
    }

    override fun get(taskId: String): AgentTaskReceipt? = receipts[taskId]

    override fun principalForTask(taskId: String): Principal? = tasks[taskId]?.principal

    override fun pause(taskId: String): AgentTaskReceipt = update(taskId, TaskState.PAUSED, "Paused")

    override fun takeOver(taskId: String): AgentTaskReceipt = update(taskId, TaskState.PAUSED, "Owner took control")

    override fun cancel(taskId: String): AgentTaskReceipt = update(taskId, TaskState.CANCELLED, "Cancelled")

    override fun resume(taskId: String): AgentTaskReceipt = update(taskId, resumeState, "Resumed")

    override fun capabilities(): GatewayCapabilities = GatewayCapabilities(
        operations = setOf("submit_task"),
        taskPrincipals = PrincipalKind.entries.toSet(),
        supportsIdempotency = true,
        supportsCancellation = true,
        supportsLongRunningTasks = true
    )

    fun set(taskId: String, receipt: AgentTaskReceipt) {
        receipts[taskId] = receipt
    }

    private fun update(taskId: String, state: TaskState, narration: String): AgentTaskReceipt =
        AgentTaskReceipt(taskId, state, narration, receipts[taskId]?.evidence.orEmpty()).also { receipts[taskId] = it }
}
