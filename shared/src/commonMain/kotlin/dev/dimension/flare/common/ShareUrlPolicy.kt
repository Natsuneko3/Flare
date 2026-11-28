package dev.dimension.flare.common

import io.ktor.http.Url

public object ShareUrlPolicy {
    public fun normalize(rawUrl: String?): String? {
        val trimmed = rawUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.any { it.isWhitespace() }) return null
        val afterScheme =
            when {
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed.substring("https://".length)
                trimmed.startsWith("http://", ignoreCase = true) -> trimmed.substring("http://".length)
                else -> return null
            }
        if (afterScheme.isEmpty() || afterScheme.startsWith("/") || afterScheme.startsWith("?") || afterScheme.startsWith("#")) {
            return null
        }
        val parsed = runCatching { Url(trimmed) }.getOrNull() ?: return null
        val scheme = parsed.protocol.name
        if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) {
            return null
        }
        if (parsed.host.isBlank()) return null
        return trimmed
    }
}
