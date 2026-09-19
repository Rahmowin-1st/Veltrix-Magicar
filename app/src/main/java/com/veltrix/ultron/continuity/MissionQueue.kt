package com.veltrix.ultron.continuity

import java.time.Instant
import java.util.PriorityQueue
import java.util.UUID

enum class MissionPriority(val weight: Int) { URGENT(0), NORMAL(1), BACKGROUND(2) }
enum class ExecutionTarget { PHONE, CLOUD, EITHER }

data class QueuedMission(
    val id: String = UUID.randomUUID().toString(),
    val objective: String,
    val priority: MissionPriority = MissionPriority.NORMAL,
    val target: ExecutionTarget = ExecutionTarget.EITHER,
    val createdAt: Instant = Instant.now()
)

class MissionQueue {
    private val queue = PriorityQueue<QueuedMission>(
        compareBy<QueuedMission> { it.priority.weight }.thenBy { it.createdAt }
    )

    @Synchronized
    fun enqueue(mission: QueuedMission) {
        queue.removeIf { it.id == mission.id }
        queue += mission
    }

    @Synchronized
    fun next(phoneOnline: Boolean): QueuedMission? {
        val eligible = queue.filter { phoneOnline || it.target != ExecutionTarget.PHONE }
            .minWithOrNull(compareBy<QueuedMission> { it.priority.weight }.thenBy { it.createdAt })
        if (eligible != null) queue.remove(eligible)
        return eligible
    }

    @Synchronized
    fun snapshot(): List<QueuedMission> = queue.toList().sortedWith(
        compareBy<QueuedMission> { it.priority.weight }.thenBy { it.createdAt }
    )
}
