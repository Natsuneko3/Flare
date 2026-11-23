package dev.dimension.flare.data.database.cache

import androidx.room3.Room
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.dimension.flare.data.database.buildCacheDatabase
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class CacheDatabaseDestructiveMigrationTest {
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
            assertCacheIsRebuilt(fromVersion = 53)
            assertCacheIsRebuilt(fromVersion = 54)
            assertCacheIsRebuilt(fromVersion = 55)
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
