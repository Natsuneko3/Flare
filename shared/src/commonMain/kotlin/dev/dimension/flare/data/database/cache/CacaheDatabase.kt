package dev.dimension.flare.data.database.cache

import androidx.room3.ColumnTypeConverters
import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.withWriteTransaction

// Added DbMangaState and DbMangaChapterProgress tables for manga reading progress.
internal const val CACHE_DATABASE_VERSION = 57

@Database(
    entities = [
        dev.dimension.flare.data.database.cache.model.DbEmoji::class,
        dev.dimension.flare.data.database.cache.model.DbStatusReference::class,
        dev.dimension.flare.data.database.cache.model.DbStatus::class,
        dev.dimension.flare.data.database.cache.model.DbUser::class,
        dev.dimension.flare.data.database.cache.model.DbPagingTimeline::class,
        dev.dimension.flare.data.database.cache.model.DbTimelineItemPresentationReference::class,
        dev.dimension.flare.data.database.cache.model.DbMessageRoom::class,
        dev.dimension.flare.data.database.cache.model.DbMessageItem::class,
        dev.dimension.flare.data.database.cache.model.DbDirectMessageTimeline::class,
        dev.dimension.flare.data.database.cache.model.DbMessageRoomReference::class,
        dev.dimension.flare.data.database.cache.model.DbUserHistory::class,
        dev.dimension.flare.data.database.cache.model.DbEmojiHistory::class,
        dev.dimension.flare.data.database.cache.model.DbPagingKey::class,
        dev.dimension.flare.data.database.cache.model.DbList::class,
        dev.dimension.flare.data.database.cache.model.DbListPaging::class,
        dev.dimension.flare.data.database.cache.model.DbListMember::class,
        dev.dimension.flare.data.database.cache.model.DbUserRelation::class,
        dev.dimension.flare.data.database.cache.model.DbProfileTabs::class,
        dev.dimension.flare.data.database.cache.model.DbTranslation::class,
        dev.dimension.flare.data.database.cache.model.DbPluginPostWidget::class,
        dev.dimension.flare.data.database.cache.model.DbMangaState::class,
        dev.dimension.flare.data.database.cache.model.DbMangaChapterProgress::class,
    ],
    version = CACHE_DATABASE_VERSION,
    exportSchema = false,
)
@ColumnTypeConverters(
    dev.dimension.flare.data.database.adapter.MicroBlogKeyConverter::class,
    dev.dimension.flare.data.database.adapter.AccountTypeConverter::class,
    dev.dimension.flare.data.database.cache.model.EmojiContentConverter::class,
    dev.dimension.flare.data.database.cache.model.StatusConverter::class,
    dev.dimension.flare.data.database.cache.model.DbStatusContentConverter::class,
    dev.dimension.flare.data.database.cache.model.ListContentConverters::class,
    dev.dimension.flare.data.database.cache.model.TranslationConverters::class,
    dev.dimension.flare.data.database.cache.model.MangaProgressConverters::class,
)
@ConstructedBy(CacheDatabaseConstructor::class)
internal abstract class CacheDatabase : RoomDatabase() {
    val timelineResets = kotlinx.coroutines.flow.MutableStateFlow(0)

    abstract fun emojiDao(): dev.dimension.flare.data.database.cache.dao.EmojiDao

    abstract fun statusReferenceDao(): dev.dimension.flare.data.database.cache.dao.StatusReferenceDao

    abstract fun statusDao(): dev.dimension.flare.data.database.cache.dao.StatusDao

    abstract fun userDao(): dev.dimension.flare.data.database.cache.dao.UserDao

    abstract fun pagingTimelineDao(): dev.dimension.flare.data.database.cache.dao.PagingTimelineDao

    abstract fun messageDao(): dev.dimension.flare.data.database.cache.dao.MessageDao

    abstract fun listDao(): dev.dimension.flare.data.database.cache.dao.ListDao

    abstract fun translationDao(): dev.dimension.flare.data.database.cache.dao.TranslationDao

    abstract fun pluginPostWidgetDao(): dev.dimension.flare.data.database.cache.dao.PluginPostWidgetDao

    abstract fun mangaProgressDao(): dev.dimension.flare.data.database.cache.dao.MangaProgressDao
}

