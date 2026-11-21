package dev.dimension.flare.data.datasource.microblog.handler

import dev.dimension.flare.data.datasource.microblog.DirectMessageDataSource
import dev.dimension.flare.data.datasource.microblog.MicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.UnavailableMicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.loader.DirectMessageDelta
import dev.dimension.flare.data.datasource.microblog.loader.DirectMessageLoader
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiDMItem
import dev.dimension.flare.ui.model.UiDMRoom
import dev.dimension.flare.ui.model.UiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectMessageHandlerCanSendCacheTest {
    private val accountKey = MicroBlogKey("alice", "example.social")
    private val targetUser = MicroBlogKey("bob", "example.social")
    private val otherUser = MicroBlogKey("carol", "example.social")

    @Test
    fun cachesResolvedCanSendAndUpdatesOnRefreshWithoutCachingFailures() =
        runTest {
            var nextResult: Result<Boolean> = Result.success(true)
            val handler =
                DirectMessageHandler(
                    accountKey = accountKey,
                    loader =
                        object : DirectMessageLoader {
                            override val platformId: String = "test"

                            override suspend fun loadRooms(
                                pageSize: Int,
                                request: PagingRequest,
                            ): PagingResult<UiDMRoom> = error("unused")

                            override suspend fun loadMessages(
                                roomKey: MicroBlogKey,
                                pageSize: Int,
                                request: PagingRequest,
                            ): PagingResult<UiDMItem> = error("unused")

                            override suspend fun fetchRoomInfo(roomKey: MicroBlogKey): UiDMRoom? = error("unused")

                            override suspend fun sendMessage(
                                roomKey: MicroBlogKey,
                                message: String,
                            ): UiDMItem = error("unused")

                            override suspend fun deleteMessage(
                                roomKey: MicroBlogKey,
                                messageKey: MicroBlogKey,
                            ): Unit = error("unused")

                            override suspend fun fetchNewMessages(
                                roomKey: MicroBlogKey,
                                cursor: String?,
                            ): DirectMessageDelta = error("unused")

                            override suspend fun leaveRoom(roomKey: MicroBlogKey): Unit = error("unused")

                            override fun createRoom(userKey: MicroBlogKey): Flow<UiState<UiDMRoom>> = error("unused")

                            override suspend fun canSend(userKey: MicroBlogKey): Boolean = nextResult.getOrThrow()

                            override suspend fun loadBadgeCount(): Int = error("unused")
                        },
                    coroutineScope = backgroundScope,
                )
            val dataSource =
                object : DirectMessageDataSource, MicroblogDataSource by UnavailableMicroblogDataSource {
                    override val accountKey: MicroBlogKey = this@DirectMessageHandlerCanSendCacheTest.accountKey
                    override val directMessageHandler: DirectMessageHandler = handler
                }

            assertNull(handler.cachedCanSend(targetUser))
            assertNull(dataSource.cachedCanSendDirectMessage(targetUser))

            nextResult = Result.success(true)
            assertTrue(dataSource.canSendDirectMessage(targetUser))
            assertEquals(true, handler.cachedCanSend(targetUser))
            assertEquals(true, dataSource.cachedCanSendDirectMessage(targetUser))

            nextResult = Result.failure(IllegalStateException("offline"))
            assertFailsWith<IllegalStateException> { dataSource.canSendDirectMessage(targetUser) }
            assertEquals(true, handler.cachedCanSend(targetUser))
            assertNull(handler.cachedCanSend(otherUser))
            assertFailsWith<IllegalStateException> { dataSource.canSendDirectMessage(otherUser) }
            assertNull(handler.cachedCanSend(otherUser))

            nextResult = Result.success(false)
            assertFalse(dataSource.canSendDirectMessage(targetUser))
            assertEquals(false, handler.cachedCanSend(targetUser))
            assertEquals(false, dataSource.cachedCanSendDirectMessage(targetUser))
        }
}
