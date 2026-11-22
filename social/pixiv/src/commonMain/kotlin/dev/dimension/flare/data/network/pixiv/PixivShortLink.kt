package dev.dimension.flare.data.network.pixiv

import dev.dimension.flare.data.network.ktorClient
import dev.dimension.flare.data.platform.PIXIV_HOST
import dev.dimension.flare.model.PlatformShortLink
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.head
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodedPath
import kotlinx.coroutines.CancellationException

private const val PIXIV_SHORT_LINK_HOST = "pixiv.me"
private val pixivIdPattern = Regex("[A-Za-z0-9_-]+")
private val pixivProfilePath = Regex("/(?:en/)?users/[0-9]+/?")
private val pixivProfileHosts = setOf(PIXIV_HOST, "www.$PIXIV_HOST")
private val redirectStatuses =
    setOf(
        HttpStatusCode.MovedPermanently,
        HttpStatusCode.Found,
        HttpStatusCode.SeeOther,
        HttpStatusCode.TemporaryRedirect,
        HttpStatusCode.PermanentRedirect,
    )

private val pixivShortLinkClientConfig: HttpClientConfig<*>.() -> Unit = {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = 3_000
    }
}
private val defaultPixivShortLinkClient by lazy { ktorClient(protectRequestHeaders = true, config = pixivShortLinkClientConfig) }

internal val pixivShortLink = PlatformShortLink(PIXIV_SHORT_LINK_HOST) { resolvePixivShortLink(it) }

internal fun createPixivShortLinkClient(engine: HttpClientEngine): HttpClient = HttpClient(engine, pixivShortLinkClientConfig)

internal suspend fun resolvePixivShortLink(
    url: String,
    client: HttpClient? = null,
): String? {
    val original = runCatching { Url(url) }.getOrNull() ?: return null
    if (original.host.lowercase() != PIXIV_SHORT_LINK_HOST) return null
    if (original.protocol != URLProtocol.HTTP && original.protocol != URLProtocol.HTTPS) return null
    if (original.port != original.protocol.defaultPort || original.user != null || original.password != null) return null
    val pixivId = original.encodedPath.removePrefix("/").removeSuffix("/")
    if (!pixivIdPattern.matches(pixivId)) return null

    val response =
        try {
            (client ?: defaultPixivShortLinkClient).head("https://$PIXIV_SHORT_LINK_HOST/$pixivId")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
    if (response.status !in redirectStatuses) return null
    val location = response.headers[HttpHeaders.Location] ?: return null
    val target = runCatching { Url(location) }.getOrNull() ?: return null
    if (target.protocol != URLProtocol.HTTPS || target.port != URLProtocol.HTTPS.defaultPort) return null
    if (target.host.lowercase() !in pixivProfileHosts || target.user != null || target.password != null) return null
    val path = target.encodedPath
    return if (pixivProfilePath.matches(path)) "https://www.$PIXIV_HOST$path" else null
}
