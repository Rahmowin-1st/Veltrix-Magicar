package com.veltrix.ultron.chat

import com.veltrix.ultron.planner.OwnerPlannerPermissionStore
import java.security.MessageDigest

/** Canonical policy scopes for local conversation memory. */
object ConversationMemoryPrivacyScopes {
    const val ROOT = "memory/conversation"
    const val FOLLOW_UP = "$ROOT/followup"

    fun messageScope(messageId: String): String? {
        val clean = messageId.trim()
        if (clean.isEmpty() || clean.length > 512 || '\u0000' in clean) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(clean.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "$ROOT/message/$digest"
    }
}

data class PlannerVisibleConversationMemory(
    val messages: List<ChatMessage>,
    val followUp: FollowUpContext
)

/**
 * Pre-model privacy boundary for conversation memory.
 *
 * `store` denies prevent new local persistence. `observe` denies keep data local/user-visible
 * but remove it from planner/model hints. A wildcard owner HARD DENY blocks both. The firewall
 * is intentionally pure so every future searcher/report/model path can reuse the same rule.
 */
class ConversationMemoryPrivacyFirewall(
    private val ownerPermissions: OwnerPlannerPermissionStore,
    private val ownerPrincipalId: String,
    private val deviceId: String
) {
    fun canStoreMessage(message: ChatMessage): Boolean {
        val itemScope = ConversationMemoryPrivacyScopes.messageScope(message.id) ?: return false
        return !isDenied(ConversationMemoryPrivacyScopes.ROOT, STORE_ACTION_SCOPE) &&
            !isDenied(itemScope, STORE_ACTION_SCOPE)
    }

    fun canStoreFollowUp(): Boolean =
        !isDenied(ConversationMemoryPrivacyScopes.ROOT, STORE_ACTION_SCOPE) &&
            !isDenied(ConversationMemoryPrivacyScopes.FOLLOW_UP, STORE_ACTION_SCOPE)

    fun filterForPlanner(
        messages: List<ChatMessage>,
        followUp: FollowUpContext
    ): PlannerVisibleConversationMemory {
        if (isDenied(ConversationMemoryPrivacyScopes.ROOT, OBSERVE_ACTION_SCOPE)) {
            return PlannerVisibleConversationMemory(emptyList(), FollowUpContext())
        }

        val visibleMessages = messages.filter { message ->
            ConversationMemoryPrivacyScopes.messageScope(message.id)?.let { scope ->
                !isDenied(scope, OBSERVE_ACTION_SCOPE)
            } ?: false
        }
        val visibleFollowUp = if (
            isDenied(ConversationMemoryPrivacyScopes.FOLLOW_UP, OBSERVE_ACTION_SCOPE)
        ) {
            FollowUpContext()
        } else {
            followUp
        }
        return PlannerVisibleConversationMemory(visibleMessages, visibleFollowUp)
    }

    private fun isDenied(targetScope: String, actionScope: String): Boolean =
        ownerPermissions.isHardDenied(
            ownerPrincipalId = ownerPrincipalId,
            deviceId = deviceId,
            targetScope = targetScope,
            actionScope = actionScope,
            riskClass = LOW_RISK_CLASS
        )

    companion object {
        const val STORE_ACTION_SCOPE = "store"
        const val OBSERVE_ACTION_SCOPE = "observe"
        const val LOW_RISK_CLASS = "low"
    }
}
