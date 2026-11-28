package dev.dimension.flare.data.database.cache

import androidx.room3.Room
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.dimension.flare.data.database.buildCacheDatabase
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class CacheDatabaseDestructiveMigrationTest {
    @Test
    fun masterV56CacheWithoutMangaTablesIsSilentlyRebuiltAtCurrentVersion() =
        runTest {
            assertEquals(57, CACHE_DATABASE_VERSION)
            val databasePath = Files.createTempFile("flare-cache-master-v56", ".db")
            val path = databasePath.toAbsolutePath().toString()
            val profileKey = MicroBlogKey("me", "example.social")
            val owner = AccountType.Specific(profileKey)
            try {
                // Seed current schema then downgrade to a master-like v56 schema without manga tables or matching identity hash.
                withCache(path) { database ->
                    database.userDao().getProfileTabsSync(owner, profileKey)
                }
                BundledSQLiteDriver().open(path).use { connection ->
                    connection.execSQL("DROP TABLE DbMangaChapterProgress")
                    connection.execSQL("DROP TABLE DbMangaState")
                    connection.execSQL("UPDATE room_master_table SET identity_hash = '301b52290882c35fd112a882504b2475' WHERE id = 42")
                    connection.execSQL("PRAGMA user_version = 56")
                }

                withCache(path) { rebuilt ->
                    assertEquals(0L, rebuilt.statusDao().count().first())
                    assertEquals(null, rebuilt.mangaProgressDao().getState(owner, MicroBlogKey("m-1", "example.social")))
                }

                BundledSQLiteDriver().open(path).use { connection ->
                    connection.prepare("PRAGMA user_version").use { statement ->
                        assertEquals(true, statement.step())
                        assertEquals(CACHE_DATABASE_VERSION.toLong(), statement.getLong(0))
                    }
                }
            } finally {
                Files.deleteIfExists(databasePath)
            }
        }

    @Test
    fun olderAndNewerCacheVersionsAreRebuiltWithoutMigration() =
        runTest {
            listOf(44, 45, 47, 48, 49, 51, 52, 53, 54, 55, 56, 58).forEach { version ->
                assertCacheIsRebuilt(fromVersion = version)
            }
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
