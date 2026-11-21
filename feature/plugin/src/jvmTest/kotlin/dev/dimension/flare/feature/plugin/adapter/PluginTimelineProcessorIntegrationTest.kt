package dev.dimension.flare.feature.plugin.adapter

import dev.dimension.flare.data.database.cache.dao.PluginPostWidgetDao
import dev.dimension.flare.data.database.cache.model.DbPluginPostWidget
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.data.io.AppFileStore
import dev.dimension.flare.data.model.PluginPermission
import dev.dimension.flare.data.model.PluginPermissionType
import dev.dimension.flare.data.repository.PluginAuthoringPhase
import dev.dimension.flare.feature.plugin.PluginSubsystemV1
import dev.dimension.flare.feature.plugin.PluginTimelineGrantStoreV1
import dev.dimension.flare.feature.plugin.TimelineProcessorSettingsBacking
import dev.dimension.flare.feature.plugin.authoring.PluginAuthoringServiceV1
import dev.dimension.flare.feature.plugin.host.PluginCallTimeoutV1
import dev.dimension.flare.feature.plugin.installer.PluginInstallWarningTypeV1
import dev.dimension.flare.feature.plugin.installer.TestFppFactory
import dev.dimension.flare.feature.plugin.installer.sensitivePermissions
import dev.dimension.flare.feature.plugin.login.PluginOAuthPendingStoreV1
import dev.dimension.flare.feature.plugin.login.PluginOAuthPendingV1
import dev.dimension.flare.feature.plugin.management.PluginManagementPresenterV1
import dev.dimension.flare.feature.plugin.management.PluginTestAccounts
import dev.dimension.flare.feature.plugin.management.testPluginRegistry
import dev.dimension.flare.feature.plugin.manifest.PluginTimelineFilterManifestV1
import dev.dimension.flare.feature.plugin.storage.PluginStorageChangesV1
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiCard
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.UiTranslatableText
import dev.dimension.flare.ui.model.UiWidgetNode
import dev.dimension.flare.ui.model.createSampleStatus
import dev.dimension.flare.ui.model.createSampleUser
import dev.dimension.flare.ui.model.extractPluginPostInput
import dev.dimension.flare.ui.model.pluginPostEntityKey
import dev.dimension.flare.ui.render.RenderContent
import dev.dimension.flare.ui.render.RenderRun
import dev.dimension.flare.ui.render.RenderTextStyle
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.render.uiRichTextOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class PluginTimelineProcessorIntegrationTest {
    @Test
    fun linkHostFilterMatchesExactAndSubdomainOnlyNeverSuffixSpoof() {
        val filter = PluginTimelineFilterManifestV1(linkHosts = listOf("polymarket.com"), requireLinks = true)
        val legit = createSamplePost(link = "https://app.polymarket.com:443/event/fed").extractPluginPostInput()
        val spoof = createSamplePost(link = "https://evilpolymarket.com/event/fed").extractPluginPostInput()
        assertTrue(filter.matches(legit))
        assertFalse(filter.matches(spoof))
    }

    @Test
    fun executesBatchesPersistsDiskTtlSkipsMemoryOnlyInSqliteAndDropsLateResultAfterDisable() =
        runTest {
            withTimelineProcessorTestHarness { harness ->
                val stockId =
                    harness.installAndConfirmTimelineProcessor(
                        id = "org.example.stock",
                        regex = "\\$[A-Z]{1,5}\\b",
                        script =
                            """
                            defineFlareTimelineProcessorPlugin({
                              async processBatch(req) {
                                return { results: req.items.map(i => ({ itemId: i.itemId, cache: { policy: "disk", ttlSeconds: 30 }, cards: [{ id: "q", body: [{ type: "stat", label: "AAPL", value: "192.4" }] }] })) };
                              }
                            });
                            """.trimIndent(),
                    )
                val gpgId =
                    harness.installAndConfirmTimelineProcessor(
                        id = "org.example.gpg",
                        containsAny = listOf("-----BEGIN PGP MESSAGE-----"),
                        script =
                            """
                            defineFlareTimelineProcessorPlugin({
                              async processBatch(req) {
                                return { results: req.items.map(i => ({ itemId: i.itemId, cache: { policy: "memoryOnly" }, cards: [{ id: "dec", body: [{ type: "text", style: "code", text: "secret-plaintext" }] }] })) };
                              }
                            });
                            """.trimIndent(),
                    )
                assertEquals("org.example.gpg", gpgId)
                val stockPost = createSamplePost(text = "Watching \$AAPL today")
                val gpgPost = createSamplePost(text = "-----BEGIN PGP MESSAGE-----\n\nhQEMA...")
                harness.service.submitPosts(listOf(stockPost, gpgPost))
                harness.awaitIdle()

                assertEquals(1, harness.service.widgetsForPost(stockPost).size)
                assertEquals(1, harness.service.widgetsForPost(gpgPost).size)
                assertEquals(
                    1,
                    harness.db
                        .pluginPostWidgetDao()
                        .getByPostKeys(listOf(stockPost.pluginPostEntityKey()))
                        .size,
                )
                assertTrue(
                    harness.db
                        .pluginPostWidgetDao()
                        .getByPostKeys(listOf(gpgPost.pluginPostEntityKey()))
                        .isEmpty(),
                )

                harness.gateNextRuntimeCall(stockId)
                val latePost = createSamplePost(text = "Check \$NVDA")
                harness.service.submitPosts(listOf(latePost))
                harness.presenter.setTimelineProcessorEnabled(stockId, false)
                harness.releaseGatedRuntimeCall(stockId)
                harness.awaitIdle()

                assertTrue(harness.service.widgetsForPost(latePost).isEmpty())
                assertTrue(
                    harness.db
                        .pluginPostWidgetDao()
                        .getByPostKeys(listOf(latePost.pluginPostEntityKey()))
                        .isEmpty(),
                )
            }
        }

    @Test
    fun localAiAuthoredTimelineProcessorStartsDisabledAndReDisablesWhenHttpOriginsExpand() =
        runTest {
            withTimelineProcessorTestHarness { harness ->
                val created = harness.authoring.create("timeline_processor_ai_test_key_01")
                harness.writeAiTimelineProcessor(created.id, expectedRevision = 0L, httpOrigins = listOf("https://api.one.example"))
                assertFalse(
                    harness.presenter.state.value.items
                        .single { it.pluginId == created.id }
                        .timelineProcessorEnabled,
                )

                harness.presenter.setTimelineProcessorEnabled(created.id, true)
                assertTrue(
                    harness.presenter.state.value.items
                        .single { it.pluginId == created.id }
                        .timelineProcessorEnabled,
                )

                harness.writeAiTimelineProcessor(
                    created.id,
                    expectedRevision = 1L,
                    httpOrigins = listOf("https://api.one.example", "https://exfil.two.example"),
                )
                assertFalse(
                    harness.presenter.state.value.items
                        .single { it.pluginId == created.id }
                        .timelineProcessorEnabled,
                )
            }
        }

    @Test
    fun filterCombinesAllFieldsWithLogicalAndAcrossContentWarningAndCardMetadata() {
        val filter =
            PluginTimelineFilterManifestV1(
                containsAny = listOf("fed decision"),
                regex = "rate\\s+cut",
                linkHosts = listOf("polymarket.com"),
                requireLinks = true,
            )
        val matchingCwAndCard =
            createSamplePost(
                text = "Discussion thread",
                contentWarning = "Possible rate cut ahead",
                card = UiCard(url = "https://polymarket.com/event/fed", title = "Fed Decision 2026", description = null, media = null),
            ).extractPluginPostInput()
        val missingRegex =
            createSamplePost(
                text = "No pattern here",
                card = UiCard(url = "https://polymarket.com/event/fed", title = "Fed Decision 2026", description = null, media = null),
            ).extractPluginPostInput()
        val wrongHost =
            createSamplePost(
                text = "Fed decision rate cut",
                link = "https://other.example.com/fed",
            ).extractPluginPostInput()

        assertTrue(filter.matches(matchingCwAndCard))
        assertFalse(filter.matches(missingRegex))
        assertFalse(filter.matches(wrongHost))
    }

    @Test
    fun discardsMidFlightUncancelledBatchWhenDisabledAndClearsDbOnUninstall() =
        runTest {
            withTimelineProcessorTestHarness { harness ->
                val pluginId =
                    harness.installAndConfirmTimelineProcessor(
                        id = "org.example.midflight",
                        script =
                            """
                            defineFlareTimelineProcessorPlugin({
                              async processBatch(req) {
                                return { results: req.items.map(i => ({ itemId: i.itemId, cache: { policy: "disk", ttlSeconds: 60 }, cards: [{ id: "m1", body: [{ type: "text", text: "live" }] }] })) };
                              }
                            });
                            """.trimIndent(),
                    )
                val firstPost = createSamplePost(text = "First post")
                harness.service.submitPosts(listOf(firstPost))
                harness.awaitIdle()
                assertEquals(
                    1,
                    harness.db
                        .pluginPostWidgetDao()
                        .getByPostKeys(listOf(firstPost.pluginPostEntityKey()))
                        .size,
                )

                // Gate the runtime call AFTER QuickJS has produced the valid result, right before returning to the service.
                harness.gateNextRuntimeCallNonCancellable(pluginId)
                val inFlightPost = createSamplePost(text = "In-flight post")
                harness.service.submitPosts(listOf(inFlightPost))
                harness.awaitRuntimeCallEntered(pluginId)

                harness.presenter.setTimelineProcessorEnabled(pluginId, false)
                harness.releaseGatedRuntimeCall(pluginId)
                harness.awaitIdle()

                assertTrue(harness.service.widgetsForPost(firstPost).isEmpty())
                assertTrue(harness.service.widgetsForPost(inFlightPost).isEmpty())
                assertTrue(
                    harness.db
                        .pluginPostWidgetDao()
                        .getByPostKeys(listOf(firstPost.pluginPostEntityKey()))
                        .isEmpty(),
                )
                assertTrue(
                    harness.db
                        .pluginPostWidgetDao()
                        .getByPostKeys(listOf(inFlightPost.pluginPostEntityKey()))
                        .isEmpty(),
                )

                // Re-enable, populate DB, then uninstall and verify grant + DB rows are removed.
                harness.presenter.setTimelineProcessorEnabled(pluginId, true)
                harness.service.submitPosts(listOf(firstPost))
                harness.awaitIdle()
                assertEquals(
                    1,
                    harness.db
                        .pluginPostWidgetDao()
                        .getByPostKeys(listOf(firstPost.pluginPostEntityKey()))
                        .size,
                )

                harness.presenter.uninstall(pluginId)
                harness.awaitIdle()
                assertTrue(harness.service.widgetsForPost(firstPost).isEmpty())
                assertTrue(
                    harness.db
                        .pluginPostWidgetDao()
                        .getByPostKeys(listOf(firstPost.pluginPostEntityKey()))
                        .isEmpty(),
                )
                assertFalse(pluginId in harness.currentSettings().enabledTimelineProcessorPlugins)
            }
        }

    @Test
    fun ordersCardsByPluginIdCapsAtEightSupportsCollapsibleToggleManualRefreshCooldownAndMaintenance() =
        runTest {
            withTimelineProcessorTestHarness(initialExpiredRowCount = 2) { harness ->
                harness.awaitIdle()
                // Startup maintenance pruned rows expired more than 7 days ago.
                assertTrue(
                    harness.db
                        .pluginPostWidgetDao()
                        .getByPostKeys(listOf("expired_post_1", "expired_post_2"))
                        .isEmpty(),
                )

                var shouldFailZeta = true
                harness.failPluginCallsWhen("org.example.zeta") { shouldFailZeta }

                harness.installAndConfirmTimelineProcessor(
                    id = "org.example.zeta",
                    script =
                        """
                        defineFlareTimelineProcessorPlugin({
                          async processBatch(req) {
                            return {
                              results: req.items.map(i => ({
                                itemId: i.itemId,
                                cache: { policy: "disk", ttlSeconds: 30 },
                                cards: [
                                  { id: "z1", refreshable: true, body: [{ type: "collapsible", id: "sec", summary: "Details", defaultExpanded: false, children: [{ type: "text", text: "Body" }] }] },
                                  { id: "z2", body: [{ type: "text", text: "Z2" }] },
                                  { id: "z3", body: [{ type: "text", text: "Z3" }] },
                                  { id: "z4", body: [{ type: "text", text: "Z4" }] }
                                ]
                              }))
                            };
                          }
                        });
                        """.trimIndent(),
                )
                harness.installAndConfirmTimelineProcessor(
                    id = "org.example.alpha",
                    script =
                        """
                        defineFlareTimelineProcessorPlugin({
                          async processBatch(req) {
                            return {
                              results: req.items.map(i => ({
                                itemId: i.itemId,
                                cards: [
                                  { id: "a1", body: [{ type: "text", text: "A1" }] },
                                  { id: "a2", body: [{ type: "text", text: "A2" }] },
                                  { id: "a3", body: [{ type: "text", text: "A3" }] }
                                ]
                              }))
                            };
                          }
                        });
                        """.trimIndent(),
                )
                harness.installAndConfirmTimelineProcessor(
                    id = "org.example.middle",
                    script =
                        """
                        defineFlareTimelineProcessorPlugin({
                          async processBatch(req) {
                            return {
                              results: req.items.map(i => ({
                                itemId: i.itemId,
                                cards: [
                                  { id: "m1", body: [{ type: "text", text: "M1" }] },
                                  { id: "m2", body: [{ type: "text", text: "M2" }] },
                                  { id: "m3", body: [{ type: "text", text: "M3" }] }
                                ]
                              }))
                            };
                          }
                        });
                        """.trimIndent(),
                )

                val post = createSamplePost(text = "Multi plugin post")
                harness.service.submitPosts(listOf(post))
                harness.awaitIdle()

                // Zeta failed -> recorded transient cooldown in memory ONLY (no SQLite row for zeta).
                val dbRowsAfterFailure = harness.db.pluginPostWidgetDao().getByPostKeys(listOf(post.pluginPostEntityKey()))
                assertEquals(setOf("org.example.alpha", "org.example.middle"), dbRowsAfterFailure.map { it.pluginId }.toSet())

                // Re-submitting within 60s cooldown does not retry zeta automatically, but manual refreshPostPlugin retries immediately.
                shouldFailZeta = false
                harness.service.submitPosts(listOf(post))
                harness.awaitIdle()
                assertEquals(6, harness.service.widgetsForPost(post).size)

                harness.service.refreshPostPlugin(post, "org.example.zeta")
                harness.awaitIdle()

                // 3 (alpha) + 3 (middle) + 4 (zeta) = 10 -> capped at 8, sorted by pluginId ascending then card order!
                val cards = harness.service.widgetsForPost(post)
                assertEquals(8, cards.size)
                assertEquals(listOf("a1", "a2", "a3", "m1", "m2", "m3", "z1", "z2"), cards.map { it.id })
                assertNull(cards.first { it.id == "a1" }.onRefresh)
                assertNotNull(cards.first { it.id == "z1" }.onRefresh)

                val collapsibleBefore = cards.first { it.id == "z1" }.body.single() as UiWidgetNode.Collapsible
                assertFalse(collapsibleBefore.expanded)
                checkNotNull(collapsibleBefore.onToggle).invoke()

                val collapsibleAfter =
                    harness.service
                        .widgetsForPost(post)
                        .first { it.id == "z1" }
                        .body
                        .single() as UiWidgetNode.Collapsible
                assertTrue(collapsibleAfter.expanded)
            }
        }

    @Test
    fun updatePreservesDisabledGrantUnlessAddingTimelineProcessorForFirstTimeAndEmitsSensitivePermission() =
        runTest {
            withTimelineProcessorTestHarness { harness ->
                val id = "org.example.updatable"
                harness.installAndConfirmTimelineProcessor(
                    id = id,
                    version = "1.0.0",
                    httpOrigins = listOf("https://api.one.example"),
                    script = "defineFlareTimelineProcessorPlugin({ async processBatch() { return { results: [] }; } });",
                )
                assertTrue(
                    harness.presenter.state.value.items
                        .single { it.pluginId == id }
                        .timelineProcessorEnabled,
                )

                // User explicitly disables timelineProcessor; updating to 1.1.0 must preserve disabled state.
                harness.presenter.setTimelineProcessorEnabled(id, false)
                assertFalse(
                    harness.presenter.state.value.items
                        .single { it.pluginId == id }
                        .timelineProcessorEnabled,
                )

                harness.installAndConfirmTimelineProcessor(
                    id = id,
                    version = "1.1.0",
                    httpOrigins = listOf("https://api.one.example"),
                    script = "defineFlareTimelineProcessorPlugin({ async processBatch() { return { results: [] }; } });",
                )
                assertFalse(
                    harness.presenter.state.value.items
                        .single { it.pluginId == id }
                        .timelineProcessorEnabled,
                )

                // Installing a settings-only plugin and then updating to add timelineProcessor warns TimelinePostAccess and enables on confirm.
                val upgradeId = "org.example.upgrade"
                val settingsFpp = harness.writeSettingsOnlyFpp(upgradeId, version = "1.0.0")
                val initialReview = harness.presenter.inspect(settingsFpp.toString())
                assertFalse(initialReview.permissions.any { it.type == PluginPermissionType.TimelinePostAccess })
                harness.presenter.confirmInstall()
                assertFalse(
                    harness.presenter.state.value.items
                        .single { it.pluginId == upgradeId }
                        .hasTimelineProcessor,
                )

                val upgradedFpp =
                    harness.writeTimelineProcessorFpp(
                        id = upgradeId,
                        version = "2.0.0",
                        script = "defineFlareTimelineProcessorPlugin({ async processBatch() { return { results: [] }; } });",
                    )
                val upgradeReview = harness.presenter.inspect(upgradedFpp.toString())
                val timelinePermission = PluginPermission(PluginPermissionType.TimelinePostAccess, "*")
                assertTrue(timelinePermission in upgradeReview.permissions)
                assertTrue(
                    upgradeReview.warnings.any {
                        it.type == PluginInstallWarningTypeV1.AddedPermission && it.permission == timelinePermission
                    },
                )
                val manifest =
                    harness.subsystem.installer
                        .let { upgradeReview }
                assertNotNull(manifest)
                harness.presenter.confirmInstall()
                val upgradedItem =
                    harness.presenter.state.value.items
                        .single { it.pluginId == upgradeId }
                assertTrue(upgradedItem.hasTimelineProcessor)
                assertTrue(upgradedItem.timelineProcessorEnabled)

                val installedManifest =
                    harness.subsystem.stateStore.desired.value.plugins
                        .getValue(upgradeId)
                        .manifest
                assertTrue(timelinePermission in installedManifest.sensitivePermissions())

                // Also verify storageNamespaces cache invalidation on watched namespace write.
                val storagePluginId =
                    harness.installAndConfirmTimelineProcessor(
                        id = "org.example.storage",
                        storageNamespaces = listOf("options"),
                        script =
                            """
                            defineFlareTimelineProcessorPlugin({
                              async processBatch(req) {
                                const snap = await flare.storage.open("options").read();
                                const prefix = snap.values.prefix || "v1";
                                return { results: req.items.map(i => ({ itemId: i.itemId, cards: [{ id: "s1", body: [{ type: "text", text: String(prefix) }] }] })) };
                              }
                            });
                            """.trimIndent(),
                    )
                val post = createSamplePost(text = "Storage post")
                harness.service.submitPosts(listOf(post))
                harness.awaitIdle()
                assertEquals(
                    "v1",
                    (
                        harness.service
                            .widgetsForPost(post)
                            .single()
                            .body
                            .single() as UiWidgetNode.Text
                    ).text,
                )

                harness.subsystem.storage
                    .access(storagePluginId) {}
                    .write("options", PluginStorageChangesV1(set = mapOf("prefix" to JsonPrimitive("v2"))))
                harness.service.submitPosts(listOf(post))
                harness.awaitIdle()
                assertEquals(
                    "v2",
                    (
                        harness.service
                            .widgetsForPost(post)
                            .single()
                            .body
                            .single() as UiWidgetNode.Text
                    ).text,
                )
            }
        }

    @Test
    fun hydratesFromSqliteRefetchesOnTtlExpiryEvictsAiExpandedOriginsAndSupportsDevReload() =
        runTest {
            withTimelineProcessorTestHarness { harness ->
                val ttlPluginId =
                    harness.installAndConfirmTimelineProcessor(
                        id = "org.example.ttl",
                        script =
                            """
                            let seq = 0;
                            defineFlareTimelineProcessorPlugin({
                              async processBatch(req) {
                                seq += 1;
                                return { results: req.items.map(i => ({ itemId: i.itemId, cache: { policy: "disk", ttlSeconds: 20 }, cards: [{ id: "t1", body: [{ type: "text", text: "run-" + seq }] }] })) };
                              }
                            });
                            """.trimIndent(),
                    )
                val post = createSamplePost(text = "TTL post")
                harness.service.submitPosts(listOf(post))
                harness.awaitIdle()
                assertEquals(
                    "run-1",
                    (
                        harness.service
                            .widgetsForPost(post)
                            .single()
                            .body
                            .single() as UiWidgetNode.Text
                    ).text,
                )
                assertEquals(1, harness.invocationCount(ttlPluginId))

                // Cold second service sharing the same DAO hydrates from SQLite with zero runtime calls.
                val coldService = harness.createColdService()
                try {
                    coldService.submitPosts(listOf(post))
                    coldService.awaitIdleForTesting()
                    assertEquals(
                        "run-1",
                        (
                            coldService
                                .widgetsForPost(post)
                                .single()
                                .body
                                .single() as UiWidgetNode.Text
                        ).text,
                    )
                    assertEquals(1, harness.invocationCount(ttlPluginId))
                } finally {
                    coldService.close()
                }

                // Advance clock past 20s TTL -> expired entry is omitted and re-submitting invokes runtime again.
                harness.nowMillis.addAndGet(25_000L)
                assertTrue(harness.service.widgetsForPost(post).isEmpty())
                harness.service.submitPosts(listOf(post))
                harness.awaitIdle()
                assertEquals(
                    "run-2",
                    (
                        harness.service
                            .widgetsForPost(post)
                            .single()
                            .body
                            .single() as UiWidgetNode.Text
                    ).text,
                )
                assertEquals(2, harness.invocationCount(ttlPluginId))

                // AI plugin enabled -> caches widget -> expanding httpOrigins evicts cached widgets in RAM & SQLite.
                val ai = harness.authoring.create("timeline_processor_ai_eviction_01")
                harness.writeAiTimelineProcessor(ai.id, expectedRevision = 0L, httpOrigins = listOf("https://api.one.example"))
                harness.presenter.setTimelineProcessorEnabled(ai.id, true)
                val aiPost = createSamplePost(text = "AI post")
                harness.service.submitPosts(listOf(aiPost))
                harness.awaitIdle()
                assertTrue(harness.service.widgetsForPost(aiPost).any { it.pluginId == ai.id })
                assertTrue(
                    harness.db
                        .pluginPostWidgetDao()
                        .getByPostKeys(listOf(aiPost.pluginPostEntityKey()))
                        .any { it.pluginId == ai.id },
                )

                harness.writeAiTimelineProcessor(
                    ai.id,
                    expectedRevision = 1L,
                    httpOrigins = listOf("https://api.one.example", "https://exfil.two.example"),
                )
                harness.awaitIdle()
                assertFalse(harness.service.widgetsForPost(aiPost).any { it.pluginId == ai.id })
                assertFalse(
                    harness.db
                        .pluginPostWidgetDao()
                        .getByPostKeys(listOf(aiPost.pluginPostEntityKey()))
                        .any { it.pluginId == ai.id },
                )

                // Confirmed development reload grants timelineProcessor httpOrigins and stop() restores baseline (absent).
                val devDir = harness.writeDevelopmentDirectory(id = "org.example.devproc", httpOrigins = listOf("https://dev.example"))
                harness.subsystem.development.open(devDir.toString())
                harness.subsystem.development.reload(confirmed = false)
                assertNotNull(harness.subsystem.development.state.value.reviewHash)
                harness.subsystem.development.reload(confirmed = true)
                assertEquals(setOf("https://dev.example"), harness.currentSettings().enabledTimelineProcessorPlugins["org.example.devproc"])
                harness.subsystem.development.stop()
                assertFalse("org.example.devproc" in harness.currentSettings().enabledTimelineProcessorPlugins)
            }
        }
}

