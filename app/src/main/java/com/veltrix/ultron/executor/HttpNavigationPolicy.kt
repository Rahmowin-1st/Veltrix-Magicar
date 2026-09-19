package com.veltrix.ultron.executor

import java.net.URI
import java.util.Locale

/** Conservative URL gate for browser navigation actions. */
object HttpNavigationPolicy {
    private const val MAX_URL_LENGTH = 4096

    fun normalize(raw: String?): String? {
        val candidate = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (candidate.length > MAX_URL_LENGTH) return null
        val parsed = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (parsed.isOpaque) return null
        val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = parsed.host?.lowercase(Locale.ROOT) ?: return null
        if (parsed.rawUserInfo != null) return null
        if (parsed.port < -1 || parsed.port > 65535) return null

        val allowed = when (scheme) {
            "https" -> true
            "http" -> host == "localhost" || host == "127.0.0.1" || host == "::1"
            else -> false
        }
        if (!allowed) return null
        return parsed.toASCIIString()
    }
}
