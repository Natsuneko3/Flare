package dev.dimension.flare.ui.model

import androidx.compose.runtime.Immutable
import dev.dimension.flare.data.datasource.microblog.ActionMenu
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.render.UiDateTime
import dev.dimension.flare.ui.render.UiRichText
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.serialization.Serializable

@Immutable
public data class UiMangaDetail(
    public val pluginId: String?,
    public val platformId: String,
    public val mangaKey: MicroBlogKey,
    public val accountType: AccountType,
    public val title: String,
    public val altTitles: ImmutableList<String>,
    public val cover: UiMedia.Image?,
    public val author: UiProfile?,
    public val authors: ImmutableList<String>,
    public val artists: ImmutableList<String>,
    public val status: UiMangaStatus?,
    public val content: UiRichText?,
    public val url: String?,
    public val updatedAt: UiDateTime?,
    public val readingDirection: MangaReadingDirection?,
    public val tagGroups: ImmutableList<UiMangaTagGroup>,
    public val chapterGroups: ImmutableList<UiMangaChapterGroup>,
    public val isBookmarked: Boolean,
    public val bookmarkAction: ClickEvent,
    public val actions: ImmutableList<ActionMenu>,
    public val sourceLanguages: ImmutableList<String>,
) {
    public val onBookmark: ClickContext.() -> Unit by lazy {
        bookmarkAction.onClicked
    }
}

@Serializable
@Immutable
public enum class MangaReadingDirection {
    Rtl,
    Ltr,
    Webtoon,
}

@Serializable
@Immutable
public enum class UiMangaStatus {
    Ongoing,
    Completed,
    Hiatus,
    Cancelled,
}

@Immutable
public data class UiMangaTagGroup(
    public val id: String,
    public val title: UiText?,
    public val tags: ImmutableList<UiMangaTag>,
)

@Immutable
public data class UiMangaTag(
    public val name: String,
    public val clickEvent: ClickEvent,
) {
    public val onClicked: ClickContext.() -> Unit by lazy {
        clickEvent.onClicked
    }
}

@Immutable
public data class UiMangaChapterGroup(
    public val id: String,
    public val title: UiText?,
    public val chapters: ImmutableList<UiMangaChapter>,
)

@Immutable
public data class UiMangaChapter(
    public val id: String,
    public val title: String,
    public val subtitle: String?,
    public val uploadedAt: UiDateTime?,
    public val locked: Boolean,
    public val url: String?,
)

@Immutable
public sealed interface UiMangaPage {
    public val id: String

    @Immutable
    public data class Ready(
        override val id: String,
        public val media: UiMedia,
    ) : UiMangaPage

    @Immutable
    public data class Deferred(
        override val id: String,
        public val pageToken: String,
        public val previewUrl: String?,
        public val width: Float,
        public val height: Float,
        public val customHeaders: ImmutableMap<String, String>?,
        public val proxy: ProxyRef,
    ) : UiMangaPage
}
