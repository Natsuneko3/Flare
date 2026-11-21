package dev.dimension.flare.feature.plugin

import dev.dimension.flare.common.InAppNotification
import dev.dimension.flare.common.Message
import dev.dimension.flare.data.database.cache.dao.PluginPostWidgetDao
import dev.dimension.flare.data.datastore.PlatformOAuthPendingRepository
import dev.dimension.flare.data.io.AppFileStore
import dev.dimension.flare.data.plugin.TimelineProcessorService
import dev.dimension.flare.data.repository.AccountService
import dev.dimension.flare.data.repository.PluginAuthoring
import dev.dimension.flare.data.repository.SettingsRepository
import dev.dimension.flare.data.translation.PluginTranslationService
import dev.dimension.flare.feature.plugin.adapter.PluginTimelineProcessorServiceV1
import dev.dimension.flare.feature.plugin.adapter.PluginTranslationServiceV1
import dev.dimension.flare.feature.plugin.login.PluginOAuthCallbackCoordinatorV1
import dev.dimension.flare.model.PlatformSpecSource
import kotlinx.coroutines.CoroutineScope
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
@Module
@Configuration
internal class PluginKoinModule {
    @Single
    fun translation(subsystem: PluginSubsystemV1): PluginTranslationService = PluginTranslationServiceV1(subsystem)

    @Single(binds = [TimelineProcessorService::class])
    fun timelineProcessor(
        subsystem: PluginSubsystemV1,
        dao: PluginPostWidgetDao,
        settingsRepository: SettingsRepository,
        scope: CoroutineScope,
    ): TimelineProcessorService =
        PluginTimelineProcessorServiceV1(
            subsystem = subsystem,
            dao = dao,
            grants = subsystem.timelineGrants.also { it.bind(SettingsRepositoryTimelineBacking(settingsRepository), scope) },
            scope = scope,
        )

    @Single
    fun authoring(subsystem: PluginSubsystemV1): PluginAuthoring = subsystem.authoring

    @Single
    fun subsystem(
        appFiles: AppFileStore,
        pendingRepository: PlatformOAuthPendingRepository,
    ): PluginSubsystemV1 = PluginSubsystemV1(appFiles, pendingRepository)

    @Single(binds = [PlatformSpecSource::class])
    fun platformSpecSource(
        subsystem: PluginSubsystemV1,
        scope: CoroutineScope,
    ): PlatformSpecSource = subsystem.platformSource(scope)

    @Single
    fun oauthCallbackCoordinator(
        subsystem: PluginSubsystemV1,
        accountService: AccountService,
        notification: InAppNotification,
    ): PluginOAuthCallbackCoordinatorV1 =
        PluginOAuthCallbackCoordinatorV1(
            oauth = subsystem.oauth,
            accountService = accountService,
            onUnattendedFailure = { notification.onError(Message.Plugin, it) },
        )
}
