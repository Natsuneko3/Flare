package dev.dimension.flare.ui.screen.manga

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
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
import compose.icons.fontawesomeicons.solid.ChevronDown
import compose.icons.fontawesomeicons.solid.ChevronUp
import compose.icons.fontawesomeicons.solid.EllipsisVertical
import compose.icons.fontawesomeicons.solid.Lock
import compose.icons.fontawesomeicons.solid.ShareNodes
import dev.dimension.flare.LocalWindowPadding
import dev.dimension.flare.Res
import dev.dimension.flare.cancel
import dev.dimension.flare.collapsed
import dev.dimension.flare.common.PagingState
import dev.dimension.flare.common.isRefreshing
import dev.dimension.flare.common.onEmpty
import dev.dimension.flare.common.onError
import dev.dimension.flare.common.onLoading
import dev.dimension.flare.common.onSuccess
import dev.dimension.flare.data.model.TimelineDisplayMode
import dev.dimension.flare.expanded
import dev.dimension.flare.manga_detail_chapters_title
import dev.dimension.flare.manga_detail_continue_chapter
import dev.dimension.flare.manga_detail_empty_chapters
import dev.dimension.flare.manga_detail_empty_recommendations
import dev.dimension.flare.manga_detail_mark_read
import dev.dimension.flare.manga_detail_mark_unread
import dev.dimension.flare.manga_detail_page_progress
import dev.dimension.flare.manga_detail_read
import dev.dimension.flare.manga_detail_sort_ascending
import dev.dimension.flare.manga_detail_sort_descending
import dev.dimension.flare.manga_detail_start_chapter
import dev.dimension.flare.manga_detail_tab_comments
import dev.dimension.flare.manga_detail_tab_recommendations
import dev.dimension.flare.manga_detail_view_more
import dev.dimension.flare.manga_locked_chapter_message
import dev.dimension.flare.manga_locked_chapter_title
import dev.dimension.flare.manga_open_in_browser
import dev.dimension.flare.manga_status_cancelled
import dev.dimension.flare.manga_status_completed
import dev.dimension.flare.manga_status_hiatus
import dev.dimension.flare.manga_status_ongoing
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.more
import dev.dimension.flare.rss_detail_open_in_browser
import dev.dimension.flare.status_share
import dev.dimension.flare.ui.component.AvatarComponent
import dev.dimension.flare.ui.component.DateTimeText
import dev.dimension.flare.ui.component.ErrorContent
import dev.dimension.flare.ui.component.FAIcon
import dev.dimension.flare.ui.component.FlareScrollBar
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.component.NetworkImage
import dev.dimension.flare.ui.component.RichText
import dev.dimension.flare.ui.component.TranslationButton
import dev.dimension.flare.ui.component.TranslationRetryButton
import dev.dimension.flare.ui.component.manga.MangaActionsMenu
import dev.dimension.flare.ui.component.manga.MangaTagGroupsSection
import dev.dimension.flare.ui.component.placeholder
import dev.dimension.flare.ui.component.resolveText
import dev.dimension.flare.ui.component.status.AdaptiveCard
import dev.dimension.flare.ui.component.status.GalleryTimelineItem
import dev.dimension.flare.ui.component.status.LazyStatusVerticalStaggeredGrid
import dev.dimension.flare.ui.component.status.StatusActionButton
import dev.dimension.flare.ui.component.status.StatusItem
import dev.dimension.flare.ui.component.status.appendStateUI
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
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Button
import io.github.composefluent.component.FluentDialog
import io.github.composefluent.component.LiteFilter
import io.github.composefluent.component.MenuFlyoutContainer
import io.github.composefluent.component.MenuFlyoutItem
import io.github.composefluent.component.PillButton
import io.github.composefluent.component.ProgressBar
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text
import io.github.composefluent.surface.Card
import moe.tlaster.precompose.molecule.producePresenter
import org.jetbrains.compose.resources.stringResource

private val MangaGridSpacing = 8.dp
private val MangaMetaPaneWidth = 380.dp

private enum class DesktopMangaTab {
    Chapters,
    Comments,
    Recommendations,
}

