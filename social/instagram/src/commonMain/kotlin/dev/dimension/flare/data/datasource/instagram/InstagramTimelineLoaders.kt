package dev.dimension.flare.data.datasource.instagram

import dev.dimension.flare.data.datasource.microblog.paging.CacheableRemoteLoader
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.network.instagram.InstagramPage
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiTimelineV2
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class InstagramTimelineLoader(
    override val pagingKey: String,
    override val canJoinTimeline: Boolean = false,
    private val fetch: suspend (String?) -> InstagramPage,
    private val map: (JsonObject) -> UiTimelineV2?,
) : CacheableRemoteLoader<UiTimelineV2> {
    override suspend fun load(
        pageSize: Int,
        request: PagingRequest,
    ): PagingResult<UiTimelineV2> {
        if (request is PagingRequest.Prepend) return PagingResult(endOfPaginationReached = true)
        // Keep cursor history in the key so Room can resume the same page after a restart.
        val history = (request as? PagingRequest.Append)?.let { Json.decodeFromString<List<String>>(it.nextKey) }.orEmpty()
        val page = fetch(history.lastOrNull())
        val items = page.items.mapNotNull(map).distinctBy { it.statusKey }
        val next = page.nextCursor?.takeIf { it.isNotBlank() && it !in history && items.isNotEmpty() }
        return PagingResult(
            data = items,
            nextKey = next?.let { Json.encodeToString(history.takeLast(7) + it) },
        )
    }
}

internal class InstagramContextLoader(
    private val accountKey: MicroBlogKey,
    private val statusKey: MicroBlogKey,
    private val fetchPost: suspend (String) -> JsonObject,
    fetchComments: suspend (String, String?) -> InstagramPage,
) : CacheableRemoteLoader<UiTimelineV2> {
    override val pagingKey = "instagram_context_${statusKey}_$accountKey"
    override val collapseReplyChains = false
    private val comments =
        InstagramTimelineLoader(
            pagingKey = pagingKey,
            fetch = { cursor -> fetchComments(statusKey.id, cursor) },
            map = { it.toInstagramComment(accountKey, statusKey) },
        )

    override suspend fun load(
        pageSize: Int,
        request: PagingRequest,
    ): PagingResult<UiTimelineV2> =
        when (request) {
            PagingRequest.Refresh -> {
                // Present the focal post even when loading its comments later fails.
                val post = requireNotNull(fetchPost(statusKey.id).toInstagramPostOrNull(accountKey))
                PagingResult(data = listOf(post), nextKey = FIRST_COMMENT_PAGE)
            }

            is PagingRequest.Append -> {
                val page = if (request.nextKey == FIRST_COMMENT_PAGE) PagingRequest.Refresh else request
                comments.load(pageSize, page)
            }

            is PagingRequest.Prepend -> {
                PagingResult(endOfPaginationReached = true)
            }
        }

    private companion object {
        const val FIRST_COMMENT_PAGE = "comments"
    }
}
