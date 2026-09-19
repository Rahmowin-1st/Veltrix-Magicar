package com.veltrix.ultron

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import com.veltrix.ultron.car.CarRuntimeService
import com.veltrix.ultron.car.CarSessionRuntime
import com.veltrix.ultron.car.CarWakeSource
import com.veltrix.ultron.remote.UltronAgentRuntime
import com.veltrix.ultron.runtime.UltronCommandRuntime
import com.veltrix.ultron.ui.UltronApp
import com.veltrix.ultron.voice.AssistantInvocationReplayPolicy

class MainActivity : ComponentActivity() {
    private val requestedPage = mutableIntStateOf(PAGE_HOME)
    private val voiceInvocationToken = mutableIntStateOf(0)
    private val invocationReplayStore by lazy {
        getSharedPreferences(AssistantInvocationReplayPolicy.STORE_NAME, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UltronCommandRuntime.initialize(applicationContext)
        UltronAgentRuntime.initialize(applicationContext)
        requestedPage.intValue = resolvePage(intent)
        recordVoiceInvocation(intent)
        // Successful enrollment already starts the process-scoped background bridge
        // loop. Avoid a second eager sync racing that first heartbeat/claim cycle.
        UltronAgentRuntime.ensureSecureConfigurationAsync()
        UltronAgentRuntime.refreshNotifications()
        setContent {
            UltronApp(
                initialPage = requestedPage.intValue,
                voiceInvocationToken = voiceInvocationToken.intValue
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Permission/settings changes happen outside the app. Re-entering here
        // re-arms the local wake detector without opening any assistant UI.
        CarRuntimeService.start(applicationContext)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedPage.intValue = resolvePage(intent)
        recordVoiceInvocation(intent)
        UltronAgentRuntime.refreshNotifications()
    }

    private fun resolvePage(intent: Intent?): Int {
        val explicit = intent?.getIntExtra(EXTRA_INITIAL_PAGE, PAGE_UNSPECIFIED)
            ?.takeIf { it in PAGE_HOME..PAGE_CONTROL }
        if (explicit != null) return explicit
        return if (isAssistantInvocation(intent)) PAGE_CHAT else PAGE_HOME
    }

    private fun recordVoiceInvocation(intent: Intent?) {
        if (!isAssistantInvocation(intent)) return

        val invocationSequence = intent?.getLongExtra(EXTRA_INVOCATION_SEQUENCE, 0L) ?: 0L
        val lastConsumed = invocationReplayStore.getLong(
            AssistantInvocationReplayPolicy.KEY_LAST_CONSUMED_SEQUENCE,
            0L
        )
        if (!AssistantInvocationReplayPolicy.shouldConsume(invocationSequence, lastConsumed)) return

        // Fail closed: the transient microphone session can start only after this
        // invocation generation is durably consumed. No objective/audio is stored.
        val persisted = invocationReplayStore.edit()
            .putLong(AssistantInvocationReplayPolicy.KEY_LAST_CONSUMED_SEQUENCE, invocationSequence)
            .commit()
        if (!persisted) return

        CarSessionRuntime.beginUserSession(CarWakeSource.ASSISTANT_INVOCATION)
        voiceInvocationToken.intValue += 1
    }

    private fun isAssistantInvocation(intent: Intent?): Boolean =
        intent?.getStringExtra(EXTRA_INVOCATION_SOURCE) == INVOCATION_ASSISTANT

    companion object {
        const val EXTRA_INVOCATION_SOURCE = "com.veltrix.ultron.extra.INVOCATION_SOURCE"
        const val EXTRA_INVOCATION_SEQUENCE = "com.veltrix.ultron.extra.INVOCATION_SEQUENCE"
        const val EXTRA_INITIAL_PAGE = "com.veltrix.ultron.extra.INITIAL_PAGE"
        const val EXTRA_REMOTE_TASK_ID = "com.veltrix.ultron.extra.REMOTE_TASK_ID"
        const val INVOCATION_ASSISTANT = "assistant"

        const val PAGE_HOME = 0
        const val PAGE_CHAT = 1
        const val PAGE_MISSIONS = 2
        const val PAGE_CONTROL = 3
        private const val PAGE_UNSPECIFIED = -1
    }
}
