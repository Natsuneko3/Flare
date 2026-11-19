package dev.dimension.flare.data.datasource.microblog.handler

import androidx.paging.LoadState
import androidx.room3.Room
import dev.dimension.flare.RobolectricTest
import dev.dimension.flare.common.CacheState
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.cache.model.DbProfileTabs
import dev.dimension.flare.data.database.createDatabaseDriver
import dev.dimension.flare.data.datasource.microblog.CachedProfileTab
import dev.dimension.flare.data.datasource.microblog.MicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.ProfileCapability
import dev.dimension.flare.data.datasource.microblog.ProfileTab
import dev.dimension.flare.data.datasource.microblog.UnavailableMicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.datasource.UserDataSource
import dev.dimension.flare.data.datasource.microblog.loader.UserLoader
import dev.dimension.flare.data.datasource.microblog.paging.notSupported
import dev.dimension.flare.di.startKoin
import dev.dimension.flare.di.testSingle
import dev.dimension.flare.memoryDatabaseBuilder
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiHandle
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.model.toUi
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileTabsHandlerTest : RobolectricTest() {
    private lateinit var db: CacheDatabase

    private val accountKey = MicroBlogKey(id = "account-1", host = "test.social")
    private val accountType = AccountType.Specific(accountKey)
    private val userKey = MicroBlogKey(id = "user-1", host = "test.social")

    @BeforeTest
    fun setup() {
        db =
            Room
                .memoryDatabaseBuilder<CacheDatabase>()
                .setDriver(createDatabaseDriver())
                .setQueryCoroutineContext(Dispatchers.Unconfined)
                .build()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        stopKoin()
    }

    @Test
    fun refreshPersistsProfileTabsAndEmitsRestoredTabs() =
        runTest {
            startKoin {
                modules(module { testSingle { db } })
            }

            val dataSource = FakeCacheableTabsDataSource(remoteTabIds = listOf("posts", "highlights", "media"))
            val handler = ProfileTabsHandler(accountType, dataSource.profileCapability())
            val cacheable = handler.profileTabs(userKey)

            val valueDeferred =
                async {
                    cacheable.data
                        .filterIsInstance<CacheState.Success<ImmutableList<ProfileTab>>>()
                        .first()
                        .data
                }

            val refreshState = cacheable.refreshState.drop(1).first()
            assertTrue(refreshState is LoadState.NotLoading)
            assertEquals(listOf("posts", "highlights", "media"), valueDeferred.await().map { it.id })

            val saved = db.userDao().getProfileTabsSync(accountType, userKey)
            assertNotNull(saved)
            assertEquals(1, dataSource.fetchCount)
        }

    @Test
    fun cachedTabsEmitImmediatelyEvenWhenRefreshFails() =
        runTest {
            startKoin {
                modules(module { testSingle { db } })
            }

            db.userDao().insertProfileTabs(
                DbProfileTabs(
                    accountType = accountType,
                    userKey = userKey,
                    tabsJson = """[{"id":"posts"},{"id":"media"}]""",
                ),
            )

            val dataSource =
                FakeCacheableTabsDataSource(
                    remoteTabIds = listOf("posts"),
                    fetchError = RuntimeException("offline"),
                )
            val handler = ProfileTabsHandler(accountType, dataSource.profileCapability())

            val state =
                handler
                    .profileTabs(userKey)
                    .toUi()
                    .filterIsInstance<UiState.Success<ImmutableList<ProfileTab>>>()
                    .first()

            assertEquals(listOf("posts", "media"), state.data.map { it.id })
        }

    @Test
    fun corruptedCacheFallsBackToRemoteFetch() =
        runTest {
            startKoin {
                modules(module { testSingle { db } })
            }

            db.userDao().insertProfileTabs(
                DbProfileTabs(
                    accountType = accountType,
                    userKey = userKey,
                    tabsJson = "not-valid-json",
                ),
            )

            val dataSource = FakeCacheableTabsDataSource(remoteTabIds = listOf("posts", "articles"))
            val handler = ProfileTabsHandler(accountType, dataSource.profileCapability())

            val state =
                handler
                    .profileTabs(userKey)
                    .toUi()
                    .filterIsInstance<UiState.Success<ImmutableList<ProfileTab>>>()
                    .first()

            assertEquals(listOf("posts", "articles"), state.data.map { it.id })
            assertEquals(1, dataSource.fetchCount)
        }

    @Test
    fun unrecognizedCachedTabIdsFallBackToRemoteFetch() =
        runTest {
            startKoin {
                modules(module { testSingle { db } })
            }

            db.userDao().insertProfileTabs(
                DbProfileTabs(
                    accountType = accountType,
                    userKey = userKey,
                    tabsJson = """[{"id":"legacy_removed_tab"}]""",
                ),
            )

            val dataSource = FakeCacheableTabsDataSource(remoteTabIds = listOf("posts", "media"))
            val handler = ProfileTabsHandler(accountType, dataSource.profileCapability())

            val state =
                handler
                    .profileTabs(userKey)
                    .toUi()
                    .filterIsInstance<UiState.Success<ImmutableList<ProfileTab>>>()
                    .first()

            assertEquals(listOf("posts", "media"), state.data.map { it.id })
        }

    private class FakeCacheableTabsDataSource(
        var remoteTabIds: List<String>,
        var fetchError: Throwable? = null,
    ) : MicroblogDataSource by UnavailableMicroblogDataSource {
        var fetchCount: Int = 0
            private set

        private val catalog: Map<String, ProfileTab> =
            listOf("posts", "highlights", "media", "articles").associateWith { id ->
                ProfileTab(
                    id = id,
                    name = UiText.Raw(id),
                    displayType = if (id == "media") ProfileTab.DisplayType.Gallery else ProfileTab.DisplayType.Timeline,
                    loader = notSupported(),
                )
            }

        override val supportsProfileTabsCache: Boolean = true

        override suspend fun profileTabs(userKey: MicroBlogKey): ImmutableList<ProfileTab> {
            fetchCount += 1
            fetchError?.let { throw it }
            return remoteTabIds.mapNotNull { catalog[it] }.toPersistentList()
        }

        override fun restoreProfileTabs(
            userKey: MicroBlogKey,
            cached: List<CachedProfileTab>,
        ): ImmutableList<ProfileTab>? {
            if (cached.isEmpty()) return persistentListOf()
            val restored = cached.mapNotNull { catalog[it.id] }
            return restored.takeIf { it.isNotEmpty() }?.toPersistentList()
        }

        fun profileCapability(): ProfileCapability =
            ProfileCapability(
                delegate = this,
                userDataSource =
                    object : UserDataSource {
                        override val userHandler =
                            UserHandler(
                                host = "test.social",
                                loader =
                                    object : UserLoader {
                                        override suspend fun userByHandleAndHost(uiHandle: UiHandle): UiProfile = error("unused")

                                        override suspend fun userById(id: String): UiProfile = error("unused")
                                    },
                            )
                    },
            )
    }
}
