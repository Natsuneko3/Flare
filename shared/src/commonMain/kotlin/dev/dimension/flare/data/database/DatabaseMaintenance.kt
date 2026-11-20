package dev.dimension.flare.data.database

import androidx.room3.RoomDatabase
import androidx.room3.executeSQL
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.async.step
import dev.dimension.flare.common.PlatformDispatchers
import dev.dimension.flare.data.database.app.AppDatabase
import dev.dimension.flare.data.database.cache.CacheDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Single
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
public interface DatabaseMaintenance {
    public fun startWarmup()

    public suspend fun awaitWarmup()

    public suspend fun checkpoint()
}

@OptIn(ExperimentalAtomicApi::class)
@Single(binds = [DatabaseMaintenance::class])
internal class RoomDatabaseMaintenance(
    private val appDatabase: AppDatabase,
    private val cacheDatabase: CacheDatabase,
    private val coroutineScope: CoroutineScope,
) : DatabaseMaintenance {
    private val warmupStarted = AtomicBoolean(false)
    private val appWarmupReady = CompletableDeferred<Unit>()
    private val cacheWarmupReady = CompletableDeferred<Unit>()

    override fun startWarmup() {
        if (!warmupStarted.compareAndSet(expectedValue = false, newValue = true)) {
            return
        }
        warmupAndKeepInvalidationTracked(
            database = appDatabase,
            tables = APP_CORE_TABLES,
            ready = appWarmupReady,
        )
        warmupAndKeepInvalidationTracked(
            database = cacheDatabase,
            tables = CACHE_CORE_TABLES,
            ready = cacheWarmupReady,
        )
    }

    override suspend fun awaitWarmup() {
        startWarmup()
        appWarmupReady.await()
        cacheWarmupReady.await()
    }

    override suspend fun checkpoint(): Unit =
        withContext(PlatformDispatchers.IO) {
            cacheDatabase.checkpointTruncate()
            appDatabase.checkpointTruncate()
        }

    private fun warmupAndKeepInvalidationTracked(
        database: RoomDatabase,
        tables: Array<String>,
        ready: CompletableDeferred<Unit>,
    ) {
        coroutineScope.launch(PlatformDispatchers.IO) {
            var initialized = false
            try {
                // Open the writer and all pooled reader connections before syncTriggers() runs:
                // Room calls TriggerBasedInvalidationTracker.configureConnection() -> forceNeedSync()
                // the first time each pooled connection opens, which would otherwise set needsSync = true
                // later and force subsequent createFlow() subscribers onto the writer connection.
                warmConnectionPool(database)
                database
                    .invalidationTracker
                    .createFlow(*tables, emitInitialState = true)
                    .collect {
                        if (!initialized) {
                            initialized = true
                            ready.complete(Unit)
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                ready.complete(Unit)
            }
        }
    }

    private suspend fun warmConnectionPool(database: RoomDatabase) {
        val isWal =
            database.useReaderConnection { transactor ->
                transactor.usePrepared("PRAGMA journal_mode") { statement ->
                    statement.step() && statement.getText(0).equals("wal", ignoreCase = true)
                }
            }
        val poolSize = DEFAULT_READER_POOL_SIZE
        if (!isWal || poolSize <= 1) {
            return
        }
        database.useWriterConnection { }
        // Launch sibling coroutines outside useReaderConnection so Room's reentrant ConnectionElement
        // is not inherited and all pooled reader connections open independently.
        val openedCount = AtomicInt(0)
        val allReadersOpened = CompletableDeferred<Unit>()
        coroutineScope {
            val readerJobs =
                (0 until poolSize).map {
                    launch {
                        database.useReaderConnection {
                            if (openedCount.addAndFetch(1) == poolSize) {
                                allReadersOpened.complete(Unit)
                            }
                            allReadersOpened.await()
                        }
                    }
                }
            var completed = false
            try {
                completed =
                    withTimeoutOrNull(READER_WARMUP_TIMEOUT_MS) {
                        allReadersOpened.await()
                        true
                    } == true
            } finally {
                allReadersOpened.complete(Unit)
                if (!completed) {
                    readerJobs.forEach { it.cancel() }
                }
            }
        }
    }

    private suspend fun RoomDatabase.checkpointTruncate() {
        currentCoroutineContext().ensureActive()
        try {
            useWriterConnection { transactor ->
                currentCoroutineContext().ensureActive()
                transactor.executeSQL("PRAGMA busy_timeout = $CHECKPOINT_BUSY_TIMEOUT_MS")
                try {
                    transactor.executeSQL("PRAGMA wal_checkpoint(TRUNCATE)")
                } finally {
                    runCatching {
                        transactor.executeSQL("PRAGMA busy_timeout = $ROOM_DEFAULT_BUSY_TIMEOUT_MS")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val READER_WARMUP_TIMEOUT_MS = 250L
        const val CHECKPOINT_BUSY_TIMEOUT_MS = 250
        const val ROOM_DEFAULT_BUSY_TIMEOUT_MS = 3000

        val APP_CORE_TABLES =
            arrayOf(
                "DbAccount",
                "DbKeywordFilter",
                "DbRssSources",
            )

        val CACHE_CORE_TABLES =
            arrayOf(
                "DbUser",
                "DbPagingTimeline",
                "DbStatus",
                "status_reference",
                "timeline_item_presentation_reference",
                "DbTranslation",
            )
    }
}
