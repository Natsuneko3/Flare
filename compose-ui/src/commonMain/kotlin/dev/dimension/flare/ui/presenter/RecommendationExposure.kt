package dev.dimension.flare.ui.presenter

internal data class RecommendationViewportItem(
    val index: Int,
    val key: Any,
    val top: Int,
    val bottom: Int,
)

internal fun <T : Any> recommendationVisibleItems(
    measuredItems: List<RecommendationViewportItem>,
    viewportStart: Int,
    viewportEnd: Int,
    leadingItemCount: Int,
    itemCount: Int,
    peek: (Int) -> T?,
    postKey: (T) -> String?,
): List<T> {
    if (viewportEnd <= viewportStart) return emptyList()
    return measuredItems.mapNotNull { measured ->
        val index = measured.index - leadingItemCount
        if (index !in 0 until itemCount || measured.bottom <= measured.top ||
            measured.bottom <= viewportStart || measured.top >= viewportEnd
        ) {
            return@mapNotNull null
        }
        val item = peek(index) ?: return@mapNotNull null
        item.takeIf { postKey(it)?.let { key -> key == measured.key } == true }
    }
}

/** Only retains items in the current viewport; feed-wide seen history belongs to the presenter. */
internal class RecommendationExposureTracker<T : Any>(
    private val dwellMillis: Long = 500L,
) {
    private data class Exposure(
        val sinceMillis: Long,
        var reported: Boolean = false,
    )

    private val exposures = mutableMapOf<String, Exposure>()

    fun update(
        visible: Map<String, T>,
        nowMillis: Long,
    ): List<T> {
        exposures.keys.retainAll(visible.keys)
        return visible.mapNotNull { (key, item) ->
            val exposure = exposures.getOrPut(key) { Exposure(nowMillis) }
            if (!exposure.reported && nowMillis - exposure.sinceMillis >= dwellMillis) {
                exposure.reported = true
                item
            } else {
                null
            }
        }
    }

    fun nextDelay(nowMillis: Long): Long? =
        exposures.values.filterNot { it.reported }.minOfOrNull {
            (dwellMillis - (nowMillis - it.sinceMillis)).coerceAtLeast(0)
        }
}
