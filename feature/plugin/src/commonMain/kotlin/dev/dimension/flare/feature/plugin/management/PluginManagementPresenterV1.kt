package dev.dimension.flare.feature.plugin.management

import dev.dimension.flare.common.Locale
import dev.dimension.flare.data.model.PluginPermission
import dev.dimension.flare.data.repository.AccountService
import dev.dimension.flare.data.repository.AccountTabSyncCoordinator
import dev.dimension.flare.data.repository.AuthoredPlugin
import dev.dimension.flare.di.koinGet
import dev.dimension.flare.feature.plugin.PluginSubsystemV1
import dev.dimension.flare.feature.plugin.abi.PluginAbiV1
import dev.dimension.flare.feature.plugin.development.PluginDevelopmentStateV1
import dev.dimension.flare.feature.plugin.github.GitHubPluginCatalogV1
import dev.dimension.flare.feature.plugin.github.GitHubPluginClient
import dev.dimension.flare.feature.plugin.github.GitHubPluginVersionV1
import dev.dimension.flare.feature.plugin.github.GitHubRepositorySizeConfirmationRequired
import dev.dimension.flare.feature.plugin.github.gitHubPluginLocationOrNull
import dev.dimension.flare.feature.plugin.github.stableGitHubVersion
import dev.dimension.flare.feature.plugin.installer.PluginInstallWarningV1
import dev.dimension.flare.feature.plugin.installer.PreparedPluginInstallV1
import dev.dimension.flare.feature.plugin.installer.compareSemVer
import dev.dimension.flare.feature.plugin.lifecycle.InstalledPluginV1
import dev.dimension.flare.feature.plugin.lifecycle.PluginInstallSourceV1
import dev.dimension.flare.feature.plugin.lifecycle.PluginRuntimeSwitcherV1
import dev.dimension.flare.feature.plugin.lifecycle.PluginStateIssueV1
import dev.dimension.flare.feature.plugin.manifest.PluginCatalogBundleV1
import dev.dimension.flare.feature.plugin.manifest.TimelineManifestV1
import dev.dimension.flare.feature.plugin.manifest.fallbackText
import dev.dimension.flare.feature.plugin.manifest.toUiText
import dev.dimension.flare.feature.plugin.marketplace.PluginMarketplace
import dev.dimension.flare.feature.plugin.marketplace.PluginMarketplaceEntryV1
import dev.dimension.flare.feature.plugin.marketplace.PluginMarketplaceStateV1
import dev.dimension.flare.feature.plugin.runtime.PluginRuntimeIssueV1
import dev.dimension.flare.model.PlatformRegistry
import dev.dimension.flare.ui.model.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import okio.Source
import org.koin.mp.KoinPlatformTools
import kotlin.native.HiddenFromObjC

public data class PluginManagementStateV1(
    val plugins: List<PluginManagementItemV1> = emptyList(),
    val issues: List<PluginStateIssueV1> = emptyList(),
    val runtimeIssues: List<PluginRuntimeIssueV1> = emptyList(),
    val pendingInstall: PluginInstallReviewV1? = null,
    val canRebuildIndex: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val gitHubInstall: PluginGitHubInstallStateV1? = null,
    val development: PluginDevelopmentStateV1 = PluginDevelopmentStateV1(),
    val authoredPlugins: List<AuthoredPlugin> = emptyList(),
    val needsRepositoryDownloadConfirmation: Boolean = false,
    val marketplace: PluginMarketplaceStateV1 = PluginMarketplaceStateV1(),
) {
    public val canInstallFromMarketplace: Boolean
        get() = !busy && gitHubInstall == null && pendingInstall == null && !needsRepositoryDownloadConfirmation

    public fun isInstalled(pluginId: String): Boolean = plugins.any { it.pluginId == pluginId }

    public fun isManaged(pluginId: String): Boolean = items.any { it.pluginId == pluginId } || development.pluginId == pluginId

    public val items: List<PluginManagementItemV1> =
        buildList {
            val installed = plugins.associateByTo(linkedMapOf(), PluginManagementItemV1::pluginId)
            authoredPlugins.forEach { authoring ->
                add(
                    installed.remove(authoring.id)?.copy(authoring = authoring)
                        ?: PluginManagementItemV1(
                            pluginId = authoring.id,
                            platformId = authoring.platformId,
                            nameText = UiText.Raw(authoring.name),
                            version = "",
                            iconPath = "",
                            sourceUrl = null,
                            availableUpdate = null,
                            authoring = authoring,
                        ),
                )
            }
            addAll(installed.values)
        }
}

