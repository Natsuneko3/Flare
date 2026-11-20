package dev.dimension.flare.ui.model.mapper

import com.fleeksoft.ksoup.parser.Parser
import dev.dimension.flare.common.jsonObjectOrNull
import dev.dimension.flare.data.network.xqt.model.Tweet
import dev.dimension.flare.ui.model.UiCommunityNote
import dev.dimension.flare.ui.render.RenderContent
import dev.dimension.flare.ui.render.RenderRun
import dev.dimension.flare.ui.render.RenderTextStyle
import dev.dimension.flare.ui.render.uiRichTextOf
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal fun Tweet.renderCommunityNote(): UiCommunityNote? {
    val pivot = birdwatchPivot?.jsonObjectOrNull ?: return null
    val style = pivot.string("visualStyle") ?: pivot.string("visual_style")
    val footerIcon = pivot.string("footerIconType") ?: pivot.string("footer_icon_type")
    if ((style != null && style != "Default") || footerIcon == "BirdwatchEyeOff") return null
    val title = pivot.string("title")?.takeIf { it.isNotBlank() } ?: return null
    val subtitle = pivot["subtitle"]?.jsonObjectOrNull ?: return null
    val text = subtitle.string("text")?.takeIf { it.isNotBlank() } ?: return null
    val destination = pivot.string("destinationUrl") ?: pivot.string("destination_url")
    val noteLink =
        destination
            ?.let { if (it.startsWith('/') && !it.startsWith("//")) "https://x.com$it" else it }
            ?.let(::httpUrl)
            ?.takeIf { it.protocol == URLProtocol.HTTPS && (it.host == "x.com" || it.host == "twitter.com") }
            ?.toString()
            ?: restId.takeIf { it.isNotEmpty() && it.all { digit -> digit in '0'..'9' } }?.let { "https://x.com/i/status/$it" }

    val runs =
        buildList<RenderRun> {
            var endOfLastLink = 0
            val entities = (subtitle["entities"] as? JsonArray).orEmpty().mapNotNull { it.jsonObjectOrNull }
            for (entity in entities.sortedBy { it.index("fromIndex") }) {
                val start = entity.index("fromIndex") ?: continue
                val end = entity.index("toIndex") ?: continue
                if (start < endOfLastLink || start >= end || end > text.length) continue
                if (text[start].isLowSurrogate() || text.getOrNull(end)?.isLowSurrogate() == true) continue
                val ref = entity["ref"]?.jsonObjectOrNull ?: continue
                if ((ref.string("type") ?: ref.string("__typename")) != "TimelineUrl") continue
                val url = ref.string("url")?.let(::httpUrl) ?: continue
                if (start > endOfLastLink) {
                    add(RenderRun.Text(Parser.unescapeEntities(text.substring(endOfLastLink, start), false)))
                }
                add(RenderRun.Text(Parser.unescapeEntities(text.substring(start, end), false), RenderTextStyle(link = url.toString())))
                endOfLastLink = end
            }
            if (endOfLastLink < text.length) {
                add(RenderRun.Text(Parser.unescapeEntities(text.substring(endOfLastLink), false)))
            }
        }

    return UiCommunityNote(
        title = title,
        url = noteLink,
        body =
            uiRichTextOf(
                renderRuns = listOf(RenderContent.Text(runs.toImmutableList())),
                sourceLanguages = listOfNotNull(pivot["note"]?.jsonObjectOrNull?.string("language")),
            ),
    )
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.index(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull

private fun httpUrl(raw: String): Url? {
    if (!raw.startsWith("https://", ignoreCase = true) && !raw.startsWith("http://", ignoreCase = true)) return null
    return runCatching { Url(raw) }.getOrNull()?.takeIf { it.host.isNotBlank() && it.user == null && it.password == null }
}
