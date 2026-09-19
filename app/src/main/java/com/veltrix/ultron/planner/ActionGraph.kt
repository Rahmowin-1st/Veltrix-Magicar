package com.veltrix.ultron.planner

import com.veltrix.ultron.devices.DeviceCapability
import com.veltrix.ultron.devices.DeviceDescriptor
import com.veltrix.ultron.devices.DeviceObservation
import com.veltrix.ultron.devices.UniversalAction
import com.veltrix.ultron.devices.UniversalActionType
import java.util.UUID

enum class PlannerRisk { LOW, MEDIUM, HIGH, CRITICAL }
enum class VerificationMode { APP, WINDOW, TEXT_PRESENT, TEXT_ABSENT, URI_PREFIX, CONTENT_CHANGED, ACTION_ACCEPTED }
enum class PlannerDecision { EXECUTE, ASK_USER, WAIT, DONE, FAIL }

data class VerificationRule(
    val mode: VerificationMode,
    val expected: String? = null,
    val description: String
)

data class ActionGraphNode(
    val id: String,
    val description: String,
    val action: UniversalAction,
    val requiredCapability: DeviceCapability,
    val targetScope: String? = null,
    val risk: PlannerRisk = PlannerRisk.LOW,
    val verification: VerificationRule,
    val maxAttempts: Int = 2
)

data class ActionGraph(
    val id: String = UUID.randomUUID().toString(),
    val objective: String,
    val narration: String,
    val nodes: List<ActionGraphNode>,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Ephemeral screen image supplied only after a user-approved one-shot capture.
 * The frame is untrusted observation data, never an instruction or durable grant.
 */
data class PlannerVisionFrame(
    val bytes: ByteArray,
    val mimeType: String,
    val sourcePackage: String?,
    val capturedAtEpochMs: Long
)

data class PlannerContext(
    val objective: String,
    val constraints: List<String>,
    val device: DeviceDescriptor,
    val observation: DeviceObservation,
    val recentEvidence: List<String> = emptyList(),
    val memoryHints: List<String> = emptyList(),
    val failedStepDescriptions: List<String> = emptyList(),
    val visionFrame: PlannerVisionFrame? = null
)

data class PlannerProposal(
    val graph: ActionGraph,
    val providerId: String,
    val modelId: String,
    val confidence: Double,
    val explanation: String
)

data class PlannerFailure(
    val code: String,
    val message: String,
    val retryable: Boolean = false
)

sealed interface PlannerResult {
    data class Proposed(val proposal: PlannerProposal) : PlannerResult
    data class Rejected(val failure: PlannerFailure) : PlannerResult
}

fun interface AiPlanner {
    fun plan(context: PlannerContext): PlannerResult

    fun replan(context: PlannerContext, previous: PlannerProposal, reason: String): PlannerResult = plan(context)
}

/** Maps a validated graph node into the already protocol-neutral executor action. */
fun ActionGraphNode.toUniversalAction(): UniversalAction = action

internal fun UniversalActionType.defaultCapability(): DeviceCapability = when (this) {
    UniversalActionType.OPEN_APP -> DeviceCapability.OPEN_APP
    UniversalActionType.CLICK -> DeviceCapability.UI_CLICK
    UniversalActionType.TYPE_TEXT -> DeviceCapability.UI_TYPE
    UniversalActionType.SCROLL -> DeviceCapability.UI_SCROLL
    UniversalActionType.TAP -> DeviceCapability.UI_GESTURE
    UniversalActionType.BACK,
    UniversalActionType.HOME -> DeviceCapability.PHONE_AUTOMATION
    UniversalActionType.WINDOW_FOCUS -> DeviceCapability.WINDOW_CONTROL
    UniversalActionType.KEYBOARD_SHORTCUT -> DeviceCapability.KEYBOARD_SHORTCUT
    UniversalActionType.BROWSER_NAVIGATE -> DeviceCapability.BROWSER_NAVIGATE
    UniversalActionType.FILE_READ -> DeviceCapability.FILE_READ
    UniversalActionType.FILE_WRITE -> DeviceCapability.FILE_WRITE
    UniversalActionType.FILE_UPLOAD -> DeviceCapability.FILE_UPLOAD
    UniversalActionType.FILE_DOWNLOAD -> DeviceCapability.FILE_DOWNLOAD
}
