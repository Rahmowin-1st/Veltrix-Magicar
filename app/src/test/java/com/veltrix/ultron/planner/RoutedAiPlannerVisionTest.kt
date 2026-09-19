package com.veltrix.ultron.planner

import com.veltrix.ultron.brain.BrainCapability
import com.veltrix.ultron.brain.BrainModel
import com.veltrix.ultron.brain.BrainRouter
import com.veltrix.ultron.devices.ControlProfile
import com.veltrix.ultron.devices.DeviceCapability
import com.veltrix.ultron.devices.DeviceDescriptor
import com.veltrix.ultron.devices.DeviceKind
import com.veltrix.ultron.devices.DeviceObservation
import com.veltrix.ultron.devices.DevicePlatform
import com.veltrix.ultron.devices.DevicePresence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutedAiPlannerVisionTest {
    @Test
    fun visionContextSkipsTextOnlyModels() {
        val router = BrainRouter()
        router.register(
            BrainModel(
                providerId = "text",
                modelId = "text-model",
                capabilities = setOf(BrainCapability.REASONING, BrainCapability.TOOL_USE),
                freeTier = true,
                priority = 1
            )
        )
        router.register(
            BrainModel(
                providerId = "vision",
                modelId = "vision-model",
                capabilities = setOf(BrainCapability.REASONING, BrainCapability.TOOL_USE, BrainCapability.VISION),
                freeTier = true,
                priority = 2
            )
        )

        var textCalls = 0
        var visionCalls = 0
        val routed = RoutedAiPlanner(router, freeOnly = true)
        routed.register("text", "text-model", AiPlanner {
            textCalls += 1
            PlannerResult.Rejected(PlannerFailure("TEXT_CALLED", "text-only planner should not receive image"))
        })
        routed.register("vision", "vision-model", AiPlanner {
            visionCalls += 1
            PlannerResult.Proposed(
                PlannerProposal(
                    graph = ActionGraph(objective = "inspect", narration = "vision", nodes = emptyList()),
                    providerId = "vision",
                    modelId = "vision-model",
                    confidence = 0.9,
                    explanation = "vision accepted"
                )
            )
        })

        val result = routed.plan(context(withVision = true))

        assertTrue(result is PlannerResult.Proposed)
        assertEquals(0, textCalls)
        assertEquals(1, visionCalls)
    }

    @Test
    fun missingVisionModelFailsClosed() {
        val router = BrainRouter()
        router.register(
            BrainModel(
                providerId = "text",
                modelId = "text-model",
                capabilities = setOf(BrainCapability.REASONING, BrainCapability.TOOL_USE),
                freeTier = true
            )
        )
        val routed = RoutedAiPlanner(router, freeOnly = true)
        routed.register("text", "text-model", AiPlanner {
            PlannerResult.Proposed(
                PlannerProposal(
                    graph = ActionGraph(objective = "bad", narration = "bad", nodes = emptyList()),
                    providerId = "text",
                    modelId = "text-model",
                    confidence = 1.0,
                    explanation = "should not run"
                )
            )
        })

        val result = routed.plan(context(withVision = true))

        assertTrue(result is PlannerResult.Rejected)
        assertEquals("NO_VISION_PLANNER_AVAILABLE", (result as PlannerResult.Rejected).failure.code)
    }

    private fun context(withVision: Boolean): PlannerContext {
        val device = DeviceDescriptor(
            id = "android-local",
            ownerPrincipalId = "owner",
            kind = DeviceKind.PHONE,
            platform = DevicePlatform.ANDROID,
            displayName = "test",
            availableCapabilities = setOf(DeviceCapability.SCREEN_OBSERVE, DeviceCapability.SCREEN_CAPTURE),
            grantedCapabilities = setOf(DeviceCapability.SCREEN_OBSERVE, DeviceCapability.SCREEN_CAPTURE),
            controlProfile = ControlProfile.ASK_EACH_ACTION,
            presence = DevicePresence.ONLINE
        )
        return PlannerContext(
            objective = "inspect current screen",
            constraints = emptyList(),
            device = device,
            observation = DeviceObservation(
                deviceId = "android-local",
                foregroundApp = "com.example.target",
                visibleText = emptyList()
            ),
            visionFrame = if (withVision) {
                PlannerVisionFrame(
                    bytes = byteArrayOf(1, 2, 3),
                    mimeType = "image/jpeg",
                    sourcePackage = "com.example.target",
                    capturedAtEpochMs = 1_000L
                )
            } else null
        )
    }
}