internal suspend fun CacheDatabase.clearHomeTimelinesForAccount(accountKey: dev.dimension.flare.model.MicroBlogKey) {
    connect {
        val dao = pagingTimelineDao()
        dao
            .getAccountTimelineKeys()
            .filter {
                accountKey in
                    kotlinx.serialization.json.Json
                        .decodeFromString<Set<dev.dimension.flare.model.MicroBlogKey>>(dao.getAccountKeys(it) ?: "[]")
            }.forEach { dao.clearHomeTimeline(it) }
    }
}

internal suspend fun CacheDatabase.invalidateGuestPluginCachedContent(
    platformId: String,
    nonCursorPagingKeys: Set<String> = emptySet(),
) {
    connect {
        val guestTypes =
            statusDao()
                .getDistinctAccountTypesAcrossCache()
                .filterIsInstance<dev.dimension.flare.model.AccountType.GuestPlugin>()
                .filter { it.platformId == platformId }
        guestTypes.forEach { guestType ->
            purgeGuestPluginTimelineAndStatusesInTx(guestType, nonCursorPagingKeys)
        }
    }
}

internal suspend fun CacheDatabase.clearGuestPluginData(
    platformId: String,
    additionalHosts: Set<String> = emptySet(),
    preservedHosts: Set<String> = emptySet(),
    nonCursorPagingKeys: Set<String> = emptySet(),
) {
    connect {
        val guestTypes =
            statusDao()
                .getDistinctAccountTypesAcrossCache()
                .filterIsInstance<dev.dimension.flare.model.AccountType.GuestPlugin>()
                .filter { it.platformId == platformId }
        guestTypes.forEach { guestType ->
            messageDao().exclusiveRoomKeys(guestType).forEach { roomKey ->
                messageDao().deleteRoomMessages(roomKey)
                messageDao().deleteRoomReference(roomKey)
                messageDao().deleteRoom(roomKey)
            }
            listDao().deleteExclusiveMembersByAccountType(guestType)
            listDao().deletePagingByAccountType(guestType)
            listDao().deleteByAccountType(guestType)
            userDao().deleteRelationsByAccountType(guestType)
            purgeGuestPluginTimelineAndStatusesInTx(guestType, nonCursorPagingKeys)
            mangaProgressDao().deleteByAccountType(guestType)
        }
        val candidateHosts = (guestTypes.mapTo(linkedSetOf()) { it.host } + additionalHosts) - preservedHosts
        candidateHosts.forEach { host ->
            userDao().deleteOrphanedUsersByHost(host)
        }
    }
}

private suspend fun CacheDatabase.purgeGuestPluginTimelineAndStatusesInTx(
    guestType: dev.dimension.flare.model.AccountType.GuestPlugin,
    nonCursorPagingKeys: Set<String>,
) {
    val pagingDao = pagingTimelineDao()
    val affectedPagingKeys = pagingDao.getPagingKeysByAccountType(guestType)
    translationDao().deleteByAccountType(guestType)
    statusReferenceDao().deleteByAccountType(guestType)
    pagingDao.deletePresentationReferencesByAccountType(guestType)
    pagingDao.deleteByAccountType(guestType)
    affectedPagingKeys
        .filterNot { it in nonCursorPagingKeys }
        .forEach { pagingKey ->
            if (!pagingDao.anyNonGapPaging(pagingKey)) {
                pagingDao.clearHomeTimeline(pagingKey)
            } else {
                pagingDao.deletePagingKey(pagingKey)
            }
        }
    statusDao().deleteByAccountType(guestType)
    userDao().deleteProfileTabsByAccountType(guestType)
    userDao().deleteHistoryByAccountType(guestType)
    emojiDao().clearHistoryByAccountType(guestType)
    messageDao().clearMessageTimeline(guestType)
}

// The Room compiler generates the `actual` implementations.
@Suppress("NO_ACTUAL_FOR_EXPECT")
internal expect object CacheDatabaseConstructor : RoomDatabaseConstructor<CacheDatabase> {
    override fun initialize(): CacheDatabase
}

internal suspend fun <R> RoomDatabase.connect(block: suspend () -> R): R = withWriteTransaction { block() }
