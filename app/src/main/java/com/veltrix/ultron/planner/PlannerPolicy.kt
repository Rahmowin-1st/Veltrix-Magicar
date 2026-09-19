package com.veltrix.ultron.planner

import com.veltrix.ultron.agents.DelegatedPermissionStore
import com.veltrix.ultron.agents.DelegationDecision
import com.veltrix.ultron.agents.Principal
import com.veltrix.ultron.agents.PrincipalKind
import com.veltrix.ultron.devices.ControlProfile
import com.veltrix.ultron.devices.DeviceDescriptor
import com.veltrix.ultron.devices.UniversalActionType

enum class PlannerPolicyDecision { ALLOW, ASK_USER, DENY }

data class PlannerPolicyResult(
    val decision: PlannerPolicyDecision,
    val reason: String
)

enum class OwnerPlannerPermissionDecision { ALWAYS_ALLOW, ASK, DENY }

data class OwnerPlannerPermission(
    val principalId: String,
    val deviceId: String,
    val targetScope: String,
    val actionScope: String,
    val riskClass: String,
    val decision: OwnerPlannerPermissionDecision
)

private const val OWNER_SCOPE_WILDCARD = "*"
private const val MAX_OWNER_SCOPE_LENGTH = 512

internal data class OwnerHardDenyLookup(
    val deviceId: String,
    val targetScope: String,
    val actionScope: String,
    val riskClass: String
)

/**
 * HARD DENY lookups are intentionally broader than ALWAYS_ALLOW lookups.
 * A deny may cover every action/risk for one target, every owner device, or a
 * slash-delimited resource subtree such as notification/com.bank/channel.
 * ALWAYS_ALLOW remains exact-scoped and therefore cannot spread accidentally.
 */
internal fun ownerHardDenyLookups(
    deviceId: String,
    targetScope: String,
    actionScope: String,
    riskClass: String
): List<OwnerHardDenyLookup> {
    val normalizedTarget = targetScope.trim().trim('/')
    val targets = buildList {
        if (normalizedTarget.isNotEmpty()) {
            var current = normalizedTarget
            add(current)
            while ('/' in current) {
                current = current.substringBeforeLast('/').trimEnd('/')
                if (current.isNotEmpty()) add(current)
            }
        }
        add(OWNER_SCOPE_WILDCARD)
    }.distinct()

    return buildList {
        for (candidateDevice in listOf(deviceId, OWNER_SCOPE_WILDCARD).distinct()) {
            for (candidateTarget in targets) {
                for (candidateAction in listOf(actionScope, OWNER_SCOPE_WILDCARD).distinct()) {
                    for (candidateRisk in listOf(riskClass, OWNER_SCOPE_WILDCARD).distinct()) {
                        add(
                            OwnerHardDenyLookup(
                                deviceId = candidateDevice,
                                targetScope = candidateTarget,
                                actionScope = candidateAction,
                                riskClass = candidateRisk
                            )
                        )
                    }
                }
            }
        }
    }
}

interface OwnerPlannerPermissionStore {
    fun resolve(
        principalId: String,
        deviceId: String,
        targetScope: String,
        actionScope: String,
        riskClass: String
    ): OwnerPlannerPermissionDecision

    fun put(permission: OwnerPlannerPermission)

    fun revoke(
        principalId: String,
        deviceId: String,
        targetScope: String,
        actionScope: String,
        riskClass: String
    ): Boolean

    /**
     * User HARD DENY is evaluated for every caller, including delegated agents.
     * Implementations only need exact get/put support; wildcard/subtree expansion
     * is performed here so persistent and in-memory stores share identical rules.
     */
    fun isHardDenied(
        ownerPrincipalId: String,
        deviceId: String,
        targetScope: String,
        actionScope: String,
        riskClass: String
    ): Boolean = ownerHardDenyLookups(deviceId, targetScope, actionScope, riskClass).any { candidate ->
        resolve(
            principalId = ownerPrincipalId,
            deviceId = candidate.deviceId,
            targetScope = candidate.targetScope,
            actionScope = candidate.actionScope,
            riskClass = candidate.riskClass
        ) == OwnerPlannerPermissionDecision.DENY
    }
}

class InMemoryOwnerPlannerPermissionStore : OwnerPlannerPermissionStore {
    private val permissions = linkedMapOf<String, OwnerPlannerPermission>()

    @Synchronized
    override fun resolve(
        principalId: String,
        deviceId: String,
        targetScope: String,
        actionScope: String,
        riskClass: String
    ): OwnerPlannerPermissionDecision = permissions[
        key(principalId, deviceId, targetScope, actionScope, riskClass)
    ]?.decision ?: OwnerPlannerPermissionDecision.ASK

    @Synchronized
    override fun put(permission: OwnerPlannerPermission) {
        permissions[key(permission)] = permission
    }

    @Synchronized
    override fun revoke(
        principalId: String,
        deviceId: String,
        targetScope: String,
        actionScope: String,
        riskClass: String
    ): Boolean = permissions.remove(key(principalId, deviceId, targetScope, actionScope, riskClass)) != null

