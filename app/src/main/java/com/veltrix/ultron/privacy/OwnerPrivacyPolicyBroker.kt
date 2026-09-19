package com.veltrix.ultron.privacy

import com.veltrix.ultron.agents.Principal
import com.veltrix.ultron.agents.PrincipalKind
import com.veltrix.ultron.planner.OwnerPlannerPermission
import com.veltrix.ultron.planner.OwnerPlannerPermissionDecision
import com.veltrix.ultron.planner.OwnerPlannerPermissionStore
import java.util.Locale

const val OWNER_PRIVACY_WILDCARD: String = "*"

/**
 * Typed, model-independent mutation boundary for user privacy policy.
 *
 * This broker never creates an ALLOW grant. It can only install an owner HARD DENY
 * or remove one exact HARD DENY. Removal therefore falls back to the normal ASK /
 * capability policy instead of silently elevating authority.
 *
 * A model/agent/system automation is never accepted as mutation authority. A future
 * natural-language settings layer may parse a user-authenticated chat turn into this
 * type, but the trusted caller must preserve DIRECT_USER_INTERACTION provenance.
 */
enum class PrivacyPolicyOperation {
    ADD_HARD_DENY,
    REMOVE_HARD_DENY
}

enum class PrivacyPolicyMutationOrigin {
    DIRECT_USER_INTERACTION,
    TRUSTED_SETTINGS_UI,
    MODEL_OR_AGENT,
    SYSTEM_AUTOMATION
}

enum class PrivacyPolicyMutationState {
    ENFORCED,
    REMOVED,
    NO_CHANGE,
    REJECTED
}

data class PrivacyPolicyMutation(
    val operation: PrivacyPolicyOperation,
    /** Exact policy resource scope used by the consuming subsystem. */
    val targetScope: String,
    /** `*` blocks every action on the target; otherwise an exact policy action such as observe/store/click. */
    val actionScope: String = OWNER_PRIVACY_WILDCARD,
    /** `*` applies to every risk class; otherwise an exact risk class such as low/medium/high/critical. */
    val riskClass: String = OWNER_PRIVACY_WILDCARD,
    val allOwnerDevices: Boolean = false,
    val origin: PrivacyPolicyMutationOrigin
)

data class PrivacyPolicyMutationResult(
    val state: PrivacyPolicyMutationState,
    val effectiveDenied: Boolean,
    val targetScope: String? = null,
    val actionScope: String? = null,
    val riskClass: String? = null,
    val message: String
)

