package dev.dimension.flare.data.network.pixiv

import dev.dimension.flare.data.platform.PixivPlatformSpec
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PixivShortLinkTest {
    private val shortUrl = "https://pixiv.me/shapoco"
    private val profileUrl = "https://www.pixiv.net/users/24431887"

    @Test
    fun pixivRegistersItsShortLinkHost() {
        assertEquals("pixiv.me", PixivPlatformSpec.shortLink.host)
    }

    @Test
    fun resolvesLoginIdThroughOneUnauthenticatedHttpsRequest() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertEquals("https://pixiv.me/12345", request.url.toString())
                    assertEquals(HttpMethod.Head, request.method)
                    assertNull(request.headers[HttpHeaders.Authorization])
                    assertNull(request.headers[HttpHeaders.Cookie])
                    respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, profileUrl))
                }
            engine.withPixivClient { client ->
                assertEquals(
                    profileUrl,
                    resolvePixivShortLink("http://Pixiv.Me/12345/?utm_source=share#profile", client),
                )
                assertEquals(1, engine.requestHistory.size)
            }
        }

    @Test
    fun rejectsResponsesThatDoNotRedirectToAnOfficialNumericProfile() =
        runTest {
            listOf(
                HttpStatusCode.NotFound to null,
                HttpStatusCode.OK to profileUrl,
                HttpStatusCode.MovedPermanently to null,
                HttpStatusCode.Found to "https://www.pixiv.net/search_user.php?nick=shapoco",
                HttpStatusCode.Found to "https://www.pixiv.net.example.org/users/24431887",
                HttpStatusCode.Found to "https://www.pixiv.net/users/shapoco",
                HttpStatusCode.Found to "http://www.pixiv.net/users/24431887",
            ).forEach { (status, location) ->
                val engine =
                    MockEngine { request ->
                        assertEquals(shortUrl, request.url.toString())
                        respond("", status, location?.let { headersOf(HttpHeaders.Location, it) } ?: Headers.Empty)
                    }
                engine.withPixivClient { client ->
                    assertNull(resolvePixivShortLink("$shortUrl?ref=share", client), "$status, Location: $location")
                    assertEquals(1, engine.requestHistory.size)
                }
            }
        }

    @Test
    fun returnsNoProfileWhenTheRequestTimesOut() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    started.complete(Unit)
                    awaitCancellation()
                }
            engine.withPixivClient { client ->
                assertNull(resolvePixivShortLink(shortUrl, client))
                assertTrue(started.isCompleted)
            }
        }

    @Test
    fun unsupportedShortLinkUrlsDoNotRequestPixiv() =
        runTest {
            val engine = MockEngine { fail("Unexpected request to ${it.url}") }
            engine.withPixivClient { client ->
                listOf(
                    "https://pixiv.me.example.org/shapoco",
                    "https://p\u0131xiv.me/shapoco",
                    "https://pixiv.me/",
                    "https://pixiv.me/shapoco/works",
                    "https://pixiv.me/shapoco%2Fworks",
                    "ftp://pixiv.me/shapoco",
                ).forEach { url ->
                    assertNull(resolvePixivShortLink(url, client), url)
                }
            }
        }

    private inline fun MockEngine.withPixivClient(block: (HttpClient) -> Unit) {
        try {
            createPixivShortLinkClient(this).use(block)
        } finally {
            close()
        }
    }
}
