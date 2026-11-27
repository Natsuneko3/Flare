package dev.dimension.flare.data.network.instagram

import de.jensklingenberg.ktorfit.ktorfit
import dev.dimension.flare.data.network.instagram.api.createInstagramApiResources
import dev.dimension.flare.data.network.instagram.api.createInstagramWebResources
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
import kotlin.test.Test
import kotlin.test.assertEquals

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
                val web =
                    ktorfit {
                        baseUrl("https://www.instagram.com/")
                        httpClient(client)
                    }.createInstagramWebResources()
                val response = api.user("123")
                assertEquals(HttpStatusCode.Found, response.status)
                assertEquals("redirect", response.bodyAsText())
                assertEquals(HttpStatusCode.Found, web.bootstrap().status)
                assertEquals(
                    listOf("https://www.instagram.com/api/v1/users/123/info/", "https://www.instagram.com/?variant=following"),
                    requests,
                )
            } finally {
                client.close()
            }
        }
}
