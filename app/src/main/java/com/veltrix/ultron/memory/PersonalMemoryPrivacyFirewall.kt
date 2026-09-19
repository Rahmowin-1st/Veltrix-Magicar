package com.veltrix.ultron.memory

import com.veltrix.ultron.planner.OwnerPlannerPermissionStore
import java.security.MessageDigest
import java.util.Locale

/** Canonical privacy scopes for future local/Obsidian-backed personal memory. */
object PersonalMemoryPrivacyScopes {
    const val ROOT = "memory/personal"

    fun kindScope(kind: MemoryKind): String =
        "$ROOT/kind/${kind.name.lowercase(Locale.ROOT)}"

    fun recordScope(recordId: String): String? {
        val clean = recordId.trim()
        if (clean.isEmpty() || clean.length > 512 || clean.any { it.code < 0x20 || it.code == 0x7f }) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(clean.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "$ROOT/record/$digest"
    }
}

/**
 * Final privacy decision before personal memory is persisted or exposed to a model role.
 *
 * Precedence:
 *   isPrivate(USER_ONLY) / USER HARD DENY > record visibleTo allow-list > model request.
 *
 * A generic `observe` deny hides memory from every model audience. Audience-specific
 * denies use `observe.main_assistant`, `observe.searcher`, `observe.worker`, etc.
 */
class PersonalMemoryPrivacyFirewall(
    private val ownerPermissions: OwnerPlannerPermissionStore,
    private val ownerPrincipalId: String,
    private val deviceId: String
) {
    fun canStore(record: MemoryRecord): Boolean {
        val recordScope = PersonalMemoryPrivacyScopes.recordScope(record.id) ?: return false
        return !isDenied(PersonalMemoryPrivacyScopes.ROOT, STORE_ACTION_SCOPE) &&
            !isDenied(PersonalMemoryPrivacyScopes.kindScope(record.kind), STORE_ACTION_SCOPE) &&
            !isDenied(recordScope, STORE_ACTION_SCOPE)
    }

    fun canObserve(record: MemoryRecord, audience: MemoryAudience): Boolean {
        if (record.isPrivate) return false
        if (audience !in record.visibleTo) return false
        val recordScope = PersonalMemoryPrivacyScopes.recordScope(record.id) ?: return false
        val kindScope = PersonalMemoryPrivacyScopes.kindScope(record.kind)
        val audienceAction = audienceObserveAction(audience)

        return !isDenied(PersonalMemoryPrivacyScopes.ROOT, OBSERVE_ACTION_SCOPE) &&
            !isDenied(kindScope, OBSERVE_ACTION_SCOPE) &&
            !isDenied(recordScope, OBSERVE_ACTION_SCOPE) &&
            !isDenied(PersonalMemoryPrivacyScopes.ROOT, audienceAction) &&
            !isDenied(kindScope, audienceAction) &&
            !isDenied(recordScope, audienceAction)
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

        fun audienceObserveAction(audience: MemoryAudience): String =
            "observe.${audience.name.lowercase(Locale.ROOT)}"
    }
}
