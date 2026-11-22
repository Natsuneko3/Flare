package dev.dimension.flare.feature.plugin.manifest

import platform.Foundation.NSRegularExpression
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.time.TimeSource

class TimelineFilterRegexAppleTest {
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    @Test
    fun discardsMatchesAfterTheDeadline() {
        val expression = NSRegularExpression.regularExpressionWithPattern("score", options = 0u, error = null)!!
        assertFalse(expression.containsTimelineMatch("score", TimeSource.Monotonic.markNow()))
    }
}
