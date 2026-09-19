package com.veltrix.ultron.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.veltrix.ultron.car.CarSessionRuntime
import com.veltrix.ultron.car.CarWakeSource
import com.veltrix.ultron.runtime.CommandApproval
import com.veltrix.ultron.runtime.CommandOutcome
import com.veltrix.ultron.runtime.CommandOutcomeState
import com.veltrix.ultron.runtime.UltronCommandRuntime
import com.veltrix.ultron.service.MagicarActiveSurfaceService
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-scoped one-shot assistant session.
 *
 * The user sees only the edge glow / top waveform. The assistant keeps hearing
 * while a mission is executing, automatically approves ordinary reversible UI
 * work, replans through the runtime, and returns to idle only after the final
 * response or a terminal failure/cancel.
 */
class MagicarAssistantOrchestrator(
    context: Context,
    private val onIdle: () -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val live = GeminiLiveVoiceController(appContext)
    private val audio = AssistantAudioSession(appContext)
    private val main = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(false)
    private val missionRunning = AtomicBoolean(false)

    @Volatile private var pendingSensitive: PendingSensitive? = null
    @Volatile private var activeToolCall: GeminiLiveToolCall? = null
    @Volatile private var finishAfterTurn = false
    @Volatile private var destroyed = false

    init {
        UltronCommandRuntime.initialize(appContext)
        live.listener = object : GeminiLiveVoiceListener {
            override fun onStateChanged(state: GeminiLiveVoiceState) {
                if (state == GeminiLiveVoiceState.IDLE && active.get() && !missionRunning.get()) {
                    finishSession()
                }
            }

            override fun onInputTranscript(text: String, final: Boolean) = Unit

            override fun onOutputTranscript(text: String) = Unit

            override fun onInputLevel(level: Float) {
                if (active.get()) MagicarActiveSurfaceService.updateLevel(appContext, level)
            }

            override fun onTurnComplete() {
                if (finishAfterTurn && !missionRunning.get() && pendingSensitive == null) {
                    finishSession()
                    return
                }
                // Pure conversational one-shot: answer, then disappear.
                if (!missionRunning.get() && pendingSensitive == null && activeToolCall == null) {
                    finishSession()
                }
            }

            override fun onToolCall(call: GeminiLiveToolCall) {
                when (call.name) {
                    "execute_user_task" -> executeUserTask(call)
                    "cancel_active_task" -> controlTask(call) { done ->
                        UltronCommandRuntime.cancelActive(done)
                    }
                    "pause_active_task" -> controlTask(call) { done ->
                        UltronCommandRuntime.pauseActive(done)
                    }
                    "resume_active_task" -> controlTask(call) { done ->
                        UltronCommandRuntime.resumeActive(done)
                    }
                    "confirm_pending_action" -> confirmPending(call)
                    "deny_pending_action" -> denyPending(call)
                    else -> live.sendToolResult(
                        call,
                        state = "FAILED",
                        message = "Unsupported Magicar tool."
                    )
                }
            }

            override fun onToolCancellation(ids: List<String>) {
                val current = activeToolCall
                if (current != null && current.id in ids) {
                    UltronCommandRuntime.pauseActive {
                        missionRunning.set(false)
                        live.setMissionActive(false)
                        activeToolCall = null
                    }
                }
            }

            override fun onError(message: String) {
                finishSession()
            }
        }
    }

    fun wake(source: CarWakeSource = CarWakeSource.ASSISTANT_INVOCATION): Boolean {
        if (destroyed) return false
        if (!active.compareAndSet(false, true)) return true

        finishAfterTurn = false
        pendingSensitive = null
        activeToolCall = null
        missionRunning.set(false)
        CarSessionRuntime.beginUserSession(source)
        MagicarActiveSurfaceService.activate(appContext)
        audio.begin()
        live.start()
        return true
    }

    fun stop() {
        finishSession()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        finishSession()
        live.destroy()
        audio.release()
    }

    private fun executeUserTask(call: GeminiLiveToolCall) {
        if (!active.get()) {
            live.sendToolResult(call, "FAILED", "Assistant session is not active.")
            return
        }
        if (!missionRunning.compareAndSet(false, true)) {
            live.sendToolResult(
                call,
                "BUSY",
                "A user-requested task is already running. Wait for its verified result or cancel it first."
            )
            return
        }

        val objective = call.arguments["objective"]?.trim().orEmpty()
        if (objective.isBlank()) {
            missionRunning.set(false)
            live.sendToolResult(call, "FAILED", "Task objective is empty.")
            return
        }

        activeToolCall = call
        finishAfterTurn = false
        live.setMissionActive(true)
        CarSessionRuntime.markExecuting()
        UltronCommandRuntime.submit(objective) { outcome ->
            handleTaskOutcome(call, outcome, approvals = 0)
        }
    }

    private fun handleTaskOutcome(
        call: GeminiLiveToolCall,
        outcome: CommandOutcome,
        approvals: Int
    ) {
        when (outcome.state) {
            CommandOutcomeState.NEEDS_APPROVAL -> {
                if (isSensitive(outcome)) {
                    pendingSensitive = PendingSensitive(call, outcome)
                    missionRunning.set(false)
                    // Keep the Live microphone/session awake while Magicar asks for
                    // one explicit yes/no on the sensitive boundary.
                    live.setMissionActive(true)
                    live.sendToolResult(
                        call,
                        state = "NEEDS_CONFIRMATION",
                        message = "Explicit user confirmation is required before this sensitive or irreversible action.",
                        evidence = outcome.evidence
                    )
                    return
                }

                if (approvals >= MAX_AUTOMATIC_APPROVALS) {
                    terminal(
                        call,
                        outcome.copy(
                            state = CommandOutcomeState.FAILED,
                            message = "Automatic permission/retry budget was exhausted."
                        )
                    )
                    return
                }

                val missionId = outcome.missionId
                if (missionId.isNullOrBlank()) {
                    terminal(call, outcome.copy(state = CommandOutcomeState.FAILED))
                    return
                }

                // Ordinary app/UI/navigation actions are pre-authorized for the
                // bounded user-requested session, so no confirmation card appears.
                UltronCommandRuntime.approve(
                    missionId,
                    CommandApproval.ALWAYS_ALLOW
                ) { next ->
                    handleTaskOutcome(call, next, approvals + 1)
                }
            }

            CommandOutcomeState.VERIFIED_DONE -> terminal(call, outcome)

            CommandOutcomeState.FAILED,
            CommandOutcomeState.CANCELLED,
            CommandOutcomeState.UNSUPPORTED -> terminal(call, outcome)

            CommandOutcomeState.PAUSED,
            CommandOutcomeState.TAKEN_OVER -> {
                missionRunning.set(false)
                live.setMissionActive(true)
                live.sendToolResult(
                    call,
                    state = outcome.state.name,
                    message = outcome.message,
                    evidence = outcome.evidence
                )
            }
        }
    }

    private fun terminal(call: GeminiLiveToolCall, outcome: CommandOutcome) {
        missionRunning.set(false)
        pendingSensitive = null
        CarSessionRuntime.markVerifying()
        val sent = live.sendToolResult(
            call,
            state = outcome.state.name,
            message = outcome.message,
            evidence = outcome.evidence
        )
        activeToolCall = null
        live.setMissionActive(false)
        finishAfterTurn = true
        if (!sent) {
            main.postDelayed({ finishSession() }, TERMINAL_FALLBACK_MS)
        } else {
            main.postDelayed({
                if (finishAfterTurn && !missionRunning.get() && pendingSensitive == null) {
                    finishSession()
                }
            }, FINAL_TURN_TIMEOUT_MS)
        }
    }

    private fun confirmPending(call: GeminiLiveToolCall) {
        val pending = pendingSensitive
        if (pending == null) {
            live.sendToolResult(call, "FAILED", "There is no pending action to confirm.")
            return
        }
        val missionId = pending.outcome.missionId
        if (missionId.isNullOrBlank()) {
            pendingSensitive = null
            live.sendToolResult(call, "FAILED", "Pending action no longer exists.")
            return
        }

        pendingSensitive = null
        missionRunning.set(true)
        activeToolCall = pending.originalCall
        live.setMissionActive(true)
        UltronCommandRuntime.approve(missionId, CommandApproval.ALLOW_ONCE) { outcome ->
            live.sendToolResult(call, "CONFIRMED", "User confirmation accepted.")
            handleTaskOutcome(pending.originalCall, outcome, approvals = 0)
        }
    }

    private fun denyPending(call: GeminiLiveToolCall) {
        val pending = pendingSensitive
        if (pending == null) {
            live.sendToolResult(call, "FAILED", "There is no pending action to decline.")
            return
        }
        pendingSensitive = null
        missionRunning.set(false)
        val missionId = pending.outcome.missionId
        if (missionId.isNullOrBlank()) {
            live.sendToolResult(call, "CANCELLED", "Pending action declined.")
            finishAfterTurn = true
            live.setMissionActive(false)
            return
        }
        UltronCommandRuntime.cancel(missionId) { outcome ->
            live.sendToolResult(call, "CANCELLED", "Pending action declined.")
            terminal(pending.originalCall, outcome)
        }
    }

    private fun controlTask(
        call: GeminiLiveToolCall,
        action: (((CommandOutcome) -> Unit) -> Unit)
    ) {
        action { outcome ->
            if (outcome.state == CommandOutcomeState.CANCELLED ||
                outcome.state == CommandOutcomeState.FAILED ||
                outcome.state == CommandOutcomeState.VERIFIED_DONE
            ) {
                terminal(call, outcome)
            } else {
                live.sendToolResult(call, outcome.state.name, outcome.message, outcome.evidence)
            }
        }
    }

    private fun finishSession() {
        if (!active.getAndSet(false)) return
        finishAfterTurn = false
        missionRunning.set(false)
        pendingSensitive = null
        activeToolCall = null
        live.setMissionActive(false)
        live.stop()
        MagicarActiveSurfaceService.deactivate(appContext)
        audio.end()
        CarSessionRuntime.finish()
        main.post { onIdle() }
    }

    private fun isSensitive(outcome: CommandOutcome): Boolean {
        val capability = outcome.capability.orEmpty().lowercase(Locale.ROOT)
        val target = outcome.target.orEmpty().lowercase(Locale.ROOT)
        val message = outcome.message.lowercase(Locale.ROOT)
        val combined = "$capability $target $message"
        return SENSITIVE_MARKERS.any(combined::contains)
    }

    private data class PendingSensitive(
        val originalCall: GeminiLiveToolCall,
        val outcome: CommandOutcome
    )

    companion object {
        private const val MAX_AUTOMATIC_APPROVALS = 8
        private const val TERMINAL_FALLBACK_MS = 1_500L
        private const val FINAL_TURN_TIMEOUT_MS = 6_000L
        private val SENSITIVE_MARKERS = setOf(
            "purchase", "payment", "buy", "checkout",
            "factory reset", "erase all", "format", "delete account",
            "uninstall", "credential", "password", "passcode", "pin",
            "biometric", "security", "account recovery", "two-factor",
            "2fa", "bank", "card"
        )
    }
}
