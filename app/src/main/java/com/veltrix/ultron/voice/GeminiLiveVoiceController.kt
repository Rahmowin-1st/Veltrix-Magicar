package com.veltrix.ultron.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.veltrix.ultron.remote.GeminiLiveEphemeralToken
import com.veltrix.ultron.remote.GeminiLiveTokenClient
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

enum class GeminiLiveVoiceState {
    IDLE,
    CONNECTING,
    LISTENING,
    TOOL_RUNNING,
    ERROR
}

data class GeminiLiveToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, String>
)

interface GeminiLiveVoiceListener {
    fun onStateChanged(state: GeminiLiveVoiceState)
    fun onInputTranscript(text: String, final: Boolean)
    fun onOutputTranscript(text: String)
    fun onToolCall(call: GeminiLiveToolCall)
    fun onToolCancellation(ids: List<String>) {}
    fun onInputLevel(level: Float) {}
    fun onTurnComplete() {}
    fun onError(message: String)
}

/**
 * Gemini 3.8 Live voice transport for Veltrix Magicar.
 *
 * Security invariants:
 * - long-lived Gemini keys never exist in the APK;
 * - Android receives only a backend-minted short-lived single-use token;
 * - raw microphone/model PCM stays in RAM and is never written to disk;
 * - this class can request device work only through declared tool calls;
 * - actual UI authority remains inside the existing explicit user-session gate.
 */
