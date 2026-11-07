package dev.dimension.flare.data.datasource.subscription

import dev.dimension.flare.data.database.app.model.SubscriptionType
import dev.dimension.flare.data.datasource.microblog.paging.CacheableRemoteLoader
import dev.dimension.flare.data.network.rss.model.Feed
import dev.dimension.flare.model.SubscriptionTimelineSpec
import dev.dimension.flare.ui.model.UiTimelineV2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RssSourceDetectorTest {
    @Test
    fun failedDiscoveryCancelsInFlightSharedIconBeforeFallback() =
        runTest {
            var iconCancelled = false
            val detector =
                RssSourceDetector(
                    fetchFeed = {
                        when {
                            it.endsWith("valid") -> {
                                feed(it)
                            }

                            it.endsWith("broken") -> {
                                delay(10)
                                error("Broken feed")
                            }

                            else -> {
                                error("HTML")
                            }
                        }
                    },
                    fetchIcon = { _, _ ->
                        try {
                            awaitCancellation()
                        } finally {
                            iconCancelled = true
                        }
                    },
                    detectLinkSources = { listOf("https://example.com/valid", "https://example.com/broken") },
                )
            val instance = SubscriptionSourceDetection.SubscriptionInstance("example.com", "example.com", null, emptyList())
            val result =
                detector.detect("example.com") {
                    assertTrue(iconCancelled)
                    instance
                }
            assertEquals(instance, result.source)
        }

    @Test
    fun instanceAvailabilityIsBoundedAndOrdered() =
        runTest {
            var active = 0
            var peak = 0
            val specs =
                SubscriptionType.entries.map { timelineType ->
                    object : SubscriptionTimelineSpec {
                        override val type = timelineType

                        override suspend fun isAvailable(
                            host: String,
                            locale: String,
                        ): Boolean {
                            active++
                            peak = maxOf(peak, active)
                            try {
                                delay(10)
                                return timelineType != SubscriptionType.RSS
                            } finally {
                                active--
                            }
                        }

                        override fun createLoader(
                            host: String,
                            locale: String,
                        ): CacheableRemoteLoader<UiTimelineV2> = error("Only probing")
                    }
                }
            assertEquals(
                SubscriptionType.entries.filter { it != SubscriptionType.RSS },
                availableSubscriptionTimelines(specs, "example.com"),
            )
            assertEquals(3, peak)
            assertEquals(0, active)
        }

    @Test
    fun directXmlIsParsedFromOneResponse() =
        runTest {
            var reads = 0
            val detector =
                RssSourceDetector(
                    fetchFeed = { error("Must use downloaded XML") },
                    fetchIcon = { _, feed ->
                        assertEquals("XML title", (feed as Feed.Rss20).channel.title)
                        null
                    },
                    detectLinkSources = { error("Already parsed XML") },
                    readInitialText = {
                        reads++
                        "<rss version=\"2.0\"><channel><title>XML title</title><link>https://example.com/</link></channel></rss>"
                    },
                )
            val result = detector.detect("https://example.com/feed") { error("Already parsed XML") }
            assertEquals("XML title", assertIs<SubscriptionSourceDetection.RssFeed>(result.source).title)
            assertEquals(1, reads)
        }

    @Test
    fun atomIconAndWebsiteUrlUseSeparateCacheEntries() =
        runTest {
            val atom = Feed.Atom("atom", Feed.Atom.Text("Atom"), updated = null, icon = "https://example.com/")
            val detector =
                RssSourceDetector(
                    fetchFeed = {
                        when {
                            it.endsWith("atom") -> atom
                            it.endsWith("rss") -> feed("https://example.com/")
                            else -> error("HTML")
                        }
                    },
                    fetchIcon = { _, feed -> if (feed is Feed.Atom) feed.icon else "https://example.com/favicon.ico" },
                    detectLinkSources = { listOf("https://example.com/atom", "https://example.com/rss") },
                )
            val sources =
                assertIs<SubscriptionSourceDetection.RssSources>(
                    detector.detect("example.com") { error("Feeds found") }.source,
                ).sources
            assertEquals(listOf("https://example.com/", "https://example.com/favicon.ico"), sources.map { it.favIcon })
        }

    @Test
    fun feedAndHtmlDetectionReuseOneInitialResponse() =
        runTest {
            var initialReads = 0
            val detector =
                RssSourceDetector(
                    fetchFeed = {
                        assertEquals("https://example.com/feed", it)
                        feed(it)
                    },
                    fetchIcon = { _, _ -> null },
                    detectLinkSources = { error("Must reuse initial HTML") },
                    readInitialText = {
                        initialReads++
                        "<html><head><link type=\"application/rss+xml\" href=\"/feed\"></head></html>"
                    },
                )
            val result = detector.detect("https://example.com/") { error("Found a feed") }
            assertEquals(1, initialReads)
            assertEquals("https://example.com/feed", assertIs<SubscriptionSourceDetection.RssSources>(result.source).sources.single().url)
        }

    @Test
    fun directFeedUsesFetchedMetadataForIconAndReturnsCache() =
        runTest {
            val feed = feed("https://example.com/")
            val detector =
                RssSourceDetector(
                    fetchFeed = {
                        assertEquals("https://example.com/rss", it)
                        feed
                    },
                    fetchIcon = { url, fetched ->
                        assertEquals("https://example.com/rss", url)
                        assertSame(feed, fetched)
                        "icon"
                    },
                    detectLinkSources = { error("Already parsed a feed") },
                )
            val result = detector.detect(" https://example.com/rss ") { error("Already parsed a feed") }
            assertEquals("Feed", assertIs<SubscriptionSourceDetection.RssFeed>(result.source).title)
            assertSame(feed, result.feeds["https://example.com/rss"])
        }

    @Test
    fun discoveredFeedsShareIconsAndKeepOrderAndCompleteCache() =
        runTest {
            val urls = List(9) { "https://example.com/feed/$it" }
            val feeds = urls.associateWith { feed("https://example.com/") }
            var iconCalls = 0
            val detector =
                RssSourceDetector(
                    fetchFeed = { feeds[it] ?: error("HTML") },
                    fetchIcon = { _, _ ->
                        iconCalls++
                        delay(10)
                        "shared-icon"
                    },
                    detectLinkSources = { urls },
                )
            val result = detector.detect("https://example.com/") { error("Already found feeds") }
            val sources = assertIs<SubscriptionSourceDetection.RssSources>(result.source).sources
            assertEquals(urls, sources.map { it.url })
            assertEquals(feeds, result.feeds)
            assertEquals(List(9) { "shared-icon" }, sources.map { it.favIcon })
            assertEquals(1, iconCalls)
        }

    @Test
    fun cancellationDoesNotProbeOtherSources() =
        runTest {
            val detector =
                RssSourceDetector(
                    fetchFeed = { throw CancellationException("Stopped") },
                    fetchIcon = { _, _ -> error("Cancelled") },
                    detectLinkSources = { error("Cancelled") },
                )
            assertFailsWith<CancellationException> {
                detector.detect("https://example.com/") { error("Cancelled") }
            }
        }

    @Test
    fun missingIconDoesNotDiscardValidFeed() =
        runTest {
            val detector =
                RssSourceDetector(
                    fetchFeed = { feed(it) },
                    fetchIcon = { _, _ -> error("No icon") },
                    detectLinkSources = { error("Already parsed") },
                )
            val result = detector.detect("example.com") { error("Already parsed") }
            assertEquals(null, assertIs<SubscriptionSourceDetection.RssFeed>(result.source).icon)
        }

    @Test
    fun failedDiscoveryFallsBackWithoutPublishingPartialFeeds() =
        runTest {
            val instance = SubscriptionSourceDetection.SubscriptionInstance("example.com", "example.com", null, emptyList())
            val detector =
                RssSourceDetector(
                    fetchFeed = { if (it.endsWith("valid")) feed(it) else error("Not a feed") },
                    fetchIcon = { _, _ -> null },
                    detectLinkSources = { listOf("https://example.com/valid", "https://example.com/broken") },
                )
            val result =
                detector.detect("example.com") {
                    assertEquals("example.com", it)
                    instance
                }
            assertEquals(instance, result.source)
            assertEquals(emptyMap(), result.feeds)
        }

    private fun feed(link: String) = Feed.Rss20("2.0", Feed.Rss20.Channel(" Feed ", link))
}
