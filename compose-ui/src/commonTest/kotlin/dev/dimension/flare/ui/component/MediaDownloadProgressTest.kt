package dev.dimension.flare.ui.component

import coil3.annotation.ExperimentalCoilApi
import coil3.network.NetworkHeaders
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.BufferedSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.fail

@OptIn(ExperimentalCoilApi::class)
class MediaDownloadProgressTest {
    @Test
    fun reportsBytesDuringTheTransferAndPreservesTheLastPartialBuffer() =
        runTest {
            val updates = mutableListOf<Float>()
            val body =
                object : NetworkResponseBody by NetworkResponseBody(Buffer()) {
                    override suspend fun writeTo(sink: BufferedSink) {
                        sink.writeUtf8("1234").emit()
                        assertEquals(listOf(0f, 0.4f), updates)
                        sink.writeUtf8("567890")
                    }
                }
            val headers = NetworkHeaders.Builder().set("Content-Length", "10").build()
            val response = NetworkResponse(headers = headers, body = body).reportingProgress(updates::add)
            val sink = Buffer()
            checkNotNull(response.body).writeTo(sink)
            assertEquals(listOf(0f, 0.4f, 1f), updates)
            assertEquals("1234567890", sink.readUtf8())
        }

    @Test
    fun onlyUsesTheLengthOfACompleteUnencodedResponse() {
        val body = NetworkResponseBody(Buffer())
        val known = NetworkHeaders.Builder().set("Content-Length", "10").build()
        val encoded = known.newBuilder().set("Content-Encoding", "gzip").build()
        val chunked = known.newBuilder().set("Transfer-Encoding", "chunked").build()
        val unreliableResponses =
            listOf(
                NetworkResponse(body = body),
                NetworkResponse(headers = known.newBuilder().set("Content-Length", "0").build(), body = body),
                NetworkResponse(headers = known.newBuilder().set("Content-Length", "unknown").build(), body = body),
                NetworkResponse(headers = encoded, body = body),
                NetworkResponse(headers = chunked, body = body),
                NetworkResponse(code = 206, headers = known, body = body),
                NetworkResponse(code = 404, headers = known, body = body),
            )
        for (response in unreliableResponses) {
            assertSame(response, response.reportingProgress { fail("Unexpected download fraction: $it") })
        }
    }
}
