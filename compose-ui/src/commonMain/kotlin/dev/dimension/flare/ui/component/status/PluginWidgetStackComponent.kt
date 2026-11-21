package dev.dimension.flare.ui.component.status

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import coil3.compose.AsyncImage
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.ArrowRotateRight
import compose.icons.fontawesomeicons.solid.ChevronDown
import compose.icons.fontawesomeicons.solid.Copy
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.ui.component.FAIcon
import dev.dimension.flare.ui.component.FlareDividerDefaults
import dev.dimension.flare.ui.component.HorizontalDivider
import dev.dimension.flare.ui.component.NetworkImage
import dev.dimension.flare.ui.component.platform.PlatformCircularProgressIndicator
import dev.dimension.flare.ui.component.platform.PlatformText
import dev.dimension.flare.ui.component.platform.plainTextClipEntry
import dev.dimension.flare.ui.model.UiPluginWidgetCard
import dev.dimension.flare.ui.model.UiWidgetAction
import dev.dimension.flare.ui.model.UiWidgetAlign
import dev.dimension.flare.ui.model.UiWidgetBadge
import dev.dimension.flare.ui.model.UiWidgetButton
import dev.dimension.flare.ui.model.UiWidgetButtonStyle
import dev.dimension.flare.ui.model.UiWidgetCornerStyle
import dev.dimension.flare.ui.model.UiWidgetGap
import dev.dimension.flare.ui.model.UiWidgetNode
import dev.dimension.flare.ui.model.UiWidgetSparklineHeight
import dev.dimension.flare.ui.model.UiWidgetTextStyle
import dev.dimension.flare.ui.model.UiWidgetTone
import dev.dimension.flare.ui.model.renderSummaryHash
import dev.dimension.flare.ui.model.toUiImage
import dev.dimension.flare.ui.theme.PlatformTheme
import dev.dimension.flare.ui.theme.isLightTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath

private val WidgetCardShape = RoundedCornerShape(12.dp)
private val WidgetCodeShape = RoundedCornerShape(8.dp)
private val WidgetImageRoundedShape = RoundedCornerShape(8.dp)
private val WidgetButtonShape = RoundedCornerShape(8.dp)
private val WidgetIconShape = RoundedCornerShape(4.dp)

@Composable
internal fun PluginWidgetStackComponent(
    cards: ImmutableList<UiPluginWidgetCard>,
    modifier: Modifier = Modifier,
) {
    if (cards.isEmpty()) return

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag("plugin_widget_stack"),
    ) {
        if (cards.size == 1) {
            PluginWidgetCardSurface(
                card = cards[0],
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            PluginWidgetMultiCardPager(cards = cards)
        }
    }
}

