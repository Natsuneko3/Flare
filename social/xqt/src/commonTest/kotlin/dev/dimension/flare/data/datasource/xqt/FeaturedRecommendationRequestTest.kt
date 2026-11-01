package dev.dimension.flare.data.datasource.xqt

import de.jensklingenberg.ktorfit.converter.ResponseConverterFactory
import de.jensklingenberg.ktorfit.ktorfit
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.network.nullableFallbackJson
import dev.dimension.flare.data.network.xqt.XQT_JSON
import dev.dimension.flare.data.network.xqt.api.createTweetApi
import dev.dimension.flare.model.MicroBlogKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodedPath
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeaturedRecommendationRequestTest {
    @Test
    fun manualRefreshSendsSeenIdsAndStartsWithoutAnOldCursor() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            withMediator(requests = requests) { mediator ->
                mediator.loadRecommendations(24, null, listOf("100", "200"))
            }
            val request = requests.single()
            assertTrue(request.url.encodedPath.endsWith("/HomeTimeline"))
            val variables = request.variables()
            assertEquals(24L, variables.getValue("count").jsonPrimitive.long)
            assertEquals(listOf("100", "200"), variables.getValue("seenTweetIds").jsonArray.map { it.jsonPrimitive.content })
            assertEquals("launch", variables.getValue("requestContext").jsonPrimitive.content)
            assertFalse("cursor" in variables)
        }

    @Test
    fun manualRequestsLimitSeenIdsToTheMostRecentHundred() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            withMediator(requests = requests) { mediator ->
                mediator.loadRecommendations(20, null, (1..150).map(Int::toString))
            }
            assertEquals(
                (51..150).map(Int::toString),
                requests
                    .single()
                    .variables()
                    .getValue("seenTweetIds")
                    .jsonArray
                    .map { it.jsonPrimitive.content },
            )
        }

    @Test
    fun continuationSendsTheNewBatchCursorAndSameSeenSnapshot() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            withMediator(requests = requests) { mediator ->
                mediator.loadRecommendations(20, "new-batch-next", listOf("100"))
            }
            val variables = requests.single().variables()
            assertEquals("new-batch-next", variables.getValue("cursor").jsonPrimitive.content)
            assertEquals(listOf("100"), variables.getValue("seenTweetIds").jsonArray.map { it.jsonPrimitive.content })
        }

    @Test
    fun ordinaryFeaturedRefreshAndAppendDoNotUseManualSeenRecords() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            withMediator(requests = requests) { mediator ->
                mediator.loadRecommendations(20, null, listOf("100"))
                mediator.load(20, PagingRequest.Refresh)
                mediator.load(20, PagingRequest.Append("ordinary-next"))
            }
            assertEquals(
                emptyList(),
                requests[1]
                    .variables()
                    .getValue("seenTweetIds")
                    .jsonArray
                    .toList(),
            )
            assertFalse("cursor" in requests[1].variables())
            assertEquals(
                emptyList(),
                requests[2]
                    .variables()
                    .getValue("seenTweetIds")
                    .jsonArray
                    .toList(),
            )
            assertEquals(
                "ordinary-next",
                requests[2]
                    .variables()
                    .getValue("cursor")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun anEmptyRenderedPageKeepsItsServerCursorForBoundedRefill() =
        runTest {
            withMediator(body = CURSOR_ONLY_TIMELINE) { mediator ->
                val result = mediator.loadRecommendations(20, null, emptyList())
                assertTrue(result.data.isEmpty())
                assertEquals("next-recommendations", result.nextKey)
            }
        }

    @Test
    fun validEmptyTimelineCanReportNoNewRecommendations() =
        runTest {
            withMediator { mediator ->
                val result = mediator.loadRecommendations(20, null, emptyList())
                assertTrue(result.data.isEmpty())
                assertNull(result.nextKey)
            }
        }

    @Test
    fun httpFailureIsNotTreatedAsAnEmptyRecommendationBatch() =
        runTest {
            withMediator(status = HttpStatusCode.TooManyRequests) { mediator ->
                assertFailsWith<IllegalStateException> {
                    mediator.loadRecommendations(20, null, emptyList())
                }
            }
        }

    @Test
    fun missingTimelineBodyIsNotTreatedAsAnEmptyRecommendationBatch() =
        runTest {
            withMediator(body = "{}") { mediator ->
                assertFailsWith<Exception> {
                    mediator.loadRecommendations(20, null, emptyList())
                }
            }
        }

    @Test
    fun graphqlFailureIsNotTreatedAsAnEmptyRecommendationBatch() =
        runTest {
            withMediator(body = EMPTY_TIMELINE.dropLast(1) + ",\"errors\":[{\"message\":\"Try again\"}]}") { mediator ->
                assertFailsWith<IllegalStateException> {
                    mediator.loadRecommendations(20, null, emptyList())
                }
            }
        }

    @Test
    fun cancellationPropagatesFromTheNetworkRequest() =
        runTest {
            withMediator(failure = CancellationException("cancelled")) { mediator ->
                assertFailsWith<CancellationException> {
                    mediator.loadRecommendations(20, null, emptyList())
                }
            }
        }

    private fun HttpRequestData.variables(): JsonObject = Json.parseToJsonElement(checkNotNull(url.parameters["variables"])).jsonObject

    private suspend fun withMediator(
        body: String = EMPTY_TIMELINE,
        status: HttpStatusCode = HttpStatusCode.OK,
        requests: MutableList<HttpRequestData> = mutableListOf(),
        failure: Exception? = null,
        block: suspend (FeaturedTimelineRemoteMediator) -> Unit,
    ) {
        val client =
            HttpClient(
                MockEngine { request ->
                    requests += request
                    failure?.let { throw it }
                    respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                },
            ) {
                install(ContentNegotiation) { nullableFallbackJson(XQT_JSON) }
            }
        val api =
            ktorfit {
                baseUrl("https://example.com/")
                httpClient(client)
                converterFactories(ResponseConverterFactory())
            }.createTweetApi()
        try {
            block(FeaturedTimelineRemoteMediator(api, MicroBlogKey("account", "x.com")))
        } finally {
            client.close()
        }
    }
}

private const val EMPTY_TIMELINE = "{\"data\":{\"home\":{\"home_timeline_urt\":{\"instructions\":[]}}}}"
private val CURSOR_ONLY_TIMELINE =
    """
    {"data":{"home":{"home_timeline_urt":{"instructions":[
      {"type":"TimelineAddEntries","entries":[
        {"entryId":"cursor-bottom","sortIndex":"1","content":{
          "entryType":"TimelineTimelineCursor","cursorType":"Bottom","value":"next-recommendations"
        }}
      ]}
    ]}}}}
    """.trimIndent()