    private fun key(permission: OwnerPlannerPermission): String = key(
        permission.principalId,
        permission.deviceId,
        permission.targetScope,
        permission.actionScope,
        permission.riskClass
    )

    private fun key(
        principalId: String,
        deviceId: String,
        targetScope: String,
        actionScope: String,
        riskClass: String
    ): String = listOf(principalId, deviceId, targetScope, actionScope, riskClass).joinToString("|")
}

/**
 * Device MAX_APPROVED never means an arbitrary remote agent inherits owner authority.
 * Owners/users can use already-granted capabilities broadly; remote agents still need
 * an exact delegated scope. User HARD DENY is stronger than MAX_APPROVED, delegation,
 * remembered owner approval, or planner/model preference.
 */
class PlannerPolicyGate(
    private val delegatedPermissions: DelegatedPermissionStore = DelegatedPermissionStore(),
    private val ownerPermissions: OwnerPlannerPermissionStore = InMemoryOwnerPlannerPermissionStore()
) {
    fun evaluate(
        principal: Principal,
        device: DeviceDescriptor,
        node: ActionGraphNode,
        consumeDelegation: Boolean = false
    ): PlannerPolicyResult {
        val targetScope = targetScope(device, node)
        val actionScope = node.action.type.name.lowercase()
        val riskClass = node.risk.name.lowercase()

        if (
            ownerPermissions.isHardDenied(
                ownerPrincipalId = device.ownerPrincipalId,
                deviceId = device.id,
                targetScope = targetScope,
                actionScope = actionScope,
                riskClass = riskClass
            )
        ) {
            return PlannerPolicyResult(
                PlannerPolicyDecision.DENY,
                "User HARD DENY blocks this resource/action scope"
            )
        }

        if (node.requiredCapability !in device.effectiveCapabilities) {
            return PlannerPolicyResult(PlannerPolicyDecision.DENY, "Capability is not granted on this device")
        }

        if (device.controlProfile == ControlProfile.READ_ONLY && node.action.type.isMutating()) {
            return PlannerPolicyResult(PlannerPolicyDecision.DENY, "Device is in READ_ONLY control profile")
        }

        return when (principal.kind) {
            PrincipalKind.OWNER, PrincipalKind.USER -> evaluateOwner(principal, device, node)
            PrincipalKind.AGENT, PrincipalKind.SYSTEM -> evaluateDelegated(principal, device, node, consumeDelegation)
        }
    }

    /**
     * Persists an exact owner scope. Critical actions are deliberately excluded,
     * and an ungranted OS/device capability can never be made executable here.
     */
    fun rememberOwnerAlwaysAllow(
        principal: Principal,
        device: DeviceDescriptor,
        node: ActionGraphNode
    ): Boolean {
        if (principal.kind != PrincipalKind.OWNER && principal.kind != PrincipalKind.USER) return false
        if (node.risk == PlannerRisk.CRITICAL) return false
        if (node.requiredCapability !in device.effectiveCapabilities) return false
        val scope = ownerScope(principal, device, node)
        ownerPermissions.put(
            OwnerPlannerPermission(
                principalId = scope.principalId,
                deviceId = scope.deviceId,
                targetScope = scope.targetScope,
                actionScope = scope.actionScope,
                riskClass = scope.riskClass,
                decision = OwnerPlannerPermissionDecision.ALWAYS_ALLOW
            )
        )
        return true
    }

    /**
     * Installs an owner-authored HARD DENY without consulting the model. Wildcard action/risk
     * scopes let a user hide an entire app/resource. allOwnerDevices=true creates the same
     * deny boundary for every device owned by this principal. Slash-delimited target scopes
     * also protect descendants, enabling future notification/chat/folder/resource privacy.
     */
    fun rememberOwnerHardDeny(
        principal: Principal,
        device: DeviceDescriptor,
        targetScope: String,
        actionScope: String = OWNER_SCOPE_WILDCARD,
        riskClass: String = OWNER_SCOPE_WILDCARD,
        allOwnerDevices: Boolean = false
    ): Boolean {
        if (!canManageOwnerPolicy(principal, device)) return false
        val target = targetScope.trim().trim('/')
        val action = actionScope.trim().lowercase()
        val risk = riskClass.trim().lowercase()
        if (!validPolicyScope(target) || !validPolicyScope(action) || !validPolicyScope(risk)) return false

        ownerPermissions.put(
            OwnerPlannerPermission(
                principalId = device.ownerPrincipalId,
                deviceId = if (allOwnerDevices) OWNER_SCOPE_WILDCARD else device.id,
                targetScope = target,
                actionScope = action,
                riskClass = risk,
                decision = OwnerPlannerPermissionDecision.DENY
            )
        )
        return true
    }

    fun revokeOwnerHardDeny(
        principal: Principal,
        device: DeviceDescriptor,
        targetScope: String,
        actionScope: String = OWNER_SCOPE_WILDCARD,
        riskClass: String = OWNER_SCOPE_WILDCARD,
        allOwnerDevices: Boolean = false
    ): Boolean {
        if (!canManageOwnerPolicy(principal, device)) return false
        val target = targetScope.trim().trim('/')
        val action = actionScope.trim().lowercase()
        val risk = riskClass.trim().lowercase()
        if (!validPolicyScope(target) || !validPolicyScope(action) || !validPolicyScope(risk)) return false
        return ownerPermissions.revoke(
            principalId = device.ownerPrincipalId,
            deviceId = if (allOwnerDevices) OWNER_SCOPE_WILDCARD else device.id,
            targetScope = target,
            actionScope = action,
            riskClass = risk
        )
    }

    fun revokeOwnerAlwaysAllow(
        principal: Principal,
        device: DeviceDescriptor,
        node: ActionGraphNode
    ): Boolean {
        val scope = ownerScope(principal, device, node)
        return ownerPermissions.revoke(
            scope.principalId,
            scope.deviceId,
            scope.targetScope,
            scope.actionScope,
            scope.riskClass
        )
    }

    private fun evaluateOwner(
        principal: Principal,
        device: DeviceDescriptor,
        node: ActionGraphNode
    ): PlannerPolicyResult {
        if (node.risk == PlannerRisk.CRITICAL) {
            return PlannerPolicyResult(
                PlannerPolicyDecision.ASK_USER,
                "Critical action requires explicit owner confirmation"
            )
        }
        if (device.controlProfile == ControlProfile.ASK_EACH_ACTION) {
            return PlannerPolicyResult(
                PlannerPolicyDecision.ASK_USER,
                "Device profile asks before each action"
            )
        }

        val scope = ownerScope(principal, device, node)
        return when (
            ownerPermissions.resolve(
                scope.principalId,
                scope.deviceId,
                scope.targetScope,
                scope.actionScope,
                scope.riskClass
            )
        ) {
            OwnerPlannerPermissionDecision.ALWAYS_ALLOW -> PlannerPolicyResult(
                PlannerPolicyDecision.ALLOW,
                "Exact owner scope is always allowed"
            )
            OwnerPlannerPermissionDecision.DENY -> PlannerPolicyResult(
                PlannerPolicyDecision.DENY,
                "Exact owner scope is denied"
            )
            OwnerPlannerPermissionDecision.ASK -> PlannerPolicyResult(
                PlannerPolicyDecision.ALLOW,
                "Within owner-approved device scope"
            )
        }
    }

    private fun evaluateDelegated(
        principal: Principal,
        device: DeviceDescriptor,
        node: ActionGraphNode,
        consume: Boolean
    ): PlannerPolicyResult {
        val appScope = targetScope(device, node)
        val actionScope = node.action.type.name.lowercase()
        val risk = node.risk.name.lowercase()
        val resolved = delegatedPermissions.resolve(principal.id, appScope, actionScope, risk)

        if (resolved == DelegationDecision.DENY) {
            return PlannerPolicyResult(
                PlannerPolicyDecision.DENY,
                "Delegated scope denies this action"
            )
        }

        // ASK_EACH_ACTION can strengthen allow/ask decisions, but it must never
        // weaken an explicit delegated DENY into a generic approval prompt.
        if (device.controlProfile == ControlProfile.ASK_EACH_ACTION) {
            return PlannerPolicyResult(
                PlannerPolicyDecision.ASK_USER,
                "Device profile asks before each action"
            )
        }

        val decision = if (consume) {
            delegatedPermissions.authorizeAndConsume(principal.id, appScope, actionScope, risk)
        } else {
            resolved
        }
        return when (decision) {
            DelegationDecision.ALLOW_ONCE,
            DelegationDecision.ALWAYS_ALLOW -> PlannerPolicyResult(
                PlannerPolicyDecision.ALLOW,
                "Delegated scope allows this action"
            )
            DelegationDecision.ASK -> PlannerPolicyResult(
                PlannerPolicyDecision.ASK_USER,
                "Owner approval required for delegated action"
            )
            DelegationDecision.DENY -> PlannerPolicyResult(
                PlannerPolicyDecision.DENY,
                "Delegated scope denies this action"
            )
        }
    }

    private fun ownerScope(
        principal: Principal,
        device: DeviceDescriptor,
        node: ActionGraphNode
    ): OwnerPlannerPermission = OwnerPlannerPermission(
        principalId = principal.id,
        deviceId = device.id,
        targetScope = targetScope(device, node),
        actionScope = node.action.type.name.lowercase(),
        riskClass = node.risk.name.lowercase(),
        decision = OwnerPlannerPermissionDecision.ASK
    )

    private fun targetScope(device: DeviceDescriptor, node: ActionGraphNode): String =
        node.targetScope ?: node.action.target ?: device.id

    private fun canManageOwnerPolicy(principal: Principal, device: DeviceDescriptor): Boolean =
        (principal.kind == PrincipalKind.OWNER || principal.kind == PrincipalKind.USER) &&
            principal.id == device.ownerPrincipalId

    private fun validPolicyScope(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_OWNER_SCOPE_LENGTH && '\u0000' !in value

    private fun UniversalActionType.isMutating(): Boolean = when (this) {
        UniversalActionType.FILE_READ -> false
        else -> true
    }
}
