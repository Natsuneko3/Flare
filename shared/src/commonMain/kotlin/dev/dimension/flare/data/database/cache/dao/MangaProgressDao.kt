package dev.dimension.flare.data.database.cache.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import dev.dimension.flare.data.database.cache.model.DbMangaChapterProgress
import dev.dimension.flare.data.database.cache.model.DbMangaState
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.DbAccountType
import dev.dimension.flare.model.MicroBlogKey
import kotlinx.coroutines.flow.Flow

@Dao
internal interface MangaProgressDao {
    @Query("SELECT * FROM DbMangaState WHERE accountType = :accountType AND mangaKey = :mangaKey")
    fun observeState(
        accountType: DbAccountType,
        mangaKey: MicroBlogKey,
    ): Flow<DbMangaState?>

    @Query("SELECT * FROM DbMangaState WHERE accountType = :accountType AND mangaKey = :mangaKey")
    suspend fun getState(
        accountType: DbAccountType,
        mangaKey: MicroBlogKey,
    ): DbMangaState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: DbMangaState)

    @Query("SELECT * FROM DbMangaChapterProgress WHERE accountType = :accountType AND mangaKey = :mangaKey")
    fun observeChapters(
        accountType: DbAccountType,
        mangaKey: MicroBlogKey,
    ): Flow<List<DbMangaChapterProgress>>

    @Query(
        "SELECT * FROM DbMangaChapterProgress " +
            "WHERE accountType = :accountType AND mangaKey = :mangaKey AND chapterId = :chapterId",
    )
    suspend fun getChapter(
        accountType: DbAccountType,
        mangaKey: MicroBlogKey,
        chapterId: String,
    ): DbMangaChapterProgress?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapter(progress: DbMangaChapterProgress)

    @Query(
        "SELECT DISTINCT accountType FROM DbMangaState " +
            "UNION " +
            "SELECT DISTINCT accountType FROM DbMangaChapterProgress",
    )
    suspend fun getDistinctAccountTypes(): List<DbAccountType>

    @Query("DELETE FROM DbMangaState WHERE accountType = :accountType")
    suspend fun deleteStateByAccountType(accountType: DbAccountType)

    @Query("DELETE FROM DbMangaChapterProgress WHERE accountType = :accountType")
    suspend fun deleteChaptersByAccountType(accountType: DbAccountType)

    @Transaction
    suspend fun deleteByAccountType(accountType: DbAccountType) {
        deleteChaptersByAccountType(accountType)
        deleteStateByAccountType(accountType)
    }

    @Transaction
    suspend fun deleteGuestByPlatformId(platformId: String) {
        getDistinctAccountTypes()
            .filter { (it as? AccountType.GuestPlugin)?.platformId == platformId }
            .forEach { deleteByAccountType(it) }
    }
}
