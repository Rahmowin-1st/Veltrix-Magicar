package com.veltrix.ultron.planner

import com.veltrix.ultron.devices.DeviceObservation

/**
 * Final pre-model privacy boundary for Android semantic/vision observation.
 *
 * Owner HARD DENY is evaluated before any screen-derived context reaches an AI
 * provider. When the foreground or vision-source resource is denied, the model
 * receives no package/window/text/URI/frame, no recent execution evidence, and no
 * memory hints from that planning turn. The user's explicit objective is retained
 * because it was supplied by the user rather than observed from the denied resource.
 */
class PlannerObservationPrivacyFirewall(
    private val ownerPermissions: OwnerPlannerPermissionStore
) {
    fun filter(context: PlannerContext): PlannerContext {
        val targets = buildList {
            context.observation.foregroundApp
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let(::add)
            context.visionFrame?.sourcePackage
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let(::add)
        }.distinct()

        val denied = targets.any { target ->
            ownerPermissions.isHardDenied(
                ownerPrincipalId = context.device.ownerPrincipalId,
                deviceId = context.device.id,
                targetScope = target,
                actionScope = OBSERVE_ACTION_SCOPE,
                riskClass = OBSERVE_RISK_CLASS
            )
        }
        if (!denied) return context

        return context.copy(
            constraints = (context.constraints + PRIVACY_BOUNDARY_CONSTRAINT).distinct(),
            observation = DeviceObservation(
                deviceId = context.observation.deviceId,
                observedAtEpochMs = context.observation.observedAtEpochMs
            ),
            recentEvidence = emptyList(),
            memoryHints = emptyList(),
            failedStepDescriptions = emptyList(),
            visionFrame = null
        )
    }

    companion object {
        const val OBSERVE_ACTION_SCOPE = "observe"
        const val OBSERVE_RISK_CLASS = "low"
        const val PRIVACY_BOUNDARY_CONSTRAINT =
            "Current device content is unavailable due to an owner privacy boundary. Do not infer, reconstruct, request, or reveal hidden content."
    }
}
