package dev.dimension.flare.ui.screen.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Brands
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.brands.Chrome
import compose.icons.fontawesomeicons.solid.ArrowDownWideShort
import compose.icons.fontawesomeicons.solid.ArrowUpWideShort
import compose.icons.fontawesomeicons.solid.BookOpen
import compose.icons.fontawesomeicons.solid.Check
import compose.icons.fontawesomeicons.solid.ChevronDown
import compose.icons.fontawesomeicons.solid.ChevronUp
import compose.icons.fontawesomeicons.solid.EllipsisVertical
import compose.icons.fontawesomeicons.solid.Images
import compose.icons.fontawesomeicons.solid.Lock
import compose.icons.fontawesomeicons.solid.ShareNodes
import dev.dimension.flare.R
import dev.dimension.flare.common.PagingState
import dev.dimension.flare.common.onEmpty
import dev.dimension.flare.common.onError
import dev.dimension.flare.data.model.TimelineDisplayMode
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.common.items
import dev.dimension.flare.ui.component.AvatarComponent
import dev.dimension.flare.ui.component.BackButton
import dev.dimension.flare.ui.component.DateTimeText
import dev.dimension.flare.ui.component.ErrorContent
import dev.dimension.flare.ui.component.FAIcon
import dev.dimension.flare.ui.component.FlareDropdownMenu
import dev.dimension.flare.ui.component.FlareScaffold
import dev.dimension.flare.ui.component.FlareTopAppBar
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.component.NetworkImage
import dev.dimension.flare.ui.component.RefreshContainer
import dev.dimension.flare.ui.component.RichText
import dev.dimension.flare.ui.component.TranslationButton
import dev.dimension.flare.ui.component.TranslationRetryButton
import dev.dimension.flare.ui.component.manga.MangaActionsMenu
import dev.dimension.flare.ui.component.manga.MangaTagGroupsSection
import dev.dimension.flare.ui.component.placeholder
import dev.dimension.flare.ui.component.platform.isBigScreen
import dev.dimension.flare.ui.component.resolveText
import dev.dimension.flare.ui.component.status.AdaptiveCard
import dev.dimension.flare.ui.component.status.GalleryTimelineItem
import dev.dimension.flare.ui.component.status.LazyStatusVerticalStaggeredGrid
import dev.dimension.flare.ui.component.status.ListEmptyView
import dev.dimension.flare.ui.component.status.status
import dev.dimension.flare.ui.component.toImageVector
import dev.dimension.flare.ui.model.ClickContext
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.UiIcon
import dev.dimension.flare.ui.model.UiMangaChapter
import dev.dimension.flare.ui.model.UiMangaDetail
import dev.dimension.flare.ui.model.UiMangaStatus
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.onError
import dev.dimension.flare.ui.model.onLoading
import dev.dimension.flare.ui.model.onSuccess
import dev.dimension.flare.ui.model.takeSuccess
import dev.dimension.flare.ui.presenter.invoke
import dev.dimension.flare.ui.presenter.manga.MangaDetailPresenter
import dev.dimension.flare.ui.presenter.manga.MangaTranslatePresenter
import dev.dimension.flare.ui.presenter.manga.PrimaryReadTarget
import dev.dimension.flare.ui.presenter.manga.UiMangaChapterItem
import dev.dimension.flare.ui.render.UiRichText
import dev.dimension.flare.ui.render.uiRichTextOf
import dev.dimension.flare.ui.route.DeeplinkRoute
import dev.dimension.flare.ui.route.Route
import dev.dimension.flare.ui.route.toUri
import dev.dimension.flare.ui.theme.screenHorizontalPadding
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import moe.tlaster.nestedscrollview.NestedScrollViewState
import moe.tlaster.nestedscrollview.VerticalNestedScrollView
import moe.tlaster.nestedscrollview.rememberNestedScrollViewState
import moe.tlaster.precompose.molecule.producePresenter

private val MangaGridSpacing = 8.dp
private val MangaMetaPaneWidth = 380.dp

internal enum class AndroidMangaTab {
    Chapters,
    Comments,
    Recommendations,
}

internal fun computeAndroidMangaTopBarAlpha(
    offset: Float,
    maxOffset: Float,
): Float {
    if (!offset.isFinite() || !maxOffset.isFinite() || maxOffset == 0f) {
        return 0f
    }
    val progress = offset / maxOffset
    return if (progress <= 0f) 0f else progress.coerceAtMost(1f)
}

