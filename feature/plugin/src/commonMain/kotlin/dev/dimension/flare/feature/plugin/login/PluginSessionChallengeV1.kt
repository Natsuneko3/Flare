package dev.dimension.flare.feature.plugin.login

import dev.dimension.flare.common.Locale
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.repository.RequireReLoginException
import dev.dimension.flare.data.repository.SessionChallengeCheckResult
import dev.dimension.flare.data.repository.SessionChallengeCookieAttributes
import dev.dimension.flare.data.repository.SessionChallengeCookieName
import dev.dimension.flare.data.repository.SessionChallengeCookieProbe
import dev.dimension.flare.data.repository.SessionChallengeCookieRequirement
import dev.dimension.flare.data.repository.SessionChallengeCookieSnapshot
import dev.dimension.flare.data.repository.SessionChallengeCookieValue
import dev.dimension.flare.data.repository.SessionChallengeCoordinator
import dev.dimension.flare.data.repository.SessionChallengeException
import dev.dimension.flare.data.repository.SessionChallengeHandle
import dev.dimension.flare.data.repository.SessionChallengeSeedCookie
import dev.dimension.flare.feature.plugin.abi.PluginAbiV1
import dev.dimension.flare.feature.plugin.host.PluginCallTimeoutV1
import dev.dimension.flare.feature.plugin.host.PluginCredentialAccess
import dev.dimension.flare.feature.plugin.host.PluginInvocationContextV1
import dev.dimension.flare.feature.plugin.host.PluginInvocationScopeV1
import dev.dimension.flare.feature.plugin.host.PluginUrlPolicy
import dev.dimension.flare.feature.plugin.lifecycle.RunningPluginV1
import dev.dimension.flare.feature.plugin.manifest.normalizePluginInvocationLocale
import dev.dimension.flare.feature.plugin.manifest.socialPlatform
import dev.dimension.flare.feature.plugin.runtime.PluginCallException
import dev.dimension.flare.feature.plugin.runtime.PluginRuntimeKeyV1
import dev.dimension.flare.feature.plugin.runtime.PluginRuntimePool
import dev.dimension.flare.feature.plugin.wire.CookieAttributesV2
import dev.dimension.flare.feature.plugin.wire.CookieSnapshotV1
import dev.dimension.flare.feature.plugin.wire.CookieValueV1
import dev.dimension.flare.feature.plugin.wire.PluginChallengeV1
import dev.dimension.flare.feature.plugin.wire.PluginErrorCodeV1
import dev.dimension.flare.feature.plugin.wire.SessionChallengeCompleteRequestV1
import dev.dimension.flare.feature.plugin.wire.SessionChallengeResultV1
import dev.dimension.flare.feature.plugin.wire.normalizeUserAgent
import dev.dimension.flare.feature.plugin.wire.requireValid
import dev.dimension.flare.model.MicroBlogKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.native.HiddenFromObjC
import kotlin.time.Clock

