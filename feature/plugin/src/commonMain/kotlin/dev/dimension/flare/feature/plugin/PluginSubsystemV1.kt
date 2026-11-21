package dev.dimension.flare.feature.plugin

import dev.dimension.flare.data.datastore.PlatformOAuthPendingRepository
import dev.dimension.flare.data.io.AppFileStore
import dev.dimension.flare.data.plugin.TimelineProcessorService
import dev.dimension.flare.data.repository.AccountService
import dev.dimension.flare.data.repository.SettingsRepository
import dev.dimension.flare.di.koinGet
import dev.dimension.flare.feature.plugin.adapter.PluginPlatformSpecSourceV1
import dev.dimension.flare.feature.plugin.authoring.PluginAuthoringServiceV1
import dev.dimension.flare.feature.plugin.development.PluginDevelopmentSessionV1
import dev.dimension.flare.feature.plugin.development.PluginDevelopmentStateV1
import dev.dimension.flare.feature.plugin.extension.PluginExtensionServiceV1
import dev.dimension.flare.feature.plugin.github.GitHubPluginClient
import dev.dimension.flare.feature.plugin.host.createKtorPluginHttpTransport
import dev.dimension.flare.feature.plugin.installer.PluginInstaller
import dev.dimension.flare.feature.plugin.lifecycle.PluginStateIssueV1
import dev.dimension.flare.feature.plugin.lifecycle.PluginStateStore
import dev.dimension.flare.feature.plugin.lifecycle.RunningPluginV1
import dev.dimension.flare.feature.plugin.lifecycle.derivedPluginState
import dev.dimension.flare.feature.plugin.login.PlatformOAuthPluginPendingStoreV1
import dev.dimension.flare.feature.plugin.login.PluginFormLoginCoordinatorV1
import dev.dimension.flare.feature.plugin.login.PluginOAuthLoginCoordinatorV1
import dev.dimension.flare.feature.plugin.login.PluginOAuthPendingStoreV1
import dev.dimension.flare.feature.plugin.login.PluginWebCookieLoginCoordinatorV1
import dev.dimension.flare.feature.plugin.manifest.PluginManifestV1
import dev.dimension.flare.feature.plugin.marketplace.PluginMarketplace
import dev.dimension.flare.feature.plugin.runtime.PluginRuntimePool
import dev.dimension.flare.feature.plugin.storage.PluginStorageV1
import dev.dimension.flare.model.PlatformRegistry
import dev.dimension.flare.model.PlatformSpecSource
import dev.dimension.flare.ui.model.PluginMediaPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.native.HiddenFromObjC

