package dev.dimension.flare.ui.presenter.profile

import androidx.compose.runtime.BroadcastFrameClock
import androidx.room3.Room
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import dev.dimension.flare.RobolectricTest
import dev.dimension.flare.common.OnDeviceAI
import dev.dimension.flare.common.ShareUrlPolicy
import dev.dimension.flare.common.TestFormatter
import dev.dimension.flare.createTestFileSystem
import dev.dimension.flare.createTestRootPath
import dev.dimension.flare.data.database.app.AppDatabase
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.createDatabaseDriver
import dev.dimension.flare.data.datasource.microblog.DataSourceCapabilityProvider
import dev.dimension.flare.data.datasource.microblog.DataSourceCapabilitySet
import dev.dimension.flare.data.datasource.microblog.MicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.ProfileCapability
import dev.dimension.flare.data.datasource.microblog.ProfileTab
import dev.dimension.flare.data.datasource.microblog.datasource.UserDataSource
import dev.dimension.flare.data.datasource.microblog.handler.UserHandler
import dev.dimension.flare.data.datasource.microblog.loader.UserLoader
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.io.OkioFileStorage
import dev.dimension.flare.data.model.tab.TimelineSpec
import dev.dimension.flare.data.network.ai.AiCompletionService
import dev.dimension.flare.data.network.ai.OpenAIService
import dev.dimension.flare.data.repository.AccountRepository
import dev.dimension.flare.data.repository.DraftMediaStore
import dev.dimension.flare.data.repository.DraftRepository
import dev.dimension.flare.data.repository.NoActiveAccountException
import dev.dimension.flare.data.repository.ProxyRepository
import dev.dimension.flare.data.translation.OnlinePreTranslationService
import dev.dimension.flare.data.translation.PreTranslationService
import dev.dimension.flare.deleteTestRootPath
import dev.dimension.flare.di.startKoin
import dev.dimension.flare.di.testSingle
import dev.dimension.flare.memoryDatabaseBuilder
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.PlatformDataSourceContext
import dev.dimension.flare.model.PlatformDeepLink
import dev.dimension.flare.model.PlatformMetadata
import dev.dimension.flare.model.PlatformRegistry
import dev.dimension.flare.model.PlatformRuntimeData
import dev.dimension.flare.model.PlatformSpec
import dev.dimension.flare.ui.humanizer.PlatformFormatter
import dev.dimension.flare.ui.model.UiAccount
import dev.dimension.flare.ui.model.UiHandle
import dev.dimension.flare.ui.model.UiHashtag
import dev.dimension.flare.ui.model.UiIcon
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.asType
import dev.dimension.flare.ui.model.createSampleUser
import dev.dimension.flare.ui.model.takeSuccess
import dev.dimension.flare.ui.render.toUiPlainText
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.Path
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSharePresenterTest : RobolectricTest() {
    private lateinit var root: Path
    private lateinit var appDb: AppDatabase
    private lateinit var db: CacheDatabase
    private lateinit var appDataStore: AppDataStore
    private lateinit var fileStorage: OkioFileStorage
    private lateinit var scope: CoroutineScope
    private lateinit var accounts: AccountRepository
    private var platformServiceFactory: () -> MicroblogDataSource = {
        error("Platform service factory not configured for this test")
    }

    private val accountKey = MicroBlogKey(id = "me", host = "example.org")
    private val otherKey = MicroBlogKey(id = "other", host = "example.org")

    @BeforeTest
    fun setup() {
        root = createTestRootPath()
        fileStorage = OkioFileStorage(createTestFileSystem(), root)
        appDb =
            Room
                .memoryDatabaseBuilder<AppDatabase>()
                .setDriver(createDatabaseDriver())
                .setQueryCoroutineContext(Dispatchers.Unconfined)
                .build()
        db =
            Room
                .memoryDatabaseBuilder<CacheDatabase>()
                .setDriver(createDatabaseDriver())
                .setQueryCoroutineContext(Dispatchers.Unconfined)
                .build()
        appDataStore = AppDataStore(fileStorage)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val openAIService = OpenAIService()
        val onDeviceAI =
            object : OnDeviceAI {
                override suspend fun isAvailable(): Boolean = false

                override suspend fun translate(
                    source: String,
                    targetLanguage: String,
                    prompt: String,
                ): String? = null

                override suspend fun tldr(
                    source: String,
                    targetLanguage: String,
                    prompt: String,
                ): String? = null
            }
        val aiCompletionService = AiCompletionService(openAIService, onDeviceAI)
        val fakePlatformSpec =
            object : PlatformSpec {
                override val platformId: String = "Mastodon"
                override val metadata: PlatformMetadata =
                    PlatformMetadata(
                        displayName = "Mastodon",
                        icon = UiIcon.Mastodon.asType(),
                    )
                override val isDefaultGuest: Boolean = true
                override val timelineSpecs: ImmutableList<TimelineSpec<out TimelineSpec.Data>> = persistentListOf()

                override fun deepLinks(accountKey: MicroBlogKey): ImmutableList<PlatformDeepLink<*>> = persistentListOf()

                override fun createDataSource(context: PlatformDataSourceContext): MicroblogDataSource = platformServiceFactory()

                override fun guestDataSource(
                    host: String,
                    locale: String,
                ): MicroblogDataSource = platformServiceFactory()
            }
        accounts =
            AccountRepository(
                appDatabase = appDb,
                coroutineScope = scope,
                appDataStore = appDataStore,
                cacheDatabase = db,
                proxyRepository = ProxyRepository(fileStorage),
                platformRegistry =
                    PlatformRegistry(
                        PlatformRuntimeData(
                            platformSpecs = listOf(fakePlatformSpec),
                            extraTimelineSpecs = emptyList(),
                        ),
                    ),
                draftRepository = DraftRepository(appDb, DraftMediaStore(fileStorage)),
            )
        startKoin {
            modules(
                module {
                    testSingle { db }
                    testSingle { appDataStore }
                    testSingle<CoroutineScope> { scope }
                    testSingle<OnDeviceAI> { onDeviceAI }
                    testSingle { openAIService }
                    testSingle { aiCompletionService }
                    testSingle<PreTranslationService> {
                        OnlinePreTranslationService(db, appDataStore, aiCompletionService, scope)
                    }
                    testSingle<PlatformFormatter> { TestFormatter() }
                    testSingle { accounts }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        db.close()
        appDb.close()
        stopKoin()
        deleteTestRootPath(root)
    }

    @Test
    fun `null or own userKey loads accountProfileFlow`() =
        runTest {
            val ownProfile =
                createSampleUser().copy(
                    key = accountKey,
                    nameInternal = "Self Profile".toUiPlainText(),
                    url = "https://example.org/@me",
                )
            val service =
                FakeProfileService(
                    accountKey = accountKey,
                    authenticatedProfile = ownProfile,
                    userHandler = null,
                )

            val nullKeyProfile = resolveProfileShareFlow(service, null).first { it is UiState.Success<*> }
            val ownKeyProfile = resolveProfileShareFlow(service, accountKey).first { it is UiState.Success<*> }

            assertEquals(accountKey, nullKeyProfile.takeSuccess()?.key)
            assertEquals("https://example.org/@me", nullKeyProfile.takeSuccess()?.url)
            assertEquals(accountKey, ownKeyProfile.takeSuccess()?.key)
            assertEquals("https://example.org/@me", ownKeyProfile.takeSuccess()?.url)
        }

    @Test
    fun `other userKey loads userHandler userById`() =
        runTest {
            val otherProfile =
                createSampleUser().copy(
                    key = otherKey,
                    handle = UiHandle(raw = "other", host = "example.org"),
                    nameInternal = "Other User".toUiPlainText(),
                    url = "https://example.org/@other",
                )
            val requestedIds = mutableListOf<String>()
            val handler =
                UserHandler(
                    host = otherKey.host,
                    loader =
                        object : UserLoader {
                            override suspend fun userByHandleAndHost(uiHandle: UiHandle): UiProfile =
                                error("Unexpected lookup by handle")

                            override suspend fun userById(id: String): UiProfile {
                                requestedIds += id
                                return otherProfile
                            }
                        },
                )
            val service =
                FakeProfileService(
                    accountKey = accountKey,
                    authenticatedProfile = createSampleUser().copy(key = accountKey),
                    userHandler = handler,
                )

            val loaded = resolveProfileShareFlow(service, otherKey).first { it is UiState.Success<*> }

            assertEquals(listOf(otherKey.id), requestedIds)
            assertEquals(otherKey, loaded.takeSuccess()?.key)
            assertEquals("https://example.org/@other", loaded.takeSuccess()?.url)
        }

    @Test
    fun `missing accountProfileFlow emits NoActiveAccountException error`() =
        runTest {
            val unauthenticatedService =
                FakeProfileService(
                    accountKey = null,
                    authenticatedProfile = null,
                    userHandler = null,
                )
            val missingProfileService =
                FakeProfileService(
                    accountKey = accountKey,
                    authenticatedProfile = null,
                    userHandler = null,
                )

            val nullKeyUnauth = assertIs<UiState.Error<UiProfile>>(resolveProfileShareFlow(unauthenticatedService, null).first())
            val nullKeyMissing = assertIs<UiState.Error<UiProfile>>(resolveProfileShareFlow(missingProfileService, null).first())
            val ownKeyMissing = assertIs<UiState.Error<UiProfile>>(resolveProfileShareFlow(missingProfileService, accountKey).first())

            assertSame(NoActiveAccountException, nullKeyUnauth.throwable)
            assertSame(NoActiveAccountException, nullKeyMissing.throwable)
            assertSame(NoActiveAccountException, ownKeyMissing.throwable)
        }

    @Test
    fun `missing profile capability emits NoActiveAccountException error for other user`() =
        runTest {
            val serviceWithoutProfileCapability =
                FakeProfileService(
                    accountKey = accountKey,
                    authenticatedProfile = createSampleUser().copy(key = accountKey),
                    userHandler = null,
                )

            val otherUserState =
                assertIs<UiState.Error<UiProfile>>(
                    resolveProfileShareFlow(serviceWithoutProfileCapability, otherKey).first(),
                )

            assertSame(NoActiveAccountException, otherUserState.throwable)
        }

    @Test
    fun `shareUrl requires http or https url with host and returns null for relative or invalid urls`() {
        assertNull(normalizeProfileShareUrl(UiState.Loading()))
        assertNull(normalizeProfileShareUrl(UiState.Error(IllegalStateException("boom"))))
        assertNull(normalizeProfileShareUrl(UiState.Success(createSampleUser().copy(url = null))))
        assertNull(normalizeProfileShareUrl(UiState.Success(createSampleUser().copy(url = "   "))))
        assertNull(normalizeProfileShareUrl(UiState.Success(createSampleUser().copy(url = "/users/alice"))))
        assertNull(normalizeProfileShareUrl(UiState.Success(createSampleUser().copy(url = "//example.org/@alice"))))
        assertNull(normalizeProfileShareUrl(UiState.Success(createSampleUser().copy(url = "example.org/@alice"))))
        assertNull(normalizeProfileShareUrl(UiState.Success(createSampleUser().copy(url = "javascript:alert(1)"))))
        assertNull(normalizeProfileShareUrl(UiState.Success(createSampleUser().copy(url = "mailto:a@b.com"))))
        assertNull(normalizeProfileShareUrl(UiState.Success(createSampleUser().copy(url = "https://"))))
        assertNull(normalizeProfileShareUrl(UiState.Success(createSampleUser().copy(url = "https:///path"))))
        assertNull(normalizeProfileShareUrl(UiState.Success(createSampleUser().copy(url = "https://?foo=bar"))))
        assertNull(normalizeProfileShareUrl(UiState.Success(createSampleUser().copy(url = "https://example.org/with space"))))
        assertEquals(
            "https://example.org/@a",
            normalizeProfileShareUrl(UiState.Success(createSampleUser().copy(url = " https://example.org/@a "))),
        )
        assertEquals(
            "http://example.org/@a",
            ShareUrlPolicy.normalize("  http://example.org/@a  "),
        )
    }

    @Test
    fun `presenter emits NoActiveAccountException when specific account is missing or deleted`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val ownProfile =
                createSampleUser().copy(
                    key = accountKey,
                    nameInternal = "Self Profile".toUiPlainText(),
                    url = "https://example.org/@me",
                )
            platformServiceFactory = {
                FakeProfileService(
                    accountKey = accountKey,
                    authenticatedProfile = ownProfile,
                    userHandler = null,
                )
            }

            val clock = BroadcastFrameClock()
            val states = MutableStateFlow<ProfileSharePresenter.State?>(null)
            var collection: Job? = null
            try {
                val missingPresenter = ProfileSharePresenter(AccountType.Specific(accountKey), null)
                val missingFlow =
                    moleculeFlow(RecompositionMode.ContextClock) {
                        missingPresenter.body()
                    }
                val missingJob =
                    launch(clock) {
                        missingFlow.collect { states.value = it }
                    }
                var frame = 1L
                while (states.value?.profile !is UiState.Error && frame <= 10L) {
                    runCurrent()
                    clock.sendFrame(frame++)
                    runCurrent()
                }
                val missingError = assertIs<UiState.Error<UiProfile>>(states.value?.profile)
                assertSame(NoActiveAccountException, missingError.throwable)
                assertNull(states.value?.shareUrl)
                missingJob.cancelAndJoin()

                states.value = null
                accounts.addAccount(UiAccount(accountKey, "Mastodon"), "{}")
                val presenter = ProfileSharePresenter(AccountType.Specific(accountKey), null)
                collection =
                    launch(clock) {
                        moleculeFlow(RecompositionMode.ContextClock) {
                            presenter.body()
                        }.collect { states.value = it }
                    }
                while (states.value?.profile !is UiState.Success && frame <= 20L) {
                    runCurrent()
                    clock.sendFrame(frame++)
                    runCurrent()
                }
                assertIs<UiState.Success<UiProfile>>(states.value?.profile)
                assertEquals("https://example.org/@me", states.value?.shareUrl)

                appDb.accountDao().delete(accountKey)
                while (states.value?.profile !is UiState.Error && frame <= 30L) {
                    runCurrent()
                    clock.sendFrame(frame++)
                    runCurrent()
                }
                val deletedError = assertIs<UiState.Error<UiProfile>>(states.value?.profile)
                assertSame(NoActiveAccountException, deletedError.throwable)
                assertNull(states.value?.shareUrl)
            } finally {
                collection?.cancelAndJoin()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `presenter emits profile and trimmed shareUrl for specific account`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val ownProfile =
                createSampleUser().copy(
                    key = accountKey,
                    nameInternal = "Self Profile".toUiPlainText(),
                    url = " https://example.org/@me ",
                )
            platformServiceFactory = {
                FakeProfileService(
                    accountKey = accountKey,
                    authenticatedProfile = ownProfile,
                    userHandler = null,
                )
            }
            accounts.addAccount(UiAccount(accountKey, "Mastodon"), "{}")

            val clock = BroadcastFrameClock()
            val states = MutableStateFlow<ProfileSharePresenter.State?>(null)
            var collection: Job? = null
            try {
                val presenter = ProfileSharePresenter(AccountType.Specific(accountKey), null)
                collection =
                    launch(clock) {
                        moleculeFlow(RecompositionMode.ContextClock) {
                            presenter.body()
                        }.collect { states.value = it }
                    }
                var frame = 1L
                while (states.value?.profile !is UiState.Success && frame <= 10L) {
                    runCurrent()
                    clock.sendFrame(frame++)
                    runCurrent()
                }
                val state = assertNotNull(states.value)
                assertIs<UiState.Success<UiProfile>>(state.profile)
                assertEquals(accountKey, state.profile.takeSuccess()?.key)
                assertEquals("https://example.org/@me", state.shareUrl)
            } finally {
                collection?.cancelAndJoin()
                Dispatchers.resetMain()
            }
        }
}

private class FakeProfileService(
    accountKey: MicroBlogKey?,
    override val authenticatedProfile: UiProfile?,
    userHandler: UserHandler?,
) : MicroblogDataSource,
    DataSourceCapabilityProvider {
    override val authenticatedAccountKey: MicroBlogKey? = accountKey

    override val capabilitySet: DataSourceCapabilitySet by lazy {
        DataSourceCapabilitySet(
            profile =
                userHandler?.let { handler ->
                    ProfileCapability(
                        delegate = this,
                        userDataSource =
                            object : UserDataSource {
                                override val userHandler: UserHandler = handler
                            },
                    )
                },
        )
    }

    override fun homeTimeline(): RemoteLoader<UiTimelineV2> = error("Unused")

    override fun userTimeline(
        userKey: MicroBlogKey,
        mediaOnly: Boolean,
    ): RemoteLoader<UiTimelineV2> = error("Unused")

    override fun context(statusKey: MicroBlogKey): RemoteLoader<UiTimelineV2> = error("Unused")

    override fun searchStatus(query: String): RemoteLoader<UiTimelineV2> = error("Unused")

    override fun searchUser(query: String): RemoteLoader<UiProfile> = error("Unused")

    override fun discoverUsers(): RemoteLoader<UiProfile> = error("Unused")

    override fun discoverStatuses(): RemoteLoader<UiTimelineV2> = error("Unused")

    override fun discoverHashtags(): RemoteLoader<UiHashtag> = error("Unused")

    override fun following(userKey: MicroBlogKey): RemoteLoader<UiProfile> = error("Unused")

    override fun fans(userKey: MicroBlogKey): RemoteLoader<UiProfile> = error("Unused")

    override suspend fun profileTabs(userKey: MicroBlogKey): ImmutableList<ProfileTab> = persistentListOf()
}
