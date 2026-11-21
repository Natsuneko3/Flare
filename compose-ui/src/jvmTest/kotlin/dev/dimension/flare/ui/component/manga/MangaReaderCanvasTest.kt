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
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.network.httpHeaders
import coil3.request.Options
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
        SingletonImageLoader.setUnsafe { context ->
            ImageLoader
                .Builder(context)
                .components {
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
        assertEquals("https://manga.test/chapter/1", pageOptions.httpHeaders["Referer"])
        assertEquals("https://manga.test/chapter/1", prefetchOptions.httpHeaders["Referer"])
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
    override val previousBoundary: ChapterBoundary = ChapterBoundary.None,
    override val nextBoundary: ChapterBoundary = ChapterBoundary.None,
    override val prefetchMedia: ImmutableList<UiMedia.Image> = persistentListOf(),
) : MangaReaderPresenter.State {
    override val mangaTitle: String = "Test Manga"
    override val chapterTitle: String = "Chapter 1"
    override val chapterUrl: String = "https://manga.test/ch/1"
    override val activeChapterId: String = "ch-1"
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
        }
    }

    override fun stepBackward() {
        stepBackwardCount++
        val prevSlot = currentSlotIndex - 1
        if (prevSlot >= 0) {
            currentPageIndex = slots[prevSlot].primaryPageIndex
        }
    }

    override fun openAdjacentChapter(boundary: ChapterBoundary.Unlocked) {
        openedBoundaryChapterId = boundary.chapter.id
    }

    override fun retryChapter() {}

    override fun retryPage(pageId: String) {}

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
