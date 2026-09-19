package com.veltrix.ultron.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechLanguageFallbackPolicyTest {
    @Test
    fun `errors 12 and 13 fall back once from on-device recognizer`() {
        assertTrue(
            SpeechLanguageFallbackPolicy.shouldFallbackToSystem(
                errorCode = 12,
                usingOnDeviceRecognizer = true,
                fallbackAlreadyAttempted = false
            )
        )
        assertTrue(
            SpeechLanguageFallbackPolicy.shouldFallbackToSystem(
                errorCode = 13,
                usingOnDeviceRecognizer = true,
                fallbackAlreadyAttempted = false
            )
        )
    }

    @Test
    fun `language fallback never loops or retries system recognizer`() {
        assertFalse(
            SpeechLanguageFallbackPolicy.shouldFallbackToSystem(
                errorCode = 12,
                usingOnDeviceRecognizer = true,
                fallbackAlreadyAttempted = true
            )
        )
        assertFalse(
            SpeechLanguageFallbackPolicy.shouldFallbackToSystem(
                errorCode = 13,
                usingOnDeviceRecognizer = false,
                fallbackAlreadyAttempted = false
            )
        )
    }

    @Test
    fun `unrelated recognizer errors do not trigger language fallback`() {
        assertFalse(
            SpeechLanguageFallbackPolicy.shouldFallbackToSystem(
                errorCode = 8,
                usingOnDeviceRecognizer = true,
                fallbackAlreadyAttempted = false
            )
        )
    }
}