private val nextPostId = AtomicInteger(1)

private fun createSamplePost(
    id: String = "post-${nextPostId.getAndIncrement()}",
    host: String = "sampleHost",
    text: String? = null,
    link: String? = null,
    card: UiCard? = null,
    contentWarning: String? = null,
): UiTimelineV2.Post {
    val user = createSampleUser().copy(key = MicroBlogKey("sampleKey", host))
    val base =
        createSampleStatus(user).copy(
            statusKey = MicroBlogKey(id = id, host = host),
            createdAt = Instant.fromEpochSeconds(1_700_000_000L).toUi(),
            card = card,
            contentWarning =
                contentWarning?.let {
                    UiTranslatableText(
                        original = uiRichTextOf(renderRuns = listOf(RenderContent.Text(persistentListOf(RenderRun.Text(it))))),
                    )
                },
        )
    if (text == null && link == null) {
        return base
    }
    val runs =
        buildList {
            if (text != null) {
                add(RenderRun.Text(text))
            }
            if (link != null) {
                if (text != null) {
                    add(RenderRun.Text(" "))
                }
                add(RenderRun.Text(link, RenderTextStyle(link = link)))
            }
        }
    return base.copy(
        content =
            UiTranslatableText(
                original = uiRichTextOf(renderRuns = listOf(RenderContent.Text(persistentListOf(*runs.toTypedArray())))),
            ),
    )
}

