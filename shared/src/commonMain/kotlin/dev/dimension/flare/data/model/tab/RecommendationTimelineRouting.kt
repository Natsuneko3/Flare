package dev.dimension.flare.data.model.tab

import dev.dimension.flare.data.datasource.microblog.paging.RecommendationBatchLoader
import dev.dimension.flare.data.datasource.microblog.paging.RecommendationRefreshSource
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.ui.model.UiTimelineV2

internal fun UiTimelineTabItem.withRecommendationRefresh(loader: RemoteLoader<UiTimelineV2>): RemoteLoader<UiTimelineV2> =
    if (this is UiSourceTimelineTabItem && source?.specId == TimelineSpecIds.XQT_FEATURED && loader is RecommendationRefreshSource) {
        RecommendationBatchLoader(loader, id)
    } else {
        loader
    }
