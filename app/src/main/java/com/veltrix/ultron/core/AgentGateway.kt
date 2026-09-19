package com.veltrix.ultron.core

data class AgentTaskRequest(
    val agentId: String,
    val agentName: String,
    val objective: String,
    val constraints: List<String> = emptyList(),
    val completionEvidence: List<String> = emptyList()
)

data class MissionReceipt(
    val missionId: String,
    val accepted: Boolean,
    val state: MissionState,
    val message: String
)

interface AgentGateway {
    suspend fun submit(task: AgentTaskRequest): MissionReceipt
    suspend fun interrupt(missionId: String, command: InterruptCommand): MissionReceipt
}