private class InMemoryPluginPostWidgetDao(
    initialRows: List<DbPluginPostWidget> = emptyList(),
) : PluginPostWidgetDao {
    private val mutex = Mutex()
    private val rows = LinkedHashMap<Pair<String, String>, DbPluginPostWidget>()

    init {
        initialRows.forEach { row ->
            rows[row.postEntityKey to row.pluginId] = row
        }
    }

    override suspend fun getByPostKeysChunk(postEntityKeys: List<String>): List<DbPluginPostWidget> =
        mutex.withLock {
            val keySet = postEntityKeys.toSet()
            rows.values.filter { it.postEntityKey in keySet }
        }

    override suspend fun upsertAll(items: List<DbPluginPostWidget>) {
        mutex.withLock {
            items.forEach { item ->
                rows[item.postEntityKey to item.pluginId] = item
            }
        }
    }

    override suspend fun deleteByPluginId(pluginId: String) {
        mutex.withLock {
            val iterator = rows.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value.pluginId == pluginId) {
                    iterator.remove()
                }
            }
        }
    }

    override suspend fun deleteExpiredBefore(cutoffMillis: Long) {
        mutex.withLock {
            val iterator = rows.entries.iterator()
            while (iterator.hasNext()) {
                val expires = iterator.next().value.expiresAtMillis
                if (expires != null && expires < cutoffMillis) {
                    iterator.remove()
                }
            }
        }
    }

    override suspend fun trimOldestKeepingQuery(maxRows: Int) {
        mutex.withLock {
            if (rows.size <= maxRows) return@withLock
            val keepKeys =
                rows.values
                    .sortedWith(
                        compareByDescending<DbPluginPostWidget> { it.createdAtMillis }
                            .thenByDescending { it.postEntityKey }
                            .thenByDescending { it.pluginId },
                    ).take(maxRows)
                    .map { it.postEntityKey to it.pluginId }
                    .toSet()
            val iterator = rows.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().key !in keepKeys) {
                    iterator.remove()
                }
            }
        }
    }

    override suspend fun deleteAll() {
        mutex.withLock {
            rows.clear()
        }
    }
}