@HiddenFromObjC
public class PluginSessionChallengeCoordinatorV1(
    private val runtimePool: PluginRuntimePool,
    private val runningPlugin: ((pluginId: String) -> RunningPluginV1?)? = null,
    private val entropy: PluginLoginEntropyV1 = PlatformPluginLoginEntropyV1,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val maxTickets: Int = MAX_CHALLENGE_TICKETS,
    private val ttlMillis: Long = CHALLENGE_TTL_MILLIS,
) : SessionChallengeCoordinator {
    private val tickets = MutableStateFlow(SessionChallengeTicketBook())

    init {
        require(maxTickets > 0) { "Invalid maxTickets" }
        require(ttlMillis > 0) { "Invalid ttlMillis" }
    }

    internal fun activeTicketCount(): Int {
        pruneExpired()
        return tickets.value.entries.size
    }

    internal fun createTicket(
        plugin: RunningPluginV1,
        runtimeKey: PluginRuntimeKeyV1,
        accountKey: MicroBlogKey,
        accountOrigin: String,
        accountEpoch: String?,
        credentialAccess: PluginCredentialAccess,
        challenge: PluginChallengeV1,
        displayMessage: String,
        contextProvider: suspend (locale: String) -> PluginInvocationContextV1,
    ): SessionChallengeException {
        plugin.session.ensureActive()
        require(isPluginCurrent(plugin)) { "Plugin runtime is no longer current" }
        require(plugin.installed.manifest.apiVersion >= 2) { "Session challenge requires API v2" }
        require(runtimeKey.scope == PluginInvocationScopeV1.Account) { "Session challenge requires account scope" }
        val platform = plugin.installed.manifest.socialPlatform
        val capability = platform.capabilities[PluginAbiV1.Capabilities.SESSION_CHALLENGE]
        require(capability != null && "complete" in capability.operations) {
            "Session challenge capability is not declared"
        }
        challenge.requireValid()
        val canonicalOrigin = PluginUrlPolicy.requireOrigin(accountOrigin)
        val approvedChallengeOrigins = setOf(canonicalOrigin) + plugin.installed.manifest.permissions.authOrigins
        val canonicalChallengeUrl = PluginUrlPolicy.requireRequestUrl(challenge.url, approvedChallengeOrigins).toString()
        val probes =
            capability.challengeProbes.map { probe ->
                SessionChallengeCookieProbe(
                    url = resolveManifestUrl(probe.url, canonicalOrigin, approvedChallengeOrigins),
                    cookies = probe.cookies.map { SessionChallengeCookieRequirement(it.name, it.required) },
                )
            }
        require(probes.isNotEmpty()) { "Session challenge requires challengeProbes" }
        val seedCookies = challenge.seedCookies.map { SessionChallengeSeedCookie(it.name, it.value) }
        val createdAt = nowEpochMillis()
        val challengeId = allocateChallengeId()
        val closed = MutableStateFlow(false)
        lateinit var ticket: SessionChallengeTicket
        val removeCleanup =
            plugin.session.onRetire {
                invalidateTicket(challengeId)
            }
        ticket =
            SessionChallengeTicket(
                challengeId = challengeId,
                plugin = plugin,
                runtimeKey = runtimeKey,
                accountKey = accountKey,
                platformId = platform.id,
                accountOrigin = canonicalOrigin,
                accountEpoch = accountEpoch,
                credentialAccess = credentialAccess,
                challengeUrl = canonicalChallengeUrl,
                seedCookies = seedCookies,
                probes = probes,
                createdAtEpochMillis = createdAt,
                closed = closed,
                removeRetireCleanup = removeCleanup,
                contextProvider = contextProvider,
            )

        val removed = mutableListOf<SessionChallengeTicket>()
        while (true) {
            removed.clear()
            val before = tickets.value
            val next = LinkedHashMap<String, SessionChallengeTicket>(before.entries.size + 1)
            for ((id, entry) in before.entries) {
                val expired = createdAt - entry.createdAtEpochMillis !in 0L..ttlMillis
                val staleEpoch =
                    entry.plugin.installed.pluginId == plugin.installed.pluginId &&
                        entry.accountKey == accountKey &&
                        entry.accountEpoch != accountEpoch
                if (expired || staleEpoch || entry.closed.value) {
                    removed += entry
                } else {
                    next[id] = entry
                }
            }
            while (next.size >= maxTickets) {
                val iterator = next.entries.iterator()
                val eldest = iterator.next().value
                iterator.remove()
                removed += eldest
            }
            next[challengeId] = ticket
            if (tickets.compareAndSet(before, SessionChallengeTicketBook(next))) break
        }
        removed.forEach { it.destroy() }
        if (!plugin.session.isActive || !isPluginCurrent(plugin)) {
            invalidateTicket(challengeId)
            plugin.session.ensureActive()
            error("Plugin runtime is no longer current")
        }
        return SessionChallengeException(
            challengeId = challengeId,
            accountKey = accountKey,
            platformId = platform.id,
            challengeUrl = canonicalChallengeUrl,
            displayMessage = displayMessage,
        )
    }

    override suspend fun beginSession(challengeId: String): SessionChallengeHandle? {
        pruneExpired()
        val candidate = tickets.value.entries[challengeId] ?: return null
        if (candidate.closed.value || isExpired(candidate) || !candidate.plugin.session.isActive || !isPluginCurrent(candidate.plugin)) {
            invalidateTicket(challengeId)
            return null
        }
        try {
            candidate.credentialAccess.ensureActive()
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            invalidateTicket(challengeId)
            return null
        } catch (_: RequireReLoginException) {
            invalidateTicket(challengeId)
            return null
        }
        val touched = touchTicket(candidate) ?: return null
        return SessionHandle(touched)
    }

    override fun invalidateTicket(challengeId: String) {
        removeTickets { _, entry -> entry.challengeId == challengeId }
    }

    override fun invalidateAccountChallenges(accountKey: MicroBlogKey) {
        removeTickets { _, entry -> entry.accountKey == accountKey }
    }

    override fun invalidateSiblingTickets(
        accountKey: MicroBlogKey,
        excludeChallengeId: String,
    ) {
        removeTickets { id, entry -> entry.accountKey == accountKey && id != excludeChallengeId }
    }

    internal fun invalidatePlugin(pluginId: String) {
        removeTickets { _, entry -> entry.plugin.installed.pluginId == pluginId }
    }

    internal fun onAccountSession(
        accountKey: MicroBlogKey,
        activeEpoch: String?,
    ) {
        removeTickets { _, entry -> entry.accountKey == accountKey && entry.accountEpoch != activeEpoch }
    }

    private fun allocateChallengeId(): String {
        val existing = tickets.value.entries
        repeat(8) {
            val candidate = entropy.newState()
            if (candidate.isNotBlank() && candidate !in existing) return candidate
        }
        return "${entropy.newState()}-${entropy.newFlowId()}"
    }

    private fun isPluginCurrent(plugin: RunningPluginV1): Boolean {
        val lookup = runningPlugin ?: return true
        val active = lookup(plugin.installed.pluginId) ?: return false
        return active === plugin
    }

    private fun isExpired(ticket: SessionChallengeTicket): Boolean = nowEpochMillis() - ticket.createdAtEpochMillis !in 0L..ttlMillis

    private fun pruneExpired() {
        val now = nowEpochMillis()
        removeTickets { _, entry -> now - entry.createdAtEpochMillis !in 0L..ttlMillis || entry.closed.value }
    }

    private fun touchTicket(ticket: SessionChallengeTicket): SessionChallengeTicket? {
        while (true) {
            val before = tickets.value
            val current = before.entries[ticket.challengeId] ?: return null
            if (current !== ticket || current.closed.value || isExpired(current)) return null
            val next = LinkedHashMap<String, SessionChallengeTicket>(before.entries.size)
            for ((id, entry) in before.entries) {
                if (id != ticket.challengeId) next[id] = entry
            }
            next[ticket.challengeId] = current
            if (tickets.compareAndSet(before, SessionChallengeTicketBook(next))) return current
        }
    }

    private fun removeTickets(predicate: (String, SessionChallengeTicket) -> Boolean) {
        val removed = mutableListOf<SessionChallengeTicket>()
        while (true) {
            removed.clear()
            val before = tickets.value
            if (before.entries.isEmpty()) return
            var changed = false
            val next = LinkedHashMap<String, SessionChallengeTicket>(before.entries.size)
            for ((id, entry) in before.entries) {
                if (predicate(id, entry)) {
                    removed += entry
                    changed = true
                } else {
                    next[id] = entry
                }
            }
            if (!changed) return
            if (tickets.compareAndSet(before, SessionChallengeTicketBook(next))) break
        }
        removed.forEach { it.destroy() }
    }

    private inner class SessionHandle(
        private val ticket: SessionChallengeTicket,
    ) : SessionChallengeHandle {
        override val challengeId: String get() = ticket.challengeId
        override val accountKey: MicroBlogKey get() = ticket.accountKey
        override val platformId: String get() = ticket.platformId
        override val proxyRef: ProxyRef get() = ProxyRef.forAccount(ticket.accountKey)
        override val challengeUrl: String get() = ticket.challengeUrl
        override val seedCookies: List<SessionChallengeSeedCookie> get() = ticket.seedCookies
        override val probes: List<SessionChallengeCookieProbe> get() = ticket.probes

        override suspend fun check(snapshot: SessionChallengeCookieSnapshot): SessionChallengeCheckResult {
            if (!ticket.checkMutex.tryLock()) return SessionChallengeCheckResult.Busy
            try {
                requireActiveTicket()
                val wireSnapshot = snapshot.toWire().also { it.requireValid() }
                val filtered = filter(wireSnapshot)
                val present = filtered.cookies.associateBy { it.sourceUrl to it.name }
                val missing =
                    ticket.probes.flatMap { probe ->
                        probe.cookies
                            .filter { it.required && (probe.url to it.name) !in present }
                            .map { SessionChallengeCookieName(probe.url, it.name) }
                    }
                if (missing.isNotEmpty()) return SessionChallengeCheckResult.AwaitingCookies(missing)

                val request =
                    SessionChallengeCompleteRequestV1(
                        challengeUrl = ticket.challengeUrl,
                        cookies = filtered.copy(userAgent = null),
                        userAgent = filtered.userAgent,
                        pendingPayload = ticket.pendingPayload,
                    ).apply { requireValid() }

                val result =
                    try {
                        ticket.plugin.session.invoke("capabilities.sessionChallenge.complete") {
                            val invocationContext = ticket.contextProvider(normalizePluginInvocationLocale(Locale.language))
                            try {
                                runtimePool
                                    .invoke(
                                        plugin = ticket.plugin,
                                        key = ticket.runtimeKey,
                                        context = invocationContext,
                                        method = "capabilities.sessionChallenge.complete",
                                        request = request,
                                        requestSerializer = SessionChallengeCompleteRequestV1.serializer(),
                                        responseSerializer = SessionChallengeResultV1.serializer(),
                                        timeout = PluginCallTimeoutV1.Normal,
                                        validate = SessionChallengeResultV1::requireValid,
                                    ).also {
                                        ticket.credentialAccess.ensureActive()
                                    }
                            } catch (error: PluginCallException) {
                                ticket.credentialAccess.ensureActive()
                                if (error.error.code == PluginErrorCodeV1.AuthenticationRequired) {
                                    invalidateAccountChallenges(ticket.accountKey)
                                    throw RequireReLoginException(ticket.accountKey, ticket.platformId)
                                }
                                throw error
                            }
                        }
                    } catch (error: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        invalidateTicket(ticket.challengeId)
                        throw error
                    }

                if (ticket.closed.value || tickets.value.entries[ticket.challengeId] !== ticket) {
                    throw CancellationException("Session challenge was closed")
                }
                requireActiveTicket()
                return when (result) {
                    is SessionChallengeResultV1.Pending -> {
                        ticket.pendingPayload = result.pendingPayload?.takeUnless { it is JsonNull }
                        SessionChallengeCheckResult.Pending
                    }

                    SessionChallengeResultV1.Completed -> {
                        ticket.closed.value = true
                        ticket.pendingPayload = null
                        invalidateAccountChallenges(ticket.accountKey)
                        SessionChallengeCheckResult.Completed
                    }
                }
            } finally {
                ticket.checkMutex.unlock()
            }
        }

        override fun cancel() {
            invalidateTicket(ticket.challengeId)
        }

        override fun close() {
            invalidateTicket(ticket.challengeId)
        }

        private suspend fun requireActiveTicket() {
            check(!ticket.closed.value && tickets.value.entries[ticket.challengeId] === ticket) {
                "Session challenge is closed"
            }
            if (isExpired(ticket)) {
                invalidateTicket(ticket.challengeId)
                throw IllegalStateException("Session challenge has expired")
            }
            if (!isPluginCurrent(ticket.plugin)) {
                invalidatePlugin(ticket.plugin.installed.pluginId)
                throw CancellationException("Plugin runtime is no longer current")
            }
            ticket.plugin.session.ensureActive()
            try {
                ticket.credentialAccess.ensureActive()
            } catch (error: CancellationException) {
                currentCoroutineContext().ensureActive()
                invalidateTicket(ticket.challengeId)
                throw error
            } catch (error: RequireReLoginException) {
                invalidateTicket(ticket.challengeId)
                throw error
            }
        }

        private fun filter(snapshot: CookieSnapshotV1): CookieSnapshotV1 {
            val approvedChallengeOrigins =
                setOf(ticket.accountOrigin) + ticket.plugin.installed.manifest.permissions.authOrigins
            val allowed =
                ticket.probes.groupBy { it.url }.mapValues { (_, items) ->
                    items.flatMap { it.cookies }.mapTo(linkedSetOf()) { it.name }
                }
            val cookies =
                snapshot.cookies.mapNotNull { cookie ->
                    val canonicalUrl =
                        runCatching {
                            PluginUrlPolicy.requireRequestUrl(cookie.sourceUrl, approvedChallengeOrigins).toString()
                        }.getOrNull() ?: return@mapNotNull null
                    if (cookie.name !in allowed[canonicalUrl].orEmpty()) {
                        null
                    } else {
                        cookie.copy(sourceUrl = canonicalUrl)
                    }
                }
            return CookieSnapshotV1(
                cookies = cookies.sortedWith(compareBy(CookieValueV1::sourceUrl, CookieValueV1::name)),
                userAgent = normalizeUserAgent(snapshot.userAgent),
            ).also {
                it.requireValid()
            }
        }
    }

    private companion object {
        const val MAX_CHALLENGE_TICKETS = 16
        const val CHALLENGE_TTL_MILLIS = 15 * 60_000L
    }
}

