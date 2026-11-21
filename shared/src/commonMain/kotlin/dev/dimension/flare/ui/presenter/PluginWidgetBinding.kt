package dev.dimension.flare.ui.presenter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.paging.PagingData
import androidx.paging.map
import dev.dimension.flare.data.plugin.TimelineProcessorService
import dev.dimension.flare.ui.model.UiPluginWidgetCard
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.pluginPostEntityKey
import dev.dimension.flare.ui.model.takeSuccess
import dev.dimension.flare.ui.model.withPluginWidgets
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

private const val MAX_DISPLAYED_PLUGIN_WIDGETS_PER_POST = 8

internal fun Flow<PagingData<UiTimelineV2>>.bindPluginWidgets(service: TimelineProcessorService?): Flow<PagingData<UiTimelineV2>> {
    if (service == null) {
        return this
    }
    return combine(service.overlayRevision) { pagingData, _ ->
        pagingData.map { item ->
            item.bindPluginWidgetsNow(service)
        }
    }
}

internal fun UiTimelineV2.bindPluginWidgetsNow(service: TimelineProcessorService?): UiTimelineV2 {
    if (service == null) {
        return this
    }
    return when (this) {
        is UiTimelineV2.Post -> {
            service.submitPosts(listOf(this))
            attachPluginWidgets(service)
        }

        is UiTimelineV2.TimelinePostItem -> {
            if (presentation.inlineParents.isEmpty()) {
                service.submitPosts(listOf(displayPost))
            } else {
                val candidates = ArrayList<UiTimelineV2.Post>(1 + presentation.inlineParents.size)
                collectPluginCandidatePostsInto(
                    out = candidates,
                    seenKeys = LinkedHashSet(),
                    stripWidgets = false,
                )
                if (candidates.isNotEmpty()) {
                    service.submitPosts(candidates)
                }
            }
            attachPluginWidgets(service)
        }

        else -> {
            this
        }
    }
}

@Composable
internal fun rememberPluginWidgetBoundState(
    current: UiState<UiTimelineV2>,
    header: ImmutableList<UiTimelineV2>,
    service: TimelineProcessorService?,
): Pair<UiState<UiTimelineV2>, ImmutableList<UiTimelineV2>> {
    if (service == null) {
        return current to header
    }
    val revision by service.overlayRevision.collectAsState()
    val candidates =
        remember(current, header) {
            collectStateCandidatePosts(current = current.takeSuccess(), header = header)
        }
    LaunchedEffect(service, candidates, revision) {
        if (candidates.isNotEmpty()) {
            service.submitPosts(candidates)
        }
    }
    return remember(current, header, service, revision) {
        val nextCurrent =
            when (current) {
                is UiState.Success -> {
                    val updated = current.data.attachPluginWidgets(service)
                    if (updated === current.data) current else UiState.Success(updated)
                }

                is UiState.Loading -> {
                    current
                }

                is UiState.Error -> {
                    current
                }
            }
        val nextHeader =
            if (header.isEmpty()) {
                header
            } else {
                var changed = false
                val mapped =
                    header.map { item ->
                        val updated = item.attachPluginWidgets(service)
                        if (updated !== item) {
                            changed = true
                        }
                        updated
                    }
                if (changed) mapped.toImmutableList() else header
            }
        nextCurrent to nextHeader
    }
}

private fun UiTimelineV2.attachPluginWidgets(service: TimelineProcessorService): UiTimelineV2 =
    withPluginWidgets { target ->
        service.boundedWidgetsForPost(target)
    }

private fun TimelineProcessorService.boundedWidgetsForPost(post: UiTimelineV2.Post): ImmutableList<UiPluginWidgetCard> {
    val widgets = widgetsForPost(post)
    return if (widgets.size > MAX_DISPLAYED_PLUGIN_WIDGETS_PER_POST) {
        widgets.subList(0, MAX_DISPLAYED_PLUGIN_WIDGETS_PER_POST).toImmutableList()
    } else {
        widgets
    }
}

private fun collectStateCandidatePosts(
    current: UiTimelineV2?,
    header: ImmutableList<UiTimelineV2>,
): List<UiTimelineV2.Post> {
    if (current == null && header.isEmpty()) {
        return emptyList()
    }
    val out = ArrayList<UiTimelineV2.Post>((if (current != null) 1 else 0) + header.size)
    val seenKeys = LinkedHashSet<String>()
    current?.collectPluginCandidatePostsInto(out = out, seenKeys = seenKeys, stripWidgets = true)
    header.forEach { item ->
        item.collectPluginCandidatePostsInto(out = out, seenKeys = seenKeys, stripWidgets = true)
    }
    return out
}

private fun UiTimelineV2.collectPluginCandidatePostsInto(
    out: MutableList<UiTimelineV2.Post>,
    seenKeys: MutableSet<String>?,
    stripWidgets: Boolean,
) {
    when (this) {
        is UiTimelineV2.Post -> {
            val entityKey = pluginPostEntityKey()
            if (seenKeys == null || seenKeys.add(entityKey)) {
                out.add(
                    if (stripWidgets && pluginWidgets.isNotEmpty()) {
                        copy(pluginWidgets = persistentListOf())
                    } else {
                        this
                    },
                )
            }
        }

        is UiTimelineV2.TimelinePostItem -> {
            displayPost.collectPluginCandidatePostsInto(out = out, seenKeys = seenKeys, stripWidgets = stripWidgets)
            presentation.inlineParents.forEach { parent ->
                parent.collectPluginCandidatePostsInto(out = out, seenKeys = seenKeys, stripWidgets = stripWidgets)
            }
        }

        else -> {}
    }
}
