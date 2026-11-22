package dev.dimension.flare.ui.screen.manga

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Brands
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.brands.Chrome
import compose.icons.fontawesomeicons.solid.ArrowLeft
import compose.icons.fontawesomeicons.solid.BackwardStep
import compose.icons.fontawesomeicons.solid.FloppyDisk
import compose.icons.fontawesomeicons.solid.ForwardStep
import compose.icons.fontawesomeicons.solid.Gear
import compose.icons.fontawesomeicons.solid.ShareNodes
import dev.dimension.flare.LocalWindowPadding
import dev.dimension.flare.Res
import dev.dimension.flare.common.DesktopDownloadManager
import dev.dimension.flare.common.DesktopSaveDialog
import dev.dimension.flare.common.FileSystemUtilsExt
import dev.dimension.flare.common.MediaFileNamePolicy
import dev.dimension.flare.common.prepareImageTranslationExport
import dev.dimension.flare.manga_open_in_browser
import dev.dimension.flare.manga_reader_copy_page
import dev.dimension.flare.manga_reader_direction
import dev.dimension.flare.manga_reader_direction_ltr
import dev.dimension.flare.manga_reader_direction_rtl
import dev.dimension.flare.manga_reader_direction_webtoon
import dev.dimension.flare.manga_reader_double_page_spread
import dev.dimension.flare.manga_reader_next_chapter
import dev.dimension.flare.manga_reader_page_of
import dev.dimension.flare.manga_reader_page_position
import dev.dimension.flare.manga_reader_previous_chapter
import dev.dimension.flare.manga_reader_settings
import dev.dimension.flare.manga_reader_spread_cover_single
import dev.dimension.flare.media_save
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.component.FAIcon
import dev.dimension.flare.ui.component.manga.MangaReaderCanvas
import dev.dimension.flare.ui.model.MangaReadingDirection
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.presenter.invoke
import dev.dimension.flare.ui.presenter.manga.ChapterBoundary
import dev.dimension.flare.ui.presenter.manga.MangaReaderPresenter
import dev.dimension.flare.ui.presenter.manga.PageMediaStatus
import dev.dimension.flare.ui.presenter.manga.ReaderSlot
import dev.dimension.flare.ui.route.DeeplinkRoute
import dev.dimension.flare.ui.route.toUri
import dev.dimension.flare.ui.screen.media.ImageTranslationActions
import dev.dimension.flare.ui.theme.LocalComposeWindow
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.FlyoutContainer
import io.github.composefluent.component.PillButton
import io.github.composefluent.component.Slider
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Switcher
import io.github.composefluent.component.Text
import io.github.composefluent.surface.Card
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.tlaster.precompose.molecule.producePresenter
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Composable
internal fun MangaReaderScreen(
    mangaKey: MicroBlogKey,
    chapterId: String,
    initialPage: Int?,
    accountType: AccountType,
    onBack: () -> Unit,
) {
    val state by producePresenter("desktop_manga_reader_$accountType-$mangaKey-$chapterId-$initialPage") {
        remember(accountType, mangaKey, chapterId, initialPage) {
            MangaReaderPresenter(
                accountType = accountType,
                mangaKey = mangaKey,
                initialChapterId = chapterId,
                initialPage = initialPage,
            )
        }.invoke()
    }
    val downloader = koinInject<DesktopDownloadManager>()
    val ioScope = koinInject<CoroutineScope>()
    val window = LocalComposeWindow.current

    MangaReaderContent(
        state = state,
        onBack = onBack,
        onSharePage = { original, displayed, pageIndex ->
            val name = MediaFileNamePolicy.mangaPageFileName(mangaKey.id, state.activeChapterId, pageIndex, original, displayed)
            ioScope.launch { downloader.copyImageToClipboard(displayed, name) }
        },
        onSavePage = { original, displayed, pageIndex ->
            if (original is UiMedia.Ugoira) return@MangaReaderContent
            val activeChapterId = state.activeChapterId
            val defaultName =
                MediaFileNamePolicy.mangaPageFileName(
                    mangaId = mangaKey.id,
                    chapterId = activeChapterId,
                    pageIndex = pageIndex,
                    original = original,
                    displayed = displayed,
                )
            ioScope.launch {
                val export =
                    withContext(Dispatchers.IO) {
                        prepareImageTranslationExport(
                            displayed,
                            FileSystemUtilsExt.flareCacheDirectory(),
                        )
                    }
                val targetFile =
                    withContext(Dispatchers.Main) {
                        DesktopSaveDialog.chooseFile(window = window, defaultName = defaultName)
                    } ?: return@launch
                downloader.download(media = export, targetFile = targetFile)
            }
        },
    )
}