internal fun buildAndroidMangaTabs(
    supportsComments: Boolean,
    supportsRecommendations: Boolean,
): List<AndroidMangaTab> =
    buildList {
        add(AndroidMangaTab.Chapters)
        if (supportsComments) add(AndroidMangaTab.Comments)
        if (supportsRecommendations) add(AndroidMangaTab.Recommendations)
    }

internal fun resolveActiveAndroidMangaTabIndex(
    currentPage: Int,
    tabCount: Int,
): Int {
    if (tabCount <= 1) return 0
    return currentPage.coerceIn(0, tabCount - 1)
}

internal fun resolveAndroidMangaTabAfterCapabilityChange(
    selectedTab: AndroidMangaTab,
    availableTabs: List<AndroidMangaTab>,
): Int = availableTabs.indexOf(selectedTab).takeIf { it >= 0 } ?: 0

internal fun resolveAndroidPagerActivePage(
    selectedTab: AndroidMangaTab,
    availableTabs: List<AndroidMangaTab>,
    previousTabs: List<AndroidMangaTab>,
    pagerCurrentPage: Int,
): Int {
    val targetIndex =
        resolveAndroidMangaTabAfterCapabilityChange(
            selectedTab = selectedTab,
            availableTabs = availableTabs,
        )
    return if (availableTabs != previousTabs) {
        targetIndex
    } else {
        resolveActiveAndroidMangaTabIndex(
            currentPage = pagerCurrentPage,
            tabCount = availableTabs.size,
        )
    }
}

