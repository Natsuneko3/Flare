package dev.dimension.flare.data.datasource.vvo

import dev.dimension.flare.data.datasource.microblog.ActionMenu
import dev.dimension.flare.data.datasource.microblog.PostActionFamily
import dev.dimension.flare.data.datasource.microblog.capabilities
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.network.vvo.VVOService
import dev.dimension.flare.data.platform.VVoCredential
import dev.dimension.flare.data.repository.LoginExpiredException
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.UiIcon
import dev.dimension.flare.ui.model.UiStrings
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.asText
import dev.dimension.flare.ui.model.contentPostOrNull
import dev.dimension.flare.ui.model.mapper.render
import dev.dimension.flare.ui.route.DeeplinkRoute
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatusCommentRemoteMediatorTest {
    @Test
    fun postDetailOffersLikedByAfterCommentsAndReposts() =
        runTest {
            val source = VVODataSource(accountKey, flowOf(VVoCredential(chocolate = "")))

            val catalog = source.postDetailTabs(statusKey)

            assertEquals(listOf("comments", "reposts", "liked-by"), catalog.tabs.map { it.id })
            assertEquals(UiStrings.LikedBy.asText(), catalog.tabs.last().title)
            assertNull(catalog.tabs.last().filter)
            assertEquals("comments", catalog.replyTabId)
        }

    @Test
    fun hotCommentsUseWebFlowAndKeepThePostAsTheReplyRoot() =
        runTest {
            val service =
                service { request ->
                    assertEquals("weibo.com", request.url.host)
                    assertEquals("/ajax/statuses/buildComments", request.url.encodedPath)
                    assertEquals(HttpMethod.Get, request.method)
                    assertEquals("MLOGIN=1; SUB=example", request.headers[HttpHeaders.Cookie])
                    assertEquals("https://weibo.com/detail/5357831171867297", request.headers[HttpHeaders.Referrer])
                    assertEquals("5357831171867297", request.url.parameters["id"])
                    assertEquals("0", request.url.parameters["is_mix"])
                    assertEquals("0", request.url.parameters["fetch_level"])
                    assertEquals("1", request.url.parameters["flow"])
                    assertEquals("20", request.url.parameters["count"])
                    assertNull(request.url.parameters["max_id"])
                    """
                    {"ok":1,"data":[{"id":5358284531043660,"mid":"5358284531043660","text":"评论",
                    "like_counts":14,"like":1,"total_number":5,"user":{"id":30,"screen_name":"甲"}}],"max_id":"1456657314135"}
                    """.trimIndent()
                }
            val page = StatusCommentRemoteMediator(service, statusKey, accountKey).load(20, PagingRequest.Refresh)

            assertEquals("1456657314135", page.nextKey)
            val comment = assertNotNull(page.data.single().contentPostOrNull())
            val actions = comment.actions.filterIsInstance<ActionMenu.Item>()
            assertEquals("5358284531043660", comment.statusKey.id)
            val likeAction = actions.single { it.actionFamily == PostActionFamily.Like }
            assertEquals(14L, likeAction.count?.value)
            assertEquals(UiIcon.Unlike, likeAction.icon)
            val replyAction = actions.single { it.actionFamily == PostActionFamily.Comment }
            assertEquals(5L, replyAction.count?.value)
            val route =
                assertIs<DeeplinkRoute.Compose.VVOReplyComment>(
                    DeeplinkRoute.parse(assertIs<ClickEvent.Deeplink>(replyAction.clickEvent).url),
                )
            assertEquals(comment.statusKey, route.replyTo)
            assertEquals(statusKey.id, route.rootId)
        }

    @Test
    fun hotAndTimePagesKeepTheirOwnCursors() =
        runTest {
            val service =
                service { request ->
                    val flow = request.url.parameters["flow"]
                    val cursor = request.url.parameters["max_id"]
                    when (flow to cursor) {
                        "1" to null -> """{"ok":1,"data":[{"id":101}],"max_id":201}"""
                        "0" to null -> """{"ok":1,"data":{"data":[{"id":"102"}],"max_id":"202"}}"""
                        "1" to "201" -> """{"ok":1,"data":[{"id":103}],"max_id":201}"""
                        "0" to "202" -> """{"ok":1,"data":[{"id":104}],"max_id":0}"""
                        else -> error("Unexpected flow $flow and cursor $cursor")
                    }
                }
            val hot = StatusCommentRemoteMediator(service, statusKey, accountKey, StatusCommentSort.Hot)
            val time = StatusCommentRemoteMediator(service, statusKey, accountKey, StatusCommentSort.Time)

            assertNotEquals(hot.pagingKey, time.pagingKey)
            val hotCursor = assertNotNull(hot.load(20, PagingRequest.Refresh).nextKey)
            val timeCursor = assertNotNull(time.load(20, PagingRequest.Refresh).nextKey)
            val hotAppend = hot.load(20, PagingRequest.Append(hotCursor))
            val timeAppend = time.load(20, PagingRequest.Append(timeCursor))

            val hotComment = assertNotNull(hotAppend.data.single().contentPostOrNull())
            val timeComment = assertNotNull(timeAppend.data.single().contentPostOrNull())
            assertEquals("103", hotComment.statusKey.id)
            assertNull(hotAppend.nextKey)
            assertEquals("104", timeComment.statusKey.id)
            assertNull(timeAppend.nextKey)
        }

    @Test
    fun webSessionFailureNeedsRelogin() =
        runTest {
            val service = service { """{"ok":-100,"url":"https://passport.weibo.com/sso/signin"}""" }
            val loader = StatusCommentRemoteMediator(service, statusKey, accountKey)

            val error = assertFailsWith<LoginExpiredException> { loader.load(20, PagingRequest.Refresh) }

            assertEquals(accountKey, error.accountKey)
        }

    @Test
    fun emptyCommentsResponseEndsPagination() =
        runTest {
            val service = service { """{"ok":0,"msg":"暂无数据"}""" }
            val loader = StatusCommentRemoteMediator(service, statusKey, accountKey)

            val page = loader.load(20, PagingRequest.Refresh)

            assertEquals(emptyList(), page.data)
            assertNull(page.nextKey)
        }

    @Test
    fun likedByReadsTheMobileListAndPagesPastHiddenUsers() =
        runTest {
            val firstPage =
                """
                {"ok":1,"data":{"data":[{"created_at":"2026-11-15T06:00:00Z","user":{"id":30,"screen_name":"甲"}}],"total_number":99}}
                """.trimIndent()
            val service =
                service { request ->
                    assertEquals("m.weibo.cn", request.url.host)
                    assertEquals("/api/attitudes/show", request.url.encodedPath)
                    assertEquals(HttpMethod.Get, request.method)
                    assertEquals("MLOGIN=1; SUB=example", request.headers[HttpHeaders.Cookie])
                    assertEquals("https://m.weibo.cn/detail/${statusKey.id}", request.headers[HttpHeaders.Referrer])
                    assertEquals("XMLHttpRequest", request.headers["X-Requested-With"])
                    assertEquals("1", request.headers["MWeibo-Pwa"])
                    assertEquals(statusKey.id, request.url.parameters["id"])
                    when (request.url.parameters["page"]) {
                        "1" -> firstPage
                        "2" -> """{"ok":1,"data":{"data":[{"user":null},{"user":{"screen_name":"用户不可用"}}]}}"""
                        "3" -> """{"ok":1,"data":{"data":[{"user":{"id":31,"screen_name":"乙"}}]}}"""
                        "4" -> """{"ok":0,"msg":"这里还没有内容"}"""
                        else -> error("Unexpected Weibo likes page")
                    }
                }
            val loader = StatusLikedByLoader(service, accountKey, statusKey)
            val first = loader.load(20, PagingRequest.Refresh)
            assertEquals("30", assertIs<UiTimelineV2.User>(first.data.single()).value.key.id)
            assertEquals("2", first.nextKey)

            val second = loader.load(20, PagingRequest.Append(assertNotNull(first.nextKey)))
            assertEquals("31", assertIs<UiTimelineV2.User>(second.data.single()).value.key.id)
            assertEquals("4", second.nextKey)

            val last = loader.load(20, PagingRequest.Append(assertNotNull(second.nextKey)))
            assertEquals(emptyList(), last.data)
            assertNull(last.nextKey)
        }

    @Test
    fun likedBySurfacesExpiredSessionsAndUnexpectedErrors() =
        runTest {
            val expired = StatusLikedByLoader(service { """{"ok":-100}""" }, accountKey, statusKey)
            val error = assertFailsWith<LoginExpiredException> { expired.load(20, PagingRequest.Refresh) }
            assertEquals(accountKey, error.accountKey)

            val failed = StatusLikedByLoader(service { """{"ok":0,"msg":"访问频繁"}""" }, accountKey, statusKey)
            assertFailsWith<IllegalStateException> { failed.load(20, PagingRequest.Refresh) }
        }

    @Test
    fun commentRepliesTimelinePageLoadsRootAndChildrenInOneRequest() =
        runTest {
            var hotFlowChildRequests = 0
            val source =
                VVODataSource(
                    accountKey = accountKey,
                    credentialFlow = flowOf(VVoCredential(chocolate = "MLOGIN=1; SUB=example")),
                    httpClientFactory = mockClient { request ->
                        assertEquals("/comments/hotFlowChild", request.url.encodedPath)
                        assertEquals("900", request.url.parameters["cid"])
                        hotFlowChildRequests++
                        """
                        {
                          "ok": 1,
                          "rootComment": [{"id": "900", "text": "顶楼评论", "comments": [{"id": "901", "text": "预览"}]}],
                          "data": [{"id": "901", "text": "子评论"}],
                          "max_id": 12345
                        }
                        """.trimIndent()
                    },
                )
            val pages = assertNotNull(source.capabilities.timelinePage)
            val loader =
                assertIs<dev.dimension.flare.data.datasource.microblog.paging.CacheableRemoteLoader<UiTimelineV2>>(
                    pages.timelinePage("comment_replies", mapOf("commentId" to "900", "rootStatusId" to statusKey.id)),
                )
            assertFalse(loader.collapseReplyChains)
            val page = loader.load(20, PagingRequest.Refresh)
            assertEquals(1, hotFlowChildRequests)
            assertEquals("12345", page.nextKey)
            assertEquals(2, page.data.size)
            val root = assertNotNull(page.data[0].contentPostOrNull())
            assertEquals("900", root.statusKey.id)
            val child = assertNotNull(page.data[1].contentPostOrNull())
            assertEquals("901", child.statusKey.id)
            for (item in listOf(root, child)) {
                val replyAction =
                    item.actions.filterIsInstance<ActionMenu.Item>().single { it.actionFamily == PostActionFamily.Comment }
                val replyRoute =
                    assertIs<DeeplinkRoute.Compose.VVOReplyComment>(
                        DeeplinkRoute.parse(assertIs<ClickEvent.Deeplink>(replyAction.clickEvent).url),
                    )
                assertEquals(statusKey.id, replyRoute.rootId)
            }
        }

    @Test
    fun freshThreadRootRetainsTheCachedNotificationTargetWhenTheResponseOmitsTheOriginalPost() =
        runTest {
            val cached =
                dev.dimension.flare.common.JSON
                    .decodeFromString<dev.dimension.flare.data.network.vvo.model.Comment>(
                        """{"id":"900","text":"notification","status":{"id":"51001","mid":"51001"}}""",
                    ).render(accountKey)
                    .contentPostOrNull()!!
            val loader =
                CommentChildRemoteMediator(
                    service = service { """{"ok":1,"rootComment":[{"id":"900","text":"updated"}],"data":[]}""" },
                    commentKey = cached.statusKey,
                    accountKey = accountKey,
                    cachedRootComment = { cached },
                )
            val root = assertNotNull(loader.load(20, PagingRequest.Refresh).data.single().contentPostOrNull())
            val route = assertIs<DeeplinkRoute.Status.Detail>(DeeplinkRoute.parse(assertIs<ClickEvent.Deeplink>(root.clickEvent).url))
            assertEquals(MicroBlogKey("51001", accountKey.host), route.statusKey)
        }

    @Test
    fun commentRepliesFallsBackToCachedRootOnNoDataAndStopsOnNonAdvancingCursor() =
        runTest {
            val commentKey = MicroBlogKey("900", accountKey.host)
            val cachedWithPreview =
                dev.dimension.flare.common.JSON
                    .decodeFromString<dev.dimension.flare.data.network.vvo.model.Comment>(
                        """{"id":"900","text":"缓存顶楼","comments":[{"id":"901","text":"预览"}]}""",
                    ).render(accountKey, statusKey.id)
                    .contentPostOrNull()!!
            var step = 0
            val loader =
                CommentChildRemoteMediator(
                    service =
                        service { request ->
                            when (step++) {
                                0 -> """<!DOCTYPE html><html><head><title>微博-出错了</title></head><body><p class="h5-4con">暂无数据</p></body></html>"""
                                1 -> {
                                    assertEquals("42", request.url.parameters["max_id"])
                                    """{"ok":1,"data":[{"id":"902","text":"追加"}],"max_id":42}"""
                                }
                                else -> """{"ok":1,"data":[{"id":"903","text":"负数游标"}],"max_id":-1}"""
                            }
                        },
                    commentKey = commentKey,
                    accountKey = accountKey,
                    rootStatusId = statusKey.id,
                    cachedRootComment = { cachedWithPreview },
                )
            val emptyRefresh = loader.load(20, PagingRequest.Refresh)
            assertNull(emptyRefresh.nextKey)
            val fallbackRoot = assertIs<UiTimelineV2.Post>(emptyRefresh.data.single())
            assertEquals("900", fallbackRoot.statusKey.id)
            assertEquals(cachedWithPreview.references, fallbackRoot.references)

            val stalledAppend = loader.load(20, PagingRequest.Append("42"))
            assertEquals("902", stalledAppend.data.single().contentPostOrNull()?.statusKey?.id)
            assertNull(stalledAppend.nextKey)

            val negativeAppend = loader.load(20, PagingRequest.Append("50"))
            assertNull(negativeAppend.nextKey)

            val invalidCursorAppend = loader.load(20, PagingRequest.Append("0"))
            assertTrue(invalidCursorAppend.data.isEmpty())
            assertNull(invalidCursorAppend.nextKey)
        }

    @Test
    fun commentRepliesRejectsInvalidParametersAndBumpsRevisionAfterVvoCommentReply() =
        runTest {
            var replied = false
            val source =
                VVODataSource(
                    accountKey = accountKey,
                    credentialFlow = flowOf(VVoCredential(chocolate = "MLOGIN=1; SUB=example")),
                    httpClientFactory = mockClient { request ->
                        assertEquals("/api/comments/reply", request.url.encodedPath)
                        replied = true
                        """{"ok":1,"data":{"id":"999"}}"""
                    },
                )
            val pages = assertNotNull(source.capabilities.timelinePage)
            assertFailsWith<IllegalArgumentException> {
                pages.timelinePage("unknown", mapOf("commentId" to "900"))
            }
            assertFailsWith<IllegalArgumentException> {
                pages.timelinePage("comment_replies", emptyMap())
            }
            assertFailsWith<IllegalArgumentException> {
                pages.timelinePage("comment_replies", mapOf("commentId" to "  "))
            }
            assertEquals(0L, pages.revision.value)
            source.publish(
                dev.dimension.flare.data.datasource.microblog.ComposeData(
                    content = "回复子评论",
                    referenceStatus =
                        dev.dimension.flare.data.datasource.microblog.ComposeData.ReferenceStatus(
                            dev.dimension.flare.ui.presenter.compose.ComposeStatus.VVOComment(
                                statusKey = MicroBlogKey("900", accountKey.host),
                                rootId = statusKey.id,
                            ),
                        ),
                ),
            ) {}
            assertTrue(replied)
            assertEquals(1L, pages.revision.value)
        }

    @Test
    fun childCommentsTargetParentFloorRouteInsteadOfSelf() =
        runTest {
            val source =
                VVODataSource(
                    accountKey = accountKey,
                    credentialFlow = flowOf(VVoCredential(chocolate = "MLOGIN=1; SUB=example")),
                    httpClientFactory = mockClient {
                        """
                        {
                          "ok": 1,
                          "rootComment": [{"id": "900", "text": "顶楼评论"}],
                          "data": [{"id": "901", "text": "子评论"}],
                          "max_id": 0
                        }
                        """.trimIndent()
                    },
                )
            val loader =
                source.capabilities.timelinePage!!
                    .timelinePage("comment_replies", mapOf("commentId" to "900", "rootStatusId" to statusKey.id))
            val page = loader.load(20, PagingRequest.Refresh)
            val child = assertNotNull(page.data[1].contentPostOrNull())
            val childRoute =
                assertIs<DeeplinkRoute.Timeline.Page>(
                    DeeplinkRoute.parse(assertIs<ClickEvent.Deeplink>(child.clickEvent).url),
                )
            assertEquals("900", childRoute.parameters["commentId"])
            assertEquals(MicroBlogKey("900", accountKey.host), childRoute.detailStatusKey)
        }

    @Test
    fun appendRetainsRootStatusIdResolvedFromRefreshRootComment() =
        runTest {
            val loader =
                CommentChildRemoteMediator(
                    service =
                        service { request ->
                            if (request.url.parameters["max_id"] == null) {
                                """
                                {
                                  "ok": 1,
                                  "rootComment": [{"id": "900", "text": "顶楼", "status": {"id": "${statusKey.id}", "mid": "${statusKey.id}"}}],
                                  "data": [],
                                  "max_id": 100
                                }
                                """.trimIndent()
                            } else {
                                """{"ok": 1, "data": [{"id": "902", "text": "第二页子评论"}], "max_id": 0}"""
                            }
                        },
                    commentKey = MicroBlogKey("900", accountKey.host),
                    accountKey = accountKey,
                )
            val first = loader.load(20, PagingRequest.Refresh)
            val second = loader.load(20, PagingRequest.Append(assertNotNull(first.nextKey)))
            val appendedChild = assertNotNull(second.data.single().contentPostOrNull())
            val replyAction =
                appendedChild.actions.filterIsInstance<ActionMenu.Item>().single { it.actionFamily == PostActionFamily.Comment }
            val replyRoute =
                assertIs<DeeplinkRoute.Compose.VVOReplyComment>(
                    DeeplinkRoute.parse(assertIs<ClickEvent.Deeplink>(replyAction.clickEvent).url),
                )
            assertEquals(statusKey.id, replyRoute.rootId)
        }

    @Test
    fun cachedRootLookupPropagatesCancellationException() =
        runTest {
            org.koin.core.context.startKoin {
                modules(
                    org.koin.dsl.module {
                        single<dev.dimension.flare.data.datasource.microblog.MicroblogCacheLookup> {
                            object : dev.dimension.flare.data.datasource.microblog.MicroblogCacheLookup {
                                override suspend fun findProfiles(
                                    keys: Collection<MicroBlogKey>,
                                ): Map<MicroBlogKey, dev.dimension.flare.ui.model.UiProfile> = emptyMap()

                                override suspend fun findPost(
                                    accountType: dev.dimension.flare.model.AccountType,
                                    statusKey: MicroBlogKey,
                                ): UiTimelineV2.Post = throw kotlinx.coroutines.CancellationException("cancelled")
                            }
                        }
                    },
                )
            }
            try {
                val source =
                    VVODataSource(
                        accountKey = accountKey,
                        credentialFlow = flowOf(VVoCredential(chocolate = "MLOGIN=1; SUB=example")),
                        httpClientFactory = mockClient {
                            """<!DOCTYPE html><html><head><title>微博-出错了</title></head><body><p class="h5-4con">暂无数据</p></body></html>"""
                        },
                    )
                val loader = source.capabilities.timelinePage!!.timelinePage("comment_replies", mapOf("commentId" to "900"))
                assertFailsWith<kotlinx.coroutines.CancellationException> {
                    loader.load(20, PagingRequest.Refresh)
                }
            } finally {
                org.koin.core.context.stopKoin()
            }
        }

    private fun mockClient(webResponse: (HttpRequestData) -> String): (io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient =
        { config ->
            HttpClient(
                MockEngine { request ->
                    val body =
                        if (request.url.encodedPath == "/api/config") {
                            """{"ok":1,"data":{"login":true,"st":"st-test"}}"""
                        } else {
                            webResponse(request)
                        }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ) { config.invoke(this) }
        }

    private fun service(webResponse: (HttpRequestData) -> String): VVOService =
        VVOService(
            credentialFlow = flowOf(VVoCredential(chocolate = "MLOGIN=1; SUB=example")),
            httpClientFactory = mockClient(webResponse),
        )

    private val accountKey = MicroBlogKey(id = "23", host = "m.weibo.cn")
    private val statusKey = MicroBlogKey(id = "5357831171867297", host = "m.weibo.cn")
}
