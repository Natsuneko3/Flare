package dev.dimension.flare.ui.presenter.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import dev.dimension.flare.common.collectAsState
import dev.dimension.flare.data.datasource.microblog.ActionMenu
import dev.dimension.flare.data.datasource.microblog.ProfileTab
import dev.dimension.flare.data.datasource.microblog.UnavailableMicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.accountKeyOrNull
import dev.dimension.flare.data.datasource.microblog.accountProfileFlow
import dev.dimension.flare.data.datasource.microblog.capabilities
import dev.dimension.flare.data.datasource.microblog.handler.ProfileTabsHandler
import dev.dimension.flare.data.datasource.microblog.loader.RelationActionType
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.repository.AccountRepository
import dev.dimension.flare.data.repository.NoActiveAccountException
import dev.dimension.flare.data.repository.accountServiceFlow
import dev.dimension.flare.data.repository.accountServiceProvider
import dev.dimension.flare.di.koinInject
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.UiHandle
import dev.dimension.flare.ui.model.UiIcon
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.UiRelation
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.flatMap
import dev.dimension.flare.ui.model.flatMapLatestUiState
import dev.dimension.flare.ui.model.flattenUiState
import dev.dimension.flare.ui.model.map
import dev.dimension.flare.ui.model.onSuccess
import dev.dimension.flare.ui.model.takeSuccess
import dev.dimension.flare.ui.model.takeSuccessOr
import dev.dimension.flare.ui.model.toUi
import dev.dimension.flare.ui.model.zipState
import dev.dimension.flare.ui.presenter.PresenterBase
import dev.dimension.flare.ui.presenter.home.TimelinePresenter
import dev.dimension.flare.ui.presenter.home.UserState
import dev.dimension.flare.ui.presenter.status.LogUserHistoryPresenter
import dev.dimension.flare.ui.route.DeeplinkRoute
import dev.dimension.flare.web.shared.WebPresenter
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalCoroutinesApi::class)
@WebPresenter("profile")
public class ProfilePresenter(
    private val accountType: AccountType,
    private val userKey: MicroBlogKey?,
) : PresenterBase<ProfileState>() {
    private val accountRepository: AccountRepository by koinInject()
    private val tabsReloadVersion = MutableStateFlow(0)

    private val serviceFlow by lazy {
        accountServiceFlow(accountType, accountRepository)
    }

    private val userStateFlow by lazy {
        serviceFlow.flatMapLatestUiState { service ->
            val userId =
                userKey?.id
                    ?: service.accountKeyOrNull?.id
                    ?: throw NoActiveAccountException
            if (userKey == null || userKey == service.accountKeyOrNull) {
                requireNotNull(service.accountProfileFlow())
            } else {
                requireNotNull(service.capabilities.profile).userHandler.userById(userId).toUi()
            }
        }
    }

    private val relationStateFlow by lazy {
        serviceFlow.flatMapLatestUiState { service ->
            val actualUserKey =
                userKey ?: service.accountKeyOrNull ?: run {
                    throw NoActiveAccountException
                }
            val relation =
                service.capabilities.relation
                    ?: return@flatMapLatestUiState flowOf(
                        UiState.Error(UnsupportedOperationException("Current service does not support relations")),
                    )
            relation.relationHandler.relation(actualUserKey).toUi()
        }
    }

    private val tabsFlow by lazy {
        tabsReloadVersion.flatMapLatest {
            serviceFlow
                .flatMapLatestUiState { service ->
                    val actualUserKey =
                        userKey
                            ?: service.accountKeyOrNull ?: run {
                            throw NoActiveAccountException
                        }
                    val profile = service.capabilities.profile
                    val rawTabsFlow: Flow<UiState<ImmutableList<ProfileTab>>> =
                        when {
                            profile == null -> {
                                flowOf(UiState.Success(persistentListOf()))
                            }

                            profile.supportsProfileTabsCache -> {
                                ProfileTabsHandler(
                                    accountType = accountType,
                                    profile = profile,
                                ).profileTabs(actualUserKey).toUi()
                            }

                            else -> {
                                flowOf(UiState.Success(profile.profileTabs(actualUserKey)))
                            }
                        }

                    val cachedTabs = mutableMapOf<ProfileTabReuseKey, ProfileState.Tab>()
                    rawTabsFlow
                        .map { state ->
                            state.map { profileTabs ->
                                profileTabs
                                    .map { tab ->
                                        val tabId = "${accountType}_${actualUserKey}_${tab.id}"
                                        val reuseKey =
                                            ProfileTabReuseKey(
                                                id = tabId,
                                                name = tab.name,
                                                displayType = tab.displayType,
                                                showAllImagesInGallery = tab.showAllImagesInGallery,
                                            )
                                        cachedTabs.getOrPut(reuseKey) {
                                            val timelinePresenter =
                                                object : TimelinePresenter() {
                                                    override val loader: Flow<RemoteLoader<UiTimelineV2>>
                                                        get() = flowOf(tab.loader)
                                                }
                                            when (tab.displayType) {
                                                ProfileTab.DisplayType.Timeline -> {
                                                    ProfileState.Tab.Timeline(
                                                        id = tabId,
                                                        name = tab.name,
                                                        presenter = timelinePresenter,
                                                    )
                                                }

                                                ProfileTab.DisplayType.Gallery -> {
                                                    ProfileState.Tab.Media(
                                                        id = tabId,
                                                        name = tab.name,
                                                        presenter =
                                                            ProfileMediaPresenter(
                                                                mediaTimelinePresenter = timelinePresenter,
                                                                showAllImages = tab.showAllImagesInGallery,
                                                            ),
                                                    )
                                                }
                                            }
                                        }
                                    }.toImmutableList()
                            }
                        }.distinctUntilChanged()
                }
        }
    }

    @Composable
    override fun body(): ProfileState {
        val service = accountServiceProvider(accountType = accountType, repository = accountRepository)
        val userState by userStateFlow.flattenUiState()
        service.onSuccess {
            val userKey = userKey ?: it.accountKeyOrNull
            if (userKey != null) {
                remember { LogUserHistoryPresenter(accountType, userKey) }.body()
            }
        }
        val relationState by relationStateFlow.flattenUiState()
        val currentService = service.takeSuccess()
        val isMeBool = currentService?.accountKeyOrNull?.let { it == userKey || userKey == null } ?: false
        val isMe =
            remember(service, userKey) {
                service.map { it.accountKeyOrNull?.let { key -> key == userKey || userKey == null } ?: false }
            }
        val myAccountKey =
            remember(service) {
                service.flatMap {
                    it.accountKeyOrNull?.let { key -> UiState.Success(key) }
                        ?: UiState.Error(NoActiveAccountException)
                }
            }
        val directMessage = if (!isMeBool && userKey != null) currentService?.capabilities?.directMessage else null
        val initialCanSend =
            remember(directMessage, userKey) {
                if (directMessage != null && userKey != null) {
                    directMessage.cachedCanSendDirectMessage(userKey) ?: false
                } else {
                    false
                }
            }
        val canSendMessage by produceState(initialValue = initialCanSend, key1 = directMessage, key2 = userKey) {
            if (directMessage == null || userKey == null) {
                value = false
            } else {
                directMessage.cachedCanSendDirectMessage(userKey)?.let { value = it }
                try {
                    value = directMessage.canSendDirectMessage(userKey)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    value = directMessage.cachedCanSendDirectMessage(userKey) ?: false
                }
            }
        }
        val shouldShowMenus =
            !isMeBool &&
                userKey != null &&
                currentService != null &&
                currentService !== UnavailableMicroblogDataSource &&
                userState !is UiState.Error
        val relation = relationState.takeSuccessOr(UiRelation())
        val isListDataSource = currentService?.capabilities?.list != null
        val supportedRelationTypes =
            currentService
                ?.capabilities
                ?.relation
                ?.supportedRelationTypes
                .orEmpty()
        val resolvedAccountKey = myAccountKey.takeSuccess()
        val profileMenus =
            remember(
                shouldShowMenus,
                canSendMessage,
                relation,
                supportedRelationTypes,
                isListDataSource,
                resolvedAccountKey,
                userKey,
            ) {
                if (!shouldShowMenus) {
                    persistentListOf()
                } else {
                    buildProfileMenus(
                        canSendMessage = canSendMessage,
                        relationState = relation,
                        supportedRelationTypes = supportedRelationTypes,
                        isListDataSource = isListDataSource,
                        myAccountKey = resolvedAccountKey,
                        userKey = userKey,
                    )
                }
            }
        val tabs by tabsFlow.collectAsState(UiState.Loading())
        val followButtonState =
            zipState(userState, relationState) { user, relation ->
                FollowButtonState.from(
                    profile = user,
                    relation = relation,
                )
            }

        return object : ProfileState(
            userState = userState,
            relationState = relationState,
            followButtonState = followButtonState,
            isMe = isMe,
            actions = profileMenus,
            myAccountKey = myAccountKey,
            tabs = tabs,
        ) {
            override fun follow(userKey: MicroBlogKey) {
                service.onSuccess { service ->
                    requireNotNull(service.capabilities.relation).relationHandler.follow(
                        userKey = userKey,
                        requestFollow = followButtonState.takeSuccess() == FollowButtonState.RequestFollow,
                    )
                }
            }

            override fun unfollow(userKey: MicroBlogKey) {
                service.onSuccess { service ->
                    requireNotNull(service.capabilities.relation).relationHandler.unfollow(userKey)
                }
            }

            override fun unblock(userKey: MicroBlogKey) {
                service.onSuccess { service ->
                    requireNotNull(service.capabilities.relation).relationHandler.unblock(userKey)
                }
            }

            override fun report(userKey: MicroBlogKey) {
            }

            override fun retryTabs() {
                tabsReloadVersion.value += 1
            }
        }
    }
}

