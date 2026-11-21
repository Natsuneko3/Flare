package dev.dimension.flare.data.database.cache.model

import androidx.room3.ColumnTypeConverter
import androidx.room3.Entity
import androidx.room3.Index
import dev.dimension.flare.model.DbAccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.MangaReadingDirection

@Entity(
    primaryKeys = ["accountType", "mangaKey"],
)
internal data class DbMangaState(
    val accountType: DbAccountType,
    val mangaKey: MicroBlogKey,
    val lastChapterId: String? = null,
    val lastPageIndex: Int = 0,
    val lastReadAt: Long = 0L,
    val readerModeOverride: MangaReadingDirection? = null,
    val chapterSortDescending: Boolean = true,
)

@Entity(
    primaryKeys = ["accountType", "mangaKey", "chapterId"],
    indices = [
        Index(value = ["accountType", "mangaKey"]),
    ],
)
internal data class DbMangaChapterProgress(
    val accountType: DbAccountType,
    val mangaKey: MicroBlogKey,
    val chapterId: String,
    val lastPageIndex: Int,
    val pageCount: Int,
    val isCompleted: Boolean,
    val updatedAt: Long,
)

internal class MangaProgressConverters {
    @ColumnTypeConverter
    fun fromReadingDirection(value: MangaReadingDirection): String = value.name

    @ColumnTypeConverter
    fun toReadingDirection(value: String): MangaReadingDirection = MangaReadingDirection.valueOf(value)
}
