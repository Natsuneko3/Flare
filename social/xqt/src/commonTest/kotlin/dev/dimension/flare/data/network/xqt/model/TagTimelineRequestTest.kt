package dev.dimension.flare.data.network.xqt.model

import de.jensklingenberg.ktorfit.converter.ResponseConverterFactory
import de.jensklingenberg.ktorfit.ktorfit
import dev.dimension.flare.common.JSON
import dev.dimension.flare.common.encodeJson
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.xqt.TagTimelineRemoteMediator
import dev.dimension.flare.data.network.nullableFallbackJson
import dev.dimension.flare.data.network.xqt.XQTTimelineQueryIds
import dev.dimension.flare.data.network.xqt.XQT_JSON
import dev.dimension.flare.data.network.xqt.api.createPinnableTimelineApi
import dev.dimension.flare.model.MicroBlogKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodedPath
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TagTimelineRequestTest {
    @Test
    fun requestMatchesCapturedHomeTimelineShape() {
        assertEquals(
            "9GXlcvTyuEyONW2ua63Zfw",
            XQTTimelineQueryIds.PINNED_TIMELINES_MANAGEMENT_SHEET,
        )
        assertEquals("wp06oo3fRGU4P1sK8rECqQ", XQTTimelineQueryIds.TAG_TIMELINE)

        val request =
            TagTimelineRequest(
                variables =
                    TagTimelineVariables(
                        count = 20,
                        tag = "1925949503837798401",
                    ),
            )
        val root = JSON.parseToJsonElement(request.encodeJson()).jsonObject
        val variables = root.getValue("variables").jsonObject
        val features = root.getValue("features").jsonObject

        assertEquals(XQTTimelineQueryIds.TAG_TIMELINE, root.getValue("queryId").jsonPrimitive.content)
        assertEquals(
            setOf(
                "count",
                "includePromotedContent",
                "requestContext",
                "tag",
                "withCommunity",
                "seenTweetIds",
            ),
            variables.keys,
        )
        assertEquals("1925949503837798401", variables.getValue("tag").jsonPrimitive.content)
        assertEquals(38, features.size)
        assertTrue(features.getValue("responsive_web_profile_redirect_enabled").jsonPrimitive.boolean)
        assertFalse(features.getValue("post_ctas_fetch_enabled").jsonPrimitive.boolean)
    }

    @Test
    fun cursorIsOnlyAddedForPagination() {
        val variables =
            TagTimelineVariables(
                count = 20,
                cursor = "next-page",
                tag = "tag-id",
            )

        assertEquals(
            """{"count":20,"cursor":"next-page","includePromotedContent":true,"requestContext":"launch","tag":"tag-id","withCommunity":true,"seenTweetIds":[]}""",
            variables.encodeJson(),
        )
    }

    @Test
    fun categoryRefreshAndAppendSendTheSelectedTagAndCursor() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            withMediator(requests = requests) { mediator ->
                mediator.load(24, PagingRequest.Refresh)
                mediator.load(20, PagingRequest.Append("next-page"))
            }

            assertEquals(2, requests.size)
            requests.forEach { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("/graphql/${XQTTimelineQueryIds.TAG_TIMELINE}/HomeTimeline", request.url.encodedPath)
                assertEquals(
                    "selected-tag",
                    request
                        .variables()
                        .getValue("tag")
                        .jsonPrimitive.content,
                )
            }
            assertEquals(
                "24",
                requests[0]
                    .variables()
                    .getValue("count")
                    .jsonPrimitive.content,
            )
            assertFalse("cursor" in requests[0].variables())
            assertEquals(
                "next-page",
                requests[1]
                    .variables()
                    .getValue("cursor")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun categoryPageWithoutRenderableTweetsKeepsItsContinuation() =
        runTest {
            withMediator(body = CURSOR_ONLY_CATEGORY_TIMELINE) { mediator ->
                val result = mediator.load(20, PagingRequest.Refresh)
                assertTrue(result.data.isEmpty())
                assertEquals("next-category-page", result.nextKey)
            }
        }

    @Test
    fun validEmptyCategoryTimelineEndsPaginationAndPrependDoesNotRequestTheServer() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            withMediator(requests = requests) { mediator ->
                val refresh = mediator.load(20, PagingRequest.Refresh)
                assertTrue(refresh.data.isEmpty())
                assertNull(refresh.nextKey)
                val prepend = mediator.load(20, PagingRequest.Prepend("previous"))
                assertTrue(prepend.data.isEmpty())
                assertNull(prepend.nextKey)
            }
            assertEquals(1, requests.size)
        }

    @Test
    fun categoryHttpFailureDoesNotBecomeAnEmptyTimeline() =
        runTest {
            withMediator(status = HttpStatusCode.TooManyRequests) { mediator ->
                assertFailsWith<IllegalStateException> { mediator.load(20, PagingRequest.Refresh) }
            }
        }

    @Test
    fun categoryGraphqlErrorsRejectAnOtherwiseValidTimeline() =
        runTest {
            withMediator(body = EMPTY_CATEGORY_TIMELINE.dropLast(1) + ",\"errors\":[{\"message\":\"Try again\"}]}") { mediator ->
                assertFailsWith<IllegalStateException> { mediator.load(20, PagingRequest.Refresh) }
            }
        }

    @Test
    fun missingCategoryTimelineBodyIsAnError() =
        runTest {
            for (body in listOf("{}", "null")) {
                withMediator(body = body) { mediator ->
                    assertFailsWith<Exception>(body) { mediator.load(20, PagingRequest.Refresh) }
                }
            }
        }

    private suspend fun HttpRequestData.variables(): JsonObject =
        JSON
            .parseToJsonElement(body.toByteArray().decodeToString())
            .jsonObject
            .getValue("variables")
            .jsonObject

    private suspend fun withMediator(
        body: String = EMPTY_CATEGORY_TIMELINE,
        status: HttpStatusCode = HttpStatusCode.OK,
        requests: MutableList<HttpRequestData> = mutableListOf(),
        block: suspend (TagTimelineRemoteMediator) -> Unit,
    ) {
        val client =
            HttpClient(
                MockEngine { request ->
                    requests += request
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
            }.createPinnableTimelineApi()
        try {
            block(TagTimelineRemoteMediator("selected-tag", api, MicroBlogKey("account", "x.com")))
        } finally {
            client.close()
        }
    }
}

private const val EMPTY_CATEGORY_TIMELINE = "{\"data\":{\"home\":{\"home_timeline_urt\":{\"instructions\":[]}}}}"
private val CURSOR_ONLY_CATEGORY_TIMELINE =
    """
    {"data":{"home":{"home_timeline_urt":{"instructions":[
      {"type":"TimelineAddEntries","entries":[
        {"entryId":"cursor-bottom","sortIndex":"1","content":{
          "entryType":"TimelineTimelineCursor","cursorType":"Bottom","value":"next-category-page"
        }}
      ]}
    ]}}}}
    """.trimIndent()
