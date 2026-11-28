package dev.dimension.flare.ui.model

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import dev.dimension.flare.data.model.ProxyRef
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList

private val instagramPost = Regex(
    """^https?://(?:www\.|m\.)?instagram\.com/(?:[\w.]+/)?(?:p|reel|tv)/[\w-]+(?:/|[?#]|$)""",
    RegexOption.IGNORE_CASE,
)

private fun Element.webUrl(attribute: String): String? =
    absUrl(attribute).takeIf { it.startsWith("https://") || it.startsWith("http://") }

private fun Element.dimension(attribute: String): Float =
    attr(attribute).toFloatOrNull()?.takeIf { it.isFinite() && it > 0f } ?: 0f

public fun Element.rssVideo(headers: ImmutableMap<String, String>?, proxy: ProxyRef): UiMedia.Video? {
    val src = webUrl("src") ?: select("source[src]").firstNotNullOfOrNull { it.webUrl("src") } ?: return null
    val width = dimension("width")
    val height = dimension("height")
    return UiMedia.Video(
        url = src,
        thumbnailUrl = webUrl("poster").orEmpty(),
        description = attr("aria-label").ifBlank { null },
        width = if (height > 0f) width else 0f,
        height = if (width > 0f) height else 0f,
        customHeaders = headers,
        proxy = proxy,
    )
}

// Read cached article bodies too. RSSHub preserves album order in the HTML.
public fun UiTimelineV2.Feed.instagramMedia(): ImmutableList<UiMedia> {
    if (pluginId != null || !instagramPost.containsMatchIn(url)) return persistentListOf()
    val headers = media?.customHeaders ?: persistentMapOf("Referer" to "https://www.instagram.com/")
    val proxy = media?.proxy ?: source.proxy
    val items = descriptionHtml?.let { html ->
        Ksoup.parse(html, url).select("img[src], video").mapNotNull { element ->
            if (element.tagName() == "video") return@mapNotNull element.rssVideo(headers, proxy)
            if (element.parents().any { it.tagName() == "video" }) return@mapNotNull null
            val src = element.webUrl("src") ?: return@mapNotNull null
            UiMedia.Image(
                url = src,
                previewUrl = src,
                description = element.attr("alt").takeIf { it.isNotBlank() },
                width = element.dimension("width"),
                height = element.dimension("height"),
                sensitive = false,
                customHeaders = headers,
                proxy = proxy,
            )
        }.distinctBy { it.url }
    }.orEmpty()
    return items.ifEmpty { listOfNotNull(media) }.toPersistentList()
}
