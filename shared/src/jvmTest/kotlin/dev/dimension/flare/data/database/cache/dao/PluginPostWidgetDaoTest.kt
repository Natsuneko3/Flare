package dev.dimension.flare.data.database.cache.dao

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.dimension.flare.data.database.buildCacheDatabase
import dev.dimension.flare.data.database.cache.CACHE_DATABASE_VERSION
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.cache.TimelineRevisionCallback
import dev.dimension.flare.data.database.cache.model.DbEmojiHistory
import dev.dimension.flare.data.database.cache.model.DbPagingTimeline
import dev.dimension.flare.data.database.cache.model.DbPluginPostWidget
import dev.dimension.flare.data.database.cache.model.DbProfileTabs
import dev.dimension.flare.data.database.cache.model.DbStatus
import dev.dimension.flare.data.database.createDatabaseDriver
import dev.dimension.flare.data.datasource.microblog.paging.TimelineDbPageCache
import dev.dimension.flare.data.datasource.microblog.paging.TimelineDbPageLoader
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.data.model.PluginPermission
import dev.dimension.flare.data.model.PluginPermissionType
import dev.dimension.flare.data.plugin.NoopTimelineProcessorService
import dev.dimension.flare.data.plugin.timelineProcessorServiceOrNull
import dev.dimension.flare.memoryDatabaseBuilder
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.UiIcon
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.createSampleStatus
import dev.dimension.flare.ui.model.createSampleUser
import dev.dimension.flare.ui.model.pluginPostEntityKey
import dev.dimension.flare.ui.render.toUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class PluginPostWidgetDaoTest {
    @Test
    fun upsertQueryDeleteByPluginAndPruneExpiredRows() =
        runTest {
            withInMemoryCacheDatabase { db ->
                val dao = db.pluginPostWidgetDao()
                dao.upsertAll(
                    listOf(
                        DbPluginPostWidget("acc_1", "p.stock", "k1", "h1", "[{\"id\":\"aapl\"}]", 100L, 500L),
                        DbPluginPostWidget("acc_1", "p.score", "k2", "h1", "[]", 100L, null),
                    ),
                )
                assertEquals(2, dao.getByPostKeys(listOf("acc_1")).size)

                // Upsert replaces existing row for the same (postEntityKey, pluginId) primary key.
                dao.upsertAll(
                    listOf(
                        DbPluginPostWidget("acc_1", "p.stock", "k1-v2", "h1", "[{\"id\":\"nvda\"}]", 150L, 500L),
                    ),
                )
                assertEquals(
                    "k1-v2",
                    dao
                        .getByPostKeys(listOf("acc_1"))
                        .single { it.pluginId == "p.stock" }
                        .pluginCacheKey,
                )

                dao.deleteExpiredBefore(600L)
                assertEquals(listOf("p.score"), dao.getByPostKeys(listOf("acc_1")).map { it.pluginId })
                dao.deleteByPluginId("p.score")
                assertTrue(dao.getByPostKeys(listOf("acc_1")).isEmpty())
            }
        }

    @Test
    fun deleteByAccountTypeDeletesSpecificAccountRowsWhilePreservingOtherAccountsAndGuest() =
        runTest {
            withInMemoryCacheDatabase { db ->
                val dao = db.pluginPostWidgetDao()
                val accountA = AccountType.Specific(MicroBlogKey("alice", "example.social"))
                val accountB = AccountType.Specific(MicroBlogKey("alice", "example.social.other"))
                val guest = AccountType.Guest
                val guestHost = AccountType.GuestHost("example.social")

                val aPost1 = "${accountA}_${MicroBlogKey("101", "example.social")}"
                val aPost2 = "${accountA}_${MicroBlogKey("102", "example.social")}"
                val bPost1 = "${accountB}_${MicroBlogKey("201", "example.social.other")}"
                val guestPost = "${guest}_${MicroBlogKey("301", "example.social")}"
                val guestHostPost = "${guestHost}_${MicroBlogKey("401", "example.social")}"

                dao.upsertAll(
                    listOf(
                        DbPluginPostWidget(aPost1, "p.stock", "k1", "h1", "[{\"id\":\"a1\"}]", 100L, null),
                        DbPluginPostWidget(aPost1, "p.score", "k2", "h1", "[{\"id\":\"a2\"}]", 100L, null),
                        DbPluginPostWidget(aPost2, "p.stock", "k1", "h2", "[{\"id\":\"a3\"}]", 101L, null),
                        DbPluginPostWidget(bPost1, "p.stock", "k1", "h3", "[{\"id\":\"b1\"}]", 102L, null),
                        DbPluginPostWidget(guestPost, "p.stock", "k1", "h4", "[{\"id\":\"g1\"}]", 103L, null),
                        DbPluginPostWidget(guestHostPost, "p.stock", "k1", "h5", "[{\"id\":\"gh1\"}]", 104L, null),
                    ),
                )

                dao.deleteByAccountType(accountA)

                val allKeys = listOf(aPost1, aPost2, bPost1, guestPost, guestHostPost)
                val remaining = dao.getByPostKeys(allKeys)
                assertEquals(
                    setOf(bPost1, guestPost, guestHostPost),
                    remaining.map { it.postEntityKey }.toSet(),
                )
            }
        }

    @Test
    fun getByPostKeysChunksOver500BindsAndTrimsOldestKeepingNewest() =
        runTest {
            withInMemoryCacheDatabase { db ->
                val dao = db.pluginPostWidgetDao()
                assertTrue(dao.getByPostKeys(emptyList()).isEmpty())

                val items =
                    (1..1_150).map { idx ->
                        DbPluginPostWidget(
                            postEntityKey = "post_$idx",
                            pluginId = "p.bulk",
                            pluginCacheKey = "k1",
                            sourceHash = "h_$idx",
                            cardsJson = "[]",
                            createdAtMillis = idx.toLong(),
                            expiresAtMillis = null,
                        )
                    }
                dao.upsertAll(items)

                val allKeys = items.map { it.postEntityKey } + listOf("post_1", "missing_key")
                val fetched = dao.getByPostKeys(allKeys)
                assertEquals(1_150, fetched.size)

                dao.trimOldestKeeping(3)
                val remaining = dao.getByPostKeys(items.map { it.postEntityKey })
                assertEquals(
                    listOf("post_1148", "post_1149", "post_1150"),
                    remaining.map { it.postEntityKey }.sorted(),
                )

                dao.trimOldestKeeping(0)
                assertTrue(dao.getByPostKeys(items.map { it.postEntityKey }).isEmpty())
            }
        }

    @Test
    fun migration54To55CreatesTableAndIndicesWhilePreservingVersion54Data() =
        runTest {
            assertEquals(55, CACHE_DATABASE_VERSION)
            val databasePath = Files.createTempFile("flare-cache-v54", ".db")
            val path = databasePath.toAbsolutePath().toString()
            val owner = AccountType.Specific(MicroBlogKey("me", "example.social"))
            val profile = MicroBlogKey("alice", "example.social")
            val emoji = DbEmojiHistory(owner, "rocket", 789L)
            val tabs = DbProfileTabs(owner, profile, "[\"posts\"]")
            try {
                buildCacheDatabase(Room.databaseBuilder<CacheDatabase>(name = path)).let { database ->
                    try {
                        database.emojiDao().insertHistory(emoji)
                        database.userDao().insertProfileTabs(tabs)
                    } finally {
                        database.close()
                    }
                }

                // Downgrade schema to match v54 (before DbPluginPostWidget existed).
                BundledSQLiteDriver().open(path).use { connection ->
                    connection.execSQL("DROP TABLE DbPluginPostWidget")
                    connection.execSQL("PRAGMA user_version = 54")
                }

                buildCacheDatabase(Room.databaseBuilder<CacheDatabase>(name = path)).let { migrated ->
                    try {
                        assertEquals(listOf(emoji), migrated.emojiDao().getHistory(owner))
                        assertEquals(tabs, migrated.userDao().getProfileTabsSync(owner, profile))
                        val widget =
                            DbPluginPostWidget(
                                postEntityKey = "acc_migrated",
                                pluginId = "p.migrated",
                                pluginCacheKey = "k1",
                                sourceHash = "h1",
                                cardsJson = "[]",
                                createdAtMillis = 42L,
                                expiresAtMillis = 99L,
                            )
                        migrated.pluginPostWidgetDao().upsertAll(listOf(widget))
                        assertEquals(listOf(widget), migrated.pluginPostWidgetDao().getByPostKeys(listOf("acc_migrated")))
                    } finally {
                        migrated.close()
                    }
                }

                BundledSQLiteDriver().open(path).use { connection ->
                    val indexNames = mutableSetOf<String>()
                    connection
                        .prepare("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'DbPluginPostWidget'")
                        .use { statement ->
                            while (statement.step()) {
                                indexNames += statement.getText(0)
                            }
                        }
                    assertTrue("index_DbPluginPostWidget_pluginId" in indexNames, "Missing pluginId index: $indexNames")
                    assertTrue("index_DbPluginPostWidget_expiresAtMillis" in indexNames, "Missing expiresAtMillis index: $indexNames")
                }
            } finally {
                deleteDatabaseFiles(databasePath)
            }
        }

    @Test
    fun writingDbPluginPostWidgetDoesNotInvalidateTimelineDbPageLoaderOrBumpContentRevision() =
        runTest {
            val databasePath = Files.createTempFile("flare-widget-no-invalidation", ".db")
            val path = databasePath.toAbsolutePath().toString()
            val database =
                Room
                    .databaseBuilder<CacheDatabase>(name = path)
                    .addCallback(TimelineRevisionCallback)
                    .setDriver(BundledSQLiteDriver())
                    .setQueryCoroutineContext(Dispatchers.IO)
                    .build()
            try {
                val statusKey = MicroBlogKey(id = "status-1", host = "test.invalid")
                val content =
                    UiTimelineV2.Message(
                        statusKey = statusKey,
                        icon = UiIcon.Info,
                        type = UiTimelineV2.Message.Type.Raw("hello"),
                        createdAt = Instant.fromEpochMilliseconds(1L).toUi(),
                        clickEvent = ClickEvent.Noop,
                        accountType = AccountType.Guest,
                    )
                val status =
                    DbStatus(
                        statusKey = statusKey,
                        accountType = AccountType.Guest,
                        content = content,
                        renderHash = content.renderHash,
                        text = null,
                    )
                database.statusDao().insertNew(listOf(status))
                database
                    .pagingTimelineDao()
                    .insertNew(
                        listOf(
                            DbPagingTimeline(
                                pagingKey = "home",
                                statusId = status.id,
                                sortId = 0,
                            ),
                        ),
                    )

                val pageCache = TimelineDbPageCache()
                val loader = TimelineDbPageLoader(database, "home", pageCache)
                val initialPage = loader.load(0, 20)
                assertEquals(1, initialPage.size)
                val initialRevision = initialPage.single().identity.contentRevision

                val invalidations = AtomicInteger(0)
                val subscription = loader.observeInvalidations { invalidations.incrementAndGet() }
                try {
                    subscription.ready?.await()
                    val dao = database.pluginPostWidgetDao()
                    dao.upsertAll(
                        listOf(
                            DbPluginPostWidget(
                                postEntityKey = status.id,
                                pluginId = "p.stock",
                                pluginCacheKey = "k1",
                                sourceHash = "h1",
                                cardsJson = "[{\"id\":\"aapl\"}]",
                                createdAtMillis = 100L,
                                expiresAtMillis = 500L,
                            ),
                        ),
                    )
                    dao.deleteByPluginId("p.stock")
                    delay(50)

                    assertEquals(0, invalidations.get(), "DbPluginPostWidget writes must not invalidate TimelineDbPageLoader")
                    val afterPage = loader.load(0, 20)
                    assertEquals(initialRevision, afterPage.single().identity.contentRevision)
                } finally {
                    subscription.job.cancel()
                }
            } finally {
                database.close()
                deleteDatabaseFiles(databasePath)
            }
        }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun appSettingsGrantsPermissionAndTimelineProcessorServiceDefaultsRoundTrip() {
        assertEquals(emptyMap(), AppSettings.default.enabledTimelineProcessorPlugins)
        assertEquals(
            emptyMap(),
            Json.decodeFromString<AppSettings>("""{"version":"legacy"}""").enabledTimelineProcessorPlugins,
        )

        val grants =
            mapOf(
                "org.example.gpg" to emptySet(),
                "org.example.market" to setOf("https://api.one.example", "https://api.two.example"),
            )
        val settings = AppSettings(version = "test", enabledTimelineProcessorPlugins = grants)
        val jsonRoundTrip = Json.decodeFromString<AppSettings>(Json.encodeToString(AppSettings.serializer(), settings))
        assertEquals(settings, jsonRoundTrip)

        val protoRoundTrip =
            ProtoBuf.decodeFromByteArray(
                AppSettings.serializer(),
                ProtoBuf.encodeToByteArray(AppSettings.serializer(), settings),
            )
        assertEquals(settings, protoRoundTrip)

        val permission = PluginPermission(PluginPermissionType.TimelinePostAccess, "*")
        assertEquals(
            permission,
            Json.decodeFromString<PluginPermission>(Json.encodeToString(PluginPermission.serializer(), permission)),
        )

        val post = createSampleStatus(createSampleUser())
        assertEquals(0L, NoopTimelineProcessorService.overlayRevision.value)
        NoopTimelineProcessorService.submitPosts(listOf(post))
        assertTrue(NoopTimelineProcessorService.widgetsForPost(post).isEmpty())
        NoopTimelineProcessorService.refreshPostPlugin(post, "p1")
        NoopTimelineProcessorService.toggleCollapsible(post.pluginPostEntityKey(), "p1", "c1", "col1")
        NoopTimelineProcessorService.clearPlugin("p1")
        NoopTimelineProcessorService.clearAccount(AccountType.Guest)
        NoopTimelineProcessorService.clearAll()
        assertNull(timelineProcessorServiceOrNull())
    }

    private suspend fun withInMemoryCacheDatabase(block: suspend (CacheDatabase) -> Unit) {
        val db =
            Room
                .memoryDatabaseBuilder<CacheDatabase>()
                .setDriver(createDatabaseDriver())
                .setQueryCoroutineContext(Dispatchers.Unconfined)
                .build()
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    private fun deleteDatabaseFiles(databasePath: Path) {
        Files.deleteIfExists(databasePath)
        Files.deleteIfExists(databasePath.resolveSibling("${databasePath.fileName}-wal"))
        Files.deleteIfExists(databasePath.resolveSibling("${databasePath.fileName}-shm"))
    }
}
