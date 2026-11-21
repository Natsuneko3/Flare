package dev.dimension.flare.ui.component.manga

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.BookOpen
import compose.icons.fontawesomeicons.solid.Lock
import compose.icons.fontawesomeicons.solid.RotateRight
import dev.dimension.flare.compose.ui.Res
import dev.dimension.flare.compose.ui.manga_boundary_next_chapter
import dev.dimension.flare.compose.ui.manga_boundary_previous_chapter
import dev.dimension.flare.compose.ui.manga_boundary_read_next
import dev.dimension.flare.compose.ui.manga_boundary_read_previous
import dev.dimension.flare.compose.ui.manga_locked_chapter_message
import dev.dimension.flare.compose.ui.manga_locked_chapter_title
import dev.dimension.flare.compose.ui.manga_open_in_browser
import dev.dimension.flare.compose.ui.manga_page_retry
import dev.dimension.flare.data.network.ProxyNetwork
import dev.dimension.flare.ui.component.ErrorContent
import dev.dimension.flare.ui.component.FAIcon
import dev.dimension.flare.ui.component.LocalNetworkImageAllowHardware
import dev.dimension.flare.ui.component.accessibleDescription
import dev.dimension.flare.ui.component.applyAllowHardware
import dev.dimension.flare.ui.component.media
import dev.dimension.flare.ui.component.placeholder
import dev.dimension.flare.ui.component.platform.PlatformButton
import dev.dimension.flare.ui.component.platform.PlatformCard
import dev.dimension.flare.ui.component.platform.PlatformCircularProgressIndicator
import dev.dimension.flare.ui.component.platform.PlatformFilledTonalButton
import dev.dimension.flare.ui.component.platform.PlatformText
import dev.dimension.flare.ui.component.rememberMediaImageRequest
import dev.dimension.flare.ui.model.MangaReadingDirection
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.toUiImage
import dev.dimension.flare.ui.presenter.manga.ChapterBoundary
import dev.dimension.flare.ui.presenter.manga.MangaReaderPresenter
import dev.dimension.flare.ui.presenter.manga.PageMediaStatus
import dev.dimension.flare.ui.presenter.manga.ReaderPageState
import dev.dimension.flare.ui.presenter.manga.ReaderSlot
import dev.dimension.flare.ui.route.DeeplinkRoute
import dev.dimension.flare.ui.route.toUri
import dev.dimension.flare.ui.theme.PlatformTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

private const val WIDE_VIEWPORT_BREAKPOINT_DP = 720
private const val LEFT_TAP_ZONE_FRACTION = 0.3f
private const val RIGHT_TAP_ZONE_FRACTION = 0.7f
private const val MAX_READER_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val ZOOM_EPSILON = 1.005f
private const val DEFAULT_WEBTOON_ASPECT_RATIO = 0.7f

