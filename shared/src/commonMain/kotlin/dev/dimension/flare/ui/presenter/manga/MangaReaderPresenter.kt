package dev.dimension.flare.ui.presenter.manga

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.paging.LoadState
import dev.dimension.flare.common.CacheState
import dev.dimension.flare.data.datasource.microblog.capabilities
import dev.dimension.flare.data.datasource.microblog.datasource.MangaDataSource
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.repository.AccountService
import dev.dimension.flare.data.repository.MangaProgressRepository
import dev.dimension.flare.data.repository.MangaProgressSnapshot
import dev.dimension.flare.di.koinInject
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.MangaReadingDirection
import dev.dimension.flare.ui.model.UiMangaChapter
import dev.dimension.flare.ui.model.UiMangaChapterGroup
import dev.dimension.flare.ui.model.UiMangaDetail
import dev.dimension.flare.ui.model.UiMangaPage
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.flatMapLatestUiState
import dev.dimension.flare.ui.model.flattenUiState
import dev.dimension.flare.ui.model.map
import dev.dimension.flare.ui.model.takeSuccess
import dev.dimension.flare.ui.model.toUi
import dev.dimension.flare.ui.model.withProxy
import dev.dimension.flare.ui.presenter.PresenterBase
import dev.dimension.flare.ui.presenter.media.ImageTranslationPresenter
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.CoroutineContext

@Immutable
public data class ReaderPageState(
    public val id: String,
    public val index: Int,
    public val status: PageMediaStatus,
    public val aspectRatio: Float?,
)

@Immutable
public data class ReaderChapterItem(
    public val chapter: UiMangaChapter,
    public val isCurrent: Boolean,
    public val isRead: Boolean,
    public val lastPageIndex: Int,
)

@Immutable
public sealed interface PageMediaStatus {
    @Immutable
    public data class Loading(
        public val previewUrl: String?,
        public val customHeaders: ImmutableMap<String, String>?,
        public val proxy: ProxyRef,
    ) : PageMediaStatus

    @Immutable
    public data class Ready(
        public val original: UiMedia,
        public val displayed: UiMedia,
    ) : PageMediaStatus

    @Immutable
    public data class Failed(
        public val error: Throwable,
    ) : PageMediaStatus
}

@Immutable
public sealed interface ChapterBoundary {
    @Immutable
    public data object None : ChapterBoundary

    @Immutable
    public data class Unlocked(
        public val chapter: UiMangaChapter,
        public val startFromEnd: Boolean,
    ) : ChapterBoundary

    @Immutable
    public data class Locked(
        public val chapter: UiMangaChapter,
        public val openUrl: String,
    ) : ChapterBoundary
}

