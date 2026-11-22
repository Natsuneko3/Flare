package dev.dimension.flare.data.datasource.xqt

import de.jensklingenberg.ktorfit.converter.ResponseConverterFactory
import de.jensklingenberg.ktorfit.ktorfit
import dev.dimension.flare.data.datasource.microblog.CachedProfileTab
import dev.dimension.flare.data.datasource.microblog.ProfileTab
import dev.dimension.flare.data.network.nullableFallbackJson
import dev.dimension.flare.data.network.xqt.XQT_JSON
import dev.dimension.flare.data.network.xqt.api.createUserApi
import dev.dimension.flare.data.platform.XQTCredential
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiStrings
import dev.dimension.flare.ui.model.asText
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class XQTProfileTabsTest {
    private val accountKey = MicroBlogKey("1", "x.com")
    private val userKey = MicroBlogKey("2", "x.com")
    private val required = listOf(UiStrings.Posts, UiStrings.PostsWithReplies, UiStrings.Reposts, UiStrings.Media)
    private var status = HttpStatusCode.OK
    private var body = userResult("""{"__typename":"User","rest_id":"2"}""")
    private val client =
        HttpClient(MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }) {
            install(ContentNegotiation) { nullableFallbackJson(XQT_JSON) }
        }
    private val source =
        XQTDataSource(
            accountKey = accountKey,
            sourceCredentialFlow = flowOf(XQTCredential(chocolate = "")),
            profileTabApi =
                ktorfit {
                    baseUrl("https://example.com/")
                    httpClient(client)
                    converterFactories(ResponseConverterFactory())
                }.createUserApi(),
        )

    @AfterTest
    fun tearDown() = client.close()

    @Test
    fun failedOrIncompleteUserResponsesCannotReplaceCachedTabs() =
        runTest {
            status = HttpStatusCode.TooManyRequests
            val rateLimit = assertFailsWith<IllegalStateException> { source.profileTabs(userKey) }
            assertContains(rateLimit.message.orEmpty(), "429")

            status = HttpStatusCode.OK
            body = """{"data":{"user":{}}}"""
            assertFailsWith<IllegalStateException> { source.profileTabs(userKey) }

            body = """{"data":{"user":{"result":{"__typename":"User","rest_id":"2"}}},"errors":[{"message":"Profile data incomplete"}]}"""
            val partial = assertFailsWith<IllegalStateException> { source.profileTabs(userKey) }
            assertContains(partial.message.orEmpty(), "Profile data incomplete")
        }

    @Test
    fun confirmedUserMetadataDeterminesTheTabsAndTheirCachedOrder() =
        runTest {
            body =
                userResult(
                    """{"__typename":"User","rest_id":"2","highlights_info":{"can_highlight_tweets":true,"highlighted_tweets":"1"},"user_seed_tweet_count":2}""",
                )
            val tabs = source.profileTabs(userKey)
            val expected =
                listOf(
                    UiStrings.Posts,
                    UiStrings.PostsWithReplies,
                    UiStrings.Reposts,
                    UiStrings.Highlights,
                    UiStrings.Media,
                    UiStrings.Articles,
                )
            assertEquals(expected.map { it.asText() }, tabs.map { it.name })

            for (absent in listOf(null, UiStrings.Highlights, UiStrings.Articles)) {
                val cached = tabs.filterNot { it.name == absent?.asText() }.reversed().map { it.toCached() }
                assertEquals(cached, source.restoreProfileTabs(userKey, cached)?.map { it.toCached() })
            }

            body = userResult("""{"__typename":"UserUnavailable","reason":"Suspended"}""")
            val unavailable = source.profileTabs(userKey)
            assertEquals(required.map { it.asText() }, unavailable.map { it.name })
            val base = unavailable.map { it.toCached() }
            assertEquals(base, source.restoreProfileTabs(userKey, base)?.map { it.toCached() })
        }

    @Test
    fun rejectsInvalidEntriesAndMissingRequiredTabsForEachProfile() =
        runTest {
            val base = source.profileTabs(userKey).map { it.toCached() }
            val media = base.single { it.displayType == ProfileTab.DisplayType.Gallery }
            val invalid =
                listOf(
                    emptyList(),
                    base + CachedProfileTab("unknown"),
                    base + base.first(),
                    base.drop(1) + base.last(),
                    base - media + media.copy(displayType = ProfileTab.DisplayType.Timeline),
                    base + CachedProfileTab("user_likes_${userKey}_$accountKey"),
                )
            for (cached in invalid) assertNull(source.restoreProfileTabs(userKey, cached))
            for (tab in base) assertNull(source.restoreProfileTabs(userKey, base - tab))
            assertNull(source.restoreProfileTabs(accountKey, base))

            body = userResult("""{"__typename":"User","rest_id":"1"}""")
            val own = source.profileTabs(accountKey)
            assertEquals((required + UiStrings.Liked).map { it.asText() }, own.map { it.name })
            val cached = own.map { it.toCached() }
            assertEquals(cached, source.restoreProfileTabs(accountKey, cached)?.map { it.toCached() })
            assertNull(source.restoreProfileTabs(accountKey, cached.dropLast(1)))
        }

    private fun userResult(result: String): String = """{"data":{"user":{"result":$result}}}"""
}
