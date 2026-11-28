package dev.dimension.flare.ui.presenter.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.room3.Room
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import dev.dimension.flare.createTestFileSystem
import dev.dimension.flare.createTestRootPath
import dev.dimension.flare.data.database.app.AppDatabase
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.cache.mapper.toDbUser
import dev.dimension.flare.data.database.cache.mapper.upsertUser
import dev.dimension.flare.data.database.createDatabaseDriver
import dev.dimension.flare.data.datasource.microblog.ActionMenu
import dev.dimension.flare.data.datasource.microblog.DataSourceCapabilityProvider
import dev.dimension.flare.data.datasource.microblog.DataSourceCapabilitySet
import dev.dimension.flare.data.datasource.microblog.DirectMessageDataSource
import dev.dimension.flare.data.datasource.microblog.MicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.ProfileCapability
import dev.dimension.flare.data.datasource.microblog.ProfileTab
import dev.dimension.flare.data.datasource.microblog.UnavailableMicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.datasource.ListDataSource
import dev.dimension.flare.data.datasource.microblog.datasource.RelationDataSource
import dev.dimension.flare.data.datasource.microblog.datasource.UserDataSource
import dev.dimension.flare.data.datasource.microblog.handler.DirectMessageHandler
import dev.dimension.flare.data.datasource.microblog.handler.ListHandler
import dev.dimension.flare.data.datasource.microblog.handler.ListMemberHandler
import dev.dimension.flare.data.datasource.microblog.handler.RelationHandler
import dev.dimension.flare.data.datasource.microblog.handler.UserHandler
import dev.dimension.flare.data.datasource.microblog.loader.DirectMessageDelta
import dev.dimension.flare.data.datasource.microblog.loader.DirectMessageLoader
import dev.dimension.flare.data.datasource.microblog.loader.RelationActionType
import dev.dimension.flare.data.datasource.microblog.loader.RelationLoader
import dev.dimension.flare.data.datasource.microblog.loader.UserLoader
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.io.OkioFileStorage
import dev.dimension.flare.data.repository.AccountRepository
import dev.dimension.flare.data.repository.AccountService
import dev.dimension.flare.data.repository.DraftMediaStore
import dev.dimension.flare.data.repository.DraftRepository
import dev.dimension.flare.data.repository.ProxyRepository
import dev.dimension.flare.data.repository.RepositoryAccountService
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
import dev.dimension.flare.model.PlatformRuntimeData
import dev.dimension.flare.model.PlatformSpec
import dev.dimension.flare.testPlatformRuntimeData
import dev.dimension.flare.ui.model.UiAccount
import dev.dimension.flare.ui.model.UiDMItem
import dev.dimension.flare.ui.model.UiDMRoom
import dev.dimension.flare.ui.model.UiHandle
import dev.dimension.flare.ui.model.UiList
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.UiRelation
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.createSampleUser
import dev.dimension.flare.ui.presenter.PresenterBase
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dev.dimension.flare.data.datasource.microblog.ActionMenu.Item.Text.Localized.Type as MenuType

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePresenterMenuDelayTest {
    private val accountKey = MicroBlogKey("alice", "profile.example")
    private val accountType = AccountType.Specific(accountKey)
    private val targetKey = MicroBlogKey("bob", "profile.example")

    @Test
    fun menuAppearsOnInitialFrameWhileNetworkRequestsArePendingAndAddsSendMessageWhenResolved() =
        withFixture {
            val canSendStarted = CompletableDeferred<Unit>()
            val canSendResult = CompletableDeferred<Boolean>()
            val userStarted = CompletableDeferred<Unit>()
            val userResult = CompletableDeferred<UiProfile>()
            val relationStarted = CompletableDeferred<Unit>()
            val relationResult = CompletableDeferred<UiRelation>()
            var shouldFailCanSendRefresh = false

            val source =
                TestProfileSource(
                    onCanSend = {
                        canSendStarted.complete(Unit)
                        if (shouldFailCanSendRefresh) {
                            throw IllegalStateException("transient network error")
                        }
                        canSendResult.await()
                    },
                    onUserById = { id ->
                        userStarted.complete(Unit)
                        userResult.await().copy(key = MicroBlogKey(id, accountKey.host))
                    },
                    onRelation = {
                        relationStarted.complete(Unit)
                        relationResult.await()
                    },
                )
            attach(source)
            warmDataSource()

            val presenter = ProfilePresenter(accountType, targetKey)
            val states = MutableStateFlow<ProfileState?>(null)
            val firstFrameDeferred = CompletableDeferred<ProfileState>()
            val observerJob =
                scope.launch {
                    moleculeFlow(RecompositionMode.Immediate) {
                        presenter.body()
                    }.collect { emitted ->
                        firstFrameDeferred.complete(emitted)
                        states.value = emitted
                    }
                }

            val firstFrame = firstFrameDeferred.await()
            assertTrue(firstFrame.relationState is UiState.Loading)
            assertEquals(listOf(MenuType.EditUserList, MenuType.Report), firstFrame.actions.localizedTypes())

            canSendStarted.await()
            userStarted.await()
            relationStarted.await()
            assertEquals(1, source.userByIdCalls.get())
            assertEquals(1, source.relationCalls.get())
            assertEquals(1, source.canSendCalls.get())

            canSendResult.complete(true)
            val withSendMessage =
                states.await { MenuType.SendMessage in it.actions.localizedTypes() }
            assertEquals(
                listOf(MenuType.EditUserList, MenuType.SendMessage, MenuType.Report),
                withSendMessage.actions.localizedTypes(),
            )

            relationResult.complete(UiRelation(blocking = true, muted = true))
            val blocked = states.await { it.relationState is UiState.Success }
            assertEquals(
                listOf(MenuType.EditUserList, MenuType.SendMessage, MenuType.UnBlock, MenuType.UnMute, MenuType.Report),
                blocked.actions.localizedTypes(),
            )
            userResult.complete(createSampleUser().copy(key = targetKey))
            states.await { it.userState is UiState.Success }
            observerJob.cancelAndJoin()

            // Re-entering profile includes SendMessage on initial synchronous frame and preserves it on transient refresh failure
            shouldFailCanSendRefresh = true
            val revisitPresenter = ProfilePresenter(accountType, targetKey)
            val revisitFirstFrame =
                moleculeFlow(RecompositionMode.Immediate) {
                    revisitPresenter.body()
                }.first()
            assertTrue(MenuType.SendMessage in revisitFirstFrame.actions.localizedTypes())

            val revisitStates = observe(ProfilePresenter(accountType, targetKey))
            revisitStates.await { source.canSendCalls.get() >= 2 && it.userState is UiState.Success }
            assertTrue(MenuType.SendMessage in revisitStates.value!!.actions.localizedTypes())
        }

    @Test
    fun replacingLiveDataSourceClearsSendMessageWhileNewPermissionIsPending() =
        withFixture {
            fun source(
                platformId: String,
                canSend: suspend (MicroBlogKey) -> Boolean,
            ) = TestProfileSource(
                onCanSend = canSend,
                onUserById = { createSampleUser().copy(key = targetKey, platformId = platformId) },
                onRelation = { UiRelation() },
            )

            attach(source("First") { true })
            warmDataSource()
            val states = observe(ProfilePresenter(accountType, targetKey))
            val first = states.await { MenuType.SendMessage in it.actions.localizedTypes() && it.relationState is UiState.Success }
            assertEquals(
                listOf(MenuType.EditUserList, MenuType.SendMessage, MenuType.Block, MenuType.Mute, MenuType.Report),
                first.actions.localizedTypes(),
            )

            val pending = CompletableDeferred<Boolean>()
            val started = CompletableDeferred<Unit>()
            attach(
                source("Replacement") {
                    started.complete(Unit)
                    pending.await()
                },
            )
            started.await()
            val replacement = states.await { (it.userState as? UiState.Success)?.data?.platformId == "Replacement" }
            assertFalse(MenuType.SendMessage in replacement.actions.localizedTypes())
            pending.complete(true)
            states.await { MenuType.SendMessage in it.actions.localizedTypes() }
        }

    @Test
    fun switchingProfilesAtTheSameCallSiteResetsActionsAndRecordsTheNewVisit() =
        withFixture {
            val nextKey = MicroBlogKey("carol", accountKey.host)
            val pending = CompletableDeferred<Boolean>()
            attach(
                TestProfileSource(
                    onCanSend = { if (it == nextKey) pending.await() else true },
                    onUserById = { id ->
                        createSampleUser().copy(key = MicroBlogKey(id, accountKey.host), handle = UiHandle(id, accountKey.host))
                    },
                    onRelation = {
                        if (it == nextKey) pending.await()
                        UiRelation(blocking = it == targetKey)
                    },
                ),
            )
            warmDataSource()
            val presenter = mutableStateOf(ProfilePresenter(accountType, targetKey))
            val states = observe { presenter.value.body() }
            states.await {
                val actions = it.actions.localizedTypes()
                it.userState is UiState.Success && MenuType.SendMessage in actions && MenuType.UnBlock in actions
            }
            val visits =
                cache.invalidationTracker.createFlow("DbUserHistory").map {
                    cache
                        .userDao()
                        .getUserHistory(limit = 2)
                        .map { it.data.accountType to it.data.userKey }
                        .toSet()
                }
            assertEquals(setOf(accountType to targetKey), visits.first { it.isNotEmpty() })

            presenter.value = ProfilePresenter(accountType, nextKey)
            val next = states.await { (it.userState as? UiState.Success)?.data?.key == nextKey }
            assertTrue(next.relationState is UiState.Loading)
            assertEquals(listOf(MenuType.EditUserList, MenuType.Report), next.actions.localizedTypes())
            pending.complete(true)
            states.await { MenuType.SendMessage in it.actions.localizedTypes() && MenuType.Block in it.actions.localizedTypes() }
            assertEquals(setOf(accountType to targetKey, accountType to nextKey), visits.first { it.size == 2 })
        }

    @Test
    fun ownProfileHidesMenuAndNeverChecksCanSend() =
        withFixture {
            val source =
                TestProfileSource(
                    onCanSend = { true },
                    onUserById = { id -> createSampleUser().copy(key = MicroBlogKey(id, accountKey.host)) },
                    onRelation = { UiRelation() },
                )
            attach(source)
            warmDataSource()

            val nullKeyState = observe(ProfilePresenter(accountType, userKey = null))
            val resolvedNullKey = nullKeyState.await { it.userState is UiState.Success && it.isMe is UiState.Success }
            assertTrue(resolvedNullKey.actions.isEmpty())
            assertEquals(0, source.canSendCalls.get())

            val explicitSelfState = observe(ProfilePresenter(accountType, userKey = accountKey))
            val resolvedSelfKey = explicitSelfState.await { it.userState is UiState.Success && it.isMe is UiState.Success }
            assertTrue(resolvedSelfKey.actions.isEmpty())
            assertEquals(0, source.canSendCalls.get())
        }

    @Test
    fun uncachedUserErrorAndPlatformDetachClearMenuWhileCachedUserOfflineKeepsMenu() =
        withFixture {
            val uncachedErrorSource =
                TestProfileSource(
                    onCanSend = { false },
                    onUserById = { throw IllegalStateException("404 not found") },
                    onRelation = { throw IllegalStateException("relation unavailable") },
                )
            attach(uncachedErrorSource)
            warmDataSource()

            val uncachedInitialPresenter = ProfilePresenter(accountType, targetKey)
            val uncachedFirst =
                moleculeFlow(RecompositionMode.Immediate) {
                    uncachedInitialPresenter.body()
                }.first()
            assertFalse(uncachedFirst.actions.isEmpty())

            val uncachedObserved = observe(ProfilePresenter(accountType, targetKey))
            val uncachedErrored = uncachedObserved.await { it.userState is UiState.Error }
            assertTrue(uncachedErrored.actions.isEmpty())

            // Cached user offline: userState stays Success from Room cache even when userById throws
            val cachedUserKey = MicroBlogKey("cached-bob", accountKey.host)
            cache.upsertUser(createSampleUser().copy(key = cachedUserKey).toDbUser())

            val cachedObserved = observe(ProfilePresenter(accountType, cachedUserKey))
            val cachedResolved = cachedObserved.await { it.userState is UiState.Success && it.relationState is UiState.Error }
            assertEquals(listOf(MenuType.EditUserList, MenuType.Report), cachedResolved.actions.localizedTypes())

            // Detaching platform clears menu
            detach()
            val detached = cachedObserved.await { it.userState is UiState.Error && it.actions.isEmpty() }
            assertTrue(detached.actions.isEmpty())
        }

    @Test
    fun guestProfileUsesOneDataSourceAndRendersReportImmediately() =
        withFixture {
            val pendingGuestUser = CompletableDeferred<UiProfile>()
            guestSource =
                object : MicroblogDataSource by UnavailableMicroblogDataSource, DataSourceCapabilityProvider {
                    override val capabilitySet: DataSourceCapabilitySet =
                        DataSourceCapabilitySet(
                            profile =
                                ProfileCapability(
                                    this,
                                    object : UserDataSource {
                                        override val userHandler =
                                            UserHandler(
                                                targetKey.host,
                                                object : UserLoader {
                                                    override suspend fun userByHandleAndHost(uiHandle: UiHandle): UiProfile =
                                                        pendingGuestUser.await()

                                                    override suspend fun userById(id: String): UiProfile = pendingGuestUser.await()
                                                },
                                            )
                                    },
                                ),
                            relation =
                                object : RelationDataSource {
                                    override val supportedRelationTypes: Set<RelationActionType> =
                                        RelationActionType.entries.toSet()
                                    override val relationHandler: RelationHandler
                                        get() = error("Guest relation handler is unused when unauthenticated")
                                },
                        )
                }
            val guestType = AccountType.GuestHost(targetKey.host)
            val guestPresenter = ProfilePresenter(guestType, targetKey)
            val firstFrame = CompletableDeferred<ProfileState>()
            val resolved =
                moleculeFlow(RecompositionMode.Immediate) {
                    guestPresenter.body()
                }.first { state ->
                    if (firstFrame.complete(state)) {
                        pendingGuestUser.complete(createSampleUser().copy(key = targetKey))
                    }
                    state.userState is UiState.Success
                }
            val first = firstFrame.await()
            assertTrue(first.userState is UiState.Loading)
            assertEquals(listOf(MenuType.Report), first.actions.localizedTypes())
            assertEquals(listOf(MenuType.Report), resolved.actions.localizedTypes())
            assertEquals(1, guestCreations)
        }

    private fun List<ActionMenu>.localizedTypes(): List<MenuType> {
        val group = singleOrNull() as? ActionMenu.Group ?: return emptyList()
        return group.actions
            .filterIsInstance<ActionMenu.Item>()
            .mapNotNull { (it.text as? ActionMenu.Item.Text.Localized)?.type }
    }

    private fun withFixture(block: suspend Fixture.() -> Unit) =
        runBlocking {
            val main = Executors.newSingleThreadExecutor { Thread(it, "profile-menu-delay") }.asCoroutineDispatcher()
            Dispatchers.setMain(main)
            val fixture = Fixture(CoroutineScope(main + SupervisorJob()))
            try {
                withContext(main) {
                    withTimeout(15_000) {
                        fixture.repository.addAccount(UiAccount(accountKey, PLATFORM), "credential")
                        fixture.block()
                    }
                }
            } finally {
                fixture.close()
                Dispatchers.resetMain()
                main.close()
            }
        }

    private inner class Fixture(
        val scope: CoroutineScope,
    ) {
        val root = createTestRootPath()
        val storage = OkioFileStorage(createTestFileSystem(), root)
        val app = Room.memoryDatabaseBuilder<AppDatabase>().setDriver(createDatabaseDriver()).build()
        val cache = Room.memoryDatabaseBuilder<CacheDatabase>().setDriver(createDatabaseDriver()).build()
        val appData = AppDataStore(storage)
        var guestSource: MicroblogDataSource = UnavailableMicroblogDataSource
        var guestCreations = 0
        val registry =
            PlatformRegistry(
                PlatformRuntimeData(
                    platformSpecs =
                        testPlatformRuntimeData().platformSpecs.map { spec ->
                            if (spec.isDefaultGuest) {
                                object : PlatformSpec by spec {
                                    override fun guestDataSource(
                                        host: String,
                                        locale: String,
                                    ): MicroblogDataSource {
                                        guestCreations++
                                        return guestSource
                                    }
                                }
                            } else {
                                spec
                            }
                        },
                    extraTimelineSpecs = emptyList(),
                ),
            )
        val drafts = DraftRepository(app, DraftMediaStore(storage))
        val repository =
            AccountRepository(
                app,
                scope,
                appData,
                cache,
                ProxyRepository(storage),
                registry,
                drafts,
            )

        init {
            startKoin {
                modules(
                    module {
                        testSingle { cache }
                        testSingle { appData }
                        testSingle { repository }
                        testSingle<CoroutineScope> { scope }
                        testSingle<AccountService> { RepositoryAccountService(repository) }
                        testSingle<PreTranslationService> { NoopPreTranslationService }
                    },
                )
            }
        }

        fun attach(source: MicroblogDataSource) {
            registry.replaceExternal(
                PLATFORM,
                object : PlatformSpec by registry.defaultGuest {
                    override val platformId: String = PLATFORM
                    override val isDefaultGuest: Boolean = false

                    override fun createDataSource(context: PlatformDataSourceContext): MicroblogDataSource = source
                },
            )
        }

        fun detach() = registry.replaceExternal(PLATFORM, null)

        suspend fun warmDataSource() {
            val account = requireNotNull(repository.find(accountKey))
            repository.getOrCreateDataSource(account)
        }

        fun <T : Any> observe(presenter: PresenterBase<T>): MutableStateFlow<T?> = observe { presenter.body() }

        fun <T : Any> observe(body: @Composable () -> T): MutableStateFlow<T?> {
            val state = MutableStateFlow<T?>(null)
            scope.launch {
                moleculeFlow(RecompositionMode.Immediate, body = body).collect { state.value = it }
            }
            return state
        }

        suspend fun close() {
            scope.coroutineContext.job.cancelAndJoin()
            app.close()
            cache.close()
            stopKoin()
            deleteTestRootPath(root)
        }
    }

    private inner class TestProfileSource(
        private val onCanSend: suspend (MicroBlogKey) -> Boolean,
        private val onUserById: suspend (String) -> UiProfile,
        private val onRelation: suspend (MicroBlogKey) -> UiRelation,
    ) : MicroblogDataSource by UnavailableMicroblogDataSource,
        DataSourceCapabilityProvider {
        val canSendCalls = AtomicInteger(0)
        val userByIdCalls = AtomicInteger(0)
        val relationCalls = AtomicInteger(0)

        override val authenticatedAccountKey: MicroBlogKey = accountKey
        override val authenticatedProfile: UiProfile = createSampleUser().copy(key = accountKey)

        private val emptyTimeline =
            object : RemoteLoader<UiTimelineV2> {
                override suspend fun load(
                    pageSize: Int,
                    request: PagingRequest,
                ): PagingResult<UiTimelineV2> = PagingResult(data = emptyList(), endOfPaginationReached = true)
            }

        private val dmHandler =
            DirectMessageHandler(
                accountKey = accountKey,
                loader =
                    object : DirectMessageLoader {
                        override val platformId: String = PLATFORM

                        override suspend fun loadRooms(
                            pageSize: Int,
                            request: PagingRequest,
                        ): PagingResult<UiDMRoom> = error("unused")

                        override suspend fun loadMessages(
                            roomKey: MicroBlogKey,
                            pageSize: Int,
                            request: PagingRequest,
                        ): PagingResult<UiDMItem> = error("unused")

                        override suspend fun fetchRoomInfo(roomKey: MicroBlogKey): UiDMRoom? = error("unused")

                        override suspend fun sendMessage(
                            roomKey: MicroBlogKey,
                            message: String,
                        ): UiDMItem = error("unused")

                        override suspend fun deleteMessage(
                            roomKey: MicroBlogKey,
                            messageKey: MicroBlogKey,
                        ): Unit = error("unused")

                        override suspend fun fetchNewMessages(
                            roomKey: MicroBlogKey,
                            cursor: String?,
                        ): DirectMessageDelta = error("unused")

                        override suspend fun leaveRoom(roomKey: MicroBlogKey): Unit = error("unused")

                        override fun createRoom(userKey: MicroBlogKey): Flow<UiState<UiDMRoom>> = error("unused")

                        override suspend fun canSend(userKey: MicroBlogKey): Boolean {
                            canSendCalls.incrementAndGet()
                            return onCanSend(userKey)
                        }

                        override suspend fun loadBadgeCount(): Int = 0
                    },
                coroutineScope = CoroutineScope(SupervisorJob()),
            )

        override val capabilitySet: DataSourceCapabilitySet =
            DataSourceCapabilitySet(
                profile =
                    ProfileCapability(
                        this,
                        object : UserDataSource {
                            override val userHandler =
                                UserHandler(
                                    accountKey.host,
                                    object : UserLoader {
                                        override suspend fun userByHandleAndHost(uiHandle: UiHandle): UiProfile = onUserById(uiHandle.raw)

                                        override suspend fun userById(id: String): UiProfile {
                                            userByIdCalls.incrementAndGet()
                                            return onUserById(id)
                                        }
                                    },
                                )
                        },
                    ),
                relation =
                    object : RelationDataSource {
                        override val supportedRelationTypes: Set<RelationActionType> =
                            setOf(RelationActionType.Follow, RelationActionType.Block, RelationActionType.Mute)
                        override val relationHandler: RelationHandler =
                            RelationHandler(
                                accountType = accountType,
                                dataSource =
                                    object : RelationLoader {
                                        override val supportedTypes: Set<RelationActionType> = supportedRelationTypes

                                        override suspend fun relation(userKey: MicroBlogKey): UiRelation {
                                            relationCalls.incrementAndGet()
                                            return onRelation(userKey)
                                        }

                                        override suspend fun follow(userKey: MicroBlogKey) = Unit

                                        override suspend fun unfollow(userKey: MicroBlogKey) = Unit

                                        override suspend fun block(userKey: MicroBlogKey) = Unit

                                        override suspend fun unblock(userKey: MicroBlogKey) = Unit

                                        override suspend fun mute(userKey: MicroBlogKey) = Unit

                                        override suspend fun unmute(userKey: MicroBlogKey) = Unit
                                    },
                            )
                    },
                list =
                    object : ListDataSource {
                        override fun listTimeline(listId: String): RemoteLoader<UiTimelineV2> = emptyTimeline

                        override val listHandler: ListHandler<UiList.List> get() = error("unused")
                        override val listMemberHandler: ListMemberHandler get() = error("unused")
                    },
                directMessage =
                    object : DirectMessageDataSource, MicroblogDataSource by this {
                        override val accountKey: MicroBlogKey = this@ProfilePresenterMenuDelayTest.accountKey
                        override val directMessageHandler: DirectMessageHandler = dmHandler
                    },
            )

        override suspend fun profileTabs(userKey: MicroBlogKey) =
            persistentListOf(
                ProfileTab(name = UiText.Raw("Posts"), loader = emptyTimeline, id = "posts"),
            )
    }

    private suspend fun <T : Any> MutableStateFlow<T?>.await(predicate: (T) -> Boolean): T =
        withTimeout(5_000) { filterNotNull().first(predicate) }

    private companion object {
        const val PLATFORM = "ProfileMenuDelay"
    }
}