private class TestCacheDatabaseFacade(
    private val dao: InMemoryPluginPostWidgetDao,
) {
    fun pluginPostWidgetDao(): PluginPostWidgetDao = dao
}

private class GateEntry(
    val nonCancellable: Boolean,
) {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
}

private class TimelineProcessorTestHarness(
    val root: Path,
    val subsystem: PluginSubsystemV1,
    val presenter: PluginManagementPresenterV1,
    val authoring: PluginAuthoringServiceV1,
    val service: PluginTimelineProcessorServiceV1,
    val db: TestCacheDatabaseFacade,
    private val appSettingsState: MutableStateFlow<AppSettings>,
    private val gates: ConcurrentHashMap<String, GateEntry>,
    private val failurePredicates: ConcurrentHashMap<String, () -> Boolean>,
    private val invocationCounters: ConcurrentHashMap<String, AtomicInteger>,
    private val coldServiceFactory: () -> PluginTimelineProcessorServiceV1,
    val nowMillis: AtomicLong,
) {
    private val packageCounter = AtomicInteger(1)

    fun currentSettings(): AppSettings = appSettingsState.value

    fun invocationCount(pluginId: String): Int = invocationCounters[pluginId]?.get() ?: 0

    fun createColdService(): PluginTimelineProcessorServiceV1 = coldServiceFactory()

    fun writeDevelopmentDirectory(
        id: String,
        httpOrigins: List<String> = emptyList(),
        script: String =
            """
            defineFlareTimelineProcessorPlugin({
              async processBatch() { return { results: [] }; }
            });
            """.trimIndent(),
    ): Path {
        val dir = root / "dev-${packageCounter.getAndIncrement()}"
        val fs = subsystem.appFiles.fileSystem
        fs.createDirectories(dir / "assets")
        val manifest = buildTimelineProcessorManifestJson(id = id, httpOrigins = httpOrigins)
        fs.write(dir / "manifest.json") { writeUtf8(manifest) }
        fs.write(dir / "plugin.js") { writeUtf8(script) }
        fs.write(dir / "assets" / "icon.png") { write(TestFppFactory.validPng) }
        return dir
    }

    fun gateNextRuntimeCall(pluginId: String) {
        gates[pluginId] = GateEntry(nonCancellable = false)
    }

    fun gateNextRuntimeCallNonCancellable(pluginId: String) {
        gates[pluginId] = GateEntry(nonCancellable = true)
    }

    suspend fun awaitRuntimeCallEntered(pluginId: String) {
        val gate = checkNotNull(gates[pluginId]) { "No gate registered for $pluginId" }
        withContext(Dispatchers.Default) {
            withTimeout(5_000L) {
                gate.entered.await()
            }
        }
    }

    fun releaseGatedRuntimeCall(pluginId: String) {
        gates.remove(pluginId)?.release?.complete(Unit)
    }

    fun failPluginCallsWhen(
        pluginId: String,
        predicate: () -> Boolean,
    ) {
        failurePredicates[pluginId] = predicate
    }

    suspend fun awaitIdle() {
        service.awaitIdleForTesting()
    }

    fun writeSettingsOnlyFpp(
        id: String,
        version: String = "1.0.0",
    ): Path {
        val fppPath = root / "pkg-${packageCounter.getAndIncrement()}.fpp"
        val manifest =
            """
            {
              "apiVersion": 2,
              "id": "$id",
              "version": "$version",
              "name": "$id",
              "settings": { "entry": "settings/index.html" }
            }
            """.trimIndent()
        TestFppFactory.write(
            fppPath,
            listOf(
                "manifest.json" to manifest.encodeToByteArray(),
                "assets/icon.png" to TestFppFactory.validPng,
                "settings/index.html" to "<!doctype html><title>Settings</title>".encodeToByteArray(),
            ),
        )
        return fppPath
    }

    fun writeTimelineProcessorFpp(
        id: String,
        version: String = "1.0.0",
        containsAny: List<String> = emptyList(),
        regex: String? = null,
        linkHosts: List<String> = emptyList(),
        requireLinks: Boolean = false,
        httpOrigins: List<String> = emptyList(),
        mediaNetwork: Boolean = false,
        storageNamespaces: List<String>? = null,
        maxBatchSize: Int = 20,
        script: String,
    ): Path {
        val fppPath = root / "pkg-${packageCounter.getAndIncrement()}.fpp"
        val manifest =
            buildTimelineProcessorManifestJson(
                id = id,
                version = version,
                containsAny = containsAny,
                regex = regex,
                linkHosts = linkHosts,
                requireLinks = requireLinks,
                httpOrigins = httpOrigins,
                mediaNetwork = mediaNetwork,
                storageNamespaces = storageNamespaces,
                maxBatchSize = maxBatchSize,
            )
        TestFppFactory.write(
            fppPath,
            listOf(
                "manifest.json" to manifest.encodeToByteArray(),
                "plugin.js" to script.encodeToByteArray(),
                "assets/icon.png" to TestFppFactory.validPng,
            ),
        )
        return fppPath
    }

    suspend fun installAndConfirmTimelineProcessor(
        id: String,
        version: String = "1.0.0",
        containsAny: List<String> = emptyList(),
        regex: String? = null,
        linkHosts: List<String> = emptyList(),
        requireLinks: Boolean = false,
        httpOrigins: List<String> = emptyList(),
        mediaNetwork: Boolean = false,
        storageNamespaces: List<String>? = null,
        maxBatchSize: Int = 20,
        script: String,
    ): String {
        val fppPath =
            writeTimelineProcessorFpp(
                id = id,
                version = version,
                containsAny = containsAny,
                regex = regex,
                linkHosts = linkHosts,
                requireLinks = requireLinks,
                httpOrigins = httpOrigins,
                mediaNetwork = mediaNetwork,
                storageNamespaces = storageNamespaces,
                maxBatchSize = maxBatchSize,
                script = script,
            )
        presenter.inspect(fppPath.toString())
        presenter.confirmInstall()
        return id
    }

    suspend fun writeAiTimelineProcessor(
        pluginId: String,
        expectedRevision: Long,
        httpOrigins: List<String>,
        script: String =
            """
            defineFlareTimelineProcessorPlugin({
              async processBatch(req) {
                return { results: req.items.map(i => ({ itemId: i.itemId, cards: [{ id: "ai", body: [{ type: "text", text: "AI" }] }] })) };
              }
            });
            """.trimIndent(),
    ) {
        val targetVersion = "0.1.${expectedRevision + 1}"
        val manifest =
            buildTimelineProcessorManifestJson(
                id = pluginId,
                version = targetVersion,
                httpOrigins = httpOrigins,
            )
        authoring.write(
            pluginId = pluginId,
            expectedRevision = expectedRevision,
            files = mapOf("manifest.json" to manifest, "plugin.js" to script),
            iconUrl = null,
        )
        withContext(Dispatchers.Default) {
            withTimeout(5_000L) {
                authoring.plugins.first { list ->
                    val item = list.firstOrNull { it.id == pluginId }
                    item != null && item.revision == expectedRevision + 1 && item.phase == PluginAuthoringPhase.Applied
                }
                presenter.state.first { state ->
                    state.items.firstOrNull { it.pluginId == pluginId }?.version == targetVersion
                }
            }
        }
    }
}