private data class ProfileTabReuseKey(
    val id: String,
    val name: UiText,
    val displayType: ProfileTab.DisplayType,
    val showAllImagesInGallery: Boolean,
)

private fun buildProfileMenus(
    canSendMessage: Boolean,
    relationState: UiRelation,
    supportedRelationTypes: Set<RelationActionType>,
    isListDataSource: Boolean,
    myAccountKey: MicroBlogKey?,
    userKey: MicroBlogKey,
): ImmutableList<ActionMenu> =
    listOfNotNull(
        if (myAccountKey != null && isListDataSource) {
            ActionMenu.Item(
                icon = UiIcon.List,
                text = ActionMenu.Item.Text.Localized(ActionMenu.Item.Text.Localized.Type.EditUserList),
                clickEvent =
                    ClickEvent.Deeplink(
                        DeeplinkRoute.EditUserList(
                            accountKey = myAccountKey,
                            userKey = userKey,
                        ),
                    ),
            )
        } else {
            null
        },
        if (canSendMessage && myAccountKey != null) {
            ActionMenu.Item(
                icon = UiIcon.ChatMessage,
                text = ActionMenu.Item.Text.Localized(ActionMenu.Item.Text.Localized.Type.SendMessage),
                clickEvent =
                    ClickEvent.Deeplink(
                        DeeplinkRoute.DirectMessage(
                            accountKey = myAccountKey,
                            userKey = userKey,
                        ),
                    ),
            )
        } else {
            null
        },
    ).let { items ->
        items +
            listOfNotNull(
                if (items.isEmpty()) {
                    null
                } else {
                    ActionMenu.Divider
                },
            ) +
            listOfNotNull(
                if (RelationActionType.Block in supportedRelationTypes) {
                    ActionMenu.Item(
                        icon = if (relationState.blocking) UiIcon.UnBlock else UiIcon.Block,
                        text =
                            ActionMenu.Item.Text.Localized(
                                if (relationState.blocking) {
                                    ActionMenu.Item.Text.Localized.Type.UnBlock
                                } else {
                                    ActionMenu.Item.Text.Localized.Type.Block
                                },
                            ),
                        clickEvent =
                            ClickEvent.Deeplink(
                                if (relationState.blocking) {
                                    DeeplinkRoute.UnblockUser(
                                        accountKey = myAccountKey,
                                        userKey = userKey,
                                    )
                                } else {
                                    DeeplinkRoute.BlockUser(
                                        accountKey = myAccountKey,
                                        userKey = userKey,
                                    )
                                },
                            ),
                    )
                } else {
                    null
                },
                if (RelationActionType.Mute in supportedRelationTypes) {
                    ActionMenu.Item(
                        icon = if (relationState.muted) UiIcon.UnMute else UiIcon.Mute,
                        text =
                            ActionMenu.Item.Text.Localized(
                                if (relationState.muted) {
                                    ActionMenu.Item.Text.Localized.Type.UnMute
                                } else {
                                    ActionMenu.Item.Text.Localized.Type.Mute
                                },
                            ),
                        clickEvent =
                            ClickEvent.Deeplink(
                                if (relationState.muted) {
                                    DeeplinkRoute.UnmuteUser(
                                        accountKey = myAccountKey,
                                        userKey = userKey,
                                    )
                                } else {
                                    DeeplinkRoute.MuteUser(
                                        accountKey = myAccountKey,
                                        userKey = userKey,
                                    )
                                },
                            ),
                    )
                } else {
                    null
                },
                ActionMenu.Item(
                    text =
                        ActionMenu.Item.Text.Localized(
                            ActionMenu.Item.Text.Localized.Type.Report,
                        ),
                    icon = UiIcon.Report,
                    clickEvent =
                        ClickEvent.Deeplink(
                            DeeplinkRoute.ReportUser(
                                accountKey = myAccountKey,
                                userKey = userKey,
                            ),
                        ),
                    color = ActionMenu.Item.Color.Red,
                ),
            )
    }.let { actions ->
        if (actions.isEmpty()) {
            actions
        } else {
            listOf(
                ActionMenu.Group(
                    displayItem =
                        ActionMenu.Item(
                            icon = UiIcon.MoreVerticel,
                        ),
                    actions = actions.toImmutableList(),
                ),
            )
        }
    }.toImmutableList()

