package com.veltrix.ultron.core

import com.veltrix.ultron.car.CarSessionRuntime
import com.veltrix.ultron.executor.AccessibilityAction
import com.veltrix.ultron.executor.AccessibilityActionResult
import com.veltrix.ultron.executor.PolicyBoundExecutor
import com.veltrix.ultron.executor.PolicyExecutionRequest
import com.veltrix.ultron.executor.PolicyExecutionState

data class ScreenBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

data class ScreenNodeObservation(
    val bounds: ScreenBounds,
    val text: String? = null,
    val hint: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val enabled: Boolean = false,
    val focused: Boolean = false
)

data class ScreenObservation(
    val packageName: String?,
    val className: String? = null,
    val visibleText: List<String> = emptyList(),
    val nodes: List<ScreenNodeObservation> = emptyList(),
    val fingerprint: String? = null,
    val capturedAtEpochMs: Long = System.currentTimeMillis()
)

fun interface ScreenObserver {
    fun observe(): ScreenObservation
}

fun interface StepActionResolver {
    fun resolve(step: MissionPlanStep, screen: ScreenObservation): AccessibilityAction?
}

fun interface PostActionSettler {
    fun await(step: MissionPlanStep, actionResult: AccessibilityActionResult)
}

data class StepVerification(
    val verified: Boolean,
    val evidence: List<String> = emptyList(),
    val message: String = if (verified) "Verified" else "Verification failed"
)

fun interface StepVerifier {
    fun verify(
        step: MissionPlanStep,
        before: ScreenObservation,
        after: ScreenObservation,
        actionResult: AccessibilityActionResult
    ): StepVerification
}

fun interface VerifiedStepObserver {
    fun onVerified(
        mission: Mission,
        step: MissionPlanStep,
        action: AccessibilityAction,
        before: ScreenObservation,
        after: ScreenObservation
    )
}

enum class RuntimeStepState {
    STEP_VERIFIED,
    MISSION_DONE,
    NEEDS_USER_APPROVAL,
    DENIED,
    KILL_SWITCHED,
    EXECUTION_FAILED,
    VERIFICATION_FAILED,
    NO_ACTIVE_STEP
}

data class RuntimeStepResult(
    val state: RuntimeStepState,
    val mission: Mission,
    val message: String,
    val evidence: List<String> = emptyList()
)

/**
 * Deterministic bridge from a planned mission step to an Android action.
 * It cannot bypass PolicyBoundExecutor and cannot mark work done without verifier evidence.
 */
class MissionRuntime(
    private val coordinator: MissionCoordinator,
    private val executor: PolicyBoundExecutor,
    private val observer: ScreenObserver,
    private val actionResolver: StepActionResolver,
    private val verifier: StepVerifier,
    private val postActionSettler: PostActionSettler = PostActionSettler { _, _ -> },
    private val verifiedStepObserver: VerifiedStepObserver = VerifiedStepObserver { _, _, _, _, _ -> }
) {
    fun executeCurrentStep(missionId: String): RuntimeStepResult {
        val mission = requireNotNull(coordinator.get(missionId)) { "Unknown mission: $missionId" }
        val step = mission.plan.getOrNull(mission.activeStepIndex)
            ?: return RuntimeStepResult(
                state = RuntimeStepState.NO_ACTIVE_STEP,
                mission = mission,
                message = "No active mission step"
            )

        val before = observer.observe()
        val action = actionResolver.resolve(step, before)
            ?: return failed(missionId, RuntimeStepState.EXECUTION_FAILED, "No executable action for step ${step.id}")

        coordinator.execute(missionId, "Executing: ${step.description}")
        val policyResult = executor.execute(
            PolicyExecutionRequest(
                sourceId = mission.request.source.id,
                targetPackage = step.target ?: before.packageName,
                action = action
            )
        )

        when (policyResult.state) {
            PolicyExecutionState.NEEDS_USER_APPROVAL -> {
                val waiting = coordinator.waitForPermission(missionId, "Permission required: ${policyResult.capability}")
                return RuntimeStepResult(
                    state = RuntimeStepState.NEEDS_USER_APPROVAL,
                    mission = waiting,
                    message = waiting.statusMessage
                )
            }
            PolicyExecutionState.DENIED ->
                return failed(missionId, RuntimeStepState.DENIED, "Permission denied: ${policyResult.capability}")
            PolicyExecutionState.KILL_SWITCHED -> {
                val paused = coordinator.interrupt(missionId, InterruptCommand.PAUSE)
                return RuntimeStepResult(
                    state = RuntimeStepState.KILL_SWITCHED,
                    mission = paused,
                    message = policyResult.message ?: "Execution stopped"
                )
            }
            PolicyExecutionState.DISPATCHED -> Unit
        }

        val actionResult = policyResult.actionResult
            ?: return failed(missionId, RuntimeStepState.EXECUTION_FAILED, "Executor returned no action result")
        if (!actionResult.accepted) {
            return failed(missionId, RuntimeStepState.EXECUTION_FAILED, actionResult.message)
        }

        coordinator.verify(missionId, "Verifying: ${step.description}")
        CarSessionRuntime.markVerifying()
        postActionSettler.await(step, actionResult)
        val after = observer.observe()
        val verification = verifier.verify(step, before, after, actionResult)
        if (!verification.verified || verification.evidence.isEmpty()) {
            return failed(
                missionId,
                RuntimeStepState.VERIFICATION_FAILED,
                if (verification.evidence.isEmpty()) "Verification produced no evidence" else verification.message
            )
        }

        runCatching { verifiedStepObserver.onVerified(mission, step, action, before, after) }

        val advanced = coordinator.verifiedStep(
            missionId = missionId,
            evidence = verification.evidence,
            message = verification.message
        )
        return RuntimeStepResult(
            state = if (advanced.state == MissionState.DONE) RuntimeStepState.MISSION_DONE else RuntimeStepState.STEP_VERIFIED,
            mission = advanced,
            message = advanced.statusMessage,
            evidence = verification.evidence
        )
    }

    private fun failed(missionId: String, state: RuntimeStepState, message: String): RuntimeStepResult {
        val mission = coordinator.fail(missionId, message)
        return RuntimeStepResult(state = state, mission = mission, message = message)
    }
}