private fun buildTimelineProcessorManifestJson(
    id: String,
    version: String = "1.0.0",
    containsAny: List<String> = emptyList(),
    regex: String? = null,
    linkHosts: List<String> = emptyList(),
    requireLinks: Boolean = false,
    httpOrigins: List<String> = emptyList(),
    mediaNetwork: Boolean = false,
    storageNamespaces: List<String>? = null,
    maxBatchSize: Int = 20,
): String =
    buildJsonObject {
        put("apiVersion", 2)
        put("id", id)
        put("version", version)
        put("name", id)
        put(
            "permissions",
            buildJsonObject {
                put("httpOrigins", JsonArray(httpOrigins.map(::JsonPrimitive)))
                put("mediaNetwork", mediaNetwork)
            },
        )
        put(
            "timelineProcessor",
            buildJsonObject {
                put("apiVersion", 1)
                put("maxBatchSize", maxBatchSize)
                if (containsAny.isNotEmpty() || regex != null || linkHosts.isNotEmpty() || requireLinks) {
                    put(
                        "filter",
                        buildJsonObject {
                            if (containsAny.isNotEmpty()) {
                                put("containsAny", JsonArray(containsAny.map(::JsonPrimitive)))
                            }
                            if (regex != null) {
                                put("regex", regex)
                            }
                            if (linkHosts.isNotEmpty()) {
                                put("linkHosts", JsonArray(linkHosts.map(::JsonPrimitive)))
                            }
                            if (requireLinks) {
                                put("requireLinks", true)
                            }
                        },
                    )
                }
                if (storageNamespaces != null) {
                    put(
                        "cache",
                        buildJsonObject {
                            put("storageNamespaces", JsonArray(storageNamespaces.map(::JsonPrimitive)))
                        },
                    )
                }
            },
        )
    }.toString()

