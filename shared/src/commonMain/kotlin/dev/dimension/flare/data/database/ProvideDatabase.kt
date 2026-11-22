package dev.dimension.flare.data.database

import androidx.room3.RoomDatabase
import dev.dimension.flare.common.PlatformDispatchers
import dev.dimension.flare.data.database.app.AppDatabase
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.cache.TimelineRevisionCallback
import dev.dimension.flare.data.database.cache.dao.PluginPostWidgetDao
import org.koin.core.annotation.Single

@Single
internal fun provideAppDatabase(driverFactory: DriverFactory): AppDatabase =
    driverFactory
        .createBuilder<AppDatabase>("app.db")
        .addMigrations(
            AppDatabase.MIGRATION_8_9,
            AppDatabase.MIGRATION_9_10,
            AppDatabase.MIGRATION_10_11,
            AppDatabase.MIGRATION_11_12,
            AppDatabase.MIGRATION_12_13,
        ).setDriver(createDatabaseDriver())
        .setQueryCoroutineContext(PlatformDispatchers.IO)
        .build()

internal const val CACHE_DATABASE_NAME = "cache.db"

@Single
internal fun provideCacheDatabase(driverFactory: DriverFactory): CacheDatabase =
    buildCacheDatabase(driverFactory.createBuilder(CACHE_DATABASE_NAME, isCache = true))

@Single
internal fun providePluginPostWidgetDao(cacheDatabase: CacheDatabase): PluginPostWidgetDao = cacheDatabase.pluginPostWidgetDao()

internal fun buildCacheDatabase(builder: RoomDatabase.Builder<CacheDatabase>): CacheDatabase =
    builder
        .addMigrations(
            CacheDatabase.MIGRATION_53_54,
            CacheDatabase.MIGRATION_54_55,
        ).fallbackToDestructiveMigration(dropAllTables = true)
        .addCallback(TimelineRevisionCallback)
        .setDriver(createDatabaseDriver())
        .setQueryCoroutineContext(PlatformDispatchers.IO)
        .build()
