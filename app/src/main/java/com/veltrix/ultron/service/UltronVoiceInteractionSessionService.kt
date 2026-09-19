package com.veltrix.ultron.service

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.veltrix.ultron.car.CarRuntimeService
import com.veltrix.ultron.car.CarWakeSource

/**
 * Android's assistant role is only an invocation hook.
 * The real session stays in CarRuntimeService and renders the minimal transparent
 * Magicar active surface instead of opening a chat/activity over the head unit.
 */
class UltronVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        UltronVoiceInteractionSession(this)
}

private class UltronVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private val appContext = context.applicationContext

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        CarRuntimeService.wake(appContext, CarWakeSource.ASSISTANT_INVOCATION)
        hide()
    }
}