@Composable
internal fun MangaDetailScreen(
    mangaKey: MicroBlogKey,
    accountType: AccountType,
    navigate: (Route) -> Unit,
) {
    var refreshKey by remember(accountType, mangaKey) { mutableIntStateOf(0) }
    val state by producePresenter("desktop_manga_detail_$accountType-$mangaKey-$refreshKey") {
        remember(accountType, mangaKey, refreshKey) {
            MangaDetailPresenter(accountType = accountType, mangaKey = mangaKey)
        }.invoke()
    }
    MangaDetailContent(
        state = state,
        navigate = navigate,
        onRetry = { refreshKey++ },
    )
}

@Composable
internal fun MangaCommentsScreen(
    mangaKey: MicroBlogKey,
    accountType: AccountType,
) {
    val state by producePresenter("desktop_manga_comments_$accountType-$mangaKey") {
        remember(accountType, mangaKey) {
            MangaDetailPresenter(accountType = accountType, mangaKey = mangaKey)
        }.invoke()
    }
    val listState = rememberLazyStaggeredGridState()
    MangaCardTimeline {
        Box(Modifier.fillMaxSize()) {
            FlareScrollBar(listState) {
                LazyStatusVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(1),
                    contentPadding = LocalWindowPadding.current,
                    state = listState,
                    forceCardMode = true,
                    cardGridPadding = screenHorizontalPadding,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    status(state.comments)
                }
            }
            if (state.comments.isRefreshing) {
                ProgressBar(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
internal fun MangaDetailContent(
    state: MangaDetailPresenter.State,
    navigate: (Route) -> Unit,
    onRetry: () -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    var lockedPromptChapter by remember { mutableStateOf<UiMangaChapter?>(null) }

    MangaCardTimeline {
        state.detail
            .onSuccess { detail ->
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    if (maxWidth < 720.dp) {
                        CompactMangaDetailContent(
                            detail = detail,
                            state = state,
                            navigate = navigate,
                            onLockedChapterClick = { lockedPromptChapter = it },
                        )
                    } else {
                        WideMangaDetailContent(
                            detail = detail,
                            state = state,
                            navigate = navigate,
                            onLockedChapterClick = { lockedPromptChapter = it },
                        )
                    }
                }
                lockedPromptChapter?.let { lockedChapter ->
                    val openUrl = lockedChapter.openUrl(detail.url)
                    FluentDialog(visible = true) {
                        CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                            Column(
                                modifier =
                                    Modifier
                                        .widthIn(min = 320.dp, max = 440.dp)
                                        .padding(24.dp),
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
                                            modifier = Modifier.testTag("manga_locked_chapter_confirm"),
                                        ) {
                                            Text(stringResource(Res.string.manga_open_in_browser))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }.onLoading {
                MangaDetailLoading()
            }.onError { error ->
                ErrorContent(
                    error = error,
                    onRetry = onRetry,
                    modifier = Modifier.fillMaxSize(),
                )
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
private fun WideMangaDetailContent(
    detail: UiMangaDetail,
    state: MangaDetailPresenter.State,
    navigate: (Route) -> Unit,
    onLockedChapterClick: (UiMangaChapter) -> Unit,
) {
    val availableTabs =
        remember(state.supportsComments, state.supportsRecommendations) {
            buildList {
                add(DesktopMangaTab.Chapters)
                if (state.supportsComments) add(DesktopMangaTab.Comments)
                if (state.supportsRecommendations) add(DesktopMangaTab.Recommendations)
            }
        }
    var selectedTab by remember(availableTabs) { mutableStateOf(DesktopMangaTab.Chapters) }
    val metaListState = rememberLazyListState()

    Row(Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .width(MangaMetaPaneWidth)
                    .fillMaxHeight()
                    .background(FluentTheme.colors.background.layer.default),
        ) {
            FlareScrollBar(metaListState) {
                LazyColumn(
                    state = metaListState,
                    contentPadding =
                        PaddingValues(
                            start = 16.dp,
                            top = LocalWindowPadding.current.calculateTopPadding() + 12.dp,
                            end = 16.dp,
                            bottom = LocalWindowPadding.current.calculateBottomPadding() + 24.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "hero") {
                        MangaHeroCard(
                            detail = detail,
                            translation = state.translation,
                            primaryReadTarget = state.primaryReadTarget,
                            navigate = navigate,
                        )
                    }
                    if (detail.content != null || detail.tagGroups.any { it.tags.isNotEmpty() }) {
                        item(key = "synopsis_tags") {
                            MangaSynopsisAndTagsCard(detail = detail)
                        }
                    }
                }
            }
        }

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(
                        top = LocalWindowPadding.current.calculateTopPadding(),
                        bottom = LocalWindowPadding.current.calculateBottomPadding(),
                    ),
        ) {
            if (availableTabs.size > 1) {
                LiteFilter(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    availableTabs.forEach { tab ->
                        PillButton(
                            selected = selectedTab == tab,
                            onSelectedChanged = { if (it) selectedTab = tab },
                        ) {
                            Text(
                                when (tab) {
                                    DesktopMangaTab.Chapters -> stringResource(Res.string.manga_detail_chapters_title)
                                    DesktopMangaTab.Comments -> stringResource(Res.string.manga_detail_tab_comments)
                                    DesktopMangaTab.Recommendations -> stringResource(Res.string.manga_detail_tab_recommendations)
                                },
                            )
                        }
                    }
                }
            }

            when (selectedTab) {
                DesktopMangaTab.Chapters -> {
                    val chapterListState = rememberLazyListState()
                    FlareScrollBar(chapterListState, modifier = Modifier.weight(1f)) {
                        LazyColumn(
                            state = chapterListState,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            item(key = "chapters_header") {
                                MangaChaptersHeader(
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
                                        text = stringResource(Res.string.manga_detail_empty_chapters),
                                        style = FluentTheme.typography.body,
                                        color = FluentTheme.colors.text.text.secondary,
                                        modifier = Modifier.padding(vertical = 24.dp),
                                    )
                                }
                            } else {
                                items(
                                    items = state.chapters,
                                    key = { it.chapter.id },
                                ) { item ->
                                    MangaChapterRow(
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
                }

                DesktopMangaTab.Comments -> {
                    val commentsState = rememberLazyStaggeredGridState()
                    FlareScrollBar(commentsState, modifier = Modifier.weight(1f)) {
                        LazyStatusVerticalStaggeredGrid(
                            columns = StaggeredGridCells.Fixed(1),
                            contentPadding = PaddingValues(vertical = 8.dp),
                            state = commentsState,
                            forceCardMode = true,
                            cardGridPadding = screenHorizontalPadding,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            status(state.comments)
                        }
                    }
                }

                DesktopMangaTab.Recommendations -> {
                    val recGridState = rememberLazyStaggeredGridState()
                    FlareScrollBar(recGridState, modifier = Modifier.weight(1f)) {
                        LazyVerticalStaggeredGrid(
                            columns = StaggeredGridCells.Adaptive(148.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(MangaGridSpacing),
                            verticalItemSpacing = MangaGridSpacing,
                            state = recGridState,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            mangaRecommendationItems(state.recommendations)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactMangaDetailContent(
    detail: UiMangaDetail,
    state: MangaDetailPresenter.State,
    navigate: (Route) -> Unit,
    onLockedChapterClick: (UiMangaChapter) -> Unit,
) {
    val gridState = rememberLazyStaggeredGridState()
    FlareScrollBar(gridState) {
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(164.dp),
            state = gridState,
            contentPadding =
                PaddingValues(
                    start = screenHorizontalPadding,
                    top = LocalWindowPadding.current.calculateTopPadding() + 12.dp,
                    end = screenHorizontalPadding,
                    bottom = LocalWindowPadding.current.calculateBottomPadding() + 24.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(MangaGridSpacing),
            verticalItemSpacing = 8.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = StaggeredGridItemSpan.FullLine, key = "hero") {
                MangaHeroCard(
                    detail = detail,
                    translation = state.translation,
                    primaryReadTarget = state.primaryReadTarget,
                    navigate = navigate,
                )
            }
            if (detail.content != null || detail.tagGroups.any { it.tags.isNotEmpty() }) {
                item(span = StaggeredGridItemSpan.FullLine, key = "synopsis_tags") {
                    MangaSynopsisAndTagsCard(detail = detail)
                }
            }
            item(span = StaggeredGridItemSpan.FullLine, key = "chapters_header") {
                MangaChaptersHeader(
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
                        text = stringResource(Res.string.manga_detail_empty_chapters),
                        style = FluentTheme.typography.body,
                        color = FluentTheme.colors.text.text.secondary,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            } else {
                items(
                    items = state.chapters,
                    key = { "chapter_${it.chapter.id}" },
                    span = { StaggeredGridItemSpan.FullLine },
                ) { item ->
                    MangaChapterRow(
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
                compactMangaCommentsItems(
                    mangaKey = detail.mangaKey,
                    accountType = detail.accountType,
                    comments = state.comments,
                    navigate = navigate,
                )
            }
            if (state.supportsRecommendations) {
                item(span = StaggeredGridItemSpan.FullLine, key = "recommendations_header") {
                    Text(
                        text = stringResource(Res.string.manga_detail_tab_recommendations),
                        style = FluentTheme.typography.bodyStrong,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                mangaRecommendationItems(state.recommendations)
            }
        }
    }
}

@Composable
private fun MangaHeroCard(
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
    Card(modifier = Modifier.fillMaxWidth()) {
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
                                .width(112.dp)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    navigate(
                                        Route.RawImage(
                                            rawImage = cover.url,
                                            customHeaders = cover.customHeaders,
                                            proxy = cover.proxy,
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
                        style = FluentTheme.typography.subtitle,
                        fontWeight = FontWeight.SemiBold,
                    )
                    detail.altTitles.firstOrNull { it.isNotBlank() }?.let { alt ->
                        Text(
                            text = alt,
                            style = FluentTheme.typography.caption,
                            color = FluentTheme.colors.text.text.secondary,
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
                                    navigate(Route.Profile(detail.accountType, authorProfile.key))
                                },
                        ) {
                            AvatarComponent(data = authorProfile.avatar, size = 24.dp)
                            RichText(
                                text = authorProfile.name,
                                textStyle = FluentTheme.typography.caption,
                                color = FluentTheme.colors.text.text.secondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else if (authorNames.isNotBlank()) {
                        Text(
                            text = authorNames,
                            style = FluentTheme.typography.caption,
                            color = FluentTheme.colors.text.text.secondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        detail.status?.let { status ->
                            MangaStatusBadge(status = status)
                        }
                        detail.updatedAt?.let { updated ->
                            DateTimeText(
                                data = updated,
                                style = FluentTheme.typography.caption,
                                color = FluentTheme.colors.text.text.secondary,
                            )
                        }
                    }
                }
            }

            if (translation?.isTranslating == true) {
                ProgressBar(modifier = Modifier.fillMaxWidth())
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
                    AccentButton(
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
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text =
                                when {
                                    target.isContinue -> stringResource(Res.string.manga_detail_continue_chapter, target.chapterTitle)
                                    target.isSingleChapterWork -> stringResource(Res.string.manga_detail_read)
                                    else -> stringResource(Res.string.manga_detail_start_chapter, target.chapterTitle)
                                },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } ?: Spacer(Modifier.weight(1f))

                translation?.let {
                    TranslationButton(
                        showTranslation = it.showTranslation,
                        onClick = it::toggleTranslation,
                    )
                }

                if (detail.actions.isEmpty() && (detail.bookmarkAction != ClickEvent.Noop || detail.isBookmarked)) {
                    StatusActionButton(
                        icon =
                            if (detail.isBookmarked) {
                                UiIcon.Unlike.toImageVector()
                            } else {
                                UiIcon.Like.toImageVector()
                            },
                        number = null,
                        enabled = detail.bookmarkAction != ClickEvent.Noop,
                        color =
                            if (detail.isBookmarked) {
                                FluentTheme.colors.system.critical
                            } else {
                                FluentTheme.colors.text.text.secondary
                            },
                        onClicked = {
                            detail.onBookmark.invoke(ClickContext(uriHandler::openUri))
                        },
                        modifier = Modifier.testTag("manga_cta_bookmark_fallback"),
                    )
                }

                detail.url?.takeIf { it.isNotBlank() }?.let { shareUrl ->
                    SubtleButton(
                        onClick = {
                            uriHandler.openUri(DeeplinkRoute.OpenLinkDirectly(shareUrl).toUri())
                        },
                        iconOnly = true,
                    ) {
                        FAIcon(
                            imageVector = FontAwesomeIcons.Brands.Chrome,
                            contentDescription = stringResource(Res.string.rss_detail_open_in_browser),
                        )
                    }
                    SubtleButton(
                        onClick = {
                            navigate(
                                Route.StatusShareSheet(
                                    accountType = detail.accountType,
                                    statusKey = detail.mangaKey,
                                    shareUrl = shareUrl,
                                ),
                            )
                        },
                        iconOnly = true,
                    ) {
                        FAIcon(
                            imageVector = FontAwesomeIcons.Solid.ShareNodes,
                            contentDescription = stringResource(Res.string.status_share),
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
private fun MangaStatusBadge(status: UiMangaStatus) {
    val label =
        stringResource(
            when (status) {
                UiMangaStatus.Ongoing -> Res.string.manga_status_ongoing
                UiMangaStatus.Completed -> Res.string.manga_status_completed
                UiMangaStatus.Hiatus -> Res.string.manga_status_hiatus
                UiMangaStatus.Cancelled -> Res.string.manga_status_cancelled
            },
        )
    val badgeColor = FluentTheme.colors.fillAccent.default
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(badgeColor.copy(alpha = 0.15f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = FluentTheme.typography.caption,
            color = FluentTheme.colors.text.accent.primary,
        )
    }
}

@Composable
private fun MangaSynopsisAndTagsCard(detail: UiMangaDetail) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            detail.content?.takeIf { !it.isEmpty }?.let { content ->
                MangaDesktopCollapsibleSynopsis(content = content)
            }
            MangaTagGroupsSection(
                tagGroups = detail.tagGroups,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MangaDesktopCollapsibleSynopsis(content: UiRichText) {
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
            textStyle = FluentTheme.typography.body,
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
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { expanded = !expanded }
                        .padding(vertical = 4.dp)
                        .testTag("manga_synopsis_toggle"),
                contentAlignment = Alignment.Center,
            ) {
                FAIcon(
                    imageVector = if (expanded) FontAwesomeIcons.Solid.ChevronUp else FontAwesomeIcons.Solid.ChevronDown,
                    contentDescription = stringResource(if (expanded) Res.string.expanded else Res.string.collapsed),
                    tint = FluentTheme.colors.text.text.secondary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MangaChaptersHeader(
    detail: UiMangaDetail,
    selectedGroupId: String?,
    chapterSortDescending: Boolean,
    chapterCount: Int,
    onSelectGroup: (String) -> Unit,
    onToggleSort: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${stringResource(Res.string.manga_detail_chapters_title)} ($chapterCount)",
                style = FluentTheme.typography.bodyStrong,
            )
            SubtleButton(
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
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text =
                        stringResource(
                            if (chapterSortDescending) {
                                Res.string.manga_detail_sort_descending
                            } else {
                                Res.string.manga_detail_sort_ascending
                            },
                        ),
                )
            }
        }

        if (detail.chapterGroups.size > 1 || detail.chapterGroups.any { it.title != null }) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                detail.chapterGroups.forEach { group ->
                    val label = group.title?.resolveText()?.takeIf { it.isNotBlank() } ?: group.id
                    PillButton(
                        selected = group.id == selectedGroupId,
                        onSelectedChanged = { selected ->
                            if (selected) {
                                onSelectGroup(group.id)
                            }
                        },
                        modifier = Modifier.testTag("manga_chapter_group_${group.id}"),
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }
}

@Composable
private fun MangaChapterRow(
    item: UiMangaChapterItem,
    onClick: () -> Unit,
    onSetRead: (Boolean) -> Unit,
) {
    val chapter = item.chapter
    val titleColor =
        when {
            item.isCurrent -> FluentTheme.colors.text.accent.primary
            item.isRead || chapter.locked -> FluentTheme.colors.text.text.secondary
            else -> FluentTheme.colors.text.text.primary
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("manga_chapter_row_${chapter.id}"),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .clickable(onClick = onClick)
                        .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (chapter.locked) {
                    FAIcon(
                        imageVector = FontAwesomeIcons.Solid.Lock,
                        contentDescription = stringResource(Res.string.manga_locked_chapter_title),
                        modifier = Modifier.size(14.dp),
                        tint = FluentTheme.colors.text.text.secondary,
                    )
                } else if (item.isRead) {
                    FAIcon(
                        imageVector = FontAwesomeIcons.Solid.Check,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = FluentTheme.colors.text.text.secondary,
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = chapter.title,
                        style = if (item.isCurrent) FluentTheme.typography.bodyStrong else FluentTheme.typography.body,
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
                                style = FluentTheme.typography.caption,
                                color = FluentTheme.colors.text.text.secondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!item.isRead && item.lastPageIndex > 0 && item.pageCount > 0) {
                            Text(
                                text = stringResource(Res.string.manga_detail_page_progress, item.lastPageIndex + 1, item.pageCount),
                                style = FluentTheme.typography.caption,
                                color = FluentTheme.colors.text.accent.primary,
                            )
                        }
                        chapter.uploadedAt?.let { uploaded ->
                            DateTimeText(
                                data = uploaded,
                                style = FluentTheme.typography.caption,
                                color = FluentTheme.colors.text.text.secondary,
                            )
                        }
                    }
                }
            }

            MenuFlyoutContainer(
                flyout = {
                    MenuFlyoutItem(
                        text = {
                            Text(
                                stringResource(
                                    if (item.isRead) {
                                        Res.string.manga_detail_mark_unread
                                    } else {
                                        Res.string.manga_detail_mark_read
                                    },
                                ),
                            )
                        },
                        onClick = {
                            isFlyoutVisible = false
                            onSetRead(!item.isRead)
                        },
                    )
                },
            ) {
                SubtleButton(
                    onClick = { isFlyoutVisible = true },
                    iconOnly = true,
                    modifier = Modifier.testTag("manga_chapter_menu_${chapter.id}"),
                ) {
                    FAIcon(
                        imageVector = FontAwesomeIcons.Solid.EllipsisVertical,
                        contentDescription = stringResource(Res.string.more),
                    )
                }
            }
        }
    }
}

private fun LazyStaggeredGridScope.compactMangaCommentsItems(
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
                        text = stringResource(Res.string.manga_detail_tab_comments),
                        style = FluentTheme.typography.bodyStrong,
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
                    SubtleButton(
                        onClick = {
                            navigate(Route.Manga.Comments(mangaKey = mangaKey, accountType = accountType))
                        },
                    ) {
                        Text(stringResource(Res.string.manga_detail_view_more))
                    }
                }
            }
        }
        onLoading {
            item(span = StaggeredGridItemSpan.FullLine) {
                Text(
                    text = stringResource(Res.string.manga_detail_tab_comments),
                    style = FluentTheme.typography.bodyStrong,
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

private fun LazyStaggeredGridScope.mangaRecommendationItems(recommendations: PagingState<UiTimelineV2>) {
    with(recommendations) {
        onSuccess {
            items(
                count = itemCount,
                key = itemKey { it.itemKey ?: it.hashCode() },
                contentType = itemContentType { it.itemType },
            ) { index ->
                GalleryTimelineItem(item = get(index))
            }
            appendStateUI(this)
        }
        onLoading {
            items(6) {
                GalleryTimelineItem(item = null)
            }
        }
        onEmpty {
            item(span = StaggeredGridItemSpan.FullLine) {
                Text(
                    text = stringResource(Res.string.manga_detail_empty_recommendations),
                    style = FluentTheme.typography.body,
                    color = FluentTheme.colors.text.text.secondary,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        onError {
            item(span = StaggeredGridItemSpan.FullLine) {
                ErrorContent(error = it, onRetry = onRetry)
            }
        }
    }
}

@Composable
private fun MangaDetailLoading() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
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
