package dev.dimension.flare.ui.presenter.settings

import androidx.room3.RoomDatabase
import androidx.room3.useReaderConnection
import androidx.sqlite.async.step
import androidx.sqlite.execSQL
import dev.dimension.flare.createTestFileSystem
import dev.dimension.flare.data.database.DriverFactory
import dev.dimension.flare.data.database.app.AppDatabase
import dev.dimension.flare.data.database.app.model.DbAccount
import dev.dimension.flare.data.database.buildCacheDatabase
import dev.dimension.flare.data.database.cache.model.DbPagingKey
import dev.dimension.flare.data.database.createDatabaseDriver
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.io.OkioFileStorage
import dev.dimension.flare.di.startKoin
import dev.dimension.flare.di.testSingle
import dev.dimension.flare.model.MicroBlogKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class StorageWalTest {
    @Test
    fun manualClearReclaimsBothWalsAndPreservesTheAccount() =
        runTest {
            val factory = DriverFactory()
            val folder = "storage-wal-test-${Uuid.random()}"
            val appDir = factory.databaseDirPath(isCache = false).toPath() / folder
            val cacheDir = factory.databaseDirPath(isCache = true).toPath() / folder
            val fs = createTestFileSystem()
            val directories = setOf(appDir, cacheDir)
            directories.forEach { fs.createDirectories(it) }
            val app =
                factory
                    .createBuilder<AppDatabase>("$folder/app.db")
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(Dispatchers.Default)
                    .build()
            val cache = buildCacheDatabase(factory.createBuilder("$folder/cache.db", isCache = true))
            try {
                startKoin {
                    modules(
                        module {
                            testSingle { app }
                            testSingle { cache }
                            testSingle { AppDataStore(OkioFileStorage(fs, appDir)) }
                        },
                    )
                }
                assertEquals(-1L, app.journalSizeLimit())
                assertEquals(-1L, cache.journalSizeLimit())
                val account = DbAccount(MicroBlogKey("me", "example.org"), "{}", "Mastodon", 1L)
                val presenter = StoragePresenter()
                val appWal = appDir / "app.db-wal"
                val cacheWal = cacheDir / "cache.db-wal"
                createDatabaseDriver().open((appDir / "app.db").toString()).use { reader ->
                    reader.execSQL("BEGIN")
                    reader.execSQL("SELECT count(*) FROM DbAccount")
                    app.accountDao().insert(account)
                    cache.pagingTimelineDao().insertPagingKey(DbPagingKey("home"))
                    assertTrue(fs.metadata(appWal).size!! > 0)
                    assertTrue(fs.metadata(cacheWal).size!! > 0)

                    assertFalse(presenter.clearCacheSuspend())
                    assertEquals(0L, fs.metadata(cacheWal).size)
                    assertTrue(fs.metadata(appWal).size!! > 0)
                    assertNull(cache.pagingTimelineDao().getPagingKey("home"))
                }

                assertTrue(presenter.clearCacheSuspend())
                assertEquals(0L, fs.metadata(appWal).size)
                assertEquals(0L, fs.metadata(cacheWal).size)
                assertEquals(account, app.accountDao().getAccount(account.account_key))
            } finally {
                stopKoin()
                cache.close()
                app.close()
                directories.forEach { fs.deleteRecursively(it) }
            }
        }

    private suspend fun RoomDatabase.journalSizeLimit(): Long =
        useReaderConnection { reader ->
            reader.usePrepared("PRAGMA journal_size_limit") { result ->
                check(result.step())
                result.getLong(0)
            }
        }
}