/** Process-scoped plugin services and bounded, plugin-owned persistent storage. */
@HiddenFromObjC
public class PluginSubsystemV1 internal constructor(
    internal val appFiles: AppFileStore,
    pendingStore: PluginOAuthPendingStoreV1,
    private val registryProvider: () -> PlatformRegistry = { koinGet() },
    private val accountsProvider: () -> AccountService = { koinGet() },
    private val scopeProvider: () -> CoroutineScope = { koinGet() },
    internal val timelineGrants: PluginTimelineGrantStoreV1 = PluginTimelineGrantStoreV1(),
) {
    internal val mutationMutex = Mutex()
    private val timelineProcessorCleaners = MutableStateFlow<Set<(String) -> Unit>>(emptySet())

    public constructor(
        appFiles: AppFileStore,
        pendingRepository: PlatformOAuthPendingRepository,
    ) : this(appFiles, PlatformOAuthPluginPendingStoreV1(pendingRepository))

    public val stateStore: PluginStateStore =
        PluginStateStore
            .open(
                fileSystem = appFiles.fileSystem,
                root = appFiles.directory(STORAGE_NAMESPACE),
            ).also { store ->
                PluginMediaPermissions.replace(store.running.plugins.mapValues { it.value.installed.manifest.permissions.mediaNetwork })
            }
    public val storage: PluginStorageV1 =
        PluginStorageV1(
            appFiles.fileSystem,
            appFiles.directory(STORAGE_NAMESPACE) / "storage",
        ).also { storage ->
            stateStore.desired.value.plugins.keys
                .forEach(storage::prepare)
        }
    public val runtimePool: PluginRuntimePool =
        PluginRuntimePool(
            fileSystem = appFiles.fileSystem,
            transportFactory = ::createKtorPluginHttpTransport,
            storage = storage,
        )
    public val installer: PluginInstaller = PluginInstaller(appFiles.fileSystem, stateStore)
    internal val extensionState: PluginStorageV1 =
        PluginStorageV1(
            appFiles.fileSystem,
            appFiles.directory(STORAGE_NAMESPACE) / "extension-host-state",
        )
    internal val extensions: PluginExtensionServiceV1 by lazy {
        PluginExtensionServiceV1(this, koinGet<AccountService>(), koinGet<CoroutineScope>())
    }
    public val github: GitHubPluginClient by lazy { GitHubPluginClient() }
    public val marketplace: PluginMarketplace by lazy { PluginMarketplace(appFiles) }

    private val mutableRunning = MutableStateFlow(stateStore.running)
    public val running: StateFlow<dev.dimension.flare.feature.plugin.lifecycle.PluginRunningSnapshotV1> = mutableRunning.asStateFlow()
    internal val mutableDevelopmentState = MutableStateFlow(PluginDevelopmentStateV1())
    public val developmentState: StateFlow<PluginDevelopmentStateV1> =
        derivedPluginState(mutableDevelopmentState, running) { state, running ->
            val active = running.plugins[state.pluginId]?.takeIf { it.developmentSource != null }
            state.copy(
                active = active != null,
                runningHash = active?.installed?.packageHash,
                generation = active?.generation ?: 0,
            )
        }
    public val development: PluginDevelopmentSessionV1 by lazy {
        PluginDevelopmentSessionV1(this, registryProvider(), accountsProvider(), scopeProvider())
    }

    internal val authoring: PluginAuthoringServiceV1 by lazy {
        PluginAuthoringServiceV1(this, registryProvider, accountsProvider, scopeProvider)
    }

    internal fun registerTimelineProcessorCleaner(cleaner: (String) -> Unit): () -> Unit {
        timelineProcessorCleaners.update { it + cleaner }
        return { timelineProcessorCleaners.update { it - cleaner } }
    }

    internal fun clearTimelineProcessor(pluginId: String) {
        val cleaners = timelineProcessorCleaners.value
        if (cleaners.isNotEmpty()) {
            cleaners.forEach { it(pluginId) }
        } else {
            runCatching { koinGet<TimelineProcessorService>() }.getOrNull()?.clearPlugin(pluginId)
        }
    }

    internal suspend fun applyConfirmedTimelineProcessorGrant(
        existingManifest: PluginManifestV1?,
        nextManifest: PluginManifestV1,
    ) {
        val pluginId = nextManifest.id
        if (nextManifest.timelineProcessor == null) {
            clearTimelineProcessor(pluginId)
            timelineGrants.updateSuspend { it - pluginId }
            return
        }
        val explicitlyDisabled =
            existingManifest?.timelineProcessor != null &&
                pluginId !in timelineGrants.current
        if (!explicitlyDisabled) {
            timelineGrants.updateSuspend { it + (pluginId to nextManifest.permissions.httpOrigins) }
        }
    }

    internal fun replaceRunning(
        pluginId: String,
        plugin: RunningPluginV1?,
    ) {
        if (plugin != null) storage.prepare(pluginId)
        val plugins = mutableRunning.value.plugins.toMutableMap()
        if (plugin == null) plugins.remove(pluginId) else plugins[pluginId] = plugin
        PluginMediaPermissions.register(
            pluginId,
            plugin
                ?.installed
                ?.manifest
                ?.permissions
                ?.mediaNetwork == true,
        )
        mutableRunning.value = mutableRunning.value.copy(plugins = plugins)
        mutableSourceIssues.update { issues -> issues.filterNot { it.pluginId == pluginId } }
        if (plugin == null || plugin.installed.manifest.timelineProcessor == null) {
            clearTimelineProcessor(pluginId)
            timelineGrants.setEnabledAsync(pluginId, null)
        } else if (!timelineGrants.isEnabled(pluginId, plugin.installed.manifest)) {
            clearTimelineProcessor(pluginId)
        }
    }

    private val mutableSourceIssues = MutableStateFlow(stateStore.running.issues)
    public val sourceIssues: StateFlow<List<PluginStateIssueV1>> =
        derivedPluginState(mutableSourceIssues, stateStore.desired) { issues, desired ->
            issues.filter {
                when (it.code) {
                    "index.corrupt" -> !desired.indexHealthy
                    "uninstall.pending" -> desired.plugins[it.pluginId]?.removing == true
                    else -> true
                }
            }
        }

    public val oauth: PluginOAuthLoginCoordinatorV1 =
        PluginOAuthLoginCoordinatorV1(
            runtimePool = runtimePool,
            pendingStore = pendingStore,
            runningPlugin = { pluginId -> running.value.plugins[pluginId] },
        )
    public val form: PluginFormLoginCoordinatorV1 = PluginFormLoginCoordinatorV1(runtimePool)
    public val webCookie: PluginWebCookieLoginCoordinatorV1 = PluginWebCookieLoginCoordinatorV1(runtimePool)

    public fun platformSource(scope: CoroutineScope): PlatformSpecSource =
        PluginPlatformSpecSourceV1(
            running = running.value,
            runtimePool = runtimePool,
            oauth = oauth,
            form = form,
            webCookie = webCookie,
            coroutineScope = scope,
            onIssue = { issue -> mutableSourceIssues.value = (mutableSourceIssues.value + issue).distinct() },
        )

    public suspend fun onMemoryPressure() {
        runtimePool.closeIdle()
    }

    private companion object {
        const val STORAGE_NAMESPACE = "social-plugins-v3"
    }
}

