package dev.dimension.flare.data.datasource.microblog.paging

import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.contentPostOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Owns only the explicitly refreshed recommendation page, never the source's shared cache. */
internal class RecommendationBatchLoader(
    private val source: RecommendationRefreshSource,
    tabId: String,
) : CacheableRemoteLoader<UiTimelineV2> by source {
    override val pagingKey: String = "for_you_batch:${source.pagingKey.length}:${source.pagingKey}:$tabId"
    private val viewed = MutableStateFlow<List<MicroBlogKey>>(emptyList())

    fun markViewed(items: List<UiTimelineV2>) {
        val keys = items.mapNotNull { it.contentPostOrNull()?.statusKey }.filter { it.id.isNotBlank() }.distinct()
        if (keys.isEmpty()) return
        viewed.update { previous ->
            val recent = previous.toMutableList()
            keys.forEach { key ->
                recent.remove(key)
                recent.add(key)
            }
            recent.takeLast(MAX_VIEWED_POSTS)
        }
    }

    suspend fun loadFresh(
        pageSize: Int,
        accept: (UiTimelineV2) -> Boolean = { true },
    ): PagingResult<UiTimelineV2>? {
        require(pageSize > 0)
        // Keep the same exclusion list throughout this request, even if the viewport moves.
        val seen = viewed.value.toSet()
        val seenPostIds = seen.map { it.id }
        val collected = mutableListOf<UiTimelineV2>()
        val collectedKeys = mutableSetOf<MicroBlogKey>()
        val requestedCursors = mutableSetOf<String?>()
        var cursor: String? = null
        var nextKey: String? = null
        for (page in 0 until MAX_REQUESTS) {
            currentCoroutineContext().ensureActive()
            if (!requestedCursors.add(cursor)) break
            val result = source.loadRecommendations(pageSize, cursor, seenPostIds)
            currentCoroutineContext().ensureActive()
            result.continuationFailure?.let { throw it }
            result.sourceFailures.firstOrNull()?.let { throw it }
            result.data.forEach { item ->
                val key = item.contentPostOrNull()?.statusKey
                if (key != null && key !in seen && key !in collectedKeys && accept(item)) {
                    collectedKeys.add(key)
                    collected.add(item)
                }
            }
            nextKey = result.nextKey?.takeUnless { it in requestedCursors }
            // Keep the whole last page: truncating it would skip posts behind its cursor.
            if (collected.size >= pageSize || nextKey == null) break
            cursor = nextKey
        }
        currentCoroutineContext().ensureActive()
        return collected.takeIf { it.isNotEmpty() }?.let { PagingResult(data = it, nextKey = nextKey) }
    }

    private companion object {
        const val MAX_VIEWED_POSTS = 200
        const val MAX_REQUESTS = 3
    }
}
