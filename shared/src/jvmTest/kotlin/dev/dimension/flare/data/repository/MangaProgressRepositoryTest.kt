package dev.dimension.flare.data.repository

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.dimension.flare.common.PlatformDispatchers
import dev.dimension.flare.createTestFileSystem
import dev.dimension.flare.createTestRootPath
import dev.dimension.flare.data.database.app.AppDatabase
import dev.dimension.flare.data.database.buildCacheDatabase
import dev.dimension.flare.data.database.cache.CACHE_DATABASE_VERSION
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.cache.model.DbPluginPostWidget
import dev.dimension.flare.data.database.createDatabaseDriver
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.data.io.OkioFileStorage
import dev.dimension.flare.data.model.tab.TimelineResolver
import dev.dimension.flare.deleteTestRootPath
import dev.dimension.flare.memoryDatabaseBuilder
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.PlatformRegistry
import dev.dimension.flare.model.PlatformSpec
import dev.dimension.flare.testPlatformRuntimeData
import dev.dimension.flare.ui.model.MangaReadingDirection
import dev.dimension.flare.ui.model.UiAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class MangaProgressRepositoryTest {
    @Test
    fun persistsProgressPerAccountAndGuestPluginAndCleansUpOnDelete() =
        runTest {
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            val appDataStore = AppDataStore(fileStorage)
            val appDatabase =
                Room
                    .memoryDatabaseBuilder<AppDatabase>()
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(PlatformDispatchers.IO)
                    .build()
            val cacheDatabase =
                Room
                    .memoryDatabaseBuilder<CacheDatabase>()
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(PlatformDispatchers.IO)
                    .build()
            val runtimeData = testPlatformRuntimeData()
            val registry = PlatformRegistry(runtimeData)
            var now = 1_000L
            val progressRepository = MangaProgressRepository(cacheDatabase) { now }
            val proxyRepository = ProxyRepository(fileStorage)
            val draftRepository = DraftRepository(appDatabase, DraftMediaStore(fileStorage))
            val accountRepository =
                AccountRepository(
                    appDatabase = appDatabase,
                    coroutineScope = backgroundScope,
                    appDataStore = appDataStore,
                    cacheDatabase = cacheDatabase,
                    proxyRepository = proxyRepository,
                    platformRegistry = registry,
                    draftRepository = draftRepository,
                )
            val resolver = TimelineResolver(runtimeData, RepositoryAccountService(accountRepository))
            val settingsRepository = SettingsRepository(fileStorage, appDataStore, resolver, cacheDatabase)
            val coordinatorJob = SupervisorJob()
            val coordinatorScope = CoroutineScope(backgroundScope.coroutineContext + coordinatorJob)

            try {
                // Verify AppSettings.MangaReaderConfig defaults and backward-compatible deserialization.
                val defaultSettings = Json.decodeFromString<AppSettings>("""{"version":"1.0"}""")
                assertEquals(
                    AppSettings.MangaReaderConfig(
                        defaultDirection = MangaReadingDirection.Rtl,
                        doublePageSpreadOnWideScreen = false,
                        spreadCoverSinglePage = true,
                        webtoonConstrainWidthOnWideScreen = true,
                        volumeKeysNavigation = false,
                    ),
                    defaultSettings.mangaReaderConfig,
                )
                val legacySettings =
                    Json.decodeFromString<AppSettings>(
                        """{"version":"1","mangaReaderConfig":{"defaultDirection":"Rtl"}}""",
                    )
                assertEquals(true, legacySettings.mangaReaderConfig.webtoonConstrainWidthOnWideScreen)
                assertEquals(false, legacySettings.mangaReaderConfig.volumeKeysNavigation)

                val mangaKey = MicroBlogKey("manga-42", "api.manga.example")
                val accountKey = MicroBlogKey("reader", "api.manga.example")
                val specificAccount = AccountType.Specific(accountKey)
                val guestAccount = AccountType.GuestPlugin(platformId = "MangaPlugin", host = "api.manga.example")
                val otherGuestAccount = AccountType.GuestPlugin(platformId = "OtherMangaPlugin", host = "other.manga.example")

                registry.replaceExternal(
                    "MangaPlugin",
                    object : PlatformSpec by registry.defaultGuest {
                        override val platformId = "MangaPlugin"
                        override val isDefaultGuest = false
                        override val standaloneGuestHost = "api.manga.example"
                    },
                )
                registry.replaceExternal(
                    "OtherMangaPlugin",
                    object : PlatformSpec by registry.defaultGuest {
                        override val platformId = "OtherMangaPlugin"
                        override val isDefaultGuest = false
                        override val standaloneGuestHost = "other.manga.example"
                    },
                )
                accountRepository.addAccount(UiAccount(accountKey, "MangaPlugin"), "token-1")

                assertEquals(MangaProgressSnapshot(), progressRepository.observe(specificAccount, mangaKey).first())
                assertEquals(MangaProgressSnapshot(), progressRepository.observe(guestAccount, mangaKey).first())

                // Record distinct progress for Specific account vs GuestPlugin on the exact same mangaKey.
                now = 2_500L
                progressRepository.recordPage(
                    accountType = specificAccount,
                    mangaKey = mangaKey,
                    chapterId = "ch-1",
                    pageIndex = 4,
                    pageCount = 18,
                    isCompleted = false,
                )
                progressRepository.setReaderModeOverride(specificAccount, mangaKey, MangaReadingDirection.Webtoon)
                progressRepository.setChapterSortDescending(specificAccount, mangaKey, descending = false)

                now = 3_500L
                progressRepository.recordPage(
                    accountType = guestAccount,
                    mangaKey = mangaKey,
                    chapterId = "ch-2",
                    pageIndex = 11,
                    pageCount = 12,
                    isCompleted = true,
                )
                progressRepository.setChapterRead(
                    accountType = otherGuestAccount,
                    mangaKey = mangaKey,
                    chapterId = "ch-9",
                    isRead = true,
                )

                assertEquals(
                    MangaProgressSnapshot(
                        lastChapterId = "ch-1",
                        lastPageIndex = 4,
                        lastReadAt = 2_500L,
                        readerModeOverride = MangaReadingDirection.Webtoon,
                        chapterSortDescending = false,
                        chapters =
                            mapOf(
                                "ch-1" to
                                    MangaProgressSnapshot.ChapterProgress(
                                        chapterId = "ch-1",
                                        lastPageIndex = 4,
                                        pageCount = 18,
                                        isCompleted = false,
                                    ),
                            ),
                    ),
                    progressRepository.observe(specificAccount, mangaKey).first(),
                )
                assertEquals(
                    MangaProgressSnapshot(
                        lastChapterId = "ch-2",
                        lastPageIndex = 11,
                        lastReadAt = 3_500L,
                        readerModeOverride = null,
                        chapterSortDescending = true,
                        chapters =
                            mapOf(
                                "ch-2" to
                                    MangaProgressSnapshot.ChapterProgress(
                                        chapterId = "ch-2",
                                        lastPageIndex = 11,
                                        pageCount = 12,
                                        isCompleted = true,
                                    ),
                            ),
                    ),
                    progressRepository.observe(guestAccount, mangaKey).first(),
                )

                // Toggle chapter read/unread on Specific account without losing readerModeOverride or sort order,
                // while keeping DbMangaState.lastPageIndex/lastReadAt synchronized when chapterId == lastChapterId.
                now = 4_000L
                progressRepository.setChapterRead(specificAccount, mangaKey, "ch-1", isRead = true)
                val afterRead = progressRepository.observe(specificAccount, mangaKey).first()
                assertEquals("ch-1", afterRead.lastChapterId)
                assertEquals(17, afterRead.lastPageIndex)
                assertEquals(4_000L, afterRead.lastReadAt)
                assertEquals(MangaReadingDirection.Webtoon, afterRead.readerModeOverride)
                assertEquals(false, afterRead.chapterSortDescending)
                assertEquals(
                    MangaProgressSnapshot.ChapterProgress(
                        chapterId = "ch-1",
                        lastPageIndex = 17,
                        pageCount = 18,
                        isCompleted = true,
                    ),
                    afterRead.chapters["ch-1"],
                )

                // Marking a non-current chapter read does not overwrite lastChapterId or lastPageIndex.
                now = 4_500L
                progressRepository.setChapterRead(specificAccount, mangaKey, "ch-0", isRead = true)
                val afterOtherChapterRead = progressRepository.observe(specificAccount, mangaKey).first()
                assertEquals("ch-1", afterOtherChapterRead.lastChapterId)
                assertEquals(17, afterOtherChapterRead.lastPageIndex)
                assertEquals(4_000L, afterOtherChapterRead.lastReadAt)
                assertEquals(true, afterOtherChapterRead.chapters["ch-0"]?.isCompleted)

                progressRepository.setChapterRead(specificAccount, mangaKey, "ch-1", isRead = false)
                val afterUnread = progressRepository.observe(specificAccount, mangaKey).first()
                assertEquals(MangaReadingDirection.Webtoon, afterUnread.readerModeOverride)
                assertEquals(false, afterUnread.chapterSortDescending)
                assertEquals(0, afterUnread.lastPageIndex)
                assertEquals(
                    MangaProgressSnapshot.ChapterProgress(
                        chapterId = "ch-1",
                        lastPageIndex = 0,
                        pageCount = 18,
                        isCompleted = false,
                    ),
                    afterUnread.chapters["ch-1"],
                )

                // Deleting the Specific account via AccountRepository cleans up Specific rows while preserving GuestPlugin rows.
                accountRepository.delete(accountKey)
                assertEquals(MangaProgressSnapshot(), progressRepository.observe(specificAccount, mangaKey).first())
                assertEquals("ch-2", progressRepository.observe(guestAccount, mangaKey).first().lastChapterId)

                // Uninstalling MangaPlugin via deleteAccounts cleans up GuestPlugin("MangaPlugin", ...) rows while preserving OtherMangaPlugin.
                accountRepository.deleteAccounts(
                    platformId = "MangaPlugin",
                    ownsCredential = { true },
                    beforeDelete = { registry.replaceExternal("MangaPlugin", null) },
                )
                assertEquals(MangaProgressSnapshot(), progressRepository.observe(guestAccount, mangaKey).first())
                assertEquals(
                    true,
                    progressRepository
                        .observe(otherGuestAccount, mangaKey)
                        .first()
                        .chapters["ch-9"]
                        ?.isCompleted,
                )

                // Removing or disabling a guest platform watched by AccountTabSyncCoordinator also cleans up its guest progress.
                AccountTabSyncCoordinator(
                    accountRepository = accountRepository,
                    settingsRepository = settingsRepository,
                    coroutineScope = coordinatorScope,
                    timelineResolver = resolver,
                    mangaProgressRepository = progressRepository,
                )
                registry.replaceExternal("OtherMangaPlugin", null)
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) {
                        progressRepository.observe(otherGuestAccount, mangaKey).first { it == MangaProgressSnapshot() }
                    }
                }
                assertEquals(MangaProgressSnapshot(), progressRepository.observe(otherGuestAccount, mangaKey).first())
            } finally {
                coordinatorJob.cancelAndJoin()
                cacheDatabase.close()
                appDatabase.close()
                deleteTestRootPath(root)
            }
        }

    @Test
    fun cacheRebuildFromV56CreatesMangaProgressTablesAndDecodesUnknownDirectionDefensively() =
        runTest {
            assertEquals(57, CACHE_DATABASE_VERSION)
            val dbPath = Files.createTempFile("flare-cache-v56-rebuild", ".db")
            val path = dbPath.toAbsolutePath().toString()
            val owner = AccountType.GuestPlugin("MangaPlugin", "api.manga.example")
            val mangaKey = MicroBlogKey("series-1", "api.manga.example")
            try {
                val seedDb = buildCacheDatabase(Room.databaseBuilder<CacheDatabase>(name = path))
                try {
                    seedDb.pluginPostWidgetDao().upsertAll(
                        listOf(DbPluginPostWidget("post", "plugin", "v1", "hash", "[]", 42L, null)),
                    )
                } finally {
                    seedDb.close()
                }

                // Recreate a prior v56 schema without manga tables or matching identity hash.
                BundledSQLiteDriver().open(path).use { connection ->
                    connection.execSQL("DROP TABLE DbMangaChapterProgress")
                    connection.execSQL("DROP TABLE DbMangaState")
                    connection.execSQL("UPDATE room_master_table SET identity_hash = '301b52290882c35fd112a882504b2475' WHERE id = 42")
                    connection.execSQL("PRAGMA user_version = 56")
                }

                val rebuiltDb = buildCacheDatabase(Room.databaseBuilder<CacheDatabase>(name = path))
                try {
                    assertEquals(emptyList(), rebuiltDb.pluginPostWidgetDao().getByPostKeys(listOf("post")))
                    val repo = MangaProgressRepository(rebuiltDb) { 9_000L }
                    repo.recordPage(owner, mangaKey, "ch-1", pageIndex = 2, pageCount = 10, isCompleted = false)
                    val snapshot = repo.observe(owner, mangaKey).first()
                    assertEquals("ch-1", snapshot.lastChapterId)
                    assertEquals(2, snapshot.lastPageIndex)
                    assertEquals(9_000L, snapshot.lastReadAt)
                } finally {
                    rebuiltDb.close()
                }

                // Unknown persisted reading direction string decodes defensively to null without crashing Room queries.
                BundledSQLiteDriver().open(path).use { connection ->
                    connection.execSQL("UPDATE DbMangaState SET readerModeOverride = 'FutureScrollMode'")
                }
                val reopenedDb = buildCacheDatabase(Room.databaseBuilder<CacheDatabase>(name = path))
                try {
                    val reopenedSnapshot = MangaProgressRepository(reopenedDb).observe(owner, mangaKey).first()
                    assertEquals("ch-1", reopenedSnapshot.lastChapterId)
                    assertEquals(null, reopenedSnapshot.readerModeOverride)
                } finally {
                    reopenedDb.close()
                }
            } finally {
                Files.deleteIfExists(dbPath)
                Files.deleteIfExists(dbPath.resolveSibling("${dbPath.fileName}-wal"))
                Files.deleteIfExists(dbPath.resolveSibling("${dbPath.fileName}-shm"))
            }
        }

    @Test
    fun uninstallingGuestPluginDeletesProcessorWidgetsWhileKeepingOtherAccounts() =
        runTest {
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            val appDataStore = AppDataStore(fileStorage)
            val appDatabase =
                Room
                    .memoryDatabaseBuilder<AppDatabase>()
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(PlatformDispatchers.IO)
                    .build()
            val cacheDatabase =
                Room
                    .memoryDatabaseBuilder<CacheDatabase>()
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(PlatformDispatchers.IO)
                    .build()
            val runtimeData = testPlatformRuntimeData()
            val registry = PlatformRegistry(runtimeData)
            val proxyRepository = ProxyRepository(fileStorage)
            val draftRepository = DraftRepository(appDatabase, DraftMediaStore(fileStorage))
            val accountRepository =
                AccountRepository(
                    appDatabase = appDatabase,
                    coroutineScope = backgroundScope,
                    appDataStore = appDataStore,
                    cacheDatabase = cacheDatabase,
                    proxyRepository = proxyRepository,
                    platformRegistry = registry,
                    draftRepository = draftRepository,
                )
            try {
                val guestAccount = AccountType.GuestPlugin(platformId = "MangaPlugin", host = "api.manga.example")
                val otherGuestAccount = AccountType.GuestPlugin(platformId = "OtherMangaPlugin", host = "other.manga.example")
                registry.replaceExternal(
                    "MangaPlugin",
                    object : PlatformSpec by registry.defaultGuest {
                        override val platformId = "MangaPlugin"
                        override val isDefaultGuest = false
                        override val standaloneGuestHost = "api.manga.example"
                    },
                )
                registry.replaceExternal(
                    "OtherMangaPlugin",
                    object : PlatformSpec by registry.defaultGuest {
                        override val platformId = "OtherMangaPlugin"
                        override val isDefaultGuest = false
                        override val standaloneGuestHost = "other.manga.example"
                    },
                )
                val targetPostEntityKey = "${guestAccount}_${MicroBlogKey("post-1", "api.manga.example")}"
                val orphanPostEntityKey = "${guestAccount}_${MicroBlogKey("post-orphan", "api.manga.example")}"
                val preservedPostEntityKey = "${otherGuestAccount}_${MicroBlogKey("post-2", "other.manga.example")}"
                cacheDatabase.pluginPostWidgetDao().upsertAll(
                    listOf(
                        DbPluginPostWidget(targetPostEntityKey, "other.processor.plugin", "k1", "h1", "[]", 10L, null),
                        DbPluginPostWidget(orphanPostEntityKey, "another.processor.plugin", "k2", "h2", "[]", 20L, null),
                        DbPluginPostWidget(preservedPostEntityKey, "other.processor.plugin", "k3", "h3", "[]", 30L, null),
                    ),
                )

                accountRepository.deleteAccounts(
                    platformId = "MangaPlugin",
                    ownsCredential = { true },
                    beforeDelete = { registry.replaceExternal("MangaPlugin", null) },
                )

                assertEquals(
                    emptyList(),
                    cacheDatabase.pluginPostWidgetDao().getByPostKeys(listOf(targetPostEntityKey, orphanPostEntityKey)),
                )
                assertEquals(
                    listOf(preservedPostEntityKey),
                    cacheDatabase.pluginPostWidgetDao().getByPostKeys(listOf(preservedPostEntityKey)).map { it.postEntityKey },
                )
            } finally {
                cacheDatabase.close()
                appDatabase.close()
                deleteTestRootPath(root)
            }
        }
}