// Reference equality ensures MutableStateFlow compares SessionChallengeTicketBook identity rather than LinkedHashMap order-insensitive equals.
internal class SessionChallengeTicketBook(
    val entries: Map<String, SessionChallengeTicket> = emptyMap(),
)

internal class SessionChallengeTicket(
    val challengeId: String,
    val plugin: RunningPluginV1,
    val runtimeKey: PluginRuntimeKeyV1,
    val accountKey: MicroBlogKey,
    val platformId: String,
    val accountOrigin: String,
    val accountEpoch: String?,
    val credentialAccess: PluginCredentialAccess,
    val challengeUrl: String,
    val seedCookies: List<SessionChallengeSeedCookie>,
    val probes: List<SessionChallengeCookieProbe>,
    val createdAtEpochMillis: Long,
    val closed: MutableStateFlow<Boolean>,
    val removeRetireCleanup: () -> Unit,
    val contextProvider: suspend (locale: String) -> PluginInvocationContextV1,
) {
    val checkMutex = Mutex()
    var pendingPayload: JsonElement? = null

    fun destroy() {
        closed.value = true
        pendingPayload = null
        runCatching { removeRetireCleanup() }
    }
}

private fun SessionChallengeCookieSnapshot.toWire(): CookieSnapshotV1 =
    CookieSnapshotV1(
        cookies = cookies.map(SessionChallengeCookieValue::toWire),
        userAgent = userAgent,
    )

private fun SessionChallengeCookieValue.toWire(): CookieValueV1 =
    CookieValueV1(
        sourceUrl = sourceUrl,
        name = name,
        value = value,
        attributes = attributes?.toWire(),
    )

private fun SessionChallengeCookieAttributes.toWire(): CookieAttributesV2 =
    CookieAttributesV2(
        domain = domain,
        hostOnly = hostOnly,
        path = path,
        expiresAt = expiresAt,
        session = session,
        secure = secure,
        httpOnly = httpOnly,
        sameSite = sameSite,
    )
