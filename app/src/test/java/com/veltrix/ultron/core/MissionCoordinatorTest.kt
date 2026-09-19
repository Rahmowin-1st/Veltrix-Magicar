package com.veltrix.ultron.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MissionCoordinatorTest {
    private val source = MissionSource(MissionSourceKind.USER, "owner", "Owner")

    @Test
    fun userCanInterruptAndResumeMission() {
        val coordinator = MissionCoordinator()
        val mission = coordinator.receive(MissionRequest("Open Maps", source))
        coordinator.plan(
            mission.request.id,
            listOf(MissionPlanStep("1", "Open Maps", "phone.open_app", "maps")),
            "I will open Maps"
        )
        coordinator.execute(mission.request.id, "Opening Maps")

        assertEquals(MissionState.PAUSED, coordinator.interrupt(mission.request.id, InterruptCommand.PAUSE).state)
        assertEquals(MissionState.EXECUTING, coordinator.interrupt(mission.request.id, InterruptCommand.RESUME).state)
    }

    @Test
    fun doneRequiresEvidence() {
        val coordinator = MissionCoordinator()
        val mission = coordinator.receive(MissionRequest("Verify action", source))

        assertThrows(IllegalArgumentException::class.java) {
            coordinator.complete(mission.request.id, emptyList())
        }
    }
}
