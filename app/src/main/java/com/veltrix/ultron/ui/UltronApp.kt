package com.veltrix.ultron.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.veltrix.ultron.platform.AndroidCapabilityController
import com.veltrix.ultron.platform.ScreenCaptureState
import com.veltrix.ultron.runtime.CommandApproval
import com.veltrix.ultron.runtime.CommandOutcome
import com.veltrix.ultron.runtime.CommandOutcomeState
import com.veltrix.ultron.runtime.UltronCommandRuntime

private val UltronCyan = Color(0xFF19C8FF)
private val UltronDeep = Color(0xFF0B1016)
private val UltronPanel = Color(0xCC121A22)

@Composable
fun UltronApp(initialPage: Int = 0, voiceInvocationToken: Int = 0) {
    val scheme = darkColorScheme(
        primary = UltronCyan,
        background = UltronDeep,
        surface = Color(0xFF101820)
    )
    var selected by remember(initialPage) { mutableIntStateOf(initialPage.coerceIn(0, 3)) }
    val pages = listOf("Home", "Chat", "Missions", "Control")

    LaunchedEffect(voiceInvocationToken) {
        if (voiceInvocationToken > 0) selected = 1
    }

    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(containerColor = Color(0xF010171E)) {
                    pages.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = selected == index,
                            onClick = { selected = index },
                            icon = { Text(if (selected == index) "◆" else "◇") },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF07131B), UltronDeep, Color(0xFF05080C))
                        )
                    )
                    .padding(padding)
            ) {
                when (selected) {
                    0 -> HomeScreen()
                    1 -> PersistentChatScreen(autoStartVoiceToken = voiceInvocationToken)
                    2 -> MissionsScreen()
                    else -> ControlScreen()
                }
            }
        }
    }
}

@Composable
private fun HomeScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("VELTRIX MAGICAR", style = MaterialTheme.typography.headlineMedium, color = UltronCyan)
        Text("Idle · ready when you call it")
        UltronCard("Veltrix Magicar Core", "Understand → Tell → Permission → Execute → Verify")
        UltronCard("Live", "Voice + user-consented screen copilot foundation")
        UltronCard("Universal Fabric", "Phone · Desktop · Cloud · MCP")
    }
}

@Composable
private fun ChatScreen() {
    var input by remember { mutableStateOf("") }
    var pendingApproval by remember { mutableStateOf<CommandOutcome?>(null) }
    var busy by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf("Veltrix: Ready.") }

    val applyOutcome: (CommandOutcome) -> Unit = { outcome ->
        busy = false
        pendingApproval = outcome.takeIf { it.state == CommandOutcomeState.NEEDS_APPROVAL }
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
        messages += "Veltrix: $prefix · ${outcome.message}$evidence"
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("AI Chat", style = MaterialTheme.typography.headlineSmall, color = UltronCyan)
        Spacer(Modifier.height(12.dp))
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { message -> Text(message) }
        }

        pendingApproval?.let { pending ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = UltronPanel)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Permission", color = UltronCyan, style = MaterialTheme.typography.titleMedium)
                    Text(pending.message)
                    Text("${pending.capability ?: "action"} · ${pending.target ?: "current screen"}")
                    Button(
                        enabled = !busy,
                        onClick = {
                            val missionId = pending.missionId ?: return@Button
                            busy = true
                            UltronCommandRuntime.approve(missionId, CommandApproval.ALLOW_ONCE, applyOutcome)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Allow once") }
                    Button(
                        enabled = !busy,
                        onClick = {
                            val missionId = pending.missionId ?: return@Button
                            busy = true
                            UltronCommandRuntime.approve(missionId, CommandApproval.ALWAYS_ALLOW, applyOutcome)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Always allow") }
                    Button(
                        enabled = !busy,
                        onClick = {
                            val missionId = pending.missionId ?: return@Button
                            busy = true
                            UltronCommandRuntime.cancel(missionId, applyOutcome)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Cancel") }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = input,
                onValueChange = { input = it },
                enabled = !busy && pendingApproval == null,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Write to Veltrix…") }
            )
            Button(
                enabled = !busy && pendingApproval == null,
                onClick = {
                    val clean = input.trim()
                    if (clean.isNotEmpty()) {
                        messages += "You: $clean"
                        input = ""
                        busy = true
                        UltronCommandRuntime.submit(clean, applyOutcome)
                    }
                }
            ) { Text(if (busy) "…" else "Send") }
        }
    }
}

@Composable
private fun MissionsScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Missions", style = MaterialTheme.typography.headlineSmall, color = UltronCyan)
        }
        item { RemoteMissionsPanel() }
    }
}

