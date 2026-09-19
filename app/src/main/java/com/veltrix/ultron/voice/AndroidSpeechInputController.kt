package com.veltrix.ultron.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale


enum class SpeechInputState {
    IDLE,
    LISTENING,
    PROCESSING,
    UNAVAILABLE,
    ERROR
}

interface SpeechInputListener {
    fun onStateChanged(state: SpeechInputState)
    fun onPartialTranscript(text: String)
    fun onFinalTranscript(text: String)
    fun onError(message: String)
}

/**
 * One-shot, visible-session speech input. This class never records audio to disk,
 * never starts a background microphone service, and destroys SpeechRecognizer with
 * the UI lifecycle.
 */
class AndroidSpeechInputController(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbackGate = SpeechCallbackGate()
    private var recognizer: SpeechRecognizer? = null
    private var cancelledByOwner = false
    private var usingOnDeviceRecognizer = false
    private var forceSystemRecognizer = false
    private var languageFallbackAttempted = false
    private var activeLocaleTag = Locale.getDefault().toLanguageTag()

    @Volatile
    var listener: SpeechInputListener = NOOP_LISTENER

    fun start(localeTag: String = Locale.getDefault().toLanguageTag()) = onMain {
        // A fresh user/system invocation supersedes any previous one-shot recognizer.
        // Invalidating first prevents late callbacks from the replaced recognizer.
        releaseRecognizer(invalidateCallbacks = true)
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            listener.onStateChanged(SpeechInputState.UNAVAILABLE)
            listener.onError("Speech recognition is not available on this device")
            return@onMain
        }

        cancelledByOwner = false
        languageFallbackAttempted = false
        activeLocaleTag = localeTag
        startRecognition(localeTag)
    }

    fun stop() = onMain {
        val active = recognizer ?: return@onMain
        cancelledByOwner = false
        runCatching { active.stopListening() }
        listener.onStateChanged(SpeechInputState.PROCESSING)
    }

    fun cancel() = onMain {
        cancelledByOwner = true
        releaseRecognizer(invalidateCallbacks = true)
        listener.onStateChanged(SpeechInputState.IDLE)
    }

    fun destroy() = onMain {
        cancelledByOwner = true
        releaseRecognizer(invalidateCallbacks = true)
        forceSystemRecognizer = false
        languageFallbackAttempted = false
        listener = NOOP_LISTENER
    }

    private fun startRecognition(localeTag: String) {
        val speechRecognizer = recognizer ?: createRecognizer()
        if (speechRecognizer == null) {
            listener.onStateChanged(SpeechInputState.UNAVAILABLE)
            listener.onError("Speech recognizer could not be created")
            return
        }
        recognizer = speechRecognizer

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
        }
        runCatching {
            speechRecognizer.startListening(intent)
            listener.onStateChanged(SpeechInputState.LISTENING)
        }.onFailure { error ->
            releaseRecognizer(invalidateCallbacks = true)
            listener.onStateChanged(SpeechInputState.ERROR)
            listener.onError("Speech input failed to start: ${error.javaClass.simpleName}")
        }
    }

    private fun createRecognizer(): SpeechRecognizer? = runCatching {
        val shouldTryOnDevice = !forceSystemRecognizer &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

        val created = if (shouldTryOnDevice) {
            runCatching {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext).also {
                    usingOnDeviceRecognizer = true
                }
            }.getOrElse {
                usingOnDeviceRecognizer = false
                SpeechRecognizer.createSpeechRecognizer(appContext)
            }
        } else {
            usingOnDeviceRecognizer = false
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }
        val callbackToken = callbackGate.begin()
        if (callbackToken <= 0L) {
            runCatching { created.destroy() }
            error("Speech callback generation is exhausted")
        }
        created.setRecognitionListener(recognitionListener(callbackToken))
        created
    }.getOrNull()

    private fun retryWithSystemRecognizerAfterLanguageFailure(): Boolean {
        if (!usingOnDeviceRecognizer || languageFallbackAttempted) return false

        languageFallbackAttempted = true
        forceSystemRecognizer = true
        usingOnDeviceRecognizer = false
        releaseRecognizer(invalidateCallbacks = true)

        mainHandler.post {
            if (cancelledByOwner) return@post
            startRecognition(activeLocaleTag)
        }
        return true
    }

    private fun recognitionListener(callbackToken: Long) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (callbackGate.isCurrent(callbackToken)) {
                listener.onStateChanged(SpeechInputState.LISTENING)
            }
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (callbackGate.isCurrent(callbackToken)) {
                listener.onStateChanged(SpeechInputState.PROCESSING)
            }
        }

        override fun onError(error: Int) {
            if (!callbackGate.isCurrent(callbackToken)) return

            if (
                SpeechLanguageFallbackPolicy.shouldFallbackToSystem(
                    errorCode = error,
                    usingOnDeviceRecognizer = usingOnDeviceRecognizer,
                    fallbackAlreadyAttempted = languageFallbackAttempted
                ) && retryWithSystemRecognizerAfterLanguageFailure()
            ) return

            if (!callbackGate.consume(callbackToken)) return
            releaseRecognizer(invalidateCallbacks = false)
            listener.onStateChanged(SpeechInputState.ERROR)
            listener.onError(errorMessage(error))
        }

        override fun onResults(results: Bundle?) {
            if (!callbackGate.consume(callbackToken)) return
            val finalText = bestTranscript(results)
            releaseRecognizer(invalidateCallbacks = false)
            listener.onStateChanged(SpeechInputState.IDLE)
            if (finalText == null) {
                listener.onError("No speech transcript was returned")
            } else {
                listener.onFinalTranscript(finalText)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!callbackGate.isCurrent(callbackToken)) return
            bestTranscript(partialResults)?.let(listener::onPartialTranscript)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun releaseRecognizer(invalidateCallbacks: Boolean) {
        if (invalidateCallbacks) callbackGate.invalidate()
        val active = recognizer
        recognizer = null
        usingOnDeviceRecognizer = false
        runCatching { active?.cancel() }
        runCatching { active?.destroy() }
    }

    private fun bestTranscript(bundle: Bundle?): String? = bundle
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.asSequence()
        ?.mapNotNull(VoiceTranscriptPolicy::sanitize)
        ?.firstOrNull()

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Speech audio input failed"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition network error"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech match was found"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Speech recognition service error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was detected"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            "Speech language $activeLocaleTag is not supported by this device"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "Speech language $activeLocaleTag is supported but its model is unavailable"
        else -> "Speech recognition error ($error)"
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private companion object {
        val NOOP_LISTENER = object : SpeechInputListener {
            override fun onStateChanged(state: SpeechInputState) = Unit
            override fun onPartialTranscript(text: String) = Unit
            override fun onFinalTranscript(text: String) = Unit
            override fun onError(message: String) = Unit
        }
    }
}
