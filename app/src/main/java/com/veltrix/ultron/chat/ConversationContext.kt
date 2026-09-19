package com.veltrix.ultron.chat

import java.time.Instant
import java.util.UUID

enum class MessageRole { USER, VELTRIX, AGENT, SYSTEM }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val text: String,
    val createdAt: Instant = Instant.now(),
    val missionId: String? = null
)

data class FollowUpContext(
    val activeMissionId: String? = null,
    val activeArtifactIds: List<String> = emptyList(),
    val activeAppPackage: String? = null,
    val activePersonRef: String? = null,
    val lastIntent: String? = null
)

class ConversationContext {
    private val messages = mutableListOf<ChatMessage>()
    private var followUp = FollowUpContext()

    @Synchronized
    fun append(message: ChatMessage) {
        messages += message
    }

    @Synchronized
    fun recent(limit: Int = 30): List<ChatMessage> = messages.takeLast(limit.coerceAtLeast(0))

    @Synchronized
    fun updateFollowUp(transform: (FollowUpContext) -> FollowUpContext): FollowUpContext {
        followUp = transform(followUp)
        return followUp
    }

    @Synchronized
    fun followUpContext(): FollowUpContext = followUp

    @Synchronized
    fun resetFollowUp() {
        followUp = FollowUpContext()
    }
}
