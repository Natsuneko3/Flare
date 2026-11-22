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
    val expression = NSRegularExpression.regularExpressionWithPattern(pattern, options = 0u, error = null) ?: return null
    return { text -> expression.containsTimelineMatch(text, TimeSource.Monotonic.markNow() + 10.milliseconds) }
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
