package dev.dimension.flare.data.datasource.microblog.paging

import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.UiTranslatableText
import dev.dimension.flare.ui.model.contentPostOrNull
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.render.toUiPlainText
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Instant

class RecommendationBatchLoaderTest {
    @Test
    fun dedicatedCachesAreIsolatedByAccountAndTab() {
        val source = Source()
        val first = RecommendationBatchLoader(source, "first")
        val second = RecommendationBatchLoader(source, "second")
        val anotherAccount = RecommendationBatchLoader(Source("featured_other"), "first")

        assertNotEquals(source.pagingKey, first.pagingKey)
        assertNotEquals(first.pagingKey, second.pagingKey)
        assertNotEquals(first.pagingKey, anotherAccount.pagingKey)
        assertEquals(first.pagingKey, RecommendationBatchLoader(source, "first").pagingKey)
    }

    @Test
    fun ordinaryPagingStillDelegatesWithoutRecommendationFiltering() =
        runTest {
            val source = Source()
            val loader = RecommendationBatchLoader(source, "tab")
            loader.markViewed(listOf(post(1)))
            val request = PagingRequest.Append("ordinary-tail")

            assertSame(source.normalPage, loader.load(7, request))
            assertEquals(listOf<Pair<Int, PagingRequest>>(7 to request), source.normalRequests)
            assertEquals(emptyList<Request>(), source.requests)
            assertEquals(source.refreshGapOncePerSession, loader.refreshGapOncePerSession)
        }

    @Test
    fun freshBatchSkipsViewedAndDuplicateDisplayedPostsInServerOrder() =
        runTest {
            val source =
                Source().apply {
                    pages += page(listOf(post(3), post(1), repost(90, 3), post(2), post(4)), "next")
                }
            val loader = RecommendationBatchLoader(source, "tab")
            loader.markViewed(listOf(repost(80, 1)))

            val result = assertNotNull(loader.loadFresh(3))

            assertEquals(listOf("3", "2", "4"), result.ids())
            assertEquals("next", result.nextKey)
            assertEquals(listOf(Request(3, null, listOf("1"))), source.requests)
        }

    @Test
    fun merelyFetchingPostsDoesNotMarkThemViewed() =
        runTest {
            val source = Source().apply { repeat(2) { pages += page(listOf(post(1)), null) } }
            val loader = RecommendationBatchLoader(source, "tab")

            assertEquals(listOf("1"), assertNotNull(loader.loadFresh(1)).ids())
            assertEquals(listOf("1"), assertNotNull(loader.loadFresh(1)).ids())
            assertEquals(listOf(emptyList(), emptyList()), source.requests.map { it.seenPostIds })
        }

    @Test
    fun viewedHistoryIsBoundedAndRevisitingAnOldPostKeepsItRecent() =
        runTest {
            val source = Source().apply { pages += page(listOf(post(999)), null) }
            val loader = RecommendationBatchLoader(source, "tab")
            loader.markViewed((1..200).map(::post))
            loader.markViewed(listOf(post(1), post(201)))

            assertNotNull(loader.loadFresh(1))

            assertEquals((3..200).map(Int::toString) + listOf("1", "201"), source.requests.single().seenPostIds)
        }

    @Test
    fun eachBatchUsesOneViewedSnapshotWhileBackfillingAndDeduplicatesAcrossPages() =
        runTest {
            val source =
                Source().apply {
                    pages += page(listOf(post(1), post(2)), "a")
                    pages += page(listOf(repost(20, 2), post(3)), "b")
                }
            val loader = RecommendationBatchLoader(source, "tab")
            loader.markViewed(listOf(post(1)))
            source.onRequest = { loader.markViewed(listOf(post(3))) }

            val result = assertNotNull(loader.loadFresh(2))

            assertEquals(listOf("2", "3"), result.ids())
            assertEquals("b", result.nextKey)
            assertEquals(listOf(null, "a"), source.requests.map { it.cursor })
            assertEquals(listOf(listOf("1"), listOf("1")), source.requests.map { it.seenPostIds })
        }

    @Test
    fun atMostThreePagesAreFetchedWhenEverythingWasSeen() =
        runTest {
            val source =
                Source().apply {
                    pages += page(listOf(post(1)), "a")
                    pages += page(listOf(post(1)), "b")
                    pages += page(listOf(post(1)), "c")
                    pages += page(listOf(post(2)), "d")
                }
            val loader = RecommendationBatchLoader(source, "tab")
            loader.markViewed(listOf(post(1)))

            assertNull(loader.loadFresh(20))
            assertEquals(listOf(null, "a", "b"), source.requests.map { it.cursor })
        }

    @Test
    fun enoughFreshPostsKeepTheWholePageAndItsContinuation() =
        runTest {
            val source = Source().apply { pages += page(listOf(post(1), post(2), post(3)), "tail") }
            val loader = RecommendationBatchLoader(source, "tab")

            val result = assertNotNull(loader.loadFresh(2))

            assertEquals(listOf("1", "2", "3"), result.ids())
            assertEquals("tail", result.nextKey)
            assertEquals(1, source.requests.size)
        }

    @Test
    fun partialFreshBatchKeepsTheLastContinuationAtRequestBudget() =
        runTest {
            val source =
                Source().apply {
                    pages += page(listOf(post(1)), "a")
                    pages += page(listOf(post(1)), "b")
                    pages += page(listOf(post(2)), "c")
                }
            val loader = RecommendationBatchLoader(source, "tab")

            val result = assertNotNull(loader.loadFresh(20))

            assertEquals(listOf("1", "2"), result.ids())
            assertEquals("c", result.nextKey)
            assertEquals(3, source.requests.size)
        }