@Composable
internal fun MangaReaderContent(
    state: MangaReaderPresenter.State,
    onBack: () -> Unit,
    onSharePage: ((original: UiMedia, displayed: UiMedia, pageIndex: Int) -> Unit)? = null,
    onSavePage: ((original: UiMedia, displayed: UiMedia, pageIndex: Int) -> Unit)? = null,
) {
    var hudVisible by remember { mutableStateOf(true) }
    val uriHandler = LocalUriHandler.current
    val currentPage = state.pages.getOrNull(state.currentPageIndex)
    val currentReady = currentPage?.status as? PageMediaStatus.Ready

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        val isWideViewport = maxWidth >= 720.dp
        MangaReaderCanvas(
            state = state,
            hudVisible = hudVisible,
            onToggleHud = { hudVisible = !hudVisible },
            onBack = onBack,
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag("manga_reader_canvas"),
        )

        // Top HUD Overlay
        AnimatedVisibility(
            visible = hudVisible,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            val hudColor = FluentTheme.colors.background.layer.default
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(hudColor.copy(alpha = 0.92f))
                        .padding(
                            top = LocalWindowPadding.current.calculateTopPadding() + 8.dp,
                            bottom = 8.dp,
                            start = 12.dp,
                            end = 12.dp,
                        ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SubtleButton(
                    onClick = onBack,
                    iconOnly = true,
                    modifier = Modifier.testTag("manga_reader_back"),
                ) {
                    FAIcon(
                        imageVector = FontAwesomeIcons.Solid.ArrowLeft,
                        contentDescription = null,
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = state.chapterTitle,
                        style = FluentTheme.typography.bodyStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.mangaTitle.isNotBlank()) {
                        Text(
                            text = state.mangaTitle,
                            style = FluentTheme.typography.caption,
                            color = FluentTheme.colors.text.text.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                currentReady?.let { ready ->
                    ImageTranslationActions(
                        translation = state.imageTranslation,
                        media = ready.original,
                    )
                    if (onSavePage != null && ready.original !is UiMedia.Ugoira) {
                        SubtleButton(
                            onClick = {
                                onSavePage(ready.original, ready.displayed, state.currentPageIndex)
                            },
                            iconOnly = true,
                            modifier = Modifier.testTag("manga_reader_save_page"),
                        ) {
                            FAIcon(
                                imageVector = FontAwesomeIcons.Solid.FloppyDisk,
                                contentDescription = stringResource(Res.string.media_save),
                            )
                        }
                    }
                }

                if (onSharePage != null && currentReady != null &&
                    (currentReady.displayed is UiMedia.Image || currentReady.displayed is UiMedia.Gif)
                ) {
                    SubtleButton(
                        onClick = { onSharePage(currentReady.original, currentReady.displayed, state.currentPageIndex) },
                        iconOnly = true,
                        modifier = Modifier.testTag("manga_reader_share_page"),
                    ) {
                        FAIcon(
                            imageVector = FontAwesomeIcons.Solid.ShareNodes,
                            contentDescription = stringResource(Res.string.manga_reader_copy_page),
                        )
                    }
                }

                state.chapterUrl?.takeIf { it.isNotBlank() }?.let { chapterUrl ->
                    SubtleButton(
                        onClick = {
                            uriHandler.openUri(DeeplinkRoute.OpenLinkDirectly(chapterUrl).toUri())
                        },
                        iconOnly = true,
                    ) {
                        FAIcon(
                            imageVector = FontAwesomeIcons.Brands.Chrome,
                            contentDescription = stringResource(Res.string.manga_open_in_browser),
                        )
                    }
                }

                FlyoutContainer(
                    flyout = {
                        DesktopMangaReaderSettingsFlyout(
                            state = state,
                            isWideViewport = isWideViewport,
                        )
                    },
                ) {
                    SubtleButton(
                        onClick = { isFlyoutVisible = !isFlyoutVisible },
                        iconOnly = true,
                        modifier = Modifier.testTag("manga_reader_settings_button"),
                    ) {
                        FAIcon(
                            imageVector = FontAwesomeIcons.Solid.Gear,
                            contentDescription = stringResource(Res.string.manga_reader_settings),
                        )
                    }
                }
            }
        }

        // Bottom HUD Overlay
        AnimatedVisibility(
            visible = hudVisible && state.pages.isNotEmpty(),
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = LocalWindowPadding.current.calculateBottomPadding() + 16.dp,
                    ).widthIn(max = 640.dp),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                val isRtl = state.direction == MangaReadingDirection.Rtl
                val prevUnlocked = state.previousBoundary as? ChapterBoundary.Unlocked
                val nextUnlocked = state.nextBoundary as? ChapterBoundary.Unlocked
                val leftChapterTarget = if (isRtl) nextUnlocked else prevUnlocked
                val rightChapterTarget = if (isRtl) prevUnlocked else nextUnlocked
                val leftChapterLabel =
                    stringResource(if (isRtl) Res.string.manga_reader_next_chapter else Res.string.manga_reader_previous_chapter)
                val rightChapterLabel =
                    stringResource(if (isRtl) Res.string.manga_reader_previous_chapter else Res.string.manga_reader_next_chapter)

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SubtleButton(
                        onClick = { leftChapterTarget?.let(state::openAdjacentChapter) },
                        disabled = leftChapterTarget == null,
                        iconOnly = true,
                    ) {
                        FAIcon(
                            imageVector = FontAwesomeIcons.Solid.BackwardStep,
                            contentDescription = leftChapterLabel,
                            modifier = Modifier.size(14.dp),
                        )
                    }

                    DesktopMangaPageSlider(
                        pageCount = state.pages.size,
                        currentPageIndex = state.currentPageIndex,
                        isRtl = isRtl,
                        onPageSelected = state::goToPage,
                        modifier = Modifier.weight(1f),
                    )

                    SubtleButton(
                        onClick = { rightChapterTarget?.let(state::openAdjacentChapter) },
                        disabled = rightChapterTarget == null,
                        iconOnly = true,
                    ) {
                        FAIcon(
                            imageVector = FontAwesomeIcons.Solid.ForwardStep,
                            contentDescription = rightChapterLabel,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }

        // Collapsed Bottom-Right Page Badge
        AnimatedVisibility(
            visible = !hudVisible && state.pages.isNotEmpty(),
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 16.dp,
                        bottom = LocalWindowPadding.current.calculateBottomPadding() + 12.dp,
                    ),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .testTag("manga_reader_collapsed_page_badge"),
            ) {
                Text(
                    text = formatReaderPageBadge(state),
                    style = FluentTheme.typography.caption,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun DesktopMangaPageSlider(
    pageCount: Int,
    currentPageIndex: Int,
    isRtl: Boolean,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxPage = (pageCount - 1).coerceAtLeast(0)
    val clampedPage = currentPageIndex.coerceIn(0, maxPage)
    val positionLabel = stringResource(Res.string.manga_reader_page_position)
    val pageDescription = stringResource(Res.string.manga_reader_page_of, clampedPage + 1, pageCount)

    CompositionLocalProvider(
        LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = (clampedPage + 1).toString(),
                style = FluentTheme.typography.caption,
            )
            if (maxPage > 0) {
                Slider(
                    value = clampedPage.toFloat(),
                    onValueChange = { rawValue ->
                        val nextPage = rawValue.roundToInt().coerceIn(0, maxPage)
                        if (nextPage != currentPageIndex) {
                            onPageSelected(nextPage)
                        }
                    },
                    valueRange = 0f..maxPage.toFloat(),
                    steps = if (pageCount <= 30) (pageCount - 2).coerceAtLeast(0) else 0,
                    modifier =
                        Modifier
                            .weight(1f)
                            .graphicsLayer {
                                scaleX = if (isRtl) -1f else 1f
                            }.semantics {
                                contentDescription = positionLabel
                                stateDescription = pageDescription
                            }.testTag("manga_reader_page_slider"),
                )
            } else {
                Spacer(
                    Modifier
                        .weight(1f)
                        .testTag("manga_reader_page_slider"),
                )
            }
            Text(
                text = pageCount.toString(),
                style = FluentTheme.typography.caption,
            )
        }
    }
}

@Composable
private fun DesktopMangaReaderSettingsFlyout(
    state: MangaReaderPresenter.State,
    isWideViewport: Boolean,
) {
    Column(
        modifier =
            Modifier
                .width(300.dp)
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(Res.string.manga_reader_settings),
            style = FluentTheme.typography.bodyStrong,
            fontWeight = FontWeight.SemiBold,
        )

        Text(
            text = stringResource(Res.string.manga_reader_direction),
            style = FluentTheme.typography.caption,
            color = FluentTheme.colors.text.text.secondary,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MangaReadingDirection.entries.forEach { dir ->
                PillButton(
                    selected = state.direction == dir,
                    onSelectedChanged = { selected ->
                        if (selected) {
                            state.setDirection(dir)
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            when (dir) {
                                MangaReadingDirection.Rtl -> Res.string.manga_reader_direction_rtl
                                MangaReadingDirection.Ltr -> Res.string.manga_reader_direction_ltr
                                MangaReadingDirection.Webtoon -> Res.string.manga_reader_direction_webtoon
                            },
                        ),
                    )
                }
            }
        }

        if (isWideViewport && state.direction != MangaReadingDirection.Webtoon) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.manga_reader_double_page_spread),
                    style = FluentTheme.typography.body,
                    modifier = Modifier.weight(1f),
                )
                Switcher(
                    checked = state.doublePageSpread,
                    onCheckStateChange = state::setDoublePageSpread,
                )
            }

            if (state.doublePageSpread) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.manga_reader_spread_cover_single),
                        style = FluentTheme.typography.body,
                        modifier = Modifier.weight(1f),
                    )
                    Switcher(
                        checked = state.spreadCoverSinglePage,
                        onCheckStateChange = state::setSpreadCoverSinglePage,
                    )
                }
            }
        }
    }
}

private fun formatReaderPageBadge(state: MangaReaderPresenter.State): String {
    val total = state.pages.size
    if (total <= 0) return ""
    if (state.direction != MangaReadingDirection.Webtoon) {
        val currentSlot = state.slots.getOrNull(state.currentSlotIndex)
        if (currentSlot is ReaderSlot.Double) {
            val first = minOf(currentSlot.leftPageIndex, currentSlot.rightPageIndex) + 1
            val second = maxOf(currentSlot.leftPageIndex, currentSlot.rightPageIndex) + 1
            return "$first-$second / $total"
        }
    }
    return "${state.currentPageIndex + 1} / $total"
}
