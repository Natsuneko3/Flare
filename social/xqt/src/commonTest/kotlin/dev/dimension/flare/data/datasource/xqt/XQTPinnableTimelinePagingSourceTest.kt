package dev.dimension.flare.data.datasource.xqt

import androidx.paging.PagingSource
import androidx.paging.testing.asSnapshot
import de.jensklingenberg.ktorfit.converter.ResponseConverterFactory
import de.jensklingenberg.ktorfit.ktorfit
import dev.dimension.flare.data.network.nullableFallbackJson
import dev.dimension.flare.data.network.xqt.XQT_JSON
import dev.dimension.flare.data.network.xqt.api.PinnableTimelineApi
import dev.dimension.flare.data.network.xqt.api.createPinnableTimelineApi
import dev.dimension.flare.data.network.xqt.model.TagPinnedTimeline
import dev.dimension.flare.data.platform.XqtPlatformSpec
import dev.dimension.flare.data.platform.XqtTagTimelineData
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiIcon
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.model.asType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.JsonConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class XQTPinnableTimelinePagingSourceTest {
    private val accountKey = MicroBlogKey(id = "account", host = "x.com")

    @Test
    fun mapsTagPinnedTimelineToRegisteredTimelineSpec() =
        runTest {
            val accountKey = MicroBlogKey(id = "account", host = "x.com")
            val pagingSource =
                XQTPinnableTimelinePagingSource(accountKey) {
                    listOf(
                        TagPinnedTimeline(
                            name = "Technology",
                            tabLabel = "Tech",
                            tag = "tag-id",
                        ),
                    )
                }

            val result =
                pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = null,
                        loadSize = 20,
                        placeholdersEnabled = false,
                    ),
                )
            val candidate = assertIs<PagingSource.LoadResult.Page<Int, *>>(result).data.single()
            val timelineCandidate = assertIs<dev.dimension.flare.data.model.tab.TimelineCandidate<*>>(candidate)
            val data = assertIs<XqtTagTimelineData>(timelineCandidate.target.data)

            assertSame(XqtPlatformSpec.tagTimelineSpec, timelineCandidate.target.spec)
            assertEquals(accountKey, data.accountKey)
            assertEquals("tag-id", data.tag)
            assertEquals(UiText.Raw("Tech"), timelineCandidate.title)
            assertEquals(UiIcon.World.asType(), timelineCandidate.icon)
        }

    @Test
    fun validEmptyCatalogProducesAnEmptyPage() =
        runTest {
            withApi { api ->
                assertEquals(emptyList(), api.pinnableTagTimelines(accountKey).asSnapshot())
            }
        }

    @Test
    fun catalogFailureIsNotPresentedAsAnEmptyPicker() =
        runTest {
            withApi(status = HttpStatusCode.TooManyRequests) { api ->
                assertFailsWith<IllegalStateException> {
                    api.pinnableTagTimelines(accountKey).asSnapshot()
                }
            }
        }

    @Test
    fun graphqlErrorsRejectEvenAnOtherwiseValidCatalog() =
        runTest {
            withApi(body = EMPTY_CATALOG.dropLast(1) + ",\"errors\":[{\"message\":\"Try again\"}]}") { api ->
                assertFailsWith<IllegalStateException> {
                    api.pinnableTagTimelines(accountKey).asSnapshot()
                }
            }
        }

    @Test
    fun missingCatalogContainersAndArrayRemainErrors() =
        runTest {
            for (body in listOf("{}", "{\"data\":{}}", "{\"data\":{\"pinnable_timelines\":{}}}")) {
                withApi(body = body) { api ->
                    assertFailsWith<IllegalStateException>(body) {
                        api.pinnableTagTimelines(accountKey).asSnapshot()
                    }
                }
            }
        }

    @Test
    fun nullCatalogResponseRemainsADeserializationError() =
        runTest {
            withApi(body = "null") { api ->
                assertFailsWith<JsonConvertException> {
                    api.pinnableTagTimelines(accountKey).asSnapshot()
                }
            }
        }

    @Test
    fun fetchedCategoriesKeepAccountIdentifiersAndLocalizedLabels() =
        runTest {
            withApi(
                body =
                    """
                    {"data":{"pinnable_timelines":{"pinnable_timelines":[
                      {"__typename":"TagPinnedTimeline","name":"Business & Finance","tab_label":"Business","tag":"business"},
                      {"__typename":"TagPinnedTimeline","name":"Duplicate","tag":"business"},
                      {"__typename":"TagPinnedTimeline","name":"音楽","tab_label":"J-Pop","tag":"music"}
                    ]}}}
                    """.trimIndent(),
            ) { api ->
                val candidates = api.pinnableTagTimelines(accountKey).asSnapshot()
                assertEquals(listOf(UiText.Raw("Business"), UiText.Raw("J-Pop")), candidates.map { it.title })
                assertEquals(
                    listOf(XqtTagTimelineData(accountKey, "business"), XqtTagTimelineData(accountKey, "music")),
                    candidates.map { it.target.data },
                )
            }
        }

    @Test
    fun aFailedLoadCanBeRetriedWithoutReplacingItsAccountOrCategory() =
        runTest {
            var attempts = 0
            val pagingSource =
                XQTPinnableTimelinePagingSource(accountKey) {
                    check(++attempts > 1) { "Temporary failure" }
                    listOf(TagPinnedTimeline(name = "Technology", tag = "technology"))
                }
            val params = PagingSource.LoadParams.Refresh<Int>(key = null, loadSize = 20, placeholdersEnabled = false)

            assertIs<PagingSource.LoadResult.Error<Int, *>>(pagingSource.load(params))
            val page = assertIs<PagingSource.LoadResult.Page<Int, *>>(pagingSource.load(params))
            val candidate = assertIs<dev.dimension.flare.data.model.tab.TimelineCandidate<*>>(page.data.single())
            assertEquals(XqtTagTimelineData(accountKey, "technology"), candidate.target.data)
            assertEquals(2, attempts)
        }

    @Test
    fun cancellationIsNotConvertedIntoAPickerError() =
        runTest {
            val pagingSource = XQTPinnableTimelinePagingSource(accountKey) { throw CancellationException("cancelled") }
            assertFailsWith<CancellationException> {
                pagingSource.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false))
            }
        }

    private suspend fun withApi(
        body: String = EMPTY_CATALOG,
        status: HttpStatusCode = HttpStatusCode.OK,
        block: suspend (PinnableTimelineApi) -> Unit,
    ) {
        val client =
            HttpClient(MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())) }) {
                install(ContentNegotiation) { nullableFallbackJson(XQT_JSON) }
            }
        val api =
            ktorfit {
                baseUrl("https://example.com/")
                httpClient(client)
                converterFactories(ResponseConverterFactory())
            }.createPinnableTimelineApi()
        try {
            block(api)
        } finally {
            client.close()
        }
    }
}

private const val EMPTY_CATALOG = "{\"data\":{\"pinnable_timelines\":{\"pinnable_timelines\":[]}}}"
