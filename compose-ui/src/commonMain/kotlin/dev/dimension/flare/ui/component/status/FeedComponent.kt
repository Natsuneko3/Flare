package dev.dimension.flare.ui.component.status

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.dimension.flare.compose.ui.Res
import dev.dimension.flare.compose.ui.feed_open_article
import dev.dimension.flare.ui.component.DateTimeText
import dev.dimension.flare.ui.component.LocalTimelineCarouselItem
import dev.dimension.flare.ui.component.TimelineCarouselItem
import dev.dimension.flare.ui.component.NetworkImage
import dev.dimension.flare.ui.component.accessibleDescription
import dev.dimension.flare.ui.component.platform.PlatformText
import dev.dimension.flare.ui.model.ClickContext
import dev.dimension.flare.ui.model.TranslationDisplayState
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.instagramMedia
import dev.dimension.flare.ui.route.DeeplinkRoute
import dev.dimension.flare.ui.route.toUri
import dev.dimension.flare.ui.theme.PlatformTheme
import dev.dimension.flare.ui.theme.screenHorizontalPadding
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun FeedComponent(
    data: UiTimelineV2.Feed,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val openArticleLabel = stringResource(Res.string.feed_open_article)
    Column(
        modifier =
            Modifier
                .clickable(onClickLabel = openArticleLabel) {
                    data.onClicked.invoke(
                        ClickContext(uriHandler::openUri),
                    )
                }.padding(
                    horizontal = screenHorizontalPadding,
                    vertical = 8.dp,
                ).then(modifier),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            data.source.media?.let {
                NetworkImage(
                    it,
                    contentDescription = data.source.name,
                    modifier = Modifier.size(16.dp),
                )
            }
            PlatformText(
                text = data.source.name,
                style = PlatformTheme.typography.caption,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            AnimatedVisibility(data.translationDisplayState != TranslationDisplayState.Hidden) {
                TranslationDisplayBadge(
                    state = data.translationDisplayState,
                )
            }
            data.actualCreatedAt?.let {
                DateTimeText(
                    it,
                    style = PlatformTheme.typography.caption,
                    color = PlatformTheme.colorScheme.caption,
                )
            }
        }
        data.title?.let { title ->
            PlatformText(
                text = title,
                style = PlatformTheme.typography.title,
            )
        }
        val media = remember(data.url, data.descriptionHtml, data.media, data.source.proxy, data.pluginId) { data.instagramMedia() }
        if (media.isNotEmpty()) {
            data.description?.takeIf { it.isNotBlank() && it != data.title }?.let {
                PlatformText(text = it, style = PlatformTheme.typography.caption, maxLines = 5)
            }
            InstagramMediaGallery(media)
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                data.description?.let {
                    PlatformText(
                        text = it,
                        style = PlatformTheme.typography.caption,
                        maxLines = 5,
                        color = PlatformTheme.colorScheme.caption,
                        modifier =
                            Modifier.let {
                                if (data.media != null) {
                                    it.weight(1f)
                                } else {
                                    it
                                }
                            },
                    )
                }
                data.media?.let {
                    NetworkImage(
                        model = it,
                        contentDescription = it.accessibleDescription(),
                        modifier =
                            Modifier
                                .let {
                                    if (data.description != null) {
                                        it.size(80.dp)
                                    } else {
                                        it.aspectRatio(16f / 9f)
                                    }
                                }.clip(
                                    PlatformTheme.shapes.medium,
                                ),
                    )
                }
            }
        }
    }
}

public val LocalOpenRssMedia: ProvidableCompositionLocal<((ImmutableList<UiMedia>, Int) -> Unit)?> = compositionLocalOf { null }

@Composable
private fun InstagramMediaGallery(items: ImmutableList<UiMedia>) {
    val uriHandler = LocalUriHandler.current
    val openGallery = LocalOpenRssMedia.current
    val ratio = when (val first = items.first()) {
        is UiMedia.Image -> first.aspectRatio
        is UiMedia.Video -> first.aspectRatio
        else -> 1f
    }
    val frame = Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.75f, 2.4f)).clip(PlatformTheme.shapes.medium)
    val group = remember(items) { Any() }
    val showItem: @Composable (Int, Boolean) -> Unit = { index, selected ->
        val item = items[index]
        CompositionLocalProvider(LocalTimelineCarouselItem provides TimelineCarouselItem(group, index, selected, items.size > 1)) {
            MediaItem(
                media = item,
                modifier =
                    Modifier.fillMaxSize().clickable {
                        if (openGallery != null) {
                            openGallery(items, index)
                        } else if (item is UiMedia.Image) {
                            uriHandler.openUri(DeeplinkRoute.Media.Image(item.url, item.previewUrl, item.customHeaders, item.proxy).toUri())
                        } else {
                            uriHandler.openUri(item.url)
                        }
                    },
                keepAspectRatio = false,
                contentScale = ContentScale.Fit,
            )
        }
    }
    if (items.size == 1) {
        Box(frame) { showItem(0, true) }
    } else {
        val pager = rememberPagerState { items.size }
        Box(frame) {
            HorizontalPager(state = pager, modifier = Modifier.matchParentSize(), key = { items[it].url }) { index ->
                showItem(index, index == pager.currentPage)
            }
            PlatformText(
                text = "${pager.currentPage + 1}/${items.size}",
                style = PlatformTheme.typography.caption,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(PlatformTheme.shapes.small)
                        .background(PlatformTheme.colorScheme.card)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}
