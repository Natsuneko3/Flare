package dev.dimension.flare.data.datasource.instagram

import dev.dimension.flare.data.datasource.microblog.AuthenticatedMicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.DatabaseUpdater
import dev.dimension.flare.data.datasource.microblog.PostEvent
import dev.dimension.flare.data.datasource.microblog.ProfileTab
import dev.dimension.flare.data.datasource.microblog.SearchOperation
import dev.dimension.flare.data.datasource.microblog.datasource.PostDataSource
import dev.dimension.flare.data.datasource.microblog.datasource.TimelineTabConfigurationDataSource
import dev.dimension.flare.data.datasource.microblog.datasource.UserDataSource
import dev.dimension.flare.data.datasource.microblog.handler.PostEventHandler
import dev.dimension.flare.data.datasource.microblog.handler.PostHandler
import dev.dimension.flare.data.datasource.microblog.handler.UserHandler
import dev.dimension.flare.data.datasource.microblog.loader.PostLoader
import dev.dimension.flare.data.datasource.microblog.loader.UserLoader
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.datasource.microblog.paging.notSupported
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.model.tab.ShortcutSpec
import dev.dimension.flare.data.model.tab.TimelineCandidate
import dev.dimension.flare.data.model.tab.TimelineSpec
import dev.dimension.flare.data.network.instagram.InstagramService
import dev.dimension.flare.data.platform.CommonTimelineSpecs
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiHandle
import dev.dimension.flare.ui.model.UiHashtag
import dev.dimension.flare.ui.model.UiIcon
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.UiStrings
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.asType
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

internal class InstagramDataSource(
    override val accountKey: MicroBlogKey,
    private val service: InstagramService,
) : AuthenticatedMicroblogDataSource,
    UserDataSource,
    PostDataSource,
    UserLoader,
    PostLoader,
    PostEventHandler.Handler,
    TimelineTabConfigurationDataSource {
    private val accountType = AccountType.Specific(accountKey)
    override val userHandler by lazy { UserHandler(host = accountKey.host, loader = this, proxy = ProxyRef.forAccount(accountKey)) }
    override val postHandler by lazy { PostHandler(accountType, this) }
    override val postEventHandler by lazy { PostEventHandler(accountType, this) }

    private val homeTab =
        CommonTimelineSpecs.home.candidate(
            data = TimelineSpec.AccountBasedData(accountKey),
            icon = UiIcon.Instagram.asType(),
            title = UiText.Raw("Instagram"),
        )
    override val defaultTabs: ImmutableList<TimelineCandidate<*>> = persistentListOf(homeTab)
    override val builtInTimelineTabs: ImmutableList<TimelineCandidate<*>> = defaultTabs
    override val shortcuts = persistentListOf(ShortcutSpec(UiStrings.Home, UiIcon.Home, ShortcutSpec.Target.Timeline(homeTab)))

    override fun homeTimeline(): RemoteLoader<UiTimelineV2> =
        InstagramTimelineLoader(
            pagingKey = "instagram_home_$accountKey",
            canJoinTimeline = true,
            fetch = service::home,
            map = { it.toInstagramPostOrNull(accountKey) },
        )

    override fun userTimeline(
        userKey: MicroBlogKey,
        mediaOnly: Boolean,
    ): RemoteLoader<UiTimelineV2> =
        InstagramTimelineLoader(
            pagingKey = "instagram_posts_${userKey}_${accountKey}_$mediaOnly",
            fetch = { cursor -> service.userPosts(userKey.id, cursor) },
            map = { item -> item.toInstagramPostOrNull(accountKey)?.takeIf { !mediaOnly || it.images.isNotEmpty() } },
        )

    override fun context(statusKey: MicroBlogKey): RemoteLoader<UiTimelineV2> =
        InstagramContextLoader(accountKey, statusKey, service::post, service::comments)

    override suspend fun userByHandleAndHost(uiHandle: UiHandle): UiProfile =
        service.userByHandle(uiHandle.normalizedRaw).toInstagramProfile(accountKey)

    override suspend fun userById(id: String): UiProfile = service.userById(id).toInstagramProfile(accountKey)

    override suspend fun status(statusKey: MicroBlogKey): UiTimelineV2 =
        requireNotNull(service.post(statusKey.id).toInstagramPostOrNull(accountKey)) { "Instagram did not return the requested post" }

    override suspend fun profileTabs(userKey: MicroBlogKey): ImmutableList<ProfileTab> =
        persistentListOf(ProfileTab(name = UiStrings.Posts, loader = userTimeline(userKey, mediaOnly = false)))

    override val searchOperations: Set<SearchOperation> = emptySet()

    override fun searchStatus(query: String): RemoteLoader<UiTimelineV2> = notSupported()

    override fun searchUser(query: String): RemoteLoader<UiProfile> = notSupported()

    override fun discoverUsers(): RemoteLoader<UiProfile> = notSupported()

    override fun discoverStatuses(): RemoteLoader<UiTimelineV2> = notSupported()

    override fun discoverHashtags(): RemoteLoader<UiHashtag> = notSupported()

    override fun following(userKey: MicroBlogKey): RemoteLoader<UiProfile> = notSupported()

    override fun fans(userKey: MicroBlogKey): RemoteLoader<UiProfile> = notSupported()

    override suspend fun deleteStatus(statusKey: MicroBlogKey): Unit = throw UnsupportedOperationException("Instagram posts are read-only")

    override suspend fun handle(
        event: PostEvent,
        updater: DatabaseUpdater,
    ): Unit = throw UnsupportedOperationException("Instagram posts are read-only")
}
