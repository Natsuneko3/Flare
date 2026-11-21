package dev.dimension.flare.feature.plugin.adapter

import dev.dimension.flare.data.datasource.microblog.MicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.model.TimelineDisplayMode
import dev.dimension.flare.data.model.appearance.AppearanceKeys
import dev.dimension.flare.data.model.tab.TimelineLoaderContext
import dev.dimension.flare.data.model.tab.TimelineSpec
import dev.dimension.flare.data.model.tab.createLoader
import dev.dimension.flare.data.model.tab.toUiTimelineTabItem
import dev.dimension.flare.data.repository.AccountMicroblogDataSource
import dev.dimension.flare.data.repository.AccountService
import dev.dimension.flare.feature.plugin.abi.PluginJsonV1
import dev.dimension.flare.feature.plugin.host.PluginHttpTransport
import dev.dimension.flare.feature.plugin.host.PluginTransportRequestV1
import dev.dimension.flare.feature.plugin.host.PluginTransportResponseV1
import dev.dimension.flare.feature.plugin.installer.PluginInstaller
import dev.dimension.flare.feature.plugin.installer.TestFppFactory
import dev.dimension.flare.feature.plugin.lifecycle.PluginStateStore
import dev.dimension.flare.feature.plugin.login.PluginFormLoginCoordinatorV1
import dev.dimension.flare.feature.plugin.login.PluginOAuthLoginCoordinatorV1
import dev.dimension.flare.feature.plugin.login.PluginOAuthPendingStoreV1
import dev.dimension.flare.feature.plugin.login.PluginOAuthPendingV1
import dev.dimension.flare.feature.plugin.login.PluginWebCookieLoginCoordinatorV1
import dev.dimension.flare.feature.plugin.runtime.PluginRuntimePool
import dev.dimension.flare.feature.plugin.runtime.PluginRuntimeUnavailableException
import dev.dimension.flare.feature.plugin.wire.PluginAccountCredentialV1
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.PlatformSpec
import dev.dimension.flare.ui.model.UiAccount
import dev.dimension.flare.ui.model.UiIcon
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.asType
import dev.dimension.flare.ui.presenter.login.LoginPlatformProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.SYSTEM
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginPlatformSpecSourceV1Test {
    @Test
    fun detectorImagesRequireTheRunningPackagesMediaPermission() =
        runBlocking {
            for (image in listOf("iconUrl", "bannerUrl")) {
                withPlatform(mediaNetwork = false, metadata = "$image: request.origin + '/image.png'") { platform ->
                    assertFailsWith<PluginRuntimeUnavailableException> { platform.detector.detect("instance.example") }
                    val error = assertFailsWith<PluginRuntimeUnavailableException> { platform.instanceMetadata("instance.example") }
                    assertEquals("Plugin has no media network permission", assertIs<IllegalArgumentException>(error.cause).message)
                }
            }
        }

    @Test
    fun grantedDetectorImagesAndTextOnlyInstancesRemainAvailable() =
        runBlocking {
            withPlatform(
                mediaNetwork = true,
                metadata = "iconUrl: request.origin + '/icon.png', bannerUrl: request.origin + '/banner.png'",
            ) { platform ->
                assertNotNull(platform.detector.detect("instance.example"))
                val instance = platform.instanceMetadata("instance.example").instance
                assertEquals("https://instance.example/icon.png", instance.iconUrl)
                assertEquals("https://instance.example/banner.png", instance.bannerUrl)
            }
            withPlatform(mediaNetwork = false, metadata = "description: 'Text only'") { platform ->
                assertNotNull(platform.detector.detect("instance.example"))
                val instance = platform.instanceMetadata("instance.example").instance
                assertEquals("Text only", instance.description)
                assertNull(instance.iconUrl)
                assertNull(instance.bannerUrl)
            }
        }

    @Test
    fun apiV2RestorationRetainsPluginStateButRotatesPrivateSessionMetadata() =
        runBlocking {
            withPlatform(mediaNetwork = false, metadata = "description: 'Restore'", apiVersion = 2) { provider ->
                val saved = """{
              "snapshot": {
                "pluginId": "dev.dimension.flare.test.plugin", "platformId": "TestPlugin", "packageHash": "${"a".repeat(64)}",
                "origin": "https://instance.example", "accountId": "me",
                "profile": { "key": { "id": "me", "host": "instance.example" }, "handle": "me", "displayName": "Me" },
                "manifestCapabilities": { "flare.datasource.timeline/v1": ["page"] },
                "negotiatedCapabilities": { "flare.datasource.timeline/v1": ["page"] }, "credentialSchemaVersion": 1
              }, "credential": { "token": "kept" }, "sessionEpoch": "saved-epoch", "credentialRevision": "saved-revision"
            }"""
                val platform = provider as PlatformSpec
                val original = PluginJsonV1.decodeFromString(PluginAccountCredentialV1.serializer(), saved)
                val first = PluginJsonV1.decodeFromString(PluginAccountCredentialV1.serializer(), platform.restoreCredential(saved))
                val second = PluginJsonV1.decodeFromString(PluginAccountCredentialV1.serializer(), platform.restoreCredential(saved))
                assertEquals(original.snapshot, first.snapshot)
                assertEquals(original.credential, first.credential)
                assertNotNull(first.sessionEpoch)
                assertNotNull(first.credentialRevision)
                assertNotEquals(original.sessionEpoch, first.sessionEpoch)
                assertNotEquals(first.sessionEpoch, second.sessionEpoch)
                assertNotEquals(first.credentialRevision, second.credentialRevision)
            }
        }

    @Test
    fun standaloneGuestOriginExposesGuestTabsAndRoutesThroughGuestPluginAccountType() =
        runBlocking {
            withPlatform(mediaNetwork = false, metadata = "description: 'Non-standalone'", apiVersion = 2) { provider ->
                val spec = provider as PlatformSpec
                assertNull(spec.standaloneGuestHost)
                assertTrue(spec.guestDefaultTabs.isEmpty())
                assertTrue(spec.guestTimelineTabs.isEmpty())
                assertTrue(spec.timelineSpecs.none { ":guest-timeline:" in it.id })
            }

            val standaloneManifest =
                """
                {
                  "schemaVersion": 1,
                  "apiVersion": 2,
                  "id": "dev.dimension.flare.test.manga",
                  "version": "1.0.0",
                  "defaultLocale": "en",
                  "name": "Manga plugin",
                  "platform": {
                    "id": "MangaExample",
                    "name": "Manga Example",
                    "guest": { "enabled": true, "origin": "https://manga.example" },
                    "capabilities": {
                      "flare.datasource.timeline/v1": {
                        "operations": { "page": { "directions": ["refresh", "older"] } }
                      }
                    },
                    "timelines": [
                      { "id": "latest", "title": "Latest", "defaultForNewAccount": true },
                      { "id": "popular", "title": "Popular", "display": "Grid", "icon": "Featured" }
                    ]
                  }
                }
                """.trimIndent()
            val standaloneScript =
                """
                defineFlareSocialPlugin({
                  capabilities: {
                    timeline: {
                      async page(request) {
                        return {
                          items: [{
                            type: "post",
                            post: {
                              key: { id: request.timelineId + "-1", host: "manga.example" },
                              createdAt: "2026-10-27T00:00:00Z",
                              content: { format: "plain", value: "Chapter 1" }
                            }
                          }]
                        };
                      }
                    }
                  }
                });
                """.trimIndent()

            withCustomPlatform(standaloneManifest, standaloneScript) { spec ->
                assertEquals("manga.example", spec.standaloneGuestHost)
                assertEquals(2, spec.timelineSpecs.count { ":guest-timeline:" in it.id })
                assertEquals(1, spec.guestDefaultTabs.size)
                assertEquals(2, spec.guestTimelineTabs.size)

                val defaultTab = spec.guestDefaultTabs.single()
                assertEquals(spec.metadata.displayNameText, defaultTab.title)
                assertEquals(spec.metadata.icon, defaultTab.icon)
                assertEquals(
                    TimelineSpec.GuestPluginResourceData("MangaExample", "manga.example", "latest"),
                    defaultTab.target.data,
                )
                val tabItem = defaultTab.toUiTimelineTabItem()
                assertEquals(
                    "plugin:dev.dimension.flare.test.manga:MangaExample:schema1:guest-timeline:latest:MangaExample:manga.example:latest",
                    tabItem.id,
                )

                val allTabs = spec.guestTimelineTabs
                assertEquals(UiText.Raw("Latest"), allTabs[0].title)
                assertEquals(spec.metadata.icon, allTabs[0].icon)
                assertEquals(UiText.Raw("Popular"), allTabs[1].title)
                assertEquals(UiIcon.Featured.asType(), allTabs[1].icon)
                assertEquals(TimelineDisplayMode.Gallery, allTabs[1].appearancePatch?.get(AppearanceKeys.TimelineDisplayMode))

                val requestedAccounts = mutableListOf<AccountType>()
                val service =
                    object : AccountService {
                        override suspend fun deleteAccounts(
                            platformId: String,
                            ownsCredential: (String) -> Boolean,
                            beforeDelete: suspend () -> Unit,
                        ) = error("Unused")

                        override fun accountServiceFlow(accountType: AccountType): Flow<MicroblogDataSource> {
                            requestedAccounts += accountType
                            val guest = assertIs<AccountType.GuestPlugin>(accountType)
                            assertEquals("MangaExample", guest.platformId)
                            return flowOf(spec.guestDataSource(guest.host, "en"))
                        }

                        override fun allAccountServicesFlow(): Flow<List<AccountMicroblogDataSource>> = error("Unused")

                        override suspend fun <T : Any> addAccount(
                            account: UiAccount,
                            credential: T,
                            serializer: KSerializer<T>,
                            beforeCommit: () -> Unit,
                        ) = error("Unused")

                        override fun <T : Any> credentialFlow(
                            accountKey: MicroBlogKey,
                            serializer: KSerializer<T>,
                        ): Flow<T> = error("Unused")

                        override fun <T : Any> updateCredential(
                            accountKey: MicroBlogKey,
                            credential: T,
                            serializer: KSerializer<T>,
                        ): Job = error("Unused")
                    }

                val loader = defaultTab.createLoader(TimelineLoaderContext(service)).first()
                assertEquals(listOf<AccountType>(AccountType.GuestPlugin("MangaExample", "manga.example")), requestedAccounts)
                val page = loader.load(20, PagingRequest.Refresh)
                val item = assertIs<UiTimelineV2.TimelinePostItem>(page.data.single())
                assertEquals("latest-1", item.statusKey.id)
                assertEquals(AccountType.GuestPlugin("MangaExample", "manga.example"), item.accountType)
            }
        }

    private suspend fun withCustomPlatform(
        manifest: String,
        script: String,
        block: suspend (PlatformSpec) -> Unit,
    ) {
        val root = Files.createTempDirectory("plugin-standalone-guest").toOkioPath()
        val files = FileSystem.SYSTEM
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pool =
            PluginRuntimePool(
                files,
                object : PluginHttpTransport {
                    override suspend fun execute(request: PluginTransportRequestV1): PluginTransportResponseV1 =
                        error("Standalone test does not use HTTP")
                },
            )
        try {
            val archive = root / "plugin.fpp"
            TestFppFactory.write(archive, TestFppFactory.validEntries(manifest = manifest, script = script))
            val store = PluginStateStore.open(files, root / "state")
            val installer = PluginInstaller(files, store)
            installer.commit(installer.inspect(archive), confirmed = true, activate = store::install)
            val snapshot = PluginStateStore.open(files, root / "state").running
            val pending =
                object : PluginOAuthPendingStoreV1 {
                    override suspend fun save(pending: PluginOAuthPendingV1): Unit = error("Unused")

                    override suspend fun load(flowId: String): PluginOAuthPendingV1? = null

                    override suspend fun consume(pending: PluginOAuthPendingV1): Boolean = false
                }
            val platform =
                PluginPlatformSpecSourceV1(
                    snapshot,
                    pool,
                    PluginOAuthLoginCoordinatorV1(pool, pending, runningPlugin = snapshot.plugins::get),
                    PluginFormLoginCoordinatorV1(pool),
                    PluginWebCookieLoginCoordinatorV1(pool),
                    scope,
                ).load(emptySet()).single()
            block(platform)
        } finally {
            scope.cancel()
            pool.close()
            files.deleteRecursively(root, mustExist = false)
        }
    }

    private suspend fun withPlatform(
        mediaNetwork: Boolean,
        metadata: String,
        apiVersion: Int = 1,
        block: suspend (LoginPlatformProvider) -> Unit,
    ) {
        val root = Files.createTempDirectory("plugin-detector-media").toOkioPath()
        val files = FileSystem.SYSTEM
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pool =
            PluginRuntimePool(
                files,
                object : PluginHttpTransport {
                    override suspend fun execute(request: PluginTransportRequestV1): PluginTransportResponseV1 =
                        error("The detector does not need HTTP")
                },
            )
        try {
            val archive = root / "plugin.fpp"
            val manifest =
                TestFppFactory.validManifest.replace("\"apiVersion\": 1", "\"apiVersion\": $apiVersion").replace(
                    "\"platform\": {",
                    "\"permissions\": {\"mediaNetwork\": $mediaNetwork}, \"platform\": {",
                )
            val script =
                TestFppFactory.validScript.replace(
                    "return { matched: true };",
                    "return { match: 'exact', canonicalOrigin: request.origin, instance: { domain: 'instance.example', $metadata } };",
                )
            TestFppFactory.write(archive, TestFppFactory.validEntries(manifest = manifest, script = script))
            val store = PluginStateStore.open(files, root / "state")
            val installer = PluginInstaller(files, store)
            installer.commit(installer.inspect(archive), confirmed = true, activate = store::install)
            val snapshot = PluginStateStore.open(files, root / "state").running
            val pending =
                object : PluginOAuthPendingStoreV1 {
                    override suspend fun save(pending: PluginOAuthPendingV1): Unit = error("Unused")

                    override suspend fun load(flowId: String): PluginOAuthPendingV1? = null

                    override suspend fun consume(pending: PluginOAuthPendingV1): Boolean = false
                }
            val platform =
                PluginPlatformSpecSourceV1(
                    snapshot,
                    pool,
                    PluginOAuthLoginCoordinatorV1(pool, pending, runningPlugin = snapshot.plugins::get),
                    PluginFormLoginCoordinatorV1(pool),
                    PluginWebCookieLoginCoordinatorV1(pool),
                    scope,
                ).load(emptySet()).single() as LoginPlatformProvider
            block(platform)
        } finally {
            scope.cancel()
            pool.close()
            files.deleteRecursively(root, mustExist = false)
        }
    }
}
