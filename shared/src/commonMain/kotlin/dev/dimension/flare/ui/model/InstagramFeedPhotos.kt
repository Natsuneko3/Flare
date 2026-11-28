package dev.dimension.flare.ui.model

import com.fleeksoft.ksoup.Ksoup
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

private val instagramPost = Regex(
    """^https?://(?:www\.|m\.)?instagram\.com/(?:[\w.]+/)?(?:p|reel|tv)/[\w-]+(?:/|[?#]|$)""",
    RegexOption.IGNORE_CASE,
)

// Read the saved article body so posts already in the timeline cache get the same gallery.
public fun UiTimelineV2.Feed.instagramPhotos(): ImmutableList<UiMedia.Image> {
    val cover = media ?: return persistentListOf()
    if (pluginId != null || !instagramPost.containsMatchIn(url)) return persistentListOf()
    val photos = descriptionHtml?.let { html ->
        Ksoup.parse(html, url).select("img[src]").mapNotNull { image ->
            val src = image.absUrl("src")
            if (!src.startsWith("https://") && !src.startsWith("http://")) return@mapNotNull null
            cover.copy(
                url = src,
                previewUrl = src,
                description = image.attr("alt").takeIf { it.isNotBlank() },
                width = image.attr("width").toFloatOrNull()?.takeIf { it.isFinite() && it > 0f } ?: 0f,
                height = image.attr("height").toFloatOrNull()?.takeIf { it.isFinite() && it > 0f } ?: 0f,
            )
        }.distinctBy { it.url }
    }.orEmpty()
    return if (photos.isEmpty()) persistentListOf(cover) else photos.toPersistentList()
}
