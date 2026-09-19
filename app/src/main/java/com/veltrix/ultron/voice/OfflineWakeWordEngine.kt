package com.veltrix.ultron.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Always-local wake detector for "Hey Magicar".
 *
 * The 3.3M GigaSpeech KWS model runs with one CPU thread so the 4 GB UIS8581A
 * head unit stays responsive. No microphone audio leaves the device while idle.
 */
class OfflineWakeWordEngine(
    context: Context,
    private val onWake: () -> Unit
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "magicar-offline-wake").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val listening = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private val audioLock = Any()

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var spotter: KeywordSpotter? = null

    fun start(): Boolean {
        if (destroyed.get()) return false
        if (!hasMicrophonePermission()) return false
        if (!assetsReady()) return false
        if (!listening.compareAndSet(false, true)) return true
        worker.execute(::runLoop)
        return true
    }

    fun pause() {
        listening.set(false)
        stopRecorder()
    }

    fun resume(): Boolean = start()

    fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        pause()
        worker.shutdownNow()
        runCatching { spotter?.release() }
        spotter = null
    }

    private fun runLoop() {
        val kws = ensureSpotter() ?: run {
            listening.set(false)
            return
        }
        val stream = runCatching { kws.createStream() }.getOrNull() ?: run {
            listening.set(false)
            return
        }

        val recorder = createRecorder() ?: run {
            stream.release()
            listening.set(false)
            return
        }

        synchronized(audioLock) {
            if (!listening.get()) {
                runCatching { recorder.release() }
                stream.release()
                return
            }
            audioRecord = recorder
            runCatching { recorder.startRecording() }.onFailure {
                audioRecord = null
                runCatching { recorder.release() }
                stream.release()
                listening.set(false)
                return
            }
        }

        val buffer = ShortArray(FRAME_SAMPLES)
        try {
            while (listening.get() && !destroyed.get() && audioRecord === recorder) {
                val read = runCatching { recorder.read(buffer, 0, buffer.size) }.getOrDefault(-1)
                if (read <= 0) continue
                val samples = FloatArray(read) { index -> buffer[index] / 32768.0f }
                stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)

                while (listening.get() && kws.isReady(stream)) {
                    kws.decode(stream)
                    val keyword = kws.getResult(stream).keyword.trim()
                    if (keyword.isNotEmpty()) {
                        // There is exactly one keyword in the bundled keyword file.
                        kws.reset(stream)
                        listening.set(false)
                        stopRecorder()
                        main.post(onWake)
                        break
                    }
                }
            }
        } finally {
            runCatching { stream.release() }
            synchronized(audioLock) {
                if (audioRecord === recorder) audioRecord = null
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
        }
    }

    private fun ensureSpotter(): KeywordSpotter? {
        spotter?.let { return it }
        return synchronized(this) {
            spotter?.let { return@synchronized it }
            val model = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$MODEL_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    decoder = "$MODEL_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    joiner = "$MODEL_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
                ),
                tokens = "$MODEL_DIR/tokens.txt",
                numThreads = 1,
                debug = false,
                provider = "cpu",
                modelType = "zipformer2"
            )
            val config = KeywordSpotterConfig(
                featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = model,
                maxActivePaths = 4,
                keywordsFile = "$MODEL_DIR/keywords.txt",
                keywordsScore = 2.2f,
                keywordsThreshold = 0.20f,
                numTrailingBlanks = 1
            )
            runCatching {
                KeywordSpotter(assetManager = appContext.assets, config = config)
            }.getOrNull()?.also { spotter = it }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(): AudioRecord? {
        if (!hasMicrophonePermission()) return null
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return null
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, FRAME_SAMPLES * 2 * 4)
            )
        }.getOrNull() ?: return null
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            return null
        }
        return recorder
    }

    private fun stopRecorder() {
        synchronized(audioLock) {
            val current = audioRecord
            audioRecord = null
            runCatching { current?.stop() }
            runCatching { current?.release() }
        }
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun assetsReady(): Boolean = REQUIRED_ASSETS.all { relative ->
        runCatching {
            appContext.assets.open("$MODEL_DIR/$relative").use { it.read() }
            true
        }.getOrDefault(false)
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 1_600 // 100 ms
        private const val MODEL_DIR = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
        private val REQUIRED_ASSETS = listOf(
            "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            "tokens.txt",
            "keywords.txt"
        )
    }
}
