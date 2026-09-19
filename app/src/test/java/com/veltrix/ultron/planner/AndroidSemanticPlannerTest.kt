package com.veltrix.ultron.planner

import com.veltrix.ultron.devices.ControlProfile
import com.veltrix.ultron.devices.DeviceCapability
import com.veltrix.ultron.devices.DeviceDescriptor
import com.veltrix.ultron.devices.DeviceKind
import com.veltrix.ultron.devices.DeviceObservation
import com.veltrix.ultron.devices.DevicePlatform
import com.veltrix.ultron.devices.DevicePresence
import com.veltrix.ultron.devices.UniversalAction
import com.veltrix.ultron.devices.UniversalActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSemanticPlannerTest {
    private val device = DeviceDescriptor(
        id = "phone",
        ownerPrincipalId = "owner",
        kind = DeviceKind.PHONE,
        platform = DevicePlatform.ANDROID,
        displayName = "Phone",
        availableCapabilities = setOf(DeviceCapability.OPEN_APP),
        grantedCapabilities = setOf(DeviceCapability.OPEN_APP),
        controlProfile = ControlProfile.MAX_APPROVED,
        presence = DevicePresence.ONLINE
    )

    @Test
    fun humanAppLabelIsCanonicalizedBeforeValidationAndExecution() {
        var observedHints = emptyList<String>()
        val delegate = AiPlanner { context ->
            observedHints = context.memoryHints
            PlannerResult.Proposed(proposal("Telegram"))
        }
        val resolver = fakeResolver(mapOf("Telegram" to "org.telegram.messenger"))
        val planner = AndroidSemanticPlanner(delegate, resolver)

        val result = planner.plan(context())

        assertTrue(result is PlannerResult.Proposed)
        val node = (result as PlannerResult.Proposed).proposal.graph.nodes.single()
        assertEquals("org.telegram.messenger", node.action.target)
        assertEquals("org.telegram.messenger", node.targetScope)
        assertEquals("org.telegram.messenger", node.verification.expected)
        assertTrue(observedHints.any { it.contains("Telegram=>org.telegram.messenger") })
    }

    @Test
    fun unknownAppNeverFallsThroughAsInventedPackageTarget() {
        val planner = AndroidSemanticPlanner(
            delegate = AiPlanner { PlannerResult.Proposed(proposal("Imaginary Messenger")) },
            catalog = fakeResolver(emptyMap())
        )

        val result = planner.plan(context())

        assertTrue(result is PlannerResult.Rejected)
        assertEquals("APP_NOT_RESOLVED", (result as PlannerResult.Rejected).failure.code)
    }

    @Test
    fun browserUriVerificationFallsBackToActionAcceptedOnAndroid() {
        val delegate = AiPlanner { PlannerResult.Proposed(browserProposal()) }
        val planner = AndroidSemanticPlanner(delegate, fakeResolver(emptyMap()))

        val result = planner.plan(context())

        assertTrue(result is PlannerResult.Proposed)
        val node = (result as PlannerResult.Proposed).proposal.graph.nodes.single()
        assertEquals(UniversalActionType.BROWSER_NAVIGATE, node.action.type)
        assertEquals(VerificationMode.ACTION_ACCEPTED, node.verification.mode)
        assertNull(node.verification.expected)
        assertEquals("Android accepted browser navigation intent", node.verification.description)
    }

    private fun context() = PlannerContext(
        objective = "Open Telegram",
        constraints = emptyList(),
        device = device,
        observation = DeviceObservation(deviceId = "phone", foregroundApp = "com.veltrix.ultron")
    )

    private fun proposal(target: String) = PlannerProposal(
        graph = ActionGraph(
            objective = "Open $target",
            narration = "Open app",
            nodes = listOf(
                ActionGraphNode(
                    id = "open",
                    description = "Open $target",
                    action = UniversalAction(UniversalActionType.OPEN_APP, target = target),
                    requiredCapability = DeviceCapability.OPEN_APP,
                    targetScope = target,
                    verification = VerificationRule(VerificationMode.APP, target, "App observed")
                )
            )
        ),
        providerId = "test",
        modelId = "test",
        confidence = 1.0,
        explanation = "test"
    )

    private fun browserProposal() = PlannerProposal(
        graph = ActionGraph(
            objective = "Open website",
            narration = "Navigate browser",
            nodes = listOf(
                ActionGraphNode(
                    id = "web",
                    description = "Open website",
                    action = UniversalAction(
                        type = UniversalActionType.BROWSER_NAVIGATE,
                        target = "https://example.com/path"
                    ),
                    requiredCapability = DeviceCapability.BROWSER_NAVIGATE,
                    targetScope = "https://example.com/path",
                    verification = VerificationRule(
                        VerificationMode.URI_PREFIX,
                        "https://example.com",
                        "URL observed"
                    )
                )
            )
        ),
        providerId = "test",
        modelId = "test",
        confidence = 1.0,
        explanation = "test"
    )

    private fun fakeResolver(mapping: Map<String, String>) = object : AndroidAppTargetResolver {
        override fun resolve(raw: String): String? = mapping[raw]
        override fun plannerHint(limit: Int): String = mapping.entries.joinToString(
            prefix = "installed_android_apps=[",
            postfix = "]"
        ) { (label, pkg) -> "$label=>$pkg" }
    }
}
