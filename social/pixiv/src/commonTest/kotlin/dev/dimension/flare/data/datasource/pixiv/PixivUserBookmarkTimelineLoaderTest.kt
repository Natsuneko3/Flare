package dev.dimension.flare.data.datasource.pixiv

import de.jensklingenberg.ktorfit.converter.ResponseConverterFactory
import de.jensklingenberg.ktorfit.ktorfit
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.network.nullableFallbackJson
import dev.dimension.flare.data.network.pixiv.PixivService
import dev.dimension.flare.data.network.pixiv.api.createPixivAppResources
import dev.dimension.flare.data.network.pixiv.api.createPixivAuthResources
import dev.dimension.flare.model.MicroBlogKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodedPath
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PixivUserBookmarkTimelineLoaderTest {
    @Test
    fun publicBookmarksFollowTheCursorAndRejectFailedLoads() =
        runTest {
            val nextUrl = "https://example.com/v1/user/bookmarks/illust?user_id=200&restrict=public&max_bookmark_id=30"
            var status = HttpStatusCode.OK
            val engine =
                MockEngine { request ->
                    assertEquals("/v1/user/bookmarks/illust", request.url.encodedPath)
                    assertEquals("200", request.url.parameters["user_id"])
                    assertEquals("public", request.url.parameters["restrict"])
                    val body =
                        when {
                            status != HttpStatusCode.OK -> """{"error":{"message":"Too many requests"}}"""
                            request.url.parameters["max_bookmark_id"] == "30" -> """{"illusts":[],"next_url":null}"""
                            else -> """{"illusts":[],"next_url":"$nextUrl"}"""
                        }
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            HttpClient(engine) { install(ContentNegotiation) { nullableFallbackJson() } }.use { client ->
                val api =
                    ktorfit {
                        baseUrl("https://example.com/")
                        httpClient(client)
                        converterFactories(ResponseConverterFactory())
                    }
                val loader =
                    PixivUserBookmarkTimelineLoader(
                        service = PixivService(api.createPixivAuthResources(), api.createPixivAppResources(), client),
                        accountKey = MicroBlogKey("100", "pixiv.net"),
                        userKey = MicroBlogKey("200", "pixiv.net"),
                    )
                val cursor = assertNotNull(loader.load(30, PagingRequest.Refresh).nextKey)
                assertEquals(nextUrl, cursor)
                val append = PagingRequest.Append(cursor)
                assertNull(loader.load(30, append).nextKey)

                status = HttpStatusCode.TooManyRequests
                assertFailsWith<IllegalStateException> { loader.load(30, PagingRequest.Refresh) }
                assertFailsWith<IllegalStateException> { loader.load(30, append) }
            }
        }
}
