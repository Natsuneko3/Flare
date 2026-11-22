package dev.dimension.flare.ui.presenter

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.testing.asPagingSourceFactory
import androidx.paging.testing.asSnapshot
import androidx.room3.Room
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import dev.dimension.flare.common.InAppNotification
import dev.dimension.flare.common.Message
import dev.dimension.flare.common.PagingState
import dev.dimension.flare.common.TestFormatter
import dev.dimension.flare.createTestFileSystem
import dev.dimension.flare.createTestRootPath
import dev.dimension.flare.data.database.app.AppDatabase
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.createDatabaseDriver
import dev.dimension.flare.data.datasource.microblog.MicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.PostDetailTab
import dev.dimension.flare.data.datasource.microblog.PostDetailTabs
import dev.dimension.flare.data.datasource.microblog.UnavailableMicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.paging.CacheableRemoteLoader
import dev.dimension.flare.data.datasource.microblog.paging.HomeTimelineRefreshSessions
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.datasource.microblog.paging.toTimelinePagingSource
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.io.OkioFileStorage
import dev.dimension.flare.data.model.tab.TimelinePresenterFactory
import dev.dimension.flare.data.model.tab.TimelineResolver
import dev.dimension.flare.data.plugin.TimelineProcessorService
import dev.dimension.flare.data.repository.AccountRepository
import dev.dimension.flare.data.repository.AccountService
import dev.dimension.flare.data.repository.DraftMediaStore
import dev.dimension.flare.data.repository.DraftRepository
import dev.dimension.flare.data.repository.LocalFilterRepository
import dev.dimension.flare.data.repository.MxgaRepository
import dev.dimension.flare.data.repository.ProxyRepository
import dev.dimension.flare.data.repository.RepositoryAccountService
import dev.dimension.flare.data.repository.SettingsRepository
import dev.dimension.flare.data.translation.NoopPreTranslationService
import dev.dimension.flare.data.translation.PreTranslationService
import dev.dimension.flare.deleteTestRootPath
import dev.dimension.flare.di.startKoin
import dev.dimension.flare.di.testSingle
import dev.dimension.flare.memoryDatabaseBuilder
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.PlatformDataSourceContext
import dev.dimension.flare.model.PlatformRegistry
import dev.dimension.flare.model.PlatformSpec
import dev.dimension.flare.testPlatformRegistry
import dev.dimension.flare.testPlatformRuntimeData
import dev.dimension.flare.ui.humanizer.PlatformFormatter
import dev.dimension.flare.ui.model.UiAccount
import dev.dimension.flare.ui.model.UiPluginWidgetCard
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.UiWidgetNode
import dev.dimension.flare.ui.model.contentPostOrNull
import dev.dimension.flare.ui.model.createSamplePost
import dev.dimension.flare.ui.model.pluginPostEntityKey
import dev.dimension.flare.ui.model.takeSuccess
import dev.dimension.flare.ui.presenter.home.TimelinePresenter
import dev.dimension.flare.ui.presenter.status.StatusContextPresenter
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.unavailableAccountService
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TimelinePluginWidgetPresenterTest {
    @Test
    fun emitsTimelineImmediatelyAndUpdatesPostReactivelyIncludingRepostsWhenRepostDisplayPostMatches() =
        runTest {
            startKoin {
                modules(
                    module {
                        testSingle<PlatformFormatter> { TestFormatter() }
                    },
                )
            }
            try {
                val original = createSamplePost(id = "orig-1")
                val quoted = createSamplePost(id = "quoted-1")
                val repostItem =
                    UiTimelineV2.TimelinePostItem(
                        post = createSamplePost(id = "repost-wrapper"),
                        presentation =
                            UiTimelineV2.PostPresentation(
                                repost = original,
                                quotes = persistentListOf(quoted),
                            ),
                    )
                val fakeService = FakeDelayedTimelineProcessorService()
                repostItem.submitPluginPosts(fakeService)
                val bound = repostItem.attachPluginWidgets(fakeService) as UiTimelineV2.TimelinePostItem
                assertTrue(bound.displayPost.pluginWidgets.isEmpty())
                assertEquals(listOf(original.statusKey), fakeService.submittedKeys)

                fakeService.emitCards(
                    original,
                    (1..10).map { idx ->
                        UiPluginWidgetCard(
                            "p$idx",
                            "P$idx",
                            null,
                            "c$idx",
                            "Card $idx",
                            body = persistentListOf(UiWidgetNode.Text("$idx")),
                        )
                    },
                )
                val updated = repostItem.attachPluginWidgets(fakeService) as UiTimelineV2.TimelinePostItem
                assertEquals(8, updated.displayPost.pluginWidgets.size)
                assertTrue(updated.post.pluginWidgets.isEmpty())
                assertTrue(
                    updated.presentation.quotes
                        .single()
                        .pluginWidgets
                        .isEmpty(),
                )
                assertNotEquals(bound.renderHash, updated.renderHash)
                assertEquals(bound.stableItemKey, updated.stableItemKey)
            } finally {
                stopKoin()
            }
        }

    @Test
    fun bindPluginWidgetsSubmitsInlineParentsSkipsGapsAndPassesThroughWhenServiceIsNull() =
        runTest {
            startKoin {
                modules(
                    module {
                        testSingle<PlatformFormatter> { TestFormatter() }
                    },
                )
            }
            try {
                val parentPost = createSamplePost(id = "parent-1")
                val replyPost = createSamplePost(id = "reply-1")
                val standalonePost = createSamplePost(id = "standalone-1")
                val gapItem =
                    UiTimelineV2.Gap(
                        statusKey = MicroBlogKey("gap-1", "sampleHost"),
                        createdAt = Instant.fromEpochSeconds(1_700_000_000L).toUi(),
                        nextKey = "cursor-2",
                    )
                val replyItem =
                    UiTimelineV2.TimelinePostItem(
                        post = replyPost,
                        presentation =
                            UiTimelineV2.PostPresentation(
                                inlineParents = persistentListOf(UiTimelineV2.TimelinePostItem(post = parentPost)),
                            ),
                    )

                val sourceFlow =
                    Pager(PagingConfig(pageSize = 20)) {
                        object : RemoteLoader<UiTimelineV2> {
                            override suspend fun load(
                                pageSize: Int,
                                request: PagingRequest,
                            ): PagingResult<UiTimelineV2> =
                                PagingResult(
                                    data = listOf(gapItem, replyItem, standalonePost),
                                    endOfPaginationReached = true,
                                )
                        }.toTimelinePagingSource()
                    }.flow.cachedIn(backgroundScope)

                assertSame(sourceFlow, sourceFlow.bindPluginWidgets(null, backgroundScope))

                val fakeService = FakeDelayedTimelineProcessorService()
                val boundFlow = sourceFlow.bindPluginWidgets(fakeService, backgroundScope)

                val initialSnapshot = boundFlow.asSnapshot()
                assertEquals(3, initialSnapshot.size)
                assertEquals(gapItem.statusKey, (initialSnapshot[0] as UiTimelineV2.Gap).statusKey)
                val initialReply = initialSnapshot[1] as UiTimelineV2.TimelinePostItem
                assertTrue(initialReply.displayPost.pluginWidgets.isEmpty())
                assertTrue(
                    initialReply.presentation.inlineParents
                        .single()
                        .displayPost.pluginWidgets
                        .isEmpty(),
                )
                assertEquals(
                    listOf(replyPost.statusKey, parentPost.statusKey, standalonePost.statusKey),
                    fakeService.submittedKeys,
                )

                val parentCard = sampleCard("parent-card", "Parent Score")
                val replyCard = sampleCard("reply-card", "Reply Quote")
                fakeService.emitCards(parentPost, listOf(parentCard))
                fakeService.emitCards(replyPost, listOf(replyCard))

                val updatedSnapshot = boundFlow.asSnapshot()
                assertEquals(listOf(replyPost.statusKey, parentPost.statusKey, standalonePost.statusKey), fakeService.submittedKeys)
                val updatedReply = updatedSnapshot[1] as UiTimelineV2.TimelinePostItem
                assertEquals(listOf("reply-card"), updatedReply.displayPost.pluginWidgets.map { it.id })
                assertEquals(
                    listOf("parent-card"),
                    updatedReply.presentation.inlineParents
                        .single()
                        .displayPost.pluginWidgets
                        .map { it.id },
                )
            } finally {
                stopKoin()
            }
        }

    @Test
    fun overlayUpdatesDoNotResubmitPagesLargerThanTheProcessorCache() =
        runTest {
            startKoin { modules(module { testSingle<PlatformFormatter> { TestFormatter() } }) }
            try {
                val posts = List(1_025) { index -> createSamplePost(id = "row-$index") }
                val items: List<UiTimelineV2> = posts
                val service = FakeDelayedTimelineProcessorService()
                val timeline =
                    Pager(PagingConfig(pageSize = posts.size), pagingSourceFactory = items.asPagingSourceFactory())
                        .flow
                        .cachedIn(backgroundScope)
                        .bindPluginWidgets(service, backgroundScope)

                assertEquals(posts.size, timeline.asSnapshot().size)
                repeat(5) { index ->
                    val card = sampleCard("card-$index", "Result $index")
                    service.emitCards(posts[index], listOf(card))
                    val post = timeline.asSnapshot()[index].contentPostOrNull()!!
                    assertEquals(card.id, post.pluginWidgets.single().id)
                }
                assertEquals(posts.map { it.statusKey }, service.submittedKeys)
            } finally {
                stopKoin()
            }
        }

    @Test
    fun timelinePresenterIntegratesPluginWidgetsAcrossCachePagerAndNetworkPagerWithoutInvalidatingSqliteWindow() =
        withPresenterHarness {
            val cachedPost = createSamplePost(id = "cached-1", host = accountKey.host, text = "Cached post")
            val filteredOutPost = createSamplePost(id = "muted-1", host = accountKey.host, text = "secret-muted-keyword")
            localFilterRepository
                .add(
                    keyword = "secret-muted-keyword",
                    forTimeline = true,
                    forNotification = false,
                    forSearch = false,
                    expiredAt = null,
                    isRegex = false,
                ).join()

            val cacheLoads = AtomicInteger(0)
            val cacheLoader =
                object : CacheableRemoteLoader<UiTimelineV2> {
                    override val pagingKey: String = "plugin_widget_cache_test"

                    override suspend fun load(
                        pageSize: Int,
                        request: PagingRequest,
                    ): PagingResult<UiTimelineV2> {
                        if (request is PagingRequest.Append) {
                            return PagingResult(data = emptyList(), endOfPaginationReached = true)
                        }
                        cacheLoads.incrementAndGet()
                        return PagingResult(
                            data = listOf(cachedPost, filteredOutPost),
                            endOfPaginationReached = true,
                        )
                    }
                }

            val cachedState = observe(TimelinePresenter(loader = flowOf(cacheLoader)))
            val initialCached =
                cachedState.await { state ->
                    (state.listState as? PagingState.Success)?.itemCount == 1
                }
            val initialCachedItem = (initialCached.listState as PagingState.Success).peek(0)!!
            assertTrue(initialCachedItem.contentPostOrNull()!!.pluginWidgets.isEmpty())
            assertEquals(1, cacheLoads.get())
            assertTrue(fakeService.submittedKeys.contains(cachedPost.statusKey))
            assertTrue(fakeService.submittedKeys.none { it == filteredOutPost.statusKey })

            fakeService.emitCards(cachedPost, listOf(sampleCard("cached-card", "Cached Stock")))
            val enrichedCached =
                cachedState.await { state ->
                    (state.listState as? PagingState.Success)
                        ?.peek(0)
                        ?.contentPostOrNull()
                        ?.pluginWidgets
                        ?.size == 1
                }
            assertEquals(
                "Cached Stock",
                (enrichedCached.listState as PagingState.Success)
                    .peek(0)!!
                    .contentPostOrNull()!!
                    .pluginWidgets
                    .single()
                    .title,
            )
            assertEquals(1, cacheLoads.get())

            val submissionsBeforeRevisit = fakeService.submittedKeys.size
            (enrichedCached.listState as PagingState.Success)[0]
            assertEquals(listOf(cachedPost.statusKey), fakeService.submittedKeys.drop(submissionsBeforeRevisit))

            val networkPost = createSamplePost(id = "network-1", host = accountKey.host, text = "Search result")
            val networkLoader =
                object : RemoteLoader<UiTimelineV2> {
                    override suspend fun load(
                        pageSize: Int,
                        request: PagingRequest,
                    ): PagingResult<UiTimelineV2> =
                        PagingResult(
                            data = listOf(networkPost),
                            endOfPaginationReached = true,
                        )
                }
            val networkState = observe(TimelinePresenter(loader = flowOf(networkLoader)))
            networkState.await { state ->
                (state.listState as? PagingState.Success)?.itemCount == 1
            }
            assertTrue(fakeService.submittedKeys.contains(networkPost.statusKey))

            fakeService.emitCards(networkPost, listOf(sampleCard("net-card", "Search Market")))
            val enrichedNetwork =
                networkState.await { state ->
                    (state.listState as? PagingState.Success)
                        ?.peek(0)
                        ?.contentPostOrNull()
                        ?.pluginWidgets
                        ?.size == 1
                }
            assertEquals(
                "Search Market",
                (enrichedNetwork.listState as PagingState.Success)
                    .peek(0)!!
                    .contentPostOrNull()!!
                    .pluginWidgets
                    .single()
                    .title,
            )
        }

    @Test
    fun statusContextPresenterBindsReactivePluginWidgetsToNonContextDetailAndContextualDetail() =
        withPresenterHarness {
            val focalKey = MicroBlogKey("focal-1", accountKey.host)
            val parentKey = MicroBlogKey("parent-ctx-1", accountKey.host)
            val replyKey = MicroBlogKey("reply-ctx-1", accountKey.host)
            val focalPost =
                createSamplePost(id = focalKey.id, host = focalKey.host, text = "Focal post")
                    .copy(accountType = accountType)
            val parentPost =
                createSamplePost(id = parentKey.id, host = parentKey.host, text = "Parent post")
                    .copy(accountType = accountType)
            val replyPost =
                createSamplePost(id = replyKey.id, host = replyKey.host, text = "Reply post")
                    .copy(accountType = accountType)

            attachDataSource(
                object : MicroblogDataSource by UnavailableMicroblogDataSource {
                    override suspend fun postDetailTabs(statusKey: MicroBlogKey): PostDetailTabs =
                        PostDetailTabs(
                            tabs =
                                listOf(
                                    PostDetailTab(
                                        id = "replies",
                                        title = UiText.Raw("Replies"),
                                        includesPostContext = false,
                                        loader = {
                                            object : RemoteLoader<UiTimelineV2> {
                                                override suspend fun load(
                                                    pageSize: Int,
                                                    request: PagingRequest,
                                                ): PagingResult<UiTimelineV2> =
                                                    PagingResult(
                                                        data = listOf(replyPost),
                                                        endOfPaginationReached = true,
                                                    )
                                            }
                                        },
                                    ),
                                ),
                            replyTabId = "replies",
                        )

                    override fun postDetailPost(statusKey: MicroBlogKey): Flow<UiState<UiTimelineV2>>? =
                        if (statusKey == focalKey) flowOf(UiState.Success(focalPost)) else null
                },
            )

            val nonContextState = observe(StatusContextPresenter(accountType, focalKey))
            nonContextState.await { state ->
                state.current.takeSuccess() != null &&
                    state.header.isNotEmpty() &&
                    (state.listState as? PagingState.Success)?.itemCount == 1
            }
            assertEquals(1, fakeService.submittedKeys.count { it == focalKey })
            assertTrue(fakeService.submittedKeys.contains(replyKey))

            // Simulate enabling a plugin / updating flare.storage while Post Detail is open:
            fakeService.submittedKeys.clear()
            fakeService.invalidateProcessing()
            nonContextState.await { fakeService.submittedKeys.contains(focalKey) }
            assertEquals(1, fakeService.submittedKeys.count { it == focalKey })

            fakeService.emitCards(focalPost, (1..9).map { sampleCard("focal-$it", "Focal $it") })
            fakeService.emitCards(replyPost, listOf(sampleCard("reply-card", "Reply Card")))

            val enrichedNonContext =
                nonContextState.await { state ->
                    state.current
                        .takeSuccess()
                        ?.contentPostOrNull()
                        ?.pluginWidgets
                        ?.size == 8 &&
                        state.header
                            .single()
                            .contentPostOrNull()
                            .let { it?.pluginWidgets?.size == 8 } &&
                        (state.listState as? PagingState.Success)
                            ?.peek(0)
                            ?.contentPostOrNull()
                            ?.pluginWidgets
                            ?.size == 1
                }
            assertEquals(
                8,
                enrichedNonContext.current
                    .takeSuccess()!!
                    .contentPostOrNull()!!
                    .pluginWidgets.size,
            )

            val contextKey = MicroBlogKey("focal-ctx-2", accountKey.host)
            val contextFocal =
                createSamplePost(id = contextKey.id, host = contextKey.host, text = "Context focal")
                    .copy(accountType = accountType)
            attachDataSource(
                object : MicroblogDataSource by UnavailableMicroblogDataSource {
                    override suspend fun postDetailTabs(statusKey: MicroBlogKey): PostDetailTabs =
                        PostDetailTabs(
                            tabs =
                                listOf(
                                    PostDetailTab(
                                        id = "thread",
                                        title = UiText.Raw("Thread"),
                                        includesPostContext = true,
                                        loader = {
                                            object : RemoteLoader<UiTimelineV2> {
                                                override suspend fun load(
                                                    pageSize: Int,
                                                    request: PagingRequest,
                                                ): PagingResult<UiTimelineV2> =
                                                    PagingResult(
                                                        data = listOf(parentPost, contextFocal, replyPost),
                                                        endOfPaginationReached = true,
                                                    )
                                            }
                                        },
                                    ),
                                ),
                            replyTabId = "thread",
                        )
                },
            )
            fakeService.emitCards(parentPost, listOf(sampleCard("parent-card", "Parent Card")))
            fakeService.emitCards(contextFocal, listOf(sampleCard("ctx-focal-card", "Ctx Focal Card")))

            val contextState = observe(StatusContextPresenter(accountType, contextKey))
            val enrichedContext =
                contextState.await { state ->
                    state.current
                        .takeSuccess()
                        ?.contentPostOrNull()
                        ?.pluginWidgets
                        ?.map { it.id } == listOf("ctx-focal-card") &&
                        state.header.size == 2 &&
                        state.header[0]
                            .contentPostOrNull()
                            ?.pluginWidgets
                            ?.map { it.id } == listOf("parent-card") &&
                        state.header[1]
                            .contentPostOrNull()
                            ?.pluginWidgets
                            ?.map { it.id } == listOf("ctx-focal-card") &&
                        (state.listState as? PagingState.Success)
                            ?.peek(0)
                            ?.contentPostOrNull()
                            ?.pluginWidgets
                            ?.map { it.id } == listOf("reply-card")
                }
            assertEquals(2, enrichedContext.header.size)

            val legacyKey = MicroBlogKey("legacy-detail-3", accountKey.host)
            val legacyFocal =
                createSamplePost(id = legacyKey.id, host = legacyKey.host, text = "Legacy detail focal")
                    .copy(accountType = accountType)
            attachDataSource(
                object : MicroblogDataSource by UnavailableMicroblogDataSource {
                    override suspend fun postDetailTabs(statusKey: MicroBlogKey): PostDetailTabs? = null

                    override fun postDetailPost(statusKey: MicroBlogKey): Flow<UiState<UiTimelineV2>>? =
                        if (statusKey == legacyKey) flowOf(UiState.Success(legacyFocal)) else null
                },
            )
            val legacyState = observe(StatusContextPresenter(accountType, legacyKey))
            legacyState.await { state -> state.current.takeSuccess() != null }
            assertEquals(1, fakeService.submittedKeys.count { it == legacyKey })
            fakeService.emitCards(legacyFocal, listOf(sampleCard("legacy-card", "Legacy Card")))
            val enrichedLegacy =
                legacyState.await { state ->
                    state.current
                        .takeSuccess()
                        ?.contentPostOrNull()
                        ?.pluginWidgets
                        ?.map { it.id } == listOf("legacy-card")
                }
            assertEquals(
                listOf("legacy-card"),
                enrichedLegacy.current
                    .takeSuccess()!!
                    .contentPostOrNull()!!
                    .pluginWidgets
                    .map { it.id },
            )
        }

    @Test
    fun pullToRefreshInTimelineAndStatusDetailClearsTransientFailureCooldownAndRetriesImmediately() =
        withPresenterHarness {
            val timelinePost =
                createSamplePost(id = "cooldown-tl-1", host = accountKey.host, text = "Cooldown timeline post")
                    .copy(accountType = accountType)
            fakeService.markCoolingDown(timelinePost)
            fakeService.onSubmitActivePost = { post ->
                if (post.statusKey == timelinePost.statusKey) {
                    fakeService.emitCards(timelinePost, listOf(sampleCard("tl-recovered", "Recovered Timeline Card")))
                }
            }

            val loader =
                object : RemoteLoader<UiTimelineV2> {
                    override suspend fun load(
                        pageSize: Int,
                        request: PagingRequest,
                    ): PagingResult<UiTimelineV2> =
                        PagingResult(
                            data = listOf(timelinePost),
                            endOfPaginationReached = true,
                        )
                }
            val timelineStateFlow = observe(TimelinePresenter(loader = flowOf(loader)))
            val initialTimeline =
                timelineStateFlow.await { state ->
                    (state.listState as? PagingState.Success)?.itemCount == 1
                }
            assertTrue(
                (initialTimeline.listState as PagingState.Success)
                    .peek(0)!!
                    .contentPostOrNull()!!
                    .pluginWidgets
                    .isEmpty(),
            )
            assertEquals(0, fakeService.clearFailureCooldownCalls.get())

            initialTimeline.refresh()
            val recoveredTimeline =
                timelineStateFlow.await { state ->
                    (state.listState as? PagingState.Success)
                        ?.peek(0)
                        ?.contentPostOrNull()
                        ?.pluginWidgets
                        ?.map { it.id } == listOf("tl-recovered")
                }
            assertEquals(1, fakeService.clearFailureCooldownCalls.get())
            assertEquals(
                "Recovered Timeline Card",
                (recoveredTimeline.listState as PagingState.Success)
                    .peek(0)!!
                    .contentPostOrNull()!!
                    .pluginWidgets
                    .single()
                    .title,
            )

            // Also verify StatusContextPresenter refresh clears transient cooldown and re-submits focal post.
            val detailKey = MicroBlogKey("cooldown-detail-1", accountKey.host)
            val detailPost =
                createSamplePost(id = detailKey.id, host = detailKey.host, text = "Cooldown detail post")
                    .copy(accountType = accountType)
            fakeService.markCoolingDown(detailPost)
            fakeService.onSubmitActivePost = { post ->
                if (post.statusKey == detailKey) {
                    fakeService.emitCards(detailPost, listOf(sampleCard("detail-recovered", "Recovered Detail Card")))
                }
            }
            attachDataSource(
                object : MicroblogDataSource by UnavailableMicroblogDataSource {
                    override suspend fun postDetailTabs(statusKey: MicroBlogKey): PostDetailTabs =
                        PostDetailTabs(
                            tabs =
                                listOf(
                                    PostDetailTab(
                                        id = "replies",
                                        title = UiText.Raw("Replies"),
                                        includesPostContext = false,
                                        loader = {
                                            object : RemoteLoader<UiTimelineV2> {
                                                override suspend fun load(
                                                    pageSize: Int,
                                                    request: PagingRequest,
                                                ): PagingResult<UiTimelineV2> =
                                                    PagingResult(data = emptyList(), endOfPaginationReached = true)
                                            }
                                        },
                                    ),
                                ),
                            replyTabId = "replies",
                        )

                    override fun postDetailPost(statusKey: MicroBlogKey): Flow<UiState<UiTimelineV2>>? =
                        if (statusKey == detailKey) flowOf(UiState.Success(detailPost)) else null
                },
            )
            val detailStateFlow = observe(StatusContextPresenter(accountType, detailKey))
            val initialDetail =
                detailStateFlow.await { state ->
                    state.current.takeSuccess() != null
                }
            assertTrue(
                initialDetail.current
                    .takeSuccess()!!
                    .contentPostOrNull()!!
                    .pluginWidgets
                    .isEmpty(),
            )

            initialDetail.refresh()
            val recoveredDetail =
                detailStateFlow.await { state ->
                    state.current
                        .takeSuccess()
                        ?.contentPostOrNull()
                        ?.pluginWidgets
                        ?.map { it.id } == listOf("detail-recovered")
                }
            assertTrue(fakeService.clearFailureCooldownCalls.get() >= 2)
            assertEquals(
                "Recovered Detail Card",
                recoveredDetail.current
                    .takeSuccess()!!
                    .contentPostOrNull()!!
                    .pluginWidgets
                    .single()
                    .title,
            )
        }

    @Test
    fun rememberPluginWidgetBoundStatePassesThroughWhenServiceIsNull() =
        runTest {
            startKoin {
                modules(
                    module {
                        testSingle<PlatformFormatter> { TestFormatter() }
                    },
                )
            }
            try {
                val post = createSamplePost(id = "null-service-post")
                val initialCurrent: UiState<UiTimelineV2> = UiState.Success(post)
                val initialHeader: ImmutableList<UiTimelineV2> = persistentListOf(post)
                val pair =
                    moleculeFlow(RecompositionMode.Immediate) {
                        rememberPluginWidgetBoundState(initialCurrent, initialHeader, null)
                    }.first()
                assertSame(initialCurrent, pair.first)
                assertSame(initialHeader, pair.second)
            } finally {
                stopKoin()
            }
        }

    private fun sampleCard(
        id: String,
        title: String,
    ): UiPluginWidgetCard =
        UiPluginWidgetCard(
            pluginId = "org.example.plugin",
            pluginName = "Example",
            pluginIconPath = null,
            id = id,
            title = title,
            body = persistentListOf(UiWidgetNode.Text(title)),
        )

    private fun withPresenterHarness(block: suspend PresenterHarness.() -> Unit) =
        runBlocking {
            val main = Executors.newSingleThreadExecutor { Thread(it, "plugin-widget-presenter-test") }.asCoroutineDispatcher()
            Dispatchers.setMain(main)
            val harness = PresenterHarness(CoroutineScope(main + SupervisorJob()))
            try {
                withContext(main) {
                    withTimeout(15_000) {
                        harness.accountRepository.addAccount(UiAccount(harness.accountKey, TEST_PLATFORM), "credential")
                        harness.block()
                    }
                }
            } finally {
                harness.close()
                Dispatchers.resetMain()
                main.close()
            }
        }

    private inner class PresenterHarness(
        val scope: CoroutineScope,
    ) {
        val accountKey = MicroBlogKey("alice", "pluginwidget.example")
        val accountType = AccountType.Specific(accountKey)
        val root = createTestRootPath()
        val storage = OkioFileStorage(createTestFileSystem(), root)
        val appDb =
            Room
                .memoryDatabaseBuilder<AppDatabase>()
                .setDriver(createDatabaseDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        val cacheDb =
            Room
                .memoryDatabaseBuilder<CacheDatabase>()
                .setDriver(createDatabaseDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        val appData = AppDataStore(storage)
        val registry = testPlatformRegistry()
        val drafts = DraftRepository(appDb, DraftMediaStore(storage))
        val accountRepository =
            AccountRepository(
                appDb,
                scope,
                appData,
                cacheDb,
                ProxyRepository(storage),
                registry,
                drafts,
            )
        val accountService: AccountService = RepositoryAccountService(accountRepository)
        val resolver = TimelineResolver(testPlatformRuntimeData(), unavailableAccountService())
        val settingsRepository = SettingsRepository(storage, appData, resolver, cacheDb)
        val presenterFactory = TimelinePresenterFactory(resolver, settingsRepository)
        val localFilterRepository = LocalFilterRepository(appDb, scope)
        val mxgaRepository = MxgaRepository(storage, settingsRepository, scope)
        val fakeService = FakeDelayedTimelineProcessorService()

        init {
            startKoin {
                modules(
                    module {
                        testSingle { appDb }
                        testSingle { cacheDb }
                        testSingle { appData }
                        testSingle { settingsRepository }
                        testSingle { presenterFactory }
                        testSingle { HomeTimelineRefreshSessions() }
                        testSingle { accountRepository }
                        testSingle<AccountService> { accountService }
                        testSingle<CoroutineScope> { scope }
                        testSingle<PreTranslationService> { NoopPreTranslationService }
                        testSingle { localFilterRepository }
                        testSingle { mxgaRepository }
                        testSingle<PlatformRegistry> { registry }
                        testSingle<InAppNotification> { NoopInAppNotification }
                        testSingle<PlatformFormatter> { TestFormatter() }
                        testSingle<TimelineProcessorService> { fakeService }
                    },
                )
            }
        }

        fun attachDataSource(source: MicroblogDataSource) {
            registry.replaceExternal(
                TEST_PLATFORM,
                object : PlatformSpec by registry.defaultGuest {
                    override val platformId: String = TEST_PLATFORM
                    override val isDefaultGuest: Boolean = false

                    override fun createDataSource(context: PlatformDataSourceContext): MicroblogDataSource = source
                },
            )
        }

        fun <T : Any> observe(presenter: PresenterBase<T>): MutableStateFlow<T?> {
            val state = MutableStateFlow<T?>(null)
            scope.launch {
                moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.collect { state.value = it }
            }
            return state
        }

        suspend fun <T : Any> MutableStateFlow<T?>.await(predicate: (T) -> Boolean): T =
            withTimeout(5_000) { filterNotNull().first(predicate) }

        suspend fun close() {
            scope.coroutineContext.job.cancelAndJoin()
            appDb.close()
            cacheDb.close()
            stopKoin()
            deleteTestRootPath(root)
        }
    }

    private object NoopInAppNotification : InAppNotification {
        override fun onProgress(
            message: Message,
            progress: Int,
            total: Int,
        ) = Unit

        override fun onSuccess(message: Message) = Unit

        override fun onError(
            message: Message,
            throwable: Throwable,
        ) = Unit
    }

    private companion object {
        const val TEST_PLATFORM = "PluginWidgetPresenterTestPlatform"
    }
}

private class FakeDelayedTimelineProcessorService : TimelineProcessorService {
    private val revision = MutableStateFlow(0L)
    override val overlayRevision: StateFlow<Long> = revision.asStateFlow()
    private val processing = MutableStateFlow(0L)
    override val processingRevision: StateFlow<Long> = processing.asStateFlow()

    private val cardsByEntityKey = MutableStateFlow<Map<String, ImmutableList<UiPluginWidgetCard>>>(emptyMap())
    private val coolingDownEntityKeys = MutableStateFlow<Set<String>>(emptySet())
    val submittedKeys = CopyOnWriteArrayList<MicroBlogKey>()
    val clearFailureCooldownCalls = AtomicInteger(0)
    var onSubmitActivePost: ((UiTimelineV2.Post) -> Unit)? = null

    override fun submitPosts(posts: List<UiTimelineV2.Post>) {
        val coolingDown = coolingDownEntityKeys.value
        val existingCards = cardsByEntityKey.value
        posts.forEach { post ->
            submittedKeys.add(post.statusKey)
            val key = post.pluginPostEntityKey()
            if (key !in coolingDown && key !in existingCards) {
                onSubmitActivePost?.invoke(post)
            }
        }
    }

    override fun widgetsForPost(post: UiTimelineV2.Post): ImmutableList<UiPluginWidgetCard> =
        cardsByEntityKey.value[post.pluginPostEntityKey()] ?: persistentListOf()

    fun markCoolingDown(post: UiTimelineV2.Post) {
        coolingDownEntityKeys.update { it + post.pluginPostEntityKey() }
    }

    fun invalidateProcessing() {
        processing.update { it + 1L }
        revision.update { it + 1L }
    }

    fun emitCards(
        post: UiTimelineV2.Post,
        cards: List<UiPluginWidgetCard>,
    ) {
        cardsByEntityKey.update { current ->
            current + (post.pluginPostEntityKey() to cards.toImmutableList())
        }
        revision.update { it + 1L }
    }

    override fun refreshPostPlugin(
        post: UiTimelineV2.Post,
        pluginId: String,
    ) = Unit

    override fun toggleCollapsible(
        postEntityKey: String,
        pluginId: String,
        cardId: String,
        collapsibleId: String,
    ) = Unit

    override fun clearPlugin(pluginId: String) = Unit

    override fun clearFailureCooldowns() {
        clearFailureCooldownCalls.incrementAndGet()
        var clearedAny = false
        coolingDownEntityKeys.update { current ->
            clearedAny = current.isNotEmpty()
            emptySet()
        }
        if (clearedAny) {
            invalidateProcessing()
        }
    }
}