@Immutable
public abstract class ProfileState(
    public val userState: UiState<UiProfile>,
    public val relationState: UiState<UiRelation>,
    public val followButtonState: UiState<FollowButtonState>,
    public val isMe: UiState<Boolean>,
    public val actions: ImmutableList<ActionMenu>,
    public val myAccountKey: UiState<MicroBlogKey>,
    public val tabs: UiState<ImmutableList<Tab>>,
) {
    public abstract fun follow(userKey: MicroBlogKey)

    public abstract fun unfollow(userKey: MicroBlogKey)

    public abstract fun unblock(userKey: MicroBlogKey)

    public abstract fun report(userKey: MicroBlogKey)

    public abstract fun retryTabs()

    @Immutable
    public sealed class Tab {
        public abstract val id: String
        public abstract val name: UiText

        @Immutable
        public data class Timeline(
            override val id: String,
            override val name: UiText,
            val presenter: TimelinePresenter,
        ) : Tab()

        @Immutable
        public data class Media(
            override val id: String,
            override val name: UiText,
            val presenter: ProfileMediaPresenter,
        ) : Tab()
    }
}

@Immutable
public sealed class FollowButtonState {
    public abstract val id: String

    @Immutable
    public data object Follow : FollowButtonState() {
        override val id: String = "follow"
    }

