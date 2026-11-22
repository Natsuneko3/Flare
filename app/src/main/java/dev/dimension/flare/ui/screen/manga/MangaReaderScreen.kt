package dev.dimension.flare.ui.screen.manga

import android.Manifest
import android.content.Context
import android.os.Build
import android.widget.Toast
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.annotation.ExperimentalCoilApi
import coil3.imageLoader
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Brands
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.brands.Chrome
import compose.icons.fontawesomeicons.solid.ArrowLeft
import compose.icons.fontawesomeicons.solid.BackwardStep
import compose.icons.fontawesomeicons.solid.Download
import compose.icons.fontawesomeicons.solid.ForwardStep
import compose.icons.fontawesomeicons.solid.Gear
import compose.icons.fontawesomeicons.solid.Language
import compose.icons.fontawesomeicons.solid.RotateRight
import compose.icons.fontawesomeicons.solid.ShareNodes
import dev.dimension.flare.R
import dev.dimension.flare.common.AndroidDownloadManager
import dev.dimension.flare.common.MediaFileNamePolicy
import dev.dimension.flare.common.prepareImageTranslationExport
import dev.dimension.flare.common.shareImageMedia
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

@OptIn(ExperimentalPermissionsApi::class)
@Composable
internal fun MangaReaderScreen(
    mangaKey: MicroBlogKey,
    chapterId: String,
    initialPage: Int?,
    accountType: AccountType,
    navigate: (Route) -> Unit,
    onBack: () -> Unit,
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
    val permissionState = rememberPermissionState(Manifest.permission.WRITE_EXTERNAL_STORAGE)

    FlareTheme(darkTheme = true) {
        MangaReaderContent(
            state = state,
            onBack = onBack,
            onSavePage = { original, displayed, pageIndex ->
                val performSave = {
                    val prepared = prepareImageTranslationExport(displayed, context.cacheDir)
                    val fileName =
                        buildAndroidMangaPageFileName(
                            mangaKey = mangaKey,
                            chapterId = state.activeChapterId,
                            pageIndex = pageIndex,
                            original = original,
                            displayed = displayed,
                        )
                    saveAndroidMangaPage(
                        scope = ioScope,
                        downloadManager = downloadManager,
                        context = context.applicationContext,
                        media = prepared,
                        fileName = fileName,
                    )
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !permissionState.status.isGranted) {
                    permissionState.launchPermissionRequest()
                } else {
                    performSave()
                }
            },
            onSharePage = { original, displayed, pageIndex ->
                val prepared = prepareImageTranslationExport(displayed, context.cacheDir)
                if (prepared is UiMedia.Image) {
                    val fileName =
                        buildAndroidMangaPageFileName(
                            mangaKey = mangaKey,
                            chapterId = state.activeChapterId,
                            pageIndex = pageIndex,
                            original = original,
                            displayed = displayed,
                        )
                    ioScope.launch {
                        shareImageMedia(
                            context = context,
                            media = prepared,
                            fileName = fileName,
                        )
                    }
                } else {
                    state.chapterUrl?.takeIf { it.isNotBlank() }?.let { shareUrl ->
                        navigate(
                            Route.Status.ShareSheet(
                                statusKey = mangaKey,
                                accountType = accountType,
                                shareUrl = shareUrl,
                            ),
                        )
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MangaReaderContent(
    state: MangaReaderPresenter.State,
    onBack: () -> Unit,
    onSavePage: ((original: UiMedia, displayed: UiMedia, pageIndex: Int) -> Unit)? = null,
    onSharePage: ((original: UiMedia, displayed: UiMedia, pageIndex: Int) -> Unit)? = null,
) {
    var hudVisible by remember { mutableStateOf(true) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var isWideViewport by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val currentPage = state.pages.getOrNull(state.currentPageIndex)
    val currentReady = currentPage?.status as? PageMediaStatus.Ready

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        isWideViewport = maxWidth >= 720.dp
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
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .systemBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
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

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = state.chapterTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.mangaTitle.isNotBlank()) {
                            Text(
                                text = state.mangaTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
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
                        if (state.imageTranslation.canRegenerate(ready.original)) {
                            FilledTonalIconButton(
                                onClick = { state.imageTranslation.regenerate(ready.original) },
                                enabled = !item.loading,
                            ) {
                                FAIcon(
                                    imageVector = FontAwesomeIcons.Solid.RotateRight,
                                    contentDescription = imageTranslationText("Translate again"),
                                )
                            }
                        }
                        if (onSavePage != null && ready.original !is UiMedia.Ugoira) {
                            FilledTonalIconButton(
                                onClick = { onSavePage(ready.original, ready.displayed, state.currentPageIndex) },
                            ) {
                                FAIcon(
                                    imageVector = FontAwesomeIcons.Solid.Download,
                                    contentDescription = stringResource(R.string.media_menu_save),
                                )
                            }
                        }
                        if (onSharePage != null && ready.displayed is UiMedia.Image) {
                            FilledTonalIconButton(
                                onClick = { onSharePage(ready.original, ready.displayed, state.currentPageIndex) },
                            ) {
                                FAIcon(
                                    imageVector = FontAwesomeIcons.Solid.ShareNodes,
                                    contentDescription = stringResource(R.string.media_menu_share_image),
                                )
                            }
                        }
                    }

                    state.chapterUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        FilledTonalIconButton(
                            onClick = {
                                uriHandler.openUri(DeeplinkRoute.OpenLinkDirectly(url).toUri())
                            },
                        ) {
                            FAIcon(
                                imageVector = FontAwesomeIcons.Brands.Chrome,
                                contentDescription = stringResource(R.string.manga_open_in_browser),
                            )
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
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = BottomSheetDefaults.ContainerColor.copy(alpha = 0.94f),
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

                    AndroidMangaPageSlider(
                        pageCount = state.pages.size,
                        currentPageIndex = state.currentPageIndex,
                        isRtl = isRtl,
                        onPageSelected = state::goToPage,
                        modifier = Modifier.weight(1f),
                    )

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

    currentReady?.let { ready ->
        ImageTranslationErrorDialog(
            translation = state.imageTranslation,
            media = ready.original,
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AndroidMangaPageSlider(
    pageCount: Int,
    currentPageIndex: Int,
    isRtl: Boolean,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxPage = (pageCount - 1).coerceAtLeast(0)
    var isDragging by remember { mutableStateOf(false) }
    val sliderState =
        remember(pageCount) {
            SliderState(
                value = currentPageIndex.coerceIn(0, maxPage).toFloat(),
                steps = if (pageCount <= 30) (pageCount - 2).coerceAtLeast(0) else 0,
                trackRange = 0f..maxPage.coerceAtLeast(1).toFloat(),
            )
        }
    LaunchedEffect(currentPageIndex, maxPage, isDragging) {
        if (!isDragging) {
            sliderState.value = currentPageIndex.coerceIn(0, maxPage).toFloat()
        }
    }
    val displayedPage = sliderState.value.roundToInt().coerceIn(0, maxPage)
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
                        isDragging = true
                        sliderState.value = page.toFloat()
                        if (page != currentPageIndex) {
                            onPageSelected(page)
                        }
                    },
                    onValueChangeFinished = {
                        val page = sliderState.value.roundToInt().coerceIn(0, maxPage)
                        isDragging = false
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

private fun buildAndroidMangaPageFileName(
    mangaKey: MicroBlogKey,
    chapterId: String,
    pageIndex: Int,
    original: UiMedia,
    displayed: UiMedia,
): String {
    val rawOriginalName = MediaFileNamePolicy.rawMediaFileName(original)
    val ext = rawOriginalName.substringAfterLast('.', "jpg")
    val safeManga = mangaKey.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val safeChapter = chapterId.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val base = "${safeManga}_${safeChapter}_p${pageIndex + 1}.$ext"
    return if (displayed.url == original.url) {
        base
    } else {
        "${base.substringBeforeLast('.')}_${MediaFileNamePolicy.rawMediaFileName(displayed)}"
    }
}

@OptIn(ExperimentalCoilApi::class)
private fun saveAndroidMangaPage(
    scope: CoroutineScope,
    downloadManager: AndroidDownloadManager,
    context: Context,
    media: UiMedia,
    fileName: String,
) {
    scope.launch {
        if (media is UiMedia.Image) {
            val cachedSaved =
                context.imageLoader.diskCache?.openSnapshot(media.cacheKey)?.use { snapshot ->
                    val bytes = snapshot.data.toFile().readBytes()
                    downloadManager.saveByteArray(byteArray = bytes, fileName = fileName)
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