public class LockedChapterException(
    public val chapter: UiMangaChapter,
    public val openUrl: String,
) : IllegalStateException("Chapter ${chapter.id} is locked")

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
public class MangaReaderPresenter(
    private val accountType: AccountType,
    private val mangaKey: MicroBlogKey,
    private val initialChapterId: String,
    private val initialPage: Int? = null,
) : PresenterBase<MangaReaderPresenter.State>() {
    private val accountService: AccountService by koinInject()
    private val progressRepository: MangaProgressRepository by koinInject()
    private val appDataStore: AppDataStore by koinInject()
    private val writeJobs = MutableStateFlow<Set<Job>>(emptySet())

    internal suspend fun awaitPendingWrites() {
        writeJobs.value.forEach { it.join() }
    }

    private val proxyRef: ProxyRef by lazy {
        ProxyRef.forAccountType(accountType)
    }

    private val serviceFlow by lazy {
        accountService.accountServiceFlow(accountType)
    }

    private val mangaDataSourceFlow by lazy {
        serviceFlow.map { service -> service.capabilities.manga }
    }

    private fun buildDetailFlow(forceRefresh: Boolean) =
        serviceFlow.flatMapLatestUiState { service ->
            val mangaDataSource =
                service.capabilities.manga
                    ?: error("Current service does not support manga data source")
            val cacheable = mangaDataSource.mangaDetail(mangaKey)
            if (!forceRefresh) {
                cacheable
                    .toUi()
                    .map { state -> state.map { it.bindMangaProxy(proxyRef) } }
            } else {
                flow {
                    var hasObservedSuccessInAttempt = false
                    combine(cacheable.data, cacheable.refreshState) { data, refresh ->
                        if (!hasObservedSuccessInAttempt && data is CacheState.Success && refresh is LoadState.NotLoading) {
                            hasObservedSuccessInAttempt = true
                        }
                        if (hasObservedSuccessInAttempt && data is CacheState.Success) {
                            UiState.Success(data.data.bindMangaProxy(proxyRef))
                        } else {
                            when {
                                refresh is LoadState.Loading -> UiState.Loading()
                                refresh is LoadState.Error -> UiState.Error(refresh.error)
                                data is CacheState.Success -> UiState.Success(data.data.bindMangaProxy(proxyRef))
                                else -> UiState.Loading()
                            }
                        }
                    }.collect { emit(it) }
                }
            }
        }

    @Immutable
    public interface State {
        public val mangaTitle: String
        public val chapterTitle: String
        public val chapterUrl: String?
        public val activeChapterId: String
        public val loadState: UiState<Unit>
        public val pages: ImmutableList<ReaderPageState>
        public val slots: ImmutableList<ReaderSlot>
        public val currentPageIndex: Int
        public val currentSlotIndex: Int
        public val direction: MangaReadingDirection
        public val doublePageSpread: Boolean
        public val spreadCoverSinglePage: Boolean
        public val previousBoundary: ChapterBoundary
        public val nextBoundary: ChapterBoundary
        public val prefetchMedia: ImmutableList<UiMedia.Image>
        public val imageTranslation: ImageTranslationPresenter.State
        public val chapterGroups: ImmutableList<UiMangaChapterGroup> get() = persistentListOf()
        public val selectedChapterGroupId: String? get() = null
        public val chapterSortDescending: Boolean get() = true
        public val chapterItems: ImmutableList<ReaderChapterItem> get() = persistentListOf()
        public val webtoonConstrainWidthOnWideScreen: Boolean get() = true
        public val volumeKeysNavigation: Boolean get() = false

        public val lockedChapter: ChapterBoundary.Locked?
            get() =
                ((loadState as? UiState.Error)?.throwable as? LockedChapterException)?.let {
                    ChapterBoundary.Locked(chapter = it.chapter, openUrl = it.openUrl)
                }

        public fun setWideViewport(isWide: Boolean)

        public fun onPageDimensionsLoaded(
            pageId: String,
            width: Float,
            height: Float,
        )

        public fun goToPage(pageIndex: Int)

        public fun goToSlot(slotIndex: Int)

        public fun stepForward()

        public fun stepBackward()

        public fun openAdjacentChapter(boundary: ChapterBoundary.Unlocked)

        public fun retryChapter()

        public fun retryPage(pageId: String)

        public fun setDirection(direction: MangaReadingDirection)

        public fun setDoublePageSpread(enabled: Boolean)

        public fun setSpreadCoverSinglePage(enabled: Boolean)

        public fun selectChapterGroup(groupId: String) {}

        public fun toggleChapterSort() {}

        public fun selectChapter(chapterId: String) {}

        public fun openChapter(
            chapterId: String,
            initialPage: Int?,
        ) {}

        public fun resolveChapterOpenUrl(chapter: UiMangaChapter): String? = null

        public fun setWebtoonConstrainWidthOnWideScreen(enabled: Boolean) {}

        public fun setVolumeKeysNavigation(enabled: Boolean) {}
    }

    @Composable
    override fun body(): State {
        val scope = rememberCoroutineScope()
        val writeJob =
            remember {
                SupervisorJob().also { job ->
                    writeJobs.update { it + job }
                    job.invokeOnCompletion { writeJobs.update { current -> current - job } }
                }
            }
        val writeScope = remember(scope) { CoroutineScope(scope.coroutineContext.minusKey(Job) + writeJob) }
        val writeMutex = remember { Mutex() }

        var detailAttempt by remember(accountType, mangaKey) { mutableIntStateOf(0) }
        val detailFlow =
            remember(accountType, mangaKey, detailAttempt) {
                buildDetailFlow(forceRefresh = detailAttempt > 0)
            }
        val detailState by detailFlow.flattenUiState()
        val rawDetail = detailState.takeSuccess()
        val latestRawDetail by rememberUpdatedState(rawDetail)
        val currentDataSource by mangaDataSourceFlow.collectAsState(null)

        var resolvedMangaKey by remember(accountType, mangaKey) { mutableStateOf(mangaKey) }
        if (rawDetail != null && rawDetail.mangaKey != resolvedMangaKey) {
            resolvedMangaKey = rawDetail.mangaKey
        }
        val effectiveMangaKey = rawDetail?.mangaKey ?: resolvedMangaKey
        val latestEffectiveMangaKey by rememberUpdatedState(effectiveMangaKey)

        val settings by appDataStore.appSettingsStore.data.collectAsState(AppSettings.default)
        val progressFlow =
            remember(accountType, effectiveMangaKey) {
                progressRepository.observe(accountType, effectiveMangaKey)
            }
        val taggedProgress by remember(progressFlow, effectiveMangaKey) {
            progressFlow.map { effectiveMangaKey to it }
        }.collectAsState(initial = null)
        val loadedProgress = taggedProgress?.takeIf { it.first == effectiveMangaKey }?.second
        var seedProgress by remember(accountType, effectiveMangaKey) { mutableStateOf<MangaProgressSnapshot?>(null) }
        val effectiveObservedProgress = loadedProgress ?: seedProgress
        val latestLoadedProgress by rememberUpdatedState(effectiveObservedProgress)

        var optimisticDirectionOverride by remember(accountType, mangaKey) { mutableStateOf<MangaReadingDirection?>(null) }
        var pendingDirectionWrites by remember(accountType, mangaKey) { mutableIntStateOf(0) }
        LaunchedEffect(loadedProgress) {
            if (loadedProgress != null && pendingDirectionWrites == 0) {
                optimisticDirectionOverride = null
            }
        }

        var optimisticChapterSortDescending by remember(accountType, mangaKey) { mutableStateOf<Boolean?>(null) }
        var pendingSortWrites by remember(accountType, mangaKey) { mutableIntStateOf(0) }
        LaunchedEffect(loadedProgress) {
            if (loadedProgress != null && pendingSortWrites == 0) {
                optimisticChapterSortDescending = null
            }
        }

        var optimisticDoublePageSpread by remember { mutableStateOf<Boolean?>(null) }
        var optimisticSpreadCoverSinglePage by remember { mutableStateOf<Boolean?>(null) }
        var optimisticWebtoonConstrainWidthOnWideScreen by remember { mutableStateOf<Boolean?>(null) }
        var optimisticVolumeKeysNavigation by remember { mutableStateOf<Boolean?>(null) }
        var pendingSettingsWrites by remember { mutableIntStateOf(0) }
        LaunchedEffect(settings.mangaReaderConfig) {
            if (pendingSettingsWrites == 0) {
                optimisticDoublePageSpread = null
                optimisticSpreadCoverSinglePage = null
                optimisticWebtoonConstrainWidthOnWideScreen = null
                optimisticVolumeKeysNavigation = null
            }
        }

        fun clearSyncedOptimisticSettings() {
            if (pendingSettingsWrites != 0) return
            if (settings.mangaReaderConfig.doublePageSpreadOnWideScreen == optimisticDoublePageSpread) {
                optimisticDoublePageSpread = null
            }
            if (settings.mangaReaderConfig.spreadCoverSinglePage == optimisticSpreadCoverSinglePage) {
                optimisticSpreadCoverSinglePage = null
            }
            if (settings.mangaReaderConfig.webtoonConstrainWidthOnWideScreen == optimisticWebtoonConstrainWidthOnWideScreen) {
                optimisticWebtoonConstrainWidthOnWideScreen = null
            }
            if (settings.mangaReaderConfig.volumeKeysNavigation == optimisticVolumeKeysNavigation) {
                optimisticVolumeKeysNavigation = null
            }
        }

        var wideViewport by remember { mutableStateOf<Boolean?>(null) }
        var chapterTarget by remember(accountType, mangaKey) {
            mutableStateOf(
                ChapterTarget(
                    chapterId = initialChapterId,
                    attempt = 0,
                    landing = ChapterLanding.Initial(initialPage),
                ),
            )
        }
        val activeChapterId = chapterTarget.chapterId

        val chapterLocation =
            remember(rawDetail, activeChapterId) {
                rawDetail?.locateChapter(activeChapterId)
            }
        val activeChapter = chapterLocation?.chapter
        val isChapterLocked = activeChapter?.locked == true

        var selectedGroupId by remember(accountType, mangaKey) { mutableStateOf<String?>(null) }
        LaunchedEffect(activeChapterId, chapterLocation?.group?.id) {
            val activeGroupId = chapterLocation?.group?.id
            if (activeGroupId != null) {
                selectedGroupId = activeGroupId
            }
        }

        val activeSessionHolder = remember { ActiveSessionRef() }
        val session =
            if (rawDetail != null && !isChapterLocked && currentDataSource != null) {
                remember(chapterTarget, currentDataSource, effectiveMangaKey) {
                    ChapterPagesSession(
                        mangaKey = effectiveMangaKey,
                        chapterId = chapterTarget.chapterId,
                        landing = chapterTarget.landing,
                        initialPageHint = (chapterTarget.landing as? ChapterLanding.Initial)?.initialPage ?: 0,
                        parentContext = scope.coroutineContext,
                        proxyRef = proxyRef,
                    )
                }
            } else {
                null
            }
        activeSessionHolder.session = session

        DisposableEffect(session) {
            onDispose {
                session?.cancel()
            }
        }

        LaunchedEffect(session, currentDataSource, progressFlow) {
            val currentSession = session ?: return@LaunchedEffect
            val source = currentDataSource ?: return@LaunchedEffect
            if (!currentSession.hasStartedLoad) {
                currentSession.hasStartedLoad = true
                try {
                    val fetchedPages = source.chapterPages(currentSession.mangaKey, currentSession.chapterId)
                    currentCoroutineContext().ensureActive()
                    val resolvedProgress = latestLoadedProgress ?: progressFlow.first()
                    currentCoroutineContext().ensureActive()
                    val landingIndex =
                        when (val landing = currentSession.landing) {
                            is ChapterLanding.Initial -> {
                                val saved = resolvedProgress.chapters[currentSession.chapterId]
                                val target =
                                    landing.initialPage
                                        ?: if (saved != null && !saved.isCompleted) saved.lastPageIndex else 0
                                target.coerceIn(0, (fetchedPages.size - 1).coerceAtLeast(0))
                            }

                            is ChapterLanding.Boundary -> {
                                if (landing.startFromEnd) (fetchedPages.size - 1).coerceAtLeast(0) else 0
                            }

                            is ChapterLanding.PreservePage -> {
                                landing.pageIndex.coerceIn(0, (fetchedPages.size - 1).coerceAtLeast(0))
                            }
                        }
                    Snapshot.withMutableSnapshot {
                        if (seedProgress == null) {
                            seedProgress = resolvedProgress
                        }
                        currentSession.currentPageIndex = landingIndex
                        currentSession.pagesLoadState = UiState.Success(fetchedPages)
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    currentCoroutineContext().ensureActive()
                    currentSession.pagesLoadState = UiState.Error(e)
                }
            }
        }

        val rawPages = (session?.pagesLoadState as? UiState.Success)?.data
        val clampedPageIndex =
            if (session != null && !rawPages.isNullOrEmpty()) {
                session.currentPageIndex.coerceIn(0, rawPages.lastIndex)
            } else {
                0
            }

        LaunchedEffect(session, rawPages, clampedPageIndex, currentDataSource) {
            val currentSession = session ?: return@LaunchedEffect
            val loadedPages = rawPages ?: return@LaunchedEffect
            val source = currentDataSource ?: return@LaunchedEffect
            currentSession.syncResolutionWindow(
                currentPageIndex = clampedPageIndex,
                rawPages = loadedPages,
                dataSource = source,
                mangaKey = currentSession.mangaKey,
            )
        }

        val imageTranslation = remember(accountType) { ImageTranslationPresenter(accountType) }.body()

        val effectiveDirection =
            optimisticDirectionOverride
                ?: effectiveObservedProgress?.readerModeOverride
                ?: rawDetail?.readingDirection
                ?: settings.mangaReaderConfig.defaultDirection
        val effectiveSpreadSetting =
            optimisticDoublePageSpread ?: settings.mangaReaderConfig.doublePageSpreadOnWideScreen
        val isWideAllowed = wideViewport ?: true
        val doublePageSpread =
            effectiveSpreadSetting &&
                isWideAllowed &&
                effectiveDirection != MangaReadingDirection.Webtoon
        val spreadCoverSinglePage =
            optimisticSpreadCoverSinglePage ?: settings.mangaReaderConfig.spreadCoverSinglePage
        val webtoonConstrainWidthOnWideScreen =
            optimisticWebtoonConstrainWidthOnWideScreen
                ?: settings.mangaReaderConfig.webtoonConstrainWidthOnWideScreen
        val volumeKeysNavigation =
            optimisticVolumeKeysNavigation
                ?: settings.mangaReaderConfig.volumeKeysNavigation
        val effectiveChapterSortDescending =
            optimisticChapterSortDescending
                ?: effectiveObservedProgress?.chapterSortDescending
                ?: true
        val chapterGroups = rawDetail?.chapterGroups ?: persistentListOf()
        val effectiveSelectedGroupId =
            remember(chapterGroups, selectedGroupId, chapterLocation) {
                if (chapterGroups.isEmpty()) {
                    null
                } else {
                    selectedGroupId?.takeIf { id -> chapterGroups.any { it.id == id } }
                        ?: chapterLocation?.group?.id
                        ?: chapterGroups.first().id
                }
            }
        val chapterItems =
            remember(
                chapterGroups,
                effectiveSelectedGroupId,
                effectiveChapterSortDescending,
                activeChapterId,
                effectiveObservedProgress,
            ) {
                val selectedGroup =
                    effectiveSelectedGroupId?.let { id ->
                        chapterGroups.firstOrNull { it.id == id }
                    }
                if (selectedGroup == null) {
                    persistentListOf()
                } else {
                    val orderedChapters =
                        if (effectiveChapterSortDescending) {
                            selectedGroup.chapters.asReversed()
                        } else {
                            selectedGroup.chapters
                        }
                    orderedChapters
                        .map { chapter ->
                            val saved = effectiveObservedProgress?.chapters?.get(chapter.id)
                            ReaderChapterItem(
                                chapter = chapter,
                                isCurrent = chapter.id == activeChapterId,
                                isRead = saved?.isCompleted == true,
                                lastPageIndex = saved?.lastPageIndex ?: 0,
                            )
                        }.toImmutableList()
                }
            }

        val resolvedMediaMap = session?.resolvedMedia.orEmpty()
        val failedPagesMap = session?.failedPages.orEmpty()
        val measuredDimensionsMap = session?.measuredDimensions.orEmpty()

        val pages =
            remember(rawPages, resolvedMediaMap, failedPagesMap, measuredDimensionsMap, imageTranslation) {
                if (rawPages == null) {
                    persistentListOf()
                } else {
                    rawPages
                        .mapIndexed { index, rawPage ->
                            val resolved = resolvedMediaMap[rawPage.id]
                            val aspectRatio =
                                resolvePageAspectRatio(
                                    rawPage = rawPage,
                                    resolvedMedia = resolved,
                                    measured = measuredDimensionsMap[rawPage.id],
                                )
                            val status =
                                when (rawPage) {
                                    is UiMangaPage.Ready -> {
                                        val boundOriginal =
                                            if (rawPage.media.proxy == ProxyRef.Unbound) {
                                                rawPage.media.withProxy(proxyRef)
                                            } else {
                                                rawPage.media
                                            }
                                        PageMediaStatus.Ready(
                                            original = boundOriginal,
                                            displayed = imageTranslation.displayed(boundOriginal),
                                        )
                                    }

                                    is UiMangaPage.Deferred -> {
                                        when {
                                            resolved != null -> {
                                                PageMediaStatus.Ready(
                                                    original = resolved,
                                                    displayed = imageTranslation.displayed(resolved),
                                                )
                                            }

                                            rawPage.id in failedPagesMap -> {
                                                PageMediaStatus.Failed(error = failedPagesMap.getValue(rawPage.id))
                                            }

                                            else -> {
                                                val boundProxy =
                                                    if (rawPage.proxy == ProxyRef.Unbound) proxyRef else rawPage.proxy
                                                PageMediaStatus.Loading(
                                                    previewUrl = rawPage.previewUrl,
                                                    customHeaders = rawPage.customHeaders,
                                                    proxy = boundProxy,
                                                )
                                            }
                                        }
                                    }
                                }
                            ReaderPageState(
                                id = rawPage.id,
                                index = index,
                                status = status,
                                aspectRatio = aspectRatio,
                            )
                        }.toImmutableList()
                }
            }

        val slots =
            remember(pages, effectiveDirection, doublePageSpread, spreadCoverSinglePage) {
                MangaSpreadLayoutSolver
                    .buildSlots(
                        pageCount = pages.size,
                        direction = effectiveDirection,
                        enabled = doublePageSpread,
                        coverSinglePage = spreadCoverSinglePage,
                        isLandscapePage = { pageIndex -> (pages.getOrNull(pageIndex)?.aspectRatio ?: 0f) > 1f },
                    ).toImmutableList()
            }

        val currentSlotIndex =
            remember(slots, clampedPageIndex) {
                MangaSpreadLayoutSolver.slotIndexForPage(slots, clampedPageIndex)
            }

        val previousBoundary =
            remember(rawDetail, chapterLocation) {
                if (rawDetail == null || chapterLocation == null) {
                    ChapterBoundary.None
                } else {
                    val prev = chapterLocation.group.chapters.getOrNull(chapterLocation.indexInGroup - 1)
                    prev.toChapterBoundary(detail = rawDetail, startFromEnd = true)
                }
            }

        val nextBoundary =
            remember(rawDetail, chapterLocation) {
                if (rawDetail == null || chapterLocation == null) {
                    ChapterBoundary.None
                } else {
                    val next = chapterLocation.group.chapters.getOrNull(chapterLocation.indexInGroup + 1)
                    next.toChapterBoundary(detail = rawDetail, startFromEnd = false)
                }
            }

        val prefetchMedia =
            remember(pages, clampedPageIndex) {
                if (pages.isEmpty()) {
                    persistentListOf()
                } else {
                    val start = clampedPageIndex + 1
                    val end = minOf(clampedPageIndex + 3, pages.lastIndex)
                    if (start > end) {
                        persistentListOf()
                    } else {
                        (start..end)
                            .mapNotNull { idx ->
                                val ready = pages[idx].status as? PageMediaStatus.Ready ?: return@mapNotNull null
                                ready.displayed as? UiMedia.Image ?: ready.original as? UiMedia.Image
                            }.toImmutableList()
                    }
                }
            }

        val loadState: UiState<Unit> =
            when (val detailUi = detailState) {
                is UiState.Loading -> {
                    UiState.Loading()
                }

                is UiState.Error -> {
                    UiState.Error(detailUi.throwable)
                }

                is UiState.Success -> {
                    if (activeChapter != null && activeChapter.locked) {
                        val openUrl = activeChapter.openUrl(detailUi.data.url).orEmpty()
                        UiState.Error(LockedChapterException(chapter = activeChapter, openUrl = openUrl))
                    } else {
                        when (val pagesUi = session?.pagesLoadState) {
                            null, is UiState.Loading -> UiState.Loading()
                            is UiState.Error -> UiState.Error(pagesUi.throwable)
                            is UiState.Success -> UiState.Success(Unit)
                        }
                    }
                }
            }

        val flushState = remember { ProgressFlushTracker() }

        fun computeLiveProgressRecord(targetSession: ChapterPagesSession? = activeSessionHolder.session): PendingProgressRecord? {
            val progressSnapshot = loadedProgress ?: seedProgress ?: return null
            val currentSession = targetSession ?: return null
            if (currentSession.chapterId != chapterTarget.chapterId) return null
            val loaded = (currentSession.pagesLoadState as? UiState.Success)?.data ?: return null
            if (loaded.isEmpty()) return null
            val pageIdx = currentSession.currentPageIndex.coerceIn(0, loaded.lastIndex)
            val liveDirection =
                optimisticDirectionOverride
                    ?: progressSnapshot.readerModeOverride
                    ?: rawDetail?.readingDirection
                    ?: settings.mangaReaderConfig.defaultDirection
            val liveSpreadSetting =
                optimisticDoublePageSpread ?: settings.mangaReaderConfig.doublePageSpreadOnWideScreen
            val liveSpread =
                liveSpreadSetting &&
                    (wideViewport ?: true) &&
                    liveDirection != MangaReadingDirection.Webtoon
            val liveCoverSingle =
                optimisticSpreadCoverSinglePage ?: settings.mangaReaderConfig.spreadCoverSinglePage
            val liveSlots =
                MangaSpreadLayoutSolver.buildSlots(
                    pageCount = loaded.size,
                    direction = liveDirection,
                    enabled = liveSpread,
                    coverSinglePage = liveCoverSingle,
                    isLandscapePage = { i ->
                        val page = loaded[i]
                        val ratio =
                            resolvePageAspectRatio(
                                rawPage = page,
                                resolvedMedia = currentSession.resolvedMedia[page.id],
                                measured = currentSession.measuredDimensions[page.id],
                            )
                        (ratio ?: 0f) > 1f
                    },
                )
            val slotIdx = MangaSpreadLayoutSolver.slotIndexForPage(liveSlots, pageIdx)
            val completed = liveSlots.getOrNull(slotIdx)?.contains(loaded.lastIndex) == true
            return PendingProgressRecord(
                mangaKey = currentSession.mangaKey,
                chapterId = currentSession.chapterId,
                pageIndex = pageIdx,
                pageCount = loaded.size,
                isCompleted = completed,
            )
        }

        fun flushRecord(record: PendingProgressRecord) {
            if (flushState.lastFlushed == record) return
            flushState.lastFlushed = record
            writeScope.launch {
                writeMutex.withLock {
                    progressRepository.recordPage(
                        accountType = accountType,
                        mangaKey = record.mangaKey,
                        chapterId = record.chapterId,
                        pageIndex = record.pageIndex,
                        pageCount = record.pageCount,
                        isCompleted = record.isCompleted,
                    )
                }
            }
        }

        LaunchedEffect(session) {
            val currentSession = session ?: return@LaunchedEffect
            snapshotFlow { computeLiveProgressRecord(currentSession) }
                .filterNotNull()
                .distinctUntilChanged()
                .debounce(300)
                .collect { record ->
                    flushRecord(record)
                }
        }

        val flushOnExit by rememberUpdatedState { computeLiveProgressRecord()?.let(::flushRecord) }
        DisposableEffect(Unit) {
            onDispose {
                flushOnExit()
                activeSessionHolder.session?.cancel()
                writeJob.complete()
            }
        }

        fun transitionToChapter(
            targetChapterId: String,
            landing: ChapterLanding,
        ) {
            if (targetChapterId == chapterTarget.chapterId) return
            computeLiveProgressRecord()?.let(::flushRecord)
            activeSessionHolder.session?.cancel()
            latestRawDetail?.locateChapter(targetChapterId)?.group?.id?.let { groupId ->
                selectedGroupId = groupId
            }
            chapterTarget =
                ChapterTarget(
                    chapterId = targetChapterId,
                    attempt = 0,
                    landing = landing,
                )
        }

        fun switchToChapter(
            targetChapterId: String,
            startFromEnd: Boolean,
        ) {
            transitionToChapter(
                targetChapterId = targetChapterId,
                landing = ChapterLanding.Boundary(startFromEnd = startFromEnd),
            )
        }

        return object : State {
            override val mangaTitle: String = rawDetail?.title.orEmpty()
            override val chapterTitle: String = activeChapter?.title ?: activeChapterId.takeIf { rawDetail != null }.orEmpty()
            override val chapterUrl: String? =
                activeChapter?.openUrl(rawDetail?.url) ?: rawDetail?.url?.trim()?.takeIf(String::isNotEmpty)
            override val activeChapterId: String = activeChapterId
            override val loadState: UiState<Unit> = loadState
            override val pages: ImmutableList<ReaderPageState> = pages
            override val slots: ImmutableList<ReaderSlot> = slots
            override val currentPageIndex: Int = clampedPageIndex
            override val currentSlotIndex: Int = currentSlotIndex
            override val direction: MangaReadingDirection = effectiveDirection
            override val doublePageSpread: Boolean = doublePageSpread
            override val spreadCoverSinglePage: Boolean = spreadCoverSinglePage
            override val previousBoundary: ChapterBoundary = previousBoundary
            override val nextBoundary: ChapterBoundary = nextBoundary
            override val prefetchMedia: ImmutableList<UiMedia.Image> = prefetchMedia
            override val imageTranslation: ImageTranslationPresenter.State = imageTranslation
            override val chapterGroups: ImmutableList<UiMangaChapterGroup> = chapterGroups
            override val selectedChapterGroupId: String? = effectiveSelectedGroupId
            override val chapterSortDescending: Boolean = effectiveChapterSortDescending
            override val chapterItems: ImmutableList<ReaderChapterItem> = chapterItems
            override val webtoonConstrainWidthOnWideScreen: Boolean = webtoonConstrainWidthOnWideScreen
            override val volumeKeysNavigation: Boolean = volumeKeysNavigation

            override fun setWideViewport(isWide: Boolean) {
                wideViewport = isWide
            }

            override fun onPageDimensionsLoaded(
                pageId: String,
                width: Float,
                height: Float,
            ) {
                if (width <= 0f || height <= 0f || !width.isFinite() || !height.isFinite()) return
                val currentSession = activeSessionHolder.session ?: return
                if (currentSession.measuredDimensions[pageId] != (width to height)) {
                    currentSession.measuredDimensions = currentSession.measuredDimensions + (pageId to (width to height))
                }
            }

            override fun goToPage(pageIndex: Int) {
                val currentSession = activeSessionHolder.session ?: return
                val loaded = (currentSession.pagesLoadState as? UiState.Success)?.data ?: return
                if (loaded.isEmpty()) return
                currentSession.currentPageIndex = pageIndex.coerceIn(0, loaded.lastIndex)
            }

            override fun goToSlot(slotIndex: Int) {
                val currentSession = activeSessionHolder.session ?: return
                if (slots.isEmpty()) return
                val targetSlot = slots[slotIndex.coerceIn(0, slots.lastIndex)]
                if (!targetSlot.contains(currentSession.currentPageIndex)) {
                    currentSession.currentPageIndex = targetSlot.primaryPageIndex
                }
            }

            override fun stepForward() {
                val currentSession = activeSessionHolder.session ?: return
                if (slots.isEmpty()) return
                val liveSlotIndex = MangaSpreadLayoutSolver.slotIndexForPage(slots, currentSession.currentPageIndex)
                if (liveSlotIndex < slots.lastIndex) {
                    currentSession.currentPageIndex = slots[liveSlotIndex + 1].primaryPageIndex
                } else if (nextBoundary is ChapterBoundary.Unlocked) {
                    switchToChapter(targetChapterId = nextBoundary.chapter.id, startFromEnd = nextBoundary.startFromEnd)
                }
            }

            override fun stepBackward() {
                val currentSession = activeSessionHolder.session ?: return
                if (slots.isEmpty()) return
                val liveSlotIndex = MangaSpreadLayoutSolver.slotIndexForPage(slots, currentSession.currentPageIndex)
                if (liveSlotIndex > 0) {
                    currentSession.currentPageIndex = slots[liveSlotIndex - 1].primaryPageIndex
                } else if (previousBoundary is ChapterBoundary.Unlocked) {
                    switchToChapter(
                        targetChapterId = previousBoundary.chapter.id,
                        startFromEnd = previousBoundary.startFromEnd,
                    )
                }
            }

            override fun openAdjacentChapter(boundary: ChapterBoundary.Unlocked) {
                switchToChapter(targetChapterId = boundary.chapter.id, startFromEnd = boundary.startFromEnd)
            }

            override fun retryChapter() {
                val currentSession = activeSessionHolder.session
                if (detailState is UiState.Error || isChapterLocked) {
                    detailAttempt++
                } else if (activeChapter == null && currentSession?.pagesLoadState is UiState.Error) {
                    val nextLanding = chapterTarget.landing
                    currentSession.cancel()
                    detailAttempt++
                    chapterTarget =
                        chapterTarget.copy(
                            attempt = chapterTarget.attempt + 1,
                            landing = nextLanding,
                        )
                } else {
                    val nextLanding =
                        if (currentSession?.pagesLoadState is UiState.Success) {
                            ChapterLanding.PreservePage(currentSession.currentPageIndex)
                        } else {
                            chapterTarget.landing
                        }
                    currentSession?.cancel()
                    chapterTarget =
                        chapterTarget.copy(
                            attempt = chapterTarget.attempt + 1,
                            landing = nextLanding,
                        )
                }
            }

            override fun retryPage(pageId: String) {
                val currentSession = activeSessionHolder.session ?: return
                val loaded = (currentSession.pagesLoadState as? UiState.Success)?.data ?: return
                val deferred = loaded.firstOrNull { it.id == pageId } as? UiMangaPage.Deferred ?: return
                val source = currentDataSource ?: return
                currentSession.retryPage(
                    page = deferred,
                    dataSource = source,
                    mangaKey = currentSession.mangaKey,
                )
            }

            override fun setDirection(direction: MangaReadingDirection) {
                val targetMangaKey = latestEffectiveMangaKey
                optimisticDirectionOverride = direction
                pendingDirectionWrites++
                writeScope.launch {
                    try {
                        writeMutex.withLock {
                            progressRepository.setReaderModeOverride(accountType, targetMangaKey, direction)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        optimisticDirectionOverride = null
                    } finally {
                        pendingDirectionWrites--
                        if (pendingDirectionWrites == 0 && latestLoadedProgress?.readerModeOverride == optimisticDirectionOverride) {
                            optimisticDirectionOverride = null
                        }
                    }
                }
            }

            override fun setDoublePageSpread(enabled: Boolean) {
                optimisticDoublePageSpread = enabled
                pendingSettingsWrites++
                writeScope.launch {
                    try {
                        writeMutex.withLock {
                            appDataStore.appSettingsStore.updateData { current ->
                                current.copy(
                                    mangaReaderConfig =
                                        current.mangaReaderConfig.copy(
                                            doublePageSpreadOnWideScreen = enabled,
                                        ),
                                )
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        optimisticDoublePageSpread = null
                    } finally {
                        pendingSettingsWrites--
                        clearSyncedOptimisticSettings()
                    }
                }
            }

            override fun setSpreadCoverSinglePage(enabled: Boolean) {
                optimisticSpreadCoverSinglePage = enabled
                pendingSettingsWrites++
                writeScope.launch {
                    try {
                        writeMutex.withLock {
                            appDataStore.appSettingsStore.updateData { current ->
                                current.copy(
                                    mangaReaderConfig =
                                        current.mangaReaderConfig.copy(
                                            spreadCoverSinglePage = enabled,
                                        ),
                                )
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        optimisticSpreadCoverSinglePage = null
                    } finally {
                        pendingSettingsWrites--
                        clearSyncedOptimisticSettings()
                    }
                }
            }

            override fun selectChapterGroup(groupId: String) {
                val detail = latestRawDetail
                if (detail == null || detail.chapterGroups.any { it.id == groupId }) {
                    selectedGroupId = groupId
                }
            }

            override fun toggleChapterSort() {
                val targetMangaKey = latestEffectiveMangaKey
                val nextDescending =
                    !(optimisticChapterSortDescending ?: latestLoadedProgress?.chapterSortDescending ?: true)
                optimisticChapterSortDescending = nextDescending
                pendingSortWrites++
                writeScope.launch {
                    try {
                        writeMutex.withLock {
                            progressRepository.setChapterSortDescending(accountType, targetMangaKey, nextDescending)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        optimisticChapterSortDescending = null
                    } finally {
                        pendingSortWrites--
                        if (pendingSortWrites == 0 && latestLoadedProgress?.chapterSortDescending == optimisticChapterSortDescending) {
                            optimisticChapterSortDescending = null
                        }
                    }
                }
            }

            override fun selectChapter(chapterId: String) {
                if (chapterId == chapterTarget.chapterId) return
                val targetChapter = latestRawDetail?.locateChapter(chapterId)?.chapter ?: return
                if (targetChapter.locked) return
                transitionToChapter(
                    targetChapterId = chapterId,
                    landing = ChapterLanding.Initial(null),
                )
            }

            override fun openChapter(
                chapterId: String,
                initialPage: Int?,
            ) {
                if (chapterId == chapterTarget.chapterId) {
                    if (initialPage != null) {
                        goToPage(initialPage)
                    }
                    return
                }
                transitionToChapter(
                    targetChapterId = chapterId,
                    landing = ChapterLanding.Initial(initialPage),
                )
            }

            override fun resolveChapterOpenUrl(chapter: UiMangaChapter): String? = chapter.openUrl(latestRawDetail?.url)

            override fun setWebtoonConstrainWidthOnWideScreen(enabled: Boolean) {
                optimisticWebtoonConstrainWidthOnWideScreen = enabled
                pendingSettingsWrites++
                writeScope.launch {
                    try {
                        writeMutex.withLock {
                            appDataStore.appSettingsStore.updateData { current ->
                                current.copy(
                                    mangaReaderConfig =
                                        current.mangaReaderConfig.copy(
                                            webtoonConstrainWidthOnWideScreen = enabled,
                                        ),
                                )
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        optimisticWebtoonConstrainWidthOnWideScreen = null
                    } finally {
                        pendingSettingsWrites--
                        clearSyncedOptimisticSettings()
                    }
                }
            }

            override fun setVolumeKeysNavigation(enabled: Boolean) {
                optimisticVolumeKeysNavigation = enabled
                pendingSettingsWrites++
                writeScope.launch {
                    try {
                        writeMutex.withLock {
                            appDataStore.appSettingsStore.updateData { current ->
                                current.copy(
                                    mangaReaderConfig =
                                        current.mangaReaderConfig.copy(
                                            volumeKeysNavigation = enabled,
                                        ),
                                )
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        optimisticVolumeKeysNavigation = null
                    } finally {
                        pendingSettingsWrites--
                        clearSyncedOptimisticSettings()
                    }
                }
            }
        }
    }
}

private data class ChapterTarget(
    val chapterId: String,
    val attempt: Int,
    val landing: ChapterLanding,
)

private sealed interface ChapterLanding {
    data class Initial(
        val initialPage: Int?,
    ) : ChapterLanding

    data class Boundary(
        val startFromEnd: Boolean,
    ) : ChapterLanding

    data class PreservePage(
        val pageIndex: Int,
    ) : ChapterLanding
}

private data class ChapterLocation(
    val group: UiMangaChapterGroup,
    val indexInGroup: Int,
    val chapter: UiMangaChapter,
)

private fun UiMangaDetail.locateChapter(chapterId: String): ChapterLocation? {
    for (group in chapterGroups) {
        val index = group.chapters.indexOfFirst { it.id == chapterId }
        if (index >= 0) {
            return ChapterLocation(
                group = group,
                indexInGroup = index,
                chapter = group.chapters[index],
            )
        }
    }
    return null
}

private fun UiMangaChapter?.toChapterBoundary(
    detail: UiMangaDetail,
    startFromEnd: Boolean,
): ChapterBoundary {
    if (this == null) return ChapterBoundary.None
    return if (locked) {
        ChapterBoundary.Locked(
            chapter = this,
            openUrl = openUrl(detail.url).orEmpty(),
        )
    } else {
        ChapterBoundary.Unlocked(
            chapter = this,
            startFromEnd = startFromEnd,
        )
    }
}

private class ActiveSessionRef {
    var session: ChapterPagesSession? = null
}

private class ProgressFlushTracker {
    var lastFlushed: PendingProgressRecord? = null
}

private data class PendingProgressRecord(
    val mangaKey: MicroBlogKey,
    val chapterId: String,
    val pageIndex: Int,
    val pageCount: Int,
    val isCompleted: Boolean,
)

private class ChapterPagesSession(
    val mangaKey: MicroBlogKey,
    val chapterId: String,
    val landing: ChapterLanding,
    initialPageHint: Int,
    parentContext: CoroutineContext,
    private val proxyRef: ProxyRef,
) {
    private val job = SupervisorJob(parentContext[Job])
    private val scope = CoroutineScope(parentContext.minusKey(Job) + job)
    private val semaphore = Semaphore(3)
    private val inFlightJobs = mutableMapOf<String, Job>()

    var currentPageIndex by mutableIntStateOf(initialPageHint.coerceAtLeast(0))
    var hasStartedLoad: Boolean = false
    var pagesLoadState by mutableStateOf<UiState<ImmutableList<UiMangaPage>>>(UiState.Loading())
    var resolvedMedia by mutableStateOf<Map<String, UiMedia>>(emptyMap())
    var failedPages by mutableStateOf<Map<String, Throwable>>(emptyMap())
    var measuredDimensions by mutableStateOf<Map<String, Pair<Float, Float>>>(emptyMap())

    fun syncResolutionWindow(
        currentPageIndex: Int,
        rawPages: ImmutableList<UiMangaPage>,
        dataSource: MangaDataSource,
        mangaKey: MicroBlogKey,
    ) {
        if (!job.isActive || rawPages.isEmpty()) return
        val clampedCurrent = currentPageIndex.coerceIn(0, rawPages.lastIndex)
        val start = (clampedCurrent - 1).coerceAtLeast(0)
        val end = (clampedCurrent + 3).coerceAtMost(rawPages.lastIndex)
        val windowIds = HashSet<String>(end - start + 1)
        for (i in start..end) {
            windowIds.add(rawPages[i].id)
        }

        val iterator = inFlightJobs.entries.iterator()
        while (iterator.hasNext()) {
            val (pageId, inFlightJob) = iterator.next()
            if (pageId !in windowIds) {
                inFlightJob.cancel()
                iterator.remove()
            } else if (!inFlightJob.isActive) {
                iterator.remove()
            }
        }

        val priorityOffsets = intArrayOf(0, 1, -1, 2, 3)
        for (offset in priorityOffsets) {
            val idx = clampedCurrent + offset
            if (idx !in start..end) continue
            val deferred = rawPages[idx] as? UiMangaPage.Deferred ?: continue
            if (deferred.id in resolvedMedia || deferred.id in failedPages || inFlightJobs[deferred.id]?.isActive == true) {
                continue
            }
            launchResolvePage(
                page = deferred,
                dataSource = dataSource,
                mangaKey = mangaKey,
            )
        }
    }

    fun retryPage(
        page: UiMangaPage.Deferred,
        dataSource: MangaDataSource,
        mangaKey: MicroBlogKey,
    ) {
        if (!job.isActive) return
        inFlightJobs.remove(page.id)?.cancel()
        resolvedMedia = resolvedMedia - page.id
        failedPages = failedPages - page.id
        launchResolvePage(
            page = page,
            dataSource = dataSource,
            mangaKey = mangaKey,
        )
    }

    private fun launchResolvePage(
        page: UiMangaPage.Deferred,
        dataSource: MangaDataSource,
        mangaKey: MicroBlogKey,
    ) {
        val pageJob =
            scope.launch {
                try {
                    val media =
                        semaphore.withPermit {
                            currentCoroutineContext().ensureActive()
                            dataSource.resolvePageMedia(
                                mangaKey = mangaKey,
                                chapterId = chapterId,
                                pageId = page.id,
                                pageToken = page.pageToken,
                            )
                        }
                    currentCoroutineContext().ensureActive()
                    val bound = if (media.proxy == ProxyRef.Unbound) media.withProxy(proxyRef) else media
                    resolvedMedia = resolvedMedia + (page.id to bound)
                    failedPages = failedPages - page.id
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    currentCoroutineContext().ensureActive()
                    failedPages = failedPages + (page.id to e)
                } finally {
                    if (inFlightJobs[page.id] === currentCoroutineContext()[Job]) {
                        inFlightJobs.remove(page.id)
                    }
                }
            }
        inFlightJobs[page.id] = pageJob
    }

    fun cancel() {
        job.cancel()
        inFlightJobs.clear()
    }
}

private fun resolvePageAspectRatio(
    rawPage: UiMangaPage,
    resolvedMedia: UiMedia?,
    measured: Pair<Float, Float>?,
): Float? {
    if (measured != null && measured.first > 0f && measured.second > 0f) {
        val ratio = measured.first / measured.second
        if (ratio.isFinite() && ratio > 0f) return ratio
    }
    val mediaDims =
        resolvedMedia?.dimensionsOrNull()
            ?: (rawPage as? UiMangaPage.Ready)?.media?.dimensionsOrNull()
    if (mediaDims != null && mediaDims.first > 0f && mediaDims.second > 0f) {
        val ratio = mediaDims.first / mediaDims.second
        if (ratio.isFinite() && ratio > 0f) return ratio
    }
    if (rawPage is UiMangaPage.Deferred && rawPage.width > 0f && rawPage.height > 0f) {
        val ratio = rawPage.width / rawPage.height
        if (ratio.isFinite() && ratio > 0f) return ratio
    }
    return null
}

private fun UiMedia.dimensionsOrNull(): Pair<Float, Float>? =
    when (this) {
        is UiMedia.Image -> width to height
        is UiMedia.Gif -> width to height
        is UiMedia.Video -> width to height
        is UiMedia.Ugoira -> width to height
        is UiMedia.Audio -> null
    }
