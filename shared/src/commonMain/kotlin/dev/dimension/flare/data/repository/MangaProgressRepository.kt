package dev.dimension.flare.data.repository

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.cache.connect
import dev.dimension.flare.data.database.cache.model.DbMangaChapterProgress
import dev.dimension.flare.data.database.cache.model.DbMangaState
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.DbAccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.MangaReadingDirection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.core.annotation.Single
import kotlin.native.HiddenFromObjC
import kotlin.time.Clock

@Immutable
@HiddenFromObjC
public data class MangaProgressSnapshot(
    public val lastChapterId: String? = null,
    public val lastPageIndex: Int = 0,
    public val lastReadAt: Long = 0L,
    public val readerModeOverride: MangaReadingDirection? = null,
    public val chapterSortDescending: Boolean = true,
    public val chapters: Map<String, ChapterProgress> = emptyMap(),
) {
    @Immutable
    @HiddenFromObjC
    public data class ChapterProgress(
        public val chapterId: String,
        public val lastPageIndex: Int,
        public val pageCount: Int,
        public val isCompleted: Boolean,
    )
}

public typealias ChapterProgress = MangaProgressSnapshot.ChapterProgress

@Stable
@Single
@HiddenFromObjC
public class MangaProgressRepository internal constructor(
    private val cacheDatabase: CacheDatabase,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    public fun observe(
        accountType: AccountType,
        mangaKey: MicroBlogKey,
    ): Flow<MangaProgressSnapshot> {
        val dbAccountType = accountType.toDbAccountType()
        val dao = cacheDatabase.mangaProgressDao()
        return combine(
            dao.observeState(dbAccountType, mangaKey),
            dao.observeChapters(dbAccountType, mangaKey),
        ) { state, chapters ->
            MangaProgressSnapshot(
                lastChapterId = state?.lastChapterId,
                lastPageIndex = state?.lastPageIndex ?: 0,
                lastReadAt = state?.lastReadAt ?: 0L,
                readerModeOverride = state?.readerModeOverride,
                chapterSortDescending = state?.chapterSortDescending ?: true,
                chapters =
                    chapters.associate { row ->
                        row.chapterId to
                            MangaProgressSnapshot.ChapterProgress(
                                chapterId = row.chapterId,
                                lastPageIndex = row.lastPageIndex,
                                pageCount = row.pageCount,
                                isCompleted = row.isCompleted,
                            )
                    },
            )
        }.distinctUntilChanged()
    }

    public suspend fun recordPage(
        accountType: AccountType,
        mangaKey: MicroBlogKey,
        chapterId: String,
        pageIndex: Int,
        pageCount: Int,
        isCompleted: Boolean,
    ) {
        val dbAccountType = accountType.toDbAccountType()
        val safePageIndex = pageIndex.coerceAtLeast(0)
        val safePageCount = pageCount.coerceAtLeast(0)
        cacheDatabase.connect {
            val now = clock()
            val dao = cacheDatabase.mangaProgressDao()
            val existingState = dao.getState(dbAccountType, mangaKey)
            dao.upsertChapter(
                DbMangaChapterProgress(
                    accountType = dbAccountType,
                    mangaKey = mangaKey,
                    chapterId = chapterId,
                    lastPageIndex = safePageIndex,
                    pageCount = safePageCount,
                    isCompleted = isCompleted,
                    updatedAt = now,
                ),
            )
            dao.upsertState(
                (existingState ?: DbMangaState(accountType = dbAccountType, mangaKey = mangaKey)).copy(
                    lastChapterId = chapterId,
                    lastPageIndex = safePageIndex,
                    lastReadAt = now,
                ),
            )
        }
    }

    public suspend fun setChapterRead(
        accountType: AccountType,
        mangaKey: MicroBlogKey,
        chapterId: String,
        isRead: Boolean,
    ) {
        val dbAccountType = accountType.toDbAccountType()
        cacheDatabase.connect {
            val now = clock()
            val dao = cacheDatabase.mangaProgressDao()
            val existingChapter = dao.getChapter(dbAccountType, mangaKey, chapterId)
            val existingState = dao.getState(dbAccountType, mangaKey)
            val pageCount = existingChapter?.pageCount ?: 0
            val updatedLastPageIndex =
                if (isRead) {
                    if (pageCount > 0) {
                        maxOf(pageCount - 1, existingChapter?.lastPageIndex ?: 0)
                    } else {
                        existingChapter?.lastPageIndex ?: 0
                    }
                } else {
                    0
                }
            dao.upsertChapter(
                DbMangaChapterProgress(
                    accountType = dbAccountType,
                    mangaKey = mangaKey,
                    chapterId = chapterId,
                    lastPageIndex = updatedLastPageIndex,
                    pageCount = pageCount,
                    isCompleted = isRead,
                    updatedAt = now,
                ),
            )
            if (isRead && existingState?.lastChapterId == null) {
                dao.upsertState(
                    (existingState ?: DbMangaState(accountType = dbAccountType, mangaKey = mangaKey)).copy(
                        lastChapterId = chapterId,
                        lastPageIndex = updatedLastPageIndex,
                        lastReadAt = now,
                    ),
                )
            } else if (!isRead && existingState?.lastChapterId == chapterId) {
                dao.upsertState(existingState.copy(lastPageIndex = 0))
            }
        }
    }

    public suspend fun setReaderModeOverride(
        accountType: AccountType,
        mangaKey: MicroBlogKey,
        direction: MangaReadingDirection?,
    ) {
        val dbAccountType = accountType.toDbAccountType()
        cacheDatabase.connect {
            val dao = cacheDatabase.mangaProgressDao()
            val existing = dao.getState(dbAccountType, mangaKey)
            dao.upsertState(
                (existing ?: DbMangaState(accountType = dbAccountType, mangaKey = mangaKey)).copy(
                    readerModeOverride = direction,
                ),
            )
        }
    }

    public suspend fun setChapterSortDescending(
        accountType: AccountType,
        mangaKey: MicroBlogKey,
        descending: Boolean,
    ) {
        val dbAccountType = accountType.toDbAccountType()
        cacheDatabase.connect {
            val dao = cacheDatabase.mangaProgressDao()
            val existing = dao.getState(dbAccountType, mangaKey)
            dao.upsertState(
                (existing ?: DbMangaState(accountType = dbAccountType, mangaKey = mangaKey)).copy(
                    chapterSortDescending = descending,
                ),
            )
        }
    }

    public suspend fun deleteByAccountType(accountType: AccountType) {
        val dbAccountType = accountType.toDbAccountType()
        cacheDatabase.connect {
            cacheDatabase.mangaProgressDao().deleteByAccountType(dbAccountType)
        }
    }

    public suspend fun deleteGuestByPlatformId(platformId: String) {
        cacheDatabase.connect {
            cacheDatabase.mangaProgressDao().deleteGuestByPlatformId(platformId)
        }
    }
}

private fun AccountType.toDbAccountType(): DbAccountType =
    when (this) {
        is AccountType.Specific -> this as DbAccountType
        AccountType.Guest -> this as DbAccountType
        is AccountType.GuestHost -> this as DbAccountType
        is AccountType.GuestPlugin -> this as DbAccountType
    }
