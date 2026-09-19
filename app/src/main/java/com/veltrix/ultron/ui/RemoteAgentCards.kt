package com.veltrix.ultron.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.veltrix.ultron.agents.AgentTaskStatus
import com.veltrix.ultron.agents.TaskState
import com.veltrix.ultron.remote.UltronAgentRuntime
import com.veltrix.ultron.runtime.CommandApproval

/**
 * Production zero-input backend status.
 *
 * The phone never asks for a bridge URL, device bearer token, model id, provider
 * base URL, or provider API key. Secure enrollment starts automatically from the
 * app lifecycle. This surface only reports safe state and allows an explicit retry.
 */
@Composable
internal fun SecureBackendStatusCard() {
    val initialEnrollment = remember { UltronAgentRuntime.secureEnrollmentStatus() }
    var configured by remember { mutableStateOf(UltronAgentRuntime.isConfigured()) }
    var enrollmentState by remember { mutableStateOf(initialEnrollment.state.name) }
    var enrollmentFailureCode by remember { mutableStateOf(initialEnrollment.failureCode) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pairingCode by remember { mutableStateOf("") }

    fun refresh() {
        configured = UltronAgentRuntime.isConfigured()
        val enrollment = UltronAgentRuntime.secureEnrollmentStatus()
        enrollmentState = enrollment.state.name
        enrollmentFailureCode = enrollment.failureCode
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Secure backend", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    configured && UltronAgentRuntime.isBackgroundLoopRunning() -> "Ready · secure backend link active"
                    configured -> "Securely enrolled · reconnecting"
                    enrollmentState == "ENROLLING" -> "Secure setup in progress"
                    enrollmentState == "FAILED" -> "Secure setup needs retry${enrollmentFailureCode?.let { " · $it" }.orEmpty()}"
                    else -> "Secure setup starts automatically"
                }
            )
            Text(
                "Automatic secure enrollment is tried first. On aftermarket head units, a one-time Magicar pairing code can be entered if Play Integrity is unavailable.",
                style = MaterialTheme.typography.bodySmall
            )
            if (!configured) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        message = "Verifying this app and device…"
                        UltronAgentRuntime.ensureSecureConfigurationAsync { result ->
                            busy = false
                            refresh()
                            message = if (result.isSuccess) {
                                "Secure backend ready"
                            } else {
                                "Secure setup failed safely · ${enrollmentFailureCode ?: "UNKNOWN"}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Verifying…" else if (enrollmentState == "ENROLLING") "Check secure setup" else "Retry secure setup")
                }

                if (enrollmentState == "FAILED") {
                    TextField(
                        value = pairingCode,
                        onValueChange = { pairingCode = it.take(128) },
                        enabled = !busy,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("Magicar pairing code") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        enabled = !busy && pairingCode.trim().length >= 20,
                        onClick = {
                            busy = true
                            message = "Pairing this display securely…"
                            UltronAgentRuntime.pairWithCodeAsync(pairingCode) { result ->
                                busy = false
                                if (result.isSuccess) pairingCode = ""
                                refresh()
                                message = if (result.isSuccess) {
                                    "Veltrix Magicar backend ready"
                                } else {
                                    "Pairing failed safely"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (busy) "Pairing…" else "Pair this display")
                    }
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun RemoteMissionsPanel() {
    var tasks by remember { mutableStateOf(UltronAgentRuntime.activeTasks()) }
    var busyTask by remember { mutableStateOf<String?>(null) }
    var status by remember {
        mutableStateOf(
            if (UltronAgentRuntime.isConfigured()) "Remote bridge ready"
            else "Secure enrollment is still completing"
        )
    }

    fun reload() {
        tasks = UltronAgentRuntime.activeTasks()
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Remote missions", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                Text(status)
                Button(
                    enabled = busyTask == null && UltronAgentRuntime.isConfigured(),
                    onClick = {
                        busyTask = "__sync__"
                        UltronAgentRuntime.syncAsync { result ->
                            busyTask = null
                            status = result.fold(
                                onSuccess = { sync ->
                                    if (sync.claimedTaskId != null) "New remote mission received" else "Remote missions synced"
                                },
                                onFailure = { "Remote sync failed safely" }
                            )
                            reload()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Sync remote missions") }
            }
        }

        if (tasks.isEmpty()) {
            Text("No active remote missions")
        } else {
            tasks.forEach { task ->
                RemoteTaskCard(
                    task = task,
                    busy = busyTask == task.taskId,
                    onApprove = { approval ->
                        busyTask = task.taskId
                        UltronAgentRuntime.approveAsync(task.taskId, approval) { result ->
                            busyTask = null
                            status = result.fold(
                                onSuccess = { receipt -> "${task.principal.displayName}: ${receipt.narration}" },
                                onFailure = { "Approval failed safely" }
                            )
                            reload()
                        }
                    },
                    onPause = {
                        busyTask = task.taskId
                        UltronAgentRuntime.pauseAsync(task.taskId) { result ->
                            busyTask = null
                            status = if (result.isSuccess) "Remote mission paused" else "Pause failed safely"
                            reload()
                        }
                    },
                    onTakeOver = {
                        busyTask = task.taskId
                        UltronAgentRuntime.takeOverAsync(task.taskId) { result ->
                            busyTask = null
                            status = if (result.isSuccess) {
                                "Owner takeover active · agent stays paused until Resume"
                            } else {
                                "Take over failed safely"
                            }
                            reload()
                        }
                    },
                    onResume = {
                        busyTask = task.taskId
                        UltronAgentRuntime.resumeAsync(task.taskId) { result ->
                            busyTask = null
                            status = result.fold(
                                onSuccess = { receipt -> "Remote mission resumed · ${receipt.narration}" },
                                onFailure = { "Resume failed safely" }
                            )
                            reload()
                        }
                    },
                    onCancel = {
                        busyTask = task.taskId
                        UltronAgentRuntime.cancelAsync(task.taskId) { result ->
                            busyTask = null
                            status = if (result.isSuccess) "Remote mission cancelled" else "Cancel failed safely"
                            reload()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun RemoteTaskCard(
    task: AgentTaskStatus,
    busy: Boolean,
    onApprove: (CommandApproval) -> Unit,
    onPause: () -> Unit,
    onTakeOver: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${task.principal.displayName} · ${task.principal.kind.name}",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium
            )
            Text(task.objective)
            Text("${task.state.name} · ${task.narration}")
            if (task.evidence.isNotEmpty()) Text("Evidence · ${task.evidence.joinToString()}")

            if (task.state == TaskState.WAITING_FOR_USER) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = { onApprove(CommandApproval.ALLOW_ONCE) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Allow once") }
                    Button(
                        enabled = !busy,
                        onClick = { onApprove(CommandApproval.ALWAYS_ALLOW) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Remember for Max Approved") }
                }
                Text(
                    "ASK_EACH_ACTION still asks every time; remembered delegation applies only when Max Approved is active.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (task.state == TaskState.PAUSED) {
                Text("Owner control has priority. This mission cannot continue until you resume it.")
                Button(enabled = !busy, onClick = onResume, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Working…" else "Resume mission")
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = onPause, modifier = Modifier.weight(1f)) {
                        Text("Pause")
                    }
                    Button(enabled = !busy, onClick = onTakeOver, modifier = Modifier.weight(1f)) {
                        Text("Take over")
                    }
                }
            }

            Button(enabled = !busy, onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Working…" else "Cancel mission")
            }
        }
    }
}
