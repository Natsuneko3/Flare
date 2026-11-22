package dev.dimension.flare.feature.plugin.adapter

import dev.dimension.flare.common.Locale
import dev.dimension.flare.common.PlatformDispatchers
import dev.dimension.flare.data.database.cache.dao.PluginPostWidgetDao
import dev.dimension.flare.data.database.cache.model.DbPluginPostWidget
import dev.dimension.flare.data.plugin.TimelineProcessorService
import dev.dimension.flare.feature.plugin.PluginSubsystemV1
import dev.dimension.flare.feature.plugin.PluginTimelineGrantStoreV1
import dev.dimension.flare.feature.plugin.abi.PluginJsonV1
import dev.dimension.flare.feature.plugin.host.PluginCallTimeoutV1
import dev.dimension.flare.feature.plugin.host.PluginInvocationContextV1
import dev.dimension.flare.feature.plugin.lifecycle.RunningPluginV1
import dev.dimension.flare.feature.plugin.manifest.PluginCatalogBundleV1
import dev.dimension.flare.feature.plugin.manifest.PluginTimelineFilterManifestV1
import dev.dimension.flare.feature.plugin.manifest.compileTimelineFilterRegex
import dev.dimension.flare.feature.plugin.manifest.fallbackText
import dev.dimension.flare.feature.plugin.manifest.isValidLocaleTag
import dev.dimension.flare.feature.plugin.manifest.toUiText
import dev.dimension.flare.feature.plugin.runtime.MAX_INVOCATION_REQUEST_BYTES
import dev.dimension.flare.feature.plugin.runtime.PluginRuntimeKeyV1
import dev.dimension.flare.feature.plugin.wire.CachedWidgetCardPayloadV1
import dev.dimension.flare.feature.plugin.wire.CachedWidgetNodeV1
import dev.dimension.flare.feature.plugin.wire.TimelineProcessTriggerV1
import dev.dimension.flare.feature.plugin.wire.ValidatedPluginItemCardsV1
import dev.dimension.flare.feature.plugin.wire.WidgetCachePolicyV1
import dev.dimension.flare.feature.plugin.wire.WireTimelineProcessorLimitsV1
import dev.dimension.flare.feature.plugin.wire.encodeTimelineProcessBatchRequest
import dev.dimension.flare.feature.plugin.wire.parseAndValidateTimelineProcessBatchResponse
import dev.dimension.flare.feature.plugin.wire.splitTimelineProcessBatchRequest
import dev.dimension.flare.feature.plugin.wire.toUiCard
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.ui.model.ExtractedPluginPostInput
import dev.dimension.flare.ui.model.UiPluginWidgetCard
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.UiWidgetAction
import dev.dimension.flare.ui.model.extractPluginPostInput
import dev.dimension.flare.ui.model.pluginPostEntityKey
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

internal typealias TimelineBatchRuntimeInvoker =
    suspend (
        plugin: RunningPluginV1,
        key: PluginRuntimeKeyV1,
        context: PluginInvocationContextV1,
        request: JsonObject,
        validate: (JsonElement) -> Unit,
    ) -> JsonElement

internal fun PluginTimelineFilterManifestV1.matches(input: ExtractedPluginPostInput): Boolean =
    CompiledTimelineFilter.compile(this).matches(input)

internal class CompiledTimelineFilter private constructor(
    private val containsAny: List<String>,
    private val regexPattern: String?,
    private val compiledRegex: ((String) -> Boolean)?,
    private val linkHosts: List<String>,
    private val requireLinks: Boolean,
) {
    fun matches(input: ExtractedPluginPostInput): Boolean {
        val candidateHttpsHosts by lazy(LazyThreadSafetyMode.NONE) {
            extractCandidateHttpsHosts(input)
        }
        if (requireLinks && candidateHttpsHosts.isEmpty()) {
            return false
        }
        if (containsAny.isNotEmpty()) {
            val matchedNeedle =
                containsAny.any { needle ->
                    input.contentPlain.contains(needle, ignoreCase = true) ||
                        input.contentWarningPlain?.contains(needle, ignoreCase = true) == true ||
                        input.cardTitle?.contains(needle, ignoreCase = true) == true ||
                        input.cardUrl?.contains(needle, ignoreCase = true) == true
                }
            if (!matchedNeedle) {
                return false
            }
        }
        if (regexPattern != null) {
            val regex = compiledRegex ?: return false
            val cw = input.contentWarningPlain
            val matchedRegex =
                regex(input.contentPlain) || (cw != null && regex(cw))
            if (!matchedRegex) {
                return false
            }
        }
        if (linkHosts.isNotEmpty()) {
            val matchedHost =
                candidateHttpsHosts.any { host ->
                    linkHosts.any { allowed ->
                        host == allowed || host.endsWith(".$allowed")
                    }
                }
            if (!matchedHost) {
                return false
            }
        }
        return true
    }

    companion object {
        fun compile(manifest: PluginTimelineFilterManifestV1): CompiledTimelineFilter {
            val normalizedHosts =
                manifest.linkHosts
                    .map { it.trim().trimEnd('.').lowercase() }
                    .filter { it.isNotEmpty() }
            val compiled = manifest.regex?.let(::compileTimelineFilterRegex)
            return CompiledTimelineFilter(
                containsAny = manifest.containsAny,
                regexPattern = manifest.regex,
                compiledRegex = compiled,
                linkHosts = normalizedHosts,
                requireLinks = manifest.requireLinks,
            )
        }

        private fun extractCandidateHttpsHosts(input: ExtractedPluginPostInput): List<String> {
            val hosts = ArrayList<String>(input.links.size + 1)
            input.links.forEach { raw ->
                parseSafeHttpsHost(raw)?.let(hosts::add)
            }
            val cardUrl = input.cardUrl
            if (cardUrl != null && cardUrl !in input.links) {
                parseSafeHttpsHost(cardUrl)?.let(hosts::add)
            }
            return hosts
        }

        private fun parseSafeHttpsHost(rawUrl: String): String? {
            val trimmed = rawUrl.trim()
            if (!trimmed.startsWith("https://", ignoreCase = true)) {
                return null
            }
            val authority =
                trimmed
                    .substring(8)
                    .substringBefore('/')
                    .substringBefore('?')
                    .substringBefore('#')
            if (authority.isEmpty() || '@' in authority) {
                return null
            }
            return runCatching {
                val parsed = Url(trimmed)
                if (
                    parsed.protocol == URLProtocol.HTTPS &&
                    parsed.host.isNotBlank() &&
                    parsed.encodedUser == null &&
                    parsed.encodedPassword == null &&
                    parsed.user == null &&
                    parsed.password == null
                ) {
                    parsed.host.trimEnd('.').lowercase()
                } else {
                    null
                }
            }.getOrNull()
        }
    }
}

