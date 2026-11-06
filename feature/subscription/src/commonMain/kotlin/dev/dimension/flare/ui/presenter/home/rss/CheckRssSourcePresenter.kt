package dev.dimension.flare.ui.presenter.home.rss

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import dev.dimension.flare.data.database.app.model.SubscriptionType
import dev.dimension.flare.data.datasource.subscription.RssSourceDetector
import dev.dimension.flare.data.datasource.subscription.SubscriptionSourceDetection
import dev.dimension.flare.data.datasource.subscription.availableSubscriptionTimelines
import dev.dimension.flare.data.datasource.subscription.rssCheck
import dev.dimension.flare.data.network.FaviconService
import dev.dimension.flare.data.network.rss.model.Feed
import dev.dimension.flare.di.koinInject
import dev.dimension.flare.model.PlatformRegistry
import dev.dimension.flare.ui.model.UiRssSource
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.flattenUiState
import dev.dimension.flare.ui.presenter.PresenterBase
import dev.dimension.flare.ui.presenter.home.rss.CheckRssSourcePresenter.State.RssState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

public class CheckRssSourcePresenter internal constructor(
    private val url: String,
    private val createDetector: () -> RssSourceDetector,
) : PresenterBase<CheckRssSourcePresenter.State>() {
    public constructor(url: String) : this(url, { RssSourceDetector() })

    internal constructor(
        url: String,
        fetchFeed: suspend (String) -> Feed,
        fetchIcon: suspend (String) -> String?,
        detectLinkSources: suspend (String) -> List<String>,
    ) : this(url, { RssSourceDetector(fetchFeed, { sourceUrl, _ -> fetchIcon(sourceUrl) }, detectLinkSources) })

    internal val feeds: MutableMap<String, Feed> = mutableMapOf()
    private val platformRegistry: PlatformRegistry by koinInject()

    @Immutable
    public interface State {
        public val state: UiState<RssState>

        public sealed interface RssState {
            public data object RssHub : RssState

            public data class RssFeed(
                val title: String,
                val url: String,
                val icon: String?,
            ) : RssState

            public data class RssSources(
                val sources: ImmutableList<UiRssSource>,
            ) : RssState

            public data class SubscriptionInstance(
                val host: String,
                val instanceName: String?,
                val icon: String?,
                val availableTimelines: ImmutableList<SubscriptionType>,
            ) : RssState
        }
    }

    internal val flow: Flow<UiState<RssState>> by lazy {
        flow {
            emit(UiState.Loading())
            delay(666)
            if (url.isBlank()) return@flow
            rssCheck {
                val detected = createDetector().detect(url, unsupportedInstanceName = "Mastodon", detectInstance = ::detectSubscriptionInstance)
                feeds.putAll(detected.feeds)
                when (val source = detected.source) {
                    SubscriptionSourceDetection.RssHub -> {
                        RssState.RssHub
                    }

                    is SubscriptionSourceDetection.RssFeed -> {
                        RssState.RssFeed(source.title, source.url, source.icon)
                    }

                    is SubscriptionSourceDetection.RssSources -> {
                        RssState.RssSources(source.sources.toImmutableList())
                    }

                    is SubscriptionSourceDetection.SubscriptionInstance -> {
                        RssState.SubscriptionInstance(
                            source.host,
                            source.instanceName,
                            source.icon,
                            source.availableTimelines.toImmutableList(),
                        )
                    }
                }
            }.onFailure {
                emit(UiState.Error(it))
            }.onSuccess {
                emit(UiState.Success(it))
            }
        }
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    @Composable
    override fun body(): State {
        val state by flow.flattenUiState()
        return object : State {
            override val state: UiState<RssState>
                get() = state
        }
    }

    private suspend fun detectSubscriptionInstance(host: String): SubscriptionSourceDetection.SubscriptionInstance? =
        platformRegistry.all
            .filter { it.subscriptionTimelineSpecs.isNotEmpty() }
            .firstNotNullOfOrNull { platform ->
                val timelines = availableSubscriptionTimelines(platform.subscriptionTimelineSpecs, host)
                if (timelines.isEmpty()) {
                    null
                } else {
                    SubscriptionSourceDetection.SubscriptionInstance(
                        host = host,
                        instanceName = host,
                        icon = rssCheck { FaviconService.fetchIcon("https://$host/") }.getOrNull(),
                        availableTimelines = timelines,
                    )
                }
            }
}
