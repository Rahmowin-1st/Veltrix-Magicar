package com.veltrix.ultron.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.veltrix.ultron.car.CarSessionRuntime
import com.veltrix.ultron.car.CarWakeSource
import com.veltrix.ultron.chat.AndroidConversationMemoryStore
import com.veltrix.ultron.chat.ChatMessage
import com.veltrix.ultron.chat.MessageRole
import com.veltrix.ultron.core.MissionCoordinator
import com.veltrix.ultron.core.MissionPlanStep
import com.veltrix.ultron.core.MissionRequest
import com.veltrix.ultron.core.MissionRuntime
import com.veltrix.ultron.core.MissionSource
import com.veltrix.ultron.core.MissionSourceKind
import com.veltrix.ultron.core.PostActionSettler
import com.veltrix.ultron.core.RuntimeStepResult
import com.veltrix.ultron.core.RuntimeStepState
import com.veltrix.ultron.core.ScreenObservation
import com.veltrix.ultron.core.StepActionResolver
import com.veltrix.ultron.core.StepVerification
import com.veltrix.ultron.core.StepVerifier
import com.veltrix.ultron.core.VerifiedStepObserver
import com.veltrix.ultron.core.PermissionDecision
import com.veltrix.ultron.core.PermissionEngine
import com.veltrix.ultron.core.PermissionRule
import com.veltrix.ultron.executor.AccessibilityAction
import com.veltrix.ultron.executor.AccessibilityActionType
import com.veltrix.ultron.executor.ExecutionKillSwitch
import com.veltrix.ultron.executor.PolicyBoundExecutor
import com.veltrix.ultron.memory.AndroidPersonalMemoryStore
import com.veltrix.ultron.memory.MemoryAudience
import com.veltrix.ultron.memory.PersonalMemoryRuntimeContext
import com.veltrix.ultron.platform.AndroidExecutorBridge
import com.veltrix.ultron.platform.AndroidUndoJournal
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

enum class CommandOutcomeState {
    NEEDS_APPROVAL,
    PAUSED,
    TAKEN_OVER,
    VERIFIED_DONE,
    FAILED,
    CANCELLED,
    UNSUPPORTED
}

data class CommandOutcome(
    val state: CommandOutcomeState,
    val message: String,
    val missionId: String? = null,
    val capability: String? = null,
    val target: String? = null,
    val evidence: List<String> = emptyList()
)

enum class CommandApproval { ALLOW_ONCE, ALWAYS_ALLOW }

/**
 * Deterministic local skills stay first because they are faster and do not need
 * a network/model. Unsupported natural-language requests fall through to the AI
 * ActionGraph runtime without weakening permission or verification boundaries.
 */
