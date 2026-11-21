package dev.dimension.flare.feature.plugin.adapter

import dev.dimension.flare.data.datasource.microblog.ActionMenu
import dev.dimension.flare.data.datasource.microblog.ComposeType
import dev.dimension.flare.data.datasource.microblog.PostActionFamily
import dev.dimension.flare.data.model.IconType
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.feature.plugin.abi.PluginAbiV1
import dev.dimension.flare.feature.plugin.abi.PluginJsonV1
import dev.dimension.flare.feature.plugin.wire.ActionDescriptorV1
import dev.dimension.flare.feature.plugin.wire.ArticleAttributionV1
import dev.dimension.flare.feature.plugin.wire.ArticleBlockV1
import dev.dimension.flare.feature.plugin.wire.ArticleV1
import dev.dimension.flare.feature.plugin.wire.DirectMessageBodyV1
import dev.dimension.flare.feature.plugin.wire.DirectMessageV1
import dev.dimension.flare.feature.plugin.wire.EntityKeyV1
import dev.dimension.flare.feature.plugin.wire.FeedSourceV1
import dev.dimension.flare.feature.plugin.wire.FeedV1
import dev.dimension.flare.feature.plugin.wire.GalleryV1
import dev.dimension.flare.feature.plugin.wire.MangaChapterGroupV1
import dev.dimension.flare.feature.plugin.wire.MangaChapterV1
import dev.dimension.flare.feature.plugin.wire.MangaPageV1
import dev.dimension.flare.feature.plugin.wire.MangaPagesV1
import dev.dimension.flare.feature.plugin.wire.MangaReadingDirectionV1
import dev.dimension.flare.feature.plugin.wire.MangaStatusV1
import dev.dimension.flare.feature.plugin.wire.MangaTagGroupV1
import dev.dimension.flare.feature.plugin.wire.MangaTagV1
import dev.dimension.flare.feature.plugin.wire.MangaV1
import dev.dimension.flare.feature.plugin.wire.MediaClickPolicyV1
import dev.dimension.flare.feature.plugin.wire.MediaTypeV1
import dev.dimension.flare.feature.plugin.wire.MediaV1
import dev.dimension.flare.feature.plugin.wire.MessageV1
import dev.dimension.flare.feature.plugin.wire.NavigationTargetV1
import dev.dimension.flare.feature.plugin.wire.PostDetailTypeV1
import dev.dimension.flare.feature.plugin.wire.PostPresentationV1
import dev.dimension.flare.feature.plugin.wire.PostV1
import dev.dimension.flare.feature.plugin.wire.ProfileV1
import dev.dimension.flare.feature.plugin.wire.RelationV1
import dev.dimension.flare.feature.plugin.wire.RichTextFormatV1
import dev.dimension.flare.feature.plugin.wire.RichTextV1
import dev.dimension.flare.feature.plugin.wire.SemanticActionV1
import dev.dimension.flare.feature.plugin.wire.SocialListV1
import dev.dimension.flare.feature.plugin.wire.TimelineItemV1
import dev.dimension.flare.feature.plugin.wire.VisibilityV1
import dev.dimension.flare.feature.plugin.wire.WireTextV1
import dev.dimension.flare.feature.plugin.wire.requireValid
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.MangaReadingDirection
import dev.dimension.flare.ui.model.UiArticleAuthor
import dev.dimension.flare.ui.model.UiArticleBlock
import dev.dimension.flare.ui.model.UiDMItem
import dev.dimension.flare.ui.model.UiIcon
import dev.dimension.flare.ui.model.UiMangaPage
import dev.dimension.flare.ui.model.UiMangaStatus
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.UiNumber
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.asType
import dev.dimension.flare.ui.model.withProxy
import dev.dimension.flare.ui.route.DeeplinkRoute
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class PluginWireMapperV1Test {
    private val mapper = createMapper(MicroBlogKey("me", "local.example"))
    private val author = ProfileV1(EntityKeyV1("author", "remote.example"), "alice", "Alice")
    private val post =
        PostV1(
            key = EntityKeyV1("post-1", "remote.example"),
            author = author,
            createdAt = "2026-10-27T00:00:00Z",
            content = RichTextV1(value = "Preview"),
        )

    @Test
    fun postSubtitleAudienceAndCountsReachExistingControls() {
        val target = NavigationTargetV1.TimelinePage("replies", WireTextV1(value = "Replies"))
        val value =
            post.copy(
                authorSubtitle = "Published in Beijing",
                extendedVisibility = VisibilityV1.Friends,
                target = target,
                mediaClickPolicy = MediaClickPolicyV1.OpenTarget,
                favouritesCount = 9,
                repostsCount = 12,
                repliesCount = 5,
                actions =
                    listOf(
                        ActionDescriptorV1(SemanticActionV1.Favourite, count = 0),
                        ActionDescriptorV1(SemanticActionV1.Unrepost),
                        ActionDescriptorV1(SemanticActionV1.Reply),
                    ),
            )
        val mapped = mapper.post(value).post
        assertEquals("Published in Beijing", mapped.authorSubtitle)
        assertEquals(UiTimelineV2.Post.Visibility.Friends, mapped.visibility)
        assertEquals(UiTimelineV2.Post.MediaClickPolicy.OpenPostClickEvent, mapped.mediaClickPolicy)
        val actions = mapped.actions.filterIsInstance<ActionMenu.Item>().associate { it.actionFamily to it.count }
        assertEquals(UiNumber(0), actions[PostActionFamily.Like])
        assertEquals(UiNumber(12), actions[PostActionFamily.Repost])
        assertEquals(UiNumber(5), actions[PostActionFamily.Reply])
        val encoded = PluginJsonV1.encodeToJsonElement(value).jsonObject
        assertNull(encoded["visibility"])
        assertEquals(JsonPrimitive("friends"), encoded["extendedVisibility"])
        assertFailsWith<IllegalArgumentException> { mapper.post(value.copy(visibility = VisibilityV1.Public)) }
        assertFailsWith<IllegalArgumentException> { mapper.post(post.copy(visibility = VisibilityV1.SelfOnly)) }
    }

    @Test
    fun profileAndListMetadataRespectNavigationAndMediaGrants() {
        val avatar = "https://remote.example/avatar.png"
        val value =
            author.copy(
                url = "https://remote.example/alice",
                verified = true,
                cat = true,
                displayNameRichText = RichTextV1(RichTextFormatV1.Html, "Alice <emoji target='$avatar' alt=':sparkle:'>"),
                sourceLanguages = listOf("en"),
            )
        val profile = mapper.profile(value)
        assertEquals(listOf(UiProfile.Mark.Verified, UiProfile.Mark.Cat), profile.mark)
        assertEquals(listOf("en"), profile.sourceLanguages)
        assertEquals(listOf(avatar), profile.name.imageUrls)
        val guest = createMapper(null, mediaNetwork = false, profileAvailable = false)
        assertEquals(
            ClickEvent.Deeplink(DeeplinkRoute.OpenLinkDirectly(requireNotNull(value.url))),
            guest.cachedProfile(value).clickEvent,
        )
        assertEquals("Alice", guest.cachedProfile(value).name.raw)
        assertFailsWith<IllegalArgumentException> { guest.profile(value) }

        val list = SocialListV1("news", "News", description = "Editors", avatarUrl = avatar, creator = author, readOnly = true)
        val mapped = mapper.socialList(list)
        assertEquals("Editors", mapped.description)
        assertEquals(avatar, mapped.avatar)
        assertEquals(author.key.id, mapped.creator?.key?.id)
        assertEquals(true, mapped.readonly)
        assertFailsWith<IllegalArgumentException> { guest.socialList(list) }
    }

    @Test
    fun nicknameFormattingAndProfileLinkChangesInvalidateCachedPosts() {
        val mapper = createMapper(MicroBlogKey("me", "local.example"), profileAvailable = false)
        val profile = author.copy(url = "https://remote.example/alice")
        val original = mapper.post(post.copy(author = profile))
        val restyled =
            mapper.post(
                post.copy(author = profile.copy(displayNameRichText = RichTextV1(RichTextFormatV1.Html, "<b>Alice</b>"))),
            )
        val redirected = mapper.post(post.copy(author = profile.copy(url = "https://remote.example/profiles/alice")))

        assertEquals(
            original.post.user
                ?.name
                ?.raw,
            restyled.post.user
                ?.name
                ?.raw,
        )
        for (updated in listOf(restyled, redirected)) {
            assertEquals(original.stableItemKey, updated.stableItemKey)
            assertNotEquals(original.renderHash, updated.renderHash)
            assertNotEquals(original.post.renderHash, updated.post.renderHash)
        }
    }

    @Test
    fun relationSnapshotIncludesBothDirectionsOfFollowRequests() {
        val relation =
            mapper.relation(
                RelationV1(author.key, blockedBy = true, hasPendingFollowRequestFromYou = true, hasPendingFollowRequestToYou = true),
            )
        assertEquals(true, relation.blockedBy)
        assertEquals(true, relation.hasPendingFollowRequestFromYou)
        assertEquals(true, relation.hasPendingFollowRequestToYou)
    }

    @Test
    fun structuredArticleBlocksAndMessageBodiesUseNativeVariants() {
        val mapper = createMapper(MicroBlogKey("me", "local.example"), pluginId = "test.article")
        val video = MediaV1("v", MediaTypeV1.Video, "https://remote.example/video.mp4")
        val imageUrl = "https://remote.example/preview.png"
        val article =
            ArticleV1(
                key = post.key,
                title = "Article",
                content = RichTextV1(value = "Legacy summary"),
                attribution = ArticleAttributionV1(siteName = "Example", byline = "Alice"),
                blocks =
                    listOf(
                        ArticleBlockV1.Text(RichTextV1(value = "Paragraph")),
                        ArticleBlockV1.Video(video),
                        ArticleBlockV1.File("notes.pdf", "https://remote.example/notes.pdf", sizeBytes = 1_024),
                        ArticleBlockV1.Embed("https://remote.example/more", title = "Read more", imageUrl = imageUrl),
                        ArticleBlockV1.ContentGate("https://remote.example/subscribe"),
                    ),
            )
        val mapped = mapper.article(PluginJsonV1.decodeFromString<ArticleV1>(PluginJsonV1.encodeToString(article)))
        assertEquals("Alice", assertIs<UiArticleAuthor.Rss>(mapped.author).byline)
        assertEquals("Paragraph", assertIs<UiArticleBlock.Text>(mapped.content.blocks[0]).richText.raw)
        assertEquals(video.url, assertIs<UiArticleBlock.Video>(mapped.content.blocks[1]).media.url)
        assertEquals("notes.pdf", assertIs<UiArticleBlock.File>(mapped.content.blocks[2]).name)
        assertEquals(imageUrl, assertIs<UiArticleBlock.Embed>(mapped.content.blocks[3]).imageUrl)
        assertIs<UiArticleBlock.ContentGate>(mapped.content.blocks[4])
        val restoredEmbed =
            mapped
                .withProxy(ProxyRef.Global)
                .content.blocks
                .filterIsInstance<UiArticleBlock.Embed>()
                .single()
        assertEquals("https://remote.example/more", restoredEmbed.url)
        assertNull(restoredEmbed.imageUrl)
        assertFailsWith<IllegalArgumentException> {
            article.copy(blocks = listOf(ArticleBlockV1.Embed("http://remote.example/"))).requireValid()
        }
        assertFailsWith<IllegalArgumentException> {
            article.copy(blocks = listOf(ArticleBlockV1.File("../notes.pdf", "https://remote.example/notes.pdf"))).requireValid()
        }

        val message = DirectMessageV1(post.key, EntityKeyV1("room", post.key.host), author, post.createdAt, post.content)

        fun body(value: DirectMessageBodyV1): UiDMItem.Message {
            val encoded = PluginJsonV1.encodeToString(message.copy(body = value))
            return mapper.directMessage(PluginJsonV1.decodeFromString<DirectMessageV1>(encoded)).content
        }
        assertEquals(video.url, assertIs<UiDMItem.Message.Media>(body(DirectMessageBodyV1.Media(video))).media.url)
        assertEquals(post.key.id, assertIs<UiDMItem.Message.Status>(body(DirectMessageBodyV1.Post(post))).status.statusKey.id)
        assertIs<UiDMItem.Message.Deleted>(body(DirectMessageBodyV1.Deleted))
        assertEquals(post.content.value, assertIs<UiDMItem.Message.Text>(mapper.directMessage(message).content).text.raw)
    }

    @Test
    fun postAndQuoteTargetsOverrideNavigationWithoutChangingMediaOrEntityIdentity() {
        val pageTarget = NavigationTargetV1.TimelinePage("replies", WireTextV1(value = "Replies"), mapOf("parentId" to "123"))
        val quote = post.copy(key = EntityKeyV1("quote", post.key.host), target = pageTarget)
        val main = post.copy(target = NavigationTargetV1.Link("https://remote.example/topic"))
        val mapped =
            assertIs<UiTimelineV2.TimelinePostItem>(
                mapper.timelineItem(TimelineItemV1.Post(main, PostPresentationV1(quotes = listOf(quote)))),
            )
        assertEquals(mapper.post(post).post.mediaClickPolicy, mapped.post.mediaClickPolicy)
        assertEquals(MicroBlogKey(post.key.id, post.key.host), mapped.post.statusKey)
        assertEquals(ClickEvent.Deeplink(DeeplinkRoute.OpenLinkDirectly("https://remote.example/topic")), mapped.post.clickEvent)
        val route =
            assertIs<DeeplinkRoute.Timeline.Page>(
                DeeplinkRoute.parse(
                    assertIs<ClickEvent.Deeplink>(
                        mapped.presentation.quotes
                            .single()
                            .clickEvent,
                    ).url,
                ),
            )
        assertEquals("test.plugin", route.pluginId)
        assertEquals(AccountType.Specific(MicroBlogKey("me", "local.example")), route.accountType)
        assertEquals("replies", route.pageId)
        assertEquals(mapOf("parentId" to "123"), route.parameters)
        assertEquals(
            mapped.presentation.quotes
                .single()
                .clickEvent,
            mapper.post(quote.copy(favouritesCount = 1)).post.clickEvent,
        )
        val cover = mapper.post(quote.copy(detailType = PostDetailTypeV1.Article)).post
        assertEquals(UiTimelineV2.Post.MediaClickPolicy.OpenPostClickEvent, cover.mediaClickPolicy)
        assertEquals(
            mapped.presentation.quotes
                .single()
                .clickEvent,
            cover.clickEvent,
        )
    }

    @Test
    fun explicitUnknownPostTargetNeverFallsBackAndTargetParametersAreValidated() {
        assertEquals(ClickEvent.Noop, mapper.post(post.copy(target = NavigationTargetV1.Unknown)).post.clickEvent)
        assertEquals(mapper.post(post).post.clickEvent, mapper.post(post.copy(target = null)).post.clickEvent)
        val valid = NavigationTargetV1.TimelinePage("replies", WireTextV1(value = "Replies"))
        valid.requireValid()
        assertFailsWith<IllegalArgumentException> { post.copy(target = valid.copy(pageId = "../bad")).requireValid() }
        assertFailsWith<IllegalArgumentException> { valid.copy(parameters = mapOf("parentId" to "x".repeat(4097))).requireValid() }
        assertFailsWith<IllegalArgumentException> { valid.copy(title = WireTextV1()).requireValid() }
    }

    @Test
    fun quoteUsesTheComposeRouteWithoutRepostMutationAndHonoursSupportedModes() {
        val account = MicroBlogKey("me", "local.example")
        val value = post.copy(actions = listOf(ActionDescriptorV1(SemanticActionV1.Reply), ActionDescriptorV1(SemanticActionV1.Quote)))
        val mapped = createMapper(account, postMutationAvailable = false).post(value).post
        val quote = mapped.actions.filterIsInstance<ActionMenu.Item>().single { it.actionFamily == PostActionFamily.Quote }
        assertEquals(true, quote.enabled)
        assertEquals(ClickEvent.Deeplink(DeeplinkRoute.Compose.Quote(account, MicroBlogKey(post.key.id, post.key.host))), quote.clickEvent)
        val unsupported = createMapper(account, supportedComposeTypes = setOf(ComposeType.New, ComposeType.Reply)).post(value).post
        assertEquals(false, unsupported.actions.filterIsInstance<ActionMenu.Item>().any { it.actionFamily == PostActionFamily.Quote })
    }

    @Test
    fun unknownDetailsKeepThePreviewButDisableEveryPostClickForAccountsAndGuests() {
        val payload = PluginJsonV1.encodeToJsonElement(post).jsonObject
        listOf("audiobook", "novel").forEach { type ->
            val value = PluginJsonV1.decodeFromJsonElement<PostV1>(JsonObject(payload + ("detailType" to JsonPrimitive(type))))
            listOf(MicroBlogKey("me", "local.example"), null).forEach { account ->
                val mapped = createMapper(account).post(value).post
                assertEquals(ClickEvent.Noop, mapped.clickEvent)
                assertEquals(UiTimelineV2.Post.MediaClickPolicy.OpenPostClickEvent, mapped.mediaClickPolicy)
                assertEquals(mapper.post(post).post.content, mapped.content)
                assertNotEquals(ClickEvent.Noop, mapped.user?.clickEvent)
            }
        }
    }

    @Test
    fun unknownNavigationNeverFallsBackToTheFeedUrlOrMessageActor() {
        val target = PluginJsonV1.decodeFromString<NavigationTargetV1>("""{"type":"novel","url":"https://remote.example/fallback"}""")
        val feed = FeedV1("https://remote.example/feed", post.createdAt, FeedSourceV1("Feed"), target = target)
        assertEquals(ClickEvent.Noop, assertIs<UiTimelineV2.Feed>(mapper.timelineItem(TimelineItemV1.Feed(feed))).clickEvent)
        assertNotEquals(
            ClickEvent.Noop,
            assertIs<UiTimelineV2.Feed>(mapper.timelineItem(TimelineItemV1.Feed(feed.copy(target = null)))).clickEvent,
        )
        val message = MessageV1(post.key, post.createdAt, actor = author, text = "Message", target = target)
        assertEquals(ClickEvent.Noop, assertIs<UiTimelineV2.Message>(mapper.timelineItem(TimelineItemV1.Message(message))).clickEvent)
        assertNotEquals(
            ClickEvent.Noop,
            assertIs<UiTimelineV2.Message>(mapper.timelineItem(TimelineItemV1.Message(message.copy(target = null)))).clickEvent,
        )
    }

    @Test
    fun unknownTimelineAndMediaAreSkippedWithoutRequiringMediaPermissions() {
        val mapper = createMapper(null, mediaNetwork = false)
        assertNull(mapper.timelineItem(TimelineItemV1.Unknown))
        val value = post.copy(media = listOf(MediaV1("", MediaTypeV1.Unknown, "")))
        assertEquals(emptyList(), mapper.post(value).post.images)
        val feed = FeedV1("https://remote.example/feed", post.createdAt, FeedSourceV1("Feed"), media = value.media.single())
        assertNull(assertIs<UiTimelineV2.Feed>(mapper.timelineItem(TimelineItemV1.Feed(feed))).media)
    }

    @Test
    fun postsWithoutAuthorOrVisibilityKeepNavigationActionsAndPlatformIcon() {
        val icon = IconType.Url("file:///plugins/platform-logo.png")
        listOf(MicroBlogKey("me", "local.example"), null).forEach { accountKey ->
            val mapper = createMapper(accountKey, platformIcon = icon)
            PostDetailTypeV1.entries.forEach { type ->
                val value =
                    post.copy(
                        visibility = VisibilityV1.Followers,
                        detailType = type,
                        url = "https://remote.example/post",
                        actions = listOf(ActionDescriptorV1(SemanticActionV1.Favourite)),
                    )
                val complete = mapper.post(value)
                val withoutAuthor = mapper.post(value.copy(author = null, visibility = null))

                assertEquals(complete.post.copy(user = null, visibility = null), withoutAuthor.post)
                assertEquals(complete.stableItemKey, withoutAuthor.stableItemKey)
                assertNotEquals(complete.renderHash, withoutAuthor.renderHash)
            }
        }
    }

    @Test
    fun explicitPostVisibilitiesKeepTheirMapping() {
        mapOf(
            VisibilityV1.Public to UiTimelineV2.Post.Visibility.Public,
            VisibilityV1.Unlisted to UiTimelineV2.Post.Visibility.Home,
            VisibilityV1.Followers to UiTimelineV2.Post.Visibility.Followers,
            VisibilityV1.Direct to UiTimelineV2.Post.Visibility.Specified,
        ).forEach { (visibility, expected) ->
            assertEquals(expected, mapper.post(post.copy(visibility = visibility)).post.visibility)
        }
    }

    @Test
    fun platformIconIsSharedByPostsProfilesAndNestedAuthors() {
        val icon = IconType.Url("file:///plugins/platform-logo.png")
        val mapper = createMapper(null, platformIcon = icon)
        val mapped = assertIs<UiTimelineV2.TimelinePostItem>(mapper.post(post)).post
        assertEquals(icon, mapped.platformIcon)
        assertEquals(icon, mapped.user?.platformIcon)
        assertEquals(icon, mapper.profile(author).platformIcon)
        assertEquals(icon, mapper.cachedProfile(author).platformIcon)
    }

    @Test
    fun unsafeProfileAndNestedPostUrlsAreRejectedBeforeMapping() {
        listOf("http://remote.example/asset", "https://user:secret@remote.example/asset", "javascript:alert(1)").forEach { url ->
            listOf(
                author.copy(avatarUrl = url),
                author.copy(bannerUrl = url),
                author.copy(url = url),
            ).forEach { profile ->
                assertFailsWith<IllegalArgumentException>(url) { mapper.profile(profile) }
                assertFailsWith<IllegalArgumentException>(url) {
                    mapper.timelineItem(TimelineItemV1.Post(post, PostPresentationV1(repost = post.copy(author = profile))))
                }
            }
            listOf(
                post.copy(url = url),
                post.copy(media = listOf(MediaV1("image", MediaTypeV1.Image, url))),
                post.copy(media = listOf(MediaV1("image", MediaTypeV1.Image, "https://remote.example/image", previewUrl = url))),
            ).forEach { nested ->
                assertFailsWith<IllegalArgumentException>(
                    url,
                ) { mapper.timelineItem(TimelineItemV1.Post(post, PostPresentationV1(repost = nested))) }
            }
        }
    }

    @Test
    fun disabledOrUnsupportedGalleryBookmarkDoesNotCreateAMutation() {
        val gallery =
            GalleryV1(
                key = post.key,
                title = "Gallery",
                createdAt = post.createdAt,
                url = "https://remote.example/gallery",
                images = listOf(MediaV1("image", MediaTypeV1.Image, "https://remote.example/image.png")),
                actions =
                    listOf(
                        dev.dimension.flare.feature.plugin.wire.ActionDescriptorV1(
                            action = dev.dimension.flare.feature.plugin.wire.SemanticActionV1.Bookmark,
                            enabled = false,
                        ),
                    ),
            )
        assertEquals(ClickEvent.Noop, mapper.gallery(gallery).bookmarkAction)
        val disabledBookmark =
            gallery.copy(
                actions =
                    gallery.actions.map {
                        it.copy(action = dev.dimension.flare.feature.plugin.wire.SemanticActionV1.Unbookmark, active = true)
                    },
            )
        assertEquals(true, mapper.gallery(disabledBookmark).isBookmarked)
        assertEquals(ClickEvent.Noop, mapper.gallery(disabledBookmark).bookmarkAction)
        val available = gallery.copy(actions = gallery.actions.map { it.copy(enabled = true) })
        assertNotEquals(ClickEvent.Noop, mapper.gallery(available).bookmarkAction)
        assertEquals(ClickEvent.Noop, createMapper(null).gallery(available).bookmarkAction)
        assertEquals(
            ClickEvent.Noop,
            createMapper(MicroBlogKey("me", "local.example"), postMutationAvailable = false).gallery(available).bookmarkAction,
        )
    }

    @Test
    fun selfProfileUsesTheCanonicalAccountKeyForCacheLookups() {
        val key = MicroBlogKey("me", "local.example")
        val profile = author.copy(key = EntityKeyV1(key.id, key.host.uppercase()))
        assertEquals(key, mapper.profile(profile).key)
        assertEquals(key, mapper.cachedProfile(profile).key)
        assertEquals(
            MicroBlogKey("other", key.host),
            mapper.profile(profile.copy(key = profile.key.copy(id = "other"))).key,
        )
        assertEquals(author.key.host, mapper.profile(author).key.host)
    }

    @Test
    fun savedLoginIdentitySurvivesMediaPermissionRemoval() {
        val denied = createMapper(MicroBlogKey("me", "local.example"), mediaNetwork = false)
        val profile =
            denied.cachedProfile(
                author.copy(
                    avatarUrl = "https://cdn.example/avatar.png",
                    bannerUrl = "https://cdn.example/banner.png",
                    description = RichTextV1(RichTextFormatV1.Html, "<p>About<img src='https://cdn.example/old.png'></p>"),
                ),
            )
        assertEquals(null, profile.avatar)
        assertEquals(null, profile.banner)
        assertEquals("About", profile.description?.raw)
        assertEquals(author.key.id, profile.key.id)
        assertEquals(
            false,
            dev.dimension.flare.ui.model.PluginMediaPermissions
                .isAllowed(profile.pluginId!!),
        )
    }

    @Test
    fun mediaHeadersReachAllNativeVariantsAndTheirPreviews() {
        val headers = mapOf("Referer" to "https://local.example/", "X-Media-Token" to "fixture-token")
        MediaTypeV1.entries.filterNot { it == MediaTypeV1.Unknown }.forEach { type ->
            val media =
                MediaV1(
                    "media",
                    type,
                    "https://cdn.example/media",
                    previewUrl = "https://preview.example/image.jpg",
                    ugoiraKey = post.key.takeIf { type == MediaTypeV1.Ugoira },
                    customHeaders = headers,
                )
            val mapped =
                mapper
                    .post(post.copy(media = listOf(media)))
                    .post.images
                    .single()
            assertEquals<Map<String, String>?>(headers, mapped.customHeaders)
            assertEquals<Map<String, String>?>(headers, assertNotNull(mapped.preview).customHeaders)
            assertEquals(ProxyRef.Account(MicroBlogKey("me", "local.example")), mapped.proxy)
            val cached = PluginJsonV1.decodeFromString<UiMedia>(PluginJsonV1.encodeToString(UiMedia.serializer(), mapped))
            assertEquals<Map<String, String>?>(headers, cached.customHeaders)
            assertEquals<Map<String, String>?>(headers, cached.preview?.customHeaders)
        }
        val denied = createMapper(null, mediaNetwork = false)
        assertFailsWith<IllegalArgumentException> {
            denied.post(
                post.copy(media = listOf(MediaV1("media", MediaTypeV1.Image, "https://cdn.example/image", customHeaders = headers))),
            )
        }
        assertNull(
            mapper
                .post(
                    post.copy(media = listOf(MediaV1("media", MediaTypeV1.Image, "https://cdn.example/image"))),
                ).post.images
                .single()
                .customHeaders,
        )
    }

    @Test
    fun remoteMediaRequiresAnExplicitNetworkGrant() {
        val denied = createMapper(MicroBlogKey("me", "local.example"), mediaNetwork = false)
        assertFailsWith<IllegalArgumentException> {
            denied.profile(author.copy(avatarUrl = "https://unapproved.example/collect?token=dummy"))
        }
        assertFailsWith<IllegalArgumentException> {
            denied.post(post.copy(media = listOf(MediaV1("image", MediaTypeV1.Image, "https://cdn.example/image.png"))))
        }
        assertFailsWith<IllegalArgumentException> {
            denied.post(post.copy(content = RichTextV1(RichTextFormatV1.Html, "<p><img src='https://cdn.example/image.png'></p>")))
        }
        assertIs<UiTimelineV2.TimelinePostItem>(denied.post(post))
        assertEquals(
            "https://cdn.example/avatar.png",
            mapper.profile(author.copy(avatarUrl = "https://cdn.example/avatar.png")).avatar?.url,
        )
    }

    @Test
    fun richTextResolvesRelativeResourcesAtTheVerifiedOrigin() {
        val value = mapper.post(post.copy(content = RichTextV1(RichTextFormatV1.Html, "<p><img src='/image.png'></p>")))
        val image =
            assertIs<UiTimelineV2.TimelinePostItem>(value)
                .post.content.original.imageMedias
                .single()
        assertEquals("https://local.example/image.png", image.url)
        assertEquals(ProxyRef.Account(MicroBlogKey("me", "local.example")), image.proxy)
    }

    @Test
    fun galleryMediaKeepsTheViewingAccountScope() {
        val value =
            GalleryV1(
                key = post.key,
                title = "Gallery",
                createdAt = post.createdAt,
                url = "https://remote.example/gallery",
                author = author.copy(avatarUrl = "https://remote.example/avatar.png"),
                images = listOf(MediaV1("image", MediaTypeV1.Image, "https://remote.example/image.png")),
            )
        val detail = mapper.gallery(value)

        assertEquals(ProxyRef.Account(MicroBlogKey("me", "local.example")), detail.images.single().proxy)
        assertEquals(detail.images.single().proxy, detail.author?.avatar?.proxy)
        assertEquals(
            ProxyRef.Global,
            createMapper(null)
                .gallery(value)
                .images
                .single()
                .proxy,
        )
    }

    @Test
    fun articleBlockImagesKeepTheirPosition() {
        val value =
            ArticleV1(
                key = post.key,
                title = "Article",
                content =
                    RichTextV1(
                        RichTextFormatV1.Html,
                        "<p>Before</p><figure><img src='https://remote.example/image.png'></figure><p>After</p>",
                    ),
            )
        val blocks = mapper.article(value).content.blocks

        assertEquals(3, blocks.size)
        assertIs<UiArticleBlock.Text>(blocks[0])
        assertEquals("https://remote.example/image.png", assertIs<UiArticleBlock.Image>(blocks[1]).media.url)
        assertIs<UiArticleBlock.Text>(blocks[2])
    }

    @Test
    fun richTextRejectsLocalResourcesAndUnsafeLinks() {
        listOf(
            "file:///private/tmp/private.png",
            "content://private/image",
            "data:image/png;base64,AAA",
            "http://example.com/image",
        ).forEach { url ->
            assertFailsWith<IllegalArgumentException>(url) {
                mapper.post(post.copy(content = RichTextV1(RichTextFormatV1.Html, "<p><img src='$url'></p>")))
            }
        }
        listOf("javascript:alert(1)", "file:///private/tmp/private.png", "intent://example").forEach { url ->
            assertFailsWith<IllegalArgumentException>(url) {
                mapper.post(post.copy(content = RichTextV1(RichTextFormatV1.Html, "<a href='$url'>link</a>")))
            }
        }
    }

    @Test
    fun remoteProfileHandleKeepsItsFederatedHost() {
        val profile =
            ProfileV1(
                key = EntityKeyV1("42", "local.example"),
                handle = "@alice@remote.example",
                displayName = "Alice",
            )

        assertEquals("@alice@remote.example", mapper.profile(profile).handle.canonical)
    }

    @Test
    fun localProfileHandleFallsBackToEntityHost() {
        val profile =
            ProfileV1(
                key = EntityKeyV1("42", "local.example"),
                handle = "alice",
                displayName = "Alice",
            )

        assertEquals("@alice@local.example", mapper.profile(profile).handle.canonical)
    }

    @Test
    fun guestDetailRoutesKeepTheGuestOriginAndEntityKey() {
        val guestMapper = createMapper(null, platformId = "example")
        val accountType = AccountType.GuestPlugin("example", "local.example")
        val key = MicroBlogKey(post.key.id, post.key.host)
        val targets =
            mapOf(
                PostDetailTypeV1.Post to DeeplinkRoute.Status.Detail(key, accountType),
                PostDetailTypeV1.Article to DeeplinkRoute.Article(accountType, key),
                PostDetailTypeV1.Gallery to DeeplinkRoute.Gallery.Detail(key, accountType),
                PostDetailTypeV1.Manga to DeeplinkRoute.Manga.Detail(key, accountType),
            )

        targets.forEach { (type, route) ->
            val mapped = assertIs<UiTimelineV2.TimelinePostItem>(guestMapper.post(post.copy(detailType = type)))
            assertEquals(ClickEvent.Deeplink(route), mapped.post.clickEvent)
        }
    }

    @Test
    fun mapsMangaDetailTypeAndTargetsAndNormalizesFlatOrGroupedTagsAndChapters() {
        val accountKey = MicroBlogKey("me", "local.example")
        val specificAccount = AccountType.Specific(accountKey)
        val guestAccount = AccountType.GuestPlugin("Test", "local.example")
        val mangaKey = MicroBlogKey(post.key.id, post.key.host)

        // 1. detailType = "manga" -> DeeplinkRoute.Manga.Detail when manga capability exposed; Noop otherwise; openStatusMedia = false
        val mappedMangaPost = mapper.post(post.copy(detailType = PostDetailTypeV1.Manga)).post
        assertEquals(ClickEvent.Deeplink(DeeplinkRoute.Manga.Detail(mangaKey, specificAccount)), mappedMangaPost.clickEvent)
        assertEquals(UiTimelineV2.Post.MediaClickPolicy.OpenPostClickEvent, mappedMangaPost.mediaClickPolicy)

        val withoutManga = createMapper(accountKey, mangaAvailable = false)
        assertEquals(ClickEvent.Noop, withoutManga.post(post.copy(detailType = PostDetailTypeV1.Manga)).post.clickEvent)

        // 2. target = { type: "manga" } and { type: "mangaChapter", key, chapterId: "ch-2" }
        val mangaEntityTarget = NavigationTargetV1.Manga(post.key)
        assertEquals(
            ClickEvent.Deeplink(DeeplinkRoute.Manga.Detail(mangaKey, specificAccount)),
            mapper.post(post.copy(target = mangaEntityTarget)).post.clickEvent,
        )
        assertEquals(
            ClickEvent.Noop,
            withoutManga.post(post.copy(target = mangaEntityTarget)).post.clickEvent,
        )

        val chapterTarget = NavigationTargetV1.MangaChapter(post.key, "ch-2")
        assertEquals(
            ClickEvent.Deeplink(DeeplinkRoute.Manga.Reader(mangaKey, "ch-2", null, specificAccount)),
            mapper.post(post.copy(target = chapterTarget)).post.clickEvent,
        )
        assertEquals(
            ClickEvent.Deeplink(DeeplinkRoute.Manga.Reader(mangaKey, "ch-2", null, guestAccount)),
            createMapper(null).post(post.copy(target = chapterTarget)).post.clickEvent,
        )
        assertEquals(
            ClickEvent.Noop,
            withoutManga.post(post.copy(target = chapterTarget)).post.clickEvent,
        )

        // 3. Flat tags/chapters map to single group with title = null; grouped map with localized UiText titles
        val flatManga =
            MangaV1(
                key = post.key,
                title = "Flat Manga",
                altTitles = listOf("Alt 1"),
                cover = MediaV1("cover", MediaTypeV1.Image, "https://remote.example/cover.jpg", width = 600, height = 900),
                author = author,
                authors = listOf("Author A"),
                artists = listOf("Artist B"),
                status = MangaStatusV1.Ongoing,
                content = RichTextV1(value = "Synopsis"),
                url = "https://remote.example/manga/1",
                updatedAt = "2026-10-27T00:00:00Z",
                readingDirection = MangaReadingDirectionV1.Rtl,
                tags =
                    listOf(
                        MangaTagV1("Action", target = NavigationTargetV1.Link("https://remote.example/tags/action")),
                        MangaTagV1("Comedy"),
                    ),
                chapters =
                    listOf(
                        MangaChapterV1(id = "ch-1", title = "Chapter 1", subtitle = "Start"),
                        MangaChapterV1(id = "ch-2", title = "Chapter 2", locked = true),
                    ),
                actions =
                    listOf(
                        ActionDescriptorV1(SemanticActionV1.Bookmark, actionToken = "bm-1"),
                        ActionDescriptorV1(SemanticActionV1.Favourite, count = 42, actionToken = "fav-1"),
                    ),
                sourceLanguages = listOf("ja"),
            )
        val mappedFlat = mapper.manga(flatManga)
        assertEquals("test.plugin", mappedFlat.pluginId)
        assertEquals("Test", mappedFlat.platformId)
        assertEquals(mangaKey, mappedFlat.mangaKey)
        assertEquals(specificAccount, mappedFlat.accountType)
        assertEquals("Flat Manga", mappedFlat.title)
        assertEquals(listOf("Alt 1"), mappedFlat.altTitles)
        assertEquals(ProxyRef.Account(accountKey), mappedFlat.cover?.proxy)
        assertEquals(UiMangaStatus.Ongoing, mappedFlat.status)
        assertEquals(MangaReadingDirection.Rtl, mappedFlat.readingDirection)
        assertEquals(false, mappedFlat.isBookmarked)
        assertNotEquals(ClickEvent.Noop, mappedFlat.bookmarkAction)
        assertEquals(2, mappedFlat.actions.size)
        assertEquals(listOf("ja"), mappedFlat.sourceLanguages)

        val singleTagGroup = mappedFlat.tagGroups.single()
        assertNull(singleTagGroup.title)
        assertEquals(listOf("Action", "Comedy"), singleTagGroup.tags.map { it.name })
        assertEquals(
            ClickEvent.Deeplink(DeeplinkRoute.OpenLinkDirectly("https://remote.example/tags/action")),
            singleTagGroup.tags[0].clickEvent,
        )
        assertEquals(ClickEvent.Noop, singleTagGroup.tags[1].clickEvent)

        val singleChapterGroup = mappedFlat.chapterGroups.single()
        assertNull(singleChapterGroup.title)
        assertEquals(listOf("ch-1", "ch-2"), singleChapterGroup.chapters.map { it.id })
        assertEquals(true, singleChapterGroup.chapters[1].locked)

        // Guest disables bookmarkAction and semantic actions
        val guestMapped = createMapper(null).manga(flatManga)
        assertEquals(ClickEvent.Noop, guestMapped.bookmarkAction)
        assertEquals(true, guestMapped.actions.filterIsInstance<ActionMenu.Item>().all { !it.enabled && it.clickEvent == ClickEvent.Noop })

        // Grouped tags & chapters with localized UiText titles + Unknown forward-compat enums
        val groupedManga =
            flatManga.copy(
                status = MangaStatusV1.Unknown,
                readingDirection = MangaReadingDirectionV1.Unknown,
                tags = null,
                tagGroups =
                    listOf(
                        MangaTagGroupV1(
                            id = "genre",
                            title = WireTextV1(key = "manga.genre", fallback = "Genre"),
                            tags = listOf(MangaTagV1("Sci-Fi")),
                        ),
                        MangaTagGroupV1(
                            id = "theme",
                            title = WireTextV1(value = "Theme"),
                            tags = listOf(MangaTagV1("Space")),
                        ),
                    ),
                chapters = null,
                chapterGroups =
                    listOf(
                        MangaChapterGroupV1(
                            id = "serialized",
                            title = WireTextV1(key = "manga.serialized", fallback = "Serialized"),
                            chapters = listOf(MangaChapterV1(id = "s-1", title = "Ch. 1")),
                        ),
                        MangaChapterGroupV1(
                            id = "extra",
                            title = WireTextV1(value = "Extras"),
                            chapters = listOf(MangaChapterV1(id = "e-1", title = "Bonus")),
                        ),
                    ),
            )
        val mappedGrouped = mapper.manga(groupedManga)
        assertNull(mappedGrouped.status)
        assertNull(mappedGrouped.readingDirection)
        assertEquals(
            listOf(
                UiText.ExternalRef("test.plugin", "manga.genre", "Genre"),
                UiText.Raw("Theme"),
            ),
            mappedGrouped.tagGroups.map { it.title },
        )
        assertEquals(
            listOf(
                UiText.ExternalRef("test.plugin", "manga.serialized", "Serialized"),
                UiText.Raw("Extras"),
            ),
            mappedGrouped.chapterGroups.map { it.title },
        )

        // Direct and deferred mangaPages & mangaPageMedia validation
        val pages =
            mapper.mangaPages(
                MangaPagesV1(
                    pages =
                        listOf(
                            MangaPageV1(
                                id = "p-1",
                                media = MediaV1("img-1", MediaTypeV1.Image, "https://remote.example/p1.jpg", width = 800, height = 1200),
                            ),
                            MangaPageV1(
                                id = "p-2",
                                media = MediaV1("gif-2", MediaTypeV1.Gif, "https://remote.example/p2.gif"),
                            ),
                            MangaPageV1(
                                id = "p-3",
                                pageToken = "ptok-3",
                                previewUrl = "https://remote.example/p3-thumb.jpg",
                                width = 900,
                                height = 1300,
                                customHeaders = mapOf("Referer" to "https://remote.example/"),
                            ),
                        ),
                ),
            )
        val readyImage = assertIs<UiMangaPage.Ready>(pages[0])
        assertIs<UiMedia.Image>(readyImage.media)
        assertEquals(ProxyRef.Account(accountKey), readyImage.media.proxy)
        val readyGif = assertIs<UiMangaPage.Ready>(pages[1])
        assertIs<UiMedia.Gif>(readyGif.media)
        assertEquals(ProxyRef.Account(accountKey), readyGif.media.proxy)
        val deferred = assertIs<UiMangaPage.Deferred>(pages[2])
        assertEquals("ptok-3", deferred.pageToken)
        assertEquals(900f, deferred.width)
        assertEquals(1300f, deferred.height)
        assertEquals<Map<String, String>?>(mapOf("Referer" to "https://remote.example/"), deferred.customHeaders)
        assertEquals(ProxyRef.Account(accountKey), deferred.proxy)

        assertFailsWith<IllegalArgumentException> {
            mapper.mangaPageMedia(MediaV1("vid", MediaTypeV1.Video, "https://remote.example/v.mp4"))
        }
        assertFailsWith<IllegalArgumentException> {
            createMapper(accountKey, pageImageAvailable = false).mangaPages(
                MangaPagesV1(pages = listOf(MangaPageV1(id = "p-3", pageToken = "ptok-3"))),
            )
        }
    }

    @Test
    fun boundedLruMapPromotesRecentReadsAndEvictsOldestEntries() {
        val lru = BoundedLruMap<String, String>(maxEntries = 3)
        lru.putAllAndEvict(mapOf("a" to "1", "b" to "2", "c" to "3"))
        assertEquals(3, lru.size)
        // Promote "a" to MRU
        assertEquals("1", lru.get("a"))
        // Adding "d" should evict "b" (oldest)
        lru.putAllAndEvict(mapOf("d" to "4"))
        assertEquals(3, lru.size)
        assertNull(lru.get("b"))
        assertEquals("1", lru.get("a"))
        assertEquals("3", lru.get("c"))
        assertEquals("4", lru.get("d"))
        // Updating to null removes key
        lru.putAllAndEvict(mapOf("c" to null))
        assertNull(lru.get("c"))
        assertEquals(2, lru.size)
    }

    @Test
    fun linkPostsOpenTheExplicitBrowserTargetForAccountsAndGuests() {
        val url = "https://remote.example/posts/post-1?source=plugin#content"
        val value = post.copy(detailType = PostDetailTypeV1.Link, url = url)
        listOf(createMapper(MicroBlogKey("me", "local.example"), mediaNetwork = false), createMapper(null, mediaNetwork = false)).forEach {
            val mapped = assertIs<UiTimelineV2.TimelinePostItem>(it.post(value)).post
            assertEquals(ClickEvent.Deeplink(DeeplinkRoute.OpenLinkDirectly(url)), mapped.clickEvent)
            assertEquals(UiTimelineV2.Post.MediaClickPolicy.OpenPostClickEvent, mapped.mediaClickPolicy)
        }
    }

    @Test
    fun linkPostsKeepIndependentAuthorAndActionClicks() {
        val value = post.copy(url = "https://remote.example/post", actions = listOf(ActionDescriptorV1(SemanticActionV1.Favourite)))
        val internal = assertIs<UiTimelineV2.TimelinePostItem>(mapper.post(value)).post
        val linked = assertIs<UiTimelineV2.TimelinePostItem>(mapper.post(value.copy(detailType = PostDetailTypeV1.Link))).post
        assertIs<ClickEvent.Deeplink>(linked.user?.clickEvent)
        assertEquals(internal.user?.clickEvent, linked.user?.clickEvent)
        assertEquals(internal.actions, linked.actions)
        assertNotEquals(internal.clickEvent, linked.clickEvent)
    }

    @Test
    fun nonPostPreviewsUseTheSelectedClickInMediaGrids() {
        val previews =
            listOf(
                post,
                post.copy(detailType = PostDetailTypeV1.Article),
                post.copy(detailType = PostDetailTypeV1.Gallery),
                post.copy(detailType = PostDetailTypeV1.Manga),
                post.copy(detailType = PostDetailTypeV1.Link, url = "https://remote.example/post"),
            )

        assertEquals(
            listOf(
                UiTimelineV2.Post.MediaClickPolicy.OpenStatusMedia,
                UiTimelineV2.Post.MediaClickPolicy.OpenPostClickEvent,
                UiTimelineV2.Post.MediaClickPolicy.OpenPostClickEvent,
                UiTimelineV2.Post.MediaClickPolicy.OpenPostClickEvent,
                UiTimelineV2.Post.MediaClickPolicy.OpenPostClickEvent,
            ),
            previews.map { assertIs<UiTimelineV2.TimelinePostItem>(mapper.post(it)).post.mediaClickPolicy },
        )
    }

    @Test
    fun detailTypeUpdatesInvalidateCachedTimelineRows() {
        val article = assertIs<UiTimelineV2.TimelinePostItem>(mapper.post(post.copy(detailType = PostDetailTypeV1.Article)))
        val gallery = assertIs<UiTimelineV2.TimelinePostItem>(mapper.post(post.copy(detailType = PostDetailTypeV1.Gallery)))

        assertEquals(article.stableItemKey, gallery.stableItemKey)
        assertNotEquals(article.renderHash, gallery.renderHash)
        assertNotEquals(article.post.renderHash, gallery.post.renderHash)
    }

    @Test
    fun linkUrlUpdatesInvalidateCachedTimelineRows() {
        val value = post.copy(detailType = PostDetailTypeV1.Link, url = "https://remote.example/first")
        val first = assertIs<UiTimelineV2.TimelinePostItem>(mapper.post(value))
        val second = assertIs<UiTimelineV2.TimelinePostItem>(mapper.post(value.copy(url = "https://remote.example/second")))
        assertEquals(first.stableItemKey, second.stableItemKey)
        assertNotEquals(first.renderHash, second.renderHash)
        assertNotEquals(first.post.renderHash, second.post.renderHash)
    }

    @Test
    fun repostCanOpenALinkWhileTheParentKeepsPostDetail() {
        val url = "https://remote.example/reposted"
        val nested = post.copy(key = EntityKeyV1("linked", "remote.example"), detailType = PostDetailTypeV1.Link, url = url)
        val mapped =
            assertIs<UiTimelineV2.TimelinePostItem>(mapper.timelineItem(TimelineItemV1.Post(post, PostPresentationV1(repost = nested))))
        assertEquals(
            ClickEvent.Deeplink(
                DeeplinkRoute.Status.Detail(
                    MicroBlogKey(post.key.id, post.key.host),
                    AccountType.Specific(MicroBlogKey("me", "local.example")),
                ),
            ),
            mapped.post.clickEvent,
        )
        assertEquals(ClickEvent.Deeplink(DeeplinkRoute.OpenLinkDirectly(url)), mapped.presentation.repost?.clickEvent)
        assertEquals(UiTimelineV2.Post.MediaClickPolicy.OpenPostClickEvent, mapped.presentation.repost?.mediaClickPolicy)
    }

    @Test
    fun repostKeepsItsOwnDetailTarget() {
        val repost = post.copy(key = EntityKeyV1("article-1", "remote.example"), detailType = PostDetailTypeV1.Article)
        val mapped =
            assertIs<UiTimelineV2.TimelinePostItem>(mapper.timelineItem(TimelineItemV1.Post(post, PostPresentationV1(repost = repost))))

        assertEquals(
            ClickEvent.Deeplink(
                DeeplinkRoute.Article(
                    AccountType.Specific(MicroBlogKey("me", "local.example")),
                    MicroBlogKey("article-1", "remote.example"),
                ),
            ),
            mapped.presentation.repost?.clickEvent,
        )
    }

    private fun createMapper(
        accountKey: MicroBlogKey?,
        mediaNetwork: Boolean = true,
        postMutationAvailable: Boolean = true,
        platformIcon: IconType = UiIcon.World.asType(),
        supportedComposeTypes: Set<ComposeType> = ComposeType.entries.toSet(),
        profileAvailable: Boolean = true,
        mangaAvailable: Boolean = true,
        pageImageAvailable: Boolean = true,
        pluginId: String = "test.plugin",
        platformId: String = "Test",
    ): PluginWireMapperV1 =
        PluginWireMapperV1(
            supportedComposeTypes = supportedComposeTypes,
            pluginId = pluginId,
            platformIcon = platformIcon,
            mediaNetwork = mediaNetwork,
            platformId = platformId,
            accountKey = accountKey,
            originHost = "local.example",
            capabilities =
                PluginSessionCapabilitiesV1(
                    declarations = emptyMap(),
                    operations =
                        PluginAbiV1.knownCapabilityOperations
                            .filterKeys {
                                (profileAvailable || it != PluginAbiV1.Capabilities.PROFILE) &&
                                    (mangaAvailable || it != PluginAbiV1.Capabilities.MANGA)
                            }.mapValues { (id, operations) ->
                                when (id) {
                                    PluginAbiV1.Capabilities.POST -> if (!postMutationAvailable) operations - "mutate" else operations
                                    PluginAbiV1.Capabilities.MANGA -> if (!pageImageAvailable) operations - "pageImage" else operations
                                    else -> operations
                                }
                            },
                    authenticated = accountKey != null,
                ),
        )
}
