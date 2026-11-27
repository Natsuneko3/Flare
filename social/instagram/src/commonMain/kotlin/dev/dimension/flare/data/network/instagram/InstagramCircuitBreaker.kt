package dev.dimension.flare.data.network.instagram

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock

internal open class InstagramRequestException(
    message: String,
) : Exception(message)

internal class InstagramAuthenticationRequired : InstagramRequestException("Sign in to Instagram again and complete any verification.")

internal class InstagramRestricted : InstagramRequestException("Instagram has restricted this session. Check your account in Instagram.")

internal class InstagramRateLimited(
    val until: Long,
) : InstagramRequestException("Instagram is limiting requests. Try again after the cooldown.")

internal class InstagramCircuitBreaker(
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private var blocked: InstagramRequestException? = null

    suspend fun <T> run(
        explicitLogin: Boolean = false,
        action: suspend () -> T,
    ): T =
        mutex.withLock {
            when (val previous = blocked) {
                is InstagramRateLimited -> if (now() < previous.until) throw previous else blocked = null
                null -> Unit
                else -> if (!explicitLogin) throw previous
            }
            try {
                action().also { if (explicitLogin) blocked = null }
            } catch (error: InstagramRequestException) {
                if (error is InstagramAuthenticationRequired || error is InstagramRestricted || error is InstagramRateLimited) {
                    blocked = error
                }
                throw error
            }
        }

    fun classify(
        status: Int,
        frames: List<JsonObject>,
        location: String?,
        retryAfter: String?,
        loginHtml: Boolean,
    ) {
        val messages =
            frames
                .flatMap { root ->
                    val objects = listOf(root) + (root["errors"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                    objects.flatMap { obj ->
                        listOfNotNull(obj.string("message"), obj.string("error_type"), obj.string("error"), obj.string("summary"))
                    }
                }.map { it.lowercase().trim().trimEnd('.') }
        if (status == 429 || messages.any { it in rateMessages }) {
            val seconds = retryAfter?.toLongOrNull()?.coerceIn(900L, 604800L) ?: 900L
            throw InstagramRateLimited(now() + seconds * 1000L)
        }
        if ("feedback_required" in messages) throw InstagramRestricted()
        if (status == 401 || status == 403 || loginHtml || messages.any { it in authMessages } ||
            frames.any { it.containsKey("checkpoint_url") || it["challenge"] is JsonObject } ||
            (status in 300..399 && (location?.substringBefore('?')?.contains(loginRedirect) == true))
        ) {
            throw InstagramAuthenticationRequired()
        }
        if (status !in 200..299) throw InstagramRequestException("Instagram returned HTTP " + status)
        if (frames.any { it.string("status") == "fail" || !(it["errors"] as? JsonArray).isNullOrEmpty() }) {
            throw InstagramRequestException("Instagram could not complete this request.")
        }
    }

    companion object {
        private val accounts = MutableStateFlow(emptyMap<String, InstagramCircuitBreaker>())

        fun forAccount(id: String): InstagramCircuitBreaker =
            accounts.updateAndGet { if (id in it) it else it + (id to InstagramCircuitBreaker()) }.getValue(id)

        private val loginRedirect = Regex("/(?:accounts/login|challenge|checkpoint)(?:/|$)")
        private val rateMessages =
            setOf("rate_limit_error", "rate_limited", "throttled", "too many requests", "please wait a few minutes before you try again")
        private val authMessages = setOf("login_required", "challenge_required", "checkpoint_required")
    }
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it === kotlinx.serialization.json.JsonNull }?.content
