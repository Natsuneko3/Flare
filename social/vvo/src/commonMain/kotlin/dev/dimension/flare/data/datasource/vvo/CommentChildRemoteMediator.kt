package dev.dimension.flare.data.datasource.vvo

import androidx.paging.ExperimentalPagingApi
import dev.dimension.flare.data.datasource.microblog.paging.CacheableRemoteLoader
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.network.vvo.VVOService
import dev.dimension.flare.data.platform.VVO_PLATFORM_ID
import dev.dimension.flare.data.repository.LoginExpiredException
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.mapper.extractStatusMid
import dev.dimension.flare.ui.model.mapper.render
import kotlin.concurrent.Volatile

@OptIn(ExperimentalPagingApi::class)
internal class CommentChildRemoteMediator(
    private val service: VVOService,
    private val commentKey: MicroBlogKey,
    private val accountKey: MicroBlogKey,
    rootStatusId: String? = null,
    private val cachedRootComment: (suspend () -> UiTimelineV2.Post?)? = null,
) : CacheableRemoteLoader<UiTimelineV2> {
    override val pagingKey: String = "status_comments_child_${commentKey}_$accountKey"
    override val collapseReplyChains: Boolean = false

    @Volatile
    private var resolvedRootStatusId: String? = rootStatusId

    override suspend fun load(
        pageSize: Int,
        request: PagingRequest,
    ): PagingResult<UiTimelineV2> {
        val config = service.config()
        if (config.data?.login != true) {
            throw LoginExpiredException(
                accountKey = accountKey,
                platformId = VVO_PLATFORM_ID,
            )
        }

        return when (request) {
            PagingRequest.Refresh -> {
                val response = service.getHotFlowChild(cid = commentKey.id)
                val root = response.rootComment?.firstOrNull()
                val effectiveRootId = root?.extractStatusMid(resolvedRootStatusId) ?: resolvedRootStatusId
                resolvedRootStatusId = effectiveRootId
                val cachedRoot = cachedRootComment?.invoke()
                val rootItem =
                    root?.render(accountKey, rootStatusId = effectiveRootId, cachedClickEvent = cachedRoot?.clickEvent)
                        ?: cachedRoot
                val maxId = response.maxID?.takeIf { it > 0L }
                PagingResult(
                    endOfPaginationReached = maxId == null,
                    data =
                        listOfNotNull(rootItem) +
                            response.data.orEmpty().map {
                                it.render(accountKey, rootStatusId = effectiveRootId, parentCommentKey = commentKey)
                            },
                    nextKey = maxId?.toString(),
                )
            }

            is PagingRequest.Prepend -> {
                PagingResult(endOfPaginationReached = true)
            }

            is PagingRequest.Append -> {
                val cursor =
                    request.nextKey.toLongOrNull()?.takeIf { it > 0L }
                        ?: return PagingResult(endOfPaginationReached = true)
                val response =
                    service.getHotFlowChild(
                        cid = commentKey.id,
                        maxId = cursor,
                    )
                val effectiveRootId =
                    response.rootComment?.firstOrNull()?.extractStatusMid(resolvedRootStatusId) ?: resolvedRootStatusId
                resolvedRootStatusId = effectiveRootId
                val maxId = response.maxID?.takeIf { it > 0L && it != cursor }
                PagingResult(
                    endOfPaginationReached = maxId == null,
                    data =
                        response.data.orEmpty().map {
                            it.render(accountKey, rootStatusId = effectiveRootId, parentCommentKey = commentKey)
                        },
                    nextKey = maxId?.toString(),
                )
            }
        }
    }
}