private suspend fun withTimelineProcessorTestHarness(
    initialExpiredRowCount: Int = 0,
    block: suspend (TimelineProcessorTestHarness) -> Unit,
) {
    val files = FileSystem.SYSTEM
    val root = Files.createTempDirectory("timeline-processor-test").toOkioPath()
    val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val nowMillis = AtomicLong(1_750_000_000_000L)
    val appSettingsState = MutableStateFlow(AppSettings(version = "test"))
    val backing =
        object : TimelineProcessorSettingsBacking {
            override val enabledPlugins: Flow<Map<String, Set<String>>> =
                appSettingsState.map { it.enabledTimelineProcessorPlugins }

            override suspend fun updateEnabledPlugins(
                transform: (Map<String, Set<String>>) -> Map<String, Set<String>>,
            ): Map<String, Set<String>> {
                var result: Map<String, Set<String>> = emptyMap()
                appSettingsState.update { current ->
                    val next = transform(current.enabledTimelineProcessorPlugins)
                    result = next
                    current.copy(enabledTimelineProcessorPlugins = next)
                }
                return result
            }
        }
    val grantStore = PluginTimelineGrantStoreV1(backing = { backing }, scope = { serviceScope })
    val accounts = PluginTestAccounts()
    lateinit var subsystemRef: PluginSubsystemV1
    val registry by lazy { testPluginRegistry(subsystemRef, serviceScope) }
    val subsystem =
        PluginSubsystemV1(
            appFiles =
                object : AppFileStore {
                    override val fileSystem = files

                    override fun directory(name: String) = root / name
                },
            pendingStore =
                object : PluginOAuthPendingStoreV1 {
                    override suspend fun save(pending: PluginOAuthPendingV1) = Unit

                    override suspend fun load(flowId: String): PluginOAuthPendingV1? = null

                    override suspend fun consume(pending: PluginOAuthPendingV1) = false
                },
            registryProvider = { registry },
            accountsProvider = { accounts },
            scopeProvider = { serviceScope },
            timelineGrants = grantStore,
        ).also { subsystemRef = it }

    val expiredCutoffSeed =
        (1..initialExpiredRowCount).map { idx ->
            DbPluginPostWidget(
                postEntityKey = "expired_post_$idx",
                pluginId = "org.example.old",
                pluginCacheKey = "k",
                sourceHash = "h",
                cardsJson = "[]",
                createdAtMillis = nowMillis.get() - 10L * 24 * 60 * 60 * 1_000L,
                expiresAtMillis = nowMillis.get() - 8L * 24 * 60 * 60 * 1_000L,
            )
        }
    val dao = InMemoryPluginPostWidgetDao(expiredCutoffSeed)
    val gates = ConcurrentHashMap<String, GateEntry>()
    val failurePredicates = ConcurrentHashMap<String, () -> Boolean>()
    val invocationCounters = ConcurrentHashMap<String, AtomicInteger>()
    val invoker: TimelineBatchRuntimeInvoker = { plugin, key, context, request, validate ->
        val pluginId = plugin.installed.pluginId
        invocationCounters.computeIfAbsent(pluginId) { AtomicInteger(0) }.incrementAndGet()
        if (failurePredicates[pluginId]?.invoke() == true) {
            error("Simulated plugin failure for $pluginId")
        }
        val gate = gates[pluginId]
        if (gate != null && gate.nonCancellable) {
            withContext(NonCancellable) {
                val response =
                    subsystem.runtimePool.invokeJson(
                        plugin = plugin,
                        key = key,
                        context = context,
                        method = "timelineProcessor.processBatch",
                        request = request,
                        timeout = PluginCallTimeoutV1.Extended,
                        validate = validate,
                    )
                gate.entered.complete(Unit)
                gate.release.await()
                gates.remove(pluginId, gate)
                response
            }
        } else {
            if (gate != null) {
                gate.entered.complete(Unit)
                gate.release.await()
                gates.remove(pluginId, gate)
            }
            subsystem.runtimePool.invokeJson(
                plugin = plugin,
                key = key,
                context = context,
                method = "timelineProcessor.processBatch",
                request = request,
                timeout = PluginCallTimeoutV1.Extended,
                validate = validate,
            )
        }
    }
    val createService = {
        PluginTimelineProcessorServiceV1(
            subsystem = subsystem,
            dao = dao,
            grants = grantStore,
            scope = serviceScope,
            ioDispatcher = Dispatchers.Default,
            workDispatcher = Dispatchers.Default,
            nowEpochMillis = nowMillis::get,
            coalesceDelayMillis = 10L,
            runtimeInvoker = invoker,
        )
    }
    val service = createService()
    val presenter =
        PluginManagementPresenterV1(
            subsystem = subsystem,
            scope = serviceScope,
            registry = { registry },
            accounts = { accounts },
        )
    try {
        block(
            TimelineProcessorTestHarness(
                root = root,
                subsystem = subsystem,
                presenter = presenter,
                authoring = subsystem.authoring,
                service = service,
                db = TestCacheDatabaseFacade(dao),
                appSettingsState = appSettingsState,
                gates = gates,
                failurePredicates = failurePredicates,
                invocationCounters = invocationCounters,
                coldServiceFactory = createService,
                nowMillis = nowMillis,
            ),
        )
    } finally {
        presenter.close()
        service.close()
        serviceScope.cancel()
        subsystem.runtimePool.close()
        files.deleteRecursively(root, mustExist = false)
    }
}
