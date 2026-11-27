package dev.dimension.flare.data.network.instagram

import de.jensklingenberg.ktorfit.ktorfit
import dev.dimension.flare.common.JSON
import dev.dimension.flare.data.network.instagram.api.createInstagramApiResources
import dev.dimension.flare.data.platform.InstagramCredential
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class InstagramResourcesTest {
    @Test
    fun sendsCookiesToTheWebOriginAndReturnsLoginRedirectWithoutFollowingIt() =
        runTest {
            val requests = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    requests += request.url.toString()
                    assertEquals("sessionid=session; ds_user_id=123; csrftoken=csrf", request.headers[HttpHeaders.Cookie])
                    respond("redirect", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/accounts/login/"))
                }
            val client =
                HttpClient(engine) {
                    followRedirects = false
                    install(InstagramHeaderPlugin) { credentials = flowOf(InstagramCredential("session", "csrf", "123")) }
                }
            try {
                val api =
                    ktorfit {
                        baseUrl("https://www.instagram.com/api/v1/")
                        httpClient(client)
                    }.createInstagramApiResources()
                val response = api.user("123")
                assertEquals(HttpStatusCode.Found, response.status)
                assertEquals("redirect", response.bodyAsText())
                assertEquals(HttpStatusCode.Found, api.currentUser().status)
                assertEquals(
                    listOf("https://www.instagram.com/api/v1/users/123/info/", "https://www.instagram.com/api/v1/accounts/current_user/?edit=true"),
                    requests,
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun homeUsesTheReturnedCursorAndStopsAtTheLastPage() =
        runTest {
            val cursors = mutableListOf<String?>()
            val client = HttpClient(MockEngine { request ->
                assertEquals("/api/v1/feed/timeline/", request.url.encodedPath)
                val cursor = request.url.parameters["max_id"]
                cursors += cursor
                val body = when (cursor) {
                    null -> """{"feed_items":[{"suggested_users":{}},{"media_or_ad":{"pk":"1"}}],"more_available":true,"next_max_id":"a+/="}"""
                    "a+/=" -> """{"feed_items":[{"media_or_ad":{"ad_id":"ad"}},{"media_or_ad":{"pk":"2"}}],"more_available":false,"next_max_id":"old"}"""
                    else -> error("Unexpected cursor")
                }
                respond(body)
            })
            try {
                val api = ktorfit {
                    baseUrl("https://www.instagram.com/api/v1/")
                    httpClient(client)
                }.createInstagramApiResources()
                val first = homePage(JSON.parseToJsonElement(api.home(null).bodyAsText()).jsonObject)
                val last = homePage(JSON.parseToJsonElement(api.home(first.nextCursor).bodyAsText()).jsonObject)
                assertEquals(listOf(null, "a+/="), cursors)
                assertEquals(listOf("1", "2"), (first.items + last.items).map { it.string("pk") })
                assertNull(last.nextCursor)
                assertFailsWith<InstagramRequestException> { homePage(JSON.parseToJsonElement("{}").jsonObject) }
            } finally {
                client.close()
            }
        }
}
