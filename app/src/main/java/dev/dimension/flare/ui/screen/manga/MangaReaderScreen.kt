package dev.dimension.flare.ui.screen.manga

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.annotation.ExperimentalCoilApi
import coil3.imageLoader
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
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
import compose.icons.fontawesomeicons.solid.Download
import compose.icons.fontawesomeicons.solid.EllipsisVertical
import compose.icons.fontawesomeicons.solid.ForwardStep
import compose.icons.fontawesomeicons.solid.Gear
import compose.icons.fontawesomeicons.solid.Language
import compose.icons.fontawesomeicons.solid.Lock
import compose.icons.fontawesomeicons.solid.RotateRight
import compose.icons.fontawesomeicons.solid.ShareNodes
import dev.dimension.flare.R
import dev.dimension.flare.common.AndroidDownloadManager
import dev.dimension.flare.common.AndroidMediaSaveLocationRepository
import dev.dimension.flare.common.MediaFileNamePolicy
import dev.dimension.flare.common.MediaSaveLocationMode
import dev.dimension.flare.common.prepareImageTranslationExport
import dev.dimension.flare.common.shareImageMedia
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.component.DateTimeText
import dev.dimension.flare.ui.component.FAIcon
import dev.dimension.flare.ui.component.FlareDropdownMenu
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
import dev.dimension.flare.ui.presenter.manga.ReaderPageState
import dev.dimension.flare.ui.presenter.manga.ReaderSlot
import dev.dimension.flare.ui.route.DeeplinkRoute
import dev.dimension.flare.ui.route.Route
import dev.dimension.flare.ui.route.toUri
import dev.dimension.flare.ui.screen.media.ImageTranslationErrorDialog
import dev.dimension.flare.ui.screen.settings.imageTranslationText
import dev.dimension.flare.ui.theme.FlareTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.tlaster.precompose.molecule.producePresenter
import org.koin.compose.koinInject
import kotlin.math.roundToInt

internal data class AndroidSpreadPageTarget(
    val pageIndex: Int,
    val ready: PageMediaStatus.Ready,
)

internal data class AndroidReadyPageTarget(
    val page: ReaderPageState,
    val ready: PageMediaStatus.Ready,
)

internal fun shouldAcceptAndroidPageLongPress(page: ReaderPageState): Boolean = page.status is PageMediaStatus.Ready

internal fun resolveLongPressedReadyPageTarget(
    pages: List<ReaderPageState>,
    longPressedPageIndex: Int?,
): AndroidReadyPageTarget? {
    val pageIndex = longPressedPageIndex ?: return null
    val targetPage = pages.firstOrNull { it.index == pageIndex } ?: pages.getOrNull(pageIndex) ?: return null
    val targetReady = targetPage.status as? PageMediaStatus.Ready ?: return null
    return AndroidReadyPageTarget(page = targetPage, ready = targetReady)
}

internal fun resolveVisibleReadyPageTargets(state: MangaReaderPresenter.State): List<AndroidSpreadPageTarget> {
    if (state.pages.isEmpty()) return emptyList()
    val candidateIndices =
        if (state.direction != MangaReadingDirection.Webtoon) {
            when (val slot = state.slots.getOrNull(state.currentSlotIndex)) {
                is ReaderSlot.Double -> listOf(slot.leftPageIndex, slot.rightPageIndex).distinct().sorted()
                is ReaderSlot.Single -> listOf(slot.pageIndex)
                null -> listOf(state.currentPageIndex)
            }
        } else {
            listOf(state.currentPageIndex)
        }
    return candidateIndices.mapNotNull { pageIndex ->
        val page = state.pages.getOrNull(pageIndex) ?: return@mapNotNull null
        val ready = page.status as? PageMediaStatus.Ready ?: return@mapNotNull null
        AndroidSpreadPageTarget(pageIndex = page.index, ready = ready)
    }
}

internal fun resolveAndroidTranslationErrorMedia(
    state: MangaReaderPresenter.State,
    visibleTargets: List<AndroidSpreadPageTarget> = resolveVisibleReadyPageTargets(state),
): UiMedia? =
    (
        visibleTargets.asSequence().map { it.ready.original } +
            state.pages.asSequence().mapNotNull { (it.status as? PageMediaStatus.Ready)?.original }
    ).distinctBy { it.cacheKey }
        .firstOrNull { media ->
            val item = state.imageTranslation.item(media)
            item.error != null || item.errorDetailsVisible
        }

