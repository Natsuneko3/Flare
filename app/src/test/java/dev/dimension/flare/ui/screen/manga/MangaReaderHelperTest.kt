package dev.dimension.flare.ui.screen.manga

import android.view.KeyEvent
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.translation.image.ImageTranslationError
import dev.dimension.flare.ui.model.MangaReadingDirection
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.presenter.manga.ChapterBoundary
import dev.dimension.flare.ui.presenter.manga.MangaReaderPresenter
import dev.dimension.flare.ui.presenter.manga.PageMediaStatus
import dev.dimension.flare.ui.presenter.manga.ReaderPageState
import dev.dimension.flare.ui.presenter.manga.ReaderSlot
import dev.dimension.flare.ui.presenter.media.ImageTranslationItem
import dev.dimension.flare.ui.presenter.media.ImageTranslationPresenter
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaReaderHelperTest {
    @Test(timeout = 10_000)
    fun resolveVisibleReadyPageTargetsReturnsBothPagesInAscendingPageOrderOnDoubleSpread() {
        val pages = (0..3).map(::readyReaderPage).toImmutableList()
        val slots =
            persistentListOf(
                ReaderSlot.Single(pageIndex = 0),
                ReaderSlot.Single(pageIndex = 1),
                ReaderSlot.Double(leftPageIndex = 3, rightPageIndex = 2, primaryPageIndex = 2),
            )
        val state =
            FakeHelperReaderState(
                direction = MangaReadingDirection.Rtl,
                pages = pages,
                slots = slots,
                currentPageIndex = 0,
                currentSlotIndex = 0,
            )

        assertEquals(
            listOf(AndroidSpreadPageTarget(pageIndex = 0, ready = pages[0].status as PageMediaStatus.Ready)),
            resolveVisibleReadyPageTargets(state),
        )

        val spreadState =
            state.copy(
                currentPageIndex = 2,
                currentSlotIndex = 2,
            )
        assertEquals(
            listOf(
                AndroidSpreadPageTarget(pageIndex = 2, ready = pages[2].status as PageMediaStatus.Ready),
                AndroidSpreadPageTarget(pageIndex = 3, ready = pages[3].status as PageMediaStatus.Ready),
            ),
            resolveVisibleReadyPageTargets(spreadState),
        )

        val partiallyLoadedPages =
            pages
                .toMutableList()
                .apply {
                    this[3] =
                        this[3].copy(
                            status =
                                PageMediaStatus.Loading(
                                    previewUrl = null,
                                    customHeaders = null,
                                    proxy = ProxyRef.Unbound,
                                ),
                        )
                }.toImmutableList()
        assertEquals(
            listOf(AndroidSpreadPageTarget(pageIndex = 2, ready = pages[2].status as PageMediaStatus.Ready)),
            resolveVisibleReadyPageTargets(spreadState.copy(pages = partiallyLoadedPages)),
        )

        val webtoonState =
            spreadState.copy(
                direction = MangaReadingDirection.Webtoon,
                currentPageIndex = 1,
            )
        assertEquals(
            listOf(AndroidSpreadPageTarget(pageIndex = 1, ready = pages[1].status as PageMediaStatus.Ready)),
            resolveVisibleReadyPageTargets(webtoonState),
        )
    }

    @Test(timeout = 10_000)
    fun consumeAndroidReaderVolumeKeyEventStepsOnActionDownAndSwallowsActionUpOnlyWhenEnabled() {
        var stepBackwardCount = 0
        var stepForwardCount = 0

        assertFalse(
            consumeAndroidReaderVolumeKeyEvent(
                enabled = false,
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_DOWN,
                onStepBackward = { stepBackwardCount++ },
                onStepForward = { stepForwardCount++ },
            ),
        )
        assertFalse(
            consumeAndroidReaderVolumeKeyEvent(
                enabled = false,
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_UP,
                onStepBackward = { stepBackwardCount++ },
                onStepForward = { stepForwardCount++ },
            ),
        )
        assertFalse(
            consumeAndroidReaderVolumeKeyEvent(
                enabled = false,
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
                action = KeyEvent.ACTION_DOWN,
                onStepBackward = { stepBackwardCount++ },
                onStepForward = { stepForwardCount++ },
            ),
        )
        assertFalse(
            consumeAndroidReaderVolumeKeyEvent(
                enabled = false,
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
                action = KeyEvent.ACTION_UP,
                onStepBackward = { stepBackwardCount++ },
                onStepForward = { stepForwardCount++ },
            ),
        )
        assertEquals(0, stepBackwardCount)
        assertEquals(0, stepForwardCount)

        assertTrue(
            consumeAndroidReaderVolumeKeyEvent(
                enabled = true,
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_DOWN,
                onStepBackward = { stepBackwardCount++ },
                onStepForward = { stepForwardCount++ },
            ),
        )
        assertEquals(1, stepBackwardCount)
        assertEquals(0, stepForwardCount)

        assertTrue(
            consumeAndroidReaderVolumeKeyEvent(
                enabled = true,
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_DOWN,
                onStepBackward = { stepBackwardCount++ },
                onStepForward = { stepForwardCount++ },
                repeatCount = 1,
            ),
        )
        assertTrue(
            consumeAndroidReaderVolumeKeyEvent(
                enabled = true,
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_UP,
                onStepBackward = { stepBackwardCount++ },
                onStepForward = { stepForwardCount++ },
            ),
        )
        assertEquals(1, stepBackwardCount)
        assertEquals(0, stepForwardCount)

        assertTrue(
            consumeAndroidReaderVolumeKeyEvent(
                enabled = true,
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
                action = KeyEvent.ACTION_DOWN,
                onStepBackward = { stepBackwardCount++ },
                onStepForward = { stepForwardCount++ },
            ),
        )
        assertEquals(1, stepBackwardCount)
        assertEquals(1, stepForwardCount)

        assertTrue(
            consumeAndroidReaderVolumeKeyEvent(
                enabled = true,
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
                action = KeyEvent.ACTION_DOWN,
                onStepBackward = { stepBackwardCount++ },
                onStepForward = { stepForwardCount++ },
                repeatCount = 2,
            ),
        )
        assertTrue(
            consumeAndroidReaderVolumeKeyEvent(
                enabled = true,
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
                action = KeyEvent.ACTION_UP,
                onStepBackward = { stepBackwardCount++ },
                onStepForward = { stepForwardCount++ },
            ),
        )
        assertEquals(1, stepBackwardCount)
        assertEquals(1, stepForwardCount)

        assertFalse(
            consumeAndroidReaderVolumeKeyEvent(
                enabled = true,
                keyCode = KeyEvent.KEYCODE_BACK,
                action = KeyEvent.ACTION_DOWN,
                onStepBackward = { stepBackwardCount++ },
                onStepForward = { stepForwardCount++ },
            ),
        )
        assertFalse(
            consumeAndroidReaderVolumeKeyEvent(
                enabled = true,
                keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
                action = KeyEvent.ACTION_UP,
                onStepBackward = { stepBackwardCount++ },
                onStepForward = { stepForwardCount++ },
            ),
        )
        assertEquals(1, stepBackwardCount)
        assertEquals(1, stepForwardCount)
    }

    @Test(timeout = 10_000)
    fun longPressGuardAndTargetResolutionRejectNonReadyAndStalePages() {
        val readyPage = readyReaderPage(index = 1)
        val loadingPage =
            readyPage.copy(
                index = 2,
                status =
                    PageMediaStatus.Loading(
                        previewUrl = null,
                        customHeaders = null,
                        proxy = ProxyRef.Unbound,
                    ),
            )
        val failedPage =
            readyPage.copy(
                index = 3,
                status = PageMediaStatus.Failed(IllegalStateException("decode failed")),
            )

        assertTrue(shouldAcceptAndroidPageLongPress(readyPage))
        assertFalse(shouldAcceptAndroidPageLongPress(loadingPage))
        assertFalse(shouldAcceptAndroidPageLongPress(failedPage))

        val pages = listOf(readyReaderPage(index = 0), readyPage, loadingPage, failedPage)
        assertNull(resolveLongPressedReadyPageTarget(pages = pages, longPressedPageIndex = null))
        assertEquals(
            AndroidReadyPageTarget(page = readyPage, ready = readyPage.status as PageMediaStatus.Ready),
            resolveLongPressedReadyPageTarget(pages = pages, longPressedPageIndex = 1),
        )
        assertNull(resolveLongPressedReadyPageTarget(pages = pages, longPressedPageIndex = 2))
        assertNull(resolveLongPressedReadyPageTarget(pages = pages, longPressedPageIndex = 3))
        assertNull(resolveLongPressedReadyPageTarget(pages = pages, longPressedPageIndex = 99))
    }

    @Test(timeout = 10_000)
    fun resolveAndroidTranslationErrorMediaSurfacesSpreadAndOffscreenWebtoonErrors() {
        val pages = (0..3).map(::readyReaderPage).toImmutableList()
        val entries = mutableMapOf<String, ImageTranslationItem>()
        val translationState =
            object : ImageTranslationPresenter.State {
                override val configured: Boolean = true

                override fun item(media: UiMedia): ImageTranslationItem = entries[media.url] ?: ImageTranslationItem()

                override fun displayed(media: UiMedia): UiMedia = media

                override fun toggle(media: UiMedia) {}

                override fun regenerate(media: UiMedia) {}

                override fun clearError(media: UiMedia) {
                    entries.remove(media.url)
                }

                override fun setErrorDetailsVisible(
                    media: UiMedia,
                    visible: Boolean,
                ) {}

                override fun imageFailed(
                    media: UiMedia,
                    url: String,
                ) {}
            }
        val webtoonState =
            FakeHelperReaderState(
                direction = MangaReadingDirection.Webtoon,
                pages = pages,
                slots = (0..3).map(ReaderSlot::Single).toImmutableList(),
                currentPageIndex = 0,
                currentSlotIndex = 0,
                imageTranslation = translationState,
            )

        assertNull(resolveAndroidTranslationErrorMedia(webtoonState))

        // Off-screen scrolled Webtoon page (page 3 while currentPageIndex == 0) still surfaces its translation error
        val page3Media = (pages[3].status as PageMediaStatus.Ready).original
        entries[page3Media.url] = ImageTranslationItem(error = ImageTranslationError.Authentication)
        assertEquals(page3Media, resolveAndroidTranslationErrorMedia(webtoonState))

        // Visible page error takes priority first, then falls back to off-screen error once cleared
        val page0Media = (pages[0].status as PageMediaStatus.Ready).original
        entries[page0Media.url] = ImageTranslationItem(errorDetailsVisible = true)
        assertEquals(page0Media, resolveAndroidTranslationErrorMedia(webtoonState))
        entries.remove(page0Media.url)
        assertEquals(page3Media, resolveAndroidTranslationErrorMedia(webtoonState))
    }
}

