package com.veltrix.ultron.planner

import com.veltrix.ultron.brain.BrainCapability
import com.veltrix.ultron.brain.BrainRequest
import com.veltrix.ultron.brain.BrainRouter

class RoutedAiPlanner(
    private val router: BrainRouter,
    private val freeOnly: Boolean = true
) : AiPlanner {
    private val planners = linkedMapOf<Pair<String, String>, AiPlanner>()

    @Synchronized
    fun register(providerId: String, modelId: String, planner: AiPlanner) {
        planners[providerId to modelId] = planner
    }

    override fun plan(context: PlannerContext): PlannerResult = execute(context, previous = null, reason = null)

    override fun replan(
        context: PlannerContext,
        previous: PlannerProposal,
        reason: String
    ): PlannerResult = execute(context, previous, reason)

    private fun execute(
        context: PlannerContext,
        previous: PlannerProposal?,
        reason: String?
    ): PlannerResult {
        val required = linkedSetOf(BrainCapability.REASONING, BrainCapability.TOOL_USE).apply {
            if (context.visionFrame != null) add(BrainCapability.VISION)
        }
        val candidates = router.route(
            BrainRequest(
                required = required,
                preferFast = false,
                freeOnly = freeOnly
            )
        )
        val failures = mutableListOf<String>()
        for (candidate in candidates) {
            val planner = synchronized(this) { planners[candidate.providerId to candidate.modelId] } ?: continue
            val result = if (previous != null && reason != null) {
                planner.replan(context, previous, reason)
            } else {
                planner.plan(context)
            }
            when (result) {
                is PlannerResult.Proposed -> return result
                is PlannerResult.Rejected -> {
                    failures += "${candidate.providerId}/${candidate.modelId}:${result.failure.code}"
                    if (!result.failure.retryable && result.failure.code != "SECRET_MISSING") {
                        continue
                    }
                }
            }
        }
        return PlannerResult.Rejected(
            PlannerFailure(
                code = if (context.visionFrame != null && candidates.isEmpty()) "NO_VISION_PLANNER_AVAILABLE" else "NO_PLANNER_AVAILABLE",
                message = if (failures.isEmpty()) {
                    if (context.visionFrame != null) {
                        "No healthy registered vision-capable planner model is available"
                    } else {
                        "No healthy registered planner model is available"
                    }
                } else {
                    "Planner fallbacks exhausted: ${failures.joinToString()}"
                },
                retryable = true
            )
        )
    }
}
