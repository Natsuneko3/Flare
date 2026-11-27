package dev.dimension.flare.data.network.instagram

import dev.dimension.flare.data.platform.INSTAGRAM_WEB_HOST
import dev.dimension.flare.data.platform.InstagramCredential
import dev.dimension.flare.data.platform.cookieHeader
import dev.dimension.flare.data.platform.validInstagramCookieValue
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.util.AttributeKey
import io.ktor.http.fromHttpToGmtDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

internal val requestCredential = AttributeKey<InstagramCredential>("InstagramRequestCredential")
internal class InstagramHeaderConfig {
    lateinit var credentials: Flow<InstagramCredential>
}

internal val InstagramHeaderPlugin = createClientPlugin("InstagramHeaders", ::InstagramHeaderConfig) {
    val credentials = pluginConfig.credentials
    onRequest { request, _ ->
        require(request.url.protocol == URLProtocol.HTTPS && request.url.host == INSTAGRAM_WEB_HOST &&
            (request.url.port == 443 || request.url.port == 0))
        val credential = credentials.first()
        require(listOfNotNull(credential.sessionId, credential.csrfToken, credential.dsUserId, credential.mid, credential.rur, credential.igDid)
            .all(::validInstagramCookieValue)) { "Invalid Instagram session" }
        request.attributes.put(requestCredential, credential)
        request.headers.apply {
            append(HttpHeaders.Cookie, credential.cookieHeader())
            append("X-CSRFToken", credential.csrfToken)
            append("X-IG-App-ID", "936619743392459")
            append("X-Requested-With", "XMLHttpRequest")
            append(HttpHeaders.Origin, "https://www.instagram.com")
            if (!contains(HttpHeaders.Referrer)) append(HttpHeaders.Referrer, "https://www.instagram.com/")
            if (!contains(HttpHeaders.Accept)) append(HttpHeaders.Accept, "application/json")
            append(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
            append(HttpHeaders.UserAgent, "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
        }
    }
}

internal fun InstagramCredential.rotated(headers: List<String>): InstagramCredential {
    var next = this
    for (line in headers) {
        val parts = line.split(';')
        val name = parts.first().substringBefore('=').trim()
        if (name != "rur" && name != "csrftoken") continue
        val attrs = parts.drop(1).associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=', "").trim() }
        val domain = attrs["domain"]?.trimStart('.')?.lowercase()
        if ((domain != null && domain != INSTAGRAM_WEB_HOST && domain != "instagram.com") || attrs["path"] != "/") continue
        val value = parts.first().substringAfter('=', "").trim()
        val age = attrs["max-age"]?.toLongOrNull()
        val expiry = attrs["expires"]?.let { runCatching { it.fromHttpToGmtDate().timestamp }.getOrNull() }
        val expired = age?.let { it <= 0 } ?: (expiry != null && expiry <= Clock.System.now().toEpochMilliseconds())
        if (expired || value.isEmpty()) {
            if (name == "csrftoken") throw InstagramAuthenticationRequired()
            next = next.copy(rur = null)
        } else if (validInstagramCookieValue(value)) {
            next = if (name == "csrftoken") next.copy(csrfToken = value) else next.copy(rur = value)
        }
    }
    return next
}