    @Immutable
    public data object RequestFollow : FollowButtonState() {
        override val id: String = "request_follow"
    }

    @Immutable
    public data object Requested : FollowButtonState() {
        override val id: String = "requested"
    }

    @Immutable
    public data object Following : FollowButtonState() {
        override val id: String = "following"
    }

    @Immutable
    public data object Blocked : FollowButtonState() {
        override val id: String = "blocked"
    }

    public companion object {
        public fun from(
            profile: UiProfile,
            relation: UiRelation,
        ): FollowButtonState =
            when {
                relation.blocking -> Blocked
                relation.following -> Following
                relation.hasPendingFollowRequestFromYou -> Requested
                UiProfile.Mark.Locked in profile.mark -> RequestFollow
                else -> Follow
            }
    }
}

@WebPresenter("profileUserLookup")
public class ProfileWithUserNameAndHostPresenter(
    private val userName: String,
    private val host: String,
    private val accountType: AccountType,
) : PresenterBase<UserState>() {
    private val accountRepository: AccountRepository by koinInject()

    @Composable
    override fun body(): UserState {
        val serviceState =
            accountServiceProvider(
                accountType = accountType,
                repository = accountRepository,
            )
        val profileState = remember(serviceState) { serviceState.map { requireNotNull(it.capabilities.profile) } }
        val userState =
            profileState.flatMap { profile ->
                remember(profile) {
                    profile.userHandler
                        .userByHandleAndHost(
                            UiHandle(
                                raw = userName,
                                host = host,
                            ),
                        )
                }.collectAsState().toUi()
            }
        return object : UserState {
            override val user: UiState<UiProfile>
                get() = userState
        }
    }
}
