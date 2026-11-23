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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.Extras
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.NullRequestData
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
import kotlin.math.roundToInt

internal const val MAX_CONSTRAINED_WEBTOON_WIDTH_DP = 800
private const val WIDE_VIEWPORT_BREAKPOINT_DP = 720
private const val LEFT_TAP_ZONE_FRACTION = 0.3f
private const val RIGHT_TAP_ZONE_FRACTION = 0.7f
private const val MAX_READER_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val ZOOM_EPSILON = 1.005f
private const val DEFAULT_WEBTOON_ASPECT_RATIO = 0.7f
private val readerRetryNonceExtra = Extras.Key(0)

private enum class BoundaryResidency {
    Previous,
    Next,
}

private class PagedStepHandlers(
    val stepForward: () -> Unit,
    val stepBackward: () -> Unit,
)

private class WebtoonScrollSyncState(
    initialPage: Int,
    initialHeaderCount: Int,
) {
    var lastReportedPage: Int = initialPage
    var lastHeaderCount: Int = initialHeaderCount
    var settledProgrammaticPos: Pair<Int, Int>? = null
}

@Composable
public fun MangaReaderCanvas(
    state: MangaReaderPresenter.State,
    hudVisible: Boolean,
    onToggleHud: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onPageLongPress: ((page: ReaderPageState, ready: PageMediaStatus.Ready) -> Unit)? = null,
    pageContextWrapper: (
        @Composable (
            page: ReaderPageState,
            ready: PageMediaStatus.Ready,
            content: @Composable () -> Unit,
        ) -> Unit
    )? = null,
    onStepHandlersChanged: ((stepForward: () -> Unit, stepBackward: () -> Unit) -> Unit)? = null,
) {
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val currentState by rememberUpdatedState(state)
    val currentHudVisible by rememberUpdatedState(hudVisible)
    val currentOnToggleHud by rememberUpdatedState(onToggleHud)
    val currentOnBack by rememberUpdatedState(onBack)

    var activeZoomReset by remember { mutableStateOf<(() -> Unit)?>(null) }
    var activeWebtoonListState by remember(state.activeChapterId) { mutableStateOf<LazyListState?>(null) }
    var activePagedStepHandlers by remember { mutableStateOf<PagedStepHandlers?>(null) }
    var viewportHeightPx by remember { mutableFloatStateOf(0f) }

    val webtoonStepForward: () -> Unit = {
        val readerState = currentState
        val listState = activeWebtoonListState
        if (listState != null && listState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
            if (listState.canScrollForward) {
                val delta = (viewportHeightPx * 0.8f).coerceAtLeast(1f)
                scope.launch { listState.animateScrollBy(delta) }
            } else if (readerState.nextBoundary is ChapterBoundary.Unlocked) {
                if (readerState.slots.isNotEmpty() && readerState.currentSlotIndex != readerState.slots.lastIndex) {
                    readerState.goToSlot(readerState.slots.lastIndex)
                }
                readerState.stepForward()
            }
        }
    }

    val webtoonStepBackward: () -> Unit = {
        val readerState = currentState
        val listState = activeWebtoonListState
        if (listState != null && listState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
            if (listState.canScrollBackward) {
                val delta = (viewportHeightPx * 0.8f).coerceAtLeast(1f)
                scope.launch { listState.animateScrollBy(-delta) }
            } else if (readerState.previousBoundary is ChapterBoundary.Unlocked) {
                if (readerState.slots.isNotEmpty() && readerState.currentSlotIndex != 0) {
                    readerState.goToSlot(0)
                }
                readerState.stepBackward()
            }
        }
    }

    val performStepForward: () -> Unit = {
        val readerState = currentState
        val pagedHandlers = activePagedStepHandlers
        when {
            readerState.direction == MangaReadingDirection.Webtoon -> webtoonStepForward()
            pagedHandlers != null -> pagedHandlers.stepForward()
            else -> readerState.stepForward()
        }
    }

    val performStepBackward: () -> Unit = {
        val readerState = currentState
        val pagedHandlers = activePagedStepHandlers
        when {
            readerState.direction == MangaReadingDirection.Webtoon -> webtoonStepBackward()
            pagedHandlers != null -> pagedHandlers.stepBackward()
            else -> readerState.stepBackward()
        }
    }

    SideEffect {
        onStepHandlersChanged?.invoke(performStepForward, performStepBackward)
    }

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
                                performStepForward()
                            } else {
                                performStepBackward()
                            }
                            true
                        }

                        Key.DirectionRight -> {
                            if (readerState.direction == MangaReadingDirection.Rtl) {
                                performStepBackward()
                            } else {
                                performStepForward()
                            }
                            true
                        }

                        Key.DirectionDown -> {
                            performStepForward()
                            true
                        }

                        Key.DirectionUp -> {
                            performStepBackward()
                            true
                        }

                        Key.PageDown -> {
                            performStepForward()
                            true
                        }

                        Key.PageUp -> {
                            performStepBackward()
                            true
                        }

                        Key.MoveHome -> {
                            if (readerState.pages.isNotEmpty()) {
                                readerState.goToPage(0)
                            }
                            true
                        }

                        Key.MoveEnd -> {
                            if (readerState.pages.isNotEmpty()) {
                                readerState.goToPage(readerState.pages.lastIndex)
                            }
                            true
                        }

                        Key.Spacebar -> {
                            if (event.isShiftPressed) {
                                performStepBackward()
                            } else {
                                performStepForward()
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
                        performStepForward()
                    } else {
                        performStepBackward()
                    }
                }

                fraction > RIGHT_TAP_ZONE_FRACTION -> {
                    if (currentState.direction == MangaReadingDirection.Rtl) {
                        performStepBackward()
                    } else {
                        performStepForward()
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
                activePagedStepHandlers = null
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
                activePagedStepHandlers = null
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
                        activePagedStepHandlers = null
                        MangaWebtoonStrip(
                            state = state,
                            constrainMaxWidthDp =
                                if (isWide && state.webtoonConstrainWidthOnWideScreen) {
                                    MAX_CONSTRAINED_WEBTOON_WIDTH_DP.dp
                                } else {
                                    null
                                },
                            onZoneTap = handleZoneTap,
                            onOpenLockedUrl = openExternalUrl,
                            onListStateReady = { activeWebtoonListState = it },
                            onZoomResetChanged = { activeZoomReset = it },
                            onPageLongPress = onPageLongPress,
                            pageContextWrapper = pageContextWrapper,
                        )
                    } else {
                        activeWebtoonListState = null
                        MangaPagedCanvas(
                            state = state,
                            onZoneTap = handleZoneTap,
                            onOpenLockedUrl = openExternalUrl,
                            onStepHandlersChanged = { activePagedStepHandlers = it },
                            onZoomResetChanged = { activeZoomReset = it },
                            onPageLongPress = onPageLongPress,
                            pageContextWrapper = pageContextWrapper,
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
                        .size(Size.ORIGINAL)
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
    onStepHandlersChanged: (PagedStepHandlers?) -> Unit,
    onZoomResetChanged: ((() -> Unit)?) -> Unit,
    onPageLongPress: ((page: ReaderPageState, ready: PageMediaStatus.Ready) -> Unit)?,
    pageContextWrapper: (
        @Composable (
            page: ReaderPageState,
            ready: PageMediaStatus.Ready,
            content: @Composable () -> Unit,
        ) -> Unit
    )?,
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

    DisposableEffect(Unit) {
        onDispose {
            onStepHandlersChanged(null)
        }
    }

    if (totalPagerPages == 0) {
        LaunchedEffect(Unit) {
            onStepHandlersChanged(null)
        }
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

    val pagerScope = rememberCoroutineScope()
    var programmaticScrollsInFlight by remember { mutableIntStateOf(0) }
    var boundaryScrollJob by remember { mutableStateOf<Job?>(null) }
    var isCurrentPageZoomed by remember { mutableStateOf(false) }
    var settledBoundary by remember { mutableStateOf<BoundaryResidency?>(null) }
    var lastObservedPageIndex by remember { mutableIntStateOf(state.currentPageIndex) }

    val latestState by rememberUpdatedState(state)
    val latestSlots by rememberUpdatedState(state.slots)
    val latestCurrentSlotIndex by rememberUpdatedState(state.currentSlotIndex)
    val latestHasPrevBoundary by rememberUpdatedState(hasPrevBoundary)
    val latestHasNextBoundary by rememberUpdatedState(hasNextBoundary)
    val latestStartOffset by rememberUpdatedState(startBoundaryOffset)
    val latestTotalPagerPages by rememberUpdatedState(totalPagerPages)

    fun animatePagerProgrammatically(targetPage: Int) {
        val clamped = targetPage.coerceIn(0, (latestTotalPagerPages - 1).coerceAtLeast(0))
        boundaryScrollJob?.cancel()
        boundaryScrollJob =
            pagerScope.launch {
                programmaticScrollsInFlight++
                try {
                    pagerState.animateScrollToPage(clamped)
                } finally {
                    programmaticScrollsInFlight--
                }
            }
    }

    val stepHandlers =
        remember(pagerState) {
            PagedStepHandlers(
                stepForward = {
                    val activePage = if (pagerState.isScrollInProgress) pagerState.targetPage else pagerState.currentPage
                    val prevBoundaryPage = if (latestHasPrevBoundary) 0 else -1
                    val nextBoundaryPage = if (latestHasNextBoundary) latestStartOffset + latestSlots.size else -1
                    when {
                        activePage == prevBoundaryPage -> {
                            settledBoundary = null
                            if (latestSlots.isNotEmpty()) {
                                if (!latestSlots[0].contains(latestState.currentPageIndex)) {
                                    latestState.goToSlot(0)
                                }
                                lastObservedPageIndex = latestState.currentPageIndex
                                animatePagerProgrammatically(latestStartOffset)
                            }
                        }

                        activePage == nextBoundaryPage -> {
                            val next = latestState.nextBoundary
                            if (next is ChapterBoundary.Unlocked) {
                                settledBoundary = null
                                boundaryScrollJob?.cancel()
                                latestState.openAdjacentChapter(next)
                            }
                        }

                        latestSlots.isNotEmpty() -> {
                            val slotIndex = (activePage - latestStartOffset).coerceIn(0, latestSlots.lastIndex)
                            if (slotIndex != latestCurrentSlotIndex) {
                                latestState.goToSlot(slotIndex)
                                lastObservedPageIndex = latestState.currentPageIndex
                            }
                            when {
                                slotIndex + 1 < latestSlots.size -> {
                                    settledBoundary = null
                                    boundaryScrollJob?.cancel()
                                    latestState.stepForward()
                                }

                                latestState.nextBoundary is ChapterBoundary.Unlocked -> {
                                    settledBoundary = null
                                    boundaryScrollJob?.cancel()
                                    latestState.stepForward()
                                }

                                nextBoundaryPage >= 0 -> {
                                    settledBoundary = BoundaryResidency.Next
                                    lastObservedPageIndex = latestState.currentPageIndex
                                    animatePagerProgrammatically(nextBoundaryPage)
                                }
                            }
                        }
                    }
                },
                stepBackward = {
                    val activePage = if (pagerState.isScrollInProgress) pagerState.targetPage else pagerState.currentPage
                    val prevBoundaryPage = if (latestHasPrevBoundary) 0 else -1
                    val nextBoundaryPage = if (latestHasNextBoundary) latestStartOffset + latestSlots.size else -1
                    when {
                        activePage == nextBoundaryPage -> {
                            settledBoundary = null
                            if (latestSlots.isNotEmpty()) {
                                val lastIdx = latestSlots.lastIndex
                                if (!latestSlots[lastIdx].contains(latestState.currentPageIndex)) {
                                    latestState.goToSlot(lastIdx)
                                }
                                lastObservedPageIndex = latestState.currentPageIndex
                                animatePagerProgrammatically(latestStartOffset + lastIdx)
                            }
                        }

                        activePage == prevBoundaryPage -> {
                            val prev = latestState.previousBoundary
                            if (prev is ChapterBoundary.Unlocked) {
                                settledBoundary = null
                                boundaryScrollJob?.cancel()
                                latestState.openAdjacentChapter(prev)
                            }
                        }

                        latestSlots.isNotEmpty() -> {
                            val slotIndex = (activePage - latestStartOffset).coerceIn(0, latestSlots.lastIndex)
                            if (slotIndex != latestCurrentSlotIndex) {
                                latestState.goToSlot(slotIndex)
                                lastObservedPageIndex = latestState.currentPageIndex
                            }
                            when {
                                slotIndex > 0 -> {
                                    settledBoundary = null
                                    boundaryScrollJob?.cancel()
                                    latestState.stepBackward()
                                }

                                latestState.previousBoundary is ChapterBoundary.Unlocked -> {
                                    settledBoundary = null
                                    boundaryScrollJob?.cancel()
                                    latestState.stepBackward()
                                }

                                prevBoundaryPage >= 0 -> {
                                    settledBoundary = BoundaryResidency.Previous
                                    lastObservedPageIndex = latestState.currentPageIndex
                                    animatePagerProgrammatically(prevBoundaryPage)
                                }
                            }
                        }
                    }
                },
            )
        }

    LaunchedEffect(stepHandlers) {
        onStepHandlersChanged(stepHandlers)
    }

    LaunchedEffect(state.currentPageIndex, state.currentSlotIndex, startBoundaryOffset, totalPagerPages) {
        val pageChanged = state.currentPageIndex != lastObservedPageIndex
        lastObservedPageIndex = state.currentPageIndex
        if (pageChanged) {
            settledBoundary = null
        } else if (settledBoundary == BoundaryResidency.Previous && !hasPrevBoundary) {
            settledBoundary = null
        } else if (settledBoundary == BoundaryResidency.Next && !hasNextBoundary) {
            settledBoundary = null
        }
        val maxPage = (totalPagerPages - 1).coerceAtLeast(0)
        val target =
            when (settledBoundary) {
                BoundaryResidency.Previous -> 0
                BoundaryResidency.Next -> (startBoundaryOffset + state.slots.size).coerceIn(0, maxPage)
                null -> (state.currentSlotIndex + startBoundaryOffset).coerceIn(0, maxPage)
            }
        if (pagerState.currentPage != target && (!pagerState.isScrollInProgress || programmaticScrollsInFlight > 0)) {
            boundaryScrollJob?.cancel()
            programmaticScrollsInFlight++
            try {
                pagerState.scrollToPage(target)
            } finally {
                programmaticScrollsInFlight--
            }
        }
    }

    LaunchedEffect(pagerState) {
        var lastObservedSettled = pagerState.settledPage
        snapshotFlow { pagerState.settledPage }
            .collect { settled ->
                if (settled == lastObservedSettled) return@collect
                lastObservedSettled = settled
                if (programmaticScrollsInFlight > 0) return@collect
                val prevBoundaryPage = if (latestHasPrevBoundary) 0 else -1
                val nextBoundaryPage = if (latestHasNextBoundary) latestStartOffset + latestSlots.size else -1
                when (settled) {
                    prevBoundaryPage -> {
                        settledBoundary = BoundaryResidency.Previous
                        lastObservedPageIndex = latestState.currentPageIndex
                    }

                    nextBoundaryPage -> {
                        settledBoundary = BoundaryResidency.Next
                        lastObservedPageIndex = latestState.currentPageIndex
                    }

                    else -> {
                        settledBoundary = null
                        val slotIndex = settled - latestStartOffset
                        if (slotIndex in latestSlots.indices && slotIndex != latestCurrentSlotIndex) {
                            latestState.goToSlot(slotIndex)
                            lastObservedPageIndex = latestState.currentPageIndex
                        }
                    }
                }
            }
    }

    val bookLayoutDirection = if (state.direction == MangaReadingDirection.Rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    HorizontalPager(
        state = pagerState,
        reverseLayout = LocalLayoutDirection.current != bookLayoutDirection,
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
                    onPageLongPress = onPageLongPress,
                    pageContextWrapper = pageContextWrapper,
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
    onPageLongPress: ((page: ReaderPageState, ready: PageMediaStatus.Ready) -> Unit)?,
    pageContextWrapper: (
        @Composable (
            page: ReaderPageState,
            ready: PageMediaStatus.Ready,
            content: @Composable () -> Unit,
        ) -> Unit
    )?,
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
    val currentOnPageLongPress by rememberUpdatedState(onPageLongPress)
    val currentPages by rememberUpdatedState(pages)

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .readerTapAndDoubleTap(
                    key = slot to (onPageLongPress != null),
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
                    onLongPress =
                        if (onPageLongPress != null) {
                            { tapOffset, containerSize ->
                                val handler = currentOnPageLongPress
                                if (handler != null) {
                                    val targetPageIndex =
                                        when (slot) {
                                            is ReaderSlot.Single -> {
                                                slot.pageIndex
                                            }

                                            is ReaderSlot.Double -> {
                                                val localX =
                                                    if (scale > ZOOM_EPSILON) {
                                                        (tapOffset.x - containerSize.width / 2f - offset.x) / scale +
                                                            containerSize.width / 2f
                                                    } else {
                                                        tapOffset.x
                                                    }
                                                if (localX < containerSize.width / 2f) {
                                                    slot.leftPageIndex
                                                } else {
                                                    slot.rightPageIndex
                                                }
                                            }
                                        }
                                    val page = currentPages.getOrNull(targetPageIndex)
                                    val ready = page?.status as? PageMediaStatus.Ready
                                    if (page != null && ready != null) {
                                        handler(page, ready)
                                    }
                                }
                            }
                        } else {
                            null
                        },
                ).pointerInput(slot) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        val downId = firstDown.id
                        val downPos = firstDown.position
                        val touchSlop = viewConfiguration.touchSlop
                        var pastSinglePanSlop = false
                        do {
                            val initialEvent = awaitPointerEvent(PointerEventPass.Initial)
                            val consumedUpstream = initialEvent.changes.any { it.isConsumed }
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val pressed = event.changes.count { it.pressed }
                            if (!consumedUpstream) {
                                if (pressed >= 2) {
                                    pastSinglePanSlop = true
                                } else if (pressed == 1 && scale > ZOOM_EPSILON && !pastSinglePanSlop) {
                                    val movedPastSlop =
                                        event.changes.any { change ->
                                            change.pressed &&
                                                (change.id != downId || (change.position - downPos).getDistance() > touchSlop)
                                        }
                                    if (movedPastSlop) {
                                        pastSinglePanSlop = true
                                    }
                                }
                                if (pressed >= 2 || (scale > ZOOM_EPSILON && pastSinglePanSlop)) {
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
                        pageContextWrapper = pageContextWrapper,
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
                                pageContextWrapper = pageContextWrapper,
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
                                pageContextWrapper = pageContextWrapper,
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
    constrainMaxWidthDp: Dp?,
    onZoneTap: (Offset, IntSize) -> Unit,
    onOpenLockedUrl: (String) -> Unit,
    onListStateReady: (LazyListState) -> Unit,
    onZoomResetChanged: ((() -> Unit)?) -> Unit,
    onPageLongPress: ((page: ReaderPageState, ready: PageMediaStatus.Ready) -> Unit)?,
    pageContextWrapper: (
        @Composable (
            page: ReaderPageState,
            ready: PageMediaStatus.Ready,
            content: @Composable () -> Unit,
        ) -> Unit
    )?,
) {
    val density = LocalDensity.current
    val maxStripWidthPx = constrainMaxWidthDp?.let { with(density) { it.roundToPx() }.coerceAtLeast(1) }
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
    var offsetY by remember { mutableFloatStateOf(0f) }
    val resetZoom =
        remember {
            {
                scale = 1f
                offsetX = 0f
                offsetY = 0f
            }
        }
    val isZoomed = scale > ZOOM_EPSILON
    LaunchedEffect(isZoomed) {
        onZoomResetChanged(if (isZoomed) resetZoom else null)
    }

    val syncState =
        remember {
            WebtoonScrollSyncState(
                initialPage = state.currentPageIndex,
                initialHeaderCount = headerCount,
            )
        }
    val latestState by rememberUpdatedState(state)
    val latestHeaderCount by rememberUpdatedState(headerCount)
    val latestPageCount by rememberUpdatedState(state.pages.size)
    val latestCurrentPageIndex by rememberUpdatedState(state.currentPageIndex)

    LaunchedEffect(state.currentPageIndex, headerCount, state.pages.size) {
        if (state.pages.isEmpty()) return@LaunchedEffect
        val headerChanged = headerCount != syncState.lastHeaderCount
        syncState.lastHeaderCount = headerCount
        if (state.currentPageIndex != syncState.lastReportedPage || headerChanged) {
            val targetItemIndex = state.currentPageIndex + headerCount
            syncState.lastReportedPage = state.currentPageIndex
            offsetY = 0f
            listState.scrollToItem(targetItemIndex)
            syncState.settledProgrammaticPos = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        } else if (syncState.settledProgrammaticPos == null && !listState.isScrollInProgress) {
            syncState.settledProgrammaticPos = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
    }

    LaunchedEffect(listState) {
        var lastObservedPos: Pair<Int, Int>? = null
        snapshotFlow {
            val pos = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            val visiblePage = listState.visiblePageIndex(latestHeaderCount, latestPageCount)
            pos to visiblePage
        }.collect { (pos, visiblePage) ->
            if (lastObservedPos == null) {
                lastObservedPos = pos
                return@collect
            }
            if (pos == lastObservedPos) return@collect
            lastObservedPos = pos
            if (pos == syncState.settledProgrammaticPos) return@collect
            syncState.settledProgrammaticPos = null
            if (visiblePage != null) {
                syncState.lastReportedPage = visiblePage
                if (visiblePage != latestCurrentPageIndex) {
                    latestState.goToPage(visiblePage)
                }
            }
        }
    }

    val gestureScope = rememberCoroutineScope()
    val currentOnZoneTap by rememberUpdatedState(onZoneTap)
    val currentOnPageLongPress by rememberUpdatedState(onPageLongPress)

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .readerTapAndDoubleTap(
                    key = onPageLongPress != null,
                    scope = gestureScope,
                    onTap = { tapOffset, containerSize ->
                        currentOnZoneTap(tapOffset, containerSize)
                    },
                    onDoubleTap = { tapOffset, containerSize ->
                        if (scale > ZOOM_EPSILON) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            val targetScale = DOUBLE_TAP_ZOOM
                            val centerX = containerSize.width / 2f
                            val centerY = containerSize.height / 2f
                            val rawOffsetX = (centerX - tapOffset.x) * (targetScale - 1f)
                            val desiredScreenDeltaY = (centerY - tapOffset.y) * (targetScale - 1f)
                            val maxX = (containerSize.width * (targetScale - 1f)) / 2f
                            val maxY = (containerSize.height * (targetScale - 1f)) / 2f
                            val consumedByList =
                                if (desiredScreenDeltaY != 0f) {
                                    listState.dispatchRawDelta(-desiredScreenDeltaY / targetScale)
                                } else {
                                    0f
                                }
                            val unconsumedScreenY = desiredScreenDeltaY + consumedByList * targetScale
                            scale = targetScale
                            offsetX = rawOffsetX.coerceIn(-maxX, maxX)
                            offsetY = unconsumedScreenY.coerceIn(-maxY, maxY)
                        }
                    },
                    onLongPress =
                        if (onPageLongPress != null) {
                            { tapOffset, containerSize ->
                                val handler = currentOnPageLongPress
                                if (handler != null) {
                                    val listY =
                                        if (scale > ZOOM_EPSILON) {
                                            (tapOffset.y - containerSize.height / 2f - offsetY) / scale + containerSize.height / 2f
                                        } else {
                                            tapOffset.y
                                        }
                                    val hitItem =
                                        listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                            listY >= item.offset && listY < item.offset + item.size
                                        }
                                    val pageIndex = hitItem?.let { it.index - latestHeaderCount }
                                    val page = pageIndex?.let(latestState.pages::getOrNull)
                                    val ready = page?.status as? PageMediaStatus.Ready
                                    if (page != null && ready != null) {
                                        handler(page, ready)
                                    }
                                }
                            }
                        } else {
                            null
                        },
                ).pointerInput(listState) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        val downId = firstDown.id
                        val downPos = firstDown.position
                        val touchSlop = viewConfiguration.touchSlop
                        var pastSinglePanSlop = false
                        do {
                            val initialEvent = awaitPointerEvent(PointerEventPass.Initial)
                            val consumedUpstream = initialEvent.changes.any { it.isConsumed }
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val pressed = event.changes.count { it.pressed }
                            if (!consumedUpstream) {
                                if (pressed >= 2) {
                                    pastSinglePanSlop = true
                                } else if (pressed == 1 && scale > ZOOM_EPSILON && !pastSinglePanSlop) {
                                    val movedPastSlop =
                                        event.changes.any { change ->
                                            change.pressed &&
                                                (change.id != downId || (change.position - downPos).getDistance() > touchSlop)
                                        }
                                    if (movedPastSlop) {
                                        pastSinglePanSlop = true
                                    }
                                }
                                if (pressed >= 2 || (scale > ZOOM_EPSILON && pastSinglePanSlop)) {
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()
                                    val newScale = (scale * zoomChange).coerceIn(1f, MAX_READER_ZOOM)
                                    if (newScale <= ZOOM_EPSILON) {
                                        scale = 1f
                                        offsetX = 0f
                                        offsetY = 0f
                                    } else {
                                        val maxX = (size.width * (newScale - 1f)) / 2f
                                        val maxY = (size.height * (newScale - 1f)) / 2f
                                        scale = newScale
                                        offsetX = (offsetX + panChange.x).coerceIn(-maxX, maxX)
                                        var nextOffsetY = offsetY.coerceIn(-maxY, maxY)
                                        if (panChange.y != 0f) {
                                            var remY = panChange.y
                                            if (nextOffsetY != 0f && nextOffsetY * remY < 0f) {
                                                val drained =
                                                    if (nextOffsetY > 0f) {
                                                        maxOf(remY, -nextOffsetY)
                                                    } else {
                                                        minOf(remY, -nextOffsetY)
                                                    }
                                                nextOffsetY += drained
                                                remY -= drained
                                            }
                                            if (remY != 0f) {
                                                val consumedByList = listState.dispatchRawDelta(-remY / newScale)
                                                val unconsumedScreenY = remY + consumedByList * newScale
                                                nextOffsetY = (nextOffsetY + unconsumedScreenY).coerceIn(-maxY, maxY)
                                            }
                                        }
                                        offsetY = nextOffsetY
                                    }
                                    if (pressed >= 2 || scale > ZOOM_EPSILON) {
                                        event.changes.forEach { change ->
                                            if (change.positionChanged()) {
                                                change.consume()
                                            }
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
                    translationY = offsetY
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
                key = { "page_${it.id}" },
            ) { page ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    ReaderPageCell(
                        page = page,
                        state = state,
                        contentScale = ContentScale.Fit,
                        contentAlignment = Alignment.TopCenter,
                        pageContextWrapper = pageContextWrapper,
                        modifier =
                            Modifier
                                .webtoonPageSize(
                                    aspectRatio = page.aspectRatio ?: DEFAULT_WEBTOON_ASPECT_RATIO,
                                    maxStripWidthPx = maxStripWidthPx,
                                ).testTag("manga_reader_page_${page.index}"),
                    )
                }
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
    pageContextWrapper: (
        @Composable (
            page: ReaderPageState,
            ready: PageMediaStatus.Ready,
            content: @Composable () -> Unit,
        ) -> Unit
    )? = null,
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
            var retryNonce by remember(page.id, status.displayed.url) { mutableIntStateOf(0) }
            var loadError by remember(page.id, status.displayed.url) { mutableStateOf<Throwable?>(null) }
            val baseRequest = rememberMediaImageRequest(status.displayed, crossfade = false)
            val fullSizeRequest =
                remember(baseRequest, retryNonce) {
                    baseRequest
                        .newBuilder()
                        .size(Size.ORIGINAL)
                        .apply {
                            if (retryNonce > 0) {
                                extras[readerRetryNonceExtra] = retryNonce
                                memoryCachePolicy(CachePolicy.WRITE_ONLY)
                            }
                        }.build()
                }
            var imageLoading by remember(page.id, status.displayed.url, retryNonce) {
                mutableStateOf(status.displayed.isRemote || retryNonce > 0)
            }

            val currentError = loadError
            if (currentError != null) {
                ReaderPageErrorContent(
                    error = currentError,
                    pageId = page.id,
                    onRetry = {
                        loadError = null
                        retryNonce++
                        state.retryPage(page.id)
                    },
                    modifier = modifier,
                )
            } else {
                val requestReady = fullSizeRequest.data != NullRequestData
                val readyContent: @Composable () -> Unit = {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = contentAlignment,
                    ) {
                        if (requestReady) {
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
                                    loadError = null
                                    val decoded = success.result.image
                                    state.onPageDimensionsLoaded(
                                        pageId = page.id,
                                        width = decoded.width.toFloat(),
                                        height = decoded.height.toFloat(),
                                    )
                                },
                                onError = { error ->
                                    imageLoading = false
                                    if (status.displayed.url != status.original.url) {
                                        state.imageTranslation.imageFailed(status.original, status.displayed.url)
                                    }
                                    loadError = error.result.throwable
                                },
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .blur(blurRadius),
                            )
                        }
                        if (!requestReady || translating || imageLoading) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                PlatformCircularProgressIndicator()
                            }
                        }
                    }
                }
                Box(
                    modifier = modifier,
                    contentAlignment = contentAlignment,
                ) {
                    if (pageContextWrapper != null) {
                        pageContextWrapper(page, status, readyContent)
                    } else {
                        readyContent()
                    }
                }
            }
        }

        is PageMediaStatus.Failed -> {
            ReaderPageErrorContent(
                error = status.error,
                pageId = page.id,
                onRetry = { state.retryPage(page.id) },
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun ReaderPageErrorContent(
    error: Throwable,
    pageId: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlatformText(
                text = error.message?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.manga_page_retry),
                style = PlatformTheme.typography.body,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )
            PlatformFilledTonalButton(
                onClick = onRetry,
                modifier = Modifier.testTag("manga_page_retry_$pageId"),
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

private fun Modifier.webtoonPageSize(
    aspectRatio: Float,
    maxStripWidthPx: Int? = null,
): Modifier =
    layout { measurable, constraints ->
        val width =
            if (maxStripWidthPx != null) {
                minOf(constraints.maxWidth, maxStripWidthPx)
            } else {
                constraints.maxWidth
            }
        val height = (width / aspectRatio).roundToInt().coerceIn(1, Constraints.Infinity - 1)
        val page = measurable.measure(Constraints.fitPrioritizingWidth(width, width, height, height))
        layout(page.width, page.height) { page.place(0, 0) }
    }

private fun LazyListState.visiblePageIndex(
    headerCount: Int,
    pageCount: Int,
): Int? {
    val info = layoutInfo
    if (pageCount == 0 || info.visibleItemsInfo.isEmpty()) return null
    if (!canScrollBackward) return 0
    if (!canScrollForward) return pageCount - 1
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
    val item = info.visibleItemsInfo.firstOrNull { center in it.offset until it.offset + it.size } ?: return null
    return (item.index - headerCount).coerceIn(0, pageCount - 1)
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
    onLongPress: ((Offset, IntSize) -> Unit)? = null,
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
            } else if (onLongPress == null) {
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
            } else {
                val downId = down.id
                val downPos = down.position
                val containerSize = size
                val touchSlop = viewConfiguration.touchSlop
                val longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis
                var longPressTriggered = false
                val longPressJob =
                    scope.launch {
                        val start = withFrameMillis { it }
                        while (withFrameMillis { it } - start < longPressTimeoutMs) {
                            // Yield frames on MonotonicFrameClock so both real runtime and ComposeUiTest advance cleanly.
                        }
                        longPressTriggered = true
                        pendingTapJob?.cancel()
                        pendingTapJob = null
                        lastTapUpTimeMillis = -1L
                        onLongPress(downPos, containerSize)
                    }
                try {
                    var gestureActive = true
                    var tapCandidate = true
                    while (gestureActive) {
                        val initialEvent = awaitPointerEvent(PointerEventPass.Initial)
                        if (longPressTriggered) {
                            initialEvent.changes.forEach { it.consume() }
                            if (initialEvent.changes.none { it.pressed }) {
                                gestureActive = false
                            }
                            continue
                        }
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (longPressTriggered) {
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) {
                                gestureActive = false
                            }
                        } else if (event.changes.all { it.changedToUp() }) {
                            longPressJob.cancel()
                            gestureActive = false
                            if (tapCandidate) {
                                val up = event.changes[0]
                                up.consume()
                                val tapPos = up.position
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
                        } else {
                            val pressedCount = event.changes.count { it.pressed }
                            val singleSamePointer = pressedCount == 1 && event.changes.all { !it.pressed || it.id == downId }
                            val movedPastSlop = event.changes.any { (it.position - downPos).getDistance() > touchSlop }
                            val cancelledOrConsumed =
                                event.changes.any { it.isConsumed || it.isOutOfBounds(size, extendedTouchPadding) }
                            if (!singleSamePointer || movedPastSlop || cancelledOrConsumed) {
                                longPressJob.cancel()
                                tapCandidate = false
                                gestureActive = false
                            } else {
                                val consumeCheck = awaitPointerEvent(PointerEventPass.Final)
                                if (longPressTriggered) {
                                    consumeCheck.changes.forEach { it.consume() }
                                    if (consumeCheck.changes.none { it.pressed }) {
                                        gestureActive = false
                                    }
                                } else if (consumeCheck.changes.any { it.isConsumed }) {
                                    longPressJob.cancel()
                                    tapCandidate = false
                                    gestureActive = false
                                } else if (consumeCheck.changes.none { it.pressed }) {
                                    longPressJob.cancel()
                                    gestureActive = false
                                }
                            }
                        }
                    }
                } finally {
                    if (!longPressTriggered) {
                        longPressJob.cancel()
                    }
                }
            }
        }
    }
