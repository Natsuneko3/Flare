package dev.dimension.flare.feature.plugin.adapter

import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.repository.SessionChallengeException
import dev.dimension.flare.feature.plugin.abi.PluginAbiV1
import dev.dimension.flare.feature.plugin.host.PluginCallTimeoutV1
import dev.dimension.flare.feature.plugin.host.PluginCredentialAccess
import dev.dimension.flare.feature.plugin.host.PluginHttpTransport
import dev.dimension.flare.feature.plugin.host.PluginInvocationContextV1
import dev.dimension.flare.feature.plugin.host.PluginTransportRequestV1
import dev.dimension.flare.feature.plugin.host.PluginTransportResponseV1
import dev.dimension.flare.feature.plugin.lifecycle.InstalledPluginV1
import dev.dimension.flare.feature.plugin.lifecycle.RunningPluginV1
import dev.dimension.flare.feature.plugin.login.PluginSessionChallengeCoordinatorV1
import dev.dimension.flare.feature.plugin.manifest.CapabilityManifestV1
import dev.dimension.flare.feature.plugin.manifest.CapabilityOperationManifestV1
import dev.dimension.flare.feature.plugin.manifest.CookieProbeManifestV1
import dev.dimension.flare.feature.plugin.manifest.CookieRequirementManifestV1
import dev.dimension.flare.feature.plugin.manifest.DetectorManifestV1
import dev.dimension.flare.feature.plugin.manifest.LoginInteractionV1
import dev.dimension.flare.feature.plugin.manifest.LoginMethodManifestV1
import dev.dimension.flare.feature.plugin.manifest.PluginManifestV1
import dev.dimension.flare.feature.plugin.manifest.PluginPermissionsV1
import dev.dimension.flare.feature.plugin.manifest.PluginPlatformManifestV1
import dev.dimension.flare.feature.plugin.manifest.PluginTextV1
import dev.dimension.flare.feature.plugin.runtime.PluginCallException
import dev.dimension.flare.feature.plugin.runtime.PluginRuntimeKeyV1
import dev.dimension.flare.feature.plugin.runtime.PluginRuntimePool
import dev.dimension.flare.feature.plugin.runtime.PluginRuntimeUnavailableException
import dev.dimension.flare.feature.plugin.wire.PageDirectionV1
import dev.dimension.flare.feature.plugin.wire.PageV1
import dev.dimension.flare.feature.plugin.wire.PluginErrorCodeV1
import dev.dimension.flare.model.MicroBlogKey
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.SYSTEM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginInvocationV1Test {
    @Test
    fun skippedItemsKeepBothCursorsAndDoNotEndPagination() =
        runTest {
            val loader =
                pluginRemoteLoader(
                    directions = emptySet(),
                    load = { _, _ -> PageV1(items = listOf("unknown"), olderCursor = "older", newerCursor = "newer") },
                    map = { value -> value.takeUnless { it == "unknown" } },
                )
            val result = loader.load(20, PagingRequest.Refresh)
            assertEquals(emptyList(), result.data)
            assertEquals("older", result.nextKey)
            assertEquals("newer", result.previousKey)
        }

    @Test
    fun unsupportedPagingDirectionEndsWithoutCallingPlugin() =
        runTest {
            var calls = 0
            val loader =
                pluginRemoteLoader(
                    directions = setOf(PageDirectionV1.Refresh),
                    load = { _, _ ->
                        calls += 1
                        PageV1(items = listOf("unexpected"))
                    },
                    map = { it },
                )

            val result = loader.load(20, PagingRequest.Append("older"))

            assertEquals(0, calls)
            assertEquals(emptyList(), result.data)
            assertEquals(null, result.nextKey)
        }

    @Test
    fun nonAccountScopesAndUnnegotiatedAccountsDegradeChallengeRequiredWithoutTickets() =
        runBlocking {
            val errorBody =
                """throw flare.error({
                    code: "ChallengeRequired",
                    message: { value: "Solve captcha" },
                    challenge: { url: "https://account.example/challenge" }
                });"""
            val plugin = challengePlugin(pageBody = errorBody)
            val pool = PluginRuntimePool(FileSystem.SYSTEM, UnusedTransport)
            val coordinator = PluginSessionChallengeCoordinatorV1(pool)
            try {
                // Guest scope degrades to PluginCallException with zero tickets
                val guestInvoker =
                    PluginAccountInvokerV1(
                        plugin = plugin,
                        runtimePool = pool,
                        runtimeKey = PluginRuntimeKeyV1.guest(PLUGIN_ID, HASH, ORIGIN),
                        accountKey = null,
                        sessionChallenge = coordinator,
                        sessionChallengeEnabled = false,
                    ) { _, _ ->
                        PluginInvocationContextV1.guest(PLUGIN_ID, PLATFORM_ID, HASH, ORIGIN, "en")
                    }
                val guestError =
                    assertFailsWith<PluginCallException> {
                        guestInvoker.invoke(
                            PluginAbiV1.Capabilities.TIMELINE,
                            "page",
                            JsonObject(emptyMap()),
                            JsonObject.serializer(),
                            JsonObject.serializer(),
                        )
                    }
                assertEquals(PluginErrorCodeV1.ChallengeRequired, guestError.error.code)

                // Detector scope degrades to PluginCallException
                val detectorError =
                    assertFailsWith<PluginCallException> {
                        pool.invoke(
                            plugin = plugin,
                            key = PluginRuntimeKeyV1.detector(PLUGIN_ID, HASH, "detect-1"),
                            context = PluginInvocationContextV1.detector(PLUGIN_ID, PLATFORM_ID, HASH, ORIGIN, "en"),
                            method = "detector.detect",
                            request = JsonObject(emptyMap()),
                            requestSerializer = JsonObject.serializer(),
                            responseSerializer = JsonObject.serializer(),
                        )
                    }
                assertEquals(PluginErrorCodeV1.ChallengeRequired, detectorError.error.code)

                // Login scope degrades to PluginCallException
                val loginError =
                    assertFailsWith<PluginCallException> {
                        pool.invoke(
                            plugin = plugin,
                            key = PluginRuntimeKeyV1.login(PLUGIN_ID, HASH, "flow-1"),
                            context = PluginInvocationContextV1.login(PLUGIN_ID, PLATFORM_ID, HASH, ORIGIN, emptySet(), "en"),
                            method = "login.oauth.begin",
                            request = JsonObject(emptyMap()),
                            requestSerializer = JsonObject.serializer(),
                            responseSerializer = JsonObject.serializer(),
                        )
                    }
                assertEquals(PluginErrorCodeV1.ChallengeRequired, loginError.error.code)

                // Account scope without negotiated SESSION_CHALLENGE degrades to PluginCallException
                val unnegotiatedInvoker =
                    PluginAccountInvokerV1(
                        plugin = plugin,
                        runtimePool = pool,
                        runtimeKey = PluginRuntimeKeyV1.account(PLUGIN_ID, HASH, ORIGIN, "me", "epoch-1"),
                        accountKey = ACCOUNT_KEY,
                        sessionChallenge = coordinator,
                        sessionChallengeEnabled = false,
                        credentialAccess = InMemoryCredential(),
                    ) { _, _ ->
                        PluginInvocationContextV1.account(
                            PLUGIN_ID,
                            PLATFORM_ID,
                            HASH,
                            ORIGIN,
                            "me",
                            "en",
                            InMemoryCredential(),
                        )
                    }
                val accountError =
                    assertFailsWith<PluginCallException> {
                        unnegotiatedInvoker.invoke(
                            PluginAbiV1.Capabilities.TIMELINE,
                            "page",
                            JsonObject(emptyMap()),
                            JsonObject.serializer(),
                            JsonObject.serializer(),
                        )
                    }
                assertEquals(PluginErrorCodeV1.ChallengeRequired, accountError.error.code)
                assertEquals(0, coordinator.activeTicketCount())
            } finally {
                pool.close()
            }
        }

    @Test
    fun invalidChallengeUrlsOrUnexpectedChallengeMetadataRetireRuntime() =
        runBlocking {
            val invalidErrors =
                listOf(
                    """throw flare.error({ code: "NotFound", message: { value: "Bad" }, challenge: { url: "https://account.example/c" } });""",
                    """throw flare.error({ code: "ChallengeRequired", message: { value: "Missing" } });""",
                    """throw flare.error({ code: "ChallengeRequired", message: { value: "Http" }, challenge: { url: "http://account.example/c" } });""",
                    """throw flare.error({ code: "ChallengeRequired", message: { value: "Userinfo" }, challenge: { url: "https://user:pass@account.example/c" } });""",
                    """throw flare.error({ code: "ChallengeRequired", message: { value: "Fragment" }, challenge: { url: "https://account.example/c#frag" } });""",
                    """throw flare.error({ code: "ChallengeRequired", message: { value: "TrailingHash" }, challenge: { url: "https://account.example/c#" } });""",
                    """throw flare.error({ code: "ChallengeRequired", message: { value: "Evil" }, challenge: { url: "https://evil.example/c" } });""",
                    """throw flare.error({ code: "ChallengeRequired", message: { value: "HttpOriginOnly" }, challenge: { url: "https://http-only.example/c" } });""",
                )
            for (body in invalidErrors) {
                val plugin = challengePlugin(pageBody = body)
                val pool = PluginRuntimePool(FileSystem.SYSTEM, UnusedTransport)
                val coordinator = PluginSessionChallengeCoordinatorV1(pool)
                val credential = InMemoryCredential()
                val runtimeKey = PluginRuntimeKeyV1.account(PLUGIN_ID, HASH, ORIGIN, "me", "epoch-1")
                val invoker =
                    PluginAccountInvokerV1(
                        plugin = plugin,
                        runtimePool = pool,
                        runtimeKey = runtimeKey,
                        accountKey = ACCOUNT_KEY,
                        sessionChallenge = coordinator,
                        sessionChallengeEnabled = true,
                        credentialAccess = credential,
                    ) { _, _ ->
                        PluginInvocationContextV1.account(PLUGIN_ID, PLATFORM_ID, HASH, ORIGIN, "me", "en", credential)
                    }
                try {
                    assertFailsWith<PluginRuntimeUnavailableException>("Expected retirement for $body") {
                        invoker.invoke(
                            PluginAbiV1.Capabilities.TIMELINE,
                            "page",
                            JsonObject(emptyMap()),
                            JsonObject.serializer(),
                            JsonObject.serializer(),
                        )
                    }
                    assertTrue(pool.holderKeys().isEmpty(), "Runtime holder must be retired for $body")
                    assertEquals(0, coordinator.activeTicketCount())
                } finally {
                    pool.close()
                }
            }
        }

    @Test
    fun invalidSeedCookiesAreRejectedAndRetireRuntime() =
        runBlocking {
            val tooMany = (1..33).joinToString(",") { """{ name: "c$it", value: "v$it" }""" }
            val oversizedSingle = """[{ name: "big", value: "${"a".repeat(16 * 1_024 + 1)}" }]"""
            val fiveTimesFifteenK = (1..5).joinToString(",") { """{ name: "k$it", value: "${"b".repeat(15 * 1_024)}" }""" }
            val invalidCookieLists =
                listOf(
                    "[$tooMany]",
                    """[{ name: "bad name", value: "ok" }]""",
                    """[{ name: "ok", value: "bad;value" }]""",
                    """[{ name: "ok", value: "bad\nvalue" }]""",
                    """[{ name: "ok", value: "bad\"value" }]""",
                    oversizedSingle,
                    "[$fiveTimesFifteenK]",
                )
            for (cookiesJs in invalidCookieLists) {
                val plugin =
                    challengePlugin(
                        pageBody =
                            """throw flare.error({
                                code: "ChallengeRequired",
                                message: { value: "Bad cookies" },
                                challenge: { url: "https://account.example/challenge", seedCookies: $cookiesJs }
                            });""",
                    )
                val pool = PluginRuntimePool(FileSystem.SYSTEM, UnusedTransport)
                val coordinator = PluginSessionChallengeCoordinatorV1(pool)
                val credential = InMemoryCredential()
                val invoker =
                    PluginAccountInvokerV1(
                        plugin = plugin,
                        runtimePool = pool,
                        runtimeKey = PluginRuntimeKeyV1.account(PLUGIN_ID, HASH, ORIGIN, "me", "epoch-1"),
                        accountKey = ACCOUNT_KEY,
                        sessionChallenge = coordinator,
                        sessionChallengeEnabled = true,
                        credentialAccess = credential,
                    ) { _, _ ->
                        PluginInvocationContextV1.account(PLUGIN_ID, PLATFORM_ID, HASH, ORIGIN, "me", "en", credential)
                    }
                try {
                    assertFailsWith<PluginRuntimeUnavailableException>("Expected invalid seedCookies rejection for $cookiesJs") {
                        invoker.invoke(
                            PluginAbiV1.Capabilities.TIMELINE,
                            "page",
                            JsonObject(emptyMap()),
                            JsonObject.serializer(),
                            JsonObject.serializer(),
                        )
                    }
                    assertTrue(pool.holderKeys().isEmpty())
                    assertEquals(0, coordinator.activeTicketCount())
                } finally {
                    pool.close()
                }
            }
        }

    @Test
    fun validChallengeCreatesTicketAndExceptionWithoutLeakingCookiePlaintext() =
        runBlocking {
            val secretCookieName = "cf_secret_cookie"
            val secretCookieValue = "super-sensitive-token-value-12345"
            val plugin =
                challengePlugin(
                    pageBody =
                        """throw flare.error({
                            code: "ChallengeRequired",
                            message: { value: "Verify browser" },
                            challenge: {
                              url: "https://auth.example/challenge?step=1",
                              seedCookies: [{ name: "$secretCookieName", value: "$secretCookieValue" }]
                            }
                        });""",
                )
            val pool = PluginRuntimePool(FileSystem.SYSTEM, UnusedTransport)
            val coordinator = PluginSessionChallengeCoordinatorV1(pool)
            val credential = InMemoryCredential()
            val invoker =
                PluginAccountInvokerV1(
                    plugin = plugin,
                    runtimePool = pool,
                    runtimeKey = PluginRuntimeKeyV1.account(PLUGIN_ID, HASH, ORIGIN, "me", "epoch-1"),
                    accountKey = ACCOUNT_KEY,
                    sessionChallenge = coordinator,
                    sessionChallengeEnabled = true,
                    credentialAccess = credential,
                ) { _, _ ->
                    PluginInvocationContextV1.account(PLUGIN_ID, PLATFORM_ID, HASH, ORIGIN, "me", "en", credential)
                }
            try {
                val exception =
                    assertFailsWith<SessionChallengeException> {
                        invoker.invoke(
                            PluginAbiV1.Capabilities.TIMELINE,
                            "page",
                            JsonObject(emptyMap()),
                            JsonObject.serializer(),
                            JsonObject.serializer(),
                        )
                    }
                assertEquals(ACCOUNT_KEY, exception.accountKey)
                assertEquals(PLATFORM_ID, exception.platformId)
                assertEquals("https://auth.example/challenge?step=1", exception.challengeUrl)
                assertEquals("Verify browser", exception.displayMessage)
                assertFalse(exception.toString().contains(secretCookieValue))
                assertFalse(exception.toString().contains(secretCookieName))
                assertFalse(exception.challengeId.contains(secretCookieValue))
                assertFalse(exception.challengeUrl.contains(secretCookieValue))

                val handle = assertNotNull(coordinator.beginSession(exception.challengeId))
                assertEquals(secretCookieName, handle.seedCookies.single().name)
                assertEquals(secretCookieValue, handle.seedCookies.single().value)
                handle.close()
                assertNull(coordinator.beginSession(exception.challengeId))
            } finally {
                pool.close()
            }
        }

    private fun challengePlugin(pageBody: String): RunningPluginV1 {
        val manifest =
            PluginManifestV1(
                apiVersion = 2,
                id = PLUGIN_ID,
                version = "1.0.0",
                name = PluginTextV1.Literal("Challenge Test"),
                permissions =
                    PluginPermissionsV1(
                        authOrigins = setOf("https://auth.example"),
                        httpOrigins = setOf("https://http-only.example"),
                    ),
                platform =
                    PluginPlatformManifestV1(
                        id = PLATFORM_ID,
                        name = PluginTextV1.Literal("Challenge Test"),
                        detector = DetectorManifestV1(),
                        loginMethods =
                            listOf(
                                LoginMethodManifestV1(
                                    id = "oauth",
                                    interaction = LoginInteractionV1.OAuth,
                                    title = PluginTextV1.Literal("OAuth"),
                                ),
                            ),
                        capabilities =
                            mapOf(
                                PluginAbiV1.Capabilities.TIMELINE to
                                    CapabilityManifestV1(mapOf("page" to CapabilityOperationManifestV1())),
                                PluginAbiV1.Capabilities.SESSION_CHALLENGE to
                                    CapabilityManifestV1(
                                        operations = mapOf("complete" to CapabilityOperationManifestV1()),
                                        challengeProbes =
                                            listOf(
                                                CookieProbeManifestV1(
                                                    url = "${PluginAbiV1.ACCOUNT_ORIGIN}/gallery",
                                                    cookies = listOf(CookieRequirementManifestV1("cf_clearance")),
                                                ),
                                            ),
                                    ),
                            ),
                    ),
            )
        val script =
            """
            defineFlareSocialPlugin({
              detector: { async detect() { $pageBody } },
              login: {
                oauth: {
                  async begin() { $pageBody },
                  async resume() { return { type: "Pending" }; },
                },
              },
              capabilities: {
                timeline: { async page() { $pageBody } },
                sessionChallenge: { async complete() { return { type: "completed" }; } },
              },
            });
            """.trimIndent()
        return RunningPluginV1(
            installed =
                InstalledPluginV1(
                    pluginId = PLUGIN_ID,
                    version = "1.0.0",
                    packageHash = HASH,
                    packageSize = 0,
                    iconSize = 0,
                    manifest = manifest,
                ),
            packagePath = "unused.fpp",
            iconPath = "unused.png",
            developmentSource = script,
        )
    }

    private class InMemoryCredential : PluginCredentialAccess {
        override suspend fun read(): JsonElement = JsonObject(emptyMap())

        override suspend fun replace(value: JsonElement) {}
    }

    private object UnusedTransport : PluginHttpTransport {
        override suspend fun execute(request: PluginTransportRequestV1): PluginTransportResponseV1 = error("Transport should not be called")
    }

    private companion object {
        const val PLUGIN_ID = "dev.dimension.flare.test.challenge"
        const val PLATFORM_ID = "ChallengePlatform"
        const val ORIGIN = "https://account.example"
        val HASH = "a".repeat(64)
        val ACCOUNT_KEY = MicroBlogKey("me", "account.example")
    }
}