public data class PluginGitHubInstallStateV1(
    val busy: Boolean = false,
    val error: String? = null,
)

public data class PluginManagementItemV1(
    val pluginId: String,
    val platformId: String,
    val nameText: UiText,
    val version: String,
    val iconPath: String,
    val sourceUrl: String?,
    val availableUpdate: GitHubPluginVersionV1?,
    val authoring: AuthoredPlugin? = null,
    val hasSettings: Boolean = false,
    val hasPlatformExtension: Boolean = false,
    val hasTimelineProcessor: Boolean = false,
    val timelineProcessorEnabled: Boolean = false,
    val updatedAt: String? = null,
    val sourceCommitSha: String? = null,
) {
    public val repositoryFullName: String? get() = sourceUrl?.gitHubPluginLocationOrNull()?.fullName
    public val repositoryWebUrl: String? get() = sourceUrl?.gitHubPluginLocationOrNull()?.webUrl(sourceCommitSha)
}

public data class PluginInstallReviewV1(
    val pluginId: String,
    val platformId: String,
    val name: String,
    val version: String,
    val capabilities: List<String>,
    val permissions: List<PluginPermission>,
    val warnings: List<PluginInstallWarningV1>,
    val sourceUrl: String?,
    val defaultGuestTabTitles: List<String> = emptyList(),
) {
    public val canAutoAddDefaultTabs: Boolean
        get() = defaultGuestTabTitles.isNotEmpty()
}

