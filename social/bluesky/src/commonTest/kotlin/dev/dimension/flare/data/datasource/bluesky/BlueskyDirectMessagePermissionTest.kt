package dev.dimension.flare.data.datasource.bluesky

import dev.dimension.flare.data.datasource.microblog.handler.DirectMessageHandler
import dev.dimension.flare.model.MicroBlogKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import sh.christian.ozone.XrpcBlueskyApi
import sh.christian.ozone.api.response.AtpException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlueskyDirectMessagePermissionTest {
    private val account = MicroBlogKey("did:plc:alice", "bsky.social")
    private val recipient = MicroBlogKey("did:plc:bob", "bsky.social")
    private var status = HttpStatusCode.OK
    private var canChat = true
    private var transportFailure: Exception? = null
    private val client =
        HttpClient(
            MockEngine { request ->
                assertEquals("/xrpc/chat.bsky.convo.getConvoAvailability", request.url.encodedPath)
                assertEquals(listOf(recipient.id), request.url.parameters.getAll("members"))
                transportFailure?.let { throw it }
                respond("""{"canChat":$canChat}""", status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
    private val api = XrpcBlueskyApi(client)
    private val loader = BlueskyDirectMessageLoader({ api }, account)

    @AfterTest
    fun tearDown() = client.close()

    @Test
    fun anExplicitDenialReplacesTheCachedPermission() =
        runTest {
            val handler = DirectMessageHandler(account, loader, backgroundScope)
            assertTrue(handler.canSend(recipient))
            canChat = false
            assertFalse(handler.canSend(recipient))
            assertEquals(false, handler.cachedCanSend(recipient))
        }

    @Test
    fun transportFailureAndCancellationPreserveTheLastKnownPermission() =
        runTest {
            val handler = DirectMessageHandler(account, loader, backgroundScope)
            assertTrue(handler.canSend(recipient))

            transportFailure = IOException("offline")
            assertFailsWith<IOException> { handler.canSend(recipient) }
            assertEquals(true, handler.cachedCanSend(recipient))

            transportFailure = CancellationException("cancelled")
            assertFailsWith<CancellationException> { handler.canSend(recipient) }
            assertEquals(true, handler.cachedCanSend(recipient))
        }

    @Test
    fun aFailureStatusTakesPrecedenceOverTheResponseBody() =
        runTest {
            val handler = DirectMessageHandler(account, loader, backgroundScope)
            assertTrue(handler.canSend(recipient))
            canChat = false
            val failures = listOf(HttpStatusCode.Unauthorized, HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable)
            for (failedStatus in failures) {
                status = failedStatus
                val error = assertFailsWith<AtpException> { handler.canSend(recipient) }
                assertEquals(failedStatus.value, error.statusCode.code)
                assertEquals(true, handler.cachedCanSend(recipient))
            }
        }
}
