package dev.dimension.flare.feature.plugin.adapter

import dev.dimension.flare.common.Locale
import dev.dimension.flare.data.datasource.microblog.paging.CacheableRemoteLoader
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.repository.RequireReLoginException
import dev.dimension.flare.data.repository.SessionChallengeException
import dev.dimension.flare.feature.plugin.abi.PluginAbiV1
import dev.dimension.flare.feature.plugin.host.PluginAsset
import dev.dimension.flare.feature.plugin.host.PluginCallTimeoutV1
import dev.dimension.flare.feature.plugin.host.PluginCredentialAccess
import dev.dimension.flare.feature.plugin.host.PluginInvocationContextV1
import dev.dimension.flare.feature.plugin.host.PluginInvocationScopeV1
import dev.dimension.flare.feature.plugin.host.PluginUrlPolicy
import dev.dimension.flare.feature.plugin.lifecycle.RunningPluginV1
import dev.dimension.flare.feature.plugin.login.PluginSessionChallengeCoordinatorV1
import dev.dimension.flare.feature.plugin.manifest.normalizePluginInvocationLocale
import dev.dimension.flare.feature.plugin.manifest.socialPlatform
import dev.dimension.flare.feature.plugin.runtime.PluginCallException
import dev.dimension.flare.feature.plugin.runtime.PluginRuntimeKeyV1
import dev.dimension.flare.feature.plugin.runtime.PluginRuntimePool
import dev.dimension.flare.feature.plugin.wire.PageDirectionV1
import dev.dimension.flare.feature.plugin.wire.PageRequestV1
import dev.dimension.flare.feature.plugin.wire.PageV1
import dev.dimension.flare.feature.plugin.wire.PluginErrorCodeV1
import dev.dimension.flare.feature.plugin.wire.requireValid
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiTimelineV2
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString.Companion.encodeUtf8

internal class PluginAccountInvokerV1(
    private val plugin: RunningPluginV1,
    private val runtimePool: PluginRuntimePool,
    private val runtimeKey: PluginRuntimeKeyV1,
    private val accountKey: MicroBlogKey?,
    private val sessionChallenge: PluginSessionChallengeCoordinatorV1? = null,
    private val sessionChallengeEnabled: Boolean = false,
    private val credentialAccess: PluginCredentialAccess? = null,
    private val context: suspend (locale: String, assets: Map<String, PluginAsset>) -> PluginInvocationContextV1,
) {
    suspend fun ensureActive() {
        plugin.session.ensureActive()
        context(normalizePluginInvocationLocale(Locale.language), emptyMap()).credential?.ensureActive()
    }

    suspend fun <T> withAuthorization(block: suspend () -> T): T =
        plugin.session.invoke("capabilities.accountHttp.authorize") {
            ensureActive()
            block().also { ensureActive() }
        }

    fun <Wire : Any> timelineLoader(
        capability: String,
        operation: String,
        identity: List<String>,
        directions: Set<PageDirectionV1>,
        load: suspend (pageSize: Int, request: PagingRequest) -> PageV1<Wire>,
        map: (Wire) -> UiTimelineV2?,
        collapseReplies: Boolean = capability != PluginAbiV1.Capabilities.NOTIFICATION,
    ): CacheableRemoteLoader<UiTimelineV2> {
        val loader = pluginRemoteLoader(directions, load, map)
        val key =
            JsonArray(
                (
                    listOf(
                        "plugin-timeline-v1",
                        runtimeKey.pluginId,
                        runtimeKey.packageHash,
                        runtimeKey.scope.name,
                        runtimeKey.identity,
                        capability,
                        operation,
                    ) +
                        identity
                ).map(::JsonPrimitive),
            )
        return object : CacheableRemoteLoader<UiTimelineV2>, RemoteLoader<UiTimelineV2> by loader {
            override val pagingKey =
                "plugin:" +
                    key
                        .toString()
                        .encodeUtf8()
                        .sha256()
                        .hex()
            override val supportPrepend = PageDirectionV1.Newer.isSupportedBy(directions)
            override val collapseReplyChains = collapseReplies
        }
    }

    suspend fun <Request, Response> invoke(
        capabilityId: String,
        operation: String,
        request: Request,
        requestSerializer: KSerializer<Request>,
        responseSerializer: KSerializer<Response>,
        timeout: PluginCallTimeoutV1 = PluginCallTimeoutV1.Normal,
        assets: Map<String, PluginAsset> = emptyMap(),
        workerLane: String? = null,
        maxWorkers: Int = 1,
        validate: (Response) -> Unit = {},
    ): Response {
        val method = requireNotNull(PluginAbiV1.capabilityMethod(capabilityId, operation))
        return plugin.session.invoke(method) {
            val invocationContext = context(normalizePluginInvocationLocale(Locale.language), assets)
            try {
                val result =
                    if (workerLane != null) {
                        runtimePool.invokeOnWorkerLane(
                            plugin = plugin,
                            key = runtimeKey,
                            context = invocationContext,
                            method = method,
                            request = request,
                            requestSerializer = requestSerializer,
                            responseSerializer = responseSerializer,
                            lane = workerLane,
                            maxWorkers = maxWorkers,
                            timeout = timeout,
                            validate = validate,
                        )
                    } else {
                        runtimePool.invoke(
                            plugin = plugin,
                            key = runtimeKey,
                            context = invocationContext,
                            method = method,
                            request = request,
                            requestSerializer = requestSerializer,
                            responseSerializer = responseSerializer,
                            timeout = timeout,
                            validate = validate,
                        )
                    }
                result.also {
                    invocationContext.credential?.ensureActive()
                }
            } catch (error: PluginCallException) {
                invocationContext.credential?.ensureActive()
                if (error.error.code == PluginErrorCodeV1.AuthenticationRequired && accountKey != null) {
                    throw RequireReLoginException(accountKey, plugin.installed.manifest.socialPlatform.id)
                }
                if (error.error.code == PluginErrorCodeV1.ChallengeRequired) {
                    createChallengeException(error, invocationContext)?.let { throw it }
                }
                throw error
            }
        }
    }

    private fun createChallengeException(
        error: PluginCallException,
        invocationContext: PluginInvocationContextV1,
    ): SessionChallengeException? {
        if (accountKey == null ||
            runtimeKey.scope != PluginInvocationScopeV1.Account ||
            !sessionChallengeEnabled ||
            plugin.installed.manifest.apiVersion < 2 ||
            sessionChallenge == null
        ) {
            return null
        }
        val access = credentialAccess ?: invocationContext.credential ?: return null
        val challenge = requireNotNull(error.error.challenge) { "ChallengeRequired error requires challenge metadata" }
        challenge.requireValid()
        val approvedChallengeOrigins =
            setOf(invocationContext.metadata.origin) + plugin.installed.manifest.permissions.authOrigins
        PluginUrlPolicy.requireRequestUrl(challenge.url, approvedChallengeOrigins)
        return sessionChallenge.createTicket(
            plugin = plugin,
            runtimeKey = runtimeKey,
            accountKey = accountKey,
            accountOrigin = invocationContext.metadata.origin,
            accountEpoch = runtimeKey.accountEpoch,
            credentialAccess = access,
            challenge = challenge,
            displayMessage = error.message ?: "Verification required",
            contextProvider = { locale -> context(locale, emptyMap()) },
        )
    }
}

