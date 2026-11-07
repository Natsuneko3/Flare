package dev.dimension.flare.feature.agent.runtime

import ai.koog.http.client.KoogHttpClient
import kotlinx.serialization.json.Json

internal class ResponsesHttpClientFactory(
    private val delegate: KoogHttpClient.Factory,
) : KoogHttpClient.Factory {
    override fun create(
        clientName: String,
        baseUrl: String,
        headers: Map<String, String>,
        queryParameters: Map<String, String>,
        requestTimeoutMillis: Long,
        connectTimeoutMillis: Long,
        socketTimeoutMillis: Long,
        json: Json,
    ): KoogHttpClient =
        delegate.create(
            clientName = clientName,
            baseUrl = baseUrl,
            headers = headers,
            queryParameters = queryParameters,
            requestTimeoutMillis = requestTimeoutMillis,
            connectTimeoutMillis = connectTimeoutMillis,
            socketTimeoutMillis = socketTimeoutMillis,
            // Koog's effort enum predates xhigh/max. Ignore unknown optional response metadata
            // while preserving the configured effort in the actual request.
            json = Json(json) { coerceInputValues = true },
        )
}