class GeminiLiveVoiceController(context: Context) {
    private val appContext = context.applicationContext
    private val tokenClient = GeminiLiveTokenClient(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "veltrix-magicar-live-connect").apply { isDaemon = true }
    }
    private val inputWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "veltrix-magicar-live-input").apply { isDaemon = true }
    }
    private val outputWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "veltrix-magicar-live-output").apply { isDaemon = true }
    }
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val active = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private val missionActive = AtomicBoolean(false)
    private val transcriptLock = Any()
    private val idleStopRunnable = Runnable {
        if (active.get()) stop()
    }

    @Volatile
    var listener: GeminiLiveVoiceListener = NOOP

    @Volatile private var socket: WebSocket? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    @Volatile private var currentToken: GeminiLiveEphemeralToken? = null
    @Volatile private var resumeHandle: String? = null
    @Volatile private var reconnecting = false
    private var outputTranscript = StringBuilder()

    fun start() {
        if (destroyed.get()) return
        if (!active.compareAndSet(false, true)) return
        resumeHandle = null
        reconnecting = false
        mainHandler.removeCallbacks(idleStopRunnable)
        emitState(GeminiLiveVoiceState.CONNECTING)
        connectWorker.execute {
            val token = runCatching { tokenClient.request() }
                .getOrElse { error ->
                    fail("Gemini Live authentication failed: ${safeMessage(error)}")
                    return@execute
                }
            if (!active.get()) return@execute
            currentToken = token
            openSocket(token, handle = null)
        }
    }

    fun setMissionActive(active: Boolean) {
        missionActive.set(active)
        if (active) disarmIdleStop() else if (this.active.get()) armIdleStop()
    }

    fun isActive(): Boolean = active.get()

    fun stop() {
        val wasActive = active.getAndSet(false)
        if (wasActive) {
            runCatching {
                socket?.send(
                    JSONObject()
                        .put("realtimeInput", JSONObject().put("audioStreamEnd", true))
                        .toString()
                )
            }
        }
        runCatching { socket?.close(1000, "user_stop") }
        socket = null
        currentToken = null
        resumeHandle = null
        reconnecting = false
        missionActive.set(false)
        mainHandler.removeCallbacks(idleStopRunnable)
        stopRecorder()
        stopPlayer()
        synchronized(transcriptLock) { outputTranscript = StringBuilder() }
        emitState(GeminiLiveVoiceState.IDLE)
    }

    fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        stop()
        listener = NOOP
        http.dispatcher.executorService.shutdownNow()
        http.connectionPool.evictAll()
        connectWorker.shutdownNow()
        inputWorker.shutdownNow()
        outputWorker.shutdownNow()
    }

    fun sendToolResult(
        call: GeminiLiveToolCall,
        state: String,
        message: String,
        evidence: List<String> = emptyList()
    ): Boolean {
        val current = socket ?: return false
        if (!active.get()) return false
        val responsePayload = JSONObject()
            .put("result", JSONObject()
                .put("state", state.take(80))
                .put("message", message.take(1_200))
                .put("evidence", JSONArray(evidence.take(12).map { it.take(240) }))
            )
            .put("scheduling", "INTERRUPT")

        val functionResponse = JSONObject()
            .put("id", call.id)
            .put("name", call.name)
            .put("response", responsePayload)

        val payload = JSONObject()
            .put(
                "toolResponse",
                JSONObject().put(
                    "functionResponses",
                    JSONArray().put(functionResponse)
                )
            )
        val sent = current.send(payload.toString())
        if (sent) {
            armIdleStop()
            emitState(GeminiLiveVoiceState.LISTENING)
        }
        return sent
    }

    private fun openSocket(token: GeminiLiveEphemeralToken, handle: String?) {
        if (!active.get()) return
        val encoded = URLEncoder.encode(token.token, Charsets.UTF_8.name()).replace("+", "%20")
        val request = Request.Builder()
            .url(
                "wss://generativelanguage.googleapis.com/ws/" +
                    "google.ai.generativelanguage.v1beta.GenerativeService." +
                    "BidiGenerateContentConstrained?access_token=$encoded"
            )
            .build()
        socket = http.newWebSocket(request, liveSocketListener(token, handle))
    }

    private fun liveSocketListener(
        token: GeminiLiveEphemeralToken,
        handle: String?
    ) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!active.get()) {
                webSocket.close(1000, "inactive")
                return
            }
            reconnecting = false
            if (!webSocket.send(setupMessage(token.model, handle))) {
                fail("Gemini Live setup could not be sent")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!active.get()) return
            handleServerMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket === webSocket) socket = null
            stopRecorder()
            stopPlayer()
            if (!active.get()) {
                emitState(GeminiLiveVoiceState.IDLE)
                return
            }
            maybeReconnect(token)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket === webSocket) socket = null
            stopRecorder()
            stopPlayer()
            if (!active.get()) return
            if (!maybeReconnect(token)) {
                fail("Gemini Live connection failed: ${safeMessage(t)}")
            }
        }
    }

    private fun handleServerMessage(raw: String) {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return

        if (root.has("setupComplete")) {
            ensurePlayer()
            startRecorder()
            armIdleStop()
            emitState(GeminiLiveVoiceState.LISTENING)
        }

        root.optJSONObject("sessionResumptionUpdate")?.let { update ->
            if (update.optBoolean("resumable", false)) {
                update.optString("newHandle").trim()
                    .takeIf(String::isNotEmpty)
                    ?.let { resumeHandle = it }
            }
        }

        root.optJSONObject("serverContent")?.let { content ->
            content.optJSONObject("interimInputTranscription")
                ?.optString("text")
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let {
                    disarmIdleStop()
                    emitInputTranscript(it, final = false)
                }

            content.optJSONObject("inputTranscription")
                ?.optString("text")
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let {
                    disarmIdleStop()
                    emitInputTranscript(it, final = true)
                }

            content.optJSONObject("outputTranscription")
                ?.optString("text")
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { chunk ->
                    synchronized(transcriptLock) {
                        if (outputTranscript.isNotEmpty() && !outputTranscript.endsWith(" ")) {
                            outputTranscript.append(' ')
                        }
                        outputTranscript.append(chunk)
                    }
                }

            content.optJSONObject("modelTurn")
                ?.optJSONArray("parts")
                ?.let(::playAudioParts)

            if (content.optBoolean("interrupted", false)) {
                flushPlayer()
                synchronized(transcriptLock) { outputTranscript = StringBuilder() }
            }

            if (content.optBoolean("turnComplete", false)) {
                val completed = synchronized(transcriptLock) {
                    outputTranscript.toString().trim().also { outputTranscript = StringBuilder() }
                }
                if (completed.isNotEmpty()) emitOutputTranscript(completed)
                emitTurnComplete()
                armIdleStop()
                emitState(GeminiLiveVoiceState.LISTENING)
            }
        }

        root.optJSONObject("toolCall")
            ?.optJSONArray("functionCalls")
            ?.let(::handleToolCalls)

        root.optJSONObject("toolCallCancellation")
            ?.optJSONArray("ids")
            ?.let { array ->
                val ids = buildList {
                    for (index in 0 until array.length()) {
                        array.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
                    }
                }
                if (ids.isNotEmpty()) emitToolCancellation(ids)
            }

        if (root.has("goAway")) {
            currentToken?.let(::maybeReconnect)
        }
    }

    private fun handleToolCalls(calls: JSONArray) {
        for (index in 0 until calls.length()) {
            val item = calls.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            val name = item.optString("name").trim()
            if (id.isBlank() || name.isBlank()) continue
            val argsObject = item.optJSONObject("args") ?: JSONObject()
            val args = buildMap {
                val keys = argsObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, argsObject.opt(key)?.toString().orEmpty())
                }
            }
            disarmIdleStop()
            emitState(GeminiLiveVoiceState.TOOL_RUNNING)
            emitToolCall(GeminiLiveToolCall(id = id, name = name, arguments = args))
        }
    }

    private fun playAudioParts(parts: JSONArray) {
        for (index in 0 until parts.length()) {
            val inline = parts.optJSONObject(index)?.optJSONObject("inlineData") ?: continue
            val mimeType = inline.optString("mimeType")
            if (!mimeType.startsWith("audio/pcm")) continue
            val encoded = inline.optString("data")
            if (encoded.isBlank()) continue
            val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: continue
            outputWorker.execute {
                if (!active.get()) return@execute
                val current = ensurePlayer() ?: return@execute
                runCatching { current.write(bytes, 0, bytes.size) }
            }
        }
    }

    private fun startRecorder() {
        if (!active.get() || recorder != null) return
        inputWorker.execute {
            if (!active.get() || recorder != null) return@execute
            val minBuffer = AudioRecord.getMinBufferSize(
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) {
                fail("Microphone audio buffer is unavailable")
                return@execute
            }

            val created = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    INPUT_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, INPUT_CHUNK_BYTES * 4)
                )
            }.getOrElse { error ->
                fail("Microphone could not start: ${error.javaClass.simpleName}")
                return@execute
            }
            recorder = created

            runCatching { created.startRecording() }.onFailure { error ->
                recorder = null
                runCatching { created.release() }
                fail("Microphone could not start: ${error.javaClass.simpleName}")
                return@execute
            }

            val chunk = ByteArray(INPUT_CHUNK_BYTES)
            while (active.get() && recorder === created) {
                val count = runCatching { created.read(chunk, 0, chunk.size) }.getOrDefault(-1)
                if (count <= 0) continue
                emitInputLevel(rmsLevel(chunk, count))
                val current = socket ?: continue
                val encoded = Base64.encodeToString(chunk, 0, count, Base64.NO_WRAP)
                val audio = JSONObject()
                    .put("data", encoded)
                    .put("mimeType", "audio/pcm;rate=$INPUT_SAMPLE_RATE")
                val payload = JSONObject()
                    .put("realtimeInput", JSONObject().put("audio", audio))
                if (!current.send(payload.toString())) {
                    break
                }
            }
        }
    }

    private fun stopRecorder() {
        val current = recorder
        recorder = null
        runCatching { current?.stop() }
        runCatching { current?.release() }
    }

    private fun ensurePlayer(): AudioTrack? {
        player?.let { return it }
        val minBuffer = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return null
        val created = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(OUTPUT_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, OUTPUT_SAMPLE_RATE * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return null
        runCatching { created.play() }.onFailure {
            runCatching { created.release() }
            return null
        }
        player = created
        return created
    }

    private fun flushPlayer() {
        outputWorker.execute {
            player?.let { current ->
                runCatching {
                    current.pause()
                    current.flush()
                    current.play()
                }
            }
        }
    }

    private fun stopPlayer() {
        val current = player
        player = null
        runCatching { current?.pause() }
        runCatching { current?.flush() }
        runCatching { current?.release() }
    }

    private fun setupMessage(model: String, handle: String?): String {
        val executeTask = JSONObject()
            .put("name", "execute_user_task")
            .put(
                "description",
                "Execute exactly one task explicitly requested by the user on this Android head unit. " +
                    "Use this for opening apps, Chrome/web work, YouTube, settings, typing, tapping, scrolling, " +
                    "or any other device action. Never invent a task."
            )
            .put("behavior", "NON_BLOCKING")
            .put(
                "parameters",
                JSONObject()
                    .put("type", "OBJECT")
                    .put(
                        "properties",
                        JSONObject().put(
                            "objective",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "The user's explicit task, faithfully preserved.")
                        )
                    )
                    .put("required", JSONArray().put("objective"))
            )

        val cancelTask = JSONObject()
            .put("name", "cancel_active_task")
            .put("description", "Cancel the currently active user-requested device task.")
            .put("behavior", "NON_BLOCKING")

        val pauseTask = JSONObject()
            .put("name", "pause_active_task")
            .put("description", "Pause the currently active user-requested device task.")
            .put("behavior", "NON_BLOCKING")

        val resumeTask = JSONObject()
            .put("name", "resume_active_task")
            .put("description", "Resume a user-requested task that is currently paused.")
            .put("behavior", "NON_BLOCKING")

        val confirmPending = JSONObject()
            .put("name", "confirm_pending_action")
            .put(
                "description",
                "Confirm a pending sensitive or irreversible action only after the user explicitly says yes/confirm."
            )
            .put("behavior", "NON_BLOCKING")

        val denyPending = JSONObject()
            .put("name", "deny_pending_action")
            .put("description", "Decline the pending sensitive or irreversible action when the user says no/cancel.")
            .put("behavior", "NON_BLOCKING")

        val tools = JSONArray().put(
            JSONObject().put(
                "functionDeclarations",
                JSONArray()
                    .put(executeTask)
                    .put(cancelTask)
                    .put(pauseTask)
                    .put(resumeTask)
                    .put(confirmPending)
                    .put(denyPending)
            )
        )

        val setup = JSONObject()
            .put("model", if (model.startsWith("models/")) model else "models/$model")
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
            )
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            "You are Veltrix Magicar, the low-latency voice front-end of an Android car display assistant. " +
                                "Stay idle unless the user speaks or explicitly invokes you. Never initiate device actions yourself. " +
                                "For any device, app, Chrome, YouTube, web, setting, tap, scroll, typing, navigation, or UI action, " +
                                "call execute_user_task with exactly what the user requested. Do not claim success before the tool result. " +
                                "Normal reversible UI work is autonomous. If a tool result says explicit confirmation is required for a " +
                                "purchase, destructive reset/delete, account/security change, credential step, uninstall, or similarly " +
                                "irreversible action, ask the user briefly and wait. Call confirm_pending_action only after an explicit yes; " +
                                "call deny_pending_action after an explicit no/cancel. If the user asks only a general question, answer " +
                                "conversationally without a device-action tool. Be concise. The user may interrupt you at any time; " +
                                "immediately yield to the interruption."
                        )
                    )
                )
            )
            .put("tools", tools)
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put(
                "realtimeInputConfig",
                JSONObject().put(
                    "automaticActivityDetection",
                    JSONObject()
                        .put("disabled", false)
                        .put("prefixPaddingMs", 120)
                        .put("silenceDurationMs", 600)
                )
            )
            .put(
                "contextWindowCompression",
                JSONObject().put("slidingWindow", JSONObject())
            )
            .put(
                "sessionResumption",
                if (handle.isNullOrBlank()) JSONObject() else JSONObject().put("handle", handle)
            )

        return JSONObject().put("setup", setup).toString()
    }

    private fun maybeReconnect(token: GeminiLiveEphemeralToken): Boolean {
        if (!active.get() || reconnecting) return false
        val handle = resumeHandle ?: return false
        val expiry = token.expireTime?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (expiry != null && !Instant.now().isBefore(expiry.minusSeconds(5))) return false
        reconnecting = true
        emitState(GeminiLiveVoiceState.CONNECTING)
        connectWorker.execute {
            if (!active.get()) {
                reconnecting = false
                return@execute
            }
            openSocket(token, handle)
        }
        return true
    }

    private fun armIdleStop() {
        mainHandler.removeCallbacks(idleStopRunnable)
        if (!missionActive.get()) {
            mainHandler.postDelayed(idleStopRunnable, IDLE_SLEEP_MS)
        }
    }

    private fun disarmIdleStop() {
        mainHandler.removeCallbacks(idleStopRunnable)
    }

    private fun rmsLevel(bytes: ByteArray, count: Int): Float {
        if (count < 2) return 0f
        var sum = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < count) {
            val lo = bytes[i].toInt() and 0xff
            val hi = bytes[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            val normalized = sample / 32768.0
            sum += normalized * normalized
            samples++
            i += 2
        }
        if (samples == 0) return 0f
        val rms = sqrt(sum / samples)
        return ((rms - 0.008) / 0.13).coerceIn(0.0, 1.0).toFloat()
    }

    private fun fail(message: String) {
        active.set(false)
        runCatching { socket?.close(1011, "client_error") }
        socket = null
        stopRecorder()
        stopPlayer()
        reconnecting = false
        emitState(GeminiLiveVoiceState.ERROR)
        mainHandler.post { listener.onError(message.take(500)) }
    }

    private fun emitState(value: GeminiLiveVoiceState) {
        mainHandler.post { listener.onStateChanged(value) }
    }

    private fun emitInputTranscript(text: String, final: Boolean) {
        mainHandler.post { listener.onInputTranscript(text.take(2_000), final) }
    }

    private fun emitOutputTranscript(text: String) {
        mainHandler.post { listener.onOutputTranscript(text.take(4_000)) }
    }

    private fun emitInputLevel(level: Float) {
        mainHandler.post { listener.onInputLevel(level.coerceIn(0f, 1f)) }
    }

    private fun emitTurnComplete() {
        mainHandler.post { listener.onTurnComplete() }
    }

    private fun emitToolCall(call: GeminiLiveToolCall) {
        mainHandler.post { listener.onToolCall(call) }
    }

    private fun emitToolCancellation(ids: List<String>) {
        mainHandler.post { listener.onToolCancellation(ids) }
    }

    private fun safeMessage(error: Throwable): String =
        error.message?.take(240) ?: error.javaClass.simpleName

    companion object {
        private const val INPUT_SAMPLE_RATE = 16_000
        private const val OUTPUT_SAMPLE_RATE = 24_000
        private const val INPUT_CHUNK_BYTES = 3_200
        private const val IDLE_SLEEP_MS = 90_000L

        private val NOOP = object : GeminiLiveVoiceListener {
            override fun onStateChanged(state: GeminiLiveVoiceState) = Unit
            override fun onInputTranscript(text: String, final: Boolean) = Unit
            override fun onOutputTranscript(text: String) = Unit
            override fun onToolCall(call: GeminiLiveToolCall) = Unit
            override fun onError(message: String) = Unit
        }
    }
}