@Composable
private fun PluginWidgetMultiCardPager(cards: ImmutableList<UiPluginWidgetCard>) {
    val pagerState = rememberPagerState(pageCount = { cards.size })
    val heightCache = remember { StackHeightCache() }
    val cardsSummaryHash =
        remember(cards) {
            var hash = 17
            cards.fastForEach { card ->
                hash = 31 * hash + card.renderSummaryHash()
            }
            hash
        }

    SubcomposeLayout(modifier = Modifier.fillMaxWidth()) { constraints ->
        val targetWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else -2
        val maxCardHeightPx =
            heightCache.getOrPut(
                isLookingAhead = isLookingAhead,
                summaryHash = cardsSummaryHash,
                widthPx = targetWidth,
            ) {
                val measureConstraints =
                    if (constraints.hasBoundedWidth) {
                        constraints.copy(
                            minWidth = constraints.maxWidth,
                            maxWidth = constraints.maxWidth,
                            minHeight = 0,
                            maxHeight = Constraints.Infinity,
                        )
                    } else {
                        constraints.copy(
                            minHeight = 0,
                            maxHeight = Constraints.Infinity,
                        )
                    }
                var tallest = 0
                cards.fastForEachIndexed { index, card ->
                    val measurables =
                        subcompose("measure_${card.pluginId}_${card.id}_$index") {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clearAndSetSemantics {},
                            ) {
                                PluginWidgetCardSurface(
                                    card = card,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    measurables.fastForEach { measurable ->
                        val placeable = measurable.measure(measureConstraints)
                        if (placeable.height > tallest) {
                            tallest = placeable.height
                        }
                    }
                }
                tallest
            }
        val maxCardHeightDp = maxCardHeightPx.toDp()

        val pagerPlaceables =
            subcompose("pager") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HorizontalPager(
                        state = pagerState,
                        pageSpacing = 8.dp,
                        verticalAlignment = Alignment.Top,
                        key = { index -> "${cards[index].pluginId}:${cards[index].id}:$index" },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(maxCardHeightDp)
                                .consumeHorizontalDragForPager(pagerState)
                                .testTag("plugin_widget_pager"),
                    ) { page ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.TopStart,
                        ) {
                            PluginWidgetCardSurface(
                                card = cards[page],
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.testTag("plugin_widget_dots"),
                    ) {
                        repeat(cards.size) { index ->
                            val selected = pagerState.currentPage == index
                            Box(
                                modifier =
                                    Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (selected) {
                                                PlatformTheme.colorScheme.primary
                                            } else {
                                                StatusComponentDefaults.secondaryColor.copy(alpha = 0.35f)
                                            },
                                        ),
                            )
                        }
                    }
                }
            }.map { measurable ->
                measurable.measure(constraints.copy(minHeight = 0))
            }

        val layoutWidth =
            if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                pagerPlaceables.maxOfOrNull { it.width } ?: constraints.minWidth
            }
        val layoutHeight =
            (pagerPlaceables.maxOfOrNull { it.height } ?: 0)
                .coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(layoutWidth, layoutHeight) {
            pagerPlaceables.fastForEach { placeable ->
                placeable.placeRelative(0, 0)
            }
        }
    }
}

private class StackHeightCache {
    class Entry {
        var summaryHash: Int = 0
        var widthPx: Int = -1
        var maxHeightPx: Int = 0
    }

    private val current = Entry()
    private val lookahead = Entry()

    fun getOrPut(
        isLookingAhead: Boolean,
        summaryHash: Int,
        widthPx: Int,
        compute: () -> Int,
    ): Int {
        val entry = if (isLookingAhead) lookahead else current
        if (entry.summaryHash == summaryHash && entry.widthPx == widthPx && entry.maxHeightPx > 0) {
            return entry.maxHeightPx
        }
        val measured = compute()
        entry.summaryHash = summaryHash
        entry.widthPx = widthPx
        entry.maxHeightPx = measured
        return measured
    }
}