@Composable
public fun MangaReaderCanvas(
    state: MangaReaderPresenter.State,
    hudVisible: Boolean,
    onToggleHud: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val currentState by rememberUpdatedState(state)
    val currentHudVisible by rememberUpdatedState(hudVisible)
    val currentOnToggleHud by rememberUpdatedState(onToggleHud)
    val currentOnBack by rememberUpdatedState(onBack)

    var activeZoomReset by remember { mutableStateOf<(() -> Unit)?>(null) }
    var activeWebtoonListState by remember { mutableStateOf<LazyListState?>(null) }
    var viewportHeightPx by remember { mutableFloatStateOf(0f) }

    val openExternalUrl: (String) -> Unit =
        remember(uriHandler) {
            { url ->
                if (url.isNotBlank()) {
                    uriHandler.openUri(DeeplinkRoute.OpenLinkDirectly(url).toUri())
                }
            }
        }

    PrefetchReaderMediaEffect(state = state)

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .clipToBounds()
                .focusRequester(focusRequester)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val readerState = currentState
                    when (event.key) {
                        Key.DirectionLeft -> {
                            if (readerState.direction == MangaReadingDirection.Rtl) {
                                readerState.stepForward()
                            } else {
                                readerState.stepBackward()
                            }
                            true
                        }

                        Key.DirectionRight -> {
                            if (readerState.direction == MangaReadingDirection.Rtl) {
                                readerState.stepBackward()
                            } else {
                                readerState.stepForward()
                            }
                            true
                        }

                        Key.DirectionDown -> {
                            val listState = activeWebtoonListState
                            if (readerState.direction == MangaReadingDirection.Webtoon && listState != null) {
                                if (listState.canScrollForward) {
                                    val delta = (viewportHeightPx * 0.8f).coerceAtLeast(200f)
                                    scope.launch { listState.animateScrollBy(delta) }
                                } else {
                                    readerState.stepForward()
                                }
                            } else {
                                readerState.stepForward()
                            }
                            true
                        }

                        Key.DirectionUp -> {
                            val listState = activeWebtoonListState
                            if (readerState.direction == MangaReadingDirection.Webtoon && listState != null) {
                                if (listState.canScrollBackward) {
                                    val delta = (viewportHeightPx * 0.8f).coerceAtLeast(200f)
                                    scope.launch { listState.animateScrollBy(-delta) }
                                } else {
                                    readerState.stepBackward()
                                }
                            } else {
                                readerState.stepBackward()
                            }
                            true
                        }

                        Key.Spacebar -> {
                            val listState = activeWebtoonListState
                            val isWebtoon = readerState.direction == MangaReadingDirection.Webtoon
                            if (event.isShiftPressed) {
                                if (isWebtoon && listState != null && listState.canScrollBackward) {
                                    val delta = (viewportHeightPx * 0.8f).coerceAtLeast(200f)
                                    scope.launch { listState.animateScrollBy(-delta) }
                                } else {
                                    readerState.stepBackward()
                                }
                            } else {
                                if (isWebtoon && listState != null && listState.canScrollForward) {
                                    val delta = (viewportHeightPx * 0.8f).coerceAtLeast(200f)
                                    scope.launch { listState.animateScrollBy(delta) }
                                } else {
                                    readerState.stepForward()
                                }
                            }
                            true
                        }

                        Key.Escape -> {
                            val reset = activeZoomReset
                            if (reset != null) {
                                reset()
                                true
                            } else if (currentOnBack != null) {
                                currentOnBack?.invoke()
                                true
                            } else if (currentHudVisible) {
                                currentOnToggleHud()
                                true
                            } else {
                                false
                            }
                        }

                        else -> {
                            false
                        }
                    }
                }.focusable(),
        contentAlignment = Alignment.Center,
    ) {
        val isWide = maxWidth >= WIDE_VIEWPORT_BREAKPOINT_DP.dp
        val density = LocalDensity.current
        viewportHeightPx = with(density) { maxHeight.toPx() }

        LaunchedEffect(state, isWide) {
            state.setWideViewport(isWide)
        }

        val handleZoneTap: (Offset, IntSize) -> Unit = { tapOffset, containerSize ->
            runCatching { focusRequester.requestFocus() }
            val width = containerSize.width.toFloat().coerceAtLeast(1f)
            val fraction = tapOffset.x / width
            when {
                fraction < LEFT_TAP_ZONE_FRACTION -> {
                    if (currentState.direction == MangaReadingDirection.Rtl) {
                        currentState.stepForward()
                    } else {
                        currentState.stepBackward()
                    }
                }

                fraction > RIGHT_TAP_ZONE_FRACTION -> {
                    if (currentState.direction == MangaReadingDirection.Rtl) {
                        currentState.stepBackward()
                    } else {
                        currentState.stepForward()
                    }
                }

                else -> {
                    currentOnToggleHud()
                }
            }
        }

        when (val loadState = state.loadState) {
            is UiState.Loading -> {
                activeWebtoonListState = null
                activeZoomReset = null
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { currentOnToggleHud() })
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    PlatformCircularProgressIndicator()
                }
            }

            is UiState.Error -> {
                activeWebtoonListState = null
                activeZoomReset = null
                val locked = state.lockedChapter
                if (locked != null) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = { currentOnToggleHud() })
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        MangaBoundaryCard(
                            boundary = locked,
                            isPrevious = false,
                            isStandaloneLocked = true,
                            onOpenUnlocked = state::openAdjacentChapter,
                            onOpenLockedUrl = openExternalUrl,
                            actionTestTag = "manga_locked_chapter_open",
                        )
                    }
                } else {
                    ErrorContent(
                        error = loadState.throwable,
                        onRetry = state::retryChapter,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            is UiState.Success -> {
                key(state.activeChapterId) {
                    if (state.direction == MangaReadingDirection.Webtoon) {
                        MangaWebtoonStrip(
                            state = state,
                            onZoneTap = handleZoneTap,
                            onOpenLockedUrl = openExternalUrl,
                            onListStateReady = { activeWebtoonListState = it },
                            onZoomResetChanged = { activeZoomReset = it },
                        )
                    } else {
                        activeWebtoonListState = null
                        MangaPagedCanvas(
                            state = state,
                            onZoneTap = handleZoneTap,
                            onOpenLockedUrl = openExternalUrl,
                            onZoomResetChanged = { activeZoomReset = it },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrefetchReaderMediaEffect(state: MangaReaderPresenter.State) {
    val context = LocalPlatformContext.current
    val allowHardware = LocalNetworkImageAllowHardware.current
    val proxyRevision by ProxyNetwork.changes.collectAsState()

    LaunchedEffect(state.prefetchMedia, context, allowHardware, proxyRevision) {
        if (state.prefetchMedia.isEmpty()) return@LaunchedEffect
        val imageLoader = SingletonImageLoader.get(context)
        val disposables =
            state.prefetchMedia.map { media ->
                val snapshot =
                    if (media.isRemote) {
                        try {
                            ProxyNetwork.snapshot(media.proxy)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                val request =
                    ImageRequest
                        .Builder(context)
                        .media(media, snapshot)
                        .crossfade(false)
                        .applyAllowHardware(allowHardware)
                        .build()
                imageLoader.enqueue(request)
            }
        try {
            awaitCancellation()
        } finally {
            disposables.forEach { it.dispose() }
        }
    }
}

@Composable
private fun MangaPagedCanvas(
    state: MangaReaderPresenter.State,
    onZoneTap: (Offset, IntSize) -> Unit,
    onOpenLockedUrl: (String) -> Unit,
    onZoomResetChanged: ((() -> Unit)?) -> Unit,
) {
    val hasPrevBoundary = state.previousBoundary != ChapterBoundary.None
    val hasNextBoundary = state.nextBoundary != ChapterBoundary.None
    val startBoundaryOffset = if (hasPrevBoundary) 1 else 0
    val totalPagerPages =
        if (state.slots.isEmpty()) {
            0
        } else {
            state.slots.size + startBoundaryOffset + (if (hasNextBoundary) 1 else 0)
        }

    if (totalPagerPages == 0) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { offset -> onZoneTap(offset, size) })
                    },
        )
        return
    }

    val initialPagerPage =
        (state.currentSlotIndex + startBoundaryOffset).coerceIn(0, (totalPagerPages - 1).coerceAtLeast(0))
    val pagerState =
        rememberPagerState(
            initialPage = initialPagerPage,
            pageCount = { totalPagerPages },
        )

    var programmaticTargetPage by remember { mutableStateOf<Int?>(null) }
    var isCurrentPageZoomed by remember { mutableStateOf(false) }

    val latestSlots by rememberUpdatedState(state.slots)
    val latestCurrentSlotIndex by rememberUpdatedState(state.currentSlotIndex)
    val latestStartOffset by rememberUpdatedState(startBoundaryOffset)

    LaunchedEffect(state.currentSlotIndex, startBoundaryOffset, totalPagerPages) {
        val target = (state.currentSlotIndex + startBoundaryOffset).coerceIn(0, (totalPagerPages - 1).coerceAtLeast(0))
        if (pagerState.currentPage != target && !pagerState.isScrollInProgress) {
            programmaticTargetPage = target
            try {
                pagerState.scrollToPage(target)
            } finally {
                programmaticTargetPage = null
            }
        }
    }

    LaunchedEffect(pagerState) {
        var lastObservedSettled = pagerState.settledPage
        snapshotFlow { pagerState.settledPage }
            .collect { settled ->
                if (settled == lastObservedSettled) return@collect
                lastObservedSettled = settled
                if (programmaticTargetPage != null) return@collect
                val slotIndex = settled - latestStartOffset
                if (slotIndex in latestSlots.indices && slotIndex != latestCurrentSlotIndex) {
                    state.goToSlot(slotIndex)
                }
            }
    }

    HorizontalPager(
        state = pagerState,
        reverseLayout = state.direction == MangaReadingDirection.Rtl,
        userScrollEnabled = !isCurrentPageZoomed,
        modifier = Modifier.fillMaxSize(),
    ) { pagerPage ->
        val isPreviousBoundaryPage = hasPrevBoundary && pagerPage == 0
        val isNextBoundaryPage = hasNextBoundary && pagerPage == startBoundaryOffset + state.slots.size
        when {
            isPreviousBoundaryPage -> {
                if (pagerState.currentPage == pagerPage) {
                    LaunchedEffect(pagerPage) {
                        isCurrentPageZoomed = false
                        onZoomResetChanged(null)
                    }
                }
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { offset -> onZoneTap(offset, size) })
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    MangaBoundaryCard(
                        boundary = state.previousBoundary,
                        isPrevious = true,
                        isStandaloneLocked = false,
                        onOpenUnlocked = state::openAdjacentChapter,
                        onOpenLockedUrl = onOpenLockedUrl,
                        actionTestTag = "manga_boundary_prev_action",
                    )
                }
            }

            isNextBoundaryPage -> {
                if (pagerState.currentPage == pagerPage) {
                    LaunchedEffect(pagerPage) {
                        isCurrentPageZoomed = false
                        onZoomResetChanged(null)
                    }
                }
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { offset -> onZoneTap(offset, size) })
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    MangaBoundaryCard(
                        boundary = state.nextBoundary,
                        isPrevious = false,
                        isStandaloneLocked = false,
                        onOpenUnlocked = state::openAdjacentChapter,
                        onOpenLockedUrl = onOpenLockedUrl,
                        actionTestTag = "manga_boundary_next_action",
                    )
                }
            }

            else -> {
                val slotIndex = (pagerPage - startBoundaryOffset).coerceIn(0, state.slots.lastIndex)
                val slot = state.slots[slotIndex]
                val isCurrentPagerPage = pagerState.currentPage == pagerPage
                ZoomablePagedSlot(
                    slot = slot,
                    pages = state.pages,
                    state = state,
                    isSettledCurrent = pagerState.settledPage == pagerPage,
                    isCurrentPagerPage = isCurrentPagerPage,
                    onZoneTap = onZoneTap,
                    onZoomStateChanged = { zoomed, resetFn ->
                        if (isCurrentPagerPage) {
                            isCurrentPageZoomed = zoomed
                            onZoomResetChanged(if (zoomed) resetFn else null)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ZoomablePagedSlot(
    slot: ReaderSlot,
    pages: List<ReaderPageState>,
    state: MangaReaderPresenter.State,
    isSettledCurrent: Boolean,
    isCurrentPagerPage: Boolean,
    onZoneTap: (Offset, IntSize) -> Unit,
    onZoomStateChanged: (Boolean, () -> Unit) -> Unit,
) {
    var scale by remember(slot) { mutableFloatStateOf(1f) }
    var offset by remember(slot) { mutableStateOf(Offset.Zero) }

    val resetZoom =
        remember(slot) {
            {
                scale = 1f
                offset = Offset.Zero
            }
        }

    LaunchedEffect(isSettledCurrent) {
        if (!isSettledCurrent && scale > ZOOM_EPSILON) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    val isZoomed = scale > ZOOM_EPSILON
    LaunchedEffect(isCurrentPagerPage, isZoomed) {
        if (isCurrentPagerPage) {
            onZoomStateChanged(isZoomed, resetZoom)
        }
    }

    val gestureScope = rememberCoroutineScope()
    val currentOnZoneTap by rememberUpdatedState(onZoneTap)

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .readerTapAndDoubleTap(
                    key = slot,
                    scope = gestureScope,
                    onTap = { tapOffset, containerSize ->
                        currentOnZoneTap(tapOffset, containerSize)
                    },
                    onDoubleTap = { tapOffset, containerSize ->
                        if (scale > ZOOM_EPSILON) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val targetScale = DOUBLE_TAP_ZOOM
                            val centerX = containerSize.width / 2f
                            val centerY = containerSize.height / 2f
                            val rawOffsetX = (centerX - tapOffset.x) * (targetScale - 1f)
                            val rawOffsetY = (centerY - tapOffset.y) * (targetScale - 1f)
                            val maxX = (containerSize.width * (targetScale - 1f)) / 2f
                            val maxY = (containerSize.height * (targetScale - 1f)) / 2f
                            scale = targetScale
                            offset =
                                Offset(
                                    x = rawOffsetX.coerceIn(-maxX, maxX),
                                    y = rawOffsetY.coerceIn(-maxY, maxY),
                                )
                        }
                    },
                ).pointerInput(slot) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2 || scale > ZOOM_EPSILON) {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val newScale = (scale * zoomChange).coerceIn(1f, MAX_READER_ZOOM)
                                if (newScale <= ZOOM_EPSILON) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    val maxX = (size.width * (newScale - 1f)) / 2f
                                    val maxY = (size.height * (newScale - 1f)) / 2f
                                    scale = newScale
                                    offset =
                                        Offset(
                                            x = (offset.x + panChange.x).coerceIn(-maxX, maxX),
                                            y = (offset.y + panChange.y).coerceIn(-maxY, maxY),
                                        )
                                }
                                if (pressed >= 2 || scale > ZOOM_EPSILON) {
                                    event.changes.forEach { change ->
                                        if (change.positionChanged()) {
                                            change.consume()
                                        }
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        contentAlignment = Alignment.Center,
    ) {
        when (slot) {
            is ReaderSlot.Single -> {
                pages.getOrNull(slot.pageIndex)?.let { page ->
                    ReaderPageCell(
                        page = page,
                        state = state,
                        contentScale = ContentScale.Fit,
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .testTag("manga_reader_page_${page.index}"),
                    )
                }
            }

            is ReaderSlot.Double -> {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        pages.getOrNull(slot.leftPageIndex)?.let { leftPage ->
                            ReaderPageCell(
                                page = leftPage,
                                state = state,
                                contentScale = ContentScale.Fit,
                                contentAlignment = Alignment.CenterEnd,
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .testTag("manga_reader_page_${leftPage.index}"),
                            )
                        }
                        pages.getOrNull(slot.rightPageIndex)?.let { rightPage ->
                            ReaderPageCell(
                                page = rightPage,
                                state = state,
                                contentScale = ContentScale.Fit,
                                contentAlignment = Alignment.CenterStart,
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .testTag("manga_reader_page_${rightPage.index}"),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MangaWebtoonStrip(
    state: MangaReaderPresenter.State,
    onZoneTap: (Offset, IntSize) -> Unit,
    onOpenLockedUrl: (String) -> Unit,
    onListStateReady: (LazyListState) -> Unit,
    onZoomResetChanged: ((() -> Unit)?) -> Unit,
) {
    val hasPrevBoundary = state.previousBoundary != ChapterBoundary.None
    val hasNextBoundary = state.nextBoundary != ChapterBoundary.None
    val headerCount = if (hasPrevBoundary) 1 else 0

    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex = (state.currentPageIndex + headerCount).coerceAtLeast(0),
        )
    LaunchedEffect(listState) {
        onListStateReady(listState)
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    val resetZoom =
        remember {
            {
                scale = 1f
                offsetX = 0f
            }
        }
    val isZoomed = scale > ZOOM_EPSILON
    LaunchedEffect(isZoomed) {
        onZoomResetChanged(if (isZoomed) resetZoom else null)
    }

    var programmaticTargetIndex by remember { mutableStateOf<Int?>(null) }
    val latestHeaderCount by rememberUpdatedState(headerCount)
    val latestPageCount by rememberUpdatedState(state.pages.size)
    val latestCurrentPageIndex by rememberUpdatedState(state.currentPageIndex)

    LaunchedEffect(state.currentPageIndex, headerCount, state.pages.size) {
        if (state.pages.isEmpty()) return@LaunchedEffect
        val targetItemIndex = state.currentPageIndex + headerCount
        val visiblePageIndices =
            listState.layoutInfo.visibleItemsInfo
                .map { it.index - headerCount }
                .filter { it in state.pages.indices }
        val primaryVisiblePage = visiblePageIndices.firstOrNull()
        if (!listState.isScrollInProgress && primaryVisiblePage != state.currentPageIndex) {
            programmaticTargetIndex = targetItemIndex
            try {
                listState.scrollToItem(targetItemIndex)
            } finally {
                programmaticTargetIndex = null
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val pageItem =
                info.visibleItemsInfo
                    .filter { (it.index - latestHeaderCount) in 0 until latestPageCount }
                    .minByOrNull { item -> abs((item.offset + item.size / 2) - viewportCenter) }
            listState.isScrollInProgress to pageItem?.let { it.index - latestHeaderCount }
        }.collect { (scrolling, visiblePage) ->
            if (programmaticTargetIndex != null) return@collect
            if (scrolling && visiblePage != null && visiblePage != latestCurrentPageIndex) {
                state.goToPage(visiblePage)
            }
        }
    }

    val gestureScope = rememberCoroutineScope()
    val currentOnZoneTap by rememberUpdatedState(onZoneTap)

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .readerTapAndDoubleTap(
                    key = Unit,
                    scope = gestureScope,
                    onTap = { tapOffset, containerSize ->
                        currentOnZoneTap(tapOffset, containerSize)
                    },
                    onDoubleTap = { tapOffset, containerSize ->
                        if (scale > ZOOM_EPSILON) {
                            scale = 1f
                            offsetX = 0f
                        } else {
                            val targetScale = DOUBLE_TAP_ZOOM
                            val centerX = containerSize.width / 2f
                            val rawOffsetX = (centerX - tapOffset.x) * (targetScale - 1f)
                            val maxX = (containerSize.width * (targetScale - 1f)) / 2f
                            scale = targetScale
                            offsetX = rawOffsetX.coerceIn(-maxX, maxX)
                        }
                    },
                ).pointerInput(listState) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2 || scale > ZOOM_EPSILON) {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val newScale = (scale * zoomChange).coerceIn(1f, MAX_READER_ZOOM)
                                if (newScale <= ZOOM_EPSILON) {
                                    scale = 1f
                                    offsetX = 0f
                                } else {
                                    val maxX = (size.width * (newScale - 1f)) / 2f
                                    scale = newScale
                                    offsetX = (offsetX + panChange.x).coerceIn(-maxX, maxX)
                                    if (panChange.y != 0f) {
                                        listState.dispatchRawDelta(-panChange.y / newScale)
                                    }
                                }
                                if (pressed >= 2 || scale > ZOOM_EPSILON) {
                                    event.changes.forEach { change ->
                                        if (change.positionChanged()) {
                                            change.consume()
                                        }
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                },
    ) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag("manga_webtoon_list"),
        ) {
            if (hasPrevBoundary) {
                item(key = "webtoon_boundary_prev") {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        MangaBoundaryCard(
                            boundary = state.previousBoundary,
                            isPrevious = true,
                            isStandaloneLocked = false,
                            onOpenUnlocked = state::openAdjacentChapter,
                            onOpenLockedUrl = onOpenLockedUrl,
                            actionTestTag = "manga_boundary_prev_action",
                        )
                    }
                }
            }

            items(
                items = state.pages,
                key = { it.id },
            ) { page ->
                val ratio = (page.aspectRatio ?: DEFAULT_WEBTOON_ASPECT_RATIO).coerceIn(0.05f, 10f)
                ReaderPageCell(
                    page = page,
                    state = state,
                    contentScale = ContentScale.FillWidth,
                    contentAlignment = Alignment.TopCenter,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(ratio)
                            .testTag("manga_reader_page_${page.index}"),
                )
            }

            if (hasNextBoundary) {
                item(key = "webtoon_boundary_next") {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        MangaBoundaryCard(
                            boundary = state.nextBoundary,
                            isPrevious = false,
                            isStandaloneLocked = false,
                            onOpenUnlocked = state::openAdjacentChapter,
                            onOpenLockedUrl = onOpenLockedUrl,
                            actionTestTag = "manga_boundary_next_action",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderPageCell(
    page: ReaderPageState,
    state: MangaReaderPresenter.State,
    contentScale: ContentScale,
    contentAlignment: Alignment,
    modifier: Modifier = Modifier,
) {
    when (val status = page.status) {
        is PageMediaStatus.Loading -> {
            val previewMedia =
                remember(status.previewUrl, status.customHeaders, status.proxy) {
                    status.previewUrl.toUiImage(
                        customHeaders = status.customHeaders,
                        proxy = status.proxy,
                    )
                }
            Box(
                modifier = modifier,
                contentAlignment = Alignment.Center,
            ) {
                if (previewMedia != null) {
                    val previewRequest = rememberMediaImageRequest(previewMedia, crossfade = false)
                    AsyncImage(
                        model = previewRequest,
                        contentDescription = null,
                        contentScale = contentScale,
                        alignment = contentAlignment,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .placeholder(true),
                    )
                }
                PlatformCircularProgressIndicator()
            }
        }

        is PageMediaStatus.Ready -> {
            val translating = state.imageTranslation.item(status.original).loading
            val blurRadius by animateDpAsState(if (translating) 8.dp else 0.dp, label = "MangaPageTranslationBlur")
            val baseRequest = rememberMediaImageRequest(status.displayed, crossfade = false)
            val fullSizeRequest =
                remember(baseRequest) {
                    baseRequest
                        .newBuilder()
                        .size(Size.ORIGINAL)
                        .build()
                }
            var imageLoading by remember(status.displayed.url) { mutableStateOf(status.displayed.isRemote) }

            Box(
                modifier = modifier,
                contentAlignment = contentAlignment,
            ) {
                AsyncImage(
                    model = fullSizeRequest,
                    contentDescription = status.displayed.accessibleDescription(),
                    contentScale = contentScale,
                    alignment = contentAlignment,
                    onLoading = {
                        imageLoading = true
                    },
                    onSuccess = { success ->
                        imageLoading = false
                        val decoded = success.result.image
                        state.onPageDimensionsLoaded(
                            pageId = page.id,
                            width = decoded.width.toFloat(),
                            height = decoded.height.toFloat(),
                        )
                    },
                    onError = {
                        imageLoading = false
                        if (status.displayed.url != status.original.url) {
                            state.imageTranslation.imageFailed(status.original, status.displayed.url)
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .blur(blurRadius),
                )
                if (translating || imageLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        PlatformCircularProgressIndicator()
                    }
                }
            }
        }

        is PageMediaStatus.Failed -> {
            Box(
                modifier = modifier.padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PlatformText(
                        text = status.error.message?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.manga_page_retry),
                        style = PlatformTheme.typography.body,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                    )
                    PlatformFilledTonalButton(
                        onClick = { state.retryPage(page.id) },
                        modifier = Modifier.testTag("manga_page_retry_${page.id}"),
                    ) {
                        FAIcon(
                            imageVector = FontAwesomeIcons.Solid.RotateRight,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        PlatformText(text = stringResource(Res.string.manga_page_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun MangaBoundaryCard(
    boundary: ChapterBoundary,
    isPrevious: Boolean,
    isStandaloneLocked: Boolean,
    onOpenUnlocked: (ChapterBoundary.Unlocked) -> Unit,
    onOpenLockedUrl: (String) -> Unit,
    actionTestTag: String,
) {
    when (boundary) {
        ChapterBoundary.None -> {}

        is ChapterBoundary.Unlocked -> {
            PlatformCard(
                modifier =
                    Modifier
                        .widthIn(max = 360.dp)
                        .padding(horizontal = 24.dp),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FAIcon(
                        imageVector = FontAwesomeIcons.Solid.BookOpen,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = PlatformTheme.colorScheme.primary,
                    )
                    PlatformText(
                        text =
                            stringResource(
                                if (isPrevious) {
                                    Res.string.manga_boundary_previous_chapter
                                } else {
                                    Res.string.manga_boundary_next_chapter
                                },
                                boundary.chapter.title,
                            ),
                        style = PlatformTheme.typography.caption,
                        color = PlatformTheme.colorScheme.caption,
                        textAlign = TextAlign.Center,
                    )
                    PlatformText(
                        text = boundary.chapter.title,
                        style = PlatformTheme.typography.title,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    PlatformButton(
                        onClick = { onOpenUnlocked(boundary) },
                        modifier = Modifier.testTag(actionTestTag),
                    ) {
                        PlatformText(
                            text =
                                stringResource(
                                    if (isPrevious) {
                                        Res.string.manga_boundary_read_previous
                                    } else {
                                        Res.string.manga_boundary_read_next
                                    },
                                ),
                        )
                    }
                }
            }
        }

        is ChapterBoundary.Locked -> {
            PlatformCard(
                modifier =
                    Modifier
                        .widthIn(max = 360.dp)
                        .padding(horizontal = 24.dp),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FAIcon(
                        imageVector = FontAwesomeIcons.Solid.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = PlatformTheme.colorScheme.caption,
                    )
                    PlatformText(
                        text =
                            if (isStandaloneLocked) {
                                stringResource(Res.string.manga_locked_chapter_title)
                            } else {
                                stringResource(
                                    if (isPrevious) {
                                        Res.string.manga_boundary_previous_chapter
                                    } else {
                                        Res.string.manga_boundary_next_chapter
                                    },
                                    boundary.chapter.title,
                                )
                            },
                        style = PlatformTheme.typography.title,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    PlatformText(
                        text = stringResource(Res.string.manga_locked_chapter_message, boundary.chapter.title),
                        style = PlatformTheme.typography.body,
                        color = PlatformTheme.colorScheme.caption,
                        textAlign = TextAlign.Center,
                    )
                    if (boundary.openUrl.isNotBlank()) {
                        PlatformButton(
                            onClick = { onOpenLockedUrl(boundary.openUrl) },
                            modifier = Modifier.testTag(actionTestTag),
                        ) {
                            PlatformText(text = stringResource(Res.string.manga_open_in_browser))
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.readerTapAndDoubleTap(
    key: Any?,
    scope: CoroutineScope,
    onTap: (Offset, IntSize) -> Unit,
    onDoubleTap: (Offset, IntSize) -> Unit,
): Modifier =
    pointerInput(key) {
        var pendingTapJob: Job? = null
        var lastTapUpTimeMillis = -1L
        var lastTapPosition = Offset.Zero
        awaitEachGesture {
            val down = awaitFirstDown()
            val timeoutMs = viewConfiguration.doubleTapTimeoutMillis
            val maxDoubleTapDistance = viewConfiguration.touchSlop * 3f
            val isSecondTap =
                pendingTapJob?.isActive == true &&
                    lastTapUpTimeMillis >= 0L &&
                    (down.uptimeMillis - lastTapUpTimeMillis) <= timeoutMs &&
                    (down.position - lastTapPosition).getDistance() <= maxDoubleTapDistance

            if (isSecondTap) {
                pendingTapJob?.cancel()
                pendingTapJob = null
                val up = waitForUpOrCancellation()
                if (up != null) {
                    up.consume()
                    lastTapUpTimeMillis = -1L
                    onDoubleTap(up.position, size)
                }
            } else {
                val up = waitForUpOrCancellation()
                if (up != null) {
                    up.consume()
                    val tapPos = up.position
                    val containerSize = size
                    lastTapPosition = tapPos
                    lastTapUpTimeMillis = up.uptimeMillis
                    pendingTapJob?.cancel()
                    pendingTapJob =
                        scope.launch {
                            val start = withFrameMillis { it }
                            while (withFrameMillis { it } - start < timeoutMs) {
                                // Yield frames on MonotonicFrameClock so both real runtime and ComposeUiTest advance cleanly.
                            }
                            onTap(tapPos, containerSize)
                        }
                }
            }
        }
    }
