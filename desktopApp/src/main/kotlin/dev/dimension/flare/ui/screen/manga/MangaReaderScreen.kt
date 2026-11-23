package dev.dimension.flare.ui.screen.manga

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
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
import compose.icons.fontawesomeicons.solid.ArrowDownWideShort
import compose.icons.fontawesomeicons.solid.ArrowLeft
import compose.icons.fontawesomeicons.solid.ArrowUpWideShort
import compose.icons.fontawesomeicons.solid.BackwardStep
import compose.icons.fontawesomeicons.solid.Check
import compose.icons.fontawesomeicons.solid.ChevronDown
import compose.icons.fontawesomeicons.solid.FloppyDisk
import compose.icons.fontawesomeicons.solid.ForwardStep
import compose.icons.fontawesomeicons.solid.Gear
import compose.icons.fontawesomeicons.solid.Lock
import compose.icons.fontawesomeicons.solid.ShareNodes
import dev.dimension.flare.LocalWindowPadding
import dev.dimension.flare.Res
import dev.dimension.flare.cancel
import dev.dimension.flare.common.DesktopDownloadManager
import dev.dimension.flare.common.DesktopSaveDialog
import dev.dimension.flare.common.FileSystemUtilsExt
import dev.dimension.flare.common.MediaFileNamePolicy
import dev.dimension.flare.common.prepareImageTranslationExport
import dev.dimension.flare.manga_detail_empty_chapters
import dev.dimension.flare.manga_locked_chapter_message
import dev.dimension.flare.manga_locked_chapter_title
import dev.dimension.flare.manga_open_in_browser
import dev.dimension.flare.manga_reader_chapters
import dev.dimension.flare.manga_reader_constrain_webtoon_width
import dev.dimension.flare.manga_reader_copy_page
import dev.dimension.flare.manga_reader_direction
import dev.dimension.flare.manga_reader_direction_ltr
import dev.dimension.flare.manga_reader_direction_rtl
import dev.dimension.flare.manga_reader_direction_webtoon
import dev.dimension.flare.manga_reader_double_page_spread
import dev.dimension.flare.manga_reader_next_chapter
import dev.dimension.flare.manga_reader_page_number
import dev.dimension.flare.manga_reader_page_of
import dev.dimension.flare.manga_reader_page_position
import dev.dimension.flare.manga_reader_previous_chapter
import dev.dimension.flare.manga_reader_settings
import dev.dimension.flare.manga_reader_sort_newest_first
import dev.dimension.flare.manga_reader_sort_oldest_first
import dev.dimension.flare.manga_reader_spread_cover_single
import dev.dimension.flare.media_save
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.navigate_back
import dev.dimension.flare.ui.component.DateTimeText
import dev.dimension.flare.ui.component.FAIcon
import dev.dimension.flare.ui.component.FlareScrollBar
import dev.dimension.flare.ui.component.manga.MangaReaderCanvas
import dev.dimension.flare.ui.component.resolveText
import dev.dimension.flare.ui.model.MangaReadingDirection
import dev.dimension.flare.ui.model.UiMangaChapter
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.presenter.invoke
import dev.dimension.flare.ui.presenter.manga.ChapterBoundary
import dev.dimension.flare.ui.presenter.manga.MangaReaderPresenter
import dev.dimension.flare.ui.presenter.manga.PageMediaStatus
import dev.dimension.flare.ui.presenter.manga.ReaderChapterItem
import dev.dimension.flare.ui.presenter.manga.ReaderSlot
import dev.dimension.flare.ui.route.DeeplinkRoute
import dev.dimension.flare.ui.route.toUri
import dev.dimension.flare.ui.screen.media.ImageTranslationActions
import dev.dimension.flare.ui.screen.media.ImageTranslationErrorDialog
import dev.dimension.flare.ui.screen.settings.imageTranslationText
import dev.dimension.flare.ui.theme.LocalComposeWindow
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Button
import io.github.composefluent.component.FluentDialog
import io.github.composefluent.component.FlyoutContainer
import io.github.composefluent.component.FlyoutPlacement
import io.github.composefluent.component.PillButton
import io.github.composefluent.component.Slider
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Switcher
import io.github.composefluent.component.Text
import io.github.composefluent.surface.Card
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.tlaster.precompose.molecule.producePresenter
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.math.roundToInt

private const val HUD_AUTO_HIDE_DELAY_MS = 2_500L

