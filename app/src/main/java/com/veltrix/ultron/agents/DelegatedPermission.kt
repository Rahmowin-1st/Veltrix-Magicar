package com.veltrix.ultron.agents

enum class DelegationDecision { ALLOW_ONCE, ALWAYS_ALLOW, ASK, DENY }

data class DelegatedPermission(
    val principalId: String,
    val appScope: String,
    val actionScope: String,
    val riskClass: String,
    val decision: DelegationDecision
)

class DelegatedPermissionStore {
    private val permissions = linkedMapOf<String, DelegatedPermission>()

    private fun key(p: DelegatedPermission) =
        listOf(p.principalId, p.appScope, p.actionScope, p.riskClass).joinToString("|")

    private fun key(principalId: String, app: String, action: String, risk: String) =
        listOf(principalId, app, action, risk).joinToString("|")

    @Synchronized
    fun put(permission: DelegatedPermission) {
        permissions[key(permission)] = permission
    }

    @Synchronized
    fun resolve(principalId: String, app: String, action: String, risk: String): DelegationDecision =
        permissions[key(principalId, app, action, risk)]?.decision ?: DelegationDecision.ASK

    /** ALLOW_ONCE is consumed only when the exact delegated scope is authorized. */
    @Synchronized
    fun authorizeAndConsume(
        principalId: String,
        app: String,
        action: String,
        risk: String
    ): DelegationDecision {
        val permissionKey = key(principalId, app, action, risk)
        val permission = permissions[permissionKey] ?: return DelegationDecision.ASK
        if (permission.decision == DelegationDecision.ALLOW_ONCE) {
            permissions.remove(permissionKey)
        }
        return permission.decision
    }

    @Synchronized
    fun revoke(principalId: String, app: String, action: String, risk: String): Boolean =
        permissions.remove(key(principalId, app, action, risk)) != null

    @Synchronized
    fun snapshot(): List<DelegatedPermission> = permissions.values.toList()
}
