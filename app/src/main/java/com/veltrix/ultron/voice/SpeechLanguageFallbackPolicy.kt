package com.veltrix.ultron.voice

/** Pure policy for Android SpeechRecognizer language errors 12/13. */
internal object SpeechLanguageFallbackPolicy {
    const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    const val ERROR_LANGUAGE_UNAVAILABLE = 13

    fun shouldFallbackToSystem(
        errorCode: Int,
        usingOnDeviceRecognizer: Boolean,
        fallbackAlreadyAttempted: Boolean
    ): Boolean = usingOnDeviceRecognizer &&
        !fallbackAlreadyAttempted &&
        (errorCode == ERROR_LANGUAGE_NOT_SUPPORTED || errorCode == ERROR_LANGUAGE_UNAVAILABLE)
}