class DeterministicCommandEngine(
    private val coordinator: MissionCoordinator = MissionCoordinator(),
    private val permissionEngine: PermissionEngine = PermissionEngine(),
    private val killSwitch: ExecutionKillSwitch = ExecutionKillSwitch(),
    settleMs: Long = 550L
) {
    private val undoCheckpointByMission = ConcurrentHashMap<String, String>()
    private val policyExecutor = PolicyBoundExecutor(
        permissionEngine = permissionEngine,
        killSwitch = killSwitch,
        transport = AndroidExecutorBridge.transport
    )

    private val runtime = MissionRuntime(
        coordinator = coordinator,
        executor = policyExecutor,
        observer = AndroidExecutorBridge.observer,
        actionResolver = StepActionResolver(::resolveAction),
        verifier = StepVerifier(::verifyStep),
        postActionSettler = PostActionSettler { _, _ ->
            if (settleMs > 0L) Thread.sleep(settleMs)
        },
        verifiedStepObserver = VerifiedStepObserver { mission, step, action, before, after ->
            if (!undoCheckpointByMission.containsKey(mission.id)) {
                AndroidUndoJournal.recordVerified(
                    missionId = mission.id,
                    description = step.description,
                    action = action,
                    before = before,
                    after = after
                )
            }
        }
    )

    fun canUndo(): Boolean = AndroidUndoJournal.latest() != null

    fun submit(rawCommand: String): CommandOutcome {
        val command = rawCommand.trim()
        if (command.isEmpty()) return CommandOutcome(CommandOutcomeState.UNSUPPORTED, "Command is empty")
        if (normalize(command) in UNDO_COMMANDS) return submitUndo()

        val screen = AndroidExecutorBridge.observer.observe()
        val step = parse(command, screen)
            ?: return CommandOutcome(
                state = CommandOutcomeState.UNSUPPORTED,
                message = "No deterministic local skill matched"
            )

        val mission = coordinator.receive(
            MissionRequest(
                objective = command,
                source = OWNER_SOURCE
            )
        )
        coordinator.plan(
            missionId = mission.id,
            steps = listOf(step),
            narration = "Plan: ${step.description}"
        )
        return toOutcome(runtime.executeCurrentStep(mission.id))
    }

    fun approve(missionId: String, approval: CommandApproval): CommandOutcome {
        val mission = coordinator.get(missionId)
            ?: return CommandOutcome(CommandOutcomeState.FAILED, "Mission no longer exists")
        val step = mission.plan.getOrNull(mission.activeStepIndex)
            ?: return CommandOutcome(CommandOutcomeState.FAILED, "Mission has no active step", missionId)

        permissionEngine.upsert(
            PermissionRule(
                sourceId = mission.request.source.id,
                capability = step.capability,
                target = step.target,
                decision = when (approval) {
                    CommandApproval.ALLOW_ONCE -> PermissionDecision.ALLOW_ONCE
                    CommandApproval.ALWAYS_ALLOW -> PermissionDecision.ALWAYS_ALLOW
                }
            )
        )
        return finalizeUndo(runtime.executeCurrentStep(missionId))
    }

    fun cancel(missionId: String): CommandOutcome {
        undoCheckpointByMission.remove(missionId)
        val mission = coordinator.get(missionId)
            ?: return CommandOutcome(CommandOutcomeState.CANCELLED, "Cancelled")
        val cancelled = coordinator.interrupt(mission.id, com.veltrix.ultron.core.InterruptCommand.CANCEL)
        return CommandOutcome(
            state = CommandOutcomeState.CANCELLED,
            message = cancelled.statusMessage,
            missionId = mission.id
        )
    }

    private fun submitUndo(): CommandOutcome {
        val checkpoint = AndroidUndoJournal.latest()
            ?: return CommandOutcome(
                state = CommandOutcomeState.UNSUPPORTED,
                message = "Nothing can be safely undone right now"
            )
        val current = AndroidExecutorBridge.observer.observe()
        if (current.packageName != checkpoint.expectedCurrentPackage) {
            AndroidUndoJournal.clear()
            return CommandOutcome(
                state = CommandOutcomeState.FAILED,
                message = "Undo checkpoint expired because the foreground app changed"
            )
        }

        val step = MissionPlanStep(
            id = "undo-${checkpoint.id.take(8)}",
            description = "Undo: restore ${checkpoint.restorePackage}",
            capability = "phone.open_app",
            target = checkpoint.restorePackage
        )
        val mission = coordinator.receive(
            MissionRequest(
                objective = "Undo ${checkpoint.description}",
                source = OWNER_SOURCE
            )
        )
        coordinator.plan(
            missionId = mission.id,
            steps = listOf(step),
            narration = "Plan: restore the verified pre-action app state"
        )
        undoCheckpointByMission[mission.id] = checkpoint.id
        return finalizeUndo(runtime.executeCurrentStep(mission.id))
    }

    private fun finalizeUndo(result: RuntimeStepResult): CommandOutcome {
        val checkpointId = undoCheckpointByMission[result.mission.id] ?: return toOutcome(result)
        return when (result.state) {
            RuntimeStepState.NEEDS_USER_APPROVAL -> toOutcome(result)
            RuntimeStepState.MISSION_DONE,
            RuntimeStepState.STEP_VERIFIED -> {
                undoCheckpointByMission.remove(result.mission.id)
                if (!AndroidUndoJournal.consume(checkpointId)) AndroidUndoJournal.clear()
                CommandOutcome(
                    state = CommandOutcomeState.VERIFIED_DONE,
                    message = "Undo verified",
                    missionId = result.mission.id,
                    evidence = (result.evidence + "rollback:verified").distinct()
                )
            }
            RuntimeStepState.VERIFICATION_FAILED -> {
                undoCheckpointByMission.remove(result.mission.id)
                AndroidUndoJournal.clear()
                toOutcome(result).copy(message = "Undo could not be verified; rollback checkpoint cleared")
            }
            RuntimeStepState.DENIED,
            RuntimeStepState.KILL_SWITCHED,
            RuntimeStepState.EXECUTION_FAILED,
            RuntimeStepState.NO_ACTIVE_STEP -> {
                undoCheckpointByMission.remove(result.mission.id)
                toOutcome(result)
            }
        }
    }

    private fun parse(command: String, screen: ScreenObservation): MissionPlanStep? {
        val normalized = normalize(command)
        return when {
            normalized == "settings" || normalized == "open settings" -> MissionPlanStep(
                id = "open-settings",
                description = "Open Android Settings",
                capability = "phone.open_app",
                target = "com.android.settings"
            )

            normalized.startsWith("open package ") -> {
                val packageName = command.substringAfter("open package ", "").trim()
                if (!PACKAGE_PATTERN.matches(packageName)) null else MissionPlanStep(
                    id = "open-package",
                    description = "Open $packageName",
                    capability = "phone.open_app",
                    target = packageName
                )
            }

            normalized == "home" || normalized == "go home" -> screen.packageName?.let { target ->
                MissionPlanStep(
                    id = "home",
                    description = "Go to Android home",
                    capability = "phone.home",
                    target = target
                )
            }

            normalized == "back" || normalized == "go back" -> screen.packageName?.let { target ->
                MissionPlanStep(
                    id = "back",
                    description = "Go back",
                    capability = "phone.back",
                    target = target
                )
            }

            normalized == "scroll" || normalized == "scroll down" -> screen.packageName?.let { target ->
                MissionPlanStep(
                    id = "scroll",
                    description = "Scroll current screen",
                    capability = "phone.scroll",
                    target = target
                )
            }

            else -> null
        }
    }

    private fun resolveAction(step: MissionPlanStep, screen: ScreenObservation): AccessibilityAction? =
        when (step.capability) {
            "phone.open_app" -> AccessibilityAction(
                type = AccessibilityActionType.OPEN_APP,
                packageName = step.target
            )
            "phone.home" -> AccessibilityAction(AccessibilityActionType.GLOBAL_HOME)
            "phone.back" -> AccessibilityAction(AccessibilityActionType.GLOBAL_BACK)
            "phone.scroll" -> AccessibilityAction(AccessibilityActionType.SCROLL_FORWARD)
            else -> null
        }

    private fun verifyStep(
        step: MissionPlanStep,
        before: ScreenObservation,
        after: ScreenObservation,
        actionResult: com.veltrix.ultron.executor.AccessibilityActionResult
    ): StepVerification {
        if (!actionResult.accepted) return StepVerification(false, message = actionResult.message)

        return when (step.capability) {
            "phone.open_app" -> {
                val verified = after.packageName == step.target
                StepVerification(
                    verified = verified,
                    evidence = if (verified) listOf("package:${after.packageName}") else emptyList(),
                    message = if (verified) "Target app observed" else "Target app was not observed"
                )
            }
            "phone.home", "phone.back" -> {
                val changed = before.packageName != after.packageName || before.className != after.className
                StepVerification(
                    verified = changed,
                    evidence = if (changed) listOf(
                        "before:${before.packageName}/${before.className}",
                        "after:${after.packageName}/${after.className}"
                    ) else emptyList(),
                    message = if (changed) "Navigation change observed" else "No navigation change observed"
                )
            }
            "phone.scroll" -> {
                val changed = before.visibleText != after.visibleText
                StepVerification(
                    verified = changed,
                    evidence = if (changed) listOf("visible-content-changed") else emptyList(),
                    message = if (changed) "Scroll change observed" else "No scroll change observed"
                )
            }
            else -> StepVerification(false, message = "Unsupported verification")
        }
    }

    private fun toOutcome(result: RuntimeStepResult): CommandOutcome {
        val step = result.mission.plan.getOrNull(result.mission.activeStepIndex)
            ?: result.mission.plan.lastOrNull()
        return when (result.state) {
            RuntimeStepState.NEEDS_USER_APPROVAL -> CommandOutcome(
                state = CommandOutcomeState.NEEDS_APPROVAL,
                message = "${step?.description ?: "Action"} needs permission",
                missionId = result.mission.id,
                capability = step?.capability,
                target = step?.target
            )
            RuntimeStepState.MISSION_DONE, RuntimeStepState.STEP_VERIFIED -> CommandOutcome(
                state = CommandOutcomeState.VERIFIED_DONE,
                message = result.message,
                missionId = result.mission.id,
                evidence = result.evidence
            )
            RuntimeStepState.DENIED,
            RuntimeStepState.KILL_SWITCHED,
            RuntimeStepState.EXECUTION_FAILED,
            RuntimeStepState.VERIFICATION_FAILED,
            RuntimeStepState.NO_ACTIVE_STEP -> CommandOutcome(
                state = CommandOutcomeState.FAILED,
                message = result.message,
                missionId = result.mission.id,
                evidence = result.evidence
            )
        }
    }

    private fun normalize(command: String): String =
        command.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

    companion object {
        private val OWNER_SOURCE = MissionSource(MissionSourceKind.USER, "owner", "Owner")
        private val PACKAGE_PATTERN = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
        private val UNDO_COMMANDS = setOf("undo", "undo last action", "revert last action")
    }
}