@Composable
private fun ControlScreen() {
    val context = LocalContext.current
    val controller = remember(context) { AndroidCapabilityController(context.applicationContext) }
    var status by remember { mutableStateOf(controller.status()) }
    var guidedSetupActive by remember { mutableStateOf(false) }
    var awaitingSetupResult by remember { mutableStateOf(false) }
    var setupAdvanceToken by remember { mutableIntStateOf(0) }
    var lastSpecialStep by remember { mutableStateOf<String?>(null) }
    var lastRuntimePermissions by remember { mutableStateOf<Set<String>>(emptySet()) }
    var setupMessage by remember { mutableStateOf<String?>(null) }

    val manualLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        status = controller.status()
    }
    val guidedSpecialLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val refreshed = controller.status()
        val next = controller.nextMaxApprovedSetupAction(refreshed)
        val deniedOrUnchanged = guidedSetupActive && lastSpecialStep != null && next?.label == lastSpecialStep
        status = refreshed
        awaitingSetupResult = false
        lastSpecialStep = null
        if (deniedOrUnchanged) {
            guidedSetupActive = false
            setupMessage = "Setup paused · Android did not grant that access"
        } else {
            setupAdvanceToken += 1
        }
    }
    val runtimePermissionsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val refreshed = controller.status()
        val stillMissing = controller.missingRuntimePermissions(refreshed).toSet()
        val denied = lastRuntimePermissions.any { it in stillMissing }
        status = refreshed
        awaitingSetupResult = false
        lastRuntimePermissions = emptySet()
        if (guidedSetupActive && denied) {
            guidedSetupActive = false
            setupMessage = "Setup paused · a runtime permission was not granted"
        } else {
            setupAdvanceToken += 1
        }
    }

    LaunchedEffect(guidedSetupActive, setupAdvanceToken) {
        if (!guidedSetupActive || awaitingSetupResult) return@LaunchedEffect
        val refreshed = controller.status()
        status = refreshed
        if (refreshed.maxApprovedReady) {
            guidedSetupActive = false
            setupMessage = "Veltrix setup ready"
            return@LaunchedEffect
        }

        val runtimePermissions = controller.missingRuntimePermissions(refreshed)
        if (runtimePermissions.isNotEmpty()) {
            lastRuntimePermissions = runtimePermissions.toSet()
            awaitingSetupResult = true
            runtimePermissionsLauncher.launch(runtimePermissions)
            return@LaunchedEffect
        }

        val next = controller.nextMaxApprovedSetupAction(refreshed)
        if (next != null) {
            lastSpecialStep = next.label
            awaitingSetupResult = true
            setupMessage = "Android confirmation · ${next.label}"
            guidedSpecialLauncher.launch(next.intent)
            return@LaunchedEffect
        }

        guidedSetupActive = false
        setupMessage = "Setup paused safely · review the capability status below"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Control", style = MaterialTheme.typography.headlineSmall, color = UltronCyan)
        }
        item { SecureBackendStatusCard() }
        item {
            UltronCard(
                "Secure capability setup",
                if (status.maxApprovedReady) {
                    "Ready · only Android capabilities you approved are active"
                } else {
                    "Start once · then approve each Android-owned confirmation as it appears"
                }
            )
        }
        if (!status.maxApprovedReady) {
            item {
                Button(
                    enabled = !guidedSetupActive,
                    onClick = {
                        guidedSetupActive = true
                        awaitingSetupResult = false
                        lastSpecialStep = null
                        lastRuntimePermissions = emptySet()
                        setupMessage = "Secure setup started"
                        setupAdvanceToken += 1
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (guidedSetupActive) "Setup in progress…" else "Start secure setup") }
            }
        }
        setupMessage?.let { message ->
            item { Text(message, style = MaterialTheme.typography.bodySmall) }
        }
        item {
            Text(
                "Android keeps every special-access decision under your control. Veltrix never silently enables Accessibility, overlay, assistant, microphone, notification, or screen-capture access.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        item {
            UltronCard("Action permissions", "Once · Always allow · Ask · Deny")
        }
        item {
            CapabilityCard(
                title = "Default assistant",
                enabled = status.assistantRoleHeld,
                available = status.assistantRoleAvailable,
                actionLabel = "Request role",
                onAction = {
                    controller.assistantRoleRequestIntent()?.let { intent -> manualLauncher.launch(intent) }
                }
            )
        }
        item {
            UltronCard(
                "Assistant runtime",
                when {
                    status.assistantServiceActive -> "Active system VoiceInteractionService"
                    status.assistantRoleHeld -> "Role selected · waiting for system activation"
                    else -> "Not selected"
                }
            )
        }
        item {
            CapabilityCard(
                title = "Overlay",
                enabled = status.overlayAllowed,
                actionLabel = "Open settings",
                onAction = { manualLauncher.launch(controller.overlaySettingsIntent()) }
            )
        }
        if (status.overlayAllowed) {
            item {
                Button(
                    onClick = { controller.showOverlay() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Show Veltrix overlay") }
            }
            item {
                Button(
                    onClick = { controller.hideOverlay() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Hide Veltrix overlay") }
            }
        }
        item {
            CapabilityCard(
                title = "Android executor",
                enabled = status.accessibilityEnabled,
                actionLabel = "Accessibility",
                onAction = { manualLauncher.launch(controller.accessibilitySettingsIntent()) }
            )
        }
        item {
            UltronCard(
                "Executor runtime",
                if (status.executorConnected) "Connected to live AccessibilityService" else "Disconnected"
            )
        }
        item {
            CapabilityCard(
                title = "Notification context",
                enabled = status.notificationAccessEnabled,
                actionLabel = "Notification access",
                onAction = { manualLauncher.launch(controller.notificationAccessSettingsIntent()) }
            )
        }
        item {
            UltronCard(
                "Screen vision",
                when {
                    status.freshScreenFrameReady -> "Fresh one-shot frame ready in RAM"
                    status.screenCaptureState == ScreenCaptureState.CAPTURING -> "Capture session active"
                    status.screenCaptureState == ScreenCaptureState.DENIED -> "Last one-shot consent was denied"
                    status.screenCaptureState == ScreenCaptureState.UNAVAILABLE -> "Last frame was unavailable or protected"
                    status.screenCaptureState == ScreenCaptureState.ERROR -> "Last capture failed safely"
                    else -> "Optional fallback · Android asks again for every capture session"
                }
            )
        }
        item {
            Button(
                onClick = { manualLauncher.launch(controller.screenCaptureConsentIntent()) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Request one-time screen vision") }
        }
        item {
            Text(
                "Screen vision is intentionally not part of bulk setup. Modern Android requires fresh user consent for each MediaProjection capture session.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        item {
            Button(onClick = { status = controller.status() }, modifier = Modifier.fillMaxWidth()) {
                Text("Refresh capability status")
            }
        }
        item {
            UltronCard("Kill switch", "Global stop foundation · no privileged capability self-enables")
        }
    }
}

@Composable
private fun CapabilityCard(
    title: String,
    enabled: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    available: Boolean = true
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = UltronPanel)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = UltronCyan, style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    !available -> "Unavailable on this device"
                    enabled -> "Enabled"
                    else -> "Needs user setup"
                }
            )
            if (available && !enabled) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun UltronCard(title: String, detail: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = UltronPanel)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(title, color = UltronCyan, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(detail)
        }
    }
}
