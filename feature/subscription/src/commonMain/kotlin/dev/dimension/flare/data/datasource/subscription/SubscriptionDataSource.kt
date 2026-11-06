package dev.dimension.flare.data.datasource.subscription

import dev.dimension.flare.common.Locale
import dev.dimension.flare.data.database.app.model.SubscriptionType
import dev.dimension.flare.data.datasource.microblog.paging.CacheableRemoteLoader
import dev.dimension.flare.data.datasource.rss.RssTimelineRemoteMediator
import dev.dimension.flare.data.model.tab.RssTimelineData
import dev.dimension.flare.data.model.tab.SubscriptionTimelineData
import dev.dimension.flare.data.network.FaviconService
import dev.dimension.flare.data.network.rss.DocumentData
import dev.dimension.flare.data.network.rss.Readability
import dev.dimension.flare.data.platform.RssTimelineSpecs
import dev.dimension.flare.data.repository.SettingsRepository
import dev.dimension.flare.data.repository.SubscriptionRepository
import dev.dimension.flare.data.repository.SubscriptionSourceInput
import dev.dimension.flare.di.koinInject
import dev.dimension.flare.model.PlatformRegistry
import dev.dimension.flare.ui.model.UiRssSource
import dev.dimension.flare.ui.model.UiTimelineV2
import kotlinx.coroutines.flow.first
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
public interface SubscriptionDataSource {
    public suspend fun listSources(): List<UiRssSource>

    public suspend fun detectSource(input: String): SubscriptionSourceDetection

    public fun createTimelineLoader(
        type: SubscriptionType,
        url: String,
    ): CacheableRemoteLoader<UiTimelineV2>

    public suspend fun saveSource(input: SubscriptionSourceInput): UiRssSource

    public suspend fun deleteSource(id: Int): UiRssSource?

    public suspend fun loadRssArticle(
        url: String,
        descriptionHtml: String? = null,
        descriptionTitle: String? = null,
        fallbackHtml: String? = null,
    ): DocumentData
}

@HiddenFromObjC
public sealed interface SubscriptionSourceDetection {
    @HiddenFromObjC
    public data object RssHub : SubscriptionSourceDetection

    @HiddenFromObjC
    public data class RssFeed(
        val title: String,
        val url: String,
        val icon: String?,
    ) : SubscriptionSourceDetection

    @HiddenFromObjC
    public data class RssSources(
        val sources: List<UiRssSource>,
    ) : SubscriptionSourceDetection

    @HiddenFromObjC
    public data class SubscriptionInstance(
        val host: String,
        val instanceName: String?,
        val icon: String?,
        val availableTimelines: List<SubscriptionType>,
    ) : SubscriptionSourceDetection
}

@HiddenFromObjC
public object KoinSubscriptionDataSource :
    SubscriptionDataSource {
    private val subscriptionRepository: SubscriptionRepository by koinInject()
    private val settingsRepository: SettingsRepository by koinInject()
    private val platformRegistry: PlatformRegistry by koinInject()
    private val readability = Readability()

    override suspend fun listSources(): List<UiRssSource> = subscriptionRepository.observeAll().first()

    override suspend fun detectSource(input: String): SubscriptionSourceDetection =
        RssSourceDetector().detect(input, detectInstance = ::detectSubscriptionInstance).source

    override fun createTimelineLoader(
        type: SubscriptionType,
        url: String,
    ): CacheableRemoteLoader<UiTimelineV2> =
        when (type) {
            SubscriptionType.RSS -> {
                RssTimelineRemoteMediator(
                    url = url,
                    fetchSource = {
                        subscriptionRepository.findByUrl(it).firstOrNull()
                    },
                )
            }

            else -> {
                platformRegistry
                    .requireSubscriptionTimelineSpec(type)
                    .createLoader(
                        host = url,
                        locale = Locale.language,
                    )
            }
        }

    override suspend fun saveSource(input: SubscriptionSourceInput): UiRssSource = subscriptionRepository.upsert(input)

    override suspend fun deleteSource(id: Int): UiRssSource? {
        val source = subscriptionRepository.delete(id)
        source?.let {
            settingsRepository.removeHomeTimelineTabBySourceId(it.timelineSourceId())
        }
        return source
    }

    override suspend fun loadRssArticle(
        url: String,
        descriptionHtml: String?,
        descriptionTitle: String?,
        fallbackHtml: String?,
    ): DocumentData = readability.load(url, descriptionHtml, descriptionTitle, fallbackHtml)

    private suspend fun detectSubscriptionInstance(host: String): SubscriptionSourceDetection.SubscriptionInstance? {
        val timelines = availableSubscriptionTimelines(platformRegistry.subscriptionTimelineSpecs, host)
        if (timelines.isEmpty()) return null
        return SubscriptionSourceDetection.SubscriptionInstance(
            host = host,
            instanceName = host,
            icon = rssCheck { FaviconService.fetchIcon("https://$host/") }.getOrNull(),
            availableTimelines = timelines,
        )
    }
}

private fun UiRssSource.timelineSourceId(): String =
    when (type) {
        SubscriptionType.RSS -> {
            RssTimelineSpecs.rss
                .itemId(RssTimelineData(url))
        }

        else -> {
            RssTimelineSpecs.subscription
                .itemId(
                    SubscriptionTimelineData(
                        subscriptionUrl = url,
                        subscriptionType = type,
                    ),
                )
        }
    }
