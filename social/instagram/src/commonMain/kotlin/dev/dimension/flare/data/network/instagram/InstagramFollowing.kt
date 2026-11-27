package dev.dimension.flare.data.network.instagram

import com.fleeksoft.ksoup.Ksoup
import dev.dimension.flare.common.JSON
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.Parameters
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val FOLLOWING_QUERY = "PolarisFeedRootPaginationCachedQuery_subscribe"
internal const val FOLLOWING_FIELD = "xdt_api__v1__feed__timeline__connection"

internal class InstagramBootstrap(
    html: String,
) {
    private val modules = mutableMapOf<String, JsonObject>()

    init {
        for (script in Ksoup.parse(html).select("script[type=application/json]")) {
            val text = script.data()
            if (listOf("PolarisViewer", "DTSGInitialData", "LSD", "CurrentUserInitialData", "PolarisSiteData").none { it in text }) continue
            val root = runCatching { JSON.parseToJsonElement(text) }.getOrNull() ?: continue
            val queue = ArrayDeque<JsonElement>()
            queue.addLast(root)
            while (queue.isNotEmpty()) {
                when (val el = queue.removeLast()) {
                    is JsonObject -> {
                        queue.addAll(el.values)
                    }

                    is JsonArray -> {
                        val name = (el.firstOrNull() as? JsonPrimitive)?.content
                        val data = el.getOrNull(2) as? JsonObject
                        if (name != null && data != null) modules[name] = data
                        queue.addAll(el)
                    }

                    else -> {
                        continue
                    }
                }
            }
        }
    }

    val viewer: String? get() = (modules["PolarisViewer"]?.get("data") as? JsonObject)?.string("id")

    fun requireViewer(id: String) {
        if (viewer != id) throw InstagramAuthenticationRequired()
    }

    val lsd: String get() = modules["LSD"]?.string("token") ?: invalidInstagramResponse()

    fun form(
        cursor: String?,
        deviceId: String?,
    ): FormDataContent {
        val dtsg = modules["DTSGInitialData"]?.string("token") ?: invalidInstagramResponse()
        val variables =
            buildJsonObject {
                put("after", cursor?.let(::JsonPrimitive) ?: JsonNull)
                put("before", JsonNull)
                put("first", 20)
                put("last", JsonNull)
                put("variant", "following")
                put(
                    "data",
                    buildJsonObject {
                        (modules["PolarisSiteData"]?.string("device_id") ?: deviceId)?.let { put("device_id", it) }
                        put("is_async_ads_double_request", "0")
                        put("is_async_ads_in_headload_enabled", "0")
                        put("is_async_ads_rti", "0")
                        put("rti_delivery_backend", "0")
                        put("pagination_source", "following")
                    },
                )
                put("__relay_internal__pv__PolarisIsLoggedInrelayprovider", true)
                put("__relay_internal__pv__PolarisShortDramaEnabledrelayprovider", true)
                put("__relay_internal__pv__PolarisMultiCaptionCarouselEnabledrelayprovider", true)
                put("__relay_internal__pv__PolarisThreadsStyleCommentsEnabledrelayprovider", false)
                put("__relay_internal__pv__PolarisReelsRecoDebugOverlayEnabledrelayprovider", false)
                put("__relay_internal__pv__PolarisAdDebugToolEnabledrelayprovider", false)
                put("__relay_internal__pv__PolarisSuggestedUserFeedUnitCardsEnabledrelayprovider", false)
            }
        return FormDataContent(
            Parameters.build {
                append("__d", "www")
                modules["CurrentUserInitialData"]?.string("IG_USER_EIMU")?.let { append("av", it) }
                append("lsd", lsd)
                append("fb_dtsg", dtsg)
                append("jazoest", "2" + dtsg.sumOf { it.code })
                append("fb_api_caller_class", "RelayModern")
                append("fb_api_req_friendly_name", FOLLOWING_QUERY)
                append("doc_id", "38576618445314797")
                append("server_timestamps", "true")
                append("variables", variables.toString())
            },
        )
    }
}

internal fun followingPage(frames: List<JsonObject>): InstagramPage {
    val edges = mutableMapOf<Int, JsonObject>()
    var info: JsonObject? = null
    var found = false
    for (frame in frames) {
        val data = frame["data"] as? JsonObject
        val connection = data?.get(FOLLOWING_FIELD) as? JsonObject
        if (connection != null) {
            if (connection.string("pagination_source") == "home") invalidInstagramResponse()
            val initial = connection["edges"] as? JsonArray ?: invalidInstagramResponse()
            edges.clear()
            initial.forEachIndexed { index, edge -> if (edge is JsonObject) edges[index] = edge }
            info = connection["page_info"] as? JsonObject
            found = true
        }
        val path = frame["path"] as? JsonArray ?: continue
        if (!found || path.firstOrNull() != JsonPrimitive(FOLLOWING_FIELD)) continue
        when ((path.getOrNull(1) as? JsonPrimitive)?.content) {
            "page_info" -> {
                info = data
            }

            "edges" -> {
                val index = (path.getOrNull(2) as? JsonPrimitive)?.content?.toIntOrNull() ?: invalidInstagramResponse()
                if (index !in 0..199) invalidInstagramResponse()
                val items = frame["items"] as? JsonArray
                if (items != null) {
                    items.forEachIndexed { offset, edge -> if (edge is JsonObject) edges[index + offset] = edge }
                } else if (data != null) {
                    edges[index] = data
                }
            }
        }
    }
    if (!found || info == null) invalidInstagramResponse()
    val adMarkers =
        listOf(
            "ad",
            "ad_id",
            "ad_token",
            "is_ad",
            "injected",
            "suggested_users",
            "suggested_user_feed_unit",
            "clips_netego",
            "recommended_users",
        )
    val items =
        edges.keys.sorted().mapNotNull { index ->
            val node = edges[index]?.get("node") as? JsonObject ?: return@mapNotNull null
            if (adMarkers.any { node[it]?.let { v -> v != JsonNull && v != JsonPrimitive(false) } == true }) return@mapNotNull null
            node["media"] as? JsonObject
        }
    return InstagramPage(items, info.string("end_cursor").takeIf { info.string("has_next_page") == "true" })
}
