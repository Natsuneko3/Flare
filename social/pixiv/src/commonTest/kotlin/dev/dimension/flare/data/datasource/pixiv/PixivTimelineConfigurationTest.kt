package dev.dimension.flare.data.datasource.pixiv

import dev.dimension.flare.data.datasource.microblog.ProfileTab
import dev.dimension.flare.data.network.pixiv.PixivRankingMode
import dev.dimension.flare.data.network.pixiv.PixivRestrict
import dev.dimension.flare.data.network.pixiv.PixivService
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiStrings
import dev.dimension.flare.ui.model.asText
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PixivTimelineConfigurationTest {
    @Test
    fun additionalRankingsAreOnlyInTheTabPicker() {
        val account = MicroBlogKey("test", "pixiv.net")
        val source = PixivDataSource(account, emptyFlow()) {}
        val builtInIds = source.builtInTimelineTabs.map { it.target.spec.id }
        val newIds =
            listOf(
                "pixiv.ranking.day_ai",
                "pixiv.ranking.day_r18",
                "pixiv.ranking.week_r18",
                "pixiv.ranking.day_r18_ai",
            )

        assertTrue(builtInIds.none { it in newIds })
        assertEquals(builtInIds + newIds, source.addableTimelineTabs.map { it.target.spec.id })
    }

    @Test
    fun followingFeedsCanJoinButRecommendationsCannot() {
        val service = PixivService()
        val account = MicroBlogKey("test", "pixiv.net")
        assertTrue(PixivHomeTimelineLoader(service, account).canJoinTimeline)
        for (restrict in PixivRestrict.entries) {
            assertTrue(PixivFollowingTimelineLoader(service, account, restrict).canJoinTimeline)
        }
        assertFalse(PixivDiscoverTimelineLoader(service, account).canJoinTimeline)
        for (mode in PixivRankingMode.entries) {
            assertFalse(PixivRankingTimelineLoader(service, account, mode).canJoinTimeline)
        }
    }

    @Test
    fun profileBookmarksUseAGalleryTabScopedToAccountAndAuthor() =
        runTest {
            val account = MicroBlogKey("100", "pixiv.net")
            val author = MicroBlogKey("200", "pixiv.net")
            val source = PixivDataSource(account, emptyFlow()) {}
            val tabs = source.profileTabs(author)

            assertEquals(
                listOf(UiStrings.Illustrations, UiStrings.Manga, UiStrings.Favourite).map { it.asText() },
                tabs.map { it.name },
            )
            val bookmarks = tabs.last()
            assertEquals(ProfileTab.DisplayType.Gallery, bookmarks.displayType)
            assertFalse(bookmarks.showAllImagesInGallery)
            assertNotEquals(bookmarks.id, source.profileTabs(MicroBlogKey("300", "pixiv.net")).last().id)

            val anotherAccount = PixivDataSource(MicroBlogKey("400", "pixiv.net"), emptyFlow()) {}
            assertNotEquals(bookmarks.id, anotherAccount.profileTabs(author).last().id)
        }
}
