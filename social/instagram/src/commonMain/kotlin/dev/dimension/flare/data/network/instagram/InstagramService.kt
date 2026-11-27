package dev.dimension.flare.data.network.instagram

import dev.dimension.flare.common.JSON
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.network.instagram.api.createInstagramApiResources
import dev.dimension.flare.data.network.instagram.api.createInstagramWebResources
import dev.dimension.flare.data.network.ktorfit
import dev.dimension.flare.data.platform.InstagramCredential
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal data class InstagramPage(
    val items: List<JsonObject>,
    val nextCursor: String?,
)

internal fun invalidInstagramResponse(): Nothing = throw InstagramRequestException("Instagram returned an unexpected response.")

internal class InstagramService(
    private val credentialFlow: Flow<InstagramCredential>,
    private val onCredentialRefreshed: suspend (InstagramCredential, InstagramCredential) -> Unit = { _, _ -> },
    private val proxy: ProxyRef = ProxyRef.Global,
) {
    // Keep WebView cookies on the origin where they were collected, including REST calls.
    private fun client(path: String) =
        ktorfit("https://www.instagram.com/" + path, proxy = proxy) {
            followRedirects = false
            expectSuccess = false
            install(InstagramHeaderPlugin) { credentials = credentialFlow }
        }

    private val api by lazy { client("api/v1/").createInstagramApiResources() }
    private val web by lazy { client("").createInstagramWebResources() }

    private class BootstrapCache(
        val session: String,
        val created: TimeMark,
        val data: InstagramBootstrap,
    )

    private var cached: BootstrapCache? = null

    private suspend fun <T> guarded(
        explicitLogin: Boolean = false,
        block: suspend InstagramCircuitBreaker.(InstagramCredential) -> T,
    ): T {
        val account = credentialFlow.first()
        val gate = InstagramCircuitBreaker.forAccount(account.dsUserId)
        return gate.run(explicitLogin) { gate.block(credentialFlow.first()) }
    }

    private suspend fun InstagramCircuitBreaker.read(
        response: HttpResponse,
        html: Boolean = false,
    ): Pair<String, List<JsonObject>> {
        val body =
            response
                .bodyAsText()
                .trim()
                .removePrefix("for (;;);")
                .trimStart()
        val frames =
            if (body.startsWith("{")) {
                val single = runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull()
                if (single != null) {
                    listOf(single)
                } else {
                    body
                        .lineSequence()
                        .filter { it.isNotBlank() }
                        .map { runCatching { JSON.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                        .toList()
                        .let { lines -> if (lines.any { it == null }) emptyList() else lines.filterNotNull() }
                }
            } else {
                emptyList()
            }
        val isLogin =
            body.startsWith("<") && (
                body.contains("name=\"password\"", ignoreCase = true) ||
                    body.contains("name='password'", ignoreCase = true) ||
                    (!html && body.contains("/accounts/login/"))
            )
        classify(response.status.value, frames, response.headers[HttpHeaders.Location], response.headers[HttpHeaders.RetryAfter], isLogin)
        val previous = response.request.attributes[requestCredential]
        val next = previous.rotated(response.headers.getAll(HttpHeaders.SetCookie).orEmpty())
        if (next != previous) onCredentialRefreshed(previous, next)
        if (!html && frames.isEmpty()) invalidInstagramResponse()
        return body to frames
    }

    private suspend fun InstagramCircuitBreaker.json(response: HttpResponse): JsonObject =
        read(response).second.singleOrNull() ?: invalidInstagramResponse()

    private suspend fun InstagramCircuitBreaker.bootstrap(
        credential: InstagramCredential,
        force: Boolean = false,
    ): InstagramBootstrap {
        cached?.takeIf { !force && it.session == credential.sessionId && it.created.elapsedNow() < 15.minutes }?.let { return it.data }
        val data = InstagramBootstrap(read(web.bootstrap(), html = true).first)
        data.requireViewer(credential.dsUserId)
        return data.also { cached = BootstrapCache(credential.sessionId, TimeSource.Monotonic.markNow(), it) }
    }

    suspend fun verifySession(): JsonObject =
        guarded(explicitLogin = true) { credential ->
            bootstrap(credential, force = true)
            checkedUser(json(api.user(credential.dsUserId)), credential.dsUserId)
        }

    suspend fun following(cursor: String?): InstagramPage =
        guarded { credential ->
            val tokens = bootstrap(credential)
            try {
                val response =
                    web.following(
                        tokens.lsd,
                        FOLLOWING_QUERY,
                        FOLLOWING_FIELD,
                        "https://www.instagram.com/?variant=following",
                        tokens.form(cursor, credential.igDid),
                    )
                followingPage(read(response).second)
            } catch (error: Exception) {
                cached = null
                throw error
            }
        }

    suspend fun userById(id: String): JsonObject = guarded { checkedUser(json(api.user(id)), id) }

    suspend fun userByHandle(handle: String): JsonObject =
        guarded {
            val data = json(api.profile(handle))["data"] as? JsonObject
            val user = data?.get("user") as? JsonObject ?: invalidInstagramResponse()
            user.takeIf { it.string("username").equals(handle, ignoreCase = true) } ?: invalidInstagramResponse()
        }

    suspend fun userPosts(
        id: String,
        cursor: String?,
    ): InstagramPage =
        guarded {
            val data = json(api.posts(id, cursor))
            InstagramPage(data.items("items"), data.string("next_max_id").takeUnless { data.string("more_available") == "false" })
        }

    suspend fun post(id: String): JsonObject =
        guarded {
            json(api.media(id)).items("items").firstOrNull { (it.string("pk") ?: it.string("id")?.substringBefore('_')) == id }
                ?: invalidInstagramResponse()
        }

    suspend fun comments(
        id: String,
        cursor: String?,
    ): InstagramPage =
        guarded {
            val min = cursor?.takeIf { it.startsWith("min:") }?.removePrefix("min:")
            val max = cursor?.takeIf { it.startsWith("max:") }?.removePrefix("max:")
            val data = json(api.comments(id, min, max))
            val items = if (data.string("comments_disabled") == "true") emptyList() else data.items("comments")
            val nextMin = data.string("next_min_id").takeUnless { data.string("has_more_headload_comments") == "false" }
            val nextMax = data.string("next_max_id").takeUnless { data.string("has_more_comments") == "false" }
            InstagramPage(items, nextMin?.let { "min:" + it } ?: nextMax?.let { "max:" + it })
        }

    private fun checkedUser(
        data: JsonObject,
        id: String,
    ): JsonObject {
        val user = data["user"] as? JsonObject ?: invalidInstagramResponse()
        if ((user.string("pk") ?: user.string("id")) != id || user.string("username").isNullOrBlank()) invalidInstagramResponse()
        return user
    }

    private fun JsonObject.items(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.filterIsInstance<JsonObject>() ?: invalidInstagramResponse()
}
