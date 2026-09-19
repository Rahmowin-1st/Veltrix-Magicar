package com.veltrix.ultron.core

import com.veltrix.ultron.executor.AccessibilityAction
import com.veltrix.ultron.executor.AccessibilityActionResult
import com.veltrix.ultron.executor.AccessibilityActionTransport
import com.veltrix.ultron.executor.AccessibilityActionType
import com.veltrix.ultron.executor.ExecutionKillSwitch
import com.veltrix.ultron.executor.PolicyBoundExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionRuntimeTest {
    @Test
    fun oneStepMissionBecomesDoneOnlyAfterVerifierEvidence() {
        val source = MissionSource(MissionSourceKind.USER, "owner", "Owner")
        val coordinator = MissionCoordinator()
        val mission = coordinator.receive(MissionRequest(objective = "Tap Continue", source = source))
        coordinator.plan(
            mission.id,
            listOf(
                MissionPlanStep(
                    id = "tap-continue",
                    description = "Tap Continue",
                    capability = "phone.tap",
                    target = "com.example"
                )
            ),
            "Ready to act"
        )

        val permissionEngine = PermissionEngine(
            listOf(
                PermissionRule(
                    sourceId = "owner",
                    capability = "phone.tap",
                    target = "com.example",
                    decision = PermissionDecision.ALWAYS_ALLOW
                )
            )
        )
        val executor = PolicyBoundExecutor(
            permissionEngine = permissionEngine,
            killSwitch = ExecutionKillSwitch(),
            transport = AccessibilityActionTransport { action ->
                AccessibilityActionResult(
                    accepted = true,
                    action = action.type,
                    message = "tap accepted"
                )
            }
        )

        var observationCount = 0
        val runtime = MissionRuntime(
            coordinator = coordinator,
            executor = executor,
            observer = ScreenObserver {
                observationCount += 1
                if (observationCount == 1) {
                    ScreenObservation("com.example", visibleText = listOf("Continue"))
                } else {
                    ScreenObservation("com.example", visibleText = listOf("Success"))
                }
            },
            actionResolver = StepActionResolver { _, _ ->
                AccessibilityAction(AccessibilityActionType.TAP, x = 20f, y = 40f)
            },
            verifier = StepVerifier { _, before, after, _ ->
                StepVerification(
                    verified = "Continue" in before.visibleText && "Success" in after.visibleText,
                    evidence = listOf("ui:text:Success"),
                    message = "Success state observed"
                )
            }
        )

        val result = runtime.executeCurrentStep(mission.id)

        assertEquals(RuntimeStepState.MISSION_DONE, result.state)
        assertEquals(MissionState.DONE, result.mission.state)
        assertTrue("ui:text:Success" in result.mission.evidence)
    }

    @Test
    fun missingVerifierEvidenceFailsMission() {
        val source = MissionSource(MissionSourceKind.USER, "owner", "Owner")
        val coordinator = MissionCoordinator()
        val mission = coordinator.receive(MissionRequest(objective = "Tap", source = source))
        coordinator.plan(
            mission.id,
            listOf(MissionPlanStep("tap", "Tap", "phone.tap", target = "com.example")),
            "Ready"
        )
        val executor = PolicyBoundExecutor(
            permissionEngine = PermissionEngine(
                listOf(PermissionRule("owner", "phone.tap", "com.example", PermissionDecision.ALWAYS_ALLOW))
            ),
            killSwitch = ExecutionKillSwitch(),
            transport = AccessibilityActionTransport { action ->
                AccessibilityActionResult(true, action.type, "ok")
            }
        )
        val runtime = MissionRuntime(
            coordinator = coordinator,
            executor = executor,
            observer = ScreenObserver { ScreenObservation("com.example") },
            actionResolver = StepActionResolver { _, _ -> AccessibilityAction(AccessibilityActionType.TAP, x = 1f, y = 1f) },
            verifier = StepVerifier { _, _, _, _ -> StepVerification(verified = true, evidence = emptyList()) }
        )

        val result = runtime.executeCurrentStep(mission.id)

        assertEquals(RuntimeStepState.VERIFICATION_FAILED, result.state)
        assertEquals(MissionState.FAILED, result.mission.state)
    }
}