internal interface TimelineProcessorSettingsBacking {
    val enabledPlugins: Flow<Map<String, Set<String>>>

    suspend fun updateEnabledPlugins(transform: (Map<String, Set<String>>) -> Map<String, Set<String>>): Map<String, Set<String>>
}

internal class SettingsRepositoryTimelineBacking(
    private val settingsRepository: SettingsRepository,
) : TimelineProcessorSettingsBacking {
    override val enabledPlugins: Flow<Map<String, Set<String>>>
        get() = settingsRepository.appSettings.map { it.enabledTimelineProcessorPlugins }.distinctUntilChanged()

    override suspend fun updateEnabledPlugins(
        transform: (Map<String, Set<String>>) -> Map<String, Set<String>>,
    ): Map<String, Set<String>> {
        var updatedMap: Map<String, Set<String>> = emptyMap()
        settingsRepository.updateAppSettings {
            val next = transform(enabledTimelineProcessorPlugins)
            updatedMap = next
            if (next == enabledTimelineProcessorPlugins) {
                this
            } else {
                copy(enabledTimelineProcessorPlugins = next)
            }
        }
        return updatedMap
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class PluginTimelineGrantStoreV1(
    private val backing: () -> TimelineProcessorSettingsBacking? = {
        runCatching { koinGet<SettingsRepository>() }.getOrNull()?.let(::SettingsRepositoryTimelineBacking)
    },
    private val scope: () -> CoroutineScope? = {
        runCatching { koinGet<CoroutineScope>() }.getOrNull()
    },
) {
    private val mutableState = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val pendingWrites = MutableStateFlow(0)
    private val writeMutex = Mutex()
    private val boundBacking = MutableStateFlow<TimelineProcessorSettingsBacking?>(null)
    private val boundScope = MutableStateFlow<CoroutineScope?>(null)

    val current: Map<String, Set<String>>
        get() = mutableState.value

    fun bind(
        settingsBacking: TimelineProcessorSettingsBacking,
        coroutineScope: CoroutineScope,
    ) {
        boundBacking.value = settingsBacking
        boundScope.value = coroutineScope
    }

    fun isEnabled(
        pluginId: String,
        manifest: PluginManifestV1,
        grants: Map<String, Set<String>> = current,
    ): Boolean =
        manifest.timelineProcessor != null &&
            grants[pluginId]?.containsAll(manifest.permissions.httpOrigins) == true

    fun observedGrants(): Flow<Map<String, Set<String>>> =
        boundBacking
            .flatMapLatest { explicit ->
                val activeBacking = explicit ?: backing()
                if (activeBacking == null) {
                    mutableState
                } else {
                    combine(
                        mutableState,
                        pendingWrites,
                        activeBacking.enabledPlugins.onEach { persisted ->
                            if (pendingWrites.value == 0) {
                                mutableState.value = persisted
                            }
                        },
                    ) { memory, pending, persisted ->
                        if (pending > 0) memory else persisted
                    }.distinctUntilChanged()
                }
            }.distinctUntilChanged()

    fun setEnabledAsync(
        pluginId: String,
        httpOrigins: Set<String>?,
        fallbackScope: CoroutineScope? = null,
    ) {
        val transform: (Map<String, Set<String>>) -> Map<String, Set<String>> =
            if (httpOrigins == null) {
                { it - pluginId }
            } else {
                { it + (pluginId to httpOrigins) }
            }
        val activeBacking = boundBacking.value ?: backing()
        val activeScope = boundScope.value ?: scope() ?: fallbackScope
        if (activeBacking == null || activeScope == null) {
            mutableState.update(transform)
            return
        }
        pendingWrites.update { it + 1 }
        mutableState.update(transform)
        activeScope.launch {
            var persistedResult: Map<String, Set<String>>? = null
            try {
                writeMutex.withLock {
                    persistedResult = activeBacking.updateEnabledPlugins(transform)
                }
            } finally {
                val remaining = pendingWrites.updateAndGet { (it - 1).coerceAtLeast(0) }
                val snapshot = persistedResult
                if (remaining == 0 && snapshot != null) {
                    mutableState.value = snapshot
                }
            }
        }
    }

    suspend fun updateSuspend(transform: (Map<String, Set<String>>) -> Map<String, Set<String>>) {
        val activeBacking = boundBacking.value ?: backing()
        if (activeBacking == null) {
            mutableState.update(transform)
            return
        }
        pendingWrites.update { it + 1 }
        mutableState.update(transform)
        var persistedResult: Map<String, Set<String>>? = null
        try {
            writeMutex.withLock {
                persistedResult = activeBacking.updateEnabledPlugins(transform)
            }
        } finally {
            val remaining = pendingWrites.updateAndGet { (it - 1).coerceAtLeast(0) }
            val snapshot = persistedResult
            if (remaining == 0 && snapshot != null) {
                mutableState.value = snapshot
            }
        }
    }
}
