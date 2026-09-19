package com.veltrix.ultron.brain

enum class BrainCapability {
    FAST_TEXT,
    REASONING,
    CODING,
    VISION,
    VIDEO,
    TOOL_USE,
    LONG_CONTEXT,
    SPEECH_TO_TEXT,
    LIVE_VOICE
}

data class BrainModel(
    val providerId: String,
    val modelId: String,
    val capabilities: Set<BrainCapability>,
    val freeTier: Boolean,
    val enabled: Boolean = true,
    val priority: Int = 100
)

data class BrainRequest(
    val required: Set<BrainCapability>,
    val preferFast: Boolean = false,
    val freeOnly: Boolean = true
)

data class ProviderHealth(
    val providerId: String,
    val healthy: Boolean,
    val rateLimited: Boolean = false
)
