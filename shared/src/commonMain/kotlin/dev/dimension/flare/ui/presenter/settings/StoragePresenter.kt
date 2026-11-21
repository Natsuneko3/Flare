package dev.dimension.flare.ui.presenter.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.room3.RoomDatabase
import androidx.room3.useWriterConnection
import androidx.sqlite.async.step
import dev.dimension.flare.data.database.app.AppDatabase
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.repository.UgoiraRepository
import dev.dimension.flare.di.koinInject
import dev.dimension.flare.ui.presenter.PresenterBase
import dev.dimension.flare.web.shared.WebPresenter
import kotlinx.coroutines.launch

@WebPresenter("storage")
public class StoragePresenter : PresenterBase<StorageState>() {
    private val appDataStore by koinInject<dev.dimension.flare.data.datastore.AppDataStore>()
    private val appDatabase by koinInject<AppDatabase>()
    private val cacheDatabase by koinInject<CacheDatabase>()
    private val ugoiraRepository by koinInject<UgoiraRepository>()
    private val imageTranslationRepository by koinInject<dev.dimension.flare.data.translation.image.ImageTranslationRepository>()

    @Throws(Exception::class)
    public suspend fun clearMediaCache() {
        imageTranslationRepository.clear()
        ugoiraRepository.clear()
    }

    @Throws(Exception::class)
    public suspend fun mediaCacheSize(): Long = imageTranslationRepository.size() + ugoiraRepository.size()

    @Throws(Exception::class)
    public suspend fun clearUgoiraCache(): Unit = ugoiraRepository.clear()

    @Throws(Exception::class)
    public suspend fun ugoiraCacheSize(): Long = ugoiraRepository.size()

    /** Returns false if a database is still in use and its WAL could not be truncated. */
    @Throws(Exception::class)
    public suspend fun clearCacheSuspend(): Boolean {
        cacheDatabase.clearAllTables()
        cacheDatabase.timelineResets.value += 1
        appDataStore.homeTimelinePreferences.updateData {
            dev.dimension.flare.data.datastore.model
                .HomeTimelinePreferences()
        }
        val cacheWalCleared = cacheDatabase.truncateWal()
        val appWalCleared = appDatabase.truncateWal()
        return cacheWalCleared && appWalCleared
    }

    @Composable
    override fun body(): StorageState {
        val scope = rememberCoroutineScope()
        val statusCount by remember {
            cacheDatabase
                .statusDao()
                .count()
        }.collectAsState(0L)
        val userCount by remember {
            cacheDatabase
                .userDao()
                .count()
        }.collectAsState(0L)
        return object : StorageState {
            override val userCount: Long = userCount
            override val statusCount: Long = statusCount

            override fun clearCache() {
                scope.launch {
                    clearCacheSuspend()
                }
            }
        }
    }
}

private suspend fun RoomDatabase.truncateWal(): Boolean =
    useWriterConnection { writer ->
        writer.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { result ->
            result.step() && result.getLong(0) == 0L
        }
    }

@Immutable
public interface StorageState {
    public val userCount: Long
    public val statusCount: Long

    public fun clearCache()
}
