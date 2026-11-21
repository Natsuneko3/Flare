package dev.dimension.flare.ui.screen.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.dimension.flare.common.FileSystemUtilsExt
import dev.dimension.flare.data.datasource.microblog.ActionMenu
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.model.appearance.GlobalAppearance
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.di.DesktopKoinApplication
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.component.LocalGlobalAppearance
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.component.platform.LocalWindowSizeClass
import dev.dimension.flare.ui.component.platform.WindowSizeClass
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.UiIcon
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.createSampleUser
import dev.dimension.flare.ui.presenter.home.TimelinePresenter
import dev.dimension.flare.ui.presenter.profile.ProfileState
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.theme.FlareTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koin.core.context.stopKoin
import org.koin.plugin.module.dsl.startKoin
import kotlin.time.Instant

class ProfileTabsTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val home = TemporaryFolder()
    private val originalHome = System.getProperty("user.home")
    private val originalTemp = System.getProperty("java.io.tmpdir")
    private lateinit var scope: CoroutineScope
    private val owner =
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    private var tabs by mutableStateOf<ImmutableList<ProfileState.Tab>>(persistentListOf())
    private var accountType by mutableStateOf<AccountType>(AccountType.Guest)
    private var userKey by mutableStateOf(MicroBlogKey("alice", "example.test"))
    private var windowSize by mutableStateOf(WindowSizeClass.Compact)
    private var userState by mutableStateOf<UiState<UiProfile>>(UiState.Error(IllegalStateException("Profile header unavailable")))
    private var actions = persistentListOf<ActionMenu>()

    @Before
    fun setUp() {
        System.setProperty("user.home", home.root.path)
        val temp = home.newFolder("tmp")
        System.setProperty("java.io.tmpdir", temp.path)
        assertEquals(home.root.resolve(".flare"), FileSystemUtilsExt.flareDirectory())
        assertEquals(temp.resolve(".flare"), FileSystemUtilsExt.flareCacheDirectory())
        scope = startKoin<DesktopKoinApplication>().koin.get()
    }

    @After
    fun tearDown() {
        try {
            compose.runOnIdle { owner.viewModelStore.clear() }
            if (::scope.isInitialized) runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        } finally {
            stopKoin()
            System.setProperty("java.io.tmpdir", originalTemp)
            System.setProperty("user.home", originalHome)
        }
    }

    @Test
    fun insertingArticlesBeforeLikesKeepsSelectionAndBindsEachTimelineToItsOwnRows() {
        val posts = tab("Posts")
        val articles = tab("Articles")
        val likes = tab("Likes")
        tabs = persistentListOf(posts, likes)
        showProfile()
        waitForText("Posts row")
        compose.onNodeWithText("Likes").performClick()
        waitForText("Likes row")

        compose.runOnIdle { tabs = persistentListOf(posts, articles, likes) }
        waitForText("Articles")
        compose.onNodeWithText("Likes").assertIsSelected()
        compose.onNodeWithText("Likes row").assertIsDisplayed()
        compose.onNodeWithText("Articles").performClick()
        waitForText("Articles row")
        compose.onNodeWithText("Likes row").assertDoesNotExist()
        compose.onNodeWithText("Likes").performClick()
        waitForText("Likes row")
    }

    @Test
    fun removedSelectionFallsBackToTheFirstTabAndIsScopedToTheProfile() {
        val posts = tab("Posts")
        val articles = tab("Articles")
        val likes = tab("Likes")
        tabs = persistentListOf(posts, articles, likes)
        showProfile()
        compose.onNodeWithText("Likes").performClick()
        waitForText("Likes row")

        compose.runOnIdle { tabs = persistentListOf(posts, articles) }
        waitForText("Posts row")
        compose.onNodeWithText("Posts").assertIsSelected()
        compose.runOnIdle { tabs = persistentListOf(likes, posts) }
        waitForText("Likes")
        compose.onNodeWithText("Posts").assertIsSelected()
        compose.runOnIdle { tabs = persistentListOf() }
        compose.waitUntil { compose.onAllNodesWithText("Posts row").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Likes").assertDoesNotExist()
        compose.runOnIdle { tabs = persistentListOf(likes, posts) }
        waitForText("Likes row")

        compose.onNodeWithText("Posts").performClick()
        waitForText("Posts row")
        compose.runOnIdle {
            accountType = AccountType.GuestHost("example.test")
            tabs = persistentListOf(tab("Likes", "Likes on this account"), posts)
        }
        waitForText("Likes on this account")
        compose.onNodeWithText("Posts").performClick()
        waitForText("Posts row")
        compose.runOnIdle {
            userKey = userKey.copy(id = "bob")
            tabs = persistentListOf(tab("Likes", "Bob's likes"), posts)
        }
        waitForText("Bob's likes")
    }

    @Test
    fun profileMenuOpensWhileUserIsLoadingInCompactAndExpandedLayouts() {
        userState = UiState.Loading()
        actions =
            persistentListOf(
                ActionMenu.Group(
                    displayItem = ActionMenu.Item(text = ActionMenu.Item.Text.Raw("Profile actions")),
                    actions = persistentListOf(ActionMenu.Item(text = ActionMenu.Item.Text.Raw("Report"))),
                ),
            )
        showProfile()

        val user = createSampleUser().copy(key = userKey, avatar = null, banner = null)
        for (size in listOf(WindowSizeClass.Compact, WindowSizeClass.Expanded)) {
            compose.runOnIdle {
                windowSize = size
                userState = UiState.Loading()
            }
            compose.onNodeWithContentDescription("Profile actions").assertIsDisplayed().performClick()
            compose.onNodeWithText("Report").assertIsDisplayed()
            compose.runOnIdle { userState = UiState.Success(user) }
            waitForText(user.handle.display)
            compose.onNodeWithText("Report").assertIsDisplayed().performClick()
        }
    }

    private fun showProfile() {
        compose.setContent {
            FlareTheme(isDarkTheme = false) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalWindowSizeClass provides windowSize,
                    LocalGlobalAppearance provides GlobalAppearance(),
                    LocalTimelineAppearance provides TimelineAppearance(),
                ) {
                    ProfileScreen(accountType, userKey, profileState = { profile(tabs) })
                }
            }
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun tab(
        name: String,
        rowText: String = "$name row",
    ): ProfileState.Tab.Timeline {
        val row =
            UiTimelineV2.Message(
                statusKey = MicroBlogKey(name, "example.test"),
                icon = UiIcon.Info,
                type = UiTimelineV2.Message.Type.Raw(rowText),
                createdAt = Instant.fromEpochMilliseconds(1000).toUi(),
                clickEvent = ClickEvent.Noop,
                accountType = AccountType.Guest,
            )
        val loader =
            object : RemoteLoader<UiTimelineV2> {
                override suspend fun load(
                    pageSize: Int,
                    request: PagingRequest,
                ): PagingResult<UiTimelineV2> = PagingResult(listOf(row))
            }
        return ProfileState.Tab.Timeline(name, UiText.Raw(name), TimelinePresenter(loader = flowOf(loader)))
    }

    private fun profile(tabs: ImmutableList<ProfileState.Tab>): ProfileState =
        object : ProfileState(
            userState = userState,
            relationState = UiState.Loading(),
            followButtonState = UiState.Loading(),
            isMe = UiState.Loading(),
            actions = actions,
            myAccountKey = UiState.Loading(),
            tabs = UiState.Success(tabs),
        ) {
            override fun follow(userKey: MicroBlogKey) = Unit

            override fun unfollow(userKey: MicroBlogKey) = Unit

            override fun unblock(userKey: MicroBlogKey) = Unit

            override fun report(userKey: MicroBlogKey) = Unit

            override fun retryTabs() = Unit
        }
}
