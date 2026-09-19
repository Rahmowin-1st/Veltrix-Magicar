package com.veltrix.ultron.core

import java.util.UUID

enum class MissionSourceKind { USER, AGENT, SYSTEM }

enum class MissionState {
    RECEIVED,
    UNDERSTANDING,
    PLANNED,
    WAITING_PERMISSION,
    EXECUTING,
    WAITING_USER,
    VERIFYING,
    PAUSED,
    DONE,
    FAILED,
    CANCELLED
}

data class MissionSource(
    val kind: MissionSourceKind,
    val id: String,
    val displayName: String
)

data class MissionRequest(
    val objective: String,
    val source: MissionSource,
    val constraints: List<String> = emptyList(),
    val id: String = UUID.randomUUID().toString(),
    val requestedAtEpochMs: Long = System.currentTimeMillis()
)

data class MissionPlanStep(
    val id: String,
    val description: String,
    val capability: String,
    val target: String? = null,
    val consequential: Boolean = false
)

data class Mission(
    val request: MissionRequest,
    val state: MissionState = MissionState.RECEIVED,
    val plan: List<MissionPlanStep> = emptyList(),
    val activeStepIndex: Int = -1,
    val statusMessage: String = "Received",
    val evidence: List<String> = emptyList()
) {
    val id: String get() = request.id
}

enum class InterruptCommand { PAUSE, STOP, CANCEL, UNDO, TAKE_OVER, RESUME }
