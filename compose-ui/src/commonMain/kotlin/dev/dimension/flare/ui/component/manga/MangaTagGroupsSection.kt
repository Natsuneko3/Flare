package dev.dimension.flare.ui.component.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dimension.flare.data.datasource.microblog.ActionMenu
import dev.dimension.flare.data.datasource.microblog.PostActionLayoutConfig
import dev.dimension.flare.data.model.PostActionStyle
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.component.platform.PlatformText
import dev.dimension.flare.ui.component.platform.PlatformTextStyle
import dev.dimension.flare.ui.component.resolveText
import dev.dimension.flare.ui.component.status.StatusActions
import dev.dimension.flare.ui.model.ClickContext
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.UiMangaTag
import dev.dimension.flare.ui.model.UiMangaTagGroup
import dev.dimension.flare.ui.theme.PlatformTheme
import kotlinx.collections.immutable.ImmutableList

private val MangaTagChipShape = RoundedCornerShape(16.dp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun MangaTagGroupsSection(
    tagGroups: ImmutableList<UiMangaTagGroup>,
    modifier: Modifier = Modifier,
    onTagClick: ((UiMangaTag) -> Unit)? = null,
) {
    val nonEmptyGroups = remember(tagGroups) { tagGroups.filter { it.tags.isNotEmpty() } }
    if (nonEmptyGroups.isEmpty()) return

    val uriHandler = LocalUriHandler.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        nonEmptyGroups.forEach { group ->
            val groupTitle = group.title?.resolveText()?.takeIf { it.isNotBlank() }
            if (groupTitle == null) {
                MangaTagChipsFlowRow(
                    tags = group.tags,
                    onTagClick = { tag ->
                        if (onTagClick != null) {
                            onTagClick(tag)
                        } else {
                            tag.onClicked.invoke(ClickContext(uriHandler::openUri))
                        }
                    },
                    hasCustomHandler = onTagClick != null,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    PlatformText(
                        text = groupTitle,
                        style = PlatformTheme.typography.caption,
                        fontWeight = FontWeight.SemiBold,
                        color = PlatformTheme.colorScheme.caption,
                        modifier =
                            Modifier
                                .widthIn(min = 56.dp)
                                .padding(top = 5.dp),
                    )
                    MangaTagChipsFlowRow(
                        tags = group.tags,
                        onTagClick = { tag ->
                            if (onTagClick != null) {
                                onTagClick(tag)
                            } else {
                                tag.onClicked.invoke(ClickContext(uriHandler::openUri))
                            }
                        },
                        hasCustomHandler = onTagClick != null,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MangaTagChipsFlowRow(
    tags: ImmutableList<UiMangaTag>,
    onTagClick: (UiMangaTag) -> Unit,
    hasCustomHandler: Boolean,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tags.forEach { tag ->
            val clickable = hasCustomHandler || tag.clickEvent != ClickEvent.Noop
            Box(
                modifier =
                    Modifier
                        .clip(MangaTagChipShape)
                        .background(PlatformTheme.colorScheme.primaryContainer.copy(alpha = 0.65f))
                        .let { base ->
                            if (clickable) {
                                base.clickable { onTagClick(tag) }
                            } else {
                                base
                            }
                        }.padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                PlatformText(
                    text = tag.name,
                    style = PlatformTheme.typography.caption,
                    color = PlatformTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
public fun MangaActionsMenu(
    actions: ImmutableList<ActionMenu>,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return
    CompositionLocalProvider(
        LocalTimelineAppearance provides
            LocalTimelineAppearance.current.copy(
                postActionStyle = PostActionStyle.RightAligned,
                postActionLayout = PostActionLayoutConfig.Default,
            ),
        PlatformTextStyle provides PlatformTheme.typography.title,
    ) {
        StatusActions(actions, modifier = modifier, isPost = false)
    }
}
