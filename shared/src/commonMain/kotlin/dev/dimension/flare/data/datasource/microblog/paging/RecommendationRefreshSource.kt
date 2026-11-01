package dev.dimension.flare.data.datasource.microblog.paging

import dev.dimension.flare.ui.model.UiTimelineV2
import kotlin.native.HiddenFromObjC

/** Explicit recommendation requests; ordinary paging keeps its existing behavior. */
@HiddenFromObjC
public interface RecommendationRefreshSource : CacheableRemoteLoader<UiTimelineV2> {
    public suspend fun loadRecommendations(
        pageSize: Int,
        cursor: String?,
        seenPostIds: List<String>,
    ): PagingResult<UiTimelineV2>
}
