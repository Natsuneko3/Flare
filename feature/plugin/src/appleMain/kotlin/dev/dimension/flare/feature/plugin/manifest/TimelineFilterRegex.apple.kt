@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.dimension.flare.feature.plugin.manifest

import kotlinx.cinterop.pointed
import kotlinx.cinterop.value
import platform.Foundation.NSMakeRange
import platform.Foundation.NSMatchingReportProgress
import platform.Foundation.NSRegularExpression
import platform.Foundation.enumerateMatchesInString
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal actual fun compileTimelineFilterRegex(pattern: String): ((String) -> Boolean)? {
    val portable = foundationPattern(pattern) ?: return null
    val expression = NSRegularExpression.regularExpressionWithPattern(portable, options = 0u, error = null) ?: return null
    return { text -> expression.containsTimelineMatch(text, TimeSource.Monotonic.markNow() + 10.milliseconds) }
}

private const val ASCII_WORD = "[A-Za-z0-9_]"
private val asciiEscapes =
    mapOf(
        'd' to "[0-9]",
        'D' to "[^0-9]",
        'w' to ASCII_WORD,
        'W' to "[^A-Za-z0-9_]",
        's' to """[\t\n\f\r ]""",
        'S' to """[^\t\n\f\r ]""",
        // Word boundaries stay ASCII even inside a case-insensitive group.
        'b' to "(?-i:(?<=$ASCII_WORD)(?!$ASCII_WORD)|(?<!$ASCII_WORD)(?=$ASCII_WORD))",
        'B' to "(?-i:(?<=$ASCII_WORD)(?=$ASCII_WORD)|(?<!$ASCII_WORD)(?!$ASCII_WORD))",
    )

private val asciiPosixRanges =
    mapOf(
        "alnum" to "A-Za-z0-9",
        "alpha" to "A-Za-z",
        "ascii" to """\x00-\x7F""",
        "blank" to """\t """,
        "cntrl" to """\x00-\x1F\x7F""",
        "digit" to "0-9",
        "graph" to """\x21-\x7E""",
        "lower" to "a-z",
        "print" to """\x20-\x7E""",
        "punct" to """\x21-\x2F\x3A-\x40\x5B-\x60\x7B-\x7E""",
        "space" to """\x09-\x0D """,
        "upper" to "A-Z",
        "word" to "A-Za-z0-9_",
        "xdigit" to "0-9A-Fa-f",
    )

// Foundation uses Unicode shorthands. Expand only regex tokens, preserving quoted literals and escaped backslashes.
private fun foundationPattern(pattern: String): String? =
    buildString(pattern.length) {
        var firstClassChar = -1
        var index = 0
        while (index < pattern.length) {
            val char = pattern[index++]
            if (char != '\\' || index == pattern.length) {
                when (char) {
                    '[' -> {
                        if (firstClassChar == -1) {
                            firstClassChar = if (pattern.getOrNull(index) == '^') index + 1 else index
                        } else if (pattern.getOrNull(index) == ':') {
                            val end = pattern.indexOf(":]", index + 1)
                            if (end == -1) return null
                            val name = pattern.substring(index + 1, end)
                            val range = asciiPosixRanges[name.removePrefix("^")] ?: return null
                            append(if (name.startsWith('^')) "[^" else "[").append(range).append(']')
                            index = end + 2
                            continue
                        } else {
                            append('\\')
                        }
                    }

                    ']' -> {
                        if (index - 1 == firstClassChar) append('\\') else firstClassChar = -1
                    }

                    '&' -> {
                        if (firstClassChar != -1) append('\\')
                    }
                }
                append(char)
                continue
            }

            val escape = pattern[index++]
            if (firstClassChar != -1 && (escape == 'b' || escape == 'B' || escape == 'Q')) return null
            if (escape == 'Q') {
                val end = pattern.indexOf("""\E""", index)
                val limit = if (end == -1) pattern.length else end + 2
                append(pattern, index - 2, limit)
                index = limit
            } else {
                val replacement = asciiEscapes[escape]
                if (replacement == null) append('\\').append(escape) else append(replacement)
            }
        }
    }

internal fun NSRegularExpression.containsTimelineMatch(
    text: String,
    deadline: TimeMark,
): Boolean {
    var found = false
    enumerateMatchesInString(text, NSMatchingReportProgress, NSMakeRange(0u, text.length.toULong())) { match, _, stop ->
        val timedOut = deadline.hasPassedNow()
        if (match != null || timedOut) {
            found = match != null && !timedOut
            stop?.pointed?.value = true
        }
    }
    return found
}
