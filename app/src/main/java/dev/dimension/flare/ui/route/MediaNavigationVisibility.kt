package dev.dimension.flare.ui.route

internal fun List<Route>.hasVisibleMediaViewer(): Boolean =
    // Post-action sheets keep the viewer underneath them. Stop at the current screen so
    // a viewer retained in another top-level tab does not hide that tab's navigation.
    when (lastOrNull { !it.isMediaViewerOverlay }) {
        is Route.Media.Image, is Route.Media.RawMedia, is Route.Media.StatusMedia -> true
        else -> false
    }

private val Route.isMediaViewerOverlay: Boolean
    get() =
        when (this) {
            is Route.Status.AltText,
            is Route.Status.AddReaction,
            is Route.Status.BlueskyReport,
            is Route.Status.DeleteConfirm,
            is Route.Status.MastodonReport,
            is Route.Status.MisskeyReport,
            is Route.Status.ShareSheet,
            is Route.Status.Insight,
            -> true

            else -> false
        }
