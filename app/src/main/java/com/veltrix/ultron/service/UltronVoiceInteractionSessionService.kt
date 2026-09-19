package com.veltrix.ultron.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.veltrix.ultron.MainActivity
import com.veltrix.ultron.voice.AssistantInvocationReplayPolicy

/**
 * Hosts the active Android assistant session.
 * The session itself stays visually minimal and opens the Veltrix command surface.
 */
class UltronVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        UltronVoiceInteractionSession(this)
}

private class UltronVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private val appContext = context.applicationContext
    private val sequenceLock = Any()
    private val sequenceStore = appContext.getSharedPreferences(
        AssistantInvocationReplayPolicy.STORE_NAME,
        Context.MODE_PRIVATE
    )

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        // Veltrix uses its Compose activity as the command surface instead of
        // rendering a second VoiceInteractionSession window.
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val invocationSequence = issueInvocationSequence() ?: return
        val intent = Intent(appContext, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_INVOCATION_SOURCE, MainActivity.INVOCATION_ASSISTANT)
            putExtra(MainActivity.EXTRA_INVOCATION_SEQUENCE, invocationSequence)
        }
        startAssistantActivity(intent)
    }

    private fun issueInvocationSequence(): Long? = synchronized(sequenceLock) {
        val previous = sequenceStore.getLong(AssistantInvocationReplayPolicy.KEY_LAST_ISSUED_SEQUENCE, 0L)
        val next = AssistantInvocationReplayPolicy.nextIssuedSequence(previous) ?: return@synchronized null
        val persisted = sequenceStore.edit()
            .putLong(AssistantInvocationReplayPolicy.KEY_LAST_ISSUED_SEQUENCE, next)
            .commit()
        if (persisted) next else null
    }
}
