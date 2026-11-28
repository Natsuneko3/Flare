package dev.dimension.flare.ui.model

import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.ui.render.toUi
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class InstagramFeedPhotosTest {
    private val first = "https://images.example/first.jpg"
    private val cover =
        UiMedia.Image(
            url = first,
            previewUrl = first,
            description = null,
            width = 0f,
            height = 0f,
            sensitive = false,
            customHeaders = persistentMapOf("Referer" to "https://www.instagram.com/"),
            proxy = ProxyRef.Global,
        )
    private val feed =
        UiTimelineV2.Feed(
            url = "https://www.instagram.com/p/abc/",
            title = null,
            description = null,
            descriptionHtml =
                """<img src="$first" width="900" height="1200"><img src="$first"><img src="//images.example/two.jpg" alt="two">""",
            media = cover,
            source = UiTimelineV2.Feed.Source("Test", null),
            createdAt = Instant.fromEpochMilliseconds(0).toUi(),
            accountType = AccountType.Guest,
        )

    @Test
    fun cachedPostPreservesAlbumOrderDimensionsAndRequestContext() {
        val photos = feed.instagramPhotos()
        assertEquals(listOf(first, "https://images.example/two.jpg"), photos.map { it.url })
        assertEquals(0.75f, photos.first().aspectRatio)
        assertEquals("two", photos.last().description)
        assertEquals(cover.customHeaders, photos.last().customHeaders)
        assertEquals(cover.proxy, photos.last().proxy)
        assertEquals(listOf(cover), feed.copy(descriptionHtml = null).instagramPhotos())
    }

    @Test
    fun otherArticlesAndPluginFeedsKeepTheirOwnPresentation() {
        for (link in listOf("https://news.example/p/abc", "https://instagram.com.example/p/abc", "https://instagram.com/about/")) {
            assertTrue(feed.copy(url = link).instagramPhotos().isEmpty())
        }
        assertTrue(feed.copy(pluginId = "sample").instagramPhotos().isEmpty())
    }
}
