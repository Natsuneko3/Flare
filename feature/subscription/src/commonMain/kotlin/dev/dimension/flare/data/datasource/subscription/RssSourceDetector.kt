package dev.dimension.flare.data.datasource.subscription

import dev.dimension.flare.common.Locale
import dev.dimension.flare.data.database.app.model.RssDisplayMode
import dev.dimension.flare.data.database.app.model.SubscriptionType
import dev.dimension.flare.data.network.rss.RssService
import dev.dimension.flare.data.network.rss.model.Feed
import dev.dimension.flare.data.network.rss.parseFeedText
import dev.dimension.flare.data.network.rss.toRssWebUrl
import dev.dimension.flare.model.SubscriptionTimelineSpec
import dev.dimension.flare.ui.model.UiRssSource
import dev.dimension.flare.ui.model.mapper.link
import dev.dimension.flare.ui.model.mapper.title
import dev.dimension.flare.ui.render.toUi
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Instant

internal class RssSourceDetector(
    private val fetchFeed: suspend (String) -> Feed,
    private val fetchIcon: suspend (String, Feed) -> String?,
    private val detectLinkSources: suspend (String) -> List<String>,
    private val readInitialText: (suspend (String) -> String)? = null,
) {
    constructor() : this(RssService::fetch, RssService::fetchIcon, RssService::detectLinkSources, RssService::fetchText)

    data class Result(
        val source: SubscriptionSourceDetection,
        val feeds: Map<String, Feed> = emptyMap(),
    )

    suspend fun detect(
        input: String,
        unsupportedInstanceName: String = "supported subscription",
        detectInstance: suspend (String) -> SubscriptionSourceDetection.SubscriptionInstance?,
    ): Result =
        coroutineScope {
            val url = input.trim()
            require(url.isNotBlank()) { "Subscription URL or host is required." }
            if (url.startsWith("rsshub://", ignoreCase = true)) {
                return@coroutineScope Result(SubscriptionSourceDetection.RssHub)
            }
            val actualUrl = url.toRssWebUrl()
            val icons = mutableMapOf<String, Deferred<String?>>()
            val iconsMutex = Mutex()

            suspend fun icon(
                sourceUrl: String,
                feed: Feed,
            ): String? =
                coroutineScope {
                    val key =
                        (feed as? Feed.Atom)?.icon?.let { "feed:$it" }
                            ?: "website:${feed.link?.takeIf { it.isNotBlank() } ?: sourceUrl}"
                    iconsMutex
                        .withLock {
                            icons.getOrPut(key) { async { rssCheck { fetchIcon(sourceUrl, feed) }.getOrNull() } }
                        }.await()
                }

            var initialText: String? = null
            val feed =
                rssCheck {
                    if (readInitialText != null) {
                        readInitialText(actualUrl).also { initialText = it }.let(::parseFeedText)
                    } else {
                        fetchFeed(actualUrl)
                    }
                }.getOrNull()
            if (feed != null) {
                return@coroutineScope Result(
                    SubscriptionSourceDetection.RssFeed(feed.title.trim(), actualUrl, icon(actualUrl, feed)),
                    mapOf(actualUrl to feed),
                )
            }

            val discovered =
                rssCheck {
                    coroutineScope {
                        val permits = Semaphore(3)
                        val links = initialText?.let { RssService.parseLinkSources(actualUrl, it) } ?: detectLinkSources(actualUrl)
                        links
                            .map { sourceUrl ->
                                async {
                                    permits.withPermit {
                                        val sourceFeed = fetchFeed(sourceUrl)
                                        UiRssSource(
                                            id = 0,
                                            url = sourceUrl,
                                            title = sourceFeed.title.trim(),
                                            lastUpdate = Instant.DISTANT_PAST.toUi(),
                                            favIcon = icon(sourceUrl, sourceFeed),
                                            displayMode = RssDisplayMode.FULL_CONTENT,
                                        ) to sourceFeed
                                    }
                                }
                            }.awaitAll()
                    }
                }.getOrNull()
            if (!discovered.isNullOrEmpty()) {
                return@coroutineScope Result(
                    SubscriptionSourceDetection.RssSources(discovered.map { it.first }),
                    discovered.associate { (source, sourceFeed) -> source.url to sourceFeed },
                )
            }
            val host =
                rssCheck { Url(actualUrl).host }.getOrNull()
                    ?: throw IllegalArgumentException("Invalid URL: $input")
            Result(
                detectInstance(host)
                    ?: throw IllegalArgumentException("URL is not a valid RSS feed or $unsupportedInstanceName instance: $input"),
            )
        }
}

internal suspend fun availableSubscriptionTimelines(
    specs: List<SubscriptionTimelineSpec>,
    host: String,
): List<SubscriptionType> =
    coroutineScope {
        val permits = Semaphore(3)
        specs
            .map { spec ->
                async {
                    permits.withPermit {
                        spec.type.takeIf { spec.isAvailable(host, Locale.language) }
                    }
                }
            }.awaitAll()
            .filterNotNull()
    }

internal inline fun <T> rssCheck(block: () -> T): kotlin.Result<T> =
    try {
        kotlin.Result.success(block())
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        kotlin.Result.failure(exception)
    }
