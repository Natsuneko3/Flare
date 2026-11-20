package dev.dimension.flare.data.database.cache

import androidx.paging.PagingSource
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.deferredTransaction
import androidx.room3.executeSQL
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import dev.dimension.flare.common.Locale
import dev.dimension.flare.common.OnDeviceAI
import dev.dimension.flare.createTestFileSystem
import dev.dimension.flare.createTestRootPath
import dev.dimension.flare.data.database.RoomDatabaseMaintenance
import dev.dimension.flare.data.database.app.AppDatabase
import dev.dimension.flare.data.database.buildCacheDatabase
import dev.dimension.flare.data.database.cache.mapper.saveToDatabase
import dev.dimension.flare.data.database.cache.mapper.toDbUser
import dev.dimension.flare.data.database.cache.model.DbTranslation
import dev.dimension.flare.data.database.cache.model.TranslationEntityType
import dev.dimension.flare.data.database.cache.model.TranslationPayload
import dev.dimension.flare.data.database.cache.model.TranslationStatus
import dev.dimension.flare.data.database.createDatabaseDriver
import dev.dimension.flare.data.datasource.microblog.paging.OffsetFromStartPagingSource
import dev.dimension.flare.data.datasource.microblog.paging.TimelineDbPageCache
import dev.dimension.flare.data.datasource.microblog.paging.TimelineDbPageLoader
import dev.dimension.flare.data.datasource.microblog.paging.TimelinePagingMapper
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.io.OkioFileStorage
import dev.dimension.flare.data.network.ai.AiCompletionService
import dev.dimension.flare.data.network.ai.OpenAIService
import dev.dimension.flare.data.translation.OnlinePreTranslationService
import dev.dimension.flare.deleteTestRootPath
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.UiTranslatableText
import dev.dimension.flare.ui.model.createSampleUser
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.render.toUiPlainText
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CacheDatabaseStartupMaintenanceTest {
    @Test
    fun writerConnectionTruncatesWalEvenWhenReaderOpensFirst() =
        runBlocking {
            val path = Files.createTempFile("flare-wal-limit", ".db")
            val walPath = path.resolveSibling("${path.fileName}-wal")
            val db = buildCacheDatabase(Room.databaseBuilder<CacheDatabase>(name = path.toString()))
            try {
                // Open Reader #1 first so one-shot Callback.onOpen runs on a reader instead of the writer.
                db.pagingTimelineDao().anyPaging("startup")

                db.connect {
                    saveToDatabase(
                        db,
                        TimelinePagingMapper.toDb((0 until 60).map(::samplePost), "startup"),
                    )
                }
                val peakWalBytes = Files.size(walPath)
                assertTrue(peakWalBytes > 500_000L, "Expected large WAL before passive checkpoint, got $peakWalBytes")

                // SQLite's auto-checkpoint uses PASSIVE mode and truncates the WAL down to journal_size_limit
                // when the next write transaction resets the WAL after a complete passive checkpoint.
                db.useWriterConnection { transactor ->
                    val writerLimit =
                        transactor.usePrepared("PRAGMA journal_size_limit") { statement ->
                            check(statement.step())
                            statement.getLong(0)
                        }
                    assertEquals(0L, writerLimit, "Writer connection must be configured with PRAGMA journal_size_limit = 0")
                    transactor.executeSQL("PRAGMA wal_checkpoint(PASSIVE)")
                }
                db.translationDao().insert(
                    DbTranslation(
                        entityType = TranslationEntityType.Status,
                        entityKey = "status:wal-reset",
                        targetLanguage = Locale.language,
                        sourceHash = "hash-reset",
                        status = TranslationStatus.Completed,
                        payload = TranslationPayload(content = "tiny".toUiPlainText()),
                        updatedAt = 1L,
                    ),
                )

                val shrunkWalBytes = Files.size(walPath)
                assertTrue(
                    shrunkWalBytes < peakWalBytes / 10,
                    "Expected WAL to shrink after passive checkpoint + WAL reset, but peak=$peakWalBytes shrunk=$shrunkWalBytes",
                )
            } finally {
                db.close()
                deleteDbFiles(path)
            }
        }

    @Test
    fun reopeningInitializedCacheDatabaseSkipsTriggerDdlStatements() =
        runBlocking {
            val path = Files.createTempFile("flare-trigger-reopen", ".db")
            val initialDb = buildCacheDatabase(Room.databaseBuilder<CacheDatabase>(name = path.toString()))
            try {
                initialDb.connect {
                    saveToDatabase(initialDb, TimelinePagingMapper.toDb(listOf(samplePost(1)), "startup"))
                }
            } finally {
                initialDb.close()
            }

            val recorder = RecordingDriver(createDatabaseDriver())
            val reopenedDb =
                Room
                    .databaseBuilder<CacheDatabase>(name = path.toString())
                    .addMigrations(CacheDatabase.MIGRATION_53_54)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .addCallback(TimelineRevisionCallback)
                    .setDriver(recorder)
                    .setQueryCoroutineContext(Dispatchers.IO)
                    .build()
            try {
                val before = TimelineDbPageLoader(reopenedDb, "startup", TimelineDbPageCache()).load(0, 20)
                assertEquals(1, before.size)

                val revisionTriggerDdls =
                    recorder.preparedSql.filter { sql ->
                        (sql.startsWith("DROP TRIGGER", ignoreCase = true) || sql.startsWith("CREATE TRIGGER", ignoreCase = true)) &&
                            "timeline_" in sql
                    }
                assertEquals(
                    emptyList(),
                    revisionTriggerDdls,
                    "Reopening an already-initialized CacheDatabase must not re-run 22 DROP/CREATE TRIGGER DDLs",
                )

                // Verify the existing triggers remain active and increment contentRevision on status update.
                val updated = samplePost(1).copy(content = UiTranslatableText("Updated body".toUiPlainText()))
                reopenedDb.connect {
                    reopenedDb.statusDao().update(
                        updated.statusKey,
                        AccountType.Guest,
                        updated,
                        updated.renderHash,
                        updated.searchText,
                    )
                }
                val after = TimelineDbPageLoader(reopenedDb, "startup", TimelineDbPageCache()).load(0, 20)
                assertTrue(
                    after.single().identity.contentRevision > before.single().identity.contentRevision,
                    "Timeline revision trigger must remain active across database reopens",
                )
            } finally {
                reopenedDb.close()
                deleteDbFiles(path)
            }
        }

    @Test
    fun reopeningCacheDatabaseRepairsMissingAndLegacyTimelineTriggers() =
        runBlocking {
            val path = Files.createTempFile("flare-trigger-repair", ".db")
            val initialDb = buildCacheDatabase(Room.databaseBuilder<CacheDatabase>(name = path.toString()))
            try {
                initialDb.useWriterConnection { transactor ->
                    transactor.executeSQL("DROP TRIGGER timeline_status_update_revision_v2")
                    transactor.executeSQL(
                        "CREATE TRIGGER timeline_status_insert_revision AFTER INSERT ON DbStatus BEGIN SELECT 1; END",
                    )
                }
            } finally {
                initialDb.close()
            }

            val reopenedDb = buildCacheDatabase(Room.databaseBuilder<CacheDatabase>(name = path.toString()))
            try {
                reopenedDb.useReaderConnection { transactor ->
                    val triggerNames = mutableSetOf<String>()
                    transactor.usePrepared(
                        "SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'timeline_%'",
                    ) { statement ->
                        while (statement.step()) {
                            triggerNames += statement.getText(0)
                        }
                    }
                    assertTrue(
                        "timeline_status_insert_revision" !in triggerNames,
                        "Legacy unversioned timeline trigger must be dropped on reopen, found: $triggerNames",
                    )
                    assertTrue(
                        "timeline_status_update_revision_v2" in triggerNames,
                        "Missing _v2 timeline trigger must be recreated on reopen, found: $triggerNames",
                    )
                    assertEquals(11, triggerNames.count { it.endsWith("_v2") })
                }
            } finally {
                reopenedDb.close()
                deleteDbFiles(path)
            }
        }

    @Test
    fun startupPreTranslationCleanupSkipsWriterWhenNoStaleRowsExistAndMarksStaleRowsWhenPresent() =
        runBlocking {
            val path = Files.createTempFile("flare-pretranslation-startup", ".db")
            val root = createTestRootPath()
            val recorder = RecordingDriver(createDatabaseDriver())
            val db =
                Room
                    .databaseBuilder<CacheDatabase>(name = path.toString())
                    .addCallback(TimelineRevisionCallback)
                    .setDriver(recorder)
                    .setQueryCoroutineContext(Dispatchers.IO)
                    .build()
            val appDataStore = AppDataStore(OkioFileStorage(createTestFileSystem(), root))
            try {
                db.translationDao().insert(
                    DbTranslation(
                        entityType = TranslationEntityType.Status,
                        entityKey = "status:completed",
                        targetLanguage = Locale.language,
                        sourceHash = "hash-1",
                        status = TranslationStatus.Completed,
                        payload = TranslationPayload(content = "done".toUiPlainText()),
                        updatedAt = 1L,
                    ),
                )
                // Open at least one reader before holding the writer so pool setup is complete.
                db.pagingTimelineDao().anyPaging("startup")
                recorder.preparedSql.clear()

                val writerEntered = CompletableDeferred<Unit>()
                val releaseWriter = CompletableDeferred<Unit>()
                val writerHolder =
                    launch(Dispatchers.IO) {
                        db.connect {
                            writerEntered.complete(Unit)
                            releaseWriter.await()
                        }
                    }
                writerEntered.await()

                val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                try {
                    OnlinePreTranslationService(
                        database = db,
                        appDataStore = appDataStore,
                        aiCompletionService = AiCompletionService(OpenAIService(), NoopTestOnDeviceAI),
                        coroutineScope = serviceScope,
                    )
                    val checkedWithoutWriter =
                        withTimeoutOrNull(1_000) {
                            while (recorder.preparedSql.none { "DbTranslation" in it && it.startsWith("SELECT", ignoreCase = true) }) {
                                delay(10)
                            }
                            true
                        } == true
                    assertTrue(
                        checkedWithoutWriter,
                        "Startup stale-translation check must run on a reader connection without blocking on the writer",
                    )
                    releaseWriter.complete(Unit)
                    writerHolder.join()
                    delay(50)
                    val staleUpdates =
                        recorder.preparedSql.filter {
                            it.startsWith("UPDATE DbTranslation", ignoreCase = true)
                        }
                    assertEquals(
                        emptyList(),
                        staleUpdates,
                        "Startup must not execute UPDATE DbTranslation when no stale in-flight translations exist",
                    )
                } finally {
                    releaseWriter.complete(Unit)
                    writerHolder.join()
                    serviceScope.cancel()
                }

                // Now insert an actual stale Pending row and verify a new service instance marks it Failed.
                db.translationDao().insert(
                    DbTranslation(
                        entityType = TranslationEntityType.Status,
                        entityKey = "status:stale-pending",
                        targetLanguage = Locale.language,
                        sourceHash = "hash-stale",
                        status = TranslationStatus.Pending,
                        updatedAt = 1L,
                    ),
                )
                val secondScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                try {
                    OnlinePreTranslationService(
                        database = db,
                        appDataStore = appDataStore,
                        aiCompletionService = AiCompletionService(OpenAIService(), NoopTestOnDeviceAI),
                        coroutineScope = secondScope,
                    )
                    val cleaned =
                        withTimeout(2_000) {
                            db
                                .translationDao()
                                .find(TranslationEntityType.Status, "status:stale-pending", Locale.language)
                                .filterNotNull()
                                .first { it.status == TranslationStatus.Failed }
                        }
                    assertEquals("stale_in_flight", cleaned.statusReason)
                } finally {
                    secondScope.cancel()
                }
            } finally {
                db.close()
                deleteDbFiles(path)
                deleteTestRootPath(root)
            }
        }

    @Test
    fun checkpointTruncatesNonEmptyWalToZeroBytesEvenWhileWarmupObserverIsActive() =
        runBlocking {
            val appPath = Files.createTempFile("flare-checkpoint-app", ".db")
            val cachePath = Files.createTempFile("flare-checkpoint-cache", ".db")
            val cacheWalPath = cachePath.resolveSibling("${cachePath.fileName}-wal")
            val appDb =
                Room
                    .databaseBuilder<AppDatabase>(name = appPath.toString())
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(Dispatchers.IO)
                    .build()
            val cacheDb = buildCacheDatabase(Room.databaseBuilder<CacheDatabase>(name = cachePath.toString()))
            val maintenanceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            try {
                val maintenance = RoomDatabaseMaintenance(appDb, cacheDb, maintenanceScope)
                maintenance.startWarmup()
                maintenance.awaitWarmup()

                cacheDb.connect {
                    saveToDatabase(cacheDb, TimelinePagingMapper.toDb(listOf(samplePost(0)), "startup"))
                }

                // Pin an active reader snapshot while committing a large write batch so auto-checkpoint cannot
                // checkpoint the newly written WAL frames.
                val readerHoldingSnapshot = CompletableDeferred<Unit>()
                val releaseReader = CompletableDeferred<Unit>()
                val readerJob =
                    launch(Dispatchers.IO) {
                        cacheDb.useReaderConnection { transactor ->
                            transactor.deferredTransaction {
                                usePrepared("SELECT COUNT(*) FROM DbPagingTimeline") { it.step() }
                                readerHoldingSnapshot.complete(Unit)
                                releaseReader.await()
                            }
                        }
                    }
                try {
                    readerHoldingSnapshot.await()

                    cacheDb.connect {
                        saveToDatabase(cacheDb, TimelinePagingMapper.toDb((1 until 60).map(::samplePost), "startup"))
                    }
                    assertTrue(Files.exists(cacheWalPath) && Files.size(cacheWalPath) > 0L, "Expected uncheckpointed WAL frames on disk")
                } finally {
                    releaseReader.complete(Unit)
                    readerJob.join()
                }

                maintenance.checkpoint()

                assertEquals(
                    0L,
                    if (Files.exists(cacheWalPath)) Files.size(cacheWalPath) else 0L,
                    "Background checkpoint must truncate cache.db-wal to 0 bytes so future cold starts have zero WAL recovery work",
                )
            } finally {
                maintenanceScope.cancel()
                cacheDb.close()
                appDb.close()
                deleteDbFiles(cachePath)
                deleteDbFiles(appPath)
            }
        }

    @Test
    fun cancelledCheckpointDoesNotAcquireWriterConnections() =
        runBlocking {
            val appPath = Files.createTempFile("flare-cancel-app", ".db")
            val cachePath = Files.createTempFile("flare-cancel-cache", ".db")
            val appRecorder = RecordingDriver(createDatabaseDriver())
            val cacheRecorder = RecordingDriver(createDatabaseDriver())
            val appDb =
                Room
                    .databaseBuilder<AppDatabase>(name = appPath.toString())
                    .setDriver(appRecorder)
                    .setQueryCoroutineContext(Dispatchers.IO)
                    .build()
            val cacheDb =
                Room
                    .databaseBuilder<CacheDatabase>(name = cachePath.toString())
                    .addCallback(TimelineRevisionCallback)
                    .setDriver(cacheRecorder)
                    .setQueryCoroutineContext(Dispatchers.IO)
                    .build()
            val maintenanceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            try {
                val maintenance = RoomDatabaseMaintenance(appDb, cacheDb, maintenanceScope)
                val cancelledScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                cancelledScope.cancel()
                val job =
                    cancelledScope.launch {
                        maintenance.checkpoint()
                    }
                job.join()

                assertTrue(
                    cacheRecorder.preparedSql.none { "wal_checkpoint" in it },
                    "Cancelled checkpoint coroutine must not run wal_checkpoint on CacheDatabase",
                )
                assertTrue(
                    appRecorder.preparedSql.none { "wal_checkpoint" in it },
                    "Cancelled checkpoint coroutine must not run wal_checkpoint on AppDatabase",
                )
            } finally {
                maintenanceScope.cancel()
                cacheDb.close()
                appDb.close()
                deleteDbFiles(cachePath)
                deleteDbFiles(appPath)
            }
        }

    @Test
    fun warmupPreRegistersCoreInvalidationTriggersSoFirstReadsSkipWriterSync() =
        runBlocking {
            val appPath = Files.createTempFile("flare-warmup-app", ".db")
            val cachePath = Files.createTempFile("flare-warmup-cache", ".db")
            val user = createSampleUser()

            // Seed cache.db and close it to simulate a real cold launch against an existing cache on disk.
            val seedDb = buildCacheDatabase(Room.databaseBuilder<CacheDatabase>(name = cachePath.toString()))
            try {
                seedDb.connect {
                    seedDb.userDao().insert(user.toDbUser())
                    saveToDatabase(seedDb, TimelinePagingMapper.toDb((0 until 20).map(::samplePost), "startup"))
                }
            } finally {
                seedDb.close()
            }

            val appDb =
                Room
                    .databaseBuilder<AppDatabase>(name = appPath.toString())
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(Dispatchers.IO)
                    .build()
            val cacheDb = buildCacheDatabase(Room.databaseBuilder<CacheDatabase>(name = cachePath.toString()))
            val maintenanceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            try {
                val maintenance = RoomDatabaseMaintenance(appDb, cacheDb, maintenanceScope)
                maintenance.startWarmup()
                maintenance.awaitWarmup()

                // Lock CacheDatabase's single writer connection to prove neither ActiveAccount/Home user lookup
                // nor OffsetFromStartPagingSource's first-page load needs writer-bound syncTriggers() anymore.
                val writerEntered = CompletableDeferred<Unit>()
                val releaseWriter = CompletableDeferred<Unit>()
                val writerHolder =
                    launch(Dispatchers.IO) {
                        cacheDb.connect {
                            writerEntered.complete(Unit)
                            releaseWriter.await()
                        }
                    }
                writerEntered.await()

                // Exercise all 4 pooled reader connections concurrently while the writer is held, matching
                // multi-coroutine startup reads (pre-translation check, user lookup, multi-column timelines).
                val allReadersEntered = CompletableDeferred<Unit>()
                val activeReaders = AtomicInteger(0)
                val readerJobs =
                    (0 until 4).map {
                        launch(Dispatchers.IO) {
                            cacheDb.useReaderConnection {
                                if (activeReaders.incrementAndGet() == 4) {
                                    allReadersEntered.complete(Unit)
                                }
                                allReadersEntered.await()
                            }
                        }
                    }
                try {
                    withTimeout(1_000) { allReadersEntered.await() }
                } finally {
                    allReadersEntered.complete(Unit)
                    readerJobs.forEach { it.join() }
                }

                val pagingSource = OffsetFromStartPagingSource(TimelineDbPageLoader(cacheDb, "startup", TimelineDbPageCache()))
                val userDeferred =
                    async(Dispatchers.IO) {
                        cacheDb.userDao().findByKey(user.key).first()
                    }
                val pageDeferred =
                    async(Dispatchers.IO) {
                        pagingSource.load(PagingSource.LoadParams.Refresh(null, 20, false))
                    }
                try {
                    val loadedUser =
                        withTimeoutOrNull(1_000) {
                            userDeferred.await()
                        }
                    val loadedPage =
                        withTimeoutOrNull(1_000) {
                            pageDeferred.await()
                        }
                    assertNotNull(
                        loadedUser,
                        "UserDao.findByKey must emit on a reader connection without blocking on writer syncTriggers()",
                    )
                    val page =
                        assertNotNull(
                            loadedPage as? PagingSource.LoadResult.Page,
                            "OffsetFromStartPagingSource first page must load on a reader connection without blocking on writer syncTriggers()",
                        )
                    assertEquals(20, page.data.size)
                } finally {
                    userDeferred.cancel()
                    pageDeferred.cancel()
                    releaseWriter.complete(Unit)
                    writerHolder.join()
                    pagingSource.invalidate()
                }
            } finally {
                maintenanceScope.cancel()
                cacheDb.close()
                appDb.close()
                deleteDbFiles(cachePath)
                deleteDbFiles(appPath)
            }
        }

    private fun samplePost(id: Int) =
        UiTimelineV2.Post(
            platformId = "xQt",
            images = persistentListOf(),
            sensitive = false,
            contentWarning = null,
            user = null,
            content = UiTranslatableText(("Post $id " + "payload ".repeat(250)).toUiPlainText()),
            actions = persistentListOf(),
            poll = null,
            statusKey = MicroBlogKey(id.toString(), "example.invalid"),
            card = null,
            createdAt = Instant.fromEpochMilliseconds(id.toLong()).toUi(),
            references = persistentListOf(),
            clickEvent = ClickEvent.Noop,
            accountType = AccountType.Guest,
        )

    private fun deleteDbFiles(path: Path) {
        listOf("", "-wal", "-shm").forEach { suffix ->
            Files.deleteIfExists(path.resolveSibling("${path.fileName}$suffix"))
        }
    }

    private class RecordingDriver(
        private val delegate: SQLiteDriver,
    ) : SQLiteDriver by delegate {
        val preparedSql = CopyOnWriteArrayList<String>()

        override fun open(fileName: String): SQLiteConnection {
            val connection = delegate.open(fileName)
            return object : SQLiteConnection by connection {
                override fun prepare(sql: String): SQLiteStatement {
                    preparedSql += sql.trim()
                    return connection.prepare(sql)
                }
            }
        }
    }

    private object NoopTestOnDeviceAI : OnDeviceAI {
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
}
