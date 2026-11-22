package dev.dimension.flare.data.datasource.xqt

import de.jensklingenberg.ktorfit.converter.ResponseConverterFactory
import de.jensklingenberg.ktorfit.ktorfit
import dev.dimension.flare.data.network.nullableFallbackJson
import dev.dimension.flare.data.network.xqt.XQT_JSON
import dev.dimension.flare.data.network.xqt.api.createV11GetApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XQTDirectMessagePermissionTest {
    private val recipient = "2"
    private var status = HttpStatusCode.OK
    private var body = "{}"
    private var transportFailure: Exception? = null
    private val client =
        HttpClient(
            MockEngine { request ->
                assertEquals("/1.1/dm/permissions.json", request.url.encodedPath)
                assertEquals(recipient, request.url.parameters["recipient_ids"])
                transportFailure?.let { throw it }
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) {
            install(ContentNegotiation) { nullableFallbackJson(XQT_JSON) }
        }
    private val api =
        ktorfit {
            baseUrl("https://example.com/")
            httpClient(client)
            converterFactories(ResponseConverterFactory())
        }.createV11GetApi()

    @AfterTest
    fun tearDown() = client.close()

    @Test
    fun usesTheRequestedRecipientsExplicitPermission() =
        runTest {
            body = """{"permissions":{"id_keys":{"1":{"can_dm":false},"2":{"can_dm":true}}}}"""
            assertTrue(api.canSendDirectMessage(recipient))
            body = """{"permissions":{"id_keys":{"1":{"can_dm":true},"2":{"can_dm":false}}}}"""
            assertFalse(api.canSendDirectMessage(recipient))
        }

    @Test
    fun aFailedOrIncompleteResponseCannotDenyPermission() =
        runTest {
            body = """{"permissions":{"id_keys":{"2":{"can_dm":false}}}}"""
            status = HttpStatusCode.TooManyRequests
            val error = assertFailsWith<IllegalStateException> { api.canSendDirectMessage(recipient) }
            assertContains(error.message.orEmpty(), "429")

            status = HttpStatusCode.OK
            val incompleteResponses =
                listOf("{}", """{"permissions":{"id_keys":{"1":{"can_dm":false}}}}""", """{"permissions":{"id_keys":{"2":{}}}}""")
            for (incomplete in incompleteResponses) {
                body = incomplete
                assertFailsWith<IllegalStateException> { api.canSendDirectMessage(recipient) }
            }
        }

    @Test
    fun transportFailureAndCancellationPropagate() =
        runTest {
            transportFailure = IOException("offline")
            assertFailsWith<IOException> { api.canSendDirectMessage(recipient) }
            transportFailure = CancellationException("cancelled")
            assertFailsWith<CancellationException> { api.canSendDirectMessage(recipient) }
        }
}
