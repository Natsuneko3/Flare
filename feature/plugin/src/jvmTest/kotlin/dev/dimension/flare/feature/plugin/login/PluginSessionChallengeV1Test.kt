package dev.dimension.flare.feature.plugin.login

import dev.dimension.flare.common.Locale
import dev.dimension.flare.data.io.AppFileStore
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.repository.RequireReLoginException
import dev.dimension.flare.data.repository.SessionChallengeCheckResult
import dev.dimension.flare.data.repository.SessionChallengeCookieAttributes
import dev.dimension.flare.data.repository.SessionChallengeCookieProbe
import dev.dimension.flare.data.repository.SessionChallengeCookieRequirement
import dev.dimension.flare.data.repository.SessionChallengeCookieSnapshot
import dev.dimension.flare.data.repository.SessionChallengeCookieValue
import dev.dimension.flare.data.repository.SessionChallengeCoordinator
import dev.dimension.flare.data.repository.SessionChallengeException
import dev.dimension.flare.feature.plugin.PluginKoinModule
import dev.dimension.flare.feature.plugin.PluginSubsystemV1
import dev.dimension.flare.feature.plugin.abi.PluginAbiV1
import dev.dimension.flare.feature.plugin.abi.PluginJsonV1
import dev.dimension.flare.feature.plugin.adapter.PlatformDataSourceCredentialAccessV1
import dev.dimension.flare.feature.plugin.adapter.PluginAccountCapabilitiesV1
import dev.dimension.flare.feature.plugin.adapter.PluginAccountInvokerV1
import dev.dimension.flare.feature.plugin.adapter.PluginDataSourceV1
import dev.dimension.flare.feature.plugin.adapter.PluginNamedTimelineDataSourceV1
import dev.dimension.flare.feature.plugin.adapter.accountCredential
import dev.dimension.flare.feature.plugin.adapter.accountInvocationContext
import dev.dimension.flare.feature.plugin.host.PluginCredentialAccess
import dev.dimension.flare.feature.plugin.host.PluginCredentialSnapshot
import dev.dimension.flare.feature.plugin.host.PluginHttpTransport
import dev.dimension.flare.feature.plugin.host.PluginInvocationContextV1
import dev.dimension.flare.feature.plugin.host.PluginTransportBodyV1
import dev.dimension.flare.feature.plugin.host.PluginTransportRequestV1
import dev.dimension.flare.feature.plugin.host.PluginTransportResponseV1
import dev.dimension.flare.feature.plugin.installer.PluginInstaller
import dev.dimension.flare.feature.plugin.installer.TestFppFactory
import dev.dimension.flare.feature.plugin.lifecycle.InstalledPluginV1
import dev.dimension.flare.feature.plugin.lifecycle.PluginRuntimeSwitcherV1
import dev.dimension.flare.feature.plugin.lifecycle.PluginStateStore
import dev.dimension.flare.feature.plugin.lifecycle.RunningPluginV1
import dev.dimension.flare.feature.plugin.management.PluginSessionChallengeFacadeV1
import dev.dimension.flare.feature.plugin.management.PluginTestAccounts
import dev.dimension.flare.feature.plugin.management.testPluginRegistry
import dev.dimension.flare.feature.plugin.manifest.CapabilityManifestV1
import dev.dimension.flare.feature.plugin.manifest.CapabilityOperationManifestV1
import dev.dimension.flare.feature.plugin.manifest.CookieProbeManifestV1
import dev.dimension.flare.feature.plugin.manifest.CookieRequirementManifestV1
import dev.dimension.flare.feature.plugin.manifest.PluginManifestV1
import dev.dimension.flare.feature.plugin.manifest.PluginPermissionsV1
import dev.dimension.flare.feature.plugin.manifest.PluginPlatformManifestV1
import dev.dimension.flare.feature.plugin.manifest.PluginTextV1
import dev.dimension.flare.feature.plugin.runtime.PluginCallException
import dev.dimension.flare.feature.plugin.runtime.PluginRuntimeKeyV1
import dev.dimension.flare.feature.plugin.runtime.PluginRuntimePool
import dev.dimension.flare.feature.plugin.wire.AccountPluginSnapshotV1
import dev.dimension.flare.feature.plugin.wire.EntityKeyV1
import dev.dimension.flare.feature.plugin.wire.HttpCookieV1
import dev.dimension.flare.feature.plugin.wire.LoginSuccessV1
import dev.dimension.flare.feature.plugin.wire.PluginAccountCredentialV1
import dev.dimension.flare.feature.plugin.wire.PluginChallengeV1
import dev.dimension.flare.feature.plugin.wire.PluginErrorCodeV1
import dev.dimension.flare.feature.plugin.wire.ProfileV1
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.PlatformDataSourceContext
import dev.dimension.flare.model.PlatformRegistry
import dev.dimension.flare.model.PlatformRuntimeData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginSessionChallengeV1Test {
    private val files = FileSystem.SYSTEM
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("plugin-session-challenge-test").toOkioPath()
    }

    @AfterTest
    fun tearDown() {
        runCatching { stopKoin() }
        files.deleteRecursively(root, mustExist = false)
    }

    @Test
    fun lruEvictionAndFifteenMinuteTtlEnforced() =
        runBlocking<Unit> {
            var now = 1_000_000L
            var counter = 0
            val pool = PluginRuntimePool(files, UnusedTransport)
            val coordinator =
                PluginSessionChallengeCoordinatorV1(
                    runtimePool = pool,
                    entropy = sequentialEntropy { "challenge-${++counter}" },
                    nowEpochMillis = { now },
                )
            val plugin = challengePlugin()
            val accountKey = MicroBlogKey("me", "account.example")
            val credential = MemoryVersionedCredential(JsonObject(emptyMap()))
            val runtimeKey = PluginRuntimeKeyV1.account(PLUGIN_ID, HASH, ORIGIN, "me", "epoch-1")
            try {
                val created =
                    (1..16).map { index ->
                        coordinator.createTicket(
                            plugin = plugin,
                            runtimeKey = runtimeKey,
                            accountKey = accountKey,
                            accountOrigin = ORIGIN,
                            accountEpoch = "epoch-1",
                            credentialAccess = credential,
                            challenge = PluginChallengeV1("$ORIGIN/c$index"),
                            displayMessage = "Challenge $index",
                            contextProvider = { locale ->
                                PluginInvocationContextV1.account(PLUGIN_ID, PLATFORM_ID, HASH, ORIGIN, "me", locale, credential)
                            },
                        )
                    }
                assertEquals(16, coordinator.activeTicketCount())
                // Touch ticket #1 via beginSession so ticket #2 becomes the LRU victim
                val firstHandle = assertNotNull(coordinator.beginSession(created[0].challengeId))
                val seventeenth =
                    coordinator.createTicket(
                        plugin = plugin,
                        runtimeKey = runtimeKey,
                        accountKey = accountKey,
                        accountOrigin = ORIGIN,
                        accountEpoch = "epoch-1",
                        credentialAccess = credential,
                        challenge = PluginChallengeV1("$ORIGIN/c17"),
                        displayMessage = "Challenge 17",
                        contextProvider = { locale ->
                            PluginInvocationContextV1.account(PLUGIN_ID, PLATFORM_ID, HASH, ORIGIN, "me", locale, credential)
                        },
                    )
                assertEquals(16, coordinator.activeTicketCount())
                assertNotNull(coordinator.beginSession(created[0].challengeId))
                assertNull(coordinator.beginSession(created[1].challengeId), "LRU ticket #2 should have been evicted")
                assertNotNull(coordinator.beginSession(seventeenth.challengeId))

                // Exactly 15 minutes is still valid; 15 minutes + 1 ms expires
                now = 1_000_000L + 15 * 60_000L
                assertNotNull(coordinator.beginSession(created[0].challengeId))
                now = 1_000_000L + 15 * 60_000L + 1L
                assertNull(coordinator.beginSession(created[0].challengeId))
                assertNull(coordinator.beginSession(seventeenth.challengeId))
                assertEquals(0, coordinator.activeTicketCount())
                assertFailsWith<IllegalStateException> {
                    firstHandle.check(SessionChallengeCookieSnapshot(emptyList(), null))
                }
            } finally {
                pool.close()
            }
        }

    @Test
    fun beginSessionExposesProxyAndProbesFiltersCookiesNormalizesUaAndGuardsReentrancy() =
        runBlocking<Unit> {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var capturedPayload: JsonObject? = null
            val transport =
                object : PluginHttpTransport {
                    override suspend fun execute(request: PluginTransportRequestV1): PluginTransportResponseV1 {
                        capturedPayload =
                            PluginJsonV1
                                .parseToJsonElement((request.body as PluginTransportBodyV1.Text).value)
                                .jsonObject
                        started.complete(Unit)
                        release.await()
                        return PluginTransportResponseV1(200, emptyMap(), "{}".encodeToByteArray())
                    }
                }
            val pool = PluginRuntimePool(files, transport)
            val coordinator = PluginSessionChallengeCoordinatorV1(pool)
            val plugin =
                challengePlugin(
                    completeBody =
                        """
                        await flare.http.request({ url: context.origin + "/capture", method: "POST", body: { type: "json", value: request } });
                        return { type: "completed" };
                        """.trimIndent(),
                )
            val accountKey = MicroBlogKey("me", "account.example")
            val credential = MemoryVersionedCredential(JsonObject(emptyMap()))
            val runtimeKey = PluginRuntimeKeyV1.account(PLUGIN_ID, HASH, ORIGIN, "me", "epoch-1")
            try {
                val exception =
                    coordinator.createTicket(
                        plugin = plugin,
                        runtimeKey = runtimeKey,
                        accountKey = accountKey,
                        accountOrigin = ORIGIN,
                        accountEpoch = "epoch-1",
                        credentialAccess = credential,
                        challenge = PluginChallengeV1("$ORIGIN/challenge", listOf(HttpCookieV1("seed_a", "val_a"))),
                        displayMessage = "Verify",
                        contextProvider = { locale ->
                            PluginInvocationContextV1.account(PLUGIN_ID, PLATFORM_ID, HASH, ORIGIN, "me", locale, credential)
                        },
                    )
                val handle = assertNotNull(coordinator.beginSession(exception.challengeId))
                assertEquals(accountKey, handle.accountKey)
                assertEquals(PLATFORM_ID, handle.platformId)
                assertEquals(ProxyRef.forAccount(accountKey), handle.proxyRef)
                assertEquals("$ORIGIN/challenge", handle.challengeUrl)
                assertEquals(listOf("seed_a" to "val_a"), handle.seedCookies.map { it.name to it.value })
                assertEquals(
                    listOf(
                        SessionChallengeCookieProbe(
                            url = "$ORIGIN/gallery",
                            cookies =
                                listOf(
                                    SessionChallengeCookieRequirement("cf_clearance", true),
                                    SessionChallengeCookieRequirement("optional_hint", false),
                                ),
                        ),
                        SessionChallengeCookieProbe(
                            url = "https://auth.example/session",
                            cookies = listOf(SessionChallengeCookieRequirement("igneous", true)),
                        ),
                    ),
                    handle.probes,
                )

                // Missing required cookie -> AwaitingCookies without invoking JS
                val awaiting =
                    assertIs<SessionChallengeCheckResult.AwaitingCookies>(
                        handle.check(
                            SessionChallengeCookieSnapshot(
                                cookies =
                                    listOf(
                                        SessionChallengeCookieValue("$ORIGIN/gallery", "cf_clearance", "ok"),
                                    ),
                                userAgent = null,
                            ),
                        ),
                    )
                assertEquals(listOf("igneous"), awaiting.missing.map { it.name })
                assertEquals(listOf("https://auth.example/session"), awaiting.missing.map { it.sourceUrl })
                assertNull(capturedPayload)

                // Overlapping call hits checkMutex -> Busy
                val snapshot =
                    SessionChallengeCookieSnapshot(
                        cookies =
                            listOf(
                                SessionChallengeCookieValue("$ORIGIN/gallery", "cf_clearance", "cf-val"),
                                SessionChallengeCookieValue("$ORIGIN/gallery", "undeclared", "secret-leak"),
                                SessionChallengeCookieValue("https://other.example/evil", "cf_clearance", "evil-val"),
                                SessionChallengeCookieValue(
                                    "https://auth.example/session",
                                    "igneous",
                                    "ign-val",
                                    SessionChallengeCookieAttributes(hostOnly = true, secure = true),
                                ),
                            ),
                        userAgent = "  Mozilla/5.0 FlareTest  ",
                    )
                val firstCall = async { handle.check(snapshot) }
                started.await()
                assertIs<SessionChallengeCheckResult.Busy>(handle.check(snapshot))
                release.complete(Unit)
                assertIs<SessionChallengeCheckResult.Completed>(firstCall.await())

                val sent = assertNotNull(capturedPayload)
                assertEquals("$ORIGIN/challenge", sent.getValue("challengeUrl").jsonPrimitive.content)
                assertEquals("Mozilla/5.0 FlareTest", sent.getValue("userAgent").jsonPrimitive.content)
                val nestedCookies = sent.getValue("cookies").jsonObject
                assertFalse("userAgent" in nestedCookies, "Nested cookies object must strip userAgent")
                val sentCookieNames =
                    nestedCookies
                        .getValue("cookies")
                        .jsonArray
                        .map { item ->
                            item.jsonObject
                                .getValue("name")
                                .jsonPrimitive
                                .content
                        }.toSet()
                assertEquals(setOf("cf_clearance", "igneous"), sentCookieNames)
                assertNull(coordinator.beginSession(exception.challengeId))
            } finally {
                pool.close()
            }
        }

    @Test
    fun pendingThenCompletedPerformsCasCredentialUpdateAndInvalidatesSiblings() =
        runBlocking<Unit> {
            val pool = PluginRuntimePool(files, UnusedTransport)
            val coordinator = PluginSessionChallengeCoordinatorV1(pool)
            val plugin =
                challengePlugin(
                    completeBody =
                        """
                        const count = (request.pendingPayload?.attempts ?? 0) + 1;
                        if (count === 1) {
                          return { type: "pending", pendingPayload: { attempts: 1 } };
                        }
                        const snap = await flare.credential.read({ withRevision: true });
                        const updated = await flare.credential.replace(
                          { ...snap.value, cf: request.cookies.cookies[0].value, ua: request.userAgent ?? null },
                          { expectedRevision: snap.revision }
                        );
                        if (!updated) throw flare.error({ code: "Validation", message: { value: "CAS conflict" } });
                        return { type: "completed" };
                        """.trimIndent(),
                )
            val accountKey = MicroBlogKey("me", "account.example")
            val otherKey = MicroBlogKey("other", "account.example")
            val credential = MemoryVersionedCredential(JsonObject(mapOf("session" to JsonPrimitive("keep"))))
            val otherCredential = MemoryVersionedCredential(JsonObject(emptyMap()))
            val runtimeKey = PluginRuntimeKeyV1.account(PLUGIN_ID, HASH, ORIGIN, "me", "epoch-1")
            val otherRuntimeKey = PluginRuntimeKeyV1.account(PLUGIN_ID, HASH, ORIGIN, "other", "epoch-1")
            try {
                val primary =
                    coordinator.createTicket(
                        plugin,
                        runtimeKey,
                        accountKey,
                        ORIGIN,
                        "epoch-1",
                        credential,
                        PluginChallengeV1("$ORIGIN/c1"),
                        "Verify",
                    ) { locale -> PluginInvocationContextV1.account(PLUGIN_ID, PLATFORM_ID, HASH, ORIGIN, "me", locale, credential) }
                val sibling =
                    coordinator.createTicket(
                        plugin,
                        runtimeKey,
                        accountKey,
                        ORIGIN,
                        "epoch-1",
                        credential,
                        PluginChallengeV1("$ORIGIN/c2"),
                        "Verify sibling",
                    ) { locale -> PluginInvocationContextV1.account(PLUGIN_ID, PLATFORM_ID, HASH, ORIGIN, "me", locale, credential) }
                val unrelated =
                    coordinator.createTicket(
                        plugin,
                        otherRuntimeKey,
                        otherKey,
                        ORIGIN,
                        "epoch-1",
                        otherCredential,
                        PluginChallengeV1("$ORIGIN/c3"),
                        "Verify other",
                    ) { locale ->
                        PluginInvocationContextV1.account(PLUGIN_ID, PLATFORM_ID, HASH, ORIGIN, "other", locale, otherCredential)
                    }

                val handle = assertNotNull(coordinator.beginSession(primary.challengeId))
                val readyCookies =
                    SessionChallengeCookieSnapshot(
                        cookies =
                            listOf(
                                SessionChallengeCookieValue("$ORIGIN/gallery", "cf_clearance", "cleared-1"),
                                SessionChallengeCookieValue("https://auth.example/session", "igneous", "ign-1"),
                            ),
                        userAgent = "FlareUA/2.0\nEvilHeader",
                    )
                assertIs<SessionChallengeCheckResult.Pending>(handle.check(readyCookies))
                assertNotNull(coordinator.beginSession(primary.challengeId))

                val validUaCookies = readyCookies.copy(userAgent = "FlareUA/2.0")
                assertIs<SessionChallengeCheckResult.Completed>(handle.check(validUaCookies))

                val stored = credential.readVersioned().value.jsonObject
                assertEquals("keep", stored.getValue("session").jsonPrimitive.content)
                assertEquals("cleared-1", stored.getValue("cf").jsonPrimitive.content)
                assertEquals("FlareUA/2.0", stored.getValue("ua").jsonPrimitive.content)
                assertEquals(1, credential.casSuccessCount)
                assertNull(coordinator.beginSession(primary.challengeId))
                assertNull(coordinator.beginSession(sibling.challengeId), "Completed challenge must invalidate sibling tickets")
                assertNotNull(coordinator.beginSession(unrelated.challengeId), "Other accounts must stay unaffected")
            } finally {
                pool.close()
            }
        }

    @Test
    fun authenticationRequiredDuringCompleteDestroysTicketAndThrowsRequireReLogin() =
        runBlocking<Unit> {
            val pool = PluginRuntimePool(files, UnusedTransport)
            val coordinator = PluginSessionChallengeCoordinatorV1(pool)
            val plugin =
                challengePlugin(
                    completeBody =
                        """throw flare.error({ code: "AuthenticationRequired", message: { value: "Logged out" } });""",
                )
            val accountKey = MicroBlogKey("me", "account.example")
            val credential = MemoryVersionedCredential(JsonObject(emptyMap()))
            val runtimeKey = PluginRuntimeKeyV1.account(PLUGIN_ID, HASH, ORIGIN, "me", "epoch-1")
            try {
                val ticket =
                    coordinator.createTicket(
                        plugin,
                        runtimeKey,
                        accountKey,
                        ORIGIN,
                        "epoch-1",
                        credential,
                        PluginChallengeV1("$ORIGIN/c1"),
                        "Verify",
                    ) { locale -> PluginInvocationContextV1.account(PLUGIN_ID, PLATFORM_ID, HASH, ORIGIN, "me", locale, credential) }
                val handle = assertNotNull(coordinator.beginSession(ticket.challengeId))
                val relogin =
                    assertFailsWith<RequireReLoginException> {
                        handle.check(
                            SessionChallengeCookieSnapshot(
                                cookies =
                                    listOf(
                                        SessionChallengeCookieValue("$ORIGIN/gallery", "cf_clearance", "v1"),
                                        SessionChallengeCookieValue("https://auth.example/session", "igneous", "v2"),
                                    ),
                                userAgent = "FlareUA/1.0",
                            ),
                        )
                    }
                assertEquals(accountKey, relogin.accountKey)
                assertEquals(PLATFORM_ID, relogin.platformId)
                assertNull(coordinator.beginSession(ticket.challengeId))
            } finally {
                pool.close()
            }
        }

    @Test
    fun invalidationCoversCancelCloseSiblingEpochChangeRetireHotUpdateAndUninstall() =
        runBlocking<Unit> {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val appFiles =
                object : AppFileStore {
                    override val fileSystem = files

                    override fun directory(name: String): Path = root / "files" / name
                }
            val accounts = PluginTestAccounts()
            lateinit var subsystemRef: PluginSubsystemV1
            val registry by lazy { testPluginRegistry(subsystemRef, scope) }
            val subsystem =
                PluginSubsystemV1(
                    appFiles = appFiles,
                    pendingStore = UnusedOAuthPendingStore,
                    registryProvider = { registry },
                    accountsProvider = { accounts },
                    scopeProvider = { scope },
                ).also { subsystemRef = it }
            val switcher = PluginRuntimeSwitcherV1(subsystem, registry, accounts, scope)
            try {
                // Install real v2 plugin with SESSION_CHALLENGE into subsystem
                val archive = root / "challenge-v1.fpp"
                val (manifest, script) = challengePackageManifestAndScript()
                TestFppFactory.write(archive, TestFppFactory.validEntries(manifest, script))
                val installed =
                    subsystem.installer.commit(
                        subsystem.installer.inspect(archive),
                        confirmed = true,
                        activate = subsystem.stateStore::install,
                    )
                switcher.install(installed)
                val running =
                    subsystem.running.value.plugins
                        .getValue(installed.pluginId)
                val accountKey = MicroBlogKey("me", "plugin.example")
                val accountOrigin = "https://plugin.example"

                val credV1 =
                    running.accountCredential(
                        LoginSuccessV1(
                            accountId = "me",
                            origin = accountOrigin,
                            credential = JsonObject(emptyMap()),
                            profile = ProfileV1(EntityKeyV1("me", "plugin.example"), "me", "Me"),
                            capabilities =
                                mapOf(
                                    PluginAbiV1.Capabilities.TIMELINE to setOf("page"),
                                    PluginAbiV1.Capabilities.SESSION_CHALLENGE to setOf("complete"),
                                ),
                        ),
                    )
                val dataContext = MutableAccountContext(accountKey, credV1)
                val accessV1 = PlatformDataSourceCredentialAccessV1(running, dataContext)
                val runtimeKey =
                    PluginRuntimeKeyV1.account(
                        running.installed.pluginId,
                        running.installed.packageHash,
                        accountOrigin,
                        "me",
                        credV1.sessionEpoch,
                    )

                suspend fun newTicket(tag: String): SessionChallengeException =
                    subsystem.sessionChallenge.createTicket(
                        plugin = running,
                        runtimeKey = runtimeKey,
                        accountKey = accountKey,
                        accountOrigin = accountOrigin,
                        accountEpoch = credV1.sessionEpoch,
                        credentialAccess = accessV1,
                        challenge = PluginChallengeV1("$accountOrigin/$tag"),
                        displayMessage = "Verify $tag",
                        contextProvider = { locale -> running.accountInvocationContext(accessV1.current(), locale, accessV1) },
                    )

                // 1) cancel & close destroy ticket
                val tCancel = newTicket("cancel")
                val hCancel = assertNotNull(subsystem.sessionChallenge.beginSession(tCancel.challengeId))
                hCancel.cancel()
                assertNull(subsystem.sessionChallenge.beginSession(tCancel.challengeId))

                val tClose = newTicket("close")
                val hClose = assertNotNull(subsystem.sessionChallenge.beginSession(tClose.challengeId))
                hClose.close()
                assertNull(subsystem.sessionChallenge.beginSession(tClose.challengeId))

                // 2) invalidateSiblingTickets preserves excludeChallengeId and invalidates others
                val tKeep = newTicket("keep")
                val tSib = newTicket("sib")
                subsystem.sessionChallenge.invalidateSiblingTickets(accountKey, tKeep.challengeId)
                assertNotNull(subsystem.sessionChallenge.beginSession(tKeep.challengeId))
                assertNull(subsystem.sessionChallenge.beginSession(tSib.challengeId))

                // 3) accountEpoch change invalidates ticket
                val prevEpoch = credV1.sessionEpoch
                dataContext.persisted.value = credV1.copy(sessionEpoch = "new-epoch")
                assertNull(subsystem.sessionChallenge.beginSession(tKeep.challengeId))
                dataContext.persisted.value = credV1.copy(sessionEpoch = prevEpoch)

                // 4) logout / account removal (removed = true) invalidates ticket
                val tLogout = newTicket("logout")
                dataContext.removed = true
                assertNull(subsystem.sessionChallenge.beginSession(tLogout.challengeId))
                dataContext.removed = false

                // 5) hot-update invalidates tickets for the plugin
                val tHotUpdate = newTicket("hot-update")
                val archiveV2 = root / "challenge-v2.fpp"
                val manifestV2 = manifest.replace("\"version\": \"1.0.0\"", "\"version\": \"1.0.1\"")
                TestFppFactory.write(archiveV2, TestFppFactory.validEntries(manifestV2, script))
                val installedV2 =
                    subsystem.installer.commit(
                        subsystem.installer.inspect(archiveV2),
                        confirmed = true,
                        activate = subsystem.stateStore::install,
                    )
                switcher.install(installedV2)
                assertNull(subsystem.sessionChallenge.beginSession(tHotUpdate.challengeId))

                // 6) retire / uninstall invalidates tickets for the plugin
                val runningV2 =
                    subsystem.running.value.plugins
                        .getValue(installedV2.pluginId)
                dataContext.persisted.value =
                    runningV2.accountCredential(
                        LoginSuccessV1(
                            accountId = "me",
                            origin = accountOrigin,
                            credential = JsonObject(emptyMap()),
                            profile = ProfileV1(EntityKeyV1("me", "plugin.example"), "me", "Me"),
                            capabilities =
                                mapOf(
                                    PluginAbiV1.Capabilities.TIMELINE to setOf("page"),
                                    PluginAbiV1.Capabilities.SESSION_CHALLENGE to setOf("complete"),
                                ),
                        ),
                    )
                val accessV2 = PlatformDataSourceCredentialAccessV1(runningV2, dataContext)
                val runtimeKeyV2 =
                    PluginRuntimeKeyV1.account(
                        runningV2.installed.pluginId,
                        runningV2.installed.packageHash,
                        accountOrigin,
                        "me",
                        accessV2.initial().sessionEpoch,
                    )
                val tUninstall =
                    subsystem.sessionChallenge.createTicket(
                        plugin = runningV2,
                        runtimeKey = runtimeKeyV2,
                        accountKey = accountKey,
                        accountOrigin = accountOrigin,
                        accountEpoch = accessV2.initial().sessionEpoch,
                        credentialAccess = accessV2,
                        challenge = PluginChallengeV1("$accountOrigin/uninstall"),
                        displayMessage = "Verify uninstall",
                        contextProvider = { locale -> runningV2.accountInvocationContext(accessV2.current(), locale, accessV2) },
                    )
                switcher.uninstall(runningV2.installed.pluginId, runningV2.installed.ownedPlatformId)
                assertNull(subsystem.sessionChallenge.beginSession(tUninstall.challengeId))
            } finally {
                scope.cancel()
                subsystem.runtimePool.close()
            }
        }

    @Test
    fun backgroundCapabilityNegotiationTreatsChallengeRequiredAsTransientRetryableError() =
        runBlocking<Unit> {
            var negotiateAttempts = 0
            val transport =
                object : PluginHttpTransport {
                    override suspend fun execute(request: PluginTransportRequestV1): PluginTransportResponseV1 {
                        negotiateAttempts++
                        val status = if (negotiateAttempts == 1) 403 else 200
                        return PluginTransportResponseV1(status, emptyMap(), "{}".encodeToByteArray())
                    }
                }
            val pool = PluginRuntimePool(files, transport)
            val coordinator = PluginSessionChallengeCoordinatorV1(pool)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val manifest =
                    PluginManifestV1(
                        apiVersion = 2,
                        id = PLUGIN_ID,
                        version = "1.0.0",
                        name = PluginTextV1.Literal("CapTest"),
                        platform =
                            PluginPlatformManifestV1(
                                id = PLATFORM_ID,
                                name = PluginTextV1.Literal("CapTest"),
                                capabilities =
                                    mapOf(
                                        PluginAbiV1.Capabilities.ACCOUNT_CAPABILITIES to
                                            CapabilityManifestV1(mapOf("negotiate" to CapabilityOperationManifestV1())),
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
                      capabilities: {
                        accountCapabilities: {
                          async negotiate(request, context) {
                            const response = await flare.http.request({ url: context.origin + "/caps" });
                            if (response.status === 403) {
                              throw flare.error({
                                code: "ChallengeRequired",
                                message: { value: "CF challenge" },
                                challenge: { url: "https://account.example/challenge" }
                              });
                            }
                            return {
                              capabilities: {
                                "${PluginAbiV1.Capabilities.TIMELINE}": ["page"],
                                "${PluginAbiV1.Capabilities.SESSION_CHALLENGE}": ["complete"]
                              }
                            };
                          }
                        },
                        timeline: { async page() { return { items: [] }; } },
                        sessionChallenge: { async complete() { return { type: "completed" }; } }
                      }
                    });
                    """.trimIndent()
                val plugin =
                    RunningPluginV1(
                        installed = InstalledPluginV1(PLUGIN_ID, "1.0.0", HASH, 0, 0, manifest = manifest),
                        packagePath = "unused.fpp",
                        iconPath = "unused.png",
                        developmentSource = script,
                    )
                val initial =
                    PluginAccountCredentialV1(
                        snapshot =
                            AccountPluginSnapshotV1(
                                pluginId = PLUGIN_ID,
                                platformId = PLATFORM_ID,
                                packageHash = "b".repeat(64),
                                origin = ORIGIN,
                                accountId = "me",
                                profile = ProfileV1(EntityKeyV1("me", "account.example"), "me", "Me"),
                                manifestCapabilities = mapOf(PluginAbiV1.Capabilities.TIMELINE to setOf("page")),
                                negotiatedCapabilities = mapOf(PluginAbiV1.Capabilities.TIMELINE to setOf("page")),
                                credentialSchemaVersion = 1,
                                capabilitiesPackageHash = "b".repeat(64),
                            ),
                        credential = JsonObject(emptyMap()),
                        sessionEpoch = "epoch-1",
                        credentialRevision = "rev-1",
                    )
                val context = MutableAccountContext(MicroBlogKey("me", "account.example"), initial)
                val refresher = PluginAccountCapabilitiesV1(plugin, pool, scope)

                // First refresh encounters ChallengeRequired -> throws PluginCallException (not SessionChallengeException / RequireReLoginException) and creates no ticket
                val firstError = assertFailsWith<PluginCallException> { refresher.refresh(context) }
                assertEquals(PluginErrorCodeV1.ChallengeRequired, firstError.error.code)
                assertEquals(0, coordinator.activeTicketCount())

                // Second refresh succeeds in background and enables SESSION_CHALLENGE
                assertTrue(refresher.refresh(context))
                assertEquals(
                    setOf("complete"),
                    context.persisted.value.snapshot.negotiatedCapabilities[PluginAbiV1.Capabilities.SESSION_CHALLENGE],
                )
            } finally {
                scope.cancel()
                pool.close()
            }
        }

    @Test
    fun koinBindsSessionChallengeCoordinatorAndAppleFacadeDelegatesWithoutDefaults() =
        runBlocking<Unit> {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val appFiles =
                object : AppFileStore {
                    override val fileSystem = files

                    override fun directory(name: String): Path = root / "koin-files" / name
                }
            val accounts = PluginTestAccounts()
            lateinit var subsystemRef: PluginSubsystemV1
            val registry by lazy { testPluginRegistry(subsystemRef, scope) }
            val subsystem =
                PluginSubsystemV1(
                    appFiles = appFiles,
                    pendingStore = UnusedOAuthPendingStore,
                    registryProvider = { registry },
                    accountsProvider = { accounts },
                    scopeProvider = { scope },
                ).also { subsystemRef = it }
            val switcher = PluginRuntimeSwitcherV1(subsystem, registry, accounts, scope)
            startKoin {
                modules(
                    module {
                        single { subsystem }
                        single<SessionChallengeCoordinator> {
                            PluginKoinModule().sessionChallengeCoordinator(subsystem)
                        }
                    },
                )
            }
            val koin =
                org.koin.mp.KoinPlatformTools
                    .defaultContext()
                    .get()
            try {
                val bound = koin.get<SessionChallengeCoordinator>()
                assertTrue(bound === subsystem.sessionChallenge)

                val archive = root / "koin-challenge.fpp"
                val (manifest, script) =
                    challengePackageManifestAndScript(
                        challengeProbesJson =
                            """
                            [
                              { "url": "${'$'}accountOrigin/gallery", "cookies": [{ "name": "cf_clearance", "required": true }] },
                              { "url": "https://auth.example/session", "cookies": [{ "name": "igneous", "required": false }] }
                            ]
                            """.trimIndent(),
                    )
                TestFppFactory.write(archive, TestFppFactory.validEntries(manifest, script))
                val installed =
                    subsystem.installer.commit(
                        subsystem.installer.inspect(archive),
                        confirmed = true,
                        activate = subsystem.stateStore::install,
                    )
                switcher.install(installed)
                val plugin =
                    subsystem.running.value.plugins
                        .getValue(installed.pluginId)
                val platformId = requireNotNull(plugin.installed.ownedPlatformId)
                val accountOrigin = "https://plugin.example"
                val accountKey = MicroBlogKey("me", "plugin.example")
                val credential = MemoryVersionedCredential(JsonObject(emptyMap()))
                val runtimeKey =
                    PluginRuntimeKeyV1.account(
                        plugin.installed.pluginId,
                        plugin.installed.packageHash,
                        accountOrigin,
                        "me",
                        "epoch-1",
                    )

                fun newFacadeTicket(tag: String): SessionChallengeException =
                    subsystem.sessionChallenge.createTicket(
                        plugin,
                        runtimeKey,
                        accountKey,
                        accountOrigin,
                        "epoch-1",
                        credential,
                        PluginChallengeV1("$accountOrigin/$tag"),
                        "Verify $tag",
                    ) { locale ->
                        PluginInvocationContextV1.account(
                            plugin.installed.pluginId,
                            platformId,
                            plugin.installed.packageHash,
                            accountOrigin,
                            "me",
                            locale,
                            credential,
                        )
                    }

                val ticket = newFacadeTicket("c")

                val facade = PluginSessionChallengeFacadeV1()
                val handle = assertNotNull(facade.beginSession(ticket.challengeId))
                val checkResult =
                    facade.check(
                        handle,
                        SessionChallengeCookieSnapshot(
                            cookies =
                                listOf(
                                    SessionChallengeCookieValue("$accountOrigin/gallery", "cf_clearance", "ok"),
                                    SessionChallengeCookieValue("https://auth.example/session", "igneous", "ok"),
                                ),
                            userAgent = "FlareApple/1.0",
                        ),
                    )
                assertIs<SessionChallengeCheckResult.Completed>(checkResult)
                assertNull(facade.beginSession(ticket.challengeId))

                val ticket2 = newFacadeTicket("c2")
                val ticket3 = newFacadeTicket("c3")
                facade.invalidateSiblingTickets(accountKey, ticket2.challengeId)
                assertNotNull(facade.beginSession(ticket2.challengeId))
                assertNull(facade.beginSession(ticket3.challengeId))
                facade.invalidateTicket(ticket2.challengeId)
                assertNull(facade.beginSession(ticket2.challengeId))
            } finally {
                scope.cancel()
                subsystem.runtimePool.close()
                stopKoin()
            }
        }

    private fun challengePackageManifestAndScript(
        challengeProbesJson: String =
            """[{ "url": "${'$'}accountOrigin/gallery", "cookies": [{ "name": "cf_clearance", "required": true }] }]""",
    ): Pair<String, String> {
        val withPermissions =
            TestFppFactory.validManifest
                .replace("\"apiVersion\": 1", "\"apiVersion\": 2")
                .replace(
                    "\"defaultLocale\": \"en\",",
                    """
                    "defaultLocale": "en",
                    "permissions": {
                      "authOrigins": ["https://auth.example"]
                    },
                    """.trimIndent(),
                )
        val manifest =
            withPermissions.replace(
                "\"capabilities\": {",
                """
                "capabilities": {
                  "${PluginAbiV1.Capabilities.SESSION_CHALLENGE}": {
                    "operations": { "complete": {} },
                    "challengeProbes": $challengeProbesJson
                  },
                """.trimIndent(),
            )
        val script =
            TestFppFactory.validScript.replace(
                "capabilities: {",
                """
                capabilities: {
                  sessionChallenge: { async complete() { return { type: "completed" }; } },
                """.trimIndent(),
            )
        return manifest to script
    }

    private fun challengePlugin(completeBody: String = """return { type: "completed" };"""): RunningPluginV1 {
        val manifest =
            PluginManifestV1(
                apiVersion = 2,
                id = PLUGIN_ID,
                version = "1.0.0",
                name = PluginTextV1.Literal("Challenge"),
                permissions = PluginPermissionsV1(authOrigins = setOf("https://auth.example")),
                platform =
                    PluginPlatformManifestV1(
                        id = PLATFORM_ID,
                        name = PluginTextV1.Literal("Challenge"),
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
                                                    cookies =
                                                        listOf(
                                                            CookieRequirementManifestV1("cf_clearance", required = true),
                                                            CookieRequirementManifestV1("optional_hint", required = false),
                                                        ),
                                                ),
                                                CookieProbeManifestV1(
                                                    url = "https://auth.example/session",
                                                    cookies = listOf(CookieRequirementManifestV1("igneous", required = true)),
                                                ),
                                            ),
                                    ),
                            ),
                    ),
            )
        val script =
            """
            defineFlareSocialPlugin({
              capabilities: {
                timeline: { async page() { return { items: [] }; } },
                sessionChallenge: { async complete(request, context) { $completeBody } }
              }
            });
            """.trimIndent()
        return RunningPluginV1(
            installed = InstalledPluginV1(PLUGIN_ID, "1.0.0", HASH, 0, 0, manifest = manifest),
            packagePath = "unused.fpp",
            iconPath = "unused.png",
            developmentSource = script,
        )
    }

    private fun sequentialEntropy(nextId: () -> String): PluginLoginEntropyV1 =
        object : PluginLoginEntropyV1 {
            override fun newFlowId(): String = nextId()

            override fun newState(): String = nextId()
        }

    private class MemoryVersionedCredential(
        initial: JsonElement,
    ) : PluginCredentialAccess {
        private var currentValue = initial
        private var currentRevision = "rev-0"
        private var revCounter = 0
        var casSuccessCount = 0

        override suspend fun read(): JsonElement = currentValue

        override suspend fun replace(value: JsonElement) {
            currentValue = value
            currentRevision = "rev-${++revCounter}"
        }

        override suspend fun readVersioned(): PluginCredentialSnapshot = PluginCredentialSnapshot(currentValue, currentRevision)

        override suspend fun replaceIfCurrent(
            value: JsonElement,
            revision: String,
        ): Boolean {
            if (revision != currentRevision) return false
            currentValue = value
            currentRevision = "rev-${++revCounter}"
            casSuccessCount++
            return true
        }
    }

    private class MutableAccountContext(
        override val accountKey: MicroBlogKey,
        initial: PluginAccountCredentialV1,
    ) : PlatformDataSourceContext {
        val persisted = MutableStateFlow(initial)
        var removed = false
        private val mutex = Mutex()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> credential(serializer: KSerializer<T>): T = persisted.value as T

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> credentialFlow(serializer: KSerializer<T>): Flow<T> = persisted as Flow<T>

        override suspend fun <T : Any> updateCredential(
            serializer: KSerializer<T>,
            credential: T,
        ) {
            error("API v2 must use atomic update")
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Any> mutateCredential(
            serializer: KSerializer<T>,
            update: (T) -> T?,
        ): T? =
            mutex.withLock {
                if (removed) throw CancellationException("Account was removed")
                update(persisted.value as T)?.also { persisted.value = it as PluginAccountCredentialV1 }
            }
    }

    private object UnusedTransport : PluginHttpTransport {
        override suspend fun execute(request: PluginTransportRequestV1): PluginTransportResponseV1 = error("Transport should not be called")
    }

    private object UnusedOAuthPendingStore : PluginOAuthPendingStoreV1 {
        override suspend fun save(pending: PluginOAuthPendingV1) {}

        override suspend fun load(flowId: String): PluginOAuthPendingV1? = null

        override suspend fun consume(pending: PluginOAuthPendingV1): Boolean = false
    }

    private companion object {
        const val PLUGIN_ID = "dev.dimension.flare.test.challenge"
        const val PLATFORM_ID = "ChallengePlatform"
        const val ORIGIN = "https://account.example"
        val HASH = "a".repeat(64)
    }
}
