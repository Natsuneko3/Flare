package dev.dimension.flare.data.datasource.microblog.datasource

import androidx.compose.runtime.Immutable
import dev.dimension.flare.common.Cacheable
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.datasource.microblog.paging.notSupported
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiMangaDetail
import dev.dimension.flare.ui.model.UiMangaPage
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.UiTimelineV2
import kotlinx.collections.immutable.ImmutableList
import kotlin.native.HiddenFromObjC

public enum class MangaPageImageResolveReason {
    Initial,
    ResolveRetry,
    ImageLoadFailed,
}

@Immutable
public data class MangaPagesChunk(
    val pages: ImmutableList<UiMangaPage>,
    val offset: Int? = null,
    val totalCount: Int? = null,
    val nextCursor: String? = null,
    val endReached: Boolean = true,
)

@HiddenFromObjC
public interface MangaDataSource {
    public fun mangaDetail(mangaKey: MicroBlogKey): Cacheable<UiMangaDetail>

    public suspend fun chapterPages(
        mangaKey: MicroBlogKey,
        chapterId: String,
    ): ImmutableList<UiMangaPage>

    public suspend fun chapterPagesChunk(
        mangaKey: MicroBlogKey,
        chapterId: String,
        offset: Int = 0,
        limit: Int? = null,
        cursor: String? = null,
    ): MangaPagesChunk =
        MangaPagesChunk(
            pages = chapterPages(mangaKey, chapterId),
            offset = 0,
        )

    public suspend fun resolvePageMedia(
        mangaKey: MicroBlogKey,
        chapterId: String,
        pageId: String,
        pageToken: String,
    ): UiMedia

    public suspend fun resolvePageMedia(
        mangaKey: MicroBlogKey,
        chapterId: String,
        pageId: String,
        pageToken: String,
        attempt: Int,
        reason: MangaPageImageResolveReason,
        failedUrl: String?,
    ): UiMedia =
        resolvePageMedia(
            mangaKey = mangaKey,
            chapterId = chapterId,
            pageId = pageId,
            pageToken = pageToken,
        )

    public val supportsComments: Boolean
        get() = false

    public val supportsRecommendations: Boolean
        get() = false

    public fun mangaComments(mangaKey: MicroBlogKey): RemoteLoader<UiTimelineV2> = notSupported()

    public fun mangaRecommendations(mangaKey: MicroBlogKey): RemoteLoader<UiTimelineV2> = notSupported()
}
