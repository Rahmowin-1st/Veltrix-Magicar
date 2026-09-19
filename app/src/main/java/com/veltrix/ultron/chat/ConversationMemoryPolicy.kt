package com.veltrix.ultron.chat

/**
 * Bounded/sanitized policy for local conversational continuity.
 * Potential credentials/codes are redacted before persistence or planner hints.
 */
object ConversationMemoryPolicy {
    const val MAX_MESSAGES = 80
    const val MAX_TEXT_CHARS = 1_600
    const val MAX_HINTS = 12

    fun sanitize(raw: String): String {
        var value = raw.trim().take(MAX_TEXT_CHARS)
        value = value.replace(Regex("(?i)bearer\\s+[A-Za-z0-9._~+/-]{8,}"), "<redacted-token>")
        value = value.replace(Regex("(?i)sk-[A-Za-z0-9_-]{8,}"), "<redacted-api-key>")
        value = value.replace(
            Regex("(?i)(password|passcode|secret|token|api[_ -]?key)\\s*[:=]\\s*\\S+"),
            "\$1=<redacted>"
        )
        value = value.replace(Regex("\\b(?:\\d[ -]?){13,19}\\b"), "<redacted-number>")
        value = value.replace(Regex("\\b\\d{6}\\b"), "<redacted-6-digit-code>")
        return value
    }

    fun bounded(messages: List<ChatMessage>): List<ChatMessage> =
        messages.takeLast(MAX_MESSAGES)

    fun plannerHints(
        messages: List<ChatMessage>,
        followUp: FollowUpContext,
        limit: Int = MAX_HINTS
    ): List<String> {
        val hints = mutableListOf<String>()
        followUp.lastIntent?.let { intent ->
            sanitize(intent).takeIf(String::isNotBlank)?.let { hints += "last_intent:$it" }
        }
        followUp.activeAppPackage?.takeIf(String::isNotBlank)?.let { hints += "active_app:${sanitize(it)}" }
        followUp.activeMissionId?.takeIf(String::isNotBlank)?.let { hints += "active_mission:${sanitize(it)}" }

        messages.asReversed().forEach { message ->
            if (hints.size >= limit.coerceAtLeast(0)) return@forEach
            val text = sanitize(message.text)
            if (text.isBlank()) return@forEach
            val prefix = when (message.role) {
                MessageRole.USER -> "recent_user"
                MessageRole.VELTRIX -> "recent_veltrix"
                MessageRole.AGENT -> "recent_agent"
                MessageRole.SYSTEM -> "recent_system"
            }
            hints += "$prefix:$text"
        }
        return hints.take(limit.coerceAtLeast(0)).reversed()
    }
}
