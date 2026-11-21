package dev.dimension.flare.data.plugin

import dev.dimension.flare.di.koinGet
import dev.dimension.flare.ui.model.UiPluginWidgetCard
import dev.dimension.flare.ui.model.UiTimelineV2
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
public interface TimelineProcessorService {
    public val overlayRevision: StateFlow<Long>

    public fun submitPosts(posts: List<UiTimelineV2.Post>)

    public fun widgetsForPost(post: UiTimelineV2.Post): ImmutableList<UiPluginWidgetCard>

    public fun refreshPostPlugin(
        post: UiTimelineV2.Post,
        pluginId: String,
    )

    public fun toggleCollapsible(
        postEntityKey: String,
        pluginId: String,
        cardId: String,
        collapsibleId: String,
    )

    public fun clearPlugin(pluginId: String)

    public fun clearFailureCooldowns() {}
}

@HiddenFromObjC
public object NoopTimelineProcessorService : TimelineProcessorService {
    private val revision = MutableStateFlow(0L)
    override val overlayRevision: StateFlow<Long> = revision

    override fun submitPosts(posts: List<UiTimelineV2.Post>) {}

    override fun widgetsForPost(post: UiTimelineV2.Post): ImmutableList<UiPluginWidgetCard> = persistentListOf()

    override fun refreshPostPlugin(
        post: UiTimelineV2.Post,
        pluginId: String,
    ) {}

    override fun toggleCollapsible(
        postEntityKey: String,
        pluginId: String,
        cardId: String,
        collapsibleId: String,
    ) {}

    override fun clearPlugin(pluginId: String) {}

    override fun clearFailureCooldowns() {}
}

internal fun timelineProcessorServiceOrNull(): TimelineProcessorService? = runCatching { koinGet<TimelineProcessorService>() }.getOrNull()