/** Process-scoped async facade used by Compose, voice and assistant entry points. */
object UltronCommandRuntime {
    private val deterministic = DeterministicCommandEngine()
    @Volatile private var ai: AndroidAiCommandRuntime? = null
    @Volatile private var conversation: AndroidConversationMemoryStore? = null
    @Volatile private var personalMemoryContext: PersonalMemoryRuntimeContext? = null
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "veltrix-command-runtime").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun initialize(context: Context) {
        if (ai != null && conversation != null && personalMemoryContext != null) return
        synchronized(this) {
            val appContext = context.applicationContext
            if (ai == null) ai = AndroidAiCommandRuntime(appContext)
            if (conversation == null) conversation = AndroidConversationMemoryStore(appContext)
            if (personalMemoryContext == null) {
                personalMemoryContext = PersonalMemoryRuntimeContext(AndroidPersonalMemoryStore.create(appContext))
            }
        }
    }

    fun submit(command: String, callback: (CommandOutcome) -> Unit) = runAsync({
        CarSessionRuntime.beginUserSession(CarWakeSource.DIRECT_COMMAND)
        val clean = command.trim()
        if (isClearMemoryCommand(clean)) {
            conversation?.clear()
            CommandOutcome(CommandOutcomeState.VERIFIED_DONE, "Conversation memory cleared")
        } else {
            val memory = conversation
            memory?.append(MessageRole.USER, clean)
            val local = deterministic.submit(clean)
            val outcome = if (local.state != CommandOutcomeState.UNSUPPORTED) {
                local
            } else {
                val conversationHints = memory?.plannerHints(excludeLastMessages = 1).orEmpty()
                val personalHints = personalMemoryContext
                    ?.hintsFor(MemoryAudience.MAIN_ASSISTANT)
                    .orEmpty()
                ai?.submit(
                    clean,
                    memoryHints = (conversationHints + personalHints).takeLast(MAX_AI_MEMORY_HINTS)
                ) ?: CommandOutcome(
                    CommandOutcomeState.UNSUPPORTED,
                    "AI runtime is not initialized"
                )
            }
            rememberOutcome(clean, outcome)
            settleCarSession(outcome)
            outcome
        }
    }, callback)

    fun approve(missionId: String, approval: CommandApproval, callback: (CommandOutcome) -> Unit) =
        runAsync({
            CarSessionRuntime.beginUserSession(CarWakeSource.USER_APPROVAL)
            val aiRuntime = ai
            val outcome = if (aiRuntime?.hasSession(missionId) == true) {
                aiRuntime.approve(missionId, approval)
            } else {
                deterministic.approve(missionId, approval)
            }
            rememberOutcome(null, outcome)
            settleCarSession(outcome)
            outcome
        }, callback)

    fun cancel(missionId: String, callback: (CommandOutcome) -> Unit) =
        runAsync({
            val aiRuntime = ai
            val outcome = if (aiRuntime?.hasSession(missionId) == true) aiRuntime.cancel(missionId)
            else deterministic.cancel(missionId)
            rememberOutcome(null, outcome)
            CarSessionRuntime.finish()
            outcome
        }, callback)

    /** Immediate one-shot voice/UI controls: never queued behind an active AI execution. */
    fun pauseActive(callback: (CommandOutcome) -> Unit = {}) = runImmediateControl(
        block = { ai?.pauseActive() ?: CommandOutcome(CommandOutcomeState.UNSUPPORTED, "AI runtime is not initialized") },
        callback = callback
    )

    fun takeOverActive(callback: (CommandOutcome) -> Unit = {}) = runImmediateControl(
        block = { ai?.takeOverActive() ?: CommandOutcome(CommandOutcomeState.UNSUPPORTED, "AI runtime is not initialized") },
        callback = callback
    )

    fun cancelActive(callback: (CommandOutcome) -> Unit = {}) = runImmediateControl(
        block = { ai?.cancelActive() ?: CommandOutcome(CommandOutcomeState.UNSUPPORTED, "AI runtime is not initialized") },
        callback = callback
    )

    /** Resume may replan/network, so it uses the forward worker after the old lane has yielded. */
    fun resumeActive(callback: (CommandOutcome) -> Unit = {}) = runAsync({
        CarSessionRuntime.beginUserSession(CarWakeSource.USER_APPROVAL)
        val outcome = ai?.resumeActive()
            ?: CommandOutcome(CommandOutcomeState.UNSUPPORTED, "AI runtime is not initialized")
        rememberOutcome(null, outcome)
        settleCarSession(outcome)
        outcome
    }, callback)

    fun canUndo(): Boolean = deterministic.canUndo()

    fun providerStatus(): AndroidAiCommandRuntime.ProviderStatus? = ai?.providerStatus()

    fun conversationHistory(limit: Int = 30): List<ChatMessage> =
        conversation?.recent(limit).orEmpty()

    fun conversationMemoryCount(): Int = conversation?.count() ?: 0

    fun clearConversationMemory() {
        conversation?.clear()
    }

    private fun rememberOutcome(objective: String?, outcome: CommandOutcome) {
        val memory = conversation ?: return
        memory.append(MessageRole.VELTRIX, outcome.message, outcome.missionId)
        memory.updateFollowUp { current ->
            current.copy(
                activeMissionId = outcome.missionId.takeIf {
                    outcome.state == CommandOutcomeState.NEEDS_APPROVAL ||
                        outcome.state == CommandOutcomeState.PAUSED ||
                        outcome.state == CommandOutcomeState.TAKEN_OVER
                },
                activeAppPackage = outcome.target?.takeIf(PACKAGE_PATTERN::matches) ?: current.activeAppPackage,
                lastIntent = objective ?: current.lastIntent
            )
        }
    }

    private fun settleCarSession(outcome: CommandOutcome) {
        if (outcome.state == CommandOutcomeState.NEEDS_APPROVAL ||
            outcome.state == CommandOutcomeState.PAUSED ||
            outcome.state == CommandOutcomeState.TAKEN_OVER
        ) return
        CarSessionRuntime.finish()
    }

    private fun isClearMemoryCommand(command: String): Boolean =
        command.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim() in CLEAR_MEMORY_COMMANDS

    private fun runImmediateControl(block: () -> CommandOutcome, callback: (CommandOutcome) -> Unit) {
        val outcome = runCatching(block).getOrElse { error ->
            CommandOutcome(
                state = CommandOutcomeState.FAILED,
                message = "Runtime error: ${error.javaClass.simpleName}"
            )
        }
        rememberOutcome(null, outcome)
        mainHandler.post { callback(outcome) }
    }

    private fun runAsync(block: () -> CommandOutcome, callback: (CommandOutcome) -> Unit) {
        worker.execute {
            val outcome = runCatching(block).getOrElse { error ->
                CommandOutcome(
                    state = CommandOutcomeState.FAILED,
                    message = "Runtime error: ${error.javaClass.simpleName}"
                )
            }
            mainHandler.post { callback(outcome) }
        }
    }

    private const val MAX_AI_MEMORY_HINTS = 30
    private val PACKAGE_PATTERN = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
    private val CLEAR_MEMORY_COMMANDS = setOf("clear memory", "forget conversation", "new conversation")
}
