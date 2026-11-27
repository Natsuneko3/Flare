package dev.dimension.flare.data.platform

import kotlinx.serialization.Serializable
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
public const val INSTAGRAM_HOST: String = "instagram.com"

@HiddenFromObjC
public const val INSTAGRAM_WEB_HOST: String = "www.instagram.com"

@Serializable
public data class InstagramCredential(
    val sessionId: String,
    val csrfToken: String,
    val dsUserId: String,
    val mid: String? = null,
    val igDid: String? = null,
    val rur: String? = null,
    val username: String? = null,
) {
    override fun toString(): String = "InstagramCredential(redacted)"
}

private val cookieNames = setOf("sessionid", "csrftoken", "ds_user_id", "mid", "ig_did", "rur")
internal fun validInstagramCookieValue(raw: String): Boolean {
    val value = raw.removeSurrounding("\"")
    return value.length in 1..4096 && value.all { it.code in 33..126 && it !in "\";," }
}

internal fun extractInstagramCredential(rawCookie: String): InstagramCredential? {
    if (rawCookie.length > 16384 || rawCookie.any { it.code < 32 }) return null
    val fields = mutableMapOf<String, String>()
    for (pair in rawCookie.trim().removePrefix("Cookie:").split(';')) {
        val name = pair.substringBefore('=').trim()
        if (name !in cookieNames) continue
        val value = pair.substringAfter('=', "").trim()
        if (!validInstagramCookieValue(value)) return null
        if (fields.put(name, value)?.let { it != value } == true) return null
    }
    val id = fields["ds_user_id"]?.takeIf { it.matches(Regex("[1-9][0-9]{0,39}")) } ?: return null
    val session = fields["sessionid"] ?: return null
    val owner = Regex("^([0-9]+)(?::|%3a)", RegexOption.IGNORE_CASE).find(session)?.groupValues?.get(1)
    if (owner != null && owner != id) return null
    return InstagramCredential(session, fields["csrftoken"] ?: return null, id, fields["mid"], fields["ig_did"], fields["rur"])
}

internal fun InstagramCredential.cookieHeader(): String =
    listOfNotNull(
        "sessionid=" + sessionId,
        "ds_user_id=" + dsUserId,
        "csrftoken=" + csrfToken,
        mid?.let { "mid=" + it },
        igDid?.let { "ig_did=" + it },
        rur?.let { "rur=" + it },
    ).joinToString("; ")
