package dev.dimension.flare.data.network.instagram

import dev.dimension.flare.common.JSON
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.network.instagram.api.createInstagramApiResources
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
    private val api by lazy {
        ktorfit("https://www.instagram.com/api/v1/", proxy = proxy) {
            followRedirects = false
            expectSuccess = false
            install(InstagramHeaderPlugin) { credentials = credentialFlow }
        }.createInstagramApiResources()
    }

    private suspend fun <T> guarded(
        explicitLogin: Boolean = false,
        block: suspend InstagramCircuitBreaker.(InstagramCredential) -> T,
    ): T {
        val account = credentialFlow.first()
        val gate = InstagramCircuitBreaker.forAccount(account.dsUserId)
        return gate.run(explicitLogin) { gate.block(credentialFlow.first()) }
    }

    private suspend fun InstagramCircuitBreaker.json(response: HttpResponse): JsonObject {
        val body = response.bodyAsText().trim().removePrefix("for (;;);").trimStart()
        val data = if (body.startsWith("{")) runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull() else null
        classify(
            response.status.value,
            listOfNotNull(data),
            response.headers[HttpHeaders.Location],
            response.headers[HttpHeaders.RetryAfter],
            body.startsWith("<") && body.contains("/accounts/login/"),
        )
        val previous = response.request.attributes[requestCredential]
        val next = previous.rotated(response.headers.getAll(HttpHeaders.SetCookie).orEmpty())
        if (next != previous) onCredentialRefreshed(previous, next)
        return data ?: invalidInstagramResponse()
    }

    suspend fun verifySession(): JsonObject =
        guarded(explicitLogin = true) { credential -> checkedUser(json(api.currentUser()), credential.dsUserId) }

    suspend fun home(cursor: String?): InstagramPage = guarded { homePage(json(api.home(cursor))) }

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