private fun Modifier.consumeHorizontalDragForPager(pagerState: PagerState): Modifier =
    nestedScroll(
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val canScrollInDirection =
                    (available.x < 0f && pagerState.canScrollForward) ||
                        (available.x > 0f && pagerState.canScrollBackward)
                return if (consumed.x != 0f || canScrollInDirection) {
                    Offset(available.x, 0f)
                } else {
                    Offset.Zero
                }
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity {
                val canScrollInDirection =
                    (available.x < 0f && pagerState.canScrollForward) ||
                        (available.x > 0f && pagerState.canScrollBackward)
                return if (consumed.x != 0f || canScrollInDirection) {
                    Velocity(available.x, 0f)
                } else {
                    Velocity.Zero
                }
            }
        },
    )

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PluginWidgetCardSurface(
    card: UiPluginWidgetCard,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    val executeAction: (UiWidgetAction) -> Unit = { action ->
        when (action) {
            is UiWidgetAction.OpenUrl -> {
                if (action.url.startsWith("https://", ignoreCase = true)) {
                    uriHandler.openUri(action.url)
                }
            }

            is UiWidgetAction.Copy -> {
                scope.launch {
                    clipboard.setClipEntry(plainTextClipEntry(action.text, action.label))
                }
            }

            UiWidgetAction.Refresh -> {
                card.onRefresh?.invoke()
            }
        }
    }

    val hostBackground = LocalStatusBackgroundColor.current ?: PlatformTheme.colorScheme.background
    val surfaceColor =
        PlatformTheme.colorScheme.cardAlt
            .copy(alpha = 0.55f)
            .compositeOver(hostBackground)
    val borderColor =
        if (card.accentTone == UiWidgetTone.Neutral) {
            FlareDividerDefaults.color
        } else {
            widgetToneColor(card.accentTone).copy(alpha = 0.42f)
        }

    val cardClickModifier =
        card.cardAction?.let { action ->
            Modifier
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { executeAction(action) }
        } ?: Modifier

    Column(
        modifier =
            modifier
                .clip(WidgetCardShape)
                .background(surfaceColor, WidgetCardShape)
                .border(1.dp, borderColor, WidgetCardShape)
                .then(cardClickModifier)
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PluginWidgetCardHeader(card = card)

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            card.body.fastForEach { node ->
                PluginWidgetNodeContent(
                    node = node,
                    onAction = executeAction,
                )
            }
        }

        if (card.actions.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                card.actions.fastForEach { button ->
                    WidgetActionButton(
                        button = button,
                        onClick = { executeAction(button.action) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginWidgetCardHeader(card: UiPluginWidgetCard) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val iconPath = card.pluginIconPath?.takeIf { it.isNotBlank() }
        if (iconPath != null) {
            val iconModel =
                remember(iconPath) {
                    if ("://" in iconPath) iconPath else iconPath.toPath()
                }
            AsyncImage(
                model = iconModel,
                contentDescription = card.pluginName,
                contentScale = ContentScale.Fit,
                modifier =
                    Modifier
                        .size(16.dp)
                        .clip(WidgetIconShape),
            )
        }
        PlatformText(
            text = card.title.ifBlank { card.pluginName },
            style = PlatformTheme.typography.postBody,
            fontWeight = FontWeight.SemiBold,
            color = StatusComponentDefaults.contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        card.badge?.let { badge ->
            WidgetBadgePill(badge = badge)
        }
        Spacer(modifier = Modifier.weight(1f))
        if (card.refreshable || card.isRefreshing) {
            if (card.isRefreshing) {
                Box(
                    modifier = Modifier.size(22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    PlatformCircularProgressIndicator(
                        modifier =
                            Modifier
                                .size(13.dp)
                                .testTag("plugin_widget_refresh_spinner"),
                    )
                }
            } else {
                Box(
                    modifier =
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(
                                enabled = card.onRefresh != null,
                                role = Role.Button,
                            ) {
                                card.onRefresh?.invoke()
                            }.semantics {
                                contentDescription = "Refresh"
                            }.testTag("plugin_widget_refresh_button"),
                    contentAlignment = Alignment.Center,
                ) {
                    FAIcon(
                        imageVector = FontAwesomeIcons.Solid.ArrowRotateRight,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = StatusComponentDefaults.secondaryColor,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginWidgetNodeContent(
    node: UiWidgetNode,
    onAction: (UiWidgetAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (node) {
        is UiWidgetNode.Text -> {
            WidgetTextNode(
                node = node,
                onCopy = { text -> onAction(UiWidgetAction.Copy(text)) },
                modifier = modifier,
            )
        }

        is UiWidgetNode.Row -> {
            val gap = node.gap.toDp()
            val hasWeightedChild =
                node.children.any { child ->
                    val w = child.weight
                    w != null && w > 0f && w.isFinite()
                }
            val arrangement =
                when (node.align) {
                    UiWidgetAlign.Start -> {
                        Arrangement.spacedBy(gap, Alignment.Start)
                    }

                    UiWidgetAlign.Center -> {
                        Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
                    }

                    UiWidgetAlign.End -> {
                        Arrangement.spacedBy(gap, Alignment.End)
                    }

                    UiWidgetAlign.SpaceBetween -> {
                        if (node.children.size > 1 && !hasWeightedChild) {
                            Arrangement.SpaceBetween
                        } else {
                            Arrangement.spacedBy(gap)
                        }
                    }
                }
            Row(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = arrangement,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                node.children.fastForEach { child ->
                    val weight = child.weight
                    val childModifier =
                        if (weight != null && weight > 0f && weight.isFinite()) {
                            Modifier.weight(weight)
                        } else {
                            Modifier
                        }
                    PluginWidgetNodeContent(
                        node = child,
                        onAction = onAction,
                        modifier = childModifier,
                    )
                }
            }
        }

        is UiWidgetNode.Column -> {
            val alignment =
                when (node.align) {
                    UiWidgetAlign.Start, UiWidgetAlign.SpaceBetween -> Alignment.Start
                    UiWidgetAlign.Center -> Alignment.CenterHorizontally
                    UiWidgetAlign.End -> Alignment.End
                }
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(node.gap.toDp()),
                horizontalAlignment = alignment,
            ) {
                node.children.fastForEach { child ->
                    PluginWidgetNodeContent(
                        node = child,
                        onAction = onAction,
                    )
                }
            }
        }

        is UiWidgetNode.Stat -> {
            WidgetStatNode(node = node, modifier = modifier)
        }

        is UiWidgetNode.Matchup -> {
            WidgetMatchupNode(node = node, modifier = modifier)
        }

        is UiWidgetNode.Sparkline -> {
            WidgetSparklineNode(node = node, modifier = modifier)
        }

        is UiWidgetNode.SegmentedBar -> {
            WidgetSegmentedBarNode(node = node, modifier = modifier)
        }

        is UiWidgetNode.KeyValue -> {
            WidgetKeyValueNode(
                node = node,
                onAction = onAction,
                modifier = modifier,
            )
        }

        is UiWidgetNode.BadgeRow -> {
            FlowRow(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                node.badges.fastForEach { badge ->
                    WidgetBadgePill(badge = badge)
                }
            }
        }

        is UiWidgetNode.Image -> {
            WidgetImageNode(node = node, modifier = modifier)
        }

        is UiWidgetNode.Collapsible -> {
            WidgetCollapsibleNode(
                node = node,
                onAction = onAction,
                modifier = modifier,
            )
        }

        UiWidgetNode.Divider -> {
            HorizontalDivider(modifier = modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun WidgetTextNode(
    node: UiWidgetNode.Text,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxLines = node.maxLines?.coerceAtLeast(1) ?: Int.MAX_VALUE
    val color =
        when {
            node.tone != UiWidgetTone.Neutral -> widgetToneColor(node.tone)
            node.style == UiWidgetTextStyle.Caption -> StatusComponentDefaults.secondaryColor
            else -> StatusComponentDefaults.contentColor
        }

    if (node.style == UiWidgetTextStyle.Code) {
        val codeModifier =
            if (node.copyable) {
                Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { onCopy(node.text) }
            } else {
                Modifier
            }
        Row(
            modifier =
                modifier
                    .fillMaxWidth()
                    .clip(WidgetCodeShape)
                    .background(StatusComponentDefaults.contentColor.copy(alpha = 0.06f), WidgetCodeShape)
                    .border(1.dp, FlareDividerDefaults.color, WidgetCodeShape)
                    .then(codeModifier)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlatformText(
                text = node.text,
                style = PlatformTheme.typography.caption,
                fontFamily = FontFamily.Monospace,
                color = color,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (node.copyable) {
                FAIcon(
                    imageVector = FontAwesomeIcons.Solid.Copy,
                    contentDescription = "Copy",
                    modifier = Modifier.padding(top = 2.dp).size(12.dp),
                    tint = StatusComponentDefaults.secondaryColor,
                )
            }
        }
        return
    }

    val baseBody = PlatformTheme.typography.postBody
    val style =
        when (node.style) {
            UiWidgetTextStyle.Title -> {
                baseBody.copy(
                    fontSize = (baseBody.fontSize.value + 1f).sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            UiWidgetTextStyle.Subtitle -> {
                baseBody.copy(fontWeight = FontWeight.SemiBold)
            }

            UiWidgetTextStyle.Body -> {
                baseBody
            }

            UiWidgetTextStyle.Caption -> {
                PlatformTheme.typography.caption
            }

            UiWidgetTextStyle.Code -> {
                PlatformTheme.typography.caption
            }
        }

    if (node.copyable) {
        Row(
            modifier =
                modifier
                    .clip(RoundedCornerShape(4.dp))
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { onCopy(node.text) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PlatformText(
                text = node.text,
                style = style,
                color = color,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            FAIcon(
                imageVector = FontAwesomeIcons.Solid.Copy,
                contentDescription = "Copy",
                modifier = Modifier.size(11.dp),
                tint = StatusComponentDefaults.secondaryColor,
            )
        }
    } else {
        PlatformText(
            text = node.text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    }
}

@Composable
private fun WidgetStatNode(
    node: UiWidgetNode.Stat,
    modifier: Modifier = Modifier,
) {
    val baseBody = PlatformTheme.typography.postBody
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PlatformText(
            text = node.label,
            style = PlatformTheme.typography.caption,
            color = StatusComponentDefaults.secondaryColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PlatformText(
                text = node.value,
                style = baseBody,
                fontSize = (baseBody.fontSize.value + 4f).sp,
                fontWeight = FontWeight.Bold,
                color = StatusComponentDefaults.contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            node.delta?.takeIf { it.isNotBlank() }?.let { delta ->
                PlatformText(
                    text = delta,
                    style = PlatformTheme.typography.caption,
                    fontWeight = FontWeight.SemiBold,
                    color = widgetToneColor(node.deltaTone),
                    maxLines = 1,
                )
            }
        }
        node.subtext?.takeIf { it.isNotBlank() }?.let { subtext ->
            PlatformText(
                text = subtext,
                style = PlatformTheme.typography.caption,
                color = StatusComponentDefaults.secondaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WidgetMatchupNode(
    node: UiWidgetNode.Matchup,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MatchupSideContent(
            side = node.left,
            alignEnd = false,
            modifier = Modifier.weight(1f),
        )
        val centerText = node.status?.takeIf { it.isNotBlank() } ?: "vs"
        val centerColor =
            if (node.status != null && node.statusTone != UiWidgetTone.Neutral) {
                widgetToneColor(node.statusTone)
            } else {
                StatusComponentDefaults.secondaryColor
            }
        PlatformText(
            text = centerText,
            style = PlatformTheme.typography.caption,
            fontWeight = FontWeight.SemiBold,
            color = centerColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        MatchupSideContent(
            side = node.right,
            alignEnd = true,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MatchupSideContent(
    side: UiWidgetNode.MatchupSide,
    alignEnd: Boolean,
    modifier: Modifier = Modifier,
) {
    val baseBody = PlatformTheme.typography.postBody
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement =
            if (alignEnd) {
                Arrangement.spacedBy(6.dp, Alignment.End)
            } else {
                Arrangement.spacedBy(6.dp, Alignment.Start)
            },
    ) {
        if (alignEnd) {
            side.score?.takeIf { it.isNotBlank() }?.let { score ->
                PlatformText(
                    text = score,
                    style = baseBody,
                    fontSize = (baseBody.fontSize.value + 3f).sp,
                    fontWeight = FontWeight.Bold,
                    color = StatusComponentDefaults.contentColor,
                    maxLines = 1,
                )
            }
        }
        val iconUrl = side.iconUrl?.takeIf { it.isNotBlank() }
        if (!alignEnd && iconUrl != null) {
            NetworkImage(
                model = remember(iconUrl) { iconUrl.toUiImage(proxy = ProxyRef.Global) },
                contentDescription = side.name,
                contentScale = ContentScale.Fit,
                modifier =
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape),
            )
        }
        Column(
            modifier = Modifier.weight(1f, fill = false),
            horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
        ) {
            PlatformText(
                text = side.name,
                style = baseBody,
                fontWeight = FontWeight.SemiBold,
                color = StatusComponentDefaults.contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            side.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                PlatformText(
                    text = subtitle,
                    style = PlatformTheme.typography.caption,
                    color = StatusComponentDefaults.secondaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (alignEnd && iconUrl != null) {
            NetworkImage(
                model = remember(iconUrl) { iconUrl.toUiImage(proxy = ProxyRef.Global) },
                contentDescription = side.name,
                contentScale = ContentScale.Fit,
                modifier =
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape),
            )
        }
        if (!alignEnd) {
            side.score?.takeIf { it.isNotBlank() }?.let { score ->
                PlatformText(
                    text = score,
                    style = baseBody,
                    fontSize = (baseBody.fontSize.value + 3f).sp,
                    fontWeight = FontWeight.Bold,
                    color = StatusComponentDefaults.contentColor,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun WidgetSparklineNode(
    node: UiWidgetNode.Sparkline,
    modifier: Modifier = Modifier,
) {
    val normalized = node.normalizedPoints
    val chartHeight =
        when (node.height) {
            UiWidgetSparklineHeight.Sm -> 32.dp
            UiWidgetSparklineHeight.Md -> 52.dp
        }
    val lineColor =
        if (node.tone == UiWidgetTone.Neutral) {
            PlatformTheme.colorScheme.primary
        } else {
            widgetToneColor(node.tone)
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(chartHeight),
        ) {
            if (normalized.isEmpty() || size.width <= 0f || size.height <= 0f) {
                return@Canvas
            }
            val strokePx = 2.dp.toPx()
            val inset = strokePx
            val usableHeight = (size.height - inset * 2f).coerceAtLeast(0f)

            fun yFor(norm: Float): Float {
                val clamped = if (norm.isFinite()) norm.coerceIn(0f, 1f) else 0.5f
                return inset + (1f - clamped) * usableHeight
            }

            if (normalized.size == 1) {
                val y = yFor(normalized[0])
                drawLine(
                    color = lineColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round,
                )
                return@Canvas
            }

            val stepX = size.width / (normalized.size - 1).toFloat()
            val strokePath = Path()
            val fillPath = Path()

            normalized.fastForEachIndexed { index, value ->
                val x = index * stepX
                val y = yFor(value)
                if (index == 0) {
                    strokePath.moveTo(x, y)
                    fillPath.moveTo(x, size.height)
                    fillPath.lineTo(x, y)
                } else {
                    strokePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo(size.width, size.height)
            fillPath.close()

            drawPath(
                path = fillPath,
                brush =
                    Brush.verticalGradient(
                        colors = listOf(lineColor.copy(alpha = 0.16f), Color.Transparent),
                        startY = 0f,
                        endY = size.height,
                    ),
            )
            drawPath(
                path = strokePath,
                color = lineColor,
                style =
                    Stroke(
                        width = strokePx,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
            )
        }
        if (!node.minLabel.isNullOrBlank() || !node.maxLabel.isNullOrBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PlatformText(
                    text = node.minLabel.orEmpty(),
                    style = PlatformTheme.typography.caption,
                    color = StatusComponentDefaults.secondaryColor,
                    maxLines = 1,
                )
                PlatformText(
                    text = node.maxLabel.orEmpty(),
                    style = PlatformTheme.typography.caption,
                    color = StatusComponentDefaults.secondaryColor,
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WidgetSegmentedBarNode(
    node: UiWidgetNode.SegmentedBar,
    modifier: Modifier = Modifier,
) {
    val fractions = node.normalizedFractions
    val segmentColors =
        node.segments.mapIndexed { index, segment ->
            when {
                segment.tone != UiWidgetTone.Neutral -> widgetToneColor(segment.tone)
                index == 0 -> PlatformTheme.colorScheme.primary
                else -> StatusComponentDefaults.secondaryColor.copy(alpha = 0.55f)
            }
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape),
        ) {
            if (fractions.isEmpty() || size.width <= 0f || size.height <= 0f) {
                return@Canvas
            }
            val gapPx = if (fractions.size > 1) 2.dp.toPx() else 0f
            val totalGap = gapPx * (fractions.size - 1)
            val availableWidth = (size.width - totalGap).coerceAtLeast(0f)
            var currentX = 0f
            fractions.fastForEachIndexed { index, fraction ->
                val safeFraction = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
                val segWidth = availableWidth * safeFraction
                if (segWidth > 0f) {
                    drawRoundRect(
                        color = segmentColors[index],
                        topLeft = Offset(currentX, 0f),
                        size = Size(segWidth, size.height),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
                currentX += segWidth + gapPx
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            node.segments.fastForEachIndexed { index, segment ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(segmentColors[index]),
                    )
                    val labelText =
                        if (!segment.valueText.isNullOrBlank()) {
                            "${segment.label} · ${segment.valueText}"
                        } else {
                            segment.label
                        }
                    PlatformText(
                        text = labelText,
                        style = PlatformTheme.typography.caption,
                        color = StatusComponentDefaults.contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun WidgetKeyValueNode(
    node: UiWidgetNode.KeyValue,
    onAction: (UiWidgetAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        node.items.fastForEach { item ->
            val valueColor =
                if (item.tone == UiWidgetTone.Neutral) {
                    if (item.action is UiWidgetAction.OpenUrl) {
                        PlatformTheme.colorScheme.primary
                    } else {
                        StatusComponentDefaults.contentColor
                    }
                } else {
                    widgetToneColor(item.tone)
                }
            val rowClickModifier =
                item.action?.let { action ->
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { onAction(action) }
                        .padding(vertical = 2.dp)
                } ?: Modifier

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(rowClickModifier),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlatformText(
                    text = item.label,
                    style = PlatformTheme.typography.caption,
                    color = StatusComponentDefaults.secondaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PlatformText(
                        text = item.value,
                        style = PlatformTheme.typography.postBody,
                        fontWeight = FontWeight.Medium,
                        color = valueColor,
                        textDecoration =
                            if (item.action is UiWidgetAction.OpenUrl) {
                                TextDecoration.Underline
                            } else {
                                null
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.action is UiWidgetAction.Copy) {
                        FAIcon(
                            imageVector = FontAwesomeIcons.Solid.Copy,
                            contentDescription = null,
                            modifier = Modifier.size(10.dp),
                            tint = StatusComponentDefaults.secondaryColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetImageNode(
    node: UiWidgetNode.Image,
    modifier: Modifier = Modifier,
) {
    val shape =
        when (node.cornerStyle) {
            UiWidgetCornerStyle.Rounded -> WidgetImageRoundedShape
            UiWidgetCornerStyle.Circle -> CircleShape
        }
    val defaultRatio = if (node.cornerStyle == UiWidgetCornerStyle.Circle) 1f else 16f / 9f
    val safeRatio = node.aspectRatio?.takeIf { it.isFinite() && it > 0f } ?: defaultRatio
    NetworkImage(
        model = remember(node.url) { node.url.toUiImage(proxy = ProxyRef.Global) },
        contentDescription = node.alt,
        contentScale = ContentScale.Crop,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .aspectRatio(safeRatio)
                .clip(shape),
    )
}

@Composable
private fun WidgetCollapsibleNode(
    node: UiWidgetNode.Collapsible,
    onAction: (UiWidgetAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(role = Role.Button) {
                        node.onToggle?.invoke()
                    }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FAIcon(
                imageVector = FontAwesomeIcons.Solid.ChevronDown,
                contentDescription = null,
                modifier =
                    Modifier
                        .size(10.dp)
                        .rotate(if (node.expanded) 0f else -90f),
                tint = StatusComponentDefaults.secondaryColor,
            )
            PlatformText(
                text = node.summary,
                style = PlatformTheme.typography.postBody,
                fontWeight = FontWeight.Medium,
                color = StatusComponentDefaults.contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (node.expanded) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                node.children.fastForEach { child ->
                    PluginWidgetNodeContent(
                        node = child,
                        onAction = onAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun WidgetBadgePill(
    badge: UiWidgetBadge,
    modifier: Modifier = Modifier,
) {
    val foreground =
        if (badge.tone == UiWidgetTone.Neutral) {
            StatusComponentDefaults.secondaryColor
        } else {
            widgetToneColor(badge.tone)
        }
    val background = foreground.copy(alpha = 0.14f)
    Box(
        modifier =
            modifier
                .clip(CircleShape)
                .background(background, CircleShape)
                .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        PlatformText(
            text = badge.text,
            style = PlatformTheme.typography.caption,
            fontWeight = FontWeight.SemiBold,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WidgetActionButton(
    button: UiWidgetButton,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isPrimary = button.style == UiWidgetButtonStyle.Primary
    val toneColor =
        if (button.tone == UiWidgetTone.Neutral) {
            if (isPrimary) PlatformTheme.colorScheme.primary else StatusComponentDefaults.contentColor
        } else {
            widgetToneColor(button.tone)
        }
    val backgroundColor =
        if (isPrimary) {
            toneColor
        } else {
            toneColor.copy(alpha = if (button.tone == UiWidgetTone.Neutral) 0.06f else 0.14f)
        }
    val contentColor =
        if (isPrimary) {
            if (button.tone == UiWidgetTone.Neutral || button.tone == UiWidgetTone.Accent) {
                PlatformTheme.colorScheme.onPrimaryContainer.takeIf { it != Color.Unspecified } ?: Color.White
            } else {
                Color.White
            }
        } else {
            toneColor
        }
    val borderModifier =
        if (isPrimary) {
            Modifier
        } else {
            val strokeColor =
                if (button.tone == UiWidgetTone.Neutral) {
                    FlareDividerDefaults.color
                } else {
                    toneColor.copy(alpha = 0.32f)
                }
            Modifier.border(1.dp, strokeColor, WidgetButtonShape)
        }

    Box(
        modifier =
            modifier
                .clip(WidgetButtonShape)
                .background(backgroundColor, WidgetButtonShape)
                .then(borderModifier)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        PlatformText(
            text = button.label,
            style = PlatformTheme.typography.caption,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun widgetToneColor(tone: UiWidgetTone): Color =
    when (tone) {
        UiWidgetTone.Neutral -> StatusComponentDefaults.contentColor
        UiWidgetTone.Accent -> PlatformTheme.colorScheme.primary
        UiWidgetTone.Positive -> if (isLightTheme()) Color(0xFF1B7F3B) else Color(0xFF3FB950)
        UiWidgetTone.Negative -> PlatformTheme.colorScheme.error
        UiWidgetTone.Warning -> if (isLightTheme()) Color(0xFF9A6700) else Color(0xFFD29922)
    }

private fun UiWidgetGap.toDp(): Dp =
    when (this) {
        UiWidgetGap.Xs -> 4.dp
        UiWidgetGap.Sm -> 8.dp
        UiWidgetGap.Md -> 12.dp
    }