private fun readyReaderPage(index: Int): ReaderPageState {
    val image =
        UiMedia.Image(
            url = "https://manga.example/page-$index.jpg",
            previewUrl = "https://manga.example/page-$index.jpg",
            description = "Page ${index + 1}",
            height = 1400f,
            width = 1000f,
            sensitive = false,
        )
    return ReaderPageState(
        id = "page-$index",
        index = index,
        status = PageMediaStatus.Ready(original = image, displayed = image),
        aspectRatio = 1000f / 1400f,
    )
}

private data class FakeHelperReaderState(
    override val direction: MangaReadingDirection,
    override val pages: ImmutableList<ReaderPageState>,
    override val slots: ImmutableList<ReaderSlot>,
    override val currentPageIndex: Int,
    override val currentSlotIndex: Int,
    override val imageTranslation: ImageTranslationPresenter.State = NoopHelperImageTranslationState,
) : MangaReaderPresenter.State {
    override val mangaTitle: String = "Manga"
    override val chapterTitle: String = "Chapter 1"
    override val chapterUrl: String = "https://manga.example/ch/1"
    override val activeChapterId: String = "ch-1"
    override val loadState: UiState<Unit> = UiState.Success(Unit)
    override val doublePageSpread: Boolean = slots.any { it is ReaderSlot.Double }
    override val spreadCoverSinglePage: Boolean = false
    override val previousBoundary: ChapterBoundary = ChapterBoundary.None
    override val nextBoundary: ChapterBoundary = ChapterBoundary.None
    override val prefetchMedia: ImmutableList<UiMedia.Image> = persistentListOf()

    override fun setWideViewport(isWide: Boolean) {}

    override fun onPageDimensionsLoaded(
        pageId: String,
        width: Float,
        height: Float,
    ) {}

    override fun goToPage(pageIndex: Int) {}

    override fun goToSlot(slotIndex: Int) {}

    override fun stepForward() {}

    override fun stepBackward() {}

    override fun openAdjacentChapter(boundary: ChapterBoundary.Unlocked) {}

    override fun retryChapter() {}

    override fun retryPage(pageId: String) {}

    override fun setDirection(direction: MangaReadingDirection) {}

    override fun setDoublePageSpread(enabled: Boolean) {}

    override fun setSpreadCoverSinglePage(enabled: Boolean) {}
}

private object NoopHelperImageTranslationState : ImageTranslationPresenter.State {
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