class OwnerPrivacyPolicyBroker(
    private val ownerPrincipalId: String,
    private val deviceId: String,
    private val store: OwnerPlannerPermissionStore
) {
    init {
        require(validIdentity(ownerPrincipalId)) { "Owner principal id is invalid" }
        require(validIdentity(deviceId)) { "Device id is invalid" }
    }

    @Synchronized
    fun apply(principal: Principal, mutation: PrivacyPolicyMutation): PrivacyPolicyMutationResult {
        if (!isTrustedUserMutation(principal, mutation.origin)) {
            return rejected("Only an authenticated owner/user interaction may change HARD DENY policy")
        }

        val target = normalizeTarget(mutation.targetScope)
            ?: return rejected("Privacy target scope is invalid")
        val action = normalizePolicyLeaf(mutation.actionScope)
            ?: return rejected("Privacy action scope is invalid")
        val risk = normalizePolicyLeaf(mutation.riskClass)
            ?: return rejected("Privacy risk scope is invalid")
        val persistedDeviceId = if (mutation.allOwnerDevices) OWNER_PRIVACY_WILDCARD else deviceId

        return when (mutation.operation) {
            PrivacyPolicyOperation.ADD_HARD_DENY -> addDeny(
                persistedDeviceId = persistedDeviceId,
                target = target,
                action = action,
                risk = risk
            )

            PrivacyPolicyOperation.REMOVE_HARD_DENY -> removeDeny(
                persistedDeviceId = persistedDeviceId,
                target = target,
                action = action,
                risk = risk
            )
        }
    }

    fun isHardDenied(
        targetScope: String,
        actionScope: String = OWNER_PRIVACY_WILDCARD,
        riskClass: String = OWNER_PRIVACY_WILDCARD
    ): Boolean {
        val target = normalizeTarget(targetScope) ?: return true
        val action = normalizePolicyLeaf(actionScope) ?: return true
        val risk = normalizePolicyLeaf(riskClass) ?: return true
        return store.isHardDenied(
            ownerPrincipalId = ownerPrincipalId,
            deviceId = deviceId,
            targetScope = target,
            actionScope = action,
            riskClass = risk
        )
    }

    private fun addDeny(
        persistedDeviceId: String,
        target: String,
        action: String,
        risk: String
    ): PrivacyPolicyMutationResult {
        val existing = store.resolve(
            principalId = ownerPrincipalId,
            deviceId = persistedDeviceId,
            targetScope = target,
            actionScope = action,
            riskClass = risk
        )
        if (existing == OwnerPlannerPermissionDecision.DENY) {
            return result(
                state = PrivacyPolicyMutationState.NO_CHANGE,
                target = target,
                action = action,
                risk = risk,
                message = "HARD DENY is already enforced"
            )
        }

        store.put(
            OwnerPlannerPermission(
                principalId = ownerPrincipalId,
                deviceId = persistedDeviceId,
                targetScope = target,
                actionScope = action,
                riskClass = risk,
                decision = OwnerPlannerPermissionDecision.DENY
            )
        )

        val exact = store.resolve(
            principalId = ownerPrincipalId,
            deviceId = persistedDeviceId,
            targetScope = target,
            actionScope = action,
            riskClass = risk
        )
        if (exact != OwnerPlannerPermissionDecision.DENY) {
            return result(
                state = PrivacyPolicyMutationState.REJECTED,
                target = target,
                action = action,
                risk = risk,
                message = "Policy store did not confirm the deny; execution remains fail-closed"
            )
        }

        return result(
            state = PrivacyPolicyMutationState.ENFORCED,
            target = target,
            action = action,
            risk = risk,
            message = "Owner HARD DENY enforced"
        )
    }

    private fun removeDeny(
        persistedDeviceId: String,
        target: String,
        action: String,
        risk: String
    ): PrivacyPolicyMutationResult {
        val existing = store.resolve(
            principalId = ownerPrincipalId,
            deviceId = persistedDeviceId,
            targetScope = target,
            actionScope = action,
            riskClass = risk
        )
        if (existing != OwnerPlannerPermissionDecision.DENY) {
            return result(
                state = PrivacyPolicyMutationState.NO_CHANGE,
                target = target,
                action = action,
                risk = risk,
                message = "No exact owner HARD DENY exists for this scope"
            )
        }

        val removed = store.revoke(
            principalId = ownerPrincipalId,
            deviceId = persistedDeviceId,
            targetScope = target,
            actionScope = action,
            riskClass = risk
        )
        if (!removed) {
            return result(
                state = PrivacyPolicyMutationState.REJECTED,
                target = target,
                action = action,
                risk = risk,
                message = "Policy store could not remove the deny; deny remains enforced"
            )
        }

        val exactAfter = store.resolve(
            principalId = ownerPrincipalId,
            deviceId = persistedDeviceId,
            targetScope = target,
            actionScope = action,
            riskClass = risk
        )
        if (exactAfter == OwnerPlannerPermissionDecision.DENY) {
            return result(
                state = PrivacyPolicyMutationState.REJECTED,
                target = target,
                action = action,
                risk = risk,
                message = "Policy store still reports the exact deny; deny remains enforced"
            )
        }

        val stillDenied = store.isHardDenied(
            ownerPrincipalId = ownerPrincipalId,
            deviceId = deviceId,
            targetScope = target,
            actionScope = action,
            riskClass = risk
        )
        return PrivacyPolicyMutationResult(
            state = PrivacyPolicyMutationState.REMOVED,
            effectiveDenied = stillDenied,
            targetScope = target,
            actionScope = action,
            riskClass = risk,
            message = if (stillDenied) {
                "Exact HARD DENY removed; a broader owner deny still applies"
            } else {
                "Exact HARD DENY removed; normal permission policy applies"
            }
        )
    }

    private fun result(
        state: PrivacyPolicyMutationState,
        target: String,
        action: String,
        risk: String,
        message: String
    ): PrivacyPolicyMutationResult = PrivacyPolicyMutationResult(
        state = state,
        effectiveDenied = store.isHardDenied(
            ownerPrincipalId = ownerPrincipalId,
            deviceId = deviceId,
            targetScope = target,
            actionScope = action,
            riskClass = risk
        ),
        targetScope = target,
        actionScope = action,
        riskClass = risk,
        message = message
    )

    private fun rejected(message: String): PrivacyPolicyMutationResult = PrivacyPolicyMutationResult(
        state = PrivacyPolicyMutationState.REJECTED,
        effectiveDenied = true,
        message = message
    )

    private fun isTrustedUserMutation(
        principal: Principal,
        origin: PrivacyPolicyMutationOrigin
    ): Boolean {
        if (origin != PrivacyPolicyMutationOrigin.DIRECT_USER_INTERACTION &&
            origin != PrivacyPolicyMutationOrigin.TRUSTED_SETTINGS_UI
        ) return false
        if (principal.kind != PrincipalKind.OWNER && principal.kind != PrincipalKind.USER) return false
        return principal.id == ownerPrincipalId
    }

    private fun normalizeTarget(raw: String): String? {
        val value = raw.trim().trim('/')
        if (value.isBlank() || value.length > MAX_TARGET_SCOPE_LENGTH || containsControl(value)) return null
        val segments = value.split('/')
        if (segments.any { segment ->
                segment.isBlank() ||
                    segment == "." ||
                    segment == ".." ||
                    segment.length > MAX_TARGET_SEGMENT_LENGTH ||
                    containsControl(segment)
            }
        ) return null
        return value
    }

    private fun normalizePolicyLeaf(raw: String): String? {
        val value = raw.trim().lowercase(Locale.ROOT)
        if (value.isBlank() || value.length > MAX_POLICY_LEAF_LENGTH || containsControl(value)) return null
        if ('/' in value || value == "." || value == "..") return null
        return value
    }

    private fun validIdentity(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_IDENTITY_LENGTH && !containsControl(value)

    private fun containsControl(value: String): Boolean = value.any { it.code < 0x20 || it.code == 0x7f }

    private companion object {
        const val MAX_IDENTITY_LENGTH = 256
        const val MAX_TARGET_SCOPE_LENGTH = 512
        const val MAX_TARGET_SEGMENT_LENGTH = 192
        const val MAX_POLICY_LEAF_LENGTH = 96
    }
}
