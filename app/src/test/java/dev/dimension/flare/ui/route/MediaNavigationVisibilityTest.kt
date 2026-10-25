package dev.dimension.flare.ui.route

import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.component.TopLevelBackStack
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaNavigationVisibilityTest {
    @Test
    fun everyFullscreenMediaEntryHidesNavigation() {
        val viewers =
            listOf(
                Route.Media.Image(uri = "https://example.com/image.jpg", previewUrl = null),
                Route.Media.RawMedia(medias = persistentListOf()),
                Route.Media.StatusMedia(
                    statusKey = MicroBlogKey("post", "example.com"),
                    accountType = AccountType.Guest,
                    index = 0,
                    preview = null,
                ),
            )

        viewers.forEach { viewer ->
            assertTrue(viewer.toString(), listOf(Route.Home, viewer).hasVisibleMediaViewer())
        }
    }

    @Test
    fun altTextKeepsNavigationHiddenUntilTheViewerIsDismissed() {
        val stack = TopLevelBackStack<Route>(Route.Home)
        stack.add(image)
        stack.add(Route.Status.AltText("Image description"))

        assertTrue(stack.backStack.hasVisibleMediaViewer())
        stack.removeLast()
        assertTrue(stack.backStack.hasVisibleMediaViewer())
        stack.removeLast()
        assertFalse(stack.backStack.hasVisibleMediaViewer())
    }

    @Test
    fun postActionsAboveViewerKeepNavigationHidden() {
        val statusKey = MicroBlogKey("post", "example.com")
        val overlays =
            listOf(
                Route.Status.AddReaction(statusKey, AccountType.Guest),
                Route.Status.BlueskyReport(statusKey, AccountType.Guest),
                Route.Status.DeleteConfirm(statusKey, AccountType.Guest),
                Route.Status.MastodonReport(MicroBlogKey("user", "example.com"), statusKey, AccountType.Guest),
                Route.Status.MisskeyReport(MicroBlogKey("user", "example.com"), statusKey, AccountType.Guest),
                Route.Status.ShareSheet(statusKey, AccountType.Guest, "https://example.com/post"),
                Route.Status.Insight(AccountType.Guest, statusKey),
            )

        overlays.forEach { overlay ->
            assertTrue(overlay.toString(), listOf(Route.Home, image, overlay).hasVisibleMediaViewer())
            assertFalse(overlay.toString(), listOf(Route.Home, overlay).hasVisibleMediaViewer())
        }
        assertTrue(
            (listOf(Route.Home, image) + overlays).hasVisibleMediaViewer(),
        )
    }

    @Test
    fun retainedMediaInAnotherTabDoesNotHideNavigation() {
        val stack = TopLevelBackStack<Route>(Route.Home)
        stack.add(image)
        stack.addTopLevel(Route.Discover)

        assertFalse(stack.backStack.hasVisibleMediaViewer())
        stack.addTopLevel(Route.Home)
        assertTrue(stack.backStack.hasVisibleMediaViewer())
    }

    @Test
    fun navigatingFromViewerToAnotherScreenRestoresNavigation() {
        val stack = TopLevelBackStack<Route>(Route.Home)
        stack.add(image)
        stack.add(Route.Profile.User(AccountType.Guest, MicroBlogKey("user", "example.com")))

        assertFalse(stack.backStack.hasVisibleMediaViewer())
        stack.removeLast()
        assertTrue(stack.backStack.hasVisibleMediaViewer())
    }

    @Test
    fun podcastAndOrdinaryScreensKeepNavigationVisible() {
        assertFalse(emptyList<Route>().hasVisibleMediaViewer())
        assertFalse(listOf(Route.Home).hasVisibleMediaViewer())
        assertFalse(listOf(Route.Home, Route.Status.AltText("Description")).hasVisibleMediaViewer())
        assertFalse(
            listOf(Route.Home, Route.Media.Podcast(AccountType.Guest, "podcast")).hasVisibleMediaViewer(),
        )
    }

    private val image = Route.Media.Image(uri = "https://example.com/image.jpg", previewUrl = null)
}
