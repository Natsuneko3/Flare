package dev.dimension.flare.data.datasource.vvo

import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.network.vvo.VVOService
import dev.dimension.flare.data.platform.VVoCredential
import dev.dimension.flare.model.MicroBlogKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LikeRemoteMediatorTest {
    @Test
    fun bothPagesUseTheSelectedAccountsContainer() =
        runTest {
            val accounts =
                listOf(
                    "1000000001" to "2308691000000001_-_mix",
                    "9000000001" to "2308699000000001_-_mix",
                )

            for ((uid, containerId) in accounts) {
                val requests = mutableListOf<Pair<String, String?>>()
                val clients = mutableListOf<HttpClient>()
                val engine =
                    MockEngine { request ->
                        val body =
                            when (request.url.encodedPath) {
                                "/api/config" -> {
                                    """{"ok":1,"data":{"login":true,"uid":"$uid"}}"""
                                }

                                "/api/container/getIndex" -> {
                                    val page = request.url.parameters["page"]
                                    requests += assertNotNull(request.url.parameters["containerid"]) to page
                                    if (page == null) {
                                        """{"ok":1,"data":{"cards":[],"cardlistInfo":{"page":2}}}"""
                                    } else {
                                        """{"ok":1,"data":{"cards":[]}}"""
                                    }
                                }

                                else -> {
                                    error("Unexpected path: ${request.url.encodedPath}")
                                }
                            }
                        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    }

                try {
                    val service =
                        VVOService(
                            credentialFlow = flowOf(VVoCredential(chocolate = "MLOGIN=1; SUB=test")),
                            httpClientFactory = { config ->
                                HttpClient(engine) { config.invoke(this) }.also { clients += it }
                            },
                        )
                    val loader = LikeRemoteMediator(service, MicroBlogKey(uid, "m.weibo.cn"))
                    val firstPage = loader.load(20, PagingRequest.Refresh)
                    loader.load(20, PagingRequest.Append(assertNotNull(firstPage.nextKey)))

                    assertEquals(listOf(containerId to null, containerId to "2"), requests)
                } finally {
                    clients.forEach(HttpClient::close)
                    engine.close()
                }
            }
        }
}
