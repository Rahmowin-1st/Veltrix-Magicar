package com.veltrix.ultron.voice

import java.util.Locale

enum class VoiceLocalControl {
    NONE,
    STOP_LISTENING,
    CANCEL_PENDING,
    PAUSE_ACTIVE,
    TAKE_OVER_ACTIVE,
    RESUME_ACTIVE
}

/** Pure fail-closed policy for text returned by Android speech recognition. */
object VoiceTranscriptPolicy {
    private const val MAX_TRANSCRIPT_CHARS = 1_200

    private val stopListeningPhrases = setOf(
        "stop listening",
        "stop mic",
        "microphone off",
        "mikrofonni toxta",
        "mikrofonni toxtat"
    )

    private val stopActivePhrases = setOf(
        "stop",
        "stop mission",
        "stop task",
        "vazifani to'xta",
        "vazifani to'xtat"
    )

    private val cancelPendingPhrases = setOf(
        "cancel",
        "cancel action",
        "bekor qil",
        "amalni bekor qil"
    )

    private val pauseActivePhrases = setOf(
        "pause mission",
        "pause task",
        "vazifani toxta",
        "vazifani toxtat"
    )

    private val takeOverActivePhrases = setOf(
        "take over",
        "take over mission",
        "boshqaruvni menga ber"
    )

    private val resumeActivePhrases = setOf(
        "resume mission",
        "continue mission",
        "vazifani davom ettir"
    )

    fun sanitize(raw: String?): String? {
        val clean = raw
            ?.asSequence()
            ?.filterNot(Char::isISOControl)
            ?.joinToString(separator = "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        return clean.take(MAX_TRANSCRIPT_CHARS)
    }

    fun localControl(raw: String?): VoiceLocalControl {
        val clean = sanitize(raw) ?: return VoiceLocalControl.NONE
        val normalized = normalizeControl(clean)
        return when (normalized) {
            in stopListeningPhrases -> VoiceLocalControl.STOP_LISTENING
            in stopActivePhrases -> VoiceLocalControl.PAUSE_ACTIVE
            in cancelPendingPhrases -> VoiceLocalControl.CANCEL_PENDING
            in pauseActivePhrases -> VoiceLocalControl.PAUSE_ACTIVE
            in takeOverActivePhrases -> VoiceLocalControl.TAKE_OVER_ACTIVE
            in resumeActivePhrases -> VoiceLocalControl.RESUME_ACTIVE
            else -> VoiceLocalControl.NONE
        }
    }

    internal fun normalizeControl(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace('’', '\'')
        .replace('‘', '\'')
        .replace(Regex("[^\\p{L}\\p{N}']+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
