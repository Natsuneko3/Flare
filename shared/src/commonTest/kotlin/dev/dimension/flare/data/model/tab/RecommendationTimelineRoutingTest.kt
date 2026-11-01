package dev.dimension.flare.data.model.tab

import dev.dimension.flare.data.datasource.microblog.paging.CacheableRemoteLoader
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.datasource.microblog.paging.RecommendationBatchLoader
import dev.dimension.flare.data.datasource.microblog.paging.RecommendationRefreshSource
import dev.dimension.flare.data.model.IconType
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.model.UiTimelineV2
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class RecommendationTimelineRoutingTest {
    private val source =
        object : RecommendationRefreshSource {
            override val pagingKey = "featured_account@x.com"

            override suspend fun load(
                pageSize: Int,
                request: PagingRequest,
            ) = PagingResult<UiTimelineV2>()

            override suspend fun loadRecommendations(
                pageSize: Int,
                cursor: String?,
                seenPostIds: List<String>,
            ) = PagingResult<UiTimelineV2>()
        }

    @Test fun aStandaloneFeaturedPageGetsItsOwnSessionRegardlessOfDisplayTitle() {
        val routed =
            assertIs<RecommendationBatchLoader>(tab(TimelineSpecIds.XQT_FEATURED, title = "Custom title").withRecommendationRefresh(source))
        assertNotEquals(source.pagingKey, routed.pagingKey)
    }

    @Test fun followingAndOtherSourcesKeepTheirOriginalLoaderEvenWithForYouTitle() {
        for (id in listOf(TimelineSpecIds.COMMON_HOME, "xqt.popular", "mastodon.public")) {
            assertSame(source, tab(id, title = "For You").withRecommendationRefresh(source))
        }
    }

    @Test fun groupsKeepTheirOriginalLoaderEvenIfTheyContainOnlyForYou() {
        val child = tab(TimelineSpecIds.XQT_FEATURED)
        val group =
            UiGroupTimelineTabItem(
                "group",
                listOf(child),
                TimelineMergePolicy.Time,
                GroupSource.Manual,
                TimelinePresentation(),
                UiText.Raw("For You"),
                IconType.FavIcon("x.com"),
                null,
                true,
            )
        assertSame(source, group.withRecommendationRefresh(source))
    }

    @Test fun twoPagesForOneAccountDoNotShareBatchCaches() {
        val first = assertIs<RecommendationBatchLoader>(tab(TimelineSpecIds.XQT_FEATURED, "first").withRecommendationRefresh(source))
        val second = assertIs<RecommendationBatchLoader>(tab(TimelineSpecIds.XQT_FEATURED, "second").withRecommendationRefresh(source))
        assertNotEquals(first.pagingKey, second.pagingKey)
    }

    @Test fun aSourceWithoutTheCapabilityKeepsNormalPaging() {
        val ordinary =
            object : CacheableRemoteLoader<UiTimelineV2> {
                override val pagingKey = "ordinary"

                override suspend fun load(
                    pageSize: Int,
                    request: PagingRequest,
                ) = PagingResult<UiTimelineV2>()
            }
        assertSame(ordinary, tab(TimelineSpecIds.XQT_FEATURED).withRecommendationRefresh(ordinary))
    }

    private fun tab(
        spec: String,
        id: String = "tab",
        title: String = "For You",
    ) = UiSourceTimelineTabItem(
        id,
        TimelineSourceRef("source", spec, UiText.Raw(title), IconType.FavIcon("x.com"), "{}"),
        null,
        UiText.Raw(title),
        IconType.FavIcon("x.com"),
        null,
        true,
    )
}
