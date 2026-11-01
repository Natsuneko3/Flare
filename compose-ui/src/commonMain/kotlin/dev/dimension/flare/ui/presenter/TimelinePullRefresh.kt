package dev.dimension.flare.ui.presenter

import dev.dimension.flare.ui.presenter.home.RecommendationRefreshOutcome

/** This route is only for a pull gesture; timer and tab callbacks keep their existing refresh path. */
public suspend fun TimelineItemPresenter.State.refreshTimelineFromPull(
    onRecommendationsUpdated: suspend () -> Unit,
    onRecommendationOutcome: (RecommendationRefreshOutcome) -> Unit,
) {
    if (!supportsRecommendationRefresh) {
        refreshSync()
        return
    }
    if (isRefreshing) return
    when (val outcome = refreshForPull()) {
        RecommendationRefreshOutcome.Updated -> onRecommendationsUpdated()
        RecommendationRefreshOutcome.NoNew, RecommendationRefreshOutcome.Failed -> onRecommendationOutcome(outcome)
        null -> Unit
    }
}
