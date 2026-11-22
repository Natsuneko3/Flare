package dev.dimension.flare.ui.component.manga

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.intercept.Interceptor
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.size.Size
import coil3.size.SizeResolver
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.MangaReadingDirection
import dev.dimension.flare.ui.model.UiMangaChapter
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.presenter.manga.ChapterBoundary
import dev.dimension.flare.ui.presenter.manga.MangaReaderPresenter
import dev.dimension.flare.ui.presenter.manga.PageMediaStatus
import dev.dimension.flare.ui.presenter.manga.ReaderPageState
import dev.dimension.flare.ui.presenter.manga.ReaderSlot
import dev.dimension.flare.ui.presenter.media.ImageTranslationItem
import dev.dimension.flare.ui.presenter.media.ImageTranslationPresenter
import io.github.composefluent.FluentTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, coil3.annotation.DelicateCoilApi::class)
class MangaReaderCanvasTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        SingletonImageLoader.setUnsafe { context ->
            ImageLoader
                .Builder(context)
                .components {
                    add(
                        Fetcher.Factory<Any> { data, _, _ ->
                            val text = data.toString()
                            if (!text.startsWith("local-")) return@Factory null
                            Fetcher {
                                ImageFetchResult(
                                    image = ColorImage(color = 0xFF445566.toInt(), width = 1000, height = 1400),
                                    isSampled = false,
                                    dataSource = DataSource.MEMORY,
                                )
                            }
                        },
                    )
                }.build()
        }
    }

    @After
    fun tearDown() {
        SingletonImageLoader.reset()
    }

    @Test
    fun rtlScrubberAndTapZonesAdvanceRightToLeft() {
        val fakeState =
            FakeMangaReaderState(
                initialDirection = MangaReadingDirection.Rtl,
                initialPages = samplePages(4),
                initialSlots =
                    persistentListOf(
                        ReaderSlot.Double(leftPageIndex = 1, rightPageIndex = 0, primaryPageIndex = 0),
                        ReaderSlot.Double(leftPageIndex = 3, rightPageIndex = 2, primaryPageIndex = 2),
                    ),
            )
        var hudVisible by mutableStateOf(true)
        var containerWidth by mutableStateOf(800.dp)

        composeRule.setContent {
            FluentTheme {
                Box(Modifier.size(width = containerWidth, height = 600.dp)) {
                    MangaReaderCanvas(
                        state = fakeState,
                        hudVisible = hudVisible,
                        onToggleHud = { hudVisible = !hudVisible },
                        modifier = Modifier.testTag("reader_canvas"),
                    )
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals(true, fakeState.lastWideViewport)
        }

        // 3. Double-page slot renders rightPageIndex (0) on the right and leftPageIndex (1) on the left
        val leftPageBounds = composeRule.onNodeWithTag("manga_reader_page_1").fetchSemanticsNode().boundsInRoot
        val rightPageBounds = composeRule.onNodeWithTag("manga_reader_page_0").fetchSemanticsNode().boundsInRoot
        assertTrue(
            leftPageBounds.left < rightPageBounds.left,
            "Expected leftPageIndex (1) on the physical left (${leftPageBounds.left}) of rightPageIndex (0) (${rightPageBounds.left})",
        )
        assertTrue(
            leftPageBounds.right <= rightPageBounds.left + 1f,
            "Expected leftPageIndex right edge (${leftPageBounds.right}) <= rightPageIndex left edge (${rightPageBounds.left})",
        )

        // 1. In RTL mode: tapping left 30% zone invokes stepForward(); tapping right 30% invokes stepBackward();
        //    tapping center 40% toggles HUD
        val canvasBounds = composeRule.onNodeWithTag("reader_canvas").fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("reader_canvas").performTouchInput {
            click(Offset(canvasBounds.width * 0.15f, canvasBounds.height * 0.5f))
        }
        composeRule.runOnIdle {
            assertEquals(1, fakeState.stepForwardCount)
            assertEquals(1, fakeState.currentSlotIndex)
        }

        composeRule.onNodeWithTag("reader_canvas").performTouchInput {
            click(Offset(canvasBounds.width * 0.85f, canvasBounds.height * 0.5f))
        }
        composeRule.runOnIdle {
            assertEquals(1, fakeState.stepBackwardCount)
            assertEquals(0, fakeState.currentSlotIndex)
        }

        composeRule.onNodeWithTag("reader_canvas").performTouchInput {
            click(Offset(canvasBounds.width * 0.5f, canvasBounds.height * 0.5f))
        }
        composeRule.runOnIdle {
            assertEquals(false, hudVisible)
        }

        // 2. Pressing Key.DirectionLeft advances in RTL and steps back in LTR
        composeRule.onNodeWithTag("reader_canvas").performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        composeRule.runOnIdle {
            assertEquals(2, fakeState.stepForwardCount)
            assertEquals(1, fakeState.currentSlotIndex)
        }

        composeRule.runOnIdle {
            fakeState.setDirection(MangaReadingDirection.Ltr)
        }
        composeRule.onNodeWithTag("reader_canvas").performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        composeRule.runOnIdle {
            assertEquals(2, fakeState.stepBackwardCount)
            assertEquals(0, fakeState.currentSlotIndex)
        }

        // Narrow viewport updates setWideViewport(false)
        composeRule.runOnIdle {
            containerWidth = 500.dp
        }
        composeRule.runOnIdle {
            assertEquals(false, fakeState.lastWideViewport)
        }
    }

    @Test
    fun webtoonRendersZeroSpacingStripAndBoundaryCards() {
        val nextChapter = UiMangaChapter("ch-2", "Chapter 2", null, null, locked = false, url = null)
        val fakeState =
            FakeMangaReaderState(
                initialDirection = MangaReadingDirection.Webtoon,
                initialPages = samplePages(2),
                initialSlots = persistentListOf(ReaderSlot.Single(0), ReaderSlot.Single(1)),
                nextBoundary = ChapterBoundary.Unlocked(chapter = nextChapter, startFromEnd = false),
            )

        composeRule.setContent {
            FluentTheme {
                Box(Modifier.size(width = 400.dp, height = 900.dp)) {
                    MangaReaderCanvas(
                        state = fakeState,
                        hudVisible = false,
                        onToggleHud = {},
                        modifier = Modifier.testTag("reader_canvas"),
                    )
                }
            }
        }

        val page0Bounds = composeRule.onNodeWithTag("manga_reader_page_0").fetchSemanticsNode().boundsInRoot
        val page1Bounds = composeRule.onNodeWithTag("manga_reader_page_1").fetchSemanticsNode().boundsInRoot
        assertTrue(
            abs(page0Bounds.bottom - page1Bounds.top) <= 1f,
            "Expected zero vertical gap in Webtoon mode, got ${page0Bounds.bottom} vs ${page1Bounds.top}",
        )

        composeRule.onNodeWithTag("manga_webtoon_list").performTouchInput {
            swipeUp()
        }
        composeRule.onNodeWithText("Chapter 2").assertExists()
        composeRule.onNodeWithTag("manga_boundary_next_action").performClick()
        composeRule.runOnIdle {
            assertEquals("ch-2", fakeState.openedBoundaryChapterId)
        }
    }

    @Test
    fun scaleOneYieldsHorizontalSwipeWhileZoomLocksPager() {
        val fakeState =
            FakeMangaReaderState(
                initialDirection = MangaReadingDirection.Ltr,
                initialPages = samplePages(3),
                initialSlots = persistentListOf(ReaderSlot.Single(0), ReaderSlot.Single(1), ReaderSlot.Single(2)),
            )

        composeRule.setContent {
            FluentTheme {
                Box(Modifier.size(width = 600.dp, height = 800.dp)) {
                    MangaReaderCanvas(
                        state = fakeState,
                        hudVisible = false,
                        onToggleHud = {},
                        modifier = Modifier.testTag("reader_canvas"),
                    )
                }
            }
        }

        // At scale == 1f, horizontal swipe yields to HorizontalPager and advances slot
        composeRule.onNodeWithTag("reader_canvas").performTouchInput {
            swipeLeft()
        }
        composeRule.runOnIdle {
            assertEquals(1, fakeState.currentSlotIndex)
        }

        // Double-tap zooms in (> 1f) and locks HorizontalPager against accidental page swipe
        composeRule.onNodeWithTag("reader_canvas").performTouchInput {
            doubleClick(center)
        }
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.onNodeWithTag("reader_canvas").performTouchInput {
            swipeLeft()
        }
        composeRule.runOnIdle {
            assertEquals(1, fakeState.currentSlotIndex)
        }

        // Double-tap resets zoom to 1f and restores HorizontalPager swipe
        composeRule.onNodeWithTag("reader_canvas").performTouchInput {
            doubleClick(center)
        }
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.onNodeWithTag("reader_canvas").performTouchInput {
            swipeLeft()
        }
        composeRule.runOnIdle {
            assertEquals(2, fakeState.currentSlotIndex)
        }
    }

    @Test
    fun reportsDecodedDimensionsAndPreservesCustomHeadersOnImageRequests() {
        val recordedOptions = CopyOnWriteArrayList<Pair<String, Options>>()
        val recordedRequests = CopyOnWriteArrayList<Pair<String, ImageRequest>>()
        SingletonImageLoader.setUnsafe { context ->
            ImageLoader
                .Builder(context)
                .components {
                    add(
                        Interceptor { chain ->
                            recordedRequests += chain.request.data.toString() to chain.request
                            chain.proceed()
                        },
                    )
                    add(
                        Fetcher.Factory<Any> { data, options, _ ->
                            val text = data.toString()
                            if (!text.startsWith("https://manga.test/")) return@Factory null
                            Fetcher {
                                recordedOptions += text to options
                                ImageFetchResult(
                                    image = ColorImage(color = 0xFF336699.toInt(), width = 1200, height = 1800),
                                    isSampled = false,
                                    dataSource = DataSource.MEMORY,
                                )
                            }
                        },
                    )
                }.build()
        }

        val proxy = ProxyRef.forAccount(MicroBlogKey("u1", "manga.test"))
        val headers: ImmutableMap<String, String> = persistentMapOf("Referer" to "https://manga.test/chapter/1")
        val page0Media =
            UiMedia.Image(
                url = "https://manga.test/p0.jpg",
                previewUrl = "https://manga.test/p0.jpg",
                description = null,
                height = 0f,
                width = 0f,
                sensitive = false,
                customHeaders = headers,
                proxy = proxy,
            )
        val prefetchMedia =
            UiMedia.Image(
                url = "https://manga.test/p1.jpg",
                previewUrl = "https://manga.test/p1.jpg",
                description = null,
                height = 0f,
                width = 0f,
                sensitive = false,
                customHeaders = headers,
                proxy = proxy,
            )

        val fakeState =
            FakeMangaReaderState(
                initialDirection = MangaReadingDirection.Ltr,
                initialPages =
                    persistentListOf(
                        ReaderPageState(
                            id = "p0",
                            index = 0,
                            status = PageMediaStatus.Ready(original = page0Media, displayed = page0Media),
                            aspectRatio = null,
                        ),
                    ),
                initialSlots = persistentListOf(ReaderSlot.Single(0)),
                prefetchMedia = persistentListOf(prefetchMedia),
            )

        composeRule.setContent {
            FluentTheme {
                Box(Modifier.size(width = 600.dp, height = 800.dp)) {
                    MangaReaderCanvas(
                        state = fakeState,
                        hudVisible = false,
                        onToggleHud = {},
                    )
                }
            }
        }

        composeRule.waitUntil(5_000) {
            fakeState.loadedDimensions["p0"] == (1200f to 1800f) &&
                recordedOptions.any { it.first == "https://manga.test/p1.jpg" }
        }

        val pageOptions = recordedOptions.first { it.first == "https://manga.test/p0.jpg" }.second
        val prefetchOptions = recordedOptions.first { it.first == "https://manga.test/p1.jpg" }.second
        val prefetchRequest = recordedRequests.first { it.first == "https://manga.test/p1.jpg" }.second
        assertEquals("https://manga.test/chapter/1", pageOptions.httpHeaders["Referer"])
        assertEquals("https://manga.test/chapter/1", prefetchOptions.httpHeaders["Referer"])
        assertEquals(Size.ORIGINAL, pageOptions.size)
        assertEquals(Size.ORIGINAL, prefetchOptions.size)
        assertEquals(SizeResolver.ORIGINAL, prefetchRequest.defined.sizeResolver)
    }

    @Test
    fun boundaryPagerPagesStepInwardWithoutSkippingAndOutwardWhenUnlocked() {
        val prevChapter = UiMangaChapter("ch-0", "Chapter 0", null, null, locked = false, url = null)
        val nextChapter = UiMangaChapter("ch-2", "Chapter 2", null, null, locked = false, url = null)
        val lockedChapter = UiMangaChapter("ch-locked", "Locked Epilogue", null, null, locked = true, url = "https://manga.test/locked")
        var currentState by
            mutableStateOf(
                FakeMangaReaderState(
                    initialDirection = MangaReadingDirection.Ltr,
                    initialPages = samplePages(2),
                    initialSlots = persistentListOf(ReaderSlot.Single(0), ReaderSlot.Single(1)),
                    previousBoundary = ChapterBoundary.Unlocked(chapter = prevChapter, startFromEnd = true),
                    nextBoundary = ChapterBoundary.Unlocked(chapter = nextChapter, startFromEnd = false),
                ),
            )
        val fakeState = currentState

        composeRule.setContent {
            FluentTheme {
                Box(Modifier.size(width = 600.dp, height = 800.dp)) {
                    MangaReaderCanvas(
                        state = currentState,
                        hudVisible = false,
                        onToggleHud = {},
                        modifier = Modifier.testTag("reader_canvas"),
                    )
                }
            }
        }

        // Advance to last slot (slot 1), then swipe onto isNextBoundaryPage
        composeRule.runOnIdle {
            fakeState.goToSlot(1)
        }
        composeRule.onNodeWithTag("reader_canvas").performTouchInput {
            swipeLeft()
        }
        composeRule.onNodeWithTag("manga_boundary_next_action").assertExists()

        // Stepping backward from isNextBoundaryPage via keyboard must return to slot 1 (NOT skip to slot 0)
        composeRule.onNodeWithTag("reader_canvas").performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        composeRule.onNodeWithTag("manga_reader_page_1").assertExists()
        composeRule.runOnIdle {
            assertEquals(1, fakeState.currentSlotIndex)
        }

        // Swipe onto isNextBoundaryPage again and step outward (right 30% tap in LTR) -> opens unlocked nextChapter
        composeRule.onNodeWithTag("reader_canvas").performTouchInput {
            swipeLeft()
        }
        composeRule.onNodeWithTag("manga_boundary_next_action").assertExists()
        val canvasBounds = composeRule.onNodeWithTag("reader_canvas").fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("reader_canvas").performTouchInput {
            click(Offset(canvasBounds.width * 0.85f, canvasBounds.height * 0.5f))
        }
        composeRule.runOnIdle {
            assertEquals("ch-2", fakeState.openedBoundaryChapterId)
        }

        // Move to slot 0 and swipe right onto isPreviousBoundaryPage
        composeRule.runOnIdle {
            fakeState.goToSlot(0)
        }
        composeRule.onNodeWithTag("reader_canvas").performTouchInput {
            swipeRight()
        }
        composeRule.onNodeWithTag("manga_boundary_prev_action").assertExists()

        // Stepping forward from isPreviousBoundaryPage via right 30% tap in LTR must return to slot 0 (NOT skip to slot 1)
        composeRule.onNodeWithTag("reader_canvas").performTouchInput {
            click(Offset(canvasBounds.width * 0.85f, canvasBounds.height * 0.5f))
        }
        composeRule.onNodeWithTag("manga_reader_page_0").assertExists()
        composeRule.runOnIdle {
            assertEquals(0, fakeState.currentSlotIndex)
        }

        // Swipe onto isPreviousBoundaryPage again and step outward via Key.DirectionLeft -> opens unlocked prevChapter
        composeRule.onNodeWithTag("reader_canvas").performTouchInput {
            swipeRight()
        }
        composeRule.onNodeWithTag("manga_boundary_prev_action").assertExists()
        composeRule.onNodeWithTag("reader_canvas").performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        composeRule.runOnIdle {
            assertEquals("ch-0", fakeState.openedBoundaryChapterId)
        }

        // Single-slot chapter with previousBoundary = None and nextBoundary = Locked:
        // stepping backward from isNextBoundaryPage must return to slot 0 instead of getting stuck
        val singleSlotLockedState =
            FakeMangaReaderState(
                activeChapterId = "ch-single",
                initialDirection = MangaReadingDirection.Ltr,
                initialPages = samplePages(1),
                initialSlots = persistentListOf(ReaderSlot.Single(0)),
                previousBoundary = ChapterBoundary.None,
                nextBoundary = ChapterBoundary.Locked(chapter = lockedChapter, openUrl = "https://manga.test/locked"),
            )
        composeRule.runOnIdle {
            currentState = singleSlotLockedState
        }
        composeRule.onNodeWithTag("reader_canvas").performTouchInput {
            swipeLeft()
        }
        composeRule.onNodeWithTag("manga_boundary_next_action").assertExists()
        composeRule.onNodeWithTag("reader_canvas").performTouchInput {
            click(Offset(canvasBounds.width * 0.15f, canvasBounds.height * 0.5f))
        }
        composeRule.onNodeWithTag("manga_reader_page_0").assertExists()
    }

    @Test
    fun webtoonSyncsPartiallyVisibleScrubAndZoomedVerticalPan() {
        val pages =
            (0 until 4)
                .map { idx ->
                    val media = UiMedia.Image("local-webtoon-$idx", "local-webtoon-$idx", null, 400f, 400f, false)
                    ReaderPageState(
                        id = "wp-$idx",
                        index = idx,
                        status = PageMediaStatus.Ready(original = media, displayed = media),
                        aspectRatio = 1f,
                    )
                }.toImmutableList()
        val fakeState =
            FakeMangaReaderState(
                initialDirection = MangaReadingDirection.Webtoon,
                initialPages = pages,
                initialSlots = (0 until 4).map(ReaderSlot::Single).toImmutableList(),
            )

        composeRule.setContent {
            FluentTheme {
                Box(Modifier.size(width = 400.dp, height = 600.dp)) {
                    MangaReaderCanvas(
                        state = fakeState,
                        hudVisible = false,
                        onToggleHud = {},
                        modifier = Modifier.testTag("reader_canvas"),
                    )
                }
            }
        }

        // Drag upward by ~320px so page 0 still has ~80px visible at the top while page 1 covers viewport center (300px)
        composeRule.onNodeWithTag("manga_webtoon_list").performTouchInput {
            down(Offset(200f, 450f))
            moveTo(Offset(200f, 130f))
            up()
        }
        composeRule.runOnIdle {
            assertEquals(1, fakeState.currentPageIndex)
        }
        val page0PartiallyScrolledNode = composeRule.onNodeWithTag("manga_reader_page_0").fetchSemanticsNode()
        val partialY = page0PartiallyScrolledNode.positionInRoot.y
        val partialHeight = page0PartiallyScrolledNode.boundsInRoot.height
        assertTrue(
            partialY < -100f && partialHeight < 200f,
            "Expected page 0 to remain partially scrolled off top (not snapped), got pos=$partialY, visibleHeight=$partialHeight",
        )

        // Scrubbing back to page 0 externally must scroll page 0 back to top even though it was partially visible
        composeRule.runOnIdle {
            fakeState.goToPage(0)
        }
        composeRule.runOnIdle {}
        val page0RestoredNode = composeRule.onNodeWithTag("manga_reader_page_0").fetchSemanticsNode()
        val page0RestoredTop = page0RestoredNode.positionInRoot.y
        assertTrue(
            abs(page0RestoredTop) <= 2f,
            "Expected page 0 to scroll back to top=0 after goToPage(0), got top=$page0RestoredTop",
        )

        // Zoom in via double-tap, then drag vertically while zoomed -> must update currentPageIndex via dispatchRawDelta
        composeRule.onNodeWithTag("reader_canvas").performTouchInput {
            doubleClick(center)
        }
        composeRule.mainClock.advanceTimeBy(400)
        repeat(3) {
            composeRule.onNodeWithTag("reader_canvas").performTouchInput {
                down(Offset(200f, 520f))
                moveTo(Offset(200f, 60f))
                up()
            }
        }
        composeRule.runOnIdle {
            assertTrue(
                fakeState.currentPageIndex >= 1,
                "Expected zoomed vertical drag to advance currentPageIndex >= 1, got ${fakeState.currentPageIndex}",
            )
        }
    }

    @Test
    fun readyPageFetchErrorShowsRetryAndRecoversOnRetryClick() {
        var fetchAttempts = 0
        SingletonImageLoader.setUnsafe { context ->
            ImageLoader
                .Builder(context)
                .components {
                    add(
                        Fetcher.Factory<Any> { data, _, _ ->
                            val text = data.toString()
                            if (text != "https://manga.test/flaky.jpg") return@Factory null
                            Fetcher {
                                fetchAttempts++
                                if (fetchAttempts == 1) {
                                    throw IllegalStateException("CDN timeout")
                                }
                                ImageFetchResult(
                                    image = ColorImage(color = 0xFF225588.toInt(), width = 900, height = 1350),
                                    isSampled = false,
                                    dataSource = DataSource.NETWORK,
                                )
                            }
                        },
                    )
                }.build()
        }

        val flakyMedia =
            UiMedia.Image(
                url = "https://manga.test/flaky.jpg",
                previewUrl = "https://manga.test/flaky.jpg",
                description = null,
                height = 0f,
                width = 0f,
                sensitive = false,
                proxy = ProxyRef.Global,
            )
        val fakeState =
            FakeMangaReaderState(
                initialDirection = MangaReadingDirection.Ltr,
                initialPages =
                    persistentListOf(
                        ReaderPageState(
                            id = "flaky-0",
                            index = 0,
                            status = PageMediaStatus.Ready(original = flakyMedia, displayed = flakyMedia),
                            aspectRatio = null,
                        ),
                    ),
                initialSlots = persistentListOf(ReaderSlot.Single(0)),
            )

        composeRule.setContent {
            FluentTheme {
                Box(Modifier.size(width = 600.dp, height = 800.dp)) {
                    MangaReaderCanvas(
                        state = fakeState,
                        hudVisible = false,
                        onToggleHud = {},
                    )
                }
            }
        }

        composeRule.waitUntil(5_000) {
            runCatching {
                composeRule.onNodeWithTag("manga_page_retry_flaky-0").fetchSemanticsNode()
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithText("CDN timeout").assertExists()

        composeRule.onNodeWithTag("manga_page_retry_flaky-0").performClick()

        composeRule.waitUntil(5_000) {
            fakeState.loadedDimensions["flaky-0"] == (900f to 1350f)
        }
        composeRule.runOnIdle {
            assertEquals(listOf("flaky-0"), fakeState.retriedPageIds)
            assertEquals(2, fetchAttempts)
        }
        composeRule.onNodeWithTag("manga_page_retry_flaky-0").assertDoesNotExist()
    }

    @Test
    fun lockedBoundaryReachableByTapAndKeyboardAndPreservedAcrossSlotReflow() {
        val prevLocked = UiMangaChapter("ch-0", "Locked Prologue", null, null, locked = true, url = "https://manga.test/paywall-0")
        val nextLocked = UiMangaChapter("ch-2", "Locked Epilogue", null, null, locked = true, url = "https://manga.test/paywall-2")
        val fakeState =
            FakeMangaReaderState(
                initialDirection = MangaReadingDirection.Rtl,
                initialPages = samplePages(3),
                initialSlots =
                    persistentListOf(
                        ReaderSlot.Double(leftPageIndex = 1, rightPageIndex = 0, primaryPageIndex = 0),
                        ReaderSlot.Single(pageIndex = 2),
                    ),
                previousBoundary = ChapterBoundary.Locked(chapter = prevLocked, openUrl = "https://manga.test/paywall-0"),
                nextBoundary = ChapterBoundary.Locked(chapter = nextLocked, openUrl = "https://manga.test/paywall-2"),
            )

        composeRule.setContent {
            FluentTheme {
                Box(Modifier.size(width = 800.dp, height = 600.dp)) {
                    MangaReaderCanvas(
                        state = fakeState,
                        hudVisible = false,
                        onToggleHud = {},
                        modifier = Modifier.testTag("reader_canvas"),
                    )
                }
            }
        }

        val canvasBounds = composeRule.onNodeWithTag("reader_canvas").fetchSemanticsNode().boundsInRoot

        // 1. Move to last slot (slot 1, page 2), then tap left 30% zone (forward in RTL) -> scrolls onto Locked nextBoundary card!
        composeRule.runOnIdle {
            fakeState.goToSlot(1)
        }
        composeRule.onNodeWithTag("reader_canvas").performTouchInput {
            click(Offset(canvasBounds.width * 0.15f, canvasBounds.height * 0.5f))
        }
        composeRule.onNodeWithTag("manga_boundary_next_action").assertExists()

        // 2. While settled on nextBoundary card, split slot 0 so totalPagerPages grows 4 -> 5 (and currentSlotIndex shifts 1 -> 2
        //    without currentPageIndex changing): user MUST remain on the Locked nextBoundary card without yanking back!
        composeRule.runOnIdle {
            fakeState.slots =
                persistentListOf(
                    ReaderSlot.Single(pageIndex = 0),
                    ReaderSlot.Single(pageIndex = 1),
                    ReaderSlot.Single(pageIndex = 2),
                )
        }
        composeRule.runOnIdle {}
        composeRule.onNodeWithTag("manga_boundary_next_action").assertExists()

        // 3. Step inward from nextBoundary via right 30% tap in RTL -> returns to last content slot (page 2)
        composeRule.onNodeWithTag("reader_canvas").performTouchInput {
            click(Offset(canvasBounds.width * 0.85f, canvasBounds.height * 0.5f))
        }
        composeRule.onNodeWithTag("manga_reader_page_2").assertExists()

        // 4. Keyboard Key.DirectionLeft (forward in RTL) and Key.Spacebar also reach Locked nextBoundary card
        composeRule.onNodeWithTag("reader_canvas").performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        composeRule.onNodeWithTag("manga_boundary_next_action").assertExists()

        composeRule.onNodeWithTag("reader_canvas").performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeRule.onNodeWithTag("manga_reader_page_2").assertExists()

        composeRule.onNodeWithTag("reader_canvas").performKeyInput {
            pressKey(Key.Spacebar)
        }
        composeRule.onNodeWithTag("manga_boundary_next_action").assertExists()

        // 5. From slot 0, backward key (Key.DirectionRight in RTL) reaches Locked previousBoundary card and steps back inward
        composeRule.runOnIdle {
            fakeState.goToSlot(0)
        }
        composeRule.onNodeWithTag("manga_reader_page_0").assertExists()
        composeRule.onNodeWithTag("reader_canvas").performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeRule.onNodeWithTag("manga_boundary_prev_action").assertExists()
        composeRule.onNodeWithTag("reader_canvas").performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        composeRule.onNodeWithTag("manga_reader_page_0").assertExists()
    }

    @Test
    fun webtoonZoomReachesTopAndBottomEdgesAndResetsOnEscape() {
        val pages =
            (0 until 3)
                .map { idx ->
                    val media = UiMedia.Image("local-zoom-webtoon-$idx", "local-zoom-webtoon-$idx", null, 400f, 400f, false)
                    ReaderPageState(
                        id = "zw-$idx",
                        index = idx,
                        status = PageMediaStatus.Ready(original = media, displayed = media),
                        aspectRatio = 1f,
                    )
                }.toImmutableList()
        val fakeState =
            FakeMangaReaderState(
                initialDirection = MangaReadingDirection.Webtoon,
                initialPages = pages,
                initialSlots = (0 until 3).map(ReaderSlot::Single).toImmutableList(),
            )

        composeRule.setContent {
            FluentTheme {
                Box(Modifier.size(width = 400.dp, height = 600.dp)) {
                    MangaReaderCanvas(
                        state = fakeState,
                        hudVisible = false,
                        onToggleHud = {},
                        modifier = Modifier.testTag("reader_canvas"),
                    )
                }
            }
        }

        val canvasBounds = composeRule.onNodeWithTag("reader_canvas").fetchSemanticsNode().boundsInRoot

        // Double-tap center -> 2.5x zoom
        composeRule.onNodeWithTag("reader_canvas").performTouchInput {
            doubleClick(center)
        }
        composeRule.mainClock.advanceTimeBy(400)
        val zoomedPage0Width = composeRule.onNodeWithTag("manga_reader_page_0").fetchSemanticsNode().boundsInRoot.width
        assertTrue(zoomedPage0Width >= canvasBounds.width - 1f)

        // Drag downward to reach very top of item 0 (offsetY == +maxY -> positionInRoot.y == 0f)
        repeat(3) {
            composeRule.onNodeWithTag("reader_canvas").performTouchInput {
                down(Offset(canvasBounds.width * 0.5f, 80f))
                moveTo(Offset(canvasBounds.width * 0.5f, 540f))
                up()
            }
        }
        val topEdgeY = composeRule.onNodeWithTag("manga_reader_page_0").fetchSemanticsNode().positionInRoot.y
        assertTrue(
            abs(topEdgeY) <= 2f,
            "Expected very top of zoomed Webtoon item 0 to be reachable at y=0, got y=$topEdgeY",
        )

        // Drag upward all the way to the very bottom of item 2 (offsetY == -maxY -> bottom == canvasBounds.bottom)
        repeat(8) {
            composeRule.onNodeWithTag("reader_canvas").performTouchInput {
                down(Offset(canvasBounds.width * 0.5f, 540f))
                moveTo(Offset(canvasBounds.width * 0.5f, 60f))
                up()
            }
        }
        val lastNode = composeRule.onNodeWithTag("manga_reader_page_2").fetchSemanticsNode()
        val lastBottomY = lastNode.positionInRoot.y + lastNode.size.height * 2.5f
        val clippedLastBottom = lastNode.boundsInRoot.bottom
        assertTrue(
            abs(lastBottomY - canvasBounds.bottom) <= 4f && abs(clippedLastBottom - canvasBounds.bottom) <= 2f,
            "Expected very bottom of zoomed Webtoon last item to reach canvas bottom ${canvasBounds.bottom}, got bottom=$lastBottomY",
        )

        // Escape resets zoom back to 1.0x
        composeRule.onNodeWithTag("reader_canvas").performKeyInput {
            pressKey(Key.Escape)
        }
        composeRule.runOnIdle {}
        val resetNode = composeRule.onNodeWithTag("manga_reader_page_2").fetchSemanticsNode()
        assertTrue(
            abs(resetNode.size.width.toFloat() - canvasBounds.width) <= 2f &&
                abs((resetNode.positionInRoot.y + resetNode.size.height) - canvasBounds.bottom) <= 2f,
            "Expected Escape to reset Webtoon zoom back to 1.0x",
        )
    }

    @Test
    fun webtoonTallStripStepsByEightyPercentViewportBeforeAdvancingChapter() {
        val nextChapter = UiMangaChapter("ch-2", "Chapter 2", null, null, locked = false, url = null)
        val tallMedia = UiMedia.Image("local-tall-strip", "local-tall-strip", null, 4000f, 800f, false)
        val fakeState =
            FakeMangaReaderState(
                initialDirection = MangaReadingDirection.Webtoon,
                initialPages =
                    persistentListOf(
                        ReaderPageState(
                            id = "tall-0",
                            index = 0,
                            status = PageMediaStatus.Ready(original = tallMedia, displayed = tallMedia),
                            aspectRatio = 0.2f,
                        ),
                    ),
                initialSlots = persistentListOf(ReaderSlot.Single(0)),
                nextBoundary = ChapterBoundary.Unlocked(chapter = nextChapter, startFromEnd = false),
            )

        composeRule.setContent {
            FluentTheme {
                Box(Modifier.size(width = 400.dp, height = 600.dp)) {
                    MangaReaderCanvas(
                        state = fakeState,
                        hudVisible = false,
                        onToggleHud = {},
                        modifier = Modifier.testTag("reader_canvas"),
                    )
                }
            }
        }

        val canvasBounds = composeRule.onNodeWithTag("reader_canvas").fetchSemanticsNode().boundsInRoot
        val expectedStepPx = canvasBounds.height * 0.8f

        // 1. Right 30% tap scrolls down ~80% viewport height without advancing chapter
        composeRule.onNodeWithTag("reader_canvas").performTouchInput {
            click(Offset(canvasBounds.width * 0.85f, canvasBounds.height * 0.5f))
        }
        composeRule.runOnIdle {
            assertEquals(null, fakeState.openedBoundaryChapterId)
        }
        val posAfterTap = composeRule.onNodeWithTag("manga_reader_page_0").fetchSemanticsNode().positionInRoot.y
        assertTrue(
            abs(posAfterTap + expectedStepPx) <= 6f,
            "Expected right tap to scroll tall strip by -$expectedStepPx, got $posAfterTap",
        )

        // 2. Left 30% tap scrolls back up ~80% viewport height to top
        composeRule.onNodeWithTag("reader_canvas").performTouchInput {
            click(Offset(canvasBounds.width * 0.15f, canvasBounds.height * 0.5f))
        }
        composeRule.runOnIdle {}
        val posAfterBackTap = composeRule.onNodeWithTag("manga_reader_page_0").fetchSemanticsNode().positionInRoot.y
        assertTrue(abs(posAfterBackTap) <= 6f, "Expected left tap to scroll back to top=0, got $posAfterBackTap")

        // 3. Repeated Spacebar presses scroll down in ~80% increments and only open nextChapter after reaching the bottom
        var presses = 0
        while (fakeState.openedBoundaryChapterId == null && presses < 10) {
            composeRule.onNodeWithTag("reader_canvas").performKeyInput {
                pressKey(Key.Spacebar)
            }
            composeRule.runOnIdle {}
            presses++
        }
        assertTrue(presses >= 4, "Expected multiple ~80% viewport steps before advancing chapter, took $presses")
        assertEquals("ch-2", fakeState.openedBoundaryChapterId)
    }
}

private fun samplePages(count: Int): ImmutableList<ReaderPageState> =
    (0 until count)
        .map { idx ->
            val media =
                UiMedia.Image(
                    url = "local-test-page-$idx",
                    previewUrl = "local-test-page-$idx",
                    description = "Page ${idx + 1}",
                    height = 1400f,
                    width = 1000f,
                    sensitive = false,
                )
            ReaderPageState(
                id = "p-$idx",
                index = idx,
                status = PageMediaStatus.Ready(original = media, displayed = media),
                aspectRatio = 1000f / 1400f,
            )
        }.toImmutableList()

private class FakeMangaReaderState(
    initialDirection: MangaReadingDirection,
    initialPages: ImmutableList<ReaderPageState>,
    initialSlots: ImmutableList<ReaderSlot>,
    override val activeChapterId: String = "ch-1",
    override val previousBoundary: ChapterBoundary = ChapterBoundary.None,
    override val nextBoundary: ChapterBoundary = ChapterBoundary.None,
    override val prefetchMedia: ImmutableList<UiMedia.Image> = persistentListOf(),
) : MangaReaderPresenter.State {
    override val mangaTitle: String = "Test Manga"
    override val chapterTitle: String = "Chapter 1"
    override val chapterUrl: String = "https://manga.test/ch/1"
    override val loadState: UiState<Unit> = UiState.Success(Unit)
    override var pages: ImmutableList<ReaderPageState> by mutableStateOf(initialPages)
    override var slots: ImmutableList<ReaderSlot> by mutableStateOf(initialSlots)
    override var currentPageIndex: Int by mutableIntStateOf(0)
    override val currentSlotIndex: Int
        get() = slots.indexOfFirst { it.contains(currentPageIndex) }.coerceAtLeast(0)
    private var _direction by mutableStateOf(initialDirection)
    private var _doublePageSpread by mutableStateOf(initialSlots.any { it is ReaderSlot.Double })
    private var _spreadCoverSinglePage by mutableStateOf(false)
    override val direction: MangaReadingDirection get() = _direction
    override val doublePageSpread: Boolean get() = _doublePageSpread
    override val spreadCoverSinglePage: Boolean get() = _spreadCoverSinglePage
    override val imageTranslation: ImageTranslationPresenter.State = NoopImageTranslationState

    var lastWideViewport: Boolean? by mutableStateOf(null)
    var stepForwardCount: Int = 0
    var stepBackwardCount: Int = 0
    var openedBoundaryChapterId: String? = null
    val retriedPageIds = mutableListOf<String>()
    val loadedDimensions = mutableMapOf<String, Pair<Float, Float>>()

    override fun setWideViewport(isWide: Boolean) {
        lastWideViewport = isWide
    }

    override fun onPageDimensionsLoaded(
        pageId: String,
        width: Float,
        height: Float,
    ) {
        loadedDimensions[pageId] = width to height
    }

    override fun goToPage(pageIndex: Int) {
        currentPageIndex = pageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    }

    override fun goToSlot(slotIndex: Int) {
        if (slots.isNotEmpty()) {
            currentPageIndex = slots[slotIndex.coerceIn(0, slots.lastIndex)].primaryPageIndex
        }
    }

    override fun stepForward() {
        stepForwardCount++
        val nextSlot = currentSlotIndex + 1
        if (nextSlot <= slots.lastIndex) {
            currentPageIndex = slots[nextSlot].primaryPageIndex
        } else if (nextBoundary is ChapterBoundary.Unlocked) {
            openedBoundaryChapterId = nextBoundary.chapter.id
        }
    }

    override fun stepBackward() {
        stepBackwardCount++
        val prevSlot = currentSlotIndex - 1
        if (prevSlot >= 0) {
            currentPageIndex = slots[prevSlot].primaryPageIndex
        } else if (previousBoundary is ChapterBoundary.Unlocked) {
            openedBoundaryChapterId = previousBoundary.chapter.id
        }
    }

    override fun openAdjacentChapter(boundary: ChapterBoundary.Unlocked) {
        openedBoundaryChapterId = boundary.chapter.id
    }

    override fun retryChapter() {}

    override fun retryPage(pageId: String) {
        retriedPageIds += pageId
    }

    override fun setDirection(direction: MangaReadingDirection) {
        _direction = direction
    }

    override fun setDoublePageSpread(enabled: Boolean) {
        _doublePageSpread = enabled
    }

    override fun setSpreadCoverSinglePage(enabled: Boolean) {
        _spreadCoverSinglePage = enabled
    }
}

private object NoopImageTranslationState : ImageTranslationPresenter.State {
    override val configured: Boolean = false

    override fun item(media: UiMedia): ImageTranslationItem = ImageTranslationItem()

    override fun displayed(media: UiMedia): UiMedia = media

    override fun toggle(media: UiMedia) {}

    override fun regenerate(media: UiMedia) {}

    override fun clearError(media: UiMedia) {}

    override fun setErrorDetailsVisible(
        media: UiMedia,
        visible: Boolean,
    ) {}

    override fun imageFailed(
        media: UiMedia,
        url: String,
    ) {}
}
