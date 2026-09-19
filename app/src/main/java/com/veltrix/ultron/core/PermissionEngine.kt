package com.veltrix.ultron.core

enum class PermissionDecision {
    ALLOW_ONCE,
    ALWAYS_ALLOW,
    ASK_EVERY_TIME,
    DENY
}

data class PermissionRule(
    val sourceId: String? = null,
    val capability: String,
    val target: String? = null,
    val decision: PermissionDecision
)

class PermissionEngine(initialRules: List<PermissionRule> = emptyList()) {
    private val rules = initialRules.toMutableList()

    @Synchronized
    fun evaluate(sourceId: String, capability: String, target: String?): PermissionDecision =
        findMatchingRule(sourceId, capability, target)?.second?.decision
            ?: PermissionDecision.ASK_EVERY_TIME

    /**
     * Returns the effective decision and consumes an ALLOW_ONCE rule when it is used.
     * ALWAYS_ALLOW, ASK_EVERY_TIME and DENY remain durable until explicitly changed.
     */
    @Synchronized
    fun authorizeAndConsume(sourceId: String, capability: String, target: String?): PermissionDecision {
        val match = findMatchingRule(sourceId, capability, target)
            ?: return PermissionDecision.ASK_EVERY_TIME
        val (index, rule) = match
        if (rule.decision == PermissionDecision.ALLOW_ONCE) {
            rules.removeAt(index)
        }
        return rule.decision
    }

    @Synchronized
    fun upsert(rule: PermissionRule) {
        rules.removeAll {
            it.sourceId == rule.sourceId &&
                it.capability == rule.capability &&
                it.target == rule.target
        }
        rules += rule
    }

    @Synchronized
    fun snapshot(): List<PermissionRule> = rules.toList()

    private fun findMatchingRule(
        sourceId: String,
        capability: String,
        target: String?
    ): Pair<Int, PermissionRule>? {
        for (index in rules.indices.reversed()) {
            val rule = rules[index]
            val matches =
                (rule.sourceId == null || rule.sourceId == sourceId) &&
                    rule.capability == capability &&
                    (rule.target == null || rule.target == target)
            if (matches) return index to rule
        }
        return null
    }
}