@Composable
internal fun MangaReaderScreen(
    mangaKey: MicroBlogKey,
    chapterId: String,
    initialPage: Int?,
    accountType: AccountType,
    onBack: () -> Unit,
    requestNonce: Int = 0,
    isStandaloneWindow: Boolean = false,
) {
    val presenterKey =
        if (isStandaloneWindow) {
            "desktop_manga_reader_$accountType-$mangaKey"
        } else {
            "desktop_manga_reader_$accountType-$mangaKey-$chapterId-$initialPage"
        }
    val state by producePresenter(presenterKey) {
        remember(presenterKey) {
            MangaReaderPresenter(
                accountType = accountType,
                mangaKey = mangaKey,
                initialChapterId = chapterId,
                initialPage = initialPage,
            )
        }.invoke()
    }
    val currentChapterId by rememberUpdatedState(chapterId)
    val currentInitialPage by rememberUpdatedState(initialPage)
    LaunchedEffect(requestNonce) {
        if (requestNonce > 0) {
            state.openChapter(currentChapterId, currentInitialPage)
        }
    }

    val downloader = koinInject<DesktopDownloadManager>()
    val ioScope = koinInject<CoroutineScope>()
    val window = LocalComposeWindow.current

    MangaReaderContent(
        state = state,
        onBack = onBack,
        isStandaloneWindow = isStandaloneWindow,
        requestNonce = requestNonce,
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
    isStandaloneWindow: Boolean = false,
    requestNonce: Int = 0,
) {
    var hudVisible by remember { mutableStateOf(true) }
    var manuallyHiddenUntilMove by remember { mutableStateOf(false) }
    var pointerActivityNonce by remember { mutableIntStateOf(0) }
    var lastPointerMovePosition by remember { mutableStateOf<Offset?>(null) }
    var chaptersFlyoutOpen by remember { mutableStateOf(false) }
    var settingsFlyoutOpen by remember { mutableStateOf(false) }
    var lockedPromptChapter by remember { mutableStateOf<UiMangaChapter?>(null) }

    LaunchedEffect(state.activeChapterId, requestNonce) {
        lockedPromptChapter = null
    }

    val topHudInteractionSource = remember { MutableInteractionSource() }
    val isTopHudHovered by topHudInteractionSource.collectIsHoveredAsState()
    val bottomHudInteractionSource = remember { MutableInteractionSource() }
    val isBottomHudHovered by bottomHudInteractionSource.collectIsHoveredAsState()

    val activeTranslationErrorMedia =
        state.pages.firstNotNullOfOrNull { page ->
            val originalImage = (page.status as? PageMediaStatus.Ready)?.original as? UiMedia.Image ?: return@firstNotNullOfOrNull null
            val item = state.imageTranslation.item(originalImage)
            originalImage.takeIf { item.error != null || item.errorDetailsVisible }
        }
    val anyFlyoutOpen =
        chaptersFlyoutOpen ||
            settingsFlyoutOpen ||
            lockedPromptChapter != null ||
            activeTranslationErrorMedia != null
    val isHudHovered = isTopHudHovered || isBottomHudHovered
    val canvasFocusRequester = remember { FocusRequester() }

    LaunchedEffect(anyFlyoutOpen, hudVisible) {
        if (!anyFlyoutOpen) {
            runCatching { canvasFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(hudVisible, pointerActivityNonce, anyFlyoutOpen, isHudHovered, manuallyHiddenUntilMove) {
        if (hudVisible && !anyFlyoutOpen && !isHudHovered && !manuallyHiddenUntilMove) {
            delay(HUD_AUTO_HIDE_DELAY_MS)
            hudVisible = false
        }
    }

    val toggleHud = {
        val next = !hudVisible
        hudVisible = next
        manuallyHiddenUntilMove = !next
        if (next) {
            pointerActivityNonce++
        }
    }

    val uriHandler = LocalUriHandler.current
    val currentPage = state.pages.getOrNull(state.currentPageIndex)
    val currentReady = currentPage?.status as? PageMediaStatus.Ready

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        when {
                            activeTranslationErrorMedia != null -> {
                                state.imageTranslation.clearError(activeTranslationErrorMedia)
                                state.imageTranslation.setErrorDetailsVisible(activeTranslationErrorMedia, false)
                                true
                            }

                            lockedPromptChapter != null -> {
                                lockedPromptChapter = null
                                true
                            }

                            else -> {
                                false
                            }
                        }
                    } else {
                        false
                    }
                }.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Move) {
                                val pos = event.changes.firstOrNull()?.position
                                if (pos != null) {
                                    val prev = lastPointerMovePosition
                                    lastPointerMovePosition = pos
                                    if (prev == null || (pos - prev).getDistanceSquared() > 1f) {
                                        manuallyHiddenUntilMove = false
                                        hudVisible = true
                                        pointerActivityNonce++
                                    }
                                }
                            }
                        }
                    }
                },
    ) {
        val isWideViewport = maxWidth >= 720.dp
        MangaReaderCanvas(
            state = state,
            hudVisible = hudVisible,
            onToggleHud = toggleHud,
            onBack = onBack,
            pageContextWrapper = { page, ready, content ->
                val copyLabel = stringResource(Res.string.manga_reader_copy_page)
                val saveLabel = stringResource(Res.string.media_save)
                val openInBrowserLabel = stringResource(Res.string.manga_open_in_browser)
                val chapterUrl = state.chapterUrl?.takeIf { it.isNotBlank() }

                val originalImage = ready.original as? UiMedia.Image
                val translationItem = originalImage?.let { state.imageTranslation.item(it) }
                val canToggleTranslation =
                    originalImage != null &&
                        state.imageTranslation.canToggle(originalImage) &&
                        translationItem?.loading != true
                val toggleTranslationLabel =
                    if (translationItem != null) {
                        imageTranslationText(
                            when {
                                translationItem.showingTranslated -> "Show original"
                                translationItem.translated != null -> "Show translation"
                                else -> "Translate image"
                            },
                        )
                    } else {
                        null
                    }
                val canRegenerateTranslation =
                    originalImage != null &&
                        state.imageTranslation.canRegenerate(originalImage) &&
                        translationItem?.loading != true
                val regenerateTranslationLabel = imageTranslationText("Translate again")

                val canCopyPage =
                    onSharePage != null &&
                        (ready.displayed is UiMedia.Image || ready.displayed is UiMedia.Gif)
                val canSavePage = onSavePage != null && ready.original !is UiMedia.Ugoira

                ContextMenuArea(
                    items = {
                        buildList {
                            if (canCopyPage) {
                                add(
                                    ContextMenuItem(
                                        label = copyLabel,
                                        onClick = { onSharePage.invoke(ready.original, ready.displayed, page.index) },
                                    ),
                                )
                            }
                            if (canSavePage) {
                                add(
                                    ContextMenuItem(
                                        label = saveLabel,
                                        onClick = { onSavePage.invoke(ready.original, ready.displayed, page.index) },
                                    ),
                                )
                            }
                            if (canToggleTranslation && toggleTranslationLabel != null) {
                                add(
                                    ContextMenuItem(
                                        label = toggleTranslationLabel,
                                        onClick = { state.imageTranslation.toggle(originalImage) },
                                    ),
                                )
                            }
                            if (canRegenerateTranslation) {
                                add(
                                    ContextMenuItem(
                                        label = regenerateTranslationLabel,
                                        onClick = { state.imageTranslation.regenerate(originalImage) },
                                    ),
                                )
                            }
                            if (chapterUrl != null) {
                                add(
                                    ContextMenuItem(
                                        label = openInBrowserLabel,
                                        onClick = {
                                            uriHandler.openUri(DeeplinkRoute.OpenLinkDirectly(chapterUrl).toUri())
                                        },
                                    ),
                                )
                            }
                        }
                    },
                ) {
                    content()
                }
            },
            modifier =
                Modifier
                    .fillMaxSize()
                    .focusRequester(canvasFocusRequester)
                    .testTag("manga_reader_canvas"),
        )

        // Top HUD Overlay
        AnimatedVisibility(
            visible = hudVisible,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = LocalWindowPadding.current.calculateTopPadding() + 6.dp,
                    ).widthIn(max = 860.dp),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .hoverable(topHudInteractionSource),
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (!isStandaloneWindow) {
                        SubtleButton(
                            onClick = onBack,
                            iconOnly = true,
                            modifier = Modifier.testTag("manga_reader_back"),
                        ) {
                            FAIcon(
                                imageVector = FontAwesomeIcons.Solid.ArrowLeft,
                                contentDescription = stringResource(Res.string.navigate_back),
                            )
                        }
                    }

                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        FlyoutContainer(
                            flyout = {
                                DesktopMangaReaderChaptersFlyout(
                                    state = state,
                                    onSelectUnlockedChapter = { chapterId ->
                                        isFlyoutVisible = false
                                        state.selectChapter(chapterId)
                                    },
                                    onSelectLockedChapter = { chapter ->
                                        isFlyoutVisible = false
                                        lockedPromptChapter = chapter
                                    },
                                )
                            },
                            placement = FlyoutPlacement.BottomAlignedStart,
                            adaptivePlacement = true,
                        ) {
                            DisposableEffect(isFlyoutVisible) {
                                chaptersFlyoutOpen = isFlyoutVisible
                                onDispose {
                                    chaptersFlyoutOpen = false
                                }
                            }
                            SubtleButton(
                                onClick = { isFlyoutVisible = !isFlyoutVisible },
                                modifier = Modifier.testTag("manga_reader_chapters_button"),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f, fill = false),
                                        verticalArrangement = Arrangement.spacedBy(1.dp),
                                    ) {
                                        Text(
                                            text = state.chapterTitle.ifBlank { stringResource(Res.string.manga_reader_chapters) },
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
                                    FAIcon(
                                        imageVector = FontAwesomeIcons.Solid.ChevronDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = FluentTheme.colors.text.text.secondary,
                                    )
                                }
                            }
                        }
                    }

                    currentReady?.let { ready ->
                        ImageTranslationActions(
                            translation = state.imageTranslation,
                            media = ready.original,
                            showErrorDialog = false,
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
                        placement = FlyoutPlacement.BottomAlignedEnd,
                        adaptivePlacement = true,
                    ) {
                        DisposableEffect(isFlyoutVisible) {
                            settingsFlyoutOpen = isFlyoutVisible
                            onDispose {
                                settingsFlyoutOpen = false
                            }
                        }
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
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .hoverable(bottomHudInteractionSource),
                shape = RoundedCornerShape(8.dp),
            ) {
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

        lockedPromptChapter?.let { lockedChapter ->
            val openUrl = state.resolveChapterOpenUrl(lockedChapter)?.takeIf { it.isNotBlank() }
            FluentDialog(visible = true) {
                val dialogFocusRequester = remember { FocusRequester() }
                LaunchedEffect(lockedChapter.id) {
                    runCatching { dialogFocusRequester.requestFocus() }
                }
                CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                    Column(
                        modifier =
                            Modifier
                                .widthIn(min = 320.dp, max = 440.dp)
                                .padding(24.dp)
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                        lockedPromptChapter = null
                                        true
                                    } else {
                                        false
                                    }
                                }.onKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                        lockedPromptChapter = null
                                        true
                                    } else {
                                        false
                                    }
                                }.focusRequester(dialogFocusRequester)
                                .focusable()
                                .testTag("manga_reader_locked_chapter_dialog"),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.manga_locked_chapter_title),
                            style = FluentTheme.typography.subtitle,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(Res.string.manga_locked_chapter_message, lockedChapter.title),
                            style = FluentTheme.typography.body,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            Button(
                                onClick = { lockedPromptChapter = null },
                            ) {
                                Text(stringResource(Res.string.cancel))
                            }
                            if (openUrl != null) {
                                AccentButton(
                                    onClick = {
                                        lockedPromptChapter = null
                                        uriHandler.openUri(DeeplinkRoute.OpenLinkDirectly(openUrl).toUri())
                                    },
                                ) {
                                    Text(stringResource(Res.string.manga_open_in_browser))
                                }
                            }
                        }
                    }
                }
            }
        }

        activeTranslationErrorMedia?.let { errorMedia ->
            ImageTranslationErrorDialog(
                translation = state.imageTranslation,
                media = errorMedia,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopMangaReaderChaptersFlyout(
    state: MangaReaderPresenter.State,
    onSelectUnlockedChapter: (String) -> Unit,
    onSelectLockedChapter: (UiMangaChapter) -> Unit,
) {
    val initialIndex = remember { state.chapterItems.indexOfFirst { it.isCurrent }.coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)

    Column(
        modifier =
            Modifier
                .width(340.dp)
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${stringResource(Res.string.manga_reader_chapters)} (${state.chapterItems.size})",
                style = FluentTheme.typography.bodyStrong,
                fontWeight = FontWeight.SemiBold,
            )
            SubtleButton(
                onClick = state::toggleChapterSort,
                modifier = Modifier.testTag("manga_reader_chapter_sort_toggle"),
            ) {
                FAIcon(
                    imageVector =
                        if (state.chapterSortDescending) {
                            FontAwesomeIcons.Solid.ArrowDownWideShort
                        } else {
                            FontAwesomeIcons.Solid.ArrowUpWideShort
                        },
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text =
                        stringResource(
                            if (state.chapterSortDescending) {
                                Res.string.manga_reader_sort_newest_first
                            } else {
                                Res.string.manga_reader_sort_oldest_first
                            },
                        ),
                )
            }
        }

        if (state.chapterGroups.size > 1 || state.chapterGroups.any { it.title != null }) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.chapterGroups.forEach { group ->
                    val label = group.title?.resolveText()?.takeIf { it.isNotBlank() } ?: group.id
                    PillButton(
                        selected = group.id == state.selectedChapterGroupId,
                        onSelectedChanged = { selected ->
                            if (selected) {
                                state.selectChapterGroup(group.id)
                            }
                        },
                        modifier = Modifier.testTag("manga_reader_chapter_group_${group.id}"),
                    ) {
                        Text(label)
                    }
                }
            }
        }

        if (state.chapterItems.isEmpty()) {
            Text(
                text = stringResource(Res.string.manga_detail_empty_chapters),
                style = FluentTheme.typography.body,
                color = FluentTheme.colors.text.text.secondary,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            FlareScrollBar(
                state = listState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
            ) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(
                        items = state.chapterItems,
                        key = { it.chapter.id },
                    ) { item ->
                        DesktopMangaReaderChapterRow(
                            item = item,
                            openUrl = state.resolveChapterOpenUrl(item.chapter)?.takeIf { it.isNotBlank() },
                            onClick = {
                                if (item.chapter.locked) {
                                    onSelectLockedChapter(item.chapter)
                                } else {
                                    onSelectUnlockedChapter(item.chapter.id)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopMangaReaderChapterRow(
    item: ReaderChapterItem,
    openUrl: String?,
    onClick: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val openInBrowserLabel = stringResource(Res.string.manga_open_in_browser)
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val accentFill = FluentTheme.colors.fillAccent.default
    val backgroundColor =
        when {
            isHovered -> FluentTheme.colors.subtleFill.secondary
            item.isCurrent -> accentFill.copy(alpha = 0.14f)
            else -> Color.Transparent
        }
    val titleColor =
        when {
            item.isCurrent -> FluentTheme.colors.text.accent.primary
            item.isRead || item.chapter.locked -> FluentTheme.colors.text.text.secondary
            else -> FluentTheme.colors.text.text.primary
        }

    val rowContent: @Composable () -> Unit = {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .hoverable(interactionSource)
                    .background(backgroundColor)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag("manga_reader_chapter_item_${item.chapter.id}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (item.chapter.locked) {
                FAIcon(
                    imageVector = FontAwesomeIcons.Solid.Lock,
                    contentDescription = stringResource(Res.string.manga_locked_chapter_title),
                    modifier = Modifier.size(12.dp),
                    tint = FluentTheme.colors.text.text.secondary,
                )
            } else if (item.isRead && !item.isCurrent) {
                FAIcon(
                    imageVector = FontAwesomeIcons.Solid.Check,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = FluentTheme.colors.text.text.secondary,
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = item.chapter.title,
                    style = if (item.isCurrent) FluentTheme.typography.bodyStrong else FluentTheme.typography.body,
                    color = titleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = item.chapter.subtitle?.takeIf { it.isNotBlank() }
                val hasResumePage = !item.isRead && item.lastPageIndex > 0
                val uploadedAt = item.chapter.uploadedAt
                if (subtitle != null || hasResumePage || uploadedAt != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        subtitle?.let { sub ->
                            Text(
                                text = sub,
                                style = FluentTheme.typography.caption,
                                color = FluentTheme.colors.text.text.secondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        if (hasResumePage) {
                            Text(
                                text = stringResource(Res.string.manga_reader_page_number, item.lastPageIndex + 1),
                                style = FluentTheme.typography.caption,
                                color = FluentTheme.colors.text.accent.primary,
                            )
                        }
                        uploadedAt?.let { uploaded ->
                            DateTimeText(
                                data = uploaded,
                                style = FluentTheme.typography.caption,
                                color = FluentTheme.colors.text.text.secondary,
                            )
                        }
                    }
                }
            }
        }
    }

    if (openUrl != null) {
        ContextMenuArea(
            items = {
                listOf(
                    ContextMenuItem(
                        label = openInBrowserLabel,
                        onClick = {
                            uriHandler.openUri(DeeplinkRoute.OpenLinkDirectly(openUrl).toUri())
                        },
                    ),
                )
            },
        ) {
            rowContent()
        }
    } else {
        rowContent()
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

        if (isWideViewport && state.direction == MangaReadingDirection.Webtoon) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.manga_reader_constrain_webtoon_width),
                    style = FluentTheme.typography.body,
                    modifier = Modifier.weight(1f),
                )
                Box(modifier = Modifier.testTag("manga_reader_constrain_webtoon_switcher")) {
                    Switcher(
                        checked = state.webtoonConstrainWidthOnWideScreen,
                        onCheckStateChange = state::setWebtoonConstrainWidthOnWideScreen,
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