internal fun UiMangaDetail.hasRenderableSynopsis(): Boolean {
    val text = content ?: return false
    return !text.isEmpty &&
        (text.innerText.isNotBlank() || text.imageUrls.isNotEmpty() || text.imageMedias.isNotEmpty())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MangaDetailScreen(
    mangaKey: MicroBlogKey,
    accountType: AccountType,
    navigate: (Route) -> Unit,
    onBack: () -> Unit,
) {
    val state by producePresenter("manga_detail_$accountType-$mangaKey") {
        remember(accountType, mangaKey) {
            MangaDetailPresenter(accountType = accountType, mangaKey = mangaKey)
        }.invoke()
    }
    MangaDetailContent(
        state = state,
        navigate = navigate,
        onBack = onBack,
        onRetry = state::refresh,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MangaCommentsScreen(
    mangaKey: MicroBlogKey,
    accountType: AccountType,
    onBack: () -> Unit,
) {
    val state by producePresenter("manga_comments_$accountType-$mangaKey") {
        remember(accountType, mangaKey) {
            MangaDetailPresenter(accountType = accountType, mangaKey = mangaKey)
        }.invoke()
    }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    MangaCardTimeline {
        FlareScaffold(
            topBar = {
                FlareTopAppBar(
                    title = { Text(stringResource(R.string.manga_detail_tab_comments)) },
                    navigationIcon = { BackButton(onBack = onBack) },
                    scrollBehavior = scrollBehavior,
                )
            },
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        ) { contentPadding ->
            LazyStatusVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(1),
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                forceCardMode = true,
                cardGridPadding = screenHorizontalPadding,
            ) {
                status(state.comments)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MangaDetailContent(
    state: MangaDetailPresenter.State,
    navigate: (Route) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit = state::refresh,
) {
    val isWideScreen = isBigScreen()
    val nestedScrollState = rememberNestedScrollViewState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val uriHandler = LocalUriHandler.current
    var lockedPromptChapter by remember { mutableStateOf<UiMangaChapter?>(null) }
    val availableTabs =
        remember(state.supportsComments, state.supportsRecommendations) {
            buildAndroidMangaTabs(
                supportsComments = state.supportsComments,
                supportsRecommendations = state.supportsRecommendations,
            )
        }
    var selectedTab by rememberSaveable { mutableStateOf(AndroidMangaTab.Chapters) }
    LaunchedEffect(availableTabs) {
        if (selectedTab !in availableTabs) {
            selectedTab = AndroidMangaTab.Chapters
        }
    }

    val loadedDetail = state.detail.takeSuccess()
    val hasLoadedDetail = loadedDetail != null
    val shareUrl = loadedDetail?.url?.takeIf { it.isNotBlank() }

    MangaCardTimeline {
        FlareScaffold(
            topBar = {
                val titleAlpha by remember(isWideScreen, hasLoadedDetail) {
                    derivedStateOf {
                        if (isWideScreen || !hasLoadedDetail) {
                            1f
                        } else {
                            computeAndroidMangaTopBarAlpha(
                                offset = nestedScrollState.offset,
                                maxOffset = nestedScrollState.maxOffset,
                            )
                        }
                    }
                }
                val showHeroIconPills = !isWideScreen && hasLoadedDetail

                Box {
                    Column(
                        modifier =
                            Modifier.graphicsLayer {
                                alpha = titleAlpha
                            },
                    ) {
                        Spacer(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .windowInsetsTopHeight(WindowInsets.statusBars)
                                    .background(MaterialTheme.colorScheme.background),
                        )
                        Spacer(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(64.dp)
                                    .background(MaterialTheme.colorScheme.background),
                        )
                    }
                    FlareTopAppBar(
                        title = {
                            Text(
                                text = loadedDetail?.title.orEmpty(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier =
                                    Modifier.graphicsLayer {
                                        alpha = titleAlpha
                                    },
                            )
                        },
                        navigationIcon = {
                            BackButton(onBack = onBack)
                        },
                        actions = {
                            Box(modifier = Modifier.padding(end = 8.dp)) {
                                if (showHeroIconPills) {
                                    Spacer(
                                        modifier =
                                            Modifier
                                                .matchParentSize()
                                                .graphicsLayer {
                                                    alpha = 0.72f * (1f - titleAlpha)
                                                }.background(
                                                    color = MaterialTheme.colorScheme.surface,
                                                    shape = CircleShape,
                                                ),
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    state.translation?.let { translation ->
                                        TranslationButton(
                                            showTranslation = translation.showTranslation,
                                            onClick = translation::toggleTranslation,
                                        )
                                    }
                                    IconButton(
                                        enabled = shareUrl != null,
                                        onClick = {
                                            shareUrl?.let { url ->
                                                uriHandler.openUri(DeeplinkRoute.OpenLinkDirectly(url).toUri())
                                            }
                                        },
                                    ) {
                                        FAIcon(
                                            imageVector = FontAwesomeIcons.Brands.Chrome,
                                            contentDescription = stringResource(R.string.manga_open_in_browser),
                                        )
                                    }
                                    IconButton(
                                        enabled = loadedDetail != null && shareUrl != null,
                                        onClick = {
                                            if (loadedDetail != null && shareUrl != null) {
                                                navigate(
                                                    Route.Status.ShareSheet(
                                                        statusKey = loadedDetail.mangaKey,
                                                        accountType = loadedDetail.accountType,
                                                        shareUrl = shareUrl,
                                                    ),
                                                )
                                            }
                                        },
                                    ) {
                                        FAIcon(
                                            imageVector = FontAwesomeIcons.Solid.ShareNodes,
                                            contentDescription = stringResource(R.string.gallery_detail_share_content_description),
                                        )
                                    }
                                }
                            }
                        },
                        colors =
                            TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
                                scrolledContainerColor = Color.Transparent,
                                actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        scrollBehavior = scrollBehavior,
                    )
                }
            },
            contentWindowInsets =
                if (isWideScreen) {
                    ScaffoldDefaults.contentWindowInsets
                } else {
                    ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.statusBars)
                },
            containerColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        ) { contentPadding ->
            state.detail
                .onSuccess { detail ->
                    RefreshContainer(
                        isRefreshing = state.isRefreshing,
                        onRefresh = state::refresh,
                        indicatorPadding = contentPadding,
                        modifier = Modifier.fillMaxSize(),
                        content = {
                            if (isWideScreen) {
                                WideAndroidMangaDetailContent(
                                    detail = detail,
                                    state = state,
                                    availableTabs = availableTabs,
                                    selectedTab = selectedTab,
                                    onSelectTab = { selectedTab = it },
                                    contentPadding = contentPadding,
                                    navigate = navigate,
                                    onLockedChapterClick = { lockedPromptChapter = it },
                                )
                            } else {
                                CompactAndroidMangaDetailContent(
                                    detail = detail,
                                    state = state,
                                    availableTabs = availableTabs,
                                    selectedTab = selectedTab,
                                    onSelectTab = { selectedTab = it },
                                    nestedScrollState = nestedScrollState,
                                    contentPadding = contentPadding,
                                    navigate = navigate,
                                    onLockedChapterClick = { lockedPromptChapter = it },
                                )
                            }
                        },
                    )
                    lockedPromptChapter?.let { lockedChapter ->
                        val openUrl = lockedChapter.openUrl(detail.url)
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
                                        modifier = Modifier.testTag("manga_locked_chapter_confirm"),
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
                }.onLoading {
                    MangaAndroidLoading(contentPadding)
                }.onError { error ->
                    ErrorContent(
                        error = error,
                        onRetry = onRetry,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(contentPadding),
                    )
                }
        }
    }
}

@Composable
private fun MangaCardTimeline(content: @Composable () -> Unit) {
    val appearance = LocalTimelineAppearance.current
    CompositionLocalProvider(
        LocalTimelineAppearance provides appearance.copy(timelineDisplayMode = TimelineDisplayMode.Card),
    ) {
        content()
    }
}

@Composable
private fun WideAndroidMangaDetailContent(
    detail: UiMangaDetail,
    state: MangaDetailPresenter.State,
    availableTabs: List<AndroidMangaTab>,
    selectedTab: AndroidMangaTab,
    onSelectTab: (AndroidMangaTab) -> Unit,
    contentPadding: PaddingValues,
    navigate: (Route) -> Unit,
    onLockedChapterClick: (UiMangaChapter) -> Unit,
) {
    val bottomPadding = contentPadding.calculateBottomPadding()
    Row(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(top = contentPadding.calculateTopPadding()),
    ) {
        Surface(
            modifier =
                Modifier
                    .width(MangaMetaPaneWidth)
                    .fillMaxHeight(),
            color = MaterialTheme.colorScheme.background,
        ) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = bottomPadding + 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "hero") {
                    MangaAndroidHeroHeader(
                        detail = detail,
                        translation = state.translation,
                        primaryReadTarget = state.primaryReadTarget,
                        topContentPadding = 16.dp,
                        navigate = navigate,
                    )
                }
                if (detail.hasRenderableSynopsis() || detail.tagGroups.any { it.tags.isNotEmpty() }) {
                    item(key = "synopsis_tags") {
                        Box(modifier = Modifier.padding(horizontal = screenHorizontalPadding)) {
                            MangaAndroidSynopsisAndTagsCard(detail = detail)
                        }
                    }
                }
            }
        }

        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        MangaAndroidTabsPager(
            detail = detail,
            state = state,
            availableTabs = availableTabs,
            selectedTab = selectedTab,
            onSelectTab = onSelectTab,
            bottomPadding = bottomPadding,
            navigate = navigate,
            onLockedChapterClick = onLockedChapterClick,
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
        )
    }
}

@Composable
private fun CompactAndroidMangaDetailContent(
    detail: UiMangaDetail,
    state: MangaDetailPresenter.State,
    availableTabs: List<AndroidMangaTab>,
    selectedTab: AndroidMangaTab,
    onSelectTab: (AndroidMangaTab) -> Unit,
    nestedScrollState: NestedScrollViewState,
    contentPadding: PaddingValues,
    navigate: (Route) -> Unit,
    onLockedChapterClick: (UiMangaChapter) -> Unit,
) {
    val hasSynopsisOrTags = detail.hasRenderableSynopsis() || detail.tagGroups.any { it.tags.isNotEmpty() }
    VerticalNestedScrollView(
        state = nestedScrollState,
        contentTopPadding = contentPadding.calculateTopPadding(),
        modifier = Modifier.fillMaxSize(),
        header = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background),
            ) {
                MangaAndroidHeroHeader(
                    detail = detail,
                    translation = state.translation,
                    primaryReadTarget = state.primaryReadTarget,
                    topContentPadding = contentPadding.calculateTopPadding() + 8.dp,
                    navigate = navigate,
                )
                if (hasSynopsisOrTags) {
                    Box(
                        modifier =
                            Modifier.padding(
                                start = screenHorizontalPadding,
                                end = screenHorizontalPadding,
                                bottom = 8.dp,
                            ),
                    ) {
                        MangaAndroidSynopsisAndTagsCard(detail = detail)
                    }
                }
            }
        },
        content = {
            MangaAndroidTabsPager(
                detail = detail,
                state = state,
                availableTabs = availableTabs,
                selectedTab = selectedTab,
                onSelectTab = onSelectTab,
                bottomPadding = contentPadding.calculateBottomPadding(),
                navigate = navigate,
                onLockedChapterClick = onLockedChapterClick,
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}

@Composable
private fun MangaAndroidTabsPager(
    detail: UiMangaDetail,
    state: MangaDetailPresenter.State,
    availableTabs: List<AndroidMangaTab>,
    selectedTab: AndroidMangaTab,
    onSelectTab: (AndroidMangaTab) -> Unit,
    bottomPadding: Dp,
    navigate: (Route) -> Unit,
    onLockedChapterClick: (UiMangaChapter) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (availableTabs.size == 1) {
        MangaAndroidChaptersTab(
            detail = detail,
            state = state,
            bottomPadding = bottomPadding,
            navigate = navigate,
            onLockedChapterClick = onLockedChapterClick,
            modifier = modifier,
        )
        return
    }

    var previousTabs by remember { mutableStateOf(availableTabs) }
    val currentSelectedTab by rememberUpdatedState(selectedTab)
    val currentAvailableTabs by rememberUpdatedState(availableTabs)
    val currentOnSelectTab by rememberUpdatedState(onSelectTab)

    val targetIndex =
        resolveAndroidMangaTabAfterCapabilityChange(
            selectedTab = selectedTab,
            availableTabs = availableTabs,
        )
    val pagerState = rememberPagerState(initialPage = targetIndex, pageCount = { availableTabs.size })
    val activePage =
        resolveAndroidPagerActivePage(
            selectedTab = selectedTab,
            availableTabs = availableTabs,
            previousTabs = previousTabs,
            pagerCurrentPage = pagerState.currentPage,
        )
    LaunchedEffect(pagerState, availableTabs) {
        if (availableTabs != previousTabs) {
            val resolvedIndex =
                resolveAndroidMangaTabAfterCapabilityChange(
                    selectedTab = currentSelectedTab,
                    availableTabs = availableTabs,
                )
            val resolvedTab = availableTabs.getOrElse(resolvedIndex) { AndroidMangaTab.Chapters }
            if (currentSelectedTab != resolvedTab) {
                currentOnSelectTab(resolvedTab)
            }
            try {
                if (
                    pagerState.currentPage != resolvedIndex ||
                    pagerState.settledPage != resolvedIndex ||
                    pagerState.currentPageOffsetFraction != 0f
                ) {
                    pagerState.scrollToPage(resolvedIndex)
                }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
            }
            previousTabs = availableTabs
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage to pagerState.isScrollInProgress }
            .collect { (settledPage, isScrolling) ->
                val tabs = currentAvailableTabs
                if (!isScrolling && tabs == previousTabs) {
                    val settledTab = tabs.getOrElse(settledPage) { AndroidMangaTab.Chapters }
                    if (currentSelectedTab != settledTab) {
                        currentOnSelectTab(settledTab)
                    }
                }
            }
    }
    val scope = rememberCoroutineScope()

    Column(modifier = modifier) {
        SecondaryTabRow(
            selectedTabIndex = activePage,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            availableTabs.forEachIndexed { index, tab ->
                Tab(
                    selected = activePage == index,
                    onClick = {
                        onSelectTab(tab)
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    text = {
                        Text(
                            text =
                                stringResource(
                                    when (tab) {
                                        AndroidMangaTab.Chapters -> R.string.manga_detail_chapters_title
                                        AndroidMangaTab.Comments -> R.string.manga_detail_tab_comments
                                        AndroidMangaTab.Recommendations -> R.string.manga_detail_tab_recommendations
                                    },
                                ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            key = { page -> availableTabs[page] },
            modifier = Modifier.weight(1f),
        ) { page ->
            when (availableTabs.getOrElse(page) { AndroidMangaTab.Chapters }) {
                AndroidMangaTab.Chapters -> {
                    MangaAndroidChaptersTab(
                        detail = detail,
                        state = state,
                        bottomPadding = bottomPadding,
                        navigate = navigate,
                        onLockedChapterClick = onLockedChapterClick,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                AndroidMangaTab.Comments -> {
                    LazyStatusVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(1),
                        contentPadding =
                            PaddingValues(
                                top = 8.dp,
                                bottom = bottomPadding + 24.dp,
                            ),
                        modifier = Modifier.fillMaxSize(),
                        forceCardMode = true,
                        cardGridPadding = screenHorizontalPadding,
                    ) {
                        status(state.comments)
                    }
                }

                AndroidMangaTab.Recommendations -> {
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Adaptive(140.dp),
                        contentPadding =
                            PaddingValues(
                                start = screenHorizontalPadding,
                                top = 8.dp,
                                end = screenHorizontalPadding,
                                bottom = bottomPadding + 24.dp,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(MangaGridSpacing),
                        verticalItemSpacing = MangaGridSpacing,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        state.recommendations.onEmpty {
                            item(span = StaggeredGridItemSpan.FullLine) {
                                ListEmptyView(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 32.dp),
                                    text = stringResource(R.string.manga_detail_empty_recommendations),
                                    icon = FontAwesomeIcons.Solid.Images,
                                )
                            }
                        }
                        mangaAndroidRecommendationItems(state.recommendations)
                    }
                }
            }
        }
    }
}

@Composable
private fun MangaAndroidChaptersTab(
    detail: UiMangaDetail,
    state: MangaDetailPresenter.State,
    bottomPadding: Dp,
    navigate: (Route) -> Unit,
    onLockedChapterClick: (UiMangaChapter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalChapters = state.chapters.size
    LazyColumn(
        contentPadding =
            PaddingValues(
                start = screenHorizontalPadding,
                top = 12.dp,
                end = screenHorizontalPadding,
                bottom = bottomPadding + 24.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier,
    ) {
        item(key = "chapters_header") {
            Box(modifier = Modifier.padding(bottom = 6.dp)) {
                MangaAndroidChaptersHeader(
                    detail = detail,
                    selectedGroupId = state.selectedGroupId,
                    chapterSortDescending = state.chapterSortDescending,
                    chapterCount = totalChapters,
                    onSelectGroup = state::selectGroup,
                    onToggleSort = state::toggleChapterSort,
                )
            }
        }
        if (state.chapters.isEmpty()) {
            item(key = "chapters_empty") {
                Text(
                    text = stringResource(R.string.manga_detail_empty_chapters),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        } else {
            itemsIndexed(
                items = state.chapters,
                key = { _, item -> item.chapter.id },
            ) { index, item ->
                MangaAndroidChapterRow(
                    item = item,
                    index = index,
                    totalCount = totalChapters,
                    onClick = {
                        if (item.chapter.locked) {
                            onLockedChapterClick(item.chapter)
                        } else {
                            navigate(
                                Route.Manga.Reader(
                                    mangaKey = detail.mangaKey,
                                    chapterId = item.chapter.id,
                                    initialPage = null,
                                    accountType = detail.accountType,
                                ),
                            )
                        }
                    },
                    onSetRead = { isRead ->
                        state.setChapterRead(item.chapter.id, isRead)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MangaAndroidHeroHeader(
    detail: UiMangaDetail,
    translation: MangaTranslatePresenter.State?,
    primaryReadTarget: PrimaryReadTarget?,
    topContentPadding: Dp,
    navigate: (Route) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val authorProfile = detail.author
    val authorNames =
        remember(detail.authors, detail.artists) {
            (detail.authors + detail.artists).distinct().joinToString(" · ")
        }
    val backgroundColor = MaterialTheme.colorScheme.background
    val fallbackContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)

    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .clipToBounds(),
        ) {
            val cover = detail.cover
            if (cover != null) {
                NetworkImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .blur(28.dp),
                )
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.0f to backgroundColor.copy(alpha = 0.32f),
                                    0.55f to backgroundColor.copy(alpha = 0.76f),
                                    1.0f to backgroundColor,
                                ),
                            ),
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(fallbackContainerColor, backgroundColor),
                                ),
                            ),
                )
            }
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = screenHorizontalPadding,
                        top = topContentPadding,
                        end = screenHorizontalPadding,
                        bottom = 14.dp,
                    ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                detail.cover?.let { cover ->
                    Surface(
                        shadowElevation = 6.dp,
                        shape = MaterialTheme.shapes.medium,
                        modifier =
                            Modifier
                                .width(116.dp)
                                .aspectRatio(2f / 3f),
                    ) {
                        NetworkImage(
                            model = cover,
                            contentDescription = detail.title,
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .clickable {
                                        navigate(
                                            Route.Media.RawMedia(
                                                medias = persistentListOf(cover),
                                                index = 0,
                                                preview = cover.previewUrl,
                                            ),
                                        )
                                    },
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = detail.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    detail.altTitles.firstOrNull { it.isNotBlank() }?.let { alt ->
                        Text(
                            text = alt,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (authorProfile != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier =
                                Modifier.clickable {
                                    navigate(Route.Profile.User(detail.accountType, authorProfile.key))
                                },
                        ) {
                            AvatarComponent(data = authorProfile.avatar, size = 24.dp)
                            RichText(
                                text = authorProfile.name,
                                textStyle = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else if (authorNames.isNotBlank()) {
                        Text(
                            text = authorNames,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        detail.status?.let { status ->
                            MangaAndroidStatusBadge(status = status)
                        }
                        detail.updatedAt?.let { updated ->
                            DateTimeText(
                                data = updated,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (translation?.isTranslating == true) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (translation?.error != null) {
                TranslationRetryButton(onClick = translation::retry)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                primaryReadTarget?.let { target ->
                    Button(
                        onClick = {
                            navigate(
                                Route.Manga.Reader(
                                    mangaKey = detail.mangaKey,
                                    chapterId = target.chapterId,
                                    initialPage = target.initialPage,
                                    accountType = detail.accountType,
                                ),
                            )
                        },
                        modifier =
                            Modifier
                                .weight(1f)
                                .testTag("manga_primary_read_cta"),
                    ) {
                        FAIcon(
                            imageVector = FontAwesomeIcons.Solid.BookOpen,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text =
                                when {
                                    target.isContinue -> stringResource(R.string.manga_detail_continue_chapter, target.chapterTitle)
                                    target.isSingleChapterWork -> stringResource(R.string.manga_detail_read)
                                    else -> stringResource(R.string.manga_detail_start_chapter, target.chapterTitle)
                                },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } ?: Spacer(Modifier.weight(1f))

                if (detail.actions.isEmpty() && (detail.bookmarkAction != ClickEvent.Noop || detail.isBookmarked)) {
                    FilledTonalIconButton(
                        onClick = {
                            detail.onBookmark.invoke(ClickContext(uriHandler::openUri))
                        },
                        enabled = detail.bookmarkAction != ClickEvent.Noop,
                        modifier = Modifier.testTag("manga_cta_bookmark_fallback"),
                    ) {
                        FAIcon(
                            imageVector =
                                if (detail.isBookmarked) {
                                    UiIcon.Unlike.toImageVector()
                                } else {
                                    UiIcon.Like.toImageVector()
                                },
                            contentDescription =
                                stringResource(
                                    if (detail.isBookmarked) {
                                        R.string.channel_item_unfavourite
                                    } else {
                                        R.string.channel_item_favourite
                                    },
                                ),
                            tint =
                                if (detail.isBookmarked) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (detail.actions.isNotEmpty()) {
                MangaActionsMenu(
                    actions = detail.actions,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun MangaAndroidStatusBadge(status: UiMangaStatus) {
    val label =
        stringResource(
            when (status) {
                UiMangaStatus.Ongoing -> R.string.manga_status_ongoing
                UiMangaStatus.Completed -> R.string.manga_status_completed
                UiMangaStatus.Hiatus -> R.string.manga_status_hiatus
                UiMangaStatus.Cancelled -> R.string.manga_status_cancelled
            },
        )
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun MangaAndroidSynopsisAndTagsCard(detail: UiMangaDetail) {
    AdaptiveCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("manga_synopsis_tags_card"),
        index = 0,
        totalCount = 1,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            detail.content?.takeIf { detail.hasRenderableSynopsis() }?.let { content ->
                MangaAndroidCollapsibleSynopsis(content = content)
            }
            MangaTagGroupsSection(
                tagGroups = detail.tagGroups,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MangaAndroidCollapsibleSynopsis(content: UiRichText) {
    var expanded by remember(content.raw) { mutableStateOf(false) }
    val multiBlockOverflow = content.renderRuns.size > 4 || content.innerText.count { it == '\n' } >= 4
    var hasOverflow by remember(content.raw) { mutableStateOf(multiBlockOverflow) }
    val displayedText =
        remember(content, expanded) {
            if (!expanded && content.renderRuns.size > 4) {
                uiRichTextOf(renderRuns = content.renderRuns.take(4), raw = content.raw)
            } else {
                content
            }
        }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RichText(
            text = displayedText,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                if (!expanded && (result.hasVisualOverflow || multiBlockOverflow)) {
                    hasOverflow = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (hasOverflow || expanded) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { expanded = !expanded }
                        .padding(vertical = 4.dp)
                        .testTag("manga_synopsis_toggle"),
                contentAlignment = Alignment.Center,
            ) {
                FAIcon(
                    imageVector = if (expanded) FontAwesomeIcons.Solid.ChevronUp else FontAwesomeIcons.Solid.ChevronDown,
                    contentDescription = stringResource(if (expanded) R.string.expanded else R.string.collapsed),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MangaAndroidChaptersHeader(
    detail: UiMangaDetail,
    selectedGroupId: String?,
    chapterSortDescending: Boolean,
    chapterCount: Int,
    onSelectGroup: (String) -> Unit,
    onToggleSort: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${stringResource(R.string.manga_detail_chapters_title)} ($chapterCount)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(
                onClick = onToggleSort,
                modifier = Modifier.testTag("manga_chapter_sort_toggle"),
            ) {
                FAIcon(
                    imageVector =
                        if (chapterSortDescending) {
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
                            if (chapterSortDescending) {
                                R.string.manga_detail_sort_descending
                            } else {
                                R.string.manga_detail_sort_ascending
                            },
                        ),
                )
            }
        }

        if (detail.chapterGroups.size > 1 || detail.chapterGroups.any { it.title != null }) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                detail.chapterGroups.forEach { group ->
                    val label = group.title?.resolveText()?.takeIf { it.isNotBlank() } ?: group.id
                    FilterChip(
                        selected = group.id == selectedGroupId,
                        onClick = { onSelectGroup(group.id) },
                        label = { Text(label) },
                        modifier = Modifier.testTag("manga_chapter_group_${group.id}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun MangaAndroidChapterRow(
    item: UiMangaChapterItem,
    index: Int,
    totalCount: Int,
    onClick: () -> Unit,
    onSetRead: (Boolean) -> Unit,
) {
    val chapter = item.chapter
    var menuExpanded by remember { mutableStateOf(false) }
    val titleColor =
        when {
            item.isCurrent -> MaterialTheme.colorScheme.primary
            item.isRead || chapter.locked -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        }

    AdaptiveCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("manga_chapter_row_${chapter.id}"),
        index = index,
        totalCount = totalCount,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (item.isCurrent) {
                Box(modifier = Modifier.matchParentSize()) {
                    Spacer(
                        modifier =
                            Modifier
                                .align(Alignment.CenterStart)
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clickable(onClick = onClick)
                            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
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
                    } else if (item.isRead) {
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
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            chapter.subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
                                Text(
                                    text = sub,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (!item.isRead && item.lastPageIndex > 0 && item.pageCount > 0) {
                                Text(
                                    text = stringResource(R.string.manga_detail_page_progress, item.lastPageIndex + 1, item.pageCount),
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

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.testTag("manga_chapter_menu_${chapter.id}"),
                    ) {
                        FAIcon(
                            imageVector = FontAwesomeIcons.Solid.EllipsisVertical,
                            contentDescription = stringResource(R.string.more),
                        )
                    }
                    FlareDropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (item.isRead) {
                                            R.string.manga_detail_mark_unread
                                        } else {
                                            R.string.manga_detail_mark_read
                                        },
                                    ),
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onSetRead(!item.isRead)
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun LazyStaggeredGridScope.mangaAndroidRecommendationItems(recommendations: PagingState<UiTimelineV2>) {
    items(
        recommendations,
        loadingContent = {
            GalleryTimelineItem(item = null)
        },
        errorContent = { error ->
            ErrorContent(
                error = error,
                onRetry = {
                    recommendations.onError {
                        onRetry.invoke()
                    }
                },
            )
        },
    ) { item ->
        GalleryTimelineItem(item = item)
    }
}

@Composable
private fun MangaAndroidLoading(contentPadding: PaddingValues) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .placeholder(true),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .placeholder(true),
        )
    }
}
