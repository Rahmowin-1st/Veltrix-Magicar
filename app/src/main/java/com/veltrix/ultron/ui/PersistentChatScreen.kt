package com.veltrix.ultron.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.veltrix.ultron.chat.ChatMessage
import com.veltrix.ultron.chat.MessageRole
import com.veltrix.ultron.runtime.CommandApproval
import com.veltrix.ultron.runtime.CommandOutcome
import com.veltrix.ultron.runtime.CommandOutcomeState
import com.veltrix.ultron.runtime.UltronCommandRuntime
import com.veltrix.ultron.voice.GeminiLiveToolCall
import com.veltrix.ultron.voice.GeminiLiveVoiceController
import com.veltrix.ultron.voice.GeminiLiveVoiceListener
import com.veltrix.ultron.voice.GeminiLiveVoiceState

private val PersistentChatCyan = Color(0xFF19C8FF)
private val PersistentChatPanel = Color(0xCC121A22)

@Composable
internal fun PersistentChatScreen(autoStartVoiceToken: Int = 0) {
    val context = LocalContext.current
    val liveController = remember(context) { GeminiLiveVoiceController(context.applicationContext) }

    var input by remember { mutableStateOf("") }
    var pendingApproval by remember { mutableStateOf<CommandOutcome?>(null) }
    var pendingLiveToolCall by remember { mutableStateOf<GeminiLiveToolCall?>(null) }
    var busy by remember { mutableStateOf(false) }
    var memoryCount by remember { mutableIntStateOf(UltronCommandRuntime.conversationMemoryCount()) }
    var canUndo by remember { mutableStateOf(UltronCommandRuntime.canUndo()) }
    var liveState by remember { mutableStateOf(GeminiLiveVoiceState.IDLE) }
    var partialTranscript by remember { mutableStateOf("") }
    var voiceError by remember { mutableStateOf<String?>(null) }
    var pendingVoiceStart by remember { mutableStateOf(false) }
    var handledVoiceToken by remember { mutableIntStateOf(0) }

    val messages = remember {
        mutableStateListOf<String>().apply {
            val restored = UltronCommandRuntime.conversationHistory().map(::formatPersistedMessage)
            if (restored.isEmpty()) add("Veltrix Magicar: Ready.") else addAll(restored)
        }
    }

    fun refreshRuntimeState() {
        memoryCount = UltronCommandRuntime.conversationMemoryCount()
        canUndo = UltronCommandRuntime.canUndo()
    }

    fun appendOutcome(outcome: CommandOutcome) {
        val evidence = if (outcome.evidence.isEmpty()) "" else " · ${outcome.evidence.joinToString()}"
        val prefix = when (outcome.state) {
            CommandOutcomeState.NEEDS_APPROVAL -> "Permission required"
            CommandOutcomeState.PAUSED -> "Paused"
            CommandOutcomeState.TAKEN_OVER -> "Owner control"
            CommandOutcomeState.VERIFIED_DONE -> "Verified done"
            CommandOutcomeState.CANCELLED -> "Cancelled"
            CommandOutcomeState.UNSUPPORTED -> "Planner required"
            CommandOutcomeState.FAILED -> "Failed"
        }
        messages += "Veltrix Magicar: $prefix · ${outcome.message}$evidence"
    }

    val applyTextOutcome: (CommandOutcome) -> Unit = { outcome ->
        busy = false
        pendingApproval = outcome.takeIf { it.state == CommandOutcomeState.NEEDS_APPROVAL }
        appendOutcome(outcome)
        refreshRuntimeState()
    }

    fun finishLiveTool(call: GeminiLiveToolCall, outcome: CommandOutcome) {
        busy = false
        refreshRuntimeState()
        if (outcome.state == CommandOutcomeState.NEEDS_APPROVAL) {
            pendingApproval = outcome
            pendingLiveToolCall = call
            appendOutcome(outcome)
            return
        }

        if (pendingLiveToolCall?.id == call.id) pendingLiveToolCall = null
        pendingApproval = null
        val sent = liveController.sendToolResult(
            call = call,
            state = outcome.state.name,
            message = outcome.message,
            evidence = outcome.evidence
        )
        if (!sent) {
            appendOutcome(outcome)
        }
    }

    fun submitTextCommand(clean: String) {
        if (clean.isBlank() || busy || pendingApproval != null) return
        messages += "You: $clean"
        input = ""
        busy = true
        UltronCommandRuntime.submit(clean, applyTextOutcome)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val shouldStart = pendingVoiceStart
        pendingVoiceStart = false
        if (granted && shouldStart) {
            voiceError = null
            liveController.start()
        } else if (!granted) {
            liveState = GeminiLiveVoiceState.ERROR
            voiceError = "Microphone permission was not granted"
        }
    }

    fun requestVoiceStart() {
        partialTranscript = ""
        voiceError = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            liveController.start()
        } else {
            pendingVoiceStart = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    SideEffect {
        liveController.listener = object : GeminiLiveVoiceListener {
            override fun onStateChanged(state: GeminiLiveVoiceState) {
                liveState = state
                if (state == GeminiLiveVoiceState.IDLE) partialTranscript = ""
            }

            override fun onInputTranscript(text: String, final: Boolean) {
                partialTranscript = if (final) "" else text
                if (final && text.isNotBlank()) {
                    messages += "You (voice): $text"
                }
            }

            override fun onOutputTranscript(text: String) {
                if (text.isNotBlank()) messages += "Veltrix Magicar: $text"
            }

            override fun onToolCall(call: GeminiLiveToolCall) {
                when (call.name) {
                    "execute_user_task" -> {
                        val objective = call.arguments["objective"]?.trim().orEmpty()
                        if (objective.isBlank()) {
                            liveController.sendToolResult(
                                call,
                                state = "FAILED",
                                message = "The task objective was empty."
                            )
                            return
                        }
                        pendingLiveToolCall = call
                        busy = true
                        UltronCommandRuntime.submit(objective) { outcome ->
                            finishLiveTool(call, outcome)
                        }
                    }

                    "cancel_active_task" -> {
                        pendingLiveToolCall = call
                        busy = true
                        UltronCommandRuntime.cancelActive { outcome ->
                            finishLiveTool(call, outcome)
                        }
                    }

                    "pause_active_task" -> {
                        pendingLiveToolCall = call
                        busy = true
                        UltronCommandRuntime.pauseActive { outcome ->
                            finishLiveTool(call, outcome)
                        }
                    }

                    "resume_active_task" -> {
                        pendingLiveToolCall = call
                        busy = true
                        UltronCommandRuntime.resumeActive { outcome ->
                            finishLiveTool(call, outcome)
                        }
                    }

                    else -> liveController.sendToolResult(
                        call,
                        state = "FAILED",
                        message = "Unsupported Veltrix Magicar tool."
                    )
                }
            }

            override fun onToolCancellation(ids: List<String>) {
                val pending = pendingLiveToolCall
                if (pending != null && pending.id in ids) {
                    UltronCommandRuntime.pauseActive {
                        busy = false
                        pendingLiveToolCall = null
                        refreshRuntimeState()
                    }
                }
            }

            override fun onError(message: String) {
                busy = false
                voiceError = message
            }
        }
    }

    DisposableEffect(liveController) {
        onDispose { liveController.destroy() }
    }

    LaunchedEffect(autoStartVoiceToken) {
        if (autoStartVoiceToken > 0 && autoStartVoiceToken != handledVoiceToken) {
            handledVoiceToken = autoStartVoiceToken
            requestVoiceStart()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Veltrix Magicar", style = MaterialTheme.typography.headlineSmall, color = PersistentChatCyan)
                Text(
                    "Gemini Live · encrypted local memory · $memoryCount messages",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy && pendingApproval == null && canUndo,
                    onClick = {
                        messages += "You: Undo"
                        busy = true
                        UltronCommandRuntime.submit("undo", applyTextOutcome)
                    }
                ) { Text("Undo") }
                Button(
                    enabled = !busy && pendingApproval == null && memoryCount > 0,
                    onClick = {
                        UltronCommandRuntime.clearConversationMemory()
                        messages.clear()
                        messages += "Veltrix Magicar: Ready. Conversation memory cleared."
                        memoryCount = 0
                    }
                ) { Text("Clear memory") }
            }
        }

        Spacer(Modifier.height(12.dp))
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { message -> Text(message) }
        }

        pendingApproval?.let { pending ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = PersistentChatPanel)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Permission", color = PersistentChatCyan, style = MaterialTheme.typography.titleMedium)
                    Text(pending.message)
                    Text("${pending.capability ?: "action"} · ${pending.target ?: "current screen"}")
                    Button(
                        enabled = !busy,
                        onClick = {
                            val missionId = pending.missionId ?: return@Button
                            val liveCall = pendingLiveToolCall
                            busy = true
                            UltronCommandRuntime.approve(missionId, CommandApproval.ALLOW_ONCE) { outcome ->
                                if (liveCall != null) finishLiveTool(liveCall, outcome)
                                else applyTextOutcome(outcome)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Allow once") }
                    Button(
                        enabled = !busy,
                        onClick = {
                            val missionId = pending.missionId ?: return@Button
                            val liveCall = pendingLiveToolCall
                            busy = true
                            UltronCommandRuntime.approve(missionId, CommandApproval.ALWAYS_ALLOW) { outcome ->
                                if (liveCall != null) finishLiveTool(liveCall, outcome)
                                else applyTextOutcome(outcome)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Always allow") }
                    Button(
                        enabled = !busy,
                        onClick = {
                            val missionId = pending.missionId ?: return@Button
                            val liveCall = pendingLiveToolCall
                            busy = true
                            UltronCommandRuntime.cancel(missionId) { outcome ->
                                if (liveCall != null) finishLiveTool(liveCall, outcome)
                                else applyTextOutcome(outcome)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Cancel") }
                }
            }
        }

        when {
            partialTranscript.isNotBlank() -> Text(
                "Hearing: $partialTranscript",
                style = MaterialTheme.typography.bodySmall,
                color = PersistentChatCyan,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            voiceError != null -> Text(
                voiceError.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            liveState == GeminiLiveVoiceState.CONNECTING -> Text(
                "Connecting Gemini Live…",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            liveState == GeminiLiveVoiceState.TOOL_RUNNING -> Text(
                "Working on your request…",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = input,
                onValueChange = { input = it },
                enabled = !busy && pendingApproval == null,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask Veltrix Magicar…") }
            )
            Button(
                enabled = liveState != GeminiLiveVoiceState.CONNECTING,
                onClick = {
                    if (liveState == GeminiLiveVoiceState.IDLE || liveState == GeminiLiveVoiceState.ERROR) {
                        requestVoiceStart()
                    } else {
                        liveController.stop()
                    }
                }
            ) {
                Text(
                    when (liveState) {
                        GeminiLiveVoiceState.IDLE,
                        GeminiLiveVoiceState.ERROR -> "Live"
                        GeminiLiveVoiceState.CONNECTING -> "…"
                        GeminiLiveVoiceState.LISTENING,
                        GeminiLiveVoiceState.TOOL_RUNNING -> "Stop"
                    }
                )
            }
            Button(
                enabled = !busy && pendingApproval == null,
                onClick = {
                    val clean = input.trim()
                    if (clean.isNotEmpty()) submitTextCommand(clean)
                }
            ) { Text(if (busy) "…" else "Send") }
        }
    }
}

private fun formatPersistedMessage(message: ChatMessage): String = when (message.role) {
    MessageRole.USER -> "You: ${message.text}"
    MessageRole.VELTRIX -> "Veltrix Magicar: ${message.text}"
    MessageRole.AGENT -> "Agent: ${message.text}"
    MessageRole.SYSTEM -> "System: ${message.text}"
}
