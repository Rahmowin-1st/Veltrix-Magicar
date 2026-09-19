package com.veltrix.ultron.core

class MissionCoordinator {
    private val missions = linkedMapOf<String, Mission>()

    @Synchronized
    fun receive(request: MissionRequest): Mission {
        val mission = Mission(
            request = request,
            state = MissionState.UNDERSTANDING,
            statusMessage = "Understanding objective"
        )
        missions[request.id] = mission
        return mission
    }

    @Synchronized
    fun plan(missionId: String, steps: List<MissionPlanStep>, narration: String): Mission =
        update(missionId) {
            it.copy(
                state = MissionState.PLANNED,
                plan = steps,
                activeStepIndex = if (steps.isEmpty()) -1 else 0,
                statusMessage = narration
            )
        }

    @Synchronized
    fun waitForPermission(missionId: String, message: String): Mission =
        update(missionId) { it.copy(state = MissionState.WAITING_PERMISSION, statusMessage = message) }

    @Synchronized
    fun execute(missionId: String, message: String): Mission =
        update(missionId) { it.copy(state = MissionState.EXECUTING, statusMessage = message) }

    @Synchronized
    fun verify(missionId: String, message: String): Mission =
        update(missionId) { it.copy(state = MissionState.VERIFYING, statusMessage = message) }

    @Synchronized
    fun verifiedStep(
        missionId: String,
        evidence: List<String>,
        message: String = "Step verified"
    ): Mission = update(missionId) { mission ->
        require(evidence.isNotEmpty()) { "Verified step requires evidence" }
        val combined = (mission.evidence + evidence).distinct()
        val nextIndex = mission.activeStepIndex + 1
        if (mission.plan.isNotEmpty() && nextIndex < mission.plan.size) {
            mission.copy(
                state = MissionState.PLANNED,
                activeStepIndex = nextIndex,
                evidence = combined,
                statusMessage = message
            )
        } else {
            mission.copy(
                state = MissionState.DONE,
                activeStepIndex = if (mission.plan.isEmpty()) -1 else mission.plan.lastIndex,
                evidence = combined,
                statusMessage = "Verified done"
            )
        }
    }

    @Synchronized
    fun complete(missionId: String, evidence: List<String>, message: String = "Verified done"): Mission =
        update(missionId) {
            require(evidence.isNotEmpty()) { "DONE requires evidence" }
            it.copy(state = MissionState.DONE, evidence = evidence, statusMessage = message)
        }

    @Synchronized
    fun fail(missionId: String, message: String): Mission =
        update(missionId) { it.copy(state = MissionState.FAILED, statusMessage = message) }

    @Synchronized
    fun interrupt(missionId: String, command: InterruptCommand): Mission = update(missionId) { mission ->
        when (command) {
            InterruptCommand.PAUSE,
            InterruptCommand.TAKE_OVER -> mission.copy(
                state = MissionState.PAUSED,
                statusMessage = if (command == InterruptCommand.TAKE_OVER) "User took control" else "Paused"
            )
            InterruptCommand.STOP,
            InterruptCommand.CANCEL -> mission.copy(state = MissionState.CANCELLED, statusMessage = "Cancelled")
            InterruptCommand.UNDO -> mission.copy(state = MissionState.PAUSED, statusMessage = "Undo requested")
            InterruptCommand.RESUME -> mission.copy(state = MissionState.EXECUTING, statusMessage = "Resumed")
        }
    }

    @Synchronized
    fun get(missionId: String): Mission? = missions[missionId]

    private fun update(missionId: String, transform: (Mission) -> Mission): Mission {
        val current = requireNotNull(missions[missionId]) { "Unknown mission: $missionId" }
        val next = transform(current)
        missions[missionId] = next
        return next
    }
}
