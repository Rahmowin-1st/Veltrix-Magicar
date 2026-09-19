package com.veltrix.ultron.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import kotlin.math.PI
import kotlin.math.sin

/**
 * Owns the short-lived audio policy for one Magicar assistant session.
 *
 * Android 10 can duck other playback independently through audio focus. That is
 * deliberately preferred over forcing STREAM_MUSIC to an absolute value because
 * many FYT ROMs route USAGE_ASSISTANT through the same physical stream; changing
 * that stream would make the assistant quiet as well.
 */
class AssistantAudioSession(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null
    @Volatile private var active = false

    fun begin(): Boolean {
        if (active) return true
        val manager = audioManager ?: return false
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(assistantAttributes())
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener { /* Magicar session is user initiated and bounded. */ }
                .build()
            focusRequest = request
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
        active = granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (active) playActivationChime()
        return active
    }

    fun end() {
        if (!active) return
        playDeactivationChime()
        val manager = audioManager
        val request = focusRequest
        if (manager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && request != null) {
                manager.abandonAudioFocusRequest(request)
            } else {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(null)
            }
        }
        focusRequest = null
        active = false
    }

    fun release() {
        if (active) end()
        focusRequest = null
    }

    private fun playActivationChime() {
        playChime(
            listOf(
                ToneSegment(510.0, 690.0, 76),
                ToneSegment(690.0, 930.0, 92)
            )
        )
    }

    private fun playDeactivationChime() {
        playChime(
            listOf(
                ToneSegment(820.0, 650.0, 82),
                ToneSegment(650.0, 480.0, 96)
            )
        )
    }

    private fun playChime(segments: List<ToneSegment>) {
        val sampleRate = 24_000
        val gapSamples = (sampleRate * 0.012).toInt()
        val totalSamples = segments.sumOf { sampleRate * it.durationMs / 1000 } +
            gapSamples * (segments.size - 1).coerceAtLeast(0)
        if (totalSamples <= 0) return

        val pcm = ShortArray(totalSamples)
        var offset = 0
        for ((segmentIndex, segment) in segments.withIndex()) {
            val count = sampleRate * segment.durationMs / 1000
            for (i in 0 until count) {
                val t = i.toDouble() / sampleRate
                val progress = i.toDouble() / count.coerceAtLeast(1)
                val frequency = segment.fromHz + (segment.toHz - segment.fromHz) * progress
                val attack = (progress / 0.16).coerceIn(0.0, 1.0)
                val release = ((1.0 - progress) / 0.24).coerceIn(0.0, 1.0)
                val envelope = attack * release
                val sample = sin(2.0 * PI * frequency * t) * envelope * 0.22
                pcm[offset + i] = (sample * Short.MAX_VALUE).toInt().toShort()
            }
            offset += count
            if (segmentIndex != segments.lastIndex) offset += gapSamples
        }

        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val value = pcm[i].toInt()
            bytes[i * 2] = (value and 0xff).toByte()
            bytes[i * 2 + 1] = ((value ushr 8) and 0xff).toByte()
        }

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(assistantAttributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(maxOf(bytes.size, minBuffer))
                .build()
        }.getOrNull() ?: return

        runCatching {
            track.write(bytes, 0, bytes.size)
            track.play()
            val durationMs = segments.sumOf { it.durationMs } + 16L * segments.size
            Thread {
                runCatching { Thread.sleep(durationMs.coerceAtMost(500L)) }
                runCatching { track.stop() }
                runCatching { track.release() }
            }.start()
        }.onFailure {
            runCatching { track.release() }
        }
    }

    private fun assistantAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    private data class ToneSegment(
        val fromHz: Double,
        val toHz: Double,
        val durationMs: Int
    )
}
