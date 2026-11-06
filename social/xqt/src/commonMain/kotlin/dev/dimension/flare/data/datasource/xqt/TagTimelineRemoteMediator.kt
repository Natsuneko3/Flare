package dev.dimension.flare.data.datasource.xqt

import androidx.paging.ExperimentalPagingApi
import dev.dimension.flare.data.database.cache.mapper.cursor
import dev.dimension.flare.data.database.cache.mapper.tweets
import dev.dimension.flare.data.datasource.microblog.paging.CacheableRemoteLoader
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.network.xqt.api.PinnableTimelineApi
import dev.dimension.flare.data.network.xqt.model.TagTimelineRequest
import dev.dimension.flare.data.network.xqt.model.TagTimelineVariables
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.mapper.render

@OptIn(ExperimentalPagingApi::class)
internal class TagTimelineRemoteMediator(
    private val tag: String,
    private val service: PinnableTimelineApi,
    private val accountKey: MicroBlogKey,
) : CacheableRemoteLoader<UiTimelineV2> {
    override val pagingKey: String = "tag_${tag}_$accountKey"

    override suspend fun load(
        pageSize: Int,
        request: PagingRequest,
    ): PagingResult<UiTimelineV2> {
        val cursor =
            when (request) {
                PagingRequest.Refresh -> {
                    null
                }

                is PagingRequest.Append -> {
                    request.nextKey
                }

                is PagingRequest.Prepend -> {
                    return PagingResult(endOfPaginationReached = true)
                }
            }
        val response =
            service
                .postTagTimeline(
                    request =
                        TagTimelineRequest(
                            variables =
                                TagTimelineVariables(
                                    count = pageSize.toLong(),
                                    cursor = cursor,
                                    tag = tag,
                                ),
                        ),
                )
        check(response.isSuccessful) { "Unable to load the X category timeline" }
        val body = checkNotNull(response.body()) { "The X category response is missing its timeline" }
        check(body.errors.isNullOrEmpty()) { "The X category timeline response contains errors" }
        val instructions = body.data.home.homeTimelineUrt.instructions
        val tweets = instructions.tweets()
        val nextKey = instructions.cursor()

        return PagingResult(
            endOfPaginationReached = nextKey == null,
            data = tweets.mapNotNull { it.render(accountKey) },
            nextKey = nextKey,
        )
    }
}