    @Test
    fun repeatedCursorStopsBackfillAndCannotEscapeAsARepeatedContinuation() =
        runTest {
            val source =
                Source().apply {
                    pages += page(listOf(post(1)), "a")
                    pages += page(listOf(post(2)), "a")
                }
            val loader = RecommendationBatchLoader(source, "tab")

            val result = assertNotNull(loader.loadFresh(20))

            assertEquals(listOf("1", "2"), result.ids())
            assertNull(result.nextKey)
            assertEquals(listOf(null, "a"), source.requests.map { it.cursor })
        }

    @Test
    fun filteredPagesAreBackfilledWithoutClearingTheViewedHistory() =
        runTest {
            val source =
                Source().apply {
                    pages += page(listOf(post(1), post(2)), "a")
                    pages += page(listOf(post(3)), null)
                }
            val loader = RecommendationBatchLoader(source, "tab")
            loader.markViewed(listOf(post(1)))

            val result = assertNotNull(loader.loadFresh(20) { it.contentPostOrNull()?.statusKey?.id == "3" })

            assertEquals(listOf("3"), result.ids())
            assertEquals(listOf(listOf("1"), listOf("1")), source.requests.map { it.seenPostIds })
        }

    @Test
    fun emptyOrEntirelyFilteredBatchesReturnNoReplacement() =
        runTest {
            val source =
                Source().apply {
                    pages += page(emptyList(), null)
                    pages += page(listOf(post(1)), null)
                }
            val loader = RecommendationBatchLoader(source, "tab")

            assertNull(loader.loadFresh(20))
            assertNull(loader.loadFresh(20) { false })
            assertEquals(2, source.requests.size)
        }

    @Test
    fun continuationFailureRejectsTheEntireCandidateBatch() =
        runTest {
            val failure = IllegalStateException("network failure")
            val source =
                Source().apply {
                    pages += page(listOf(post(2)), "a")
                    pages += PagingResult(data = listOf(post(3)), continuationFailure = failure)
                }
            val loader = RecommendationBatchLoader(source, "tab")
            loader.markViewed(listOf(post(1)))

            assertEquals(failure.message, assertFailsWith<IllegalStateException> { loader.loadFresh(20) }.message)
            assertEquals(listOf(listOf("1"), listOf("1")), source.requests.map { it.seenPostIds })
        }

    @Test
    fun sourceFailureRejectsTheEntireCandidateBatch() =
        runTest {
            val failure = IllegalStateException("source failed")
            val source = Source().apply { pages += PagingResult(data = listOf(post(1)), sourceFailures = listOf(failure)) }

            assertEquals(
                failure.message,
                assertFailsWith<IllegalStateException> { RecommendationBatchLoader(source, "tab").loadFresh(20) }.message,
            )
        }

    @Test
    fun cancellationPropagatesAndDoesNotForgetViewedPosts() =
        runTest {
            val source = Source().apply { pages += page(listOf(post(2)), null) }
            val loader = RecommendationBatchLoader(source, "tab")
            loader.markViewed(listOf(post(1)))
            source.onRequest = { throw CancellationException("cancelled") }

            assertFailsWith<CancellationException> { loader.loadFresh(20) }
            source.onRequest = {}
            assertNotNull(loader.loadFresh(20))
            assertEquals(listOf(listOf("1"), listOf("1")), source.requests.map { it.seenPostIds })
        }

    private data class Request(
        val pageSize: Int,
        val cursor: String?,
        val seenPostIds: List<String>,
    )

    private class Source(
        override val pagingKey: String = "featured_account",
    ) : RecommendationRefreshSource {
        val requests = mutableListOf<Request>()
        val normalRequests = mutableListOf<Pair<Int, PagingRequest>>()
        val normalPage = PagingResult<UiTimelineV2>(nextKey = "normal")
        val pages = mutableListOf<PagingResult<UiTimelineV2>>()
        var onRequest: () -> Unit = {}
        override val refreshGapOncePerSession = true

        override suspend fun loadRecommendations(
            pageSize: Int,
            cursor: String?,
            seenPostIds: List<String>,
        ): PagingResult<UiTimelineV2> {
            requests += Request(pageSize, cursor, seenPostIds)
            onRequest()
            return pages.removeAt(0)
        }

        override suspend fun load(
            pageSize: Int,
            request: PagingRequest,
        ): PagingResult<UiTimelineV2> {
            normalRequests += pageSize to request
            return normalPage
        }
    }

    private fun PagingResult<UiTimelineV2>.ids() = data.mapNotNull { it.contentPostOrNull()?.statusKey?.id }

    private fun page(
        items: List<UiTimelineV2>,
        nextKey: String?,
    ) = PagingResult(data = items, nextKey = nextKey)

    private fun repost(
        id: Int,
        originalId: Int,
    ) = UiTimelineV2.TimelinePostItem(post(id), UiTimelineV2.PostPresentation(repost = post(originalId)))

    private fun post(id: Int) =
        UiTimelineV2.Post(
            platformId = "xQt",
            images = persistentListOf(),
            sensitive = false,
            contentWarning = null,
            user = null,
            content = UiTranslatableText("Post $id".toUiPlainText()),
            actions = persistentListOf(),
            poll = null,
            statusKey = MicroBlogKey(id.toString(), "x.com"),
            card = null,
            createdAt = Instant.fromEpochMilliseconds(1).toUi(),
            clickEvent = ClickEvent.Noop,
            accountType = AccountType.Guest,
        )
}
