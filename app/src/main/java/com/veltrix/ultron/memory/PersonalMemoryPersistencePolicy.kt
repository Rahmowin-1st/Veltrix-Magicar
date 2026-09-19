package com.veltrix.ultron.memory

/**
 * Final content minimization before personal memory persistence.
 * Credentials/codes are redacted before encrypted storage or model-visible recall.
 */
object PersonalMemoryPersistencePolicy {
    const val MAX_KEY_CHARS = 512
    const val MAX_VALUE_CHARS = 4_000
    const val MAX_SOURCE_CHARS = 256

    fun sanitize(record: MemoryRecord): MemoryRecord? {
        val key = sanitizeText(record.key, MAX_KEY_CHARS)
        val value = sanitizeText(record.value, MAX_VALUE_CHARS)
        val source = sanitizeText(record.source, MAX_SOURCE_CHARS)
        if (key.isBlank() || value.isBlank() || source.isBlank()) return null
        if (!record.confidence.isFinite() || record.confidence !in 0.0..1.0) return null
        return record.copy(key = key, value = value, source = source)
    }

    fun sanitizeText(raw: String, maxChars: Int): String {
        var value = raw.trim().take(maxChars)
        value = value.replace(Regex("(?i)bearer\\s+[A-Za-z0-9._~+/-]{8,}"), "<redacted-token>")
        value = value.replace(Regex("(?i)sk-[A-Za-z0-9_-]{8,}"), "<redacted-api-key>")
        value = value.replace(
            Regex("(?i)(password|passcode|secret|token|api[_ -]?key)\\s*[:=]\\s*\\S+"),
            "\$1=<redacted>"
        )
        value = value.replace(Regex("\\b(?:\\d[ -]?){13,19}\\b"), "<redacted-number>")
        value = value.replace(Regex("\\b\\d{6}\\b"), "<redacted-6-digit-code>")
        return value
    }
}
