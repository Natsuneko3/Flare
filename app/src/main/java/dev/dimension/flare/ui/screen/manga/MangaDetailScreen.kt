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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Brands
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.brands.Chrome
import compose.icons.fontawesomeicons.solid.ArrowDownWideShort
import compose.icons.fontawesomeicons.solid.ArrowUpWideShort
import compose.icons.fontawesomeicons.solid.BookOpen
import compose.icons.fontawesomeicons.solid.Check
import compose.icons.fontawesomeicons.solid.EllipsisVertical
import compose.icons.fontawesomeicons.solid.Images
import compose.icons.fontawesomeicons.solid.Lock
import compose.icons.fontawesomeicons.solid.ShareNodes
import dev.dimension.flare.R
import dev.dimension.flare.common.PagingState
import dev.dimension.flare.common.onEmpty
import dev.dimension.flare.common.onError
import dev.dimension.flare.common.onLoading
import dev.dimension.flare.common.onSuccess
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
import dev.dimension.flare.ui.component.status.StatusActionButton
import dev.dimension.flare.ui.component.status.StatusItem
import dev.dimension.flare.ui.component.status.status
import dev.dimension.flare.ui.component.toImageVector
import dev.dimension.flare.ui.model.ClickContext
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
import dev.dimension.flare.ui.route.DeeplinkRoute
import dev.dimension.flare.ui.route.Route
import dev.dimension.flare.ui.route.toUri
import dev.dimension.flare.ui.theme.screenHorizontalPadding
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import moe.tlaster.precompose.molecule.producePresenter

private val MangaGridSpacing = 8.dp
private val MangaMetaPaneWidth = 380.dp

