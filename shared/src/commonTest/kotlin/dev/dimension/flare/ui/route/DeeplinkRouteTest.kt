package dev.dimension.flare.ui.route

import dev.dimension.flare.data.datasource.microblog.MicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.UnavailableMicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.capabilities
import dev.dimension.flare.data.datasource.microblog.datasource.TimelinePageDataSource
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.datasource.microblog.paging.notSupported
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.model.UiTimelineV2
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class DeeplinkRouteTest {
    @Test
    fun temporaryTimelinePageRoundTripsWithItsBoundAccountAndParameters() {
        val route =
            DeeplinkRoute.Timeline.Page(
                accountType = AccountType.Specific(MicroBlogKey("me", "example.com")),
                pluginId = "dev.example.plugin",
                platformId = "ExamplePlugin",
                packageHash = "package-hash",
                pageId = "replies",
                title = UiText.Raw("回复"),
                parameters = mapOf("parentId" to "comment:123", "sort" to "oldest"),
            )
        assertEquals(route, DeeplinkRoute.parse(route.toUri()))
    }

    @Test
    fun temporaryTimelinePagePreservesDetailStatusKeyAndLegacyCapability() {
        val account = MicroBlogKey("23", "m.weibo.cn")
        val detailKey = MicroBlogKey("comment-1", "m.weibo.cn")
        val route =
            DeeplinkRoute.Timeline.Page(
                accountType = AccountType.Specific(account),
                pluginId = "VVo",
                platformId = "VVo",
                packageHash = "",
                pageId = "comment_replies",
                title = UiText.Raw("Comments"),
                parameters = mapOf("commentId" to "comment-1"),
                detailStatusKey = detailKey,
            )
        val parsed = assertIs<DeeplinkRoute.Timeline.Page>(DeeplinkRoute.parse(route.toUri()))
        assertEquals(detailKey, parsed.detailStatusKey)

        val source =
            object : MicroblogDataSource by UnavailableMicroblogDataSource, TimelinePageDataSource {
                override val pluginId: String = "VVo"
                override val packageHash: String = ""
                override val revision: StateFlow<Long> = MutableStateFlow(0L)

                override fun timelinePage(
                    pageId: String,
                    parameters: Map<String, String>,
                ): RemoteLoader<UiTimelineV2> = notSupported()
            }
        assertSame(source, source.capabilities.timelinePage)
    }

    @Test
    fun timelineSourceRoundTrips() {
        val route = DeeplinkRoute.Timeline.Source(loaderKey = "timeline-loader-key")

        assertEquals(route, DeeplinkRoute.parse(route.toUri()))
    }

    private val activeAccountType = AccountType.Specific(MicroBlogKey("active", "example.com"))

    @Test
    fun testLogin() {
        val route = DeeplinkRoute.Login
        val uri = route.toUri()
        val parsed = DeeplinkRoute.parse(uri)
        if (route != parsed) {
            println("FAILURE DEBUG: Expected=$route, Actual=$parsed, URI=$uri")
        }
        assertEquals(route, parsed)
    }

    @Test
    fun testRelogin() {
        val route =
            DeeplinkRoute.Relogin(
                accountKey = MicroBlogKey("expired", "example.com"),
                platformId = "Mastodon",
            )
        val uri = route.toUri()
        val parsed = DeeplinkRoute.parse(uri)
        if (route != parsed) {
            println("FAILURE DEBUG: Expected=$route, Actual=$parsed, URI=$uri")
        }
        assertEquals(route, parsed)
    }

    @Test
    fun testStatusDetail() {
        val route =
            DeeplinkRoute.Status.Detail(
                statusKey = MicroBlogKey("status123", "example.com"),
                accountType = activeAccountType,
            )
        val uri = route.toUri()
        val parsed = DeeplinkRoute.parse(uri)
        assertEquals(route, parsed)
    }

    @Test
    fun weiboStatusUsesGenericDetailWebPathAndBumpsCacheVersionAfterLegacyRouteRemoval() {
        val route =
            DeeplinkRoute.Status.Detail(
                statusKey = MicroBlogKey("5357831171867297", "m.weibo.cn"),
                accountType = AccountType.Specific(MicroBlogKey("23", "m.weibo.cn")),
            )
        assertEquals("/23@m.weibo.cn/status/5357831171867297@m.weibo.cn", route.toWebPath())
        assertEquals(56, dev.dimension.flare.data.database.cache.CACHE_DATABASE_VERSION)
    }

    @Test
    fun testGalleryDetail() {
        val route =
            DeeplinkRoute.Gallery.Detail(
                statusKey = MicroBlogKey("status123", "example.com"),
                accountType = activeAccountType,
            )
        val uri = route.toUri()
        val parsed = DeeplinkRoute.parse(uri)
        assertEquals(route, parsed)
    }

    @Test
    fun testStatusAddReaction() {
        val route =
            DeeplinkRoute.Status.AddReaction(
                statusKey = MicroBlogKey("status789", "example.com"),
                accountType = activeAccountType,
            )
        val uri = route.toUri()
        val parsed = DeeplinkRoute.parse(uri)
        assertEquals(route, parsed)
    }

    @Test
    fun testStatusAltText() {
        val route = DeeplinkRoute.Status.AltText(text = "Some alt text")
        val uri = route.toUri()
        val parsed = DeeplinkRoute.parse(uri)
        assertEquals(route, parsed)
    }

    @Test
    fun testStatusBlueskyReport() {
        val route =
            DeeplinkRoute.Status.BlueskyReport(
                statusKey = MicroBlogKey("statusBS", "bsky.app"),
                accountType = activeAccountType,
            )
        val uri = route.toUri()
        val parsed = DeeplinkRoute.parse(uri)
        assertEquals(route, parsed)
    }

    @Test
    fun testStatusDeleteConfirm() {
        val route =
            DeeplinkRoute.Status.DeleteConfirm(
                statusKey = MicroBlogKey("delStatus", "example.com"),
                accountType = activeAccountType,
            )
        val uri = route.toUri()
        val parsed = DeeplinkRoute.parse(uri)
        assertEquals(route, parsed)
    }

    @Test
    fun testStatusMastodonReport() {
        val route =
            DeeplinkRoute.Status.MastodonReport(
                userKey = MicroBlogKey("user1", "mstdn.io"),
                statusKey = MicroBlogKey("status1", "mstdn.io"),
                accountType = activeAccountType,
            )
        val uri = route.toUri()
        val parsed = DeeplinkRoute.parse(uri)
        assertEquals(route, parsed)
    }

    @Test
    fun testStatusMisskeyReport() {
        val route =
            DeeplinkRoute.Status.MisskeyReport(
                userKey = MicroBlogKey("userMK", "misskey.io"),
                statusKey = null,
                accountType = AccountType.Guest,
            )
        val uri = route.toUri()
        val parsed = DeeplinkRoute.parse(uri)
        assertEquals(route, parsed)
    }

    @Test
    fun testRssDetail() {
        val route = DeeplinkRoute.Rss.Detail(url = "https://example.com/rss")
        val uri = route.toUri()
        val parsed = DeeplinkRoute.parse(uri)
        assertEquals(route, parsed)
    }

    @Test
    fun testSearch() {
        val route =
            DeeplinkRoute.Search(
                accountType = activeAccountType,
                query = "kotlin",
            )
        val uri = route.toUri()
        val parsed = DeeplinkRoute.parse(uri)
        assertEquals(route, parsed)
    }

    @Test
    fun testComposeNew() {
        val route = DeeplinkRoute.Compose.New
        val uri = route.toUri()
        val parsed = DeeplinkRoute.parse(uri)
        assertEquals(route, parsed)
    }

    @Test
    fun testComposeReply() {
        val route =
            DeeplinkRoute.Compose.Reply(
                accountKey = MicroBlogKey("acc1", "example.com"),
                statusKey = MicroBlogKey("statusR", "example.com"),
            )
        val uri = route.toUri()
        val parsed = DeeplinkRoute.parse(uri)
        assertEquals(route, parsed)
    }

    @Test
    fun testComposeQuote() {
        val route =
            DeeplinkRoute.Compose.Quote(
                accountKey = MicroBlogKey("acc1", "example.com"),
                statusKey = MicroBlogKey("statusQ", "example.com"),
            )
        val uri = route.toUri()
        val parsed = DeeplinkRoute.parse(uri)
        assertEquals(route, parsed)
    }

    @Test
    fun testComposeVVOReplyComment() {
        val route =
            DeeplinkRoute.Compose.VVOReplyComment(
                accountKey = MicroBlogKey("accVVO", "vvo.com"),
                replyTo = MicroBlogKey("replyTo", "vvo.com"),
                rootId = "root123",
            )
        val uri = route.toUri()
        val parsed = DeeplinkRoute.parse(uri)
        assertEquals(route, parsed)
    }

    @Test
    fun testMediaImage() {
        val route =
            DeeplinkRoute.Media.Image(
                uri = "https://image.com/1.jpg",
                previewUrl = "https://image.com/1_preview.jpg",
            )
        val uri = route.toUri()
        val parsed = DeeplinkRoute.parse(uri)
        assertEquals(route, parsed)
    }

    @Test
    fun testMediaStatusMedia() {
        val route =
            DeeplinkRoute.Media.StatusMedia(
                statusKey = MicroBlogKey("stMedia", "example.com"),
                accountType = activeAccountType,
                index = 2,
                preview = "preview_data",
                aspectRatio = 9f / 30f,
                previewIsImage = false,
            )
        val uri = route.toUri()
        val parsed = DeeplinkRoute.parse(uri)
        assertEquals(route, parsed)
    }

    @Test
    fun testMediaPodcast() {
        val route =
            DeeplinkRoute.Media.Podcast(
                accountType = activeAccountType,
                id = "podcast123",
            )
        val uri = route.toUri()
        val parsed = DeeplinkRoute.parse(uri)
        assertEquals(route, parsed)
    }

    @Test
    fun testProfileUser() {
        val route =
            DeeplinkRoute.Profile.User(
                accountType = activeAccountType,
                userKey = MicroBlogKey("userP", "example.com"),
            )
        val uri = route.toUri()
        val parsed = DeeplinkRoute.parse(uri)
        assertEquals(route, parsed)
    }

    @Test
    fun testProfileUserNameWithHost() {
        val route =
            DeeplinkRoute.Profile.UserNameWithHost(
                accountType = activeAccountType,
                userName = "user",
                host = "example.com",
            )
        val uri = route.toUri()
        val parsed = DeeplinkRoute.parse(uri)
        assertEquals(route, parsed)
    }

    @Test
    fun testProfileUserWebPath() {
        val route =
            DeeplinkRoute.Profile.User(
                accountType = AccountType.Specific(MicroBlogKey("did:plc:alice", "bsky.social")),
                userKey = MicroBlogKey("did:plc:bob", "bsky.social"),
            )
        assertEquals(
            "/did%3Aplc%3Aalice@bsky.social/profile/did%3Aplc%3Abob@bsky.social",
            route.toWebPath(),
        )
    }

    @Test
    fun testProfileUserNameWithHostWebPath() {
        val route =
            DeeplinkRoute.Profile.UserNameWithHost(
                accountType = AccountType.GuestHost("mastodon.example"),
                userName = "alice+news",
                host = "mastodon.example",
            )
        assertEquals(
            "/guest@mastodon.example/profile/by-handle/mastodon.example/alice%2Bnews",
            route.toWebPath(),
        )
    }

    @Test
    fun testSearchWebPath() {
        val route =
            DeeplinkRoute.Search(
                accountType = activeAccountType,
                query = "#flare search",
            )
        assertEquals(
            "/search?account=active@example.com&q=%23flare%20search",
            route.toWebPath(),
        )
    }

    @Test
    fun testDeepLinkAccountPicker() {
        val route =
            DeeplinkRoute.DeepLinkAccountPicker(
                originalUrl = "https://original.com",
                data =
                    persistentMapOf(
                        MicroBlogKey("acc1", "host1") to DeeplinkRoute.Login,
                    ),
            )
        val uri = route.toUri()
        val parsed = DeeplinkRoute.parse(uri)
        assertEquals(route, parsed)
    }

    @Test
    fun testOpenLinkDirectly() {
        val route = DeeplinkRoute.OpenLinkDirectly(url = "https://direct.com")
        val uri = route.toUri()
        val parsed = DeeplinkRoute.parse(uri)
        assertEquals(route, parsed)
    }

    @Test
    fun testEditUserList() {
        val route =
            DeeplinkRoute.EditUserList(
                accountKey = MicroBlogKey("acc1", "host1"),
                userKey = MicroBlogKey("user1", "host1"),
            )
        val uri = route.toUri()
        val parsed = DeeplinkRoute.parse(uri)
        assertEquals(route, parsed)
    }

    @Test
    fun testCallbackConstants() {
        assertEquals("flare://Callback/SignIn/Mastodon", DeeplinkRoute.Companion.Callback.MASTODON)
        assertEquals("flare://Callback/SignIn/Misskey", DeeplinkRoute.Companion.Callback.MISSKEY)
        assertEquals("flare://Callback/SignIn/Bluesky", DeeplinkRoute.Companion.Callback.BLUESKY)
    }

    @Test
    fun testInvalidUri() {
        assertNull(DeeplinkRoute.parse("invalid://uri"))
        assertNull(DeeplinkRoute.parse("flare://invalid_hex"))
    }

    @Test
    fun testLoginJson() {
        val route = DeeplinkRoute.Login
        val json =
            kotlinx.serialization.json.Json
                .encodeToString(DeeplinkRoute.serializer(), route)
        val parsed =
            kotlinx.serialization.json.Json
                .decodeFromString(DeeplinkRoute.serializer(), json)
        assertEquals(route, parsed)
    }
}