internal fun consumeAndroidReaderVolumeKeyEvent(
    enabled: Boolean,
    keyCode: Int,
    action: Int,
    onStepBackward: () -> Unit,
    onStepForward: () -> Unit,
    repeatCount: Int = 0,
): Boolean {
    if (!enabled) return false
    val stepCallback =
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> onStepBackward
            KeyEvent.KEYCODE_VOLUME_DOWN -> onStepForward
            else -> return false
        }
    return when (action) {
        KeyEvent.ACTION_DOWN -> {
            if (repeatCount == 0) {
                stepCallback()
            }
            true
        }

        KeyEvent.ACTION_UP -> {
            true
        }

        else -> {
            false
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
internal fun MangaReaderScreen(
    mangaKey: MicroBlogKey,
    chapterId: String,
    initialPage: Int?,
    accountType: AccountType,
    navigate: (Route) -> Unit,
    onBack: () -> Unit,
    pendingSave: PendingMangaPageSave = viewModel { PendingMangaPageSave() },
) {
    val state by producePresenter("manga_reader_$accountType-$mangaKey-$chapterId-$initialPage") {
        remember(accountType, mangaKey, chapterId, initialPage) {
            MangaReaderPresenter(
                accountType = accountType,
                mangaKey = mangaKey,
                initialChapterId = chapterId,
                initialPage = initialPage,
            )
        }.invoke()
    }
    val context = LocalContext.current
    val ioScope = koinInject<CoroutineScope>()
    val downloadManager = koinInject<AndroidDownloadManager>()
    val saveLocation = koinInject<AndroidMediaSaveLocationRepository>()
    val permissionState =
        rememberPermissionState(Manifest.permission.WRITE_EXTERNAL_STORAGE) { granted ->
            pendingSave.permissionResult(granted)
            if (!granted) Toast.makeText(context, R.string.media_save_fail, Toast.LENGTH_SHORT).show()
        }

    FlareTheme(darkTheme = true) {
        MangaReaderContent(
            state = state,
            onBack = onBack,
            onSavePage = { original, displayed, pageIndex ->
                val activeChapterId = state.activeChapterId
                val appContext = context.applicationContext
                val performSave = {
                    val fileName =
                        MediaFileNamePolicy.mangaPageFileName(
                            mangaId = mangaKey.id,
                            chapterId = activeChapterId,
                            pageIndex = pageIndex,
                            original = original,
                            displayed = displayed,
                        )
                    saveAndroidMangaPage(
                        scope = ioScope,
                        downloadManager = downloadManager,
                        context = appContext,
                        displayed = displayed,
                        fileName = fileName,
                    )
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    saveLocation.state.value.mode == MediaSaveLocationMode.DefaultDownloads &&
                    !permissionState.status.isGranted
                ) {
                    pendingSave.onGranted = performSave
                    permissionState.launchPermissionRequest()
                } else {
                    performSave()
                }
            },
            onSharePage = { original, displayed, pageIndex ->
                val activeChapterId = state.activeChapterId
                val fallbackShareUrl = state.chapterUrl?.takeIf { it.isNotBlank() }
                ioScope.launch {
                    val prepared =
                        withContext(Dispatchers.IO) {
                            prepareImageTranslationExport(displayed, context.cacheDir)
                        }
                    if (prepared is UiMedia.Image || prepared is UiMedia.Gif) {
                        val fileName =
                            MediaFileNamePolicy.mangaPageFileName(
                                mangaId = mangaKey.id,
                                chapterId = activeChapterId,
                                pageIndex = pageIndex,
                                original = original,
                                displayed = displayed,
                            )
                        shareImageMedia(
                            context = context,
                            media = prepared,
                            fileName = fileName,
                        )
                    } else if (fallbackShareUrl != null) {
                        withContext(Dispatchers.Main) {
                            navigate(
                                Route.Status.ShareSheet(
                                    statusKey = mangaKey,
                                    accountType = accountType,
                                    shareUrl = fallbackShareUrl,
                                ),
                            )
                        }
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MangaReaderContent(
    state: MangaReaderPresenter.State,
    onBack: () -> Unit,
    onSavePage: ((original: UiMedia, displayed: UiMedia, pageIndex: Int) -> Unit)? = null,
    onSharePage: ((original: UiMedia, displayed: UiMedia, pageIndex: Int) -> Unit)? = null,
) {
    val motionScheme = MaterialTheme.motionScheme
    var hudVisible by remember { mutableStateOf(true) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showChapterSheet by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var lockedPromptChapter by remember { mutableStateOf<UiMangaChapter?>(null) }
    var longPressedPageIndex by remember { mutableStateOf<Int?>(null) }
    var scrubPreviewPage by remember { mutableStateOf<Int?>(null) }
    var isWideViewport by remember { mutableStateOf(false) }

    val uriHandler = LocalUriHandler.current
    val hapticFeedback = LocalHapticFeedback.current
    val view = LocalView.current
    val latestState by rememberUpdatedState(state)

    LaunchedEffect(state.activeChapterId) {
        showMoreMenu = false
        scrubPreviewPage = null
        lockedPromptChapter = null
        longPressedPageIndex = null
    }

    ImmersiveSystemBarsEffect(hudVisible = hudVisible)

    val longPressedTarget =
        resolveLongPressedReadyPageTarget(
            pages = state.pages,
            longPressedPageIndex = longPressedPageIndex,
        )
    LaunchedEffect(longPressedPageIndex, longPressedTarget == null) {
        if (longPressedPageIndex != null && longPressedTarget == null) {
            longPressedPageIndex = null
        }
    }

    val anyOverlayOpen =
        showSettingsSheet ||
            showChapterSheet ||
            showMoreMenu ||
            lockedPromptChapter != null ||
            longPressedTarget != null
    val volumeKeysEnabled = state.volumeKeysNavigation && !anyOverlayOpen

    DisposableEffect(view, volumeKeysEnabled) {
        if (volumeKeysEnabled) {
            val listener =
                ViewCompat.OnUnhandledKeyEventListenerCompat { _, event ->
                    consumeAndroidReaderVolumeKeyEvent(
                        enabled = true,
                        keyCode = event.keyCode,
                        action = event.action,
                        onStepBackward = { latestState.stepBackward() },
                        onStepForward = { latestState.stepForward() },
                        repeatCount = event.repeatCount,
                    )
                }
            ViewCompat.addOnUnhandledKeyEventListener(view, listener)
            onDispose {
                ViewCompat.removeOnUnhandledKeyEventListener(view, listener)
            }
        } else {
            onDispose {}
        }
    }

    val currentPage = state.pages.getOrNull(state.currentPageIndex)
    val currentReady = currentPage?.status as? PageMediaStatus.Ready
    val visibleTargets = resolveVisibleReadyPageTargets(state)
    val chapterUrl = state.chapterUrl?.takeIf { it.isNotBlank() }

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onPreviewKeyEvent { keyEvent ->
                    val nativeEvent = keyEvent.nativeKeyEvent
                    consumeAndroidReaderVolumeKeyEvent(
                        enabled = volumeKeysEnabled,
                        keyCode = nativeEvent.keyCode,
                        action = nativeEvent.action,
                        onStepBackward = { latestState.stepBackward() },
                        onStepForward = { latestState.stepForward() },
                        repeatCount = nativeEvent.repeatCount,
                    )
                },
    ) {
        isWideViewport = maxWidth >= 720.dp
        MangaReaderCanvas(
            state = state,
            hudVisible = hudVisible,
            onToggleHud = { hudVisible = !hudVisible },
            onBack = onBack,
            onPageLongPress = { page, _ ->
                if (!shouldAcceptAndroidPageLongPress(page)) return@MangaReaderCanvas
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                longPressedPageIndex = page.index
            },
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
            enter =
                slideInVertically(motionScheme.defaultSpatialSpec()) { -it } +
                    fadeIn(motionScheme.defaultEffectsSpec()),
            exit =
                slideOutVertically(motionScheme.defaultSpatialSpec()) { -it } +
                    fadeOut(motionScheme.defaultEffectsSpec()),
        ) {
            val canOpenChapters = state.chapterItems.isNotEmpty()
            val saveTargets =
                if (onSavePage != null) {
                    visibleTargets.filter { it.ready.original !is UiMedia.Ugoira }
                } else {
                    emptyList()
                }
            val shareTargets =
                if (onSharePage != null) {
                    visibleTargets.filter {
                        it.ready.displayed is UiMedia.Image || it.ready.displayed is UiMedia.Gif
                    }
                } else {
                    emptyList()
                }
            val primarySharePageIndex =
                shareTargets.firstOrNull { it.pageIndex == state.currentPageIndex }?.pageIndex
                    ?: shareTargets.firstOrNull()?.pageIndex
            val regenerateTargets =
                visibleTargets.filter { state.imageTranslation.canRegenerate(it.ready.original) }
            val hasOverflowActions =
                saveTargets.isNotEmpty() ||
                    shareTargets.isNotEmpty() ||
                    regenerateTargets.isNotEmpty() ||
                    chapterUrl != null

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                        ).padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalIconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("manga_reader_back"),
                ) {
                    FAIcon(
                        imageVector = FontAwesomeIcons.Solid.ArrowLeft,
                        contentDescription = stringResource(R.string.navigate_back),
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp)
                            .clip(CircleShape)
                            .then(
                                if (canOpenChapters) {
                                    Modifier.clickable { showChapterSheet = true }
                                } else {
                                    Modifier
                                },
                            ).testTag("manga_reader_chapters_button"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            Text(
                                text =
                                    if (canOpenChapters) {
                                        state.chapterTitle.ifBlank { stringResource(R.string.manga_reader_chapters) }
                                    } else {
                                        state.chapterTitle
                                    },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (state.mangaTitle.isNotBlank()) {
                                Text(
                                    text = state.mangaTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (canOpenChapters) {
                            FAIcon(
                                imageVector = FontAwesomeIcons.Solid.ChevronDown,
                                contentDescription = stringResource(R.string.manga_reader_chapters),
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                            )
                        }
                    }
                }

                currentReady?.let { ready ->
                    val item = state.imageTranslation.item(ready.original)
                    if (state.imageTranslation.canToggle(ready.original)) {
                        FilledTonalIconButton(
                            onClick = { state.imageTranslation.toggle(ready.original) },
                            enabled = !item.loading,
                        ) {
                            FAIcon(
                                imageVector = FontAwesomeIcons.Solid.Language,
                                contentDescription =
                                    imageTranslationText(
                                        when {
                                            item.showingTranslated -> "Show original"
                                            item.translated != null -> "Show translation"
                                            else -> "Translate image"
                                        },
                                    ),
                            )
                        }
                    }
                }

                FilledTonalIconButton(
                    onClick = { showSettingsSheet = true },
                    modifier = Modifier.testTag("manga_reader_settings_button"),
                ) {
                    FAIcon(
                        imageVector = FontAwesomeIcons.Solid.Gear,
                        contentDescription = stringResource(R.string.manga_reader_settings),
                    )
                }

                Box {
                    FilledTonalIconButton(
                        onClick = { showMoreMenu = true },
                        enabled = hasOverflowActions,
                        modifier = Modifier.testTag("manga_reader_more_button"),
                    ) {
                        FAIcon(
                            imageVector = FontAwesomeIcons.Solid.EllipsisVertical,
                            contentDescription = stringResource(R.string.more),
                        )
                    }

                    FlareDropdownMenu(
                        expanded = showMoreMenu && hasOverflowActions,
                        onDismissRequest = { showMoreMenu = false },
                    ) {
                        val isMultiPageSpread = visibleTargets.size > 1
                        saveTargets.forEach { target ->
                            val label =
                                if (isMultiPageSpread) {
                                    "${stringResource(R.string.media_menu_save)} (${
                                        stringResource(R.string.manga_reader_page_number, target.pageIndex + 1)
                                    })"
                                } else {
                                    stringResource(R.string.media_menu_save)
                                }
                            DropdownMenuItem(
                                text = { Text(label) },
                                leadingIcon = {
                                    FAIcon(
                                        imageVector = FontAwesomeIcons.Solid.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = {
                                    showMoreMenu = false
                                    onSavePage?.invoke(target.ready.original, target.ready.displayed, target.pageIndex)
                                },
                            )
                        }

                        shareTargets.forEach { target ->
                            val label =
                                if (isMultiPageSpread) {
                                    "${stringResource(R.string.media_menu_share_image)} (${
                                        stringResource(R.string.manga_reader_page_number, target.pageIndex + 1)
                                    })"
                                } else {
                                    stringResource(R.string.media_menu_share_image)
                                }
                            DropdownMenuItem(
                                text = { Text(label) },
                                leadingIcon = {
                                    FAIcon(
                                        imageVector = FontAwesomeIcons.Solid.ShareNodes,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = {
                                    showMoreMenu = false
                                    onSharePage?.invoke(target.ready.original, target.ready.displayed, target.pageIndex)
                                },
                                modifier =
                                    if (target.pageIndex == primarySharePageIndex) {
                                        Modifier.testTag("manga_reader_share_page")
                                    } else {
                                        Modifier
                                    },
                            )
                        }

                        regenerateTargets.forEach { target ->
                            val item = state.imageTranslation.item(target.ready.original)
                            val baseLabel = imageTranslationText("Translate again")
                            val label =
                                if (isMultiPageSpread) {
                                    "$baseLabel (${
                                        stringResource(R.string.manga_reader_page_number, target.pageIndex + 1)
                                    })"
                                } else {
                                    baseLabel
                                }
                            DropdownMenuItem(
                                text = { Text(label) },
                                enabled = !item.loading,
                                leadingIcon = {
                                    FAIcon(
                                        imageVector = FontAwesomeIcons.Solid.RotateRight,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = {
                                    showMoreMenu = false
                                    state.imageTranslation.regenerate(target.ready.original)
                                },
                            )
                        }

                        chapterUrl?.let { url ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.manga_open_in_browser)) },
                                leadingIcon = {
                                    FAIcon(
                                        imageVector = FontAwesomeIcons.Brands.Chrome,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = {
                                    showMoreMenu = false
                                    uriHandler.openUri(DeeplinkRoute.OpenLinkDirectly(url).toUri())
                                },
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
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding() + 12.dp,
                    ).widthIn(max = BottomSheetDefaults.SheetMaxWidth),
            enter =
                slideInVertically(motionScheme.defaultSpatialSpec()) { it } +
                    fadeIn(motionScheme.defaultEffectsSpec()),
            exit =
                slideOutVertically(motionScheme.defaultSpatialSpec()) { it } +
                    fadeOut(motionScheme.defaultEffectsSpec()),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AnimatedVisibility(
                    visible = scrubPreviewPage != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    val bubblePage = (scrubPreviewPage ?: state.currentPageIndex).coerceIn(0, (state.pages.size - 1).coerceAtLeast(0))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        tonalElevation = 6.dp,
                        shadowElevation = 6.dp,
                        modifier = Modifier.testTag("manga_reader_page_slider_bubble"),
                    ) {
                        Text(
                            text = "${bubblePage + 1} / ${state.pages.size}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }

                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = BottomSheetDefaults.ContainerColor,
                ) {
                    val isRtl = state.direction == MangaReadingDirection.Rtl
                    val prevUnlocked = state.previousBoundary as? ChapterBoundary.Unlocked
                    val nextUnlocked = state.nextBoundary as? ChapterBoundary.Unlocked
                    val leftChapterTarget = if (isRtl) nextUnlocked else prevUnlocked
                    val rightChapterTarget = if (isRtl) prevUnlocked else nextUnlocked
                    val leftChapterLabel =
                        stringResource(if (isRtl) R.string.manga_reader_next_chapter else R.string.manga_reader_previous_chapter)
                    val rightChapterLabel =
                        stringResource(if (isRtl) R.string.manga_reader_previous_chapter else R.string.manga_reader_next_chapter)

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(
                            onClick = { leftChapterTarget?.let(state::openAdjacentChapter) },
                            enabled = leftChapterTarget != null,
                        ) {
                            FAIcon(
                                imageVector = FontAwesomeIcons.Solid.BackwardStep,
                                contentDescription = leftChapterLabel,
                                modifier = Modifier.size(16.dp),
                            )
                        }

                        key(state.activeChapterId) {
                            AndroidMangaPageSlider(
                                activeChapterId = state.activeChapterId,
                                pageCount = state.pages.size,
                                currentPageIndex = state.currentPageIndex,
                                isRtl = isRtl,
                                onPageSelected = state::goToPage,
                                modifier = Modifier.weight(1f),
                                onScrubPageChanged = { scrubPreviewPage = it },
                            )
                        }

                        IconButton(
                            onClick = { rightChapterTarget?.let(state::openAdjacentChapter) },
                            enabled = rightChapterTarget != null,
                        ) {
                            FAIcon(
                                imageVector = FontAwesomeIcons.Solid.ForwardStep,
                                contentDescription = rightChapterLabel,
                                modifier = Modifier.size(16.dp),
                            )
                        }
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
                        bottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding() + 12.dp,
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
                    text = formatAndroidReaderPageBadge(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }

    resolveAndroidTranslationErrorMedia(state = state, visibleTargets = visibleTargets)?.let { errorTarget ->
        ImageTranslationErrorDialog(
            translation = state.imageTranslation,
            media = errorTarget,
        )
    }

    if (showChapterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showChapterSheet = false },
        ) {
            AndroidMangaChapterDirectorySheet(
                state = state,
                onSelectUnlockedChapter = { chapterId ->
                    showChapterSheet = false
                    state.selectChapter(chapterId)
                },
                onSelectLockedChapter = { chapter ->
                    showChapterSheet = false
                    lockedPromptChapter = chapter
                },
            )
        }
    }

    lockedPromptChapter?.let { lockedChapter ->
        val openUrl = state.resolveChapterOpenUrl(lockedChapter)?.takeIf { it.isNotBlank() }
        AlertDialog(
            onDismissRequest = { lockedPromptChapter = null },
            title = { Text(stringResource(R.string.manga_locked_chapter_title)) },
            text = { Text(stringResource(R.string.manga_locked_chapter_message, lockedChapter.title)) },
            confirmButton = {
                if (openUrl != null) {
                    TextButton(
                        onClick = {
                            lockedPromptChapter = null
                            uriHandler.openUri(DeeplinkRoute.OpenLinkDirectly(openUrl).toUri())
                        },
                        modifier = Modifier.testTag("manga_reader_locked_chapter_confirm"),
                    ) {
                        Text(stringResource(R.string.manga_open_in_browser))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { lockedPromptChapter = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    longPressedTarget?.let { target ->
        ModalBottomSheet(
            onDismissRequest = { longPressedPageIndex = null },
        ) {
            AndroidMangaPageActionSheet(
                page = target.page,
                ready = target.ready,
                state = state,
                onSavePage = onSavePage,
                onSharePage = onSharePage,
                onDismiss = { longPressedPageIndex = null },
            )
        }
    }

    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
        ) {
            AndroidMangaReaderSettingsSheet(
                state = state,
                isWideViewport = isWideViewport,
            )
        }
    }
}

@Composable
private fun ImmersiveSystemBarsEffect(hudVisible: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = remember(view) { view.context.findActivity()?.window } ?: return

    DisposableEffect(window, view, hudVisible) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hudVisible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {}
    }

    DisposableEffect(window, view) {
        onDispose {
            WindowCompat
                .getInsetsController(window, view)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private tailrec fun Context?.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AndroidMangaPageSlider(
    activeChapterId: String,
    pageCount: Int,
    currentPageIndex: Int,
    isRtl: Boolean,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onScrubPageChanged: (Int?) -> Unit = {},
) {
    val maxPage = (pageCount - 1).coerceAtLeast(0)
    val hapticFeedback = LocalHapticFeedback.current
    val latestOnScrubPageChanged by rememberUpdatedState(onScrubPageChanged)
    var isDragging by remember(activeChapterId, pageCount) { mutableStateOf(false) }
    var lastDragHapticPage by remember(activeChapterId, pageCount) { mutableIntStateOf(-1) }
    val sliderState =
        remember(activeChapterId, pageCount) {
            SliderState(
                value = currentPageIndex.coerceIn(0, maxPage).toFloat(),
                steps = if (pageCount <= 30) (pageCount - 2).coerceAtLeast(0) else 0,
                trackRange = 0f..maxPage.coerceAtLeast(1).toFloat(),
            )
        }
    LaunchedEffect(activeChapterId, currentPageIndex, maxPage, isDragging) {
        if (!isDragging) {
            sliderState.value = currentPageIndex.coerceIn(0, maxPage).toFloat()
        }
    }
    val displayedPage = sliderState.value.roundToInt().coerceIn(0, maxPage)
    val effectiveDragging = isDragging && maxPage > 0
    LaunchedEffect(activeChapterId, pageCount, effectiveDragging, displayedPage) {
        latestOnScrubPageChanged(if (effectiveDragging) displayedPage else null)
    }
    DisposableEffect(activeChapterId, pageCount) {
        onDispose {
            latestOnScrubPageChanged(null)
        }
    }
    val positionLabel = stringResource(R.string.manga_reader_page_position)
    val pageDescription = stringResource(R.string.manga_reader_page_of, displayedPage + 1, pageCount)

    CompositionLocalProvider(
        LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = (displayedPage + 1).toString(),
                style = MaterialTheme.typography.labelMedium,
            )
            if (maxPage > 0) {
                Slider(
                    state = sliderState,
                    onValueChange = { rawValue ->
                        val page = rawValue.roundToInt().coerceIn(0, maxPage)
                        val previousDisplayedPage =
                            if (isDragging && lastDragHapticPage >= 0) {
                                lastDragHapticPage
                            } else {
                                displayedPage
                            }
                        isDragging = true
                        sliderState.value = page.toFloat()
                        lastDragHapticPage = page
                        if (page != previousDisplayedPage) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        if (page != currentPageIndex) {
                            onPageSelected(page)
                        }
                    },
                    onValueChangeFinished = {
                        val page = sliderState.value.roundToInt().coerceIn(0, maxPage)
                        isDragging = false
                        lastDragHapticPage = -1
                        if (page != currentPageIndex) {
                            onPageSelected(page)
                        }
                    },
                    modifier =
                        Modifier
                            .weight(1f)
                            .semantics {
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
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AndroidMangaChapterDirectorySheet(
    state: MangaReaderPresenter.State,
    onSelectUnlockedChapter: (String) -> Unit,
    onSelectLockedChapter: (UiMangaChapter) -> Unit,
) {
    val initialIndex =
        remember {
            state.chapterItems
                .indexOfFirst { it.chapter.id == state.activeChapterId || it.isCurrent }
                .coerceAtLeast(0)
        }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)

    LaunchedEffect(state.activeChapterId, state.selectedChapterGroupId, state.chapterSortDescending) {
        if (state.chapterItems.isNotEmpty()) {
            val targetIndex =
                state.chapterItems
                    .indexOfFirst { it.chapter.id == state.activeChapterId || it.isCurrent }
                    .coerceAtLeast(0)
            listState.scrollToItem(targetIndex)
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${stringResource(R.string.manga_reader_chapters)} (${state.chapterItems.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(
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
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text =
                        stringResource(
                            if (state.chapterSortDescending) {
                                R.string.manga_detail_sort_descending
                            } else {
                                R.string.manga_detail_sort_ascending
                            },
                        ),
                )
            }
        }

        if (state.chapterGroups.size > 1 || state.chapterGroups.any { it.title != null }) {
            FlowRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.chapterGroups.forEach { group ->
                    val label = group.title?.resolveText()?.takeIf { it.isNotBlank() } ?: group.id
                    FilterChip(
                        selected = group.id == state.selectedChapterGroupId,
                        onClick = { state.selectChapterGroup(group.id) },
                        label = { Text(label) },
                        modifier = Modifier.testTag("manga_reader_chapter_group_${group.id}"),
                    )
                }
            }
        }

        if (state.chapterItems.isEmpty()) {
            Text(
                text = stringResource(R.string.manga_detail_empty_chapters),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp),
            ) {
                items(
                    items = state.chapterItems,
                    key = { it.chapter.id },
                ) { item ->
                    AndroidMangaReaderChapterRow(
                        item = item,
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

@Composable
private fun AndroidMangaReaderChapterRow(
    item: ReaderChapterItem,
    onClick: () -> Unit,
) {
    val chapter = item.chapter
    val backgroundColor =
        if (item.isCurrent) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        } else {
            Color.Transparent
        }
    val titleColor =
        when {
            item.isCurrent -> MaterialTheme.colorScheme.primary
            item.isRead || chapter.locked -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(backgroundColor)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("manga_reader_chapter_item_${chapter.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (chapter.locked) {
            FAIcon(
                imageVector = FontAwesomeIcons.Solid.Lock,
                contentDescription = stringResource(R.string.manga_locked_chapter_title),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (item.isRead && !item.isCurrent) {
            FAIcon(
                imageVector = FontAwesomeIcons.Solid.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = chapter.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (item.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = chapter.subtitle?.takeIf { it.isNotBlank() }
            val hasResumePage = !item.isRead && item.lastPageIndex > 0
            if (subtitle != null || hasResumePage || chapter.uploadedAt != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    subtitle?.let { sub ->
                        Text(
                            text = sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (hasResumePage) {
                        Text(
                            text = stringResource(R.string.manga_reader_page_number, item.lastPageIndex + 1),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    chapter.uploadedAt?.let { uploaded ->
                        DateTimeText(
                            data = uploaded,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AndroidMangaPageActionSheet(
    page: ReaderPageState,
    ready: PageMediaStatus.Ready,
    state: MangaReaderPresenter.State,
    onSavePage: ((original: UiMedia, displayed: UiMedia, pageIndex: Int) -> Unit)?,
    onSharePage: ((original: UiMedia, displayed: UiMedia, pageIndex: Int) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val translationItem = state.imageTranslation.item(ready.original)
    val listItemColors = ListItemDefaults.colors(containerColor = Color.Transparent)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.manga_reader_page_number, page.index + 1),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )

        if (onSavePage != null && ready.original !is UiMedia.Ugoira) {
            ListItem(
                leadingContent = {
                    FAIcon(
                        imageVector = FontAwesomeIcons.Solid.Download,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                colors = listItemColors,
                elevation = ListItemDefaults.elevation(),
                modifier =
                    Modifier
                        .clickable {
                            onDismiss()
                            onSavePage(ready.original, ready.displayed, page.index)
                        }.testTag("manga_reader_page_sheet_save"),
            ) {
                Text(stringResource(R.string.media_menu_save))
            }
        }

        if (onSharePage != null && (ready.displayed is UiMedia.Image || ready.displayed is UiMedia.Gif)) {
            ListItem(
                leadingContent = {
                    FAIcon(
                        imageVector = FontAwesomeIcons.Solid.ShareNodes,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                colors = listItemColors,
                elevation = ListItemDefaults.elevation(),
                modifier =
                    Modifier
                        .clickable {
                            onDismiss()
                            onSharePage(ready.original, ready.displayed, page.index)
                        }.testTag("manga_reader_page_sheet_share"),
            ) {
                Text(stringResource(R.string.media_menu_share_image))
            }
        }

        if (state.imageTranslation.canToggle(ready.original)) {
            ListItem(
                leadingContent = {
                    FAIcon(
                        imageVector = FontAwesomeIcons.Solid.Language,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                colors = listItemColors,
                elevation = ListItemDefaults.elevation(),
                modifier =
                    Modifier
                        .clickable(enabled = !translationItem.loading) {
                            onDismiss()
                            state.imageTranslation.toggle(ready.original)
                        }.testTag("manga_reader_page_sheet_translate"),
            ) {
                Text(
                    imageTranslationText(
                        when {
                            translationItem.showingTranslated -> "Show original"
                            translationItem.translated != null -> "Show translation"
                            else -> "Translate image"
                        },
                    ),
                )
            }
        }

        if (state.imageTranslation.canRegenerate(ready.original)) {
            ListItem(
                leadingContent = {
                    FAIcon(
                        imageVector = FontAwesomeIcons.Solid.RotateRight,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                colors = listItemColors,
                elevation = ListItemDefaults.elevation(),
                modifier =
                    Modifier
                        .clickable(enabled = !translationItem.loading) {
                            onDismiss()
                            state.imageTranslation.regenerate(ready.original)
                        }.testTag("manga_reader_page_sheet_regenerate"),
            ) {
                Text(imageTranslationText("Translate again"))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AndroidMangaReaderSettingsSheet(
    state: MangaReaderPresenter.State,
    isWideViewport: Boolean,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.manga_reader_settings),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.manga_reader_direction),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MangaReadingDirection.entries.forEach { dir ->
                    FilterChip(
                        selected = state.direction == dir,
                        onClick = { state.setDirection(dir) },
                        label = {
                            Text(
                                stringResource(
                                    when (dir) {
                                        MangaReadingDirection.Rtl -> R.string.manga_reader_direction_rtl
                                        MangaReadingDirection.Ltr -> R.string.manga_reader_direction_ltr
                                        MangaReadingDirection.Webtoon -> R.string.manga_reader_direction_webtoon
                                    },
                                ),
                            )
                        },
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
                    text = stringResource(R.string.manga_reader_constrain_webtoon_width),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.webtoonConstrainWidthOnWideScreen,
                    onCheckedChange = state::setWebtoonConstrainWidthOnWideScreen,
                    modifier = Modifier.testTag("manga_reader_constrain_webtoon_switch"),
                )
            }
        }

        if (isWideViewport && state.direction != MangaReadingDirection.Webtoon) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.manga_reader_double_page_spread),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.doublePageSpread,
                    onCheckedChange = state::setDoublePageSpread,
                )
            }

            if (state.doublePageSpread) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.manga_reader_spread_cover_single),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = state.spreadCoverSinglePage,
                        onCheckedChange = state::setSpreadCoverSinglePage,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.manga_reader_volume_keys),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = state.volumeKeysNavigation,
                onCheckedChange = state::setVolumeKeysNavigation,
                modifier = Modifier.testTag("manga_reader_volume_keys_switch"),
            )
        }
    }
}

private fun formatAndroidReaderPageBadge(state: MangaReaderPresenter.State): String {
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

internal class PendingMangaPageSave : ViewModel() {
    var onGranted: (() -> Unit)? = null

    fun permissionResult(granted: Boolean) {
        val save = onGranted
        onGranted = null
        if (granted) save?.invoke()
    }
}

@OptIn(ExperimentalCoilApi::class)
private fun saveAndroidMangaPage(
    scope: CoroutineScope,
    downloadManager: AndroidDownloadManager,
    context: Context,
    displayed: UiMedia,
    fileName: String,
) {
    scope.launch {
        val media =
            withContext(Dispatchers.IO) {
                prepareImageTranslationExport(displayed, context.cacheDir)
            }
        if (media is UiMedia.Image || media is UiMedia.Gif) {
            val cachedSaved =
                withContext(Dispatchers.IO) {
                    context.imageLoader.diskCache?.openSnapshot(media.cacheKey)?.use { snapshot ->
                        val bytes = snapshot.data.toFile().readBytes()
                        downloadManager.saveByteArray(byteArray = bytes, fileName = fileName, keepExisting = true)
                    }
                }
            if (cachedSaved != null) {
                withContext(Dispatchers.Main) {
                    Toast
                        .makeText(
                            context,
                            context.getString(if (cachedSaved) R.string.media_save_success else R.string.media_save_fail),
                            Toast.LENGTH_SHORT,
                        ).show()
                }
                return@launch
            }
        }
        downloadManager.downloadMedia(
            media = media,
            keepExisting = true,
            fileName = fileName,
            callback =
                object : AndroidDownloadManager.DownloadCallback {
                    override fun onDownloadStarted(downloadId: Long) {
                        scope.launch(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.media_download_started), Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onDownloadSuccess(downloadId: Long) {
                        scope.launch(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.media_save_success), Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onDownloadFailed(downloadId: Long) {
                        scope.launch(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.media_save_fail), Toast.LENGTH_SHORT).show()
                        }
                    }
                },
        )
    }
}
