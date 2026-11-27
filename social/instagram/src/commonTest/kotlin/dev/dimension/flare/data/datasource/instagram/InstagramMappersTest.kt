package dev.dimension.flare.data.datasource.instagram

import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.network.instagram.InstagramPage
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.ReferenceType
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.UiProfile
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InstagramMappersTest {
    private val account = MicroBlogKey("900", "instagram.com")
    private val user = """{"pk":12,"username":"sample","full_name":"","is_verified":true,"is_private":true,"follower_count":13}"""
    private val small = """{"url":"https://a.cdninstagram.com/s","width":100,"height":300}"""
    private val large = """{"url":"https://a.cdninstagram.com/l","width":600,"height":300}"""
    private val images = """{"candidates":[$small,$large]}"""
    private val movie = """{"url":"https://a.fbcdn.net/movie.mp4","height":200,"width":700}"""
    private val video = """{"media_type":2,"image_versions2":$images,"video_versions":[$movie],"video_duration":2.5}"""
    private val image = """{"media_type":1,"image_versions2":$images,"accessibility_caption":"photo"}"""

    private fun post(body: String): kotlinx.serialization.json.JsonObject {
        val fields = body.removePrefix("{").removeSuffix("}")
        return Json.parseToJsonElement("""{"pk":9223372036854775880,"code":"abc","taken_at":1700000000,"user":$user,$fields}""").jsonObject
    }

    @Test
    fun imageAndPrivateAuthorKeepIdentityResolutionAndAccountProxy() {
        val item = assertNotNull(post(image).toInstagramPostOrNull(account))
        assertEquals("9223372036854775880", item.statusKey.id)
        assertEquals("", item.content.original.raw)
        val author = assertNotNull(item.user)
        assertEquals("sample", author.handle.raw)
        assertEquals(13L, author.matrices.fansCount)
        assertEquals(setOf(UiProfile.Mark.Verified, UiProfile.Mark.Locked), author.mark.toSet())
        val photo = assertIs<UiMedia.Image>(item.images.single())
        assertEquals("https://a.cdninstagram.com/l", photo.url)
        assertEquals("https://a.cdninstagram.com/s", photo.previewUrl)
        assertEquals("photo", photo.description)
        assertEquals(ProxyRef.forAccount(account), photo.proxy)
    }

    @Test
    fun reelAndMixedCarouselPreserveCoverDurationAndOrdering() {
        val standalone = assertNotNull(post(video).toInstagramPostOrNull(account))
        val movie = assertIs<UiMedia.Video>(standalone.images.single())
        assertEquals(2500L, movie.durationMillis)
        assertEquals("https://a.cdninstagram.com/l", movie.thumbnailUrl)
        val carousel = assertNotNull(post("""{"media_type":8,"carousel_media":[$video,$image]}""").toInstagramPostOrNull(account))
        assertEquals(movie, carousel.images[0])
        assertIs<UiMedia.Image>(carousel.images[1])
    }

    @Test
    fun adAndSuggestedUnitsNeverBecomePosts() {
        for (marker in listOf("ad_id", "injected", "suggested_users", "clips_netego")) {
            val raw = post("""{"media_type":1,"$marker":true}""")
            assertNull(raw.toInstagramPostOrNull(account))
        }
    }

    @Test
    fun emptyAndRepeatedCursorsStopAndPrependNeverFetches() =
        runTest {
            val fetched = mutableListOf<String?>()
            val loader =
                InstagramTimelineLoader(
                    pagingKey = "fixture",
                    fetch = { cursor ->
                        fetched += cursor
                        InstagramPage(listOf(post(image)), if (cursor == "A") "B" else "A")
                    },
                    map = { it.toInstagramPostOrNull(account) },
                )
            val first = loader.load(10, PagingRequest.Refresh)
            val second = loader.load(10, PagingRequest.Append(assertNotNull(first.nextKey)))
            assertNull(loader.load(10, PagingRequest.Append(assertNotNull(second.nextKey))).nextKey)
            loader.load(10, PagingRequest.Prepend("unused"))
            assertEquals(listOf(null, "A", "B"), fetched)
            val empty = InstagramTimelineLoader("empty", fetch = { InstagramPage(emptyList(), "C") }, map = { null })
            assertNull(empty.load(10, PagingRequest.Refresh).nextKey)
        }

    @Test
    fun detailLoadsParentOnceAndPaginatesComments() =
        runTest {
            val comment = Json.parseToJsonElement("""{"pk":11,"user":$user,"text":"reply","created_at":1700000001}""").jsonObject
            val loads = mutableListOf<String>()
            val key = MicroBlogKey("9223372036854775880", "instagram.com")
            val loader =
                InstagramContextLoader(
                    account,
                    key,
                    fetchPost = { id ->
                        loads += "post:$id"
                        post(image)
                    },
                    fetchComments = { id, _ ->
                        loads += "comments:$id"
                        InstagramPage(listOf(comment), null)
                    },
                )
            val page = loader.load(10, PagingRequest.Refresh)
            assertEquals(key, page.data.single().statusKey)
            val replies = loader.load(10, PagingRequest.Append(assertNotNull(page.nextKey)))
            val reply = assertIs<dev.dimension.flare.ui.model.UiTimelineV2.Post>(replies.data.single())
            assertEquals(key, reply.references.single().statusKey)
            assertEquals(ReferenceType.Reply, reply.references.single().type)
            assertNull(replies.nextKey)
            assertEquals(listOf("post:${key.id}", "comments:${key.id}"), loads)
        }
}
