package com.veltrix.ultron.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTranscriptPolicyTest {
    @Test
    fun sanitizesWhitespaceAndControlCharacters() {
        assertEquals(
            "open settings now",
            VoiceTranscriptPolicy.sanitize("  open\u0000   settings\n now  ")
        )
    }

    @Test
    fun blankTranscriptIsRejected() {
        assertNull(VoiceTranscriptPolicy.sanitize(" \n\t "))
    }

    @Test
    fun transcriptIsBoundedBeforeRuntimeHandoff() {
        val result = requireNotNull(VoiceTranscriptPolicy.sanitize("a".repeat(2_000)))
        assertEquals(1_200, result.length)
    }

    @Test
    fun exactCancelPhraseMapsToSafeLocalControl() {
        assertEquals(
            VoiceLocalControl.CANCEL_PENDING,
            VoiceTranscriptPolicy.localControl("Bekor qil")
        )
    }

    @Test
    fun exactStopPhraseMapsToStrongLocalMissionPauseBarrier() {
        assertEquals(
            VoiceLocalControl.PAUSE_ACTIVE,
            VoiceTranscriptPolicy.localControl("Stop")
        )
        assertEquals(
            VoiceLocalControl.PAUSE_ACTIVE,
            VoiceTranscriptPolicy.localControl("Stop mission")
        )
    }

    @Test
    fun exactPauseTakeOverAndResumePhrasesMapLocally() {
        assertEquals(
            VoiceLocalControl.PAUSE_ACTIVE,
            VoiceTranscriptPolicy.localControl("Pause mission")
        )
        assertEquals(
            VoiceLocalControl.TAKE_OVER_ACTIVE,
            VoiceTranscriptPolicy.localControl("Boshqaruvni menga ber")
        )
        assertEquals(
            VoiceLocalControl.RESUME_ACTIVE,
            VoiceTranscriptPolicy.localControl("Vazifani davom ettir")
        )
    }

    @Test
    fun longerObjectivesContainingControlWordsAreNotHijacked() {
        assertEquals(
            VoiceLocalControl.NONE,
            VoiceTranscriptPolicy.localControl("cancel haqida ma'lumot ber")
        )
        assertEquals(
            VoiceLocalControl.NONE,
            VoiceTranscriptPolicy.localControl("stop mission qanday ishlaydi")
        )
        assertEquals(
            VoiceLocalControl.NONE,
            VoiceTranscriptPolicy.localControl("pause mission qanday ishlaydi")
        )
        assertEquals(
            VoiceLocalControl.NONE,
            VoiceTranscriptPolicy.localControl("take over iborasini tarjima qil")
        )
        assertEquals(
            VoiceLocalControl.NONE,
            VoiceTranscriptPolicy.localControl("resume mission nima degani")
        )
    }

    @Test
    fun stopListeningRequiresExactControlPhraseAndStaysDistinctFromMissionStop() {
        assertEquals(
            VoiceLocalControl.STOP_LISTENING,
            VoiceTranscriptPolicy.localControl("Stop listening")
        )
        assertEquals(
            VoiceLocalControl.PAUSE_ACTIVE,
            VoiceTranscriptPolicy.localControl("Stop")
        )
        assertTrue(
            VoiceTranscriptPolicy.localControl("stop listening feature haqida ayt") == VoiceLocalControl.NONE
        )
    }
}
