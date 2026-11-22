package dev.dimension.flare.feature.plugin.manifest

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException

internal actual fun compileTimelineFilterRegex(pattern: String): ((String) -> Boolean)? {
    val expression =
        try {
            Pattern.compile(pattern)
        } catch (_: PatternSyntaxException) {
            return null
        }
    return { text -> expression.matcher(text).find() }
}
