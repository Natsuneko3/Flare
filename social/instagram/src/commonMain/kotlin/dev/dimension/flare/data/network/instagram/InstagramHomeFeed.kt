package dev.dimension.flare.data.network.instagram

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

internal fun homePage(response: JsonObject): InstagramPage {
    val units = response["feed_items"] as? JsonArray ?: invalidInstagramResponse()
    val posts = units.mapNotNull { unit ->
        val media = (unit as? JsonObject)?.get("media_or_ad") as? JsonObject
        media?.takeIf { it["ad_id"] == null || it["ad_id"] == JsonNull }
    }
    val next = response.string("next_max_id")?.takeIf { it.isNotBlank() && response.string("more_available") != "false" }
    return InstagramPage(posts, next)
}