/** The scope belongs to the application so installed platforms outlive the management screen. */
@HiddenFromObjC
public class PluginManagementPresenterV1(
    private val subsystem: PluginSubsystemV1,
    private val scope: CoroutineScope,
    private val operationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val github: GitHubPluginClient = subsystem.github,
    registry: () -> PlatformRegistry = { koinGet() },
    accounts: () -> AccountService = { koinGet() },
    private val marketplace: PluginMarketplace = subsystem.marketplace,
    private val addGuestPluginDefaultTabs: suspend (String) -> Unit = { platformId ->
        KoinPlatformTools
            .defaultContext()
            .getOrNull()
            ?.getOrNull<AccountTabSyncCoordinator>()
            ?.addGuestPluginDefaultTabs(platformId)
    },
) {
    private val switcher by lazy { PluginRuntimeSwitcherV1(subsystem, registry(), accounts(), scope) }
    private val updates = MutableStateFlow(emptyMap<String, GitHubPluginVersionV1>())
    private val updateIssues = MutableStateFlow(emptyMap<String, PluginStateIssueV1>())
    private var preview: InstallPreview? = null
    private var pendingDownload: PendingGitHubDownload? = null
    private val mutableState = MutableStateFlow(snapshot())
    public val state: StateFlow<PluginManagementStateV1> = mutableState.asStateFlow()

    private val actionMutex = Mutex()
    private val updateMutex = Mutex()
    private val closed = MutableStateFlow(false)
    private var marketplaceJob: Job? = null

    private val observation: Job =
        combine(
            subsystem.stateStore.desired,
            subsystem.running,
            subsystem.sourceIssues,
            subsystem.runtimePool.issues,
            subsystem.developmentState,
        ) { _, _, _, _, _ -> Unit }
            .combine(subsystem.authoring.plugins) { _, _ -> Unit }
            .combine(subsystem.timelineGrants.observedGrants()) { _, _ -> Unit }
            .onEach { refreshState() }
            .launchIn(scope)

    public fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        marketplaceJob?.cancel()
        observation.cancel()
        scope.launch {
            observation.join()
            actionMutex.withLock {
                if (preview != null || pendingDownload != null) {
                    try {
                        withContext(operationDispatcher) { clearPreview() }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        error.printStackTrace()
                    }
                }
            }
        }
    }

    public fun openGitHubInstall() {
        if (closed.value) return
        mutableState.update { it.copy(gitHubInstall = it.gitHubInstall ?: PluginGitHubInstallStateV1()) }
    }

    public fun loadMarketplace(forceRefresh: Boolean = false) {
        if (closed.value || marketplaceJob?.isActive == true) return
        mutableState.update { it.copy(marketplace = it.marketplace.copy(loading = true, error = null)) }
        marketplaceJob =
            scope.launch(operationDispatcher) {
                try {
                    marketplace.cachedCatalog()?.let { cached ->
                        if (!closed.value) {
                            mutableState.update { it.copy(marketplace = it.marketplace.copy(plugins = cached.plugins, loaded = true)) }
                        }
                    }
                    val catalog = marketplace.load(forceRefresh)
                    if (!closed.value) {
                        mutableState.update {
                            it.copy(marketplace = PluginMarketplaceStateV1(plugins = catalog.plugins, loaded = true))
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (!closed.value) {
                        mutableState.update {
                            it.copy(
                                marketplace =
                                    it.marketplace.copy(
                                        loading = false,
                                        error =
                                            error.message ?: "Plugin marketplace is unavailable",
                                    ),
                            )
                        }
                    }
                } finally {
                    if (!closed.value) {
                        mutableState.update { it.copy(marketplace = it.marketplace.copy(loading = false)) }
                    }
                }
            }
    }

    public suspend fun marketplaceIcon(pluginId: String): String? =
        withContext(operationDispatcher) {
            if (closed.value) return@withContext null
            val entry =
                mutableState.value.marketplace.plugins
                    .firstOrNull { it.id == pluginId } ?: return@withContext null
            marketplace.icon(entry)
        }

    public suspend fun refreshMarketplace() {
        loadMarketplace(forceRefresh = true)
        marketplaceJob?.join()
    }

    public suspend fun inspectMarketplace(pluginId: String) {
        runAction {
            check(
                preview == null && pendingDownload == null && mutableState.value.gitHubInstall == null,
            ) { "Finish the pending installation first" }
            requireInstallTargetAvailable(pluginId, fromMarketplace = true)
            val entry =
                requireNotNull(
                    mutableState.value.marketplace.plugins
                        .firstOrNull { it.id == pluginId },
                ) { "Plugin is no longer in the marketplace" }
            clearPreview()
            val catalog = github.catalog(entry.repository, forceRefresh = true)
            val version =
                if (entry.commitSha == null) {
                    catalog.versions.first()
                } else {
                    checkNotNull(catalog.versions.firstOrNull { it.commitSha == entry.commitSha }) {
                        "The listed plugin version is no longer available. Refresh the marketplace and try again."
                    }
                }
            prepareGitHub(catalog, version, marketplaceEntry = entry)
        }
    }

    public suspend fun inspectGitHub(url: String) {
        openGitHubInstall()
        runAction(gitHubInstall = true) {
            clearPreview()
            val catalog = github.catalog(url, forceRefresh = true)
            prepareGitHub(catalog, catalog.versions.first())
        }
    }

    public suspend fun checkUpdates(forceRefresh: Boolean = true) {
        updateMutex.withLock {
            withContext(operationDispatcher) {
                val installed =
                    subsystem.stateStore.desired.value.plugins.values
                        .filter { !it.removing && it.source is PluginInstallSourceV1.GitHub }
                val refreshedRepositories = mutableSetOf<Long>()
                val missingDates = mutableListOf<Pair<InstalledPluginV1, Boolean>>()
                installed.forEach { plugin ->
                    val source = plugin.source as PluginInstallSourceV1.GitHub
                    var failure: PluginStateIssueV1? = null
                    val update =
                        try {
                            val catalog =
                                github.catalog(
                                    source.url,
                                    forceRefresh = forceRefresh && refreshedRepositories.add(source.repository.id),
                                    expectedRepositoryId = source.repository.id,
                                )
                            updateCandidate(plugin, catalog)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            failure = PluginStateIssueV1("update.failed", plugin.pluginId, error.message ?: "Plugin update check failed")
                            null
                        }
                    actionMutex.withLock {
                        val current = subsystem.stateStore.desired.value.plugins[plugin.pluginId]
                        if (current != null && !current.removing && current.source == source && current.packageHash == plugin.packageHash) {
                            updates.update { if (update == null) it - plugin.pluginId else it + (plugin.pluginId to update) }
                            if (failure == null) {
                                updateIssues.update { it - plugin.pluginId }
                            } else {
                                updateIssues.update { it + (plugin.pluginId to requireNotNull(failure)) }
                            }
                            refreshState()
                        }
                    }
                    if (plugin.updatedAt == null) missingDates += plugin to (failure == null)
                }
                // Check every install for updates before spending any GitHub requests on optional dates.
                missingDates.forEach { (plugin, allowNetwork) ->
                    val source = plugin.source as PluginInstallSourceV1.GitHub
                    try {
                        packageUpdateDate(plugin.pluginId, plugin.version, source, allowNetwork)?.let { date ->
                            subsystem.stateStore.recordUpdateDate(plugin, date)
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                    }
                }
                refreshState()
            }
        }
    }

    public suspend fun inspectGitHubUpdate(pluginId: String) {
        runAction {
            clearPreview()
            val installed = requireNotNull(subsystem.stateStore.desired.value.plugins[pluginId]) { "Plugin is no longer installed" }
            val source = installed.source as? PluginInstallSourceV1.GitHub ?: error("Plugin is not installed from GitHub")
            val catalog = github.catalog(source.url, forceRefresh = true, expectedRepositoryId = source.repository.id)
            val version = requireNotNull(updateCandidate(installed, catalog)) { "No GitHub update is available" }
            prepareGitHub(catalog, version, expectedPluginId = pluginId)
        }
    }

    private suspend fun prepareGitHub(
        catalog: GitHubPluginCatalogV1,
        version: GitHubPluginVersionV1,
        expectedPluginId: String? = null,
        allowLargeRepository: Boolean = false,
        marketplaceEntry: PluginMarketplaceEntryV1? = null,
    ) = subsystem.installer.withRepositoryDownload { archive ->
        val fileSystem = subsystem.appFiles.fileSystem
        try {
            github.archive(catalog.repository, version, fileSystem, archive, allowLargeRepository)
        } catch (_: GitHubRepositorySizeConfirmationRequired) {
            pendingDownload = PendingGitHubDownload(catalog, version, expectedPluginId, marketplaceEntry)
            mutableState.update { it.copy(needsRepositoryDownloadConfirmation = true) }
            return@withRepositoryDownload
        }
        val source = PluginInstallSourceV1.GitHub(catalog.repository, version.tag, version.commitSha, catalog.path)
        val prepared = subsystem.installer.inspect(archive, source, expectedPluginId)
        try {
            if (marketplaceEntry != null) {
                check(prepared.pluginId == marketplaceEntry.id) { "Downloaded plugin ID does not match the marketplace entry" }
                check(marketplaceEntry.version == null || prepared.version == marketplaceEntry.version) {
                    "Downloaded plugin version does not match the marketplace entry"
                }
                requireInstallTargetAvailable(marketplaceEntry.id, fromMarketplace = true)
            }
            if (expectedPluginId != null &&
                prepared.packageHash ==
                subsystem.stateStore.desired.value.plugins[expectedPluginId]
                    ?.packageHash
            ) {
                updates.update { it - expectedPluginId }
            } else {
                val updatedAt =
                    marketplaceEntry?.takeIf { it.commitSha == source.commitSha }?.updatedAt
                        ?: packageUpdateDate(prepared.pluginId, prepared.version, source)
                preview = InstallPreview(prepared, fromMarketplace = marketplaceEntry != null, updatedAt = updatedAt)
            }
        } finally {
            if (preview?.prepared !== prepared) {
                withContext(NonCancellable) { subsystem.installer.discard(prepared) }
            }
        }
    }

    private suspend fun packageUpdateDate(
        pluginId: String,
        pluginVersion: String,
        source: PluginInstallSourceV1.GitHub,
        allowNetwork: Boolean = true,
    ): String? {
        val entry =
            marketplace.cachedCatalog()?.plugins?.find {
                it.id == pluginId && it.version == pluginVersion &&
                    it.commitSha == source.commitSha
            }
        val location = entry?.repository?.gitHubPluginLocationOrNull()
        if (entry?.updatedAt != null && location?.path == source.path &&
            location.owner.equals(source.repository.owner, ignoreCase = true) &&
            location.name.equals(source.repository.name, ignoreCase = true)
        ) {
            return entry.updatedAt
        }
        return if (allowNetwork) github.packageUpdateDate(source.repository, source.commitSha, source.path) else null
    }

    public suspend fun confirmRepositoryDownload() {
        runAction(gitHubInstall = mutableState.value.gitHubInstall != null) {
            val pending = requireNotNull(pendingDownload) { "No repository download is pending" }
            pendingDownload = null
            mutableState.update { it.copy(needsRepositoryDownloadConfirmation = false) }
            pending.marketplaceEntry?.let { requireInstallTargetAvailable(it.id, fromMarketplace = true) }
            prepareGitHub(
                pending.catalog,
                pending.version,
                pending.expectedPluginId,
                allowLargeRepository = true,
                marketplaceEntry = pending.marketplaceEntry,
            )
        }
    }

    public suspend fun cancelRepositoryDownload() {
        runAction(gitHubInstall = mutableState.value.gitHubInstall != null) {
            pendingDownload = null
            mutableState.update { it.copy(needsRepositoryDownloadConfirmation = false) }
        }
    }

    private suspend fun updateCandidate(
        installed: InstalledPluginV1,
        catalog: GitHubPluginCatalogV1,
    ): GitHubPluginVersionV1? {
        val source = installed.source as PluginInstallSourceV1.GitHub
        val latest = catalog.versions.first()
        val candidate =
            if (compareSemVer(latest.version, requireNotNull(stableGitHubVersion(source.tag))) > 0) {
                latest
            } else {
                requireNotNull(catalog.versions.firstOrNull { it.tag == source.tag }) { "The installed GitHub tag is no longer available" }
            }
        if (candidate.commitSha == source.commitSha) return null
        val fingerprint = github.packageFingerprint(catalog.repository, candidate, source.path)
        return candidate.takeIf { fingerprint != subsystem.installer.packageFingerprint(installed) }
    }

    @HiddenFromObjC
    public suspend fun inspect(source: Source): PluginInstallReviewV1 =
        runAction {
            clearPreview()
            subsystem.installer
                .inspect(source)
                .also { prepared ->
                    preview = InstallPreview(prepared)
                }.let { prepared ->
                    prepared.toReview(isFreshInstall = prepared.pluginId !in subsystem.stateStore.desired.value.plugins)
                }
        }

    public suspend fun inspect(path: String): PluginInstallReviewV1 =
        runAction {
            clearPreview()
            subsystem.installer
                .inspect(path.toPath())
                .also { prepared ->
                    preview = InstallPreview(prepared)
                }.let { prepared ->
                    prepared.toReview(isFreshInstall = prepared.pluginId !in subsystem.stateStore.desired.value.plugins)
                }
        }

    public suspend fun confirmInstall(addDefaultTabs: Boolean = true) {
        runMutation(gitHubInstall = mutableState.value.gitHubInstall != null) {
            val (prepared, fromMarketplace, updatedAt) = requireNotNull(preview) { "No plugin installation is pending" }
            try {
                requireInstallTargetAvailable(prepared.pluginId, fromMarketplace)
            } catch (error: IllegalStateException) {
                if (fromMarketplace) clearPreview()
                throw error
            }
            val existingPlugin = subsystem.stateStore.desired.value.plugins[prepared.pluginId]
            val isFreshInstall = existingPlugin == null
            val hasGuestDefaults = prepared.defaultGuestTabTitles(isFreshInstall).isNotEmpty()
            val existingManifest = existingPlugin?.manifest
            val previousGrant = subsystem.timelineGrants.awaitCurrent()[prepared.pluginId]
            subsystem.installer.commit(
                prepared,
                confirmed = true,
                updatedAt = updatedAt,
                activate = { record ->
                    subsystem.applyConfirmedTimelineProcessorGrant(
                        existingManifest = existingManifest,
                        nextManifest = record.manifest,
                    )
                    try {
                        switcher.install(record)
                    } catch (error: Throwable) {
                        subsystem.timelineGrants.updateSuspend { current ->
                            if (previousGrant == null) {
                                current - record.pluginId
                            } else {
                                current + (record.pluginId to previousGrant)
                            }
                        }
                        throw error
                    }
                },
            )
            updates.update { it - prepared.pluginId }
            updateIssues.update { it - prepared.pluginId }
            preview = null
            mutableState.update { snapshot(it).copy(gitHubInstall = null) }
            if (isFreshInstall && addDefaultTabs && hasGuestDefaults && prepared.platformId.isNotEmpty()) {
                try {
                    addGuestPluginDefaultTabs(prepared.platformId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    error.printStackTrace()
                }
            }
        }
    }

    public fun setTimelineProcessorEnabled(
        pluginId: String,
        enabled: Boolean,
    ) {
        if (closed.value) return
        if (enabled) {
            val manifest =
                (
                    subsystem.running.value.plugins[pluginId]
                        ?.installed
                        ?: subsystem.stateStore.desired.value.plugins[pluginId]
                            ?.takeUnless { it.removing }
                )?.manifest ?: return
            if (manifest.timelineProcessor == null) return
            subsystem.timelineGrants.setEnabledAsync(pluginId, manifest.permissions, scope)
        } else {
            subsystem.timelineGrants.setEnabledAsync(pluginId, null, scope)
            subsystem.clearTimelineProcessor(pluginId)
        }
        refreshState()
    }

    public suspend fun cancelInstall() {
        runAction(gitHubInstall = mutableState.value.gitHubInstall != null) {
            clearPreview()
            mutableState.update { snapshot(it).copy(gitHubInstall = null) }
        }
    }

    public suspend fun cancelPluginUpdate(pluginId: String) {
        runAuthoringAction { subsystem.authoring.cancelUpdate(pluginId) }
    }

    public suspend fun uninstall(pluginId: String) {
        if (subsystem.authoring.plugins.value
                .any { it.id == pluginId }
        ) {
            runAuthoringAction { subsystem.authoring.delete(pluginId) }
            return
        }
        runMutation {
            requireNotDeveloping(pluginId)
            val installed = requireNotNull(subsystem.stateStore.desired.value.plugins[pluginId]) { "Plugin is no longer installed" }
            switcher.uninstall(pluginId, installed.ownedPlatformId)
            updates.update { it - pluginId }
            updateIssues.update { it - pluginId }
            subsystem.installer.cleanup(subsystem.running.value.plugins.values)
        }
    }

    public suspend fun retryRuntime(pluginId: String) {
        runMutation {
            subsystem.runtimePool.retry(pluginId)
        }
    }

    public suspend fun cleanup() {
        runMutation { subsystem.installer.cleanup(subsystem.running.value.plugins.values) }
    }

    public suspend fun rebuildIndex() {
        runMutation {
            subsystem.installer.rebuildIndex()
            subsystem.stateStore.desired.value.plugins.values
                .forEach { switcher.install(it) }
        }
    }

    public fun clearGitHubInstallError() {
        mutableState.update { it.copy(gitHubInstall = it.gitHubInstall?.copy(error = null)) }
    }

    public fun reportOperationFailure() {
        mutableState.value = mutableState.value.copy(error = "Plugin operation failed")
    }

    private fun refreshState() {
        mutableState.update { snapshot(it) }
    }

    private fun snapshot(current: PluginManagementStateV1 = PluginManagementStateV1()): PluginManagementStateV1 {
        val desired = subsystem.stateStore.desired.value
        val running = subsystem.running.value.plugins
        val grants = subsystem.timelineGrants.current
        return current.copy(
            plugins =
                desired.plugins.values.sortedBy(InstalledPluginV1::pluginId).map { installed ->
                    val effectiveManifest = running[installed.pluginId]?.installed?.manifest ?: installed.manifest
                    installed.toItem(
                        iconPath =
                            subsystem.stateStore.paths
                                .iconPath(installed.packageHash, installed.iconExtension)
                                .toString(),
                        availableUpdate = updates.value[installed.pluginId].takeUnless { installed.removing },
                        hasTimelineProcessor = !installed.removing && effectiveManifest.timelineProcessor != null,
                        timelineProcessorEnabled =
                            !installed.removing &&
                                subsystem.timelineGrants.isEnabled(installed.pluginId, effectiveManifest, grants),
                    )
                },
            issues = subsystem.sourceIssues.value + updateIssues.value.values.filter { it.pluginId in desired.plugins },
            runtimeIssues =
                subsystem.runtimePool.issues.value.values
                    .sortedBy(PluginRuntimeIssueV1::pluginId),
            development = subsystem.developmentState.value,
            authoredPlugins = subsystem.authoring.plugins.value,
            canRebuildIndex = !desired.indexHealthy,
            pendingInstall = preview?.prepared?.let { it.toReview(isFreshInstall = it.pluginId !in desired.plugins) },
        )
    }

    private fun requireInstallTargetAvailable(
        pluginId: String,
        fromMarketplace: Boolean,
    ) {
        if (fromMarketplace) {
            check(subsystem.stateStore.desired.value.plugins[pluginId] == null) { "Plugin is already installed" }
        }
        requireNotDeveloping(pluginId)
        check(
            subsystem.authoring.plugins.value
                .none { it.id == pluginId },
        ) {
            if (fromMarketplace) {
                "Manage the editable local plugin with this ID first"
            } else {
                "Delete the editable local plugin before installing another package with this ID"
            }
        }
    }

    private fun requireNotDeveloping(pluginId: String) {
        check(
            subsystem.running.value.plugins[pluginId]
                ?.developmentSource == null,
        ) { "Stop development before changing the installed plugin" }
    }

    private suspend fun clearPreview() {
        val previous = preview
        preview = null
        pendingDownload = null
        mutableState.update { it.copy(needsRepositoryDownloadConfirmation = false) }
        refreshState()
        if (previous != null) {
            withContext(NonCancellable) { subsystem.installer.discard(previous.prepared) }
        }
    }

    private suspend fun runAuthoringAction(block: suspend () -> Unit) {
        try {
            block()
            mutableState.update { snapshot(it).copy(error = null) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.update { snapshot(it).copy(error = error.message ?: "Plugin operation failed") }
            throw error
        }
    }

    private suspend fun <T> runMutation(
        gitHubInstall: Boolean = false,
        block: suspend () -> T,
    ): T = runAction(gitHubInstall) { subsystem.mutationMutex.withLock { block() } }

    private suspend fun <T> runAction(
        gitHubInstall: Boolean = false,
        block: suspend () -> T,
    ): T =
        actionMutex.withLock {
            check(!closed.value) { "Plugin management is closed" }
            var failure: String? = null

            fun publish(busy: Boolean) {
                mutableState.update { current ->
                    val refreshed = snapshot(current)
                    if (gitHubInstall) {
                        refreshed.copy(
                            gitHubInstall = refreshed.gitHubInstall?.copy(busy = busy, error = failure),
                        )
                    } else {
                        refreshed.copy(busy = busy, error = failure)
                    }
                }
            }
            publish(busy = true)
            try {
                withContext(operationDispatcher) { block() }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                failure = error.message ?: "Plugin operation failed"
                throw error
            } finally {
                publish(busy = false)
            }
        }

    private data class InstallPreview(
        val prepared: PreparedPluginInstallV1,
        val fromMarketplace: Boolean = false,
        val updatedAt: String? = null,
    )

    private data class PendingGitHubDownload(
        val catalog: GitHubPluginCatalogV1,
        val version: GitHubPluginVersionV1,
        val expectedPluginId: String?,
        val marketplaceEntry: PluginMarketplaceEntryV1?,
    )
}

private fun InstalledPluginV1.toItem(
    iconPath: String,
    availableUpdate: GitHubPluginVersionV1?,
    hasTimelineProcessor: Boolean = false,
    timelineProcessorEnabled: Boolean = false,
): PluginManagementItemV1 =
    PluginManagementItemV1(
        pluginId = pluginId,
        platformId = manifest.platform?.id.orEmpty(),
        nameText = manifest.name.toUiText(pluginId),
        version = version,
        iconPath = iconPath,
        sourceUrl = (source as? PluginInstallSourceV1.GitHub)?.url,
        availableUpdate = availableUpdate,
        updatedAt = updatedAt,
        sourceCommitSha = (source as? PluginInstallSourceV1.GitHub)?.commitSha,
        hasSettings = manifest.settings != null,
        hasPlatformExtension = manifest.platformExtension != null,
        hasTimelineProcessor = hasTimelineProcessor,
        timelineProcessorEnabled = timelineProcessorEnabled,
    )

private fun PreparedPluginInstallV1.toReview(isFreshInstall: Boolean): PluginInstallReviewV1 =
    PluginInstallReviewV1(
        pluginId = pluginId,
        platformId = platformId,
        name = nameFallback,
        version = version,
        capabilities =
            capabilities.entries
                .sortedBy { it.key }
                .map { (capability, operations) -> "$capability: ${operations.sorted().joinToString()}" },
        permissions = permissions.sortedWith(compareBy({ it.type.name }, { it.origin }, { it.cookieName.orEmpty() })),
        warnings = warnings,
        sourceUrl = (source as? PluginInstallSourceV1.GitHub)?.url,
        defaultGuestTabTitles = defaultGuestTabTitles(isFreshInstall),
    )

private fun PreparedPluginInstallV1.defaultGuestTabTitles(isFreshInstall: Boolean): List<String> {
    if (!isFreshInstall) return emptyList()
    val manifest = validated.manifest
    val platform = manifest.platform ?: return emptyList()
    if (manifest.apiVersion < 2) return emptyList()
    val guest = platform.guest ?: return emptyList()
    if (!guest.enabled || guest.origin == null) return emptyList()
    if ("page" !in platform.capabilities[PluginAbiV1.Capabilities.TIMELINE]?.operations.orEmpty()) return emptyList()
    val bundle = PluginCatalogBundleV1(pluginId, manifest.defaultLocale, validated.catalogs)
    return platform.timelines
        .filter(TimelineManifestV1::defaultForNewAccount)
        .map { timeline ->
            when (val text = timeline.title.toUiText(pluginId)) {
                is UiText.Raw -> text.string
                is UiText.ExternalRef -> bundle.resolve(text, Locale.language)
                is UiText.Localized -> timeline.title.fallbackText
            }
        }
}