private enum class AndroidMangaTab {
    Chapters,
    Comments,
    Recommendations,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MangaDetailScreen(
    mangaKey: MicroBlogKey,
    accountType: AccountType,
    navigate: (Route) -> Unit,
    onBack: () -> Unit,
) {
    var refreshKey by remember(accountType, mangaKey) { mutableIntStateOf(0) }
    val state by producePresenter("manga_detail_$accountType-$mangaKey-$refreshKey") {
        remember(accountType, mangaKey, refreshKey) {
            MangaDetailPresenter(accountType = accountType, mangaKey = mangaKey)
        }.invoke()
    }
    MangaDetailContent(
        state = state,
        navigate = navigate,
        onBack = onBack,
        onRetry = { refreshKey++ },
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
    onRetry: () -> Unit = {},
) {
    val isWideScreen = isBigScreen()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val uriHandler = LocalUriHandler.current
    var lockedPromptChapter by remember { mutableStateOf<UiMangaChapter?>(null) }

    val loadedDetail = state.detail.takeSuccess()
    val shareUrl = loadedDetail?.url?.takeIf { it.isNotBlank() }

    MangaCardTimeline {
        FlareScaffold(
            topBar = {
                FlareTopAppBar(
                    title = {
                        Text(
                            text = loadedDetail?.title.orEmpty(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        BackButton(onBack = onBack)
                    },
                    actions = {
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
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            contentWindowInsets = WindowInsets(),
            containerColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        ) { contentPadding ->
            state.detail
                .onSuccess { detail ->
                    if (isWideScreen) {
                        WideAndroidMangaDetailContent(
                            detail = detail,
                            state = state,
                            contentPadding = contentPadding,
                            navigate = navigate,
                            onLockedChapterClick = { lockedPromptChapter = it },
                        )
                    } else {
                        CompactAndroidMangaDetailContent(
                            detail = detail,
                            state = state,
                            contentPadding = contentPadding,
                            navigate = navigate,
                            onLockedChapterClick = { lockedPromptChapter = it },
                        )
                    }
                    lockedPromptChapter?.let { lockedChapter ->
                        val openUrl =
                            lockedChapter.url?.takeIf { it.isNotBlank() }
                                ?: detail.url?.takeIf { it.isNotBlank() }
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
    contentPadding: PaddingValues,
    navigate: (Route) -> Unit,
    onLockedChapterClick: (UiMangaChapter) -> Unit,
) {
    val availableTabs =
        remember(state.supportsComments, state.supportsRecommendations) {
            buildList {
                add(AndroidMangaTab.Chapters)
                if (state.supportsComments) add(AndroidMangaTab.Comments)
                if (state.supportsRecommendations) add(AndroidMangaTab.Recommendations)
            }
        }
    val pagerState = rememberPagerState(pageCount = { availableTabs.size })
    val scope = rememberCoroutineScope()

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
                    .fillMaxHeight()
                    .systemBarsPadding(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
        ) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "hero") {
                    MangaAndroidHeroCard(
                        detail = detail,
                        translation = state.translation,
                        primaryReadTarget = state.primaryReadTarget,
                        navigate = navigate,
                    )
                }
                if (detail.content != null || detail.tagGroups.any { it.tags.isNotEmpty() }) {
                    item(key = "synopsis_tags") {
                        MangaAndroidSynopsisAndTagsCard(detail = detail)
                    }
                }
            }
        }

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
        ) {
            if (availableTabs.size > 1) {
                SecondaryTabRow(selectedTabIndex = pagerState.currentPage) {
                    availableTabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = {
                                Text(
                                    stringResource(
                                        when (tab) {
                                            AndroidMangaTab.Chapters -> R.string.manga_detail_chapters_title
                                            AndroidMangaTab.Comments -> R.string.manga_detail_tab_comments
                                            AndroidMangaTab.Recommendations -> R.string.manga_detail_tab_recommendations
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                when (availableTabs[page]) {
                    AndroidMangaTab.Chapters -> {
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            item(key = "chapters_header") {
                                MangaAndroidChaptersHeader(
                                    detail = detail,
                                    selectedGroupId = state.selectedGroupId,
                                    chapterSortDescending = state.chapterSortDescending,
                                    chapterCount = state.chapters.size,
                                    onSelectGroup = state::selectGroup,
                                    onToggleSort = state::toggleChapterSort,
                                )
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
                                items(
                                    items = state.chapters,
                                    key = { it.chapter.id },
                                ) { item ->
                                    MangaAndroidChapterRow(
                                        item = item,
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

                    AndroidMangaTab.Comments -> {
                        LazyStatusVerticalStaggeredGrid(
                            columns = StaggeredGridCells.Fixed(1),
                            contentPadding = PaddingValues(vertical = 8.dp),
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
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
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
}

@Composable
private fun CompactAndroidMangaDetailContent(
    detail: UiMangaDetail,
    state: MangaDetailPresenter.State,
    contentPadding: PaddingValues,
    navigate: (Route) -> Unit,
    onLockedChapterClick: (UiMangaChapter) -> Unit,
) {
    val gridState = rememberLazyStaggeredGridState()
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(164.dp),
        state = gridState,
        contentPadding =
            PaddingValues(
                start = screenHorizontalPadding,
                top = contentPadding.calculateTopPadding() + 8.dp,
                end = screenHorizontalPadding,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(MangaGridSpacing),
        verticalItemSpacing = 8.dp,
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = StaggeredGridItemSpan.FullLine, key = "hero") {
            MangaAndroidHeroCard(
                detail = detail,
                translation = state.translation,
                primaryReadTarget = state.primaryReadTarget,
                navigate = navigate,
            )
        }
        if (detail.content != null || detail.tagGroups.any { it.tags.isNotEmpty() }) {
            item(span = StaggeredGridItemSpan.FullLine, key = "synopsis_tags") {
                MangaAndroidSynopsisAndTagsCard(detail = detail)
            }
        }
        item(span = StaggeredGridItemSpan.FullLine, key = "chapters_header") {
            MangaAndroidChaptersHeader(
                detail = detail,
                selectedGroupId = state.selectedGroupId,
                chapterSortDescending = state.chapterSortDescending,
                chapterCount = state.chapters.size,
                onSelectGroup = state::selectGroup,
                onToggleSort = state::toggleChapterSort,
            )
        }
        if (state.chapters.isEmpty()) {
            item(span = StaggeredGridItemSpan.FullLine, key = "chapters_empty") {
                Text(
                    text = stringResource(R.string.manga_detail_empty_chapters),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        } else {
            items(
                items = state.chapters,
                key = { "chapter_${it.chapter.id}" },
                span = { StaggeredGridItemSpan.FullLine },
            ) { item ->
                MangaAndroidChapterRow(
                    item = item,
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
        if (state.supportsComments) {
            compactAndroidMangaCommentsItems(
                mangaKey = detail.mangaKey,
                accountType = detail.accountType,
                comments = state.comments,
                navigate = navigate,
            )
        }
        if (state.supportsRecommendations) {
            item(span = StaggeredGridItemSpan.FullLine, key = "recommendations_title") {
                Text(
                    text = stringResource(R.string.manga_detail_tab_recommendations),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            state.recommendations.onEmpty {
                item(span = StaggeredGridItemSpan.FullLine) {
                    ListEmptyView(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                        text = stringResource(R.string.manga_detail_empty_recommendations),
                        icon = FontAwesomeIcons.Solid.Images,
                    )
                }
            }
            mangaAndroidRecommendationItems(state.recommendations)
        }
    }
}

@Composable
private fun MangaAndroidHeroCard(
    detail: UiMangaDetail,
    translation: MangaTranslatePresenter.State?,
    primaryReadTarget: PrimaryReadTarget?,
    navigate: (Route) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val authorProfile = detail.author
    val authorNames =
        remember(detail.authors, detail.artists) {
            (detail.authors + detail.artists).distinct().joinToString(" · ")
        }
    AdaptiveCard(
        modifier = Modifier.fillMaxWidth(),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                detail.cover?.let { cover ->
                    NetworkImage(
                        model = cover,
                        contentDescription = detail.title,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .width(108.dp)
                                .aspectRatio(2f / 3f)
                                .clip(MaterialTheme.shapes.medium)
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
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = detail.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
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

                StatusActionButton(
                    icon =
                        if (detail.isBookmarked) {
                            UiIcon.Unlike.toImageVector()
                        } else {
                            UiIcon.Like.toImageVector()
                        },
                    number = null,
                    color =
                        if (detail.isBookmarked) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    onClicked = {
                        detail.onBookmark.invoke(ClickContext(uriHandler::openUri))
                    },
                )
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
        modifier = Modifier.fillMaxWidth(),
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
            detail.content?.let { content ->
                RichText(
                    text = content,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            MangaTagGroupsSection(
                tagGroups = detail.tagGroups,
                modifier = Modifier.fillMaxWidth(),
            )
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
        index = 0,
        totalCount = 1,
    ) {
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

private fun LazyStaggeredGridScope.compactAndroidMangaCommentsItems(
    mangaKey: MicroBlogKey,
    accountType: AccountType,
    comments: PagingState<UiTimelineV2>,
    navigate: (Route) -> Unit,
) {
    with(comments) {
        onSuccess {
            val visibleCount = minOf(itemCount, 3)
            if (visibleCount > 0) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    Text(
                        text = stringResource(R.string.manga_detail_tab_comments),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                repeat(visibleCount) { index ->
                    item(span = StaggeredGridItemSpan.FullLine) {
                        AdaptiveCard(
                            index = index,
                            totalCount = visibleCount,
                            respectTimelineMode = true,
                        ) {
                            StatusItem(get(index))
                        }
                    }
                }
            }
            if (
                visibleCount > 0 &&
                (
                    itemCount > 3 ||
                        (
                            appendState is LoadState.NotLoading &&
                                !(appendState as LoadState.NotLoading).endOfPaginationReached
                        )
                )
            ) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    Button(
                        onClick = {
                            navigate(Route.Manga.Comments(mangaKey = mangaKey, accountType = accountType))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.manga_detail_view_more))
                    }
                }
            }
        }
        onLoading {
            item(span = StaggeredGridItemSpan.FullLine) {
                Text(
                    text = stringResource(R.string.manga_detail_tab_comments),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            repeat(3) { index ->
                item(span = StaggeredGridItemSpan.FullLine) {
                    AdaptiveCard(
                        index = index,
                        totalCount = 3,
                        respectTimelineMode = true,
                    ) {
                        StatusItem(null)
                    }
                }
            }
        }
        onEmpty {}
        onError { error ->
            item(span = StaggeredGridItemSpan.FullLine) {
                ErrorContent(error = error, onRetry = onRetry)
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