internal fun PagingRequest.toWire(
    pageSize: Int,
    parameters: Map<String, String> = emptyMap(),
): PageRequestV1 =
    PageRequestV1(
        direction =
            when (this) {
                PagingRequest.Refresh -> PageDirectionV1.Refresh
                is PagingRequest.Append -> PageDirectionV1.Older
                is PagingRequest.Prepend -> PageDirectionV1.Newer
            },
        limit = pageSize.coerceIn(1, dev.dimension.flare.feature.plugin.wire.WireLimitsV1.MAX_PAGE_SIZE),
        cursor =
            when (this) {
                PagingRequest.Refresh -> null
                is PagingRequest.Append -> nextKey
                is PagingRequest.Prepend -> previousKey
            },
        parameters = parameters,
    ).also { it.requireValid() }

internal fun PageDirectionV1.isSupportedBy(directions: Set<PageDirectionV1>): Boolean = directions.isEmpty() || this in directions

internal fun <Wire : Any, Ui : Any> pluginRemoteLoader(
    directions: Set<PageDirectionV1>,
    load: suspend (pageSize: Int, request: PagingRequest) -> PageV1<Wire>,
    map: (Wire) -> Ui?,
): RemoteLoader<Ui> =
    object : RemoteLoader<Ui> {
        override suspend fun load(
            pageSize: Int,
            request: PagingRequest,
        ): PagingResult<Ui> {
            val direction =
                when (request) {
                    PagingRequest.Refresh -> PageDirectionV1.Refresh
                    is PagingRequest.Append -> PageDirectionV1.Older
                    is PagingRequest.Prepend -> PageDirectionV1.Newer
                }
            if (!direction.isSupportedBy(directions)) return PagingResult(endOfPaginationReached = true)
            val page = load(pageSize, request).also { it.requireValid() }
            return PagingResult(
                data = page.items.mapNotNull(map),
                nextKey = page.olderCursor.takeUnless { page.endReached },
                previousKey = page.newerCursor,
            )
        }
    }
