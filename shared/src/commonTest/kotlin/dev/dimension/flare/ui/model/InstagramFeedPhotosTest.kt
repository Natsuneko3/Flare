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
        val photos = feed.instagramMedia()
        assertEquals(listOf(first, "https://images.example/two.jpg"), photos.map { it.url })
        assertEquals(0.75f, (photos.first() as UiMedia.Image).aspectRatio)
        assertEquals("two", photos.last().description)
        assertEquals(cover.customHeaders, photos.last().customHeaders)
        assertEquals(cover.proxy, photos.last().proxy)
        assertEquals(cover, feed.copy(descriptionHtml = null).instagramMedia().single())
    }

    @Test
    fun videoSourcesAppearInAlbumOrderAndVideoOnlyPostsNeedNoCover() {
        val clip = "https://example.org/clip.mp4"
        val video = """<video poster="$first" width="720"><source src="$clip" type="video/mp4"><img src="$first"></video>"""
        val mixed = feed.copy(descriptionHtml = """<img src="$first">$video<img src="/last.jpg">""").instagramMedia()
        assertEquals(listOf(first, clip, "https://www.instagram.com/last.jpg"), mixed.map { it.url })
        val parsed = mixed[1] as UiMedia.Video
        assertEquals(first, parsed.thumbnailUrl)
        assertEquals(1f, parsed.aspectRatio)
        assertEquals(cover.customHeaders, parsed.customHeaders)
        assertEquals(cover.proxy, parsed.proxy)
        val direct = feed.copy(descriptionHtml = """<video src="$clip" width="1080" height="1920"></video>""").instagramMedia()
        assertEquals(1080f / 1920f, (direct.single() as UiMedia.Video).aspectRatio)
        val videoOnly = feed.copy(media = null, descriptionHtml = video).instagramMedia()
        assertEquals(listOf(clip), videoOnly.map { it.url })
    }

    @Test
    fun otherArticlesAndPluginFeedsKeepTheirOwnPresentation() {
        for (link in listOf("https://news.example/p/abc", "https://instagram.com.example/p/abc", "https://instagram.com/about/")) {
            assertTrue(feed.copy(url = link).instagramMedia().isEmpty())
        }
        assertTrue(feed.copy(pluginId = "sample").instagramMedia().isEmpty())
    }
}
