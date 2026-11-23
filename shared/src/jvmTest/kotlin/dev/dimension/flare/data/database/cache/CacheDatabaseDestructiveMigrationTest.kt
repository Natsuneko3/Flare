package dev.dimension.flare.data.database.cache

import androidx.room3.Room
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.dimension.flare.data.database.buildCacheDatabase
import dev.dimension.flare.data.database.cache.model.DbEmojiHistory
import dev.dimension.flare.data.database.cache.model.DbProfileTabs
import dev.dimension.flare.data.database.cache.model.DbUserHistory
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CacheDatabaseDestructiveMigrationTest {
    @Test
    fun addingProfileTabsPreservesVersion53History() =
        runTest {
            val databasePath = Files.createTempFile("flare-cache-v53", ".db")
            val path = databasePath.toString()
            val owner = AccountType.Specific(MicroBlogKey("me", "example.social"))
            val profile = MicroBlogKey("alice", "example.social")
            val emoji = DbEmojiHistory(owner, "wave", 456)
            try {
                withCache(path) { database ->
                    database.userDao().insertHistory(DbUserHistory(profile, owner, 123))
                    database.emojiDao().insertHistory(emoji)
                }
                // v53 has the same tables and indexes as v54 except for DbProfileTabs.
                BundledSQLiteDriver().open(path).use { connection ->
                    connection.execSQL("DROP TABLE DbProfileTabs")
                    connection.execSQL("UPDATE room_master_table SET identity_hash = 'e7c0d60666bbe4c211548b18b12df19b' WHERE id = 42")
                    connection.execSQL("PRAGMA user_version = 53")
                }
                withCache(path) { database ->
                    assertEquals(listOf(emoji), database.emojiDao().getHistory(owner))
                    val tabs = DbProfileTabs(owner, profile, "[]")
                    database.userDao().insertProfileTabs(tabs)
                    assertEquals(tabs, database.userDao().getProfileTabsSync(owner, profile))
                }
                BundledSQLiteDriver().open(path).use { connection ->
                    connection.prepare("SELECT lastVisit FROM DbUserHistory WHERE userKey = ?").use { query ->
                        query.bindText(1, profile.toString())
                        assertTrue(query.step())
                        assertEquals(123L, query.getLong(0))
                    }
                }
            } finally {
                Files.deleteIfExists(databasePath)
            }
        }

    @Test
    fun migration54To55PurgesWeiboStatusesWhilePreservingHistoryAndOtherAccounts() =
        runTest {
            val databasePath = Files.createTempFile("flare-cache-v54", ".db")
            val path = databasePath.toString()
            val mastoOwner = AccountType.Specific(MicroBlogKey("me", "mastodon.social"))
            val weiboOwner = AccountType.Specific(MicroBlogKey("23", "m.weibo.cn"))
            val profile = MicroBlogKey("alice", "mastodon.social")
            val emoji = DbEmojiHistory(mastoOwner, "tada", 789)
            try {
                withCache(path) { database ->
                    database.userDao().insertHistory(DbUserHistory(profile, mastoOwner, 321))
                    database.emojiDao().insertHistory(emoji)
                    database.userDao().insertProfileTabs(DbProfileTabs(mastoOwner, profile, "[]"))
                }
                BundledSQLiteDriver().open(path).use { connection ->
                    connection.execSQL(
                        "INSERT INTO DbStatus (statusKey, accountType, content, contentFingerprint, renderHash, text, id) " +
                            "VALUES ('100@m.weibo.cn', '${weiboOwner}', X'00', 1, 1, 'weibo', '${weiboOwner}_100@m.weibo.cn')",
                    )
                    connection.execSQL(
                        "INSERT INTO DbPagingTimeline (pagingKey, statusId, sortId, isGap, message, messageRenderHash, " +
                            "semanticReferenceSignature, presentationReferenceSignature, contentRevision, notificationKey, _id) " +
                            "VALUES ('home_23@m.weibo.cn', '${weiboOwner}_100@m.weibo.cn', 1, 0, NULL, NULL, '', '', 0, NULL, 'row1')",
                    )
                    connection.execSQL(
                        "INSERT INTO DbStatus (statusKey, accountType, content, contentFingerprint, renderHash, text, id) " +
                            "VALUES ('200@mastodon.social', '${mastoOwner}', X'00', 2, 2, 'masto', '${mastoOwner}_200@mastodon.social')",
                    )
                    connection.execSQL("PRAGMA user_version = 54")
                }

                withCache(path) { database ->
                    assertEquals(listOf(emoji), database.emojiDao().getHistory(mastoOwner))
                    assertEquals(DbProfileTabs(mastoOwner, profile, "[]"), database.userDao().getProfileTabsSync(mastoOwner, profile))
                }

                BundledSQLiteDriver().open(path).use { connection ->
                    connection.prepare("SELECT COUNT(*) FROM DbStatus WHERE accountType LIKE '%weibo.%'").use { query ->
                        assertTrue(query.step())
                        assertEquals(0L, query.getLong(0))
                    }
                    connection.prepare("SELECT COUNT(*) FROM DbPagingTimeline WHERE pagingKey LIKE '%weibo.%'").use { query ->
                        assertTrue(query.step())
                        assertEquals(0L, query.getLong(0))
                    }
                    connection.prepare("SELECT COUNT(*) FROM DbStatus WHERE accountType LIKE '%mastodon.social%'").use { query ->
                        assertTrue(query.step())
                        assertEquals(1L, query.getLong(0))
                    }
                }
            } finally {
                Files.deleteIfExists(databasePath)
            }
        }

    @Test
    fun cachesWithUiIconPlatformFieldsAreRebuilt() =
        runTest {
            assertCacheIsRebuilt(fromVersion = 52)
        }

    @Test
    fun cachesWithoutPluginMediaOwnershipAreRebuilt() =
        runTest {
            assertCacheIsRebuilt(fromVersion = 51)
        }

    @Test
    fun version44CacheIsRebuiltDestructively() =
        runTest {
            assertCacheIsRebuilt(fromVersion = 44)
        }

    @Test
    fun version45CacheIsRebuiltDestructively() =
        runTest {
            assertCacheIsRebuilt(fromVersion = 45)
        }

    @Test
    fun version47CacheIsRebuiltWithIndependentNotificationEntries() =
        runTest {
            assertCacheIsRebuilt(fromVersion = 47)
        }

    @Test
    fun currentAndExperimentalCachesAreRebuiltWithoutMigration() =
        runTest {
            assertCacheIsRebuilt(fromVersion = 48)
            assertCacheIsRebuilt(fromVersion = 49)
            assertCacheIsRebuilt(fromVersion = 58)
        }

    private suspend fun assertCacheIsRebuilt(fromVersion: Int) {
        val databasePath = Files.createTempFile("flare-cache-v$fromVersion", ".db")
        val path = databasePath.toAbsolutePath().toString()
        try {
            BundledSQLiteDriver().open(path).use { connection ->
                connection.execSQL("CREATE TABLE old_cache_fixture(id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
                connection.execSQL("INSERT INTO old_cache_fixture(value) VALUES ('discard me')")
                connection.execSQL("PRAGMA user_version = $fromVersion")
            }

            withCache(path) { it.useWriterConnection { } }

            BundledSQLiteDriver().open(path).use { connection ->
                connection.prepare("PRAGMA user_version").use { statement ->
                    assertEquals(true, statement.step())
                    assertEquals(CACHE_DATABASE_VERSION.toLong(), statement.getLong(0))
                }
                connection
                    .prepare(
                        "SELECT COUNT(*) FROM sqlite_master " +
                            "WHERE type = 'table' AND name = 'old_cache_fixture'",
                    ).use { statement ->
                        assertEquals(true, statement.step())
                        assertEquals(0L, statement.getLong(0))
                    }
            }
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    private suspend fun withCache(
        path: String,
        block: suspend (CacheDatabase) -> Unit,
    ) {
        val database = buildCacheDatabase(Room.databaseBuilder<CacheDatabase>(name = path))
        try {
            block(database)
        } finally {
            database.close()
        }
    }
}
