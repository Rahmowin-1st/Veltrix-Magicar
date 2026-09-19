package com.veltrix.ultron.continuity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MissionQueueTest {
    @Test
    fun phoneOnlyMissionWaitsWhilePhoneOffline() {
        val queue = MissionQueue()
        queue.enqueue(QueuedMission(objective = "Open Maps", target = ExecutionTarget.PHONE))

        assertNull(queue.next(phoneOnline = false))
        assertEquals(1, queue.snapshot().size)
    }

    @Test
    fun cloudMissionCanContinueWhilePhoneOffline() {
        val queue = MissionQueue()
        queue.enqueue(QueuedMission(objective = "Research", target = ExecutionTarget.CLOUD))

        assertEquals("Research", queue.next(phoneOnline = false)?.objective)
    }
}