internal class PluginTimelineProcessorServiceV1(
    private val subsystem: PluginSubsystemV1,
    private val dao: PluginPostWidgetDao,
    private val grants: PluginTimelineGrantStoreV1 = subsystem.timelineGrants,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = PlatformDispatchers.IO,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val localeProvider: () -> String = { Locale.language },
    private val coalesceDelayMillis: Long = DEFAULT_COALESCE_DELAY_MILLIS,
    private val errorCooldownMillis: Long = DEFAULT_ERROR_COOLDOWN_MILLIS,
    private val runtimeInvoker: TimelineBatchRuntimeInvoker = { plugin, key, context, request, validate ->
        subsystem.runtimePool.invokeJson(
            plugin = plugin,
            key = key,
            context = context,
            method = METHOD_PROCESS_BATCH,
            request = request,
            timeout = PluginCallTimeoutV1.Extended,
            validate = validate,
        )
    },
) : TimelineProcessorService {
    private val silentExceptionHandler = CoroutineExceptionHandler { _, _ -> }
    private val serviceJob = SupervisorJob(scope.coroutineContext[Job])
    private val serviceScope = CoroutineScope(workDispatcher + serviceJob + silentExceptionHandler)
    private val workSupervisor = SupervisorJob(serviceJob)
    private val taskScope = CoroutineScope(workDispatcher + workSupervisor + silentExceptionHandler)

    private val mutableRevision = MutableStateFlow(0L)
    override val overlayRevision: StateFlow<Long> = mutableRevision.asStateFlow()
    private val mutableProcessingRevision = MutableStateFlow(0L)
    override val processingRevision: StateFlow<Long> = mutableProcessingRevision.asStateFlow()

    private val memoryState = MutableStateFlow(MemorySnapshot())
    private val compiledFilters = MutableStateFlow<Map<FilterCacheKey, CompiledTimelineFilter?>>(emptyMap())
    private val clearState = MutableStateFlow(ClearEpochsSnapshot())
    private val pendingSubmissions = MutableStateFlow(PendingSubmissionsState())
    private val pluginWorkerJobs = MutableStateFlow<Map<String, Job>>(emptyMap())

    private val queueMutex = Mutex()
    private val pluginQueues = LinkedHashMap<String, PluginQueueState>()

    private val dbMutex = Mutex()
    private var lastMaintenanceMillis: Long = 0L
    private var writesSinceMaintenance: Int = 0

    private val unregisterCleaner: () -> Unit = subsystem.registerTimelineProcessorCleaner(::clearPlugin)

    init {
        var previousFingerprints = emptyMap<String, ActivePluginFingerprint>()
        var initialized = false
        combine(
            subsystem.running,
            subsystem.storage.changes,
            grants.observedGrants(),
        ) { _, _, currentGrants ->
            currentActivePlugins(currentGrants)
        }.onEach { activeList ->
            val nextFingerprints =
                activeList.associate { plugin ->
                    plugin.pluginId to
                        ActivePluginFingerprint(
                            cacheKey = plugin.cacheKey,
                            allowMediaNetwork = plugin.allowMediaNetwork,
                            displayName = plugin.displayName,
                            iconPath = plugin.iconPath,
                        )
                }
            val removedIds =
                if (!initialized) {
                    subsystem.running.value.plugins.values
                        .filter { it.installed.manifest.timelineProcessor != null && it.installed.pluginId !in nextFingerprints }
                        .map { it.installed.pluginId }
                        .toSet()
                } else {
                    previousFingerprints.keys - nextFingerprints.keys
                }
            initialized = true
            removedIds.forEach(::clearPlugin)
            if (nextFingerprints != previousFingerprints) {
                previousFingerprints = nextFingerprints
                mutableProcessingRevision.update { it + 1L }
                bumpRevision()
            }
        }.launchIn(serviceScope)

        taskScope.launch {
            dbMutex.withLock {
                withContext(ioDispatcher) {
                    runMaintenanceLocked(nowEpochMillis())
                }
            }
        }
    }

    internal fun close() {
        unregisterCleaner()
        serviceJob.cancel()
    }

    internal suspend fun awaitIdleForTesting() {
        while (true) {
            val activeChildren = workSupervisor.children.toList()
            if (activeChildren.isEmpty()) {
                break
            }
            activeChildren.forEach { child ->
                child.join()
            }
        }
    }

    override fun submitPosts(posts: List<UiTimelineV2.Post>) {
        if (posts.isEmpty()) return
        if (!grants.isHydrated) {
            val hasTimelineProcessorCandidate =
                subsystem.running.value.plugins.values
                    .any { it.installed.manifest.timelineProcessor != null }
            if (!hasTimelineProcessorCandidate) return
            val dedupedInputs = LinkedHashMap<String, ExtractedPluginPostInput>(posts.size)
            posts.forEach { post ->
                val input = post.extractPluginPostInput()
                dedupedInputs[input.postEntityKey] = input
            }
            enqueuePendingSubmissions(dedupedInputs.values.toList())
            return
        }

        val activePlugins = currentActivePlugins()
        if (activePlugins.isEmpty()) return

        val dedupedInputs = LinkedHashMap<String, ExtractedPluginPostInput>(posts.size)
        posts.forEach { post ->
            val input = post.extractPluginPostInput()
            dedupedInputs[input.postEntityKey] = input
        }
        val inputs = dedupedInputs.values.toList()
        val now = nowEpochMillis()
        val snapshot = memoryState.value

        val needsAnyWork =
            inputs.any { input ->
                val postRecord = snapshot.posts[input.postEntityKey]
                activePlugins.any { active ->
                    !isSatisfiedOrCoolingDown(postRecord, active, input.sourceHash, now)
                }
            }
        if (!needsAnyWork) {
            touchLruKeys(inputs.map { it.postEntityKey })
            return
        }

        enqueuePendingSubmissions(inputs)
    }

    private fun enqueuePendingSubmissions(inputs: List<ExtractedPluginPostInput>) {
        if (inputs.isEmpty()) return
        var shouldLaunchDrain = false
        while (true) {
            val stamp = clearState.value
            val current = pendingSubmissions.value
            val merged = LinkedHashMap<String, StampedInput>(current.items.size + inputs.size)
            merged.putAll(current.items)
            inputs.forEach { input ->
                merged[input.postEntityKey] = StampedInput(input = input, stamp = stamp)
            }
            val launchNewDrain = !current.draining
            val next = PendingSubmissionsState(items = merged, draining = true)
            if (pendingSubmissions.compareAndSet(current, next)) {
                shouldLaunchDrain = launchNewDrain
                break
            }
        }
        if (shouldLaunchDrain) {
            taskScope.launch {
                drainPendingSubmissions()
            }
        }
    }

    private suspend fun drainPendingSubmissions() {
        var exitedCleanly = false
        try {
            while (true) {
                yield()
                if (coalesceDelayMillis > 0L) {
                    delay(minOf(coalesceDelayMillis, MAX_INTAKE_COALESCE_MILLIS))
                }
                var batch: List<StampedInput> = emptyList()
                while (true) {
                    val current = pendingSubmissions.value
                    if (current.items.isEmpty()) {
                        if (
                            pendingSubmissions.compareAndSet(
                                current,
                                PendingSubmissionsState(items = emptyMap(), draining = false),
                            )
                        ) {
                            exitedCleanly = true
                            return
                        }
                    } else {
                        batch = current.items.values.toList()
                        if (
                            pendingSubmissions.compareAndSet(
                                current,
                                PendingSubmissionsState(items = emptyMap(), draining = true),
                            )
                        ) {
                            break
                        }
                    }
                }
                processSubmittedInputs(batch)
            }
        } finally {
            if (!exitedCleanly) {
                var shouldRelaunch = false
                while (true) {
                    val current = pendingSubmissions.value
                    if (current.items.isEmpty() || !taskScope.isActive) {
                        if (pendingSubmissions.compareAndSet(current, current.copy(draining = false))) {
                            break
                        }
                    } else {
                        shouldRelaunch = true
                        break
                    }
                }
                if (shouldRelaunch && taskScope.isActive) {
                    taskScope.launch {
                        drainPendingSubmissions()
                    }
                }
            }
        }
    }

    override fun widgetsForPost(post: UiTimelineV2.Post): ImmutableList<UiPluginWidgetCard> {
        val activePlugins = currentActivePlugins()
        if (activePlugins.isEmpty()) return persistentListOf()

        val postEntityKey = post.pluginPostEntityKey()
        val postRecord = memoryState.value.posts[postEntityKey] ?: return persistentListOf()
        if (postRecord.entries.isEmpty()) return persistentListOf()

        val now = nowEpochMillis()
        val currentSourceHash by lazy(LazyThreadSafetyMode.NONE) {
            post.extractPluginPostInput().sourceHash
        }

        val rendered = ArrayList<UiPluginWidgetCard>(minOf(WireTimelineProcessorLimitsV1.MAX_DISPLAYED_CARDS_PER_POST, 4))
        for (active in activePlugins) {
            if (rendered.size >= WireTimelineProcessorLimitsV1.MAX_DISPLAYED_CARDS_PER_POST) {
                break
            }
            val entry = postRecord.entries[active.pluginId] ?: continue
            val isRefreshing = active.pluginId in postRecord.refreshingPlugins
            if (entry.pluginCacheKey != active.cacheKey) continue
            if (entry.sourceHash != currentSourceHash) continue
            if (entry.expiresAtMillis != null && entry.expiresAtMillis <= now && !isRefreshing) continue
            if (entry.cards.isEmpty()) continue

            for (payload in entry.cards) {
                if (rendered.size >= WireTimelineProcessorLimitsV1.MAX_DISPLAYED_CARDS_PER_POST) {
                    break
                }
                val toggled = postRecord.toggledCollapsibles[active.pluginId to payload.id].orEmpty()
                val shouldBindRefresh = payload.refreshable || payload.containsRefreshAction()
                val uiCard =
                    payload.toUiCard(
                        pluginId = active.pluginId,
                        pluginName = active.displayName,
                        pluginIconPath = active.iconPath,
                        allowMediaNetwork = active.allowMediaNetwork,
                        isRefreshing = isRefreshing,
                        expandedCollapsibles = toggled,
                        onToggleCollapsible = { collapsibleId ->
                            toggleCollapsible(postEntityKey, active.pluginId, payload.id, collapsibleId)
                        },
                        onRefresh =
                            if (shouldBindRefresh) {
                                { refreshPostPlugin(post, active.pluginId) }
                            } else {
                                null
                            },
                    )
                if (uiCard != null) {
                    rendered.add(uiCard)
                }
            }
        }
        return if (rendered.isEmpty()) persistentListOf() else rendered.toImmutableList()
    }

    override fun refreshPostPlugin(
        post: UiTimelineV2.Post,
        pluginId: String,
    ) {
        val initialStamp = clearState.value
        if (!grants.isHydrated) {
            val candidate =
                subsystem.running.value.plugins[pluginId]
                    ?.takeIf { it.installed.manifest.timelineProcessor != null }
                    ?: return
            val input = post.extractPluginPostInput()
            taskScope.launch {
                val hydratedGrants = awaitCurrentGrantsSafely()
                if (!initialStamp.isPostPluginStillValid(input.postEntityKey, pluginId, clearState.value)) return@launch
                val active = toActiveProcessorPluginOrNull(candidate, hydratedGrants, localeProvider()) ?: return@launch
                if (active.compiledFilter?.matches(input) == false) return@launch
                markPostPluginRefreshing(input.postEntityKey, pluginId)
                enqueueBatchItems(
                    pluginId = pluginId,
                    expectedCacheKey = active.cacheKey,
                    trigger = TimelineProcessTriggerV1.ManualRefresh,
                    items = listOf(StampedInput(input = input, stamp = initialStamp)),
                )
            }
            return
        }

        val active = currentActivePlugin(pluginId) ?: return
        val input = post.extractPluginPostInput()
        if (active.compiledFilter?.matches(input) == false) return
        markPostPluginRefreshing(input.postEntityKey, pluginId)

        taskScope.launch {
            enqueueBatchItems(
                pluginId = pluginId,
                expectedCacheKey = active.cacheKey,
                trigger = TimelineProcessTriggerV1.ManualRefresh,
                items = listOf(StampedInput(input = input, stamp = initialStamp)),
            )
        }
    }

    private suspend fun awaitCurrentGrantsSafely(): Map<String, Set<String>> =
        try {
            grants.awaitCurrent()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            grants.current
        }

    private fun markPostPluginRefreshing(
        postEntityKey: String,
        pluginId: String,
    ) {
        var changed = false
        memoryState.update { current ->
            current.updatePost(postEntityKey) { record ->
                val nextRefreshing = record.refreshingPlugins + pluginId
                val nextCooldowns = record.cooldowns - pluginId
                if (nextRefreshing != record.refreshingPlugins || nextCooldowns != record.cooldowns) {
                    changed = true
                }
                record.copy(
                    refreshingPlugins = nextRefreshing,
                    cooldowns = nextCooldowns,
                )
            }
        }
        if (changed) {
            bumpRevision()
        }
    }

    override fun toggleCollapsible(
        postEntityKey: String,
        pluginId: String,
        cardId: String,
        collapsibleId: String,
    ) {
        if (collapsibleId.isBlank()) return
        var toggledAny = false
        memoryState.update { current ->
            if (postEntityKey !in current.posts) {
                return@update current
            }
            toggledAny = true
            current.updatePost(postEntityKey) { record ->
                val mapKey = pluginId to cardId
                val existing = record.toggledCollapsibles[mapKey].orEmpty()
                val updated = if (collapsibleId in existing) existing - collapsibleId else existing + collapsibleId
                val nextMap =
                    if (updated.isEmpty()) {
                        record.toggledCollapsibles - mapKey
                    } else {
                        record.toggledCollapsibles + (mapKey to updated)
                    }
                record.copy(toggledCollapsibles = nextMap)
            }
        }
        if (toggledAny) {
            bumpRevision()
        }
    }

    override fun clearPlugin(pluginId: String) {
        clearState.update { current ->
            current.copy(
                pluginEpochs = current.pluginEpochs + (pluginId to ((current.pluginEpochs[pluginId] ?: 0L) + 1L)),
            )
        }
        var workerToCancel: Job? = null
        pluginWorkerJobs.update { current ->
            workerToCancel = current[pluginId]
            current - pluginId
        }
        workerToCancel?.cancel()

        var changedMemory = false
        memoryState.update { current ->
            val stripped = current.removePlugin(pluginId)
            if (stripped !== current) {
                changedMemory = true
            }
            stripped
        }
        if (changedMemory) {
            bumpRevision()
        }

        taskScope.launch {
            queueMutex.withLock {
                pluginQueues.remove(pluginId)?.workerJob?.cancel()
            }
            dbMutex.withLock {
                withContext(ioDispatcher) {
                    try {
                        dao.deleteByPluginId(pluginId)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                    }
                }
            }
        }
    }

    override fun clearAccount(accountType: AccountType) {
        val prefix = "${accountType}_"
        clearState.update { current ->
            current.copy(
                accountPrefixEpochs =
                    current.accountPrefixEpochs + (prefix to ((current.accountPrefixEpochs[prefix] ?: 0L) + 1L)),
            )
        }
        pendingSubmissions.update { current ->
            if (current.items.keys.none { it.startsWith(prefix) }) {
                current
            } else {
                current.copy(items = current.items.filterKeys { !it.startsWith(prefix) })
            }
        }

        var removedVisible = false
        memoryState.update { current ->
            val (stripped, hadVisible) = current.removeAccountPrefix(prefix)
            if (hadVisible) {
                removedVisible = true
            }
            stripped
        }
        if (removedVisible) {
            bumpRevision()
        }

        taskScope.launch {
            queueMutex.withLock {
                for (queue in pluginQueues.values) {
                    queue.pendingManual.keys.removeAll { it.startsWith(prefix) }
                    queue.pendingAuto.keys.removeAll { it.startsWith(prefix) }
                    queue.inFlightAuto.keys.removeAll { it.startsWith(prefix) }
                }
            }
            dbMutex.withLock {
                withContext(ioDispatcher) {
                    try {
                        dao.deleteByAccountType(accountType)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                    }
                }
            }
        }
    }

    override fun clearAll() {
        val clearedGlobalEpoch =
            clearState
                .updateAndGet { current ->
                    current.copy(globalEpoch = current.globalEpoch + 1L)
                }.globalEpoch
        pendingSubmissions.update { current ->
            if (current.items.isEmpty()) {
                current
            } else {
                current.copy(items = emptyMap())
            }
        }

        var workersToCancel: List<Job> = emptyList()
        pluginWorkerJobs.update { current ->
            workersToCancel = current.values.toList()
            emptyMap()
        }
        workersToCancel.forEach(Job::cancel)

        var hadMemory = false
        memoryState.update { current ->
            if (current.posts.isEmpty()) {
                current
            } else {
                hadMemory = true
                MemorySnapshot()
            }
        }
        if (hadMemory) {
            bumpRevision()
        }

        taskScope.launch {
            queueMutex.withLock {
                for (queue in pluginQueues.values) {
                    queue.pendingManual.entries.removeAll { it.value.stamp.globalEpoch < clearedGlobalEpoch }
                    queue.pendingAuto.entries.removeAll { it.value.stamp.globalEpoch < clearedGlobalEpoch }
                    queue.inFlightAuto.entries.removeAll { it.value.stamp.globalEpoch < clearedGlobalEpoch }
                    if (queue.pendingManual.isEmpty() && queue.pendingAuto.isEmpty() && queue.workerJob?.isActive != true) {
                        queue.workerJob = null
                    }
                }
            }
            dbMutex.withLock {
                withContext(ioDispatcher) {
                    try {
                        dao.deleteAll()
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                    }
                }
            }
        }
    }

    override fun clearFailureCooldowns() {
        var clearedAny = false
        memoryState.update { current ->
            val cleared = current.clearCooldowns()
            clearedAny = cleared !== current
            cleared
        }
        if (clearedAny) {
            mutableProcessingRevision.update { it + 1L }
            bumpRevision()
        }
    }

    private suspend fun processSubmittedInputs(batch: List<StampedInput>) {
        if (batch.isEmpty()) return
        val hydratedGrants = awaitCurrentGrantsSafely()
        val activePlugins = currentActivePlugins(hydratedGrants)
        if (activePlugins.isEmpty()) return

        val liveAfterGrants =
            batch.filter { stamped ->
                stamped.stamp.isPostStillValid(stamped.input.postEntityKey, clearState.value)
            }
        if (liveAfterGrants.isEmpty()) return

        val nowBeforeDb = nowEpochMillis()
        val snapshotBeforeDb = memoryState.value
        val latestClearBeforeDb = clearState.value
        val uncheckedItems =
            liveAfterGrants.filter { stamped ->
                val input = stamped.input
                val record = snapshotBeforeDb.posts[input.postEntityKey]
                activePlugins.any { active ->
                    stamped.stamp.isPostPluginStillValid(input.postEntityKey, active.pluginId, latestClearBeforeDb) &&
                        !isSatisfiedOrCoolingDown(record, active, input.sourceHash, nowBeforeDb) &&
                        record?.hydratedPluginCacheKeys?.get(active.pluginId) != active.cacheKey
                }
            }

        if (uncheckedItems.isNotEmpty()) {
            val preDbStamp = clearState.value
            val uncheckedPostKeys = uncheckedItems.map { it.input.postEntityKey }
            val dbRows =
                withContext(ioDispatcher) {
                    try {
                        dao.getByPostKeys(uncheckedPostKeys)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        emptyList()
                    }
                }
            hydrateFromDatabase(uncheckedItems, dbRows, preDbStamp)
        } else {
            touchLruKeys(liveAfterGrants.map { it.input.postEntityKey })
        }

        val livePlugins = currentActivePlugins()
        if (livePlugins.isEmpty()) return
        val latestClearAfterDb = clearState.value
        val validAfterDb =
            liveAfterGrants.filter { stamped ->
                stamped.stamp.isPostStillValid(stamped.input.postEntityKey, latestClearAfterDb)
            }
        if (validAfterDb.isEmpty()) return

        val now = nowEpochMillis()
        val snapshot = memoryState.value

        val filterMisses = ArrayList<Pair<StampedInput, ActiveProcessorPlugin>>()
        val matchesByPlugin = LinkedHashMap<ActiveProcessorPlugin, MutableList<StampedInput>>()

        for (stamped in validAfterDb) {
            val input = stamped.input
            val record = snapshot.posts[input.postEntityKey]
            for (active in livePlugins) {
                if (!stamped.stamp.isPostPluginStillValid(input.postEntityKey, active.pluginId, latestClearAfterDb)) {
                    continue
                }
                if (isSatisfiedOrCoolingDown(record, active, input.sourceHash, now)) {
                    continue
                }
                val matches = active.compiledFilter?.matches(input) ?: true
                if (!matches) {
                    filterMisses.add(stamped to active)
                } else {
                    matchesByPlugin.getOrPut(active) { ArrayList() }.add(stamped)
                }
            }
        }

        if (filterMisses.isNotEmpty()) {
            memoryState.update { current ->
                val currentClear = clearState.value
                var next = current
                filterMisses.forEach { (stamped, active) ->
                    val input = stamped.input
                    if (
                        stamped.stamp.isPostPluginStillValid(input.postEntityKey, active.pluginId, currentClear) &&
                        isPluginStillActiveWithKey(active.pluginId, active.cacheKey)
                    ) {
                        next =
                            next.updatePost(input.postEntityKey) { record ->
                                val existing = record.entries[active.pluginId]
                                if (existing != null && existing.pluginCacheKey == active.cacheKey &&
                                    existing.sourceHash == input.sourceHash
                                ) {
                                    record
                                } else {
                                    record.copy(
                                        entries =
                                            record.entries +
                                                (
                                                    active.pluginId to
                                                        CachedPluginPostEntry(
                                                            pluginCacheKey = active.cacheKey,
                                                            sourceHash = input.sourceHash,
                                                            cards = emptyList(),
                                                            expiresAtMillis = null,
                                                            cachePolicy = WidgetCachePolicyV1.MemoryOnly,
                                                        )
                                                ),
                                    )
                                }
                            }
                    }
                }
                next
            }
        }

        for ((active, matchingInputs) in matchesByPlugin) {
            enqueueBatchItems(
                pluginId = active.pluginId,
                expectedCacheKey = active.cacheKey,
                trigger = TimelineProcessTriggerV1.Auto,
                items = matchingInputs,
            )
        }
    }

    private fun hydrateFromDatabase(
        uncheckedItems: List<StampedInput>,
        dbRows: List<DbPluginPostWidget>,
        preDbStamp: ClearEpochsSnapshot,
    ) {
        val liveByPluginId = currentActivePlugins().associateBy { it.pluginId }
        if (liveByPluginId.isEmpty()) return

        val now = nowEpochMillis()
        val rowsByPostAndPlugin =
            dbRows.associateBy { row ->
                row.postEntityKey to row.pluginId
            }

        var hydratedVisibleCards = false
        memoryState.update { current ->
            val currentClear = clearState.value
            var next = current
            for (stamped in uncheckedItems) {
                val input = stamped.input
                val postKey = input.postEntityKey
                if (
                    !stamped.stamp.isPostStillValid(postKey, currentClear) ||
                    !preDbStamp.isPostStillValid(postKey, currentClear)
                ) {
                    continue
                }
                next =
                    next.updatePost(postKey) { record ->
                        var updatedHydrated = record.hydratedPluginCacheKeys
                        var updatedEntries = record.entries
                        for ((pluginId, livePlugin) in liveByPluginId) {
                            if (
                                !stamped.stamp.isPostPluginStillValid(postKey, pluginId, currentClear) ||
                                !preDbStamp.isPostPluginStillValid(postKey, pluginId, currentClear)
                            ) {
                                continue
                            }

                            updatedHydrated = updatedHydrated + (pluginId to livePlugin.cacheKey)
                            val existing = updatedEntries[pluginId]
                            if (existing?.isCurrent(livePlugin.cacheKey, input.sourceHash, now) == true) {
                                continue
                            }

                            val row = rowsByPostAndPlugin[postKey to pluginId] ?: continue
                            if (row.pluginCacheKey != livePlugin.cacheKey) continue
                            if (row.sourceHash != input.sourceHash) continue
                            val rowExpiresAtMillis = row.expiresAtMillis
                            if (rowExpiresAtMillis != null && rowExpiresAtMillis <= now) continue
                            val decodedCards = decodeCardsJson(row.cardsJson) ?: continue

                            if (decodedCards.isNotEmpty()) {
                                hydratedVisibleCards = true
                            }
                            updatedEntries =
                                updatedEntries +
                                (
                                    pluginId to
                                        CachedPluginPostEntry(
                                            pluginCacheKey = livePlugin.cacheKey,
                                            sourceHash = input.sourceHash,
                                            cards = decodedCards,
                                            expiresAtMillis = row.expiresAtMillis,
                                            cachePolicy = WidgetCachePolicyV1.Disk,
                                        )
                                )
                        }
                        record.copy(
                            hydratedPluginCacheKeys = updatedHydrated,
                            entries = updatedEntries,
                        )
                    }
            }
            next
        }

        if (hydratedVisibleCards) {
            bumpRevision()
        }
    }

    private suspend fun enqueueBatchItems(
        pluginId: String,
        expectedCacheKey: String,
        trigger: TimelineProcessTriggerV1,
        items: List<StampedInput>,
    ) {
        if (items.isEmpty()) return
        queueMutex.withLock {
            val active = currentActivePlugin(pluginId)
            if (active == null || active.cacheKey != expectedCacheKey) {
                if (trigger == TimelineProcessTriggerV1.ManualRefresh) {
                    clearRefreshingForItems(pluginId, items.map { it.input })
                }
                return@withLock
            }
            val currentClear = clearState.value
            val queue = pluginQueues.getOrPut(pluginId, ::PluginQueueState)
            when (trigger) {
                TimelineProcessTriggerV1.ManualRefresh -> {
                    val cancelledInputs = ArrayList<ExtractedPluginPostInput>()
                    items.forEach { stamped ->
                        val postKey = stamped.input.postEntityKey
                        if (!stamped.stamp.isPostPluginStillValid(postKey, pluginId, currentClear)) {
                            cancelledInputs.add(stamped.input)
                        } else {
                            queue.pendingAuto.remove(postKey)
                            queue.pendingManual[postKey] = stamped
                        }
                    }
                    if (cancelledInputs.isNotEmpty()) {
                        clearRefreshingForItems(pluginId, cancelledInputs)
                    }
                }

                TimelineProcessTriggerV1.Auto -> {
                    val now = nowEpochMillis()
                    val snapshot = memoryState.value
                    items.forEach { stamped ->
                        val input = stamped.input
                        val postKey = input.postEntityKey
                        if (!stamped.stamp.isPostPluginStillValid(postKey, pluginId, currentClear)) {
                            return@forEach
                        }
                        val inFlight = queue.inFlightAuto[postKey]
                        val alreadyInFlight =
                            inFlight != null &&
                                inFlight.cacheKey == expectedCacheKey &&
                                inFlight.sourceHash == input.sourceHash &&
                                inFlight.stamp.isPostPluginStillValid(postKey, pluginId, currentClear)
                        val alreadyManual = postKey in queue.pendingManual
                        val record = snapshot.posts[postKey]
                        if (
                            !alreadyInFlight &&
                            !alreadyManual &&
                            !isSatisfiedOrCoolingDown(record, active, input.sourceHash, now)
                        ) {
                            queue.pendingAuto[postKey] = stamped
                        }
                    }
                }
            }

            if (queue.pendingManual.isEmpty() && queue.pendingAuto.isEmpty()) {
                return@withLock
            }
            if (queue.workerJob?.isActive != true) {
                val epoch = currentClearEpoch(pluginId)
                val job =
                    taskScope.launch {
                        runPluginWorker(pluginId, epoch)
                    }
                queue.workerJob = job
                pluginWorkerJobs.update { it + (pluginId to job) }
            }
        }
    }

    private suspend fun runPluginWorker(
        pluginId: String,
        workerEpoch: Long,
    ) {
        try {
            while (true) {
                val immediateManual =
                    queueMutex.withLock {
                        if (currentClearEpoch(pluginId) != workerEpoch || currentActivePlugin(pluginId) == null) {
                            val removed = pluginQueues.remove(pluginId)
                            removed?.pendingManual?.values?.let { queued ->
                                clearRefreshingForItems(pluginId, queued.map { it.input })
                            }
                            return
                        }
                        val queue = pluginQueues[pluginId] ?: return
                        if (queue.pendingManual.isNotEmpty()) {
                            queue.pendingManual.values
                                .toList()
                                .also { queue.pendingManual.clear() }
                        } else if (queue.pendingAuto.isEmpty()) {
                            queue.workerJob = null
                            return
                        } else {
                            null
                        }
                    }

                if (immediateManual != null) {
                    val active = currentActivePlugin(pluginId)
                    if (active == null || currentClearEpoch(pluginId) != workerEpoch) {
                        clearRefreshingForItems(pluginId, immediateManual.map { it.input })
                        return
                    }
                    executeBatches(active, workerEpoch, TimelineProcessTriggerV1.ManualRefresh, immediateManual)
                    continue
                }

                if (coalesceDelayMillis > 0L) {
                    delay(coalesceDelayMillis)
                }

                val autoBatch: Triple<ActiveProcessorPlugin, List<StampedInput>, List<Pair<String, InFlightAutoEntry>>>? =
                    queueMutex.withLock {
                        val active = currentActivePlugin(pluginId)
                        if (active == null || currentClearEpoch(pluginId) != workerEpoch) {
                            val removed = pluginQueues.remove(pluginId)
                            removed?.pendingManual?.values?.let { queued ->
                                clearRefreshingForItems(pluginId, queued.map { it.input })
                            }
                            return
                        }
                        val queue = pluginQueues[pluginId] ?: return
                        if (queue.pendingManual.isNotEmpty()) {
                            null
                        } else if (queue.pendingAuto.isEmpty()) {
                            queue.workerJob = null
                            return
                        } else {
                            val drained = queue.pendingAuto.values.toList()
                            queue.pendingAuto.clear()
                            val inFlightEntries =
                                drained.map { stamped ->
                                    val entry =
                                        InFlightAutoEntry(
                                            cacheKey = active.cacheKey,
                                            sourceHash = stamped.input.sourceHash,
                                            stamp = stamped.stamp,
                                        )
                                    queue.inFlightAuto[stamped.input.postEntityKey] = entry
                                    stamped.input.postEntityKey to entry
                                }
                            Triple(active, drained, inFlightEntries)
                        }
                    }

                if (autoBatch == null) {
                    continue
                }
                val (active, drained, inFlightEntries) = autoBatch
                try {
                    executeBatches(active, workerEpoch, TimelineProcessTriggerV1.Auto, drained)
                } finally {
                    queueMutex.withLock {
                        val queue = pluginQueues[pluginId]
                        if (queue != null) {
                            inFlightEntries.forEach { (postKey, entry) ->
                                if (queue.inFlightAuto[postKey] === entry) {
                                    queue.inFlightAuto.remove(postKey)
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            pluginWorkerJobs.update { current ->
                if (current[pluginId] == kotlin.coroutines.coroutineContext[Job]) {
                    current - pluginId
                } else {
                    current
                }
            }
        }
    }

    private suspend fun executeBatches(
        active: ActiveProcessorPlugin,
        expectedEpoch: Long,
        trigger: TimelineProcessTriggerV1,
        items: List<StampedInput>,
    ) {
        val batchSize = active.maxBatchSize.coerceIn(1, WireTimelineProcessorLimitsV1.MAX_BATCH_SIZE)
        for (chunk in items.chunked(batchSize)) {
            val itemPairs = chunk.mapIndexed { index, stamped -> "item_$index" to stamped }
            val request = encodeTimelineProcessBatchRequest(active.locale, trigger, itemPairs.map { (id, stamped) -> id to stamped.input })
            for ((range, boundedRequest) in request.splitTimelineProcessBatchRequest(MAX_INVOCATION_REQUEST_BYTES)) {
                executeChunk(active, expectedEpoch, trigger, itemPairs.subList(range.first, range.last + 1), boundedRequest)
            }
        }
    }

    private suspend fun executeChunk(
        active: ActiveProcessorPlugin,
        expectedEpoch: Long,
        trigger: TimelineProcessTriggerV1,
        chunk: List<Pair<String, StampedInput>>,
        boundedRequest: JsonObject?,
    ) {
        val pluginId = active.pluginId
        val plugin = active.running
        val expectedCacheKey = active.cacheKey
        if (!isPluginInvocationStillValid(plugin, expectedCacheKey, expectedEpoch)) {
            clearRefreshingForItems(pluginId, chunk.map { it.second.input })
            return
        }
        val currentClearAtStart = clearState.value
        val (itemPairs, staleItems) =
            chunk.partition { (_, stamped) ->
                stamped.stamp.isPostPluginStillValid(stamped.input.postEntityKey, pluginId, currentClearAtStart)
            }
        if (staleItems.isNotEmpty()) {
            clearRefreshingForItems(pluginId, staleItems.map { it.second.input })
        }
        if (itemPairs.isEmpty()) return
        val validChunk = itemPairs.map { it.second }
        if (boundedRequest == null) {
            recordFailureCooldown(plugin, expectedCacheKey, expectedEpoch, validChunk)
            return
        }
        val validItemIds = itemPairs.mapTo(LinkedHashSet(itemPairs.size)) { it.first }
        val requestJson =
            if (staleItems.isEmpty()) {
                boundedRequest
            } else {
                encodeTimelineProcessBatchRequest(active.locale, trigger, itemPairs.map { (id, stamped) -> id to stamped.input })
            }
        val runtimeKey = PluginRuntimeKeyV1.plugin(pluginId, plugin.installed.packageHash)
        val invocationContext =
            PluginInvocationContextV1.plugin(
                pluginId = pluginId,
                packageHash = plugin.installed.packageHash,
                httpOrigins = plugin.installed.manifest.permissions.httpOrigins,
                locale = active.locale,
                platformId =
                    plugin.installed.manifest.platform
                        ?.id
                        .orEmpty(),
            )

        val validatedByItemId: Map<String, ValidatedPluginItemCardsV1> =
            try {
                var parsedInsideValidate: Map<String, ValidatedPluginItemCardsV1>? = null
                val rawResponse =
                    runtimeInvoker(plugin, runtimeKey, invocationContext, requestJson) { element ->
                        parsedInsideValidate = parseAndValidateTimelineProcessBatchResponse(element, validItemIds)
                    }
                parsedInsideValidate ?: parseAndValidateTimelineProcessBatchResponse(rawResponse, validItemIds)
            } catch (cancelled: CancellationException) {
                clearRefreshingForItems(pluginId, validChunk.map { it.input })
                throw cancelled
            } catch (_: Throwable) {
                recordFailureCooldown(
                    plugin = plugin,
                    expectedCacheKey = expectedCacheKey,
                    expectedEpoch = expectedEpoch,
                    chunk = validChunk,
                )
                return
            }

        if (!isPluginInvocationStillValid(plugin, expectedCacheKey, expectedEpoch)) {
            clearRefreshingForItems(pluginId, validChunk.map { it.input })
            return
        }

        val now = nowEpochMillis()
        val resolvedItems =
            itemPairs.map { (itemId, stamped) ->
                val itemResult = validatedByItemId[itemId]
                val cachePolicy = itemResult?.cachePolicy ?: WidgetCachePolicyV1.Disk
                val ttlSeconds = itemResult?.ttlSeconds
                val cards = itemResult?.cards.orEmpty()
                val expiresAtMillis =
                    when {
                        ttlSeconds != null -> now + ttlSeconds * 1_000L
                        cachePolicy == WidgetCachePolicyV1.None -> now + WireTimelineProcessorLimitsV1.MIN_TTL_SECONDS * 1_000L
                        else -> null
                    }
                ResolvedBatchItem(
                    stamped = stamped,
                    entry =
                        CachedPluginPostEntry(
                            pluginCacheKey = expectedCacheKey,
                            sourceHash = stamped.input.sourceHash,
                            cards = cards,
                            expiresAtMillis = expiresAtMillis,
                            cachePolicy = cachePolicy,
                        ),
                )
            }

        dbMutex.withLock {
            if (!isPluginInvocationStillValid(plugin, expectedCacheKey, expectedEpoch)) return@withLock
            val currentClear = clearState.value
            val (diskItems, transientItems) =
                resolvedItems
                    .filter { it.stamped.stamp.isPostPluginStillValid(it.stamped.input.postEntityKey, pluginId, currentClear) }
                    .partition { it.entry.cachePolicy == WidgetCachePolicyV1.Disk }
            withContext(ioDispatcher) {
                if (transientItems.isNotEmpty()) {
                    try {
                        dao.deleteByPluginAndPostKeys(pluginId, transientItems.map { it.stamped.input.postEntityKey })
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                    }
                }
                if (diskItems.isEmpty()) return@withContext
                try {
                    dao.upsertAll(
                        diskItems.map { resolved ->
                            DbPluginPostWidget(
                                postEntityKey = resolved.stamped.input.postEntityKey,
                                pluginId = pluginId,
                                pluginCacheKey = expectedCacheKey,
                                sourceHash = resolved.entry.sourceHash,
                                cardsJson = encodeCardsJson(resolved.entry.cards),
                                createdAtMillis = now,
                                expiresAtMillis = resolved.entry.expiresAtMillis,
                            )
                        },
                    )
                    writesSinceMaintenance += diskItems.size
                    if (
                        writesSinceMaintenance >= MAINTENANCE_WRITE_THRESHOLD ||
                        now - lastMaintenanceMillis >= MAINTENANCE_INTERVAL_MILLIS
                    ) {
                        runMaintenanceLocked(now)
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                }
            }
        }

        if (!isPluginInvocationStillValid(plugin, expectedCacheKey, expectedEpoch)) {
            clearRefreshingForItems(pluginId, validChunk.map { it.input })
            return
        }

        var visibleStateChanged = false
        memoryState.update { current ->
            if (!isPluginInvocationStillValid(plugin, expectedCacheKey, expectedEpoch)) {
                return@update current
            }
            val currentClear = clearState.value
            var next = current
            for (resolved in resolvedItems) {
                val postKey = resolved.stamped.input.postEntityKey
                if (!resolved.stamped.stamp.isPostPluginStillValid(postKey, pluginId, currentClear)) {
                    if (postKey in next.posts) {
                        next =
                            next.updatePost(postKey) { record ->
                                if (pluginId in record.refreshingPlugins) {
                                    visibleStateChanged = true
                                    record.copy(refreshingPlugins = record.refreshingPlugins - pluginId)
                                } else {
                                    record
                                }
                            }
                    }
                    continue
                }
                next =
                    next.updatePost(postKey) { record ->
                        val previousEntry = record.entries[pluginId]
                        val entry = resolved.entry
                        val wasRefreshing = pluginId in record.refreshingPlugins
                        if (
                            wasRefreshing ||
                            previousEntry?.cards != entry.cards ||
                            (entry.cards.isNotEmpty() && !previousEntry.isCurrent(expectedCacheKey, entry.sourceHash, now))
                        ) {
                            visibleStateChanged = true
                        }
                        val nextCollapsibles =
                            if (previousEntry != null && previousEntry.sourceHash != entry.sourceHash) {
                                record.toggledCollapsibles.filterKeys { (ownerPluginId, _) -> ownerPluginId != pluginId }
                            } else {
                                record.toggledCollapsibles
                            }
                        record.copy(
                            hydratedPluginCacheKeys = record.hydratedPluginCacheKeys + (pluginId to expectedCacheKey),
                            entries = record.entries + (pluginId to entry),
                            refreshingPlugins = record.refreshingPlugins - pluginId,
                            cooldowns = record.cooldowns - pluginId,
                            toggledCollapsibles = nextCollapsibles,
                        )
                    }
            }
            next
        }

        if (visibleStateChanged) {
            bumpRevision()
        }
    }

    private fun recordFailureCooldown(
        plugin: RunningPluginV1,
        expectedCacheKey: String,
        expectedEpoch: Long,
        chunk: List<StampedInput>,
    ) {
        val pluginId = plugin.installed.pluginId
        if (!isPluginInvocationStillValid(plugin, expectedCacheKey, expectedEpoch)) {
            clearRefreshingForItems(pluginId, chunk.map { it.input })
            return
        }
        val untilMillis = nowEpochMillis() + errorCooldownMillis
        var hadRefreshing = false
        memoryState.update { current ->
            val currentClear = clearState.value
            var next = current
            chunk.forEach { stamped ->
                val input = stamped.input
                val postKey = input.postEntityKey
                if (!stamped.stamp.isPostPluginStillValid(postKey, pluginId, currentClear)) {
                    if (postKey in next.posts) {
                        next =
                            next.updatePost(postKey) { record ->
                                if (pluginId in record.refreshingPlugins) {
                                    hadRefreshing = true
                                    record.copy(refreshingPlugins = record.refreshingPlugins - pluginId)
                                } else {
                                    record
                                }
                            }
                    }
                    return@forEach
                }
                next =
                    next.updatePost(postKey) { record ->
                        if (pluginId in record.refreshingPlugins) {
                            hadRefreshing = true
                        }
                        record.copy(
                            refreshingPlugins = record.refreshingPlugins - pluginId,
                            cooldowns =
                                record.cooldowns +
                                    (
                                        pluginId to
                                            ErrorCooldownEntry(
                                                pluginCacheKey = expectedCacheKey,
                                                sourceHash = input.sourceHash,
                                                untilMillis = untilMillis,
                                            )
                                    ),
                        )
                    }
            }
            next
        }
        if (hadRefreshing) {
            bumpRevision()
        }
    }

    private fun clearRefreshingForItems(
        pluginId: String,
        items: List<ExtractedPluginPostInput>,
    ) {
        if (items.isEmpty()) return
        var changed = false
        memoryState.update { current ->
            var next = current
            items.forEach { input ->
                if (input.postEntityKey in next.posts) {
                    next =
                        next.updatePost(input.postEntityKey) { record ->
                            if (pluginId in record.refreshingPlugins) {
                                changed = true
                                record.copy(refreshingPlugins = record.refreshingPlugins - pluginId)
                            } else {
                                record
                            }
                        }
                }
            }
            next
        }
        if (changed) {
            bumpRevision()
        }
    }

    private suspend fun runMaintenanceLocked(nowMillis: Long) {
        try {
            dao.deleteExpiredBefore(nowMillis - EXPIRED_PRUNE_AGE_MILLIS)
            dao.trimOldestKeeping(MAX_DB_ROWS)
            lastMaintenanceMillis = nowMillis
            writesSinceMaintenance = 0
        } catch (error: Exception) {
            if (error is CancellationException) throw error
        }
    }

    private fun touchLruKeys(keys: List<String>) {
        if (keys.isEmpty()) return
        memoryState.update { current -> current.touchAll(keys) }
    }

    private fun isSatisfiedOrCoolingDown(
        record: PostMemoryRecord?,
        active: ActiveProcessorPlugin,
        sourceHash: String,
        nowMillis: Long,
    ): Boolean {
        if (record == null) return false
        val entry = record.entries[active.pluginId]
        if (entry?.isCurrent(active.cacheKey, sourceHash, nowMillis) == true) {
            return true
        }
        val cooldown = record.cooldowns[active.pluginId]
        return cooldown != null &&
            cooldown.pluginCacheKey == active.cacheKey &&
            cooldown.sourceHash == sourceHash &&
            cooldown.untilMillis > nowMillis
    }

    private fun isPluginStillActiveWithKey(
        pluginId: String,
        expectedCacheKey: String,
    ): Boolean {
        val active = currentActivePlugin(pluginId) ?: return false
        return active.cacheKey == expectedCacheKey
    }

    private fun isPluginInvocationStillValid(
        plugin: RunningPluginV1,
        expectedCacheKey: String,
        expectedEpoch: Long,
    ): Boolean {
        val pluginId = plugin.installed.pluginId
        if (currentClearEpoch(pluginId) != expectedEpoch) return false
        if (subsystem.running.value.plugins[pluginId] !== plugin) return false
        if (!grants.isEnabled(pluginId, plugin.installed.manifest)) return false
        return computePluginCacheKey(plugin, sanitizeLocale(localeProvider())) == expectedCacheKey
    }

    private fun currentClearEpoch(pluginId: String): Long = clearState.value.pluginEpochs[pluginId] ?: 0L

    private fun currentActivePlugin(pluginId: String): ActiveProcessorPlugin? {
        val running = subsystem.running.value.plugins[pluginId] ?: return null
        return toActiveProcessorPluginOrNull(running, grants.current, localeProvider())
    }

    private fun currentActivePlugins(grantMap: Map<String, Set<String>> = grants.current): List<ActiveProcessorPlugin> {
        val runningMap = subsystem.running.value.plugins
        if (runningMap.isEmpty() || grantMap.isEmpty()) return emptyList()
        val locale = localeProvider()
        return runningMap.values
            .mapNotNull { running -> toActiveProcessorPluginOrNull(running, grantMap, locale) }
            .sortedBy { it.pluginId }
    }

    private fun toActiveProcessorPluginOrNull(
        running: RunningPluginV1,
        grantMap: Map<String, Set<String>>,
        locale: String,
    ): ActiveProcessorPlugin? {
        val manifest = running.installed.manifest
        val processor = manifest.timelineProcessor ?: return null
        val pluginId = running.installed.pluginId
        if (!grants.isEnabled(pluginId, manifest, grantMap)) {
            return null
        }
        val filterKey = FilterCacheKey(pluginId, running.installed.packageHash, running.generation)
        val existingCache = compiledFilters.value
        val compiledFilter =
            if (filterKey in existingCache) {
                existingCache[filterKey]
            } else {
                val compiled = processor.filter?.let(CompiledTimelineFilter::compile)
                compiledFilters.update { current ->
                    val pruned =
                        if (current.size >= MAX_COMPILED_FILTER_CACHE) {
                            current.filterKeys { it.pluginId in subsystem.running.value.plugins }
                        } else {
                            current
                        }
                    pruned + (filterKey to compiled)
                }
                compiled
            }

        val processorLocale = sanitizeLocale(locale)
        return ActiveProcessorPlugin(
            pluginId = pluginId,
            running = running,
            cacheKey = computePluginCacheKey(running, processorLocale),
            locale = processorLocale,
            maxBatchSize = processor.maxBatchSize,
            compiledFilter = compiledFilter,
            displayName = resolvePluginDisplayName(running, processorLocale),
            iconPath = running.iconPath.takeIf { it.isNotBlank() },
            allowMediaNetwork = manifest.permissions.mediaNetwork,
        )
    }

    private fun computePluginCacheKey(
        plugin: RunningPluginV1,
        locale: String,
    ): String {
        val manifest = plugin.installed.manifest
        val namespaces = requireNotNull(manifest.timelineProcessor).cache.storageNamespaces
        val token =
            runCatching {
                subsystem.storage.token(manifest.id, namespaces)
            }.getOrElse { "unavailable" }
        return "plugin:${manifest.id}:${plugin.installed.packageHash}:${plugin.generation}:$token:$locale"
    }

    private fun resolvePluginDisplayName(
        plugin: RunningPluginV1,
        locale: String,
    ): String {
        val installed = plugin.installed
        return when (val uiText = installed.manifest.name.toUiText(installed.pluginId)) {
            is UiText.Raw -> {
                uiText.string
            }

            is UiText.ExternalRef -> {
                runCatching {
                    PluginCatalogBundleV1(
                        pluginId = installed.pluginId,
                        defaultLocale = installed.manifest.defaultLocale,
                        catalogs = installed.catalogs,
                    ).resolve(uiText, locale)
                }.getOrElse { installed.manifest.name.fallbackText }
            }

            else -> {
                installed.manifest.name.fallbackText
            }
        }
    }

    private fun sanitizeLocale(rawLocale: String): String {
        val normalized = rawLocale.trim().replace('_', '-').take(MAX_LOCALE_LENGTH)
        return if (normalized.isNotEmpty() && isValidLocaleTag(normalized)) normalized else DEFAULT_LOCALE
    }

    private fun bumpRevision() {
        mutableRevision.update { it + 1L }
    }

    private data class ActiveProcessorPlugin(
        val pluginId: String,
        val running: RunningPluginV1,
        val cacheKey: String,
        val locale: String,
        val maxBatchSize: Int,
        val compiledFilter: CompiledTimelineFilter?,
        val displayName: String,
        val iconPath: String?,
        val allowMediaNetwork: Boolean,
    )

    private data class ActivePluginFingerprint(
        val cacheKey: String,
        val allowMediaNetwork: Boolean,
        val displayName: String,
        val iconPath: String?,
    )

    private data class FilterCacheKey(
        val pluginId: String,
        val packageHash: String,
        val generation: Long,
    )

    private data class ClearEpochsSnapshot(
        val globalEpoch: Long = 0L,
        val pluginEpochs: Map<String, Long> = emptyMap(),
        val accountPrefixEpochs: Map<String, Long> = emptyMap(),
    ) {
        fun isPostStillValid(
            postEntityKey: String,
            latest: ClearEpochsSnapshot,
        ): Boolean {
            if (latest.globalEpoch != globalEpoch) return false
            for ((prefix, latestEpoch) in latest.accountPrefixEpochs) {
                if (postEntityKey.startsWith(prefix) && (accountPrefixEpochs[prefix] ?: 0L) != latestEpoch) {
                    return false
                }
            }
            return true
        }

        fun isPostPluginStillValid(
            postEntityKey: String,
            pluginId: String,
            latest: ClearEpochsSnapshot,
        ): Boolean {
            if (!isPostStillValid(postEntityKey, latest)) return false
            return (pluginEpochs[pluginId] ?: 0L) == (latest.pluginEpochs[pluginId] ?: 0L)
        }
    }

    private data class StampedInput(
        val input: ExtractedPluginPostInput,
        val stamp: ClearEpochsSnapshot,
    )

    private data class PendingSubmissionsState(
        val items: Map<String, StampedInput> = emptyMap(),
        val draining: Boolean = false,
    )

    private class InFlightAutoEntry(
        val cacheKey: String,
        val sourceHash: String,
        val stamp: ClearEpochsSnapshot,
    )

    private class PluginQueueState {
        val pendingManual = LinkedHashMap<String, StampedInput>()
        val pendingAuto = LinkedHashMap<String, StampedInput>()
        val inFlightAuto = LinkedHashMap<String, InFlightAutoEntry>()
        var workerJob: Job? = null
    }

    private data class ResolvedBatchItem(
        val stamped: StampedInput,
        val entry: CachedPluginPostEntry,
    )

    private data class CachedPluginPostEntry(
        val pluginCacheKey: String,
        val sourceHash: String,
        val cards: List<CachedWidgetCardPayloadV1>,
        val expiresAtMillis: Long?,
        val cachePolicy: WidgetCachePolicyV1,
    ) {
        fun isCurrent(
            cacheKey: String,
            currentSourceHash: String,
            now: Long,
        ): Boolean = pluginCacheKey == cacheKey && sourceHash == currentSourceHash && (expiresAtMillis == null || expiresAtMillis > now)
    }

    private data class ErrorCooldownEntry(
        val pluginCacheKey: String,
        val sourceHash: String,
        val untilMillis: Long,
    )

    private data class PostMemoryRecord(
        val hydratedPluginCacheKeys: Map<String, String> = emptyMap(),
        val entries: Map<String, CachedPluginPostEntry> = emptyMap(),
        val toggledCollapsibles: Map<Pair<String, String>, Set<String>> = emptyMap(),
        val refreshingPlugins: Set<String> = emptySet(),
        val cooldowns: Map<String, ErrorCooldownEntry> = emptyMap(),
    ) {
        val isEmpty: Boolean
            get() =
                hydratedPluginCacheKeys.isEmpty() &&
                    entries.isEmpty() &&
                    toggledCollapsibles.isEmpty() &&
                    refreshingPlugins.isEmpty() &&
                    cooldowns.isEmpty()
    }

    private class MemorySnapshot(
        val posts: Map<String, PostMemoryRecord> = emptyMap(),
    ) {
        fun updatePost(
            postEntityKey: String,
            transform: (PostMemoryRecord) -> PostMemoryRecord,
        ): MemorySnapshot {
            val copy = LinkedHashMap<String, PostMemoryRecord>(posts.size + 1)
            copy.putAll(posts)
            val existing = copy.remove(postEntityKey) ?: PostMemoryRecord()
            val updated = transform(existing)
            if (!updated.isEmpty) {
                copy[postEntityKey] = updated
                while (copy.size > MAX_MEMORY_POSTS) {
                    val eldestKey =
                        copy.entries
                            .iterator()
                            .next()
                            .key
                    copy.remove(eldestKey)
                }
            }
            return MemorySnapshot(copy)
        }

        fun touchAll(keys: List<String>): MemorySnapshot {
            if (posts.isEmpty()) return this
            var anyPresent = false
            for (key in keys) {
                if (key in posts) {
                    anyPresent = true
                    break
                }
            }
            if (!anyPresent) return this
            val copy = LinkedHashMap<String, PostMemoryRecord>(posts.size)
            copy.putAll(posts)
            for (key in keys) {
                val existing = copy.remove(key)
                if (existing != null) {
                    copy[key] = existing
                }
            }
            return MemorySnapshot(copy)
        }

        fun removePlugin(pluginId: String): MemorySnapshot {
            if (posts.isEmpty()) return this
            var changed = false
            val copy = LinkedHashMap<String, PostMemoryRecord>(posts.size)
            for ((postKey, record) in posts) {
                val hasPlugin =
                    pluginId in record.hydratedPluginCacheKeys ||
                        pluginId in record.entries ||
                        pluginId in record.refreshingPlugins ||
                        pluginId in record.cooldowns ||
                        record.toggledCollapsibles.keys.any { it.first == pluginId }
                if (!hasPlugin) {
                    copy[postKey] = record
                } else {
                    changed = true
                    val stripped =
                        PostMemoryRecord(
                            hydratedPluginCacheKeys = record.hydratedPluginCacheKeys - pluginId,
                            entries = record.entries - pluginId,
                            toggledCollapsibles = record.toggledCollapsibles.filterKeys { it.first != pluginId },
                            refreshingPlugins = record.refreshingPlugins - pluginId,
                            cooldowns = record.cooldowns - pluginId,
                        )
                    if (!stripped.isEmpty) {
                        copy[postKey] = stripped
                    }
                }
            }
            return if (changed) MemorySnapshot(copy) else this
        }

        fun removeAccountPrefix(prefix: String): Pair<MemorySnapshot, Boolean> {
            if (posts.isEmpty()) return this to false
            var changed = false
            var hadVisible = false
            val copy = LinkedHashMap<String, PostMemoryRecord>(posts.size)
            for ((postKey, record) in posts) {
                if (postKey.startsWith(prefix)) {
                    changed = true
                    if (record.entries.values.any { it.cards.isNotEmpty() } || record.refreshingPlugins.isNotEmpty()) {
                        hadVisible = true
                    }
                } else {
                    copy[postKey] = record
                }
            }
            return if (changed) MemorySnapshot(copy) to hadVisible else this to false
        }

        fun clearCooldowns(): MemorySnapshot {
            if (posts.isEmpty()) return this
            var changed = false
            val copy = LinkedHashMap<String, PostMemoryRecord>(posts.size)
            for ((postKey, record) in posts) {
                if (record.cooldowns.isEmpty()) {
                    copy[postKey] = record
                } else {
                    changed = true
                    val cleared = record.copy(cooldowns = emptyMap())
                    if (!cleared.isEmpty) {
                        copy[postKey] = cleared
                    }
                }
            }
            return if (changed) MemorySnapshot(copy) else this
        }
    }

    private companion object {
        const val METHOD_PROCESS_BATCH = "timelineProcessor.processBatch"
        const val DEFAULT_LOCALE = "en"
        const val MAX_LOCALE_LENGTH = 128
        const val MAX_MEMORY_POSTS = 1_024
        const val MAX_COMPILED_FILTER_CACHE = 64
        const val MAX_INTAKE_COALESCE_MILLIS = 10L
        const val DEFAULT_COALESCE_DELAY_MILLIS = 50L
        const val DEFAULT_ERROR_COOLDOWN_MILLIS = 60_000L
        const val EXPIRED_PRUNE_AGE_MILLIS = 7L * 24 * 60 * 60 * 1_000L
        const val MAX_DB_ROWS = 10_000
        const val MAINTENANCE_WRITE_THRESHOLD = 50
        const val MAINTENANCE_INTERVAL_MILLIS = 30L * 60 * 1_000L

        val CARD_LIST_SERIALIZER = ListSerializer(CachedWidgetCardPayloadV1.serializer())

        fun encodeCardsJson(cards: List<CachedWidgetCardPayloadV1>): String = PluginJsonV1.encodeToString(CARD_LIST_SERIALIZER, cards)

        fun decodeCardsJson(cardsJson: String): List<CachedWidgetCardPayloadV1>? =
            runCatching {
                PluginJsonV1.decodeFromString(CARD_LIST_SERIALIZER, cardsJson)
            }.getOrNull()
    }
}

private fun CachedWidgetCardPayloadV1.containsRefreshAction(): Boolean {
    if (cardAction == UiWidgetAction.Refresh) return true
    if (actions.any { it.action == UiWidgetAction.Refresh }) return true
    return body.any(CachedWidgetNodeV1::containsRefreshAction)
}

private fun CachedWidgetNodeV1.containsRefreshAction(): Boolean =
    when (this) {
        is CachedWidgetNodeV1.Row -> children.any(CachedWidgetNodeV1::containsRefreshAction)
        is CachedWidgetNodeV1.Column -> children.any(CachedWidgetNodeV1::containsRefreshAction)
        is CachedWidgetNodeV1.Collapsible -> children.any(CachedWidgetNodeV1::containsRefreshAction)
        is CachedWidgetNodeV1.KeyValue -> items.any { it.action == UiWidgetAction.Refresh }
        else -> false
    }
