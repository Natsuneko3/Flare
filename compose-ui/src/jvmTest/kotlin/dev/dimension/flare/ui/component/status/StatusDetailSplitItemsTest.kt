package dev.dimension.flare.ui.component.status

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.dimension.flare.common.PagingState
import dev.dimension.flare.common.collectPagingState
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.component.adaptive.AdaptiveSplitLayout
import dev.dimension.flare.ui.component.adaptive.PrimaryWidthStrategy
import dev.dimension.flare.ui.component.platform.LocalWindowSizeClass
import dev.dimension.flare.ui.component.platform.WindowSizeClass
import dev.dimension.flare.ui.humanizer.PlatformFormatter
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.UiHandle
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.UiTranslatableText
import dev.dimension.flare.ui.model.createSampleUser
import dev.dimension.flare.ui.presenter.status.StatusContextPresenter
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.render.toUiPlainText
import io.github.composefluent.FluentTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.assertEquals
import kotlin.time.Instant

class StatusDetailSplitItemsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        startKoin {
            modules(
                module {
                    single<PlatformFormatter> {
                        object : PlatformFormatter {
                            override fun formatNumber(number: Long) = number.toString()

                            override fun formatRelativeInstant(instant: Instant) = "5m"

                            override fun formatFullInstant(instant: Instant) = "5m"

                            override fun formatAbsoluteInstant(instant: Instant) = "5m"
                        }
                    }
                },
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun splitLayoutRendersThreadHeaderInPrimaryPaneAndControlsWithRepliesInSecondaryPane() {
        val parent = samplePost("parent-1", "Nora", "Has anyone walked the canal path today?")
        val anchor = samplePost("root-1", "Maya", "Yes, the west footbridge is open again.")
        val reply = samplePost("reply-1", "Alex", "Thanks for checking the route!")
        val replyFlow = flowOf(listOf<UiTimelineV2>(reply))

        compose.setContent {
            val replyState = replyFlow.collectPagingState().value
            val state =
                fakeDetailState(
                    current = UiState.Success(anchor),
                    header = persistentListOf(parent, anchor),
                    tabs =
                        persistentListOf(
                            StatusContextPresenter.Choice("replies", UiText.Raw("Replies")),
                            StatusContextPresenter.Choice("quotes", UiText.Raw("Quotes")),
                        ),
                    filters =
                        persistentListOf(
                            StatusContextPresenter.Choice("top", UiText.Raw("Top")),
                        ),
                    listState = replyState,
                )
            FluentTheme {
                CompositionLocalProvider(
                    LocalWindowSizeClass provides WindowSizeClass.Expanded,
                    LocalTimelineAppearance provides TimelineAppearance(showTranslateButton = false),
                ) {
                    Box(Modifier.size(width = 1000.dp, height = 700.dp)) {
                        AdaptiveSplitLayout(
                            primaryWidthStrategy = PrimaryWidthStrategy.PostDetailDefault,
                            unfoldedGap = 8.dp,
                            compactContent = { padding ->
                                LazyStatusVerticalStaggeredGrid(
                                    columns = StaggeredGridCells.Fixed(1),
                                    contentPadding = padding,
                                    modifier = Modifier.fillMaxSize().testTag("compact-grid"),
                                ) {
                                    statusDetail(state, anchor.statusKey)
                                }
                            },
                            primaryPane = { _, padding ->
                                LazyStatusVerticalStaggeredGrid(
                                    columns = StaggeredGridCells.Fixed(1),
                                    contentPadding = padding,
                                    modifier = Modifier.fillMaxSize().testTag("primary-grid"),
                                ) {
                                    statusDetailHeaderItems(state, anchor.statusKey, splitFallback = true)
                                }
                            },
                            secondaryPane = { _, padding ->
                                LazyStatusVerticalStaggeredGrid(
                                    contentPadding = padding,
                                    modifier = Modifier.fillMaxSize().testTag("secondary-grid"),
                                ) {
                                    statusDetailBodyItems(state, anchor.statusKey)
                                }
                            },
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("primary-grid").assertLeftPositionInRootIsEqualTo(0.dp).assertWidthIsEqualTo(460.dp)
        compose.onNodeWithTag("secondary-grid").assertLeftPositionInRootIsEqualTo(468.dp).assertWidthIsEqualTo(532.dp)
        compose.onNodeWithText("Has anyone walked the canal path today?").assertIsDisplayed()
        compose.onNodeWithText("Yes, the west footbridge is open again.").assertIsDisplayed()
        compose.onNodeWithText("Quotes").assertIsDisplayed()
        compose.onNodeWithTag("status-detail-filter").assertIsDisplayed()
        compose.onNodeWithText("Thanks for checking the route!").assertIsDisplayed()
    }

    @Test
    fun splitHeaderShowsErrorRetryWhenHeaderEmptyWhileCompactDefersToListState() {
        var retryClicks by mutableIntStateOf(0)
        var useSplit by mutableStateOf(true)
        var currentState by mutableStateOf<UiState<UiTimelineV2>>(UiState.Error(IllegalStateException("Network down")))
        val statusKey = MicroBlogKey("missing-root", "x.com")
        val emptyPagingFlow = flowOf(emptyList<UiTimelineV2>())

        compose.setContent {
            val emptyPagingState = emptyPagingFlow.collectPagingState().value
            val state =
                fakeDetailState(
                    current = currentState,
                    header = persistentListOf(),
                    tabs = persistentListOf(),
                    filters = persistentListOf(),
                    listState = emptyPagingState,
                    onRefreshAsync = { retryClicks++ },
                )
            FluentTheme {
                CompositionLocalProvider(
                    LocalTimelineAppearance provides TimelineAppearance(showTranslateButton = false),
                ) {
                    Box(Modifier.size(width = 600.dp, height = 600.dp)) {
                        LazyStatusVerticalStaggeredGrid(
                            columns = StaggeredGridCells.Fixed(1),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            if (useSplit) {
                                statusDetailHeaderItems(state, statusKey, splitFallback = true)
                            } else {
                                statusDetail(state, statusKey)
                            }
                        }
                    }
                }
            }
        }

        compose.onNodeWithText("Network down").assertIsDisplayed()
        compose.onNode(hasClickAction()).performClick()
        assertEquals(1, retryClicks)

        // When Compact mode has empty header while loading, statusDetail must not emit a duplicate header placeholder.
        compose.runOnIdle {
            useSplit = false
            currentState = UiState.Loading()
        }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText("Lorem ipsum", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun splitModeSuppressesDuplicateMirroredErrorInSecondaryPaneWhileCompactShowsSingleError() {
        var useSplit by mutableStateOf(true)
        val statusKey = MicroBlogKey("broken-root", "x.com")
        val failure = IllegalStateException("Root load failed")
        val mirroredErrorFlow = kotlinx.coroutines.flow.flow<List<UiTimelineV2>> { throw failure }

        compose.setContent {
            val mirroredErrorPaging = mirroredErrorFlow.collectPagingState().value
            val state =
                fakeDetailState(
                    current = UiState.Error(failure),
                    header = persistentListOf(),
                    tabs = persistentListOf(),
                    filters = persistentListOf(),
                    listState = mirroredErrorPaging,
                )
            FluentTheme {
                CompositionLocalProvider(
                    LocalWindowSizeClass provides if (useSplit) WindowSizeClass.Expanded else WindowSizeClass.Compact,
                    LocalTimelineAppearance provides TimelineAppearance(showTranslateButton = false),
                ) {
                    Box(Modifier.size(width = 1000.dp, height = 700.dp)) {
                        AdaptiveSplitLayout(
                            primaryWidthStrategy = PrimaryWidthStrategy.PostDetailDefault,
                            compactContent = { padding ->
                                LazyStatusVerticalStaggeredGrid(
                                    columns = StaggeredGridCells.Fixed(1),
                                    contentPadding = padding,
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    statusDetail(state, statusKey)
                                }
                            },
                            primaryPane = { _, padding ->
                                LazyStatusVerticalStaggeredGrid(
                                    columns = StaggeredGridCells.Fixed(1),
                                    contentPadding = padding,
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    statusDetailHeaderItems(state, statusKey, splitFallback = true)
                                }
                            },
                            secondaryPane = { _, padding ->
                                LazyStatusVerticalStaggeredGrid(
                                    contentPadding = padding,
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    statusDetailBodyItems(state, statusKey, suppressMirroredHeaderError = true)
                                }
                            },
                        )
                    }
                }
            }
        }

        assertEquals(1, compose.onAllNodesWithText("Root load failed").fetchSemanticsNodes().size)

        compose.runOnIdle { useSplit = false }
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText("Root load failed").fetchSemanticsNodes().size)
    }

    @Test
    fun deckModeCompactOverrideKeepsSingleColumnInsideWideWindow() {
        val anchor = samplePost("deck-1", "Maya", "Single column inside Deck pane.")
        val emptyPagingFlow = flowOf(emptyList<UiTimelineV2>())

        compose.setContent {
            val emptyPagingState = emptyPagingFlow.collectPagingState().value
            val state =
                fakeDetailState(
                    current = UiState.Success(anchor),
                    header = persistentListOf(anchor),
                    tabs = persistentListOf(),
                    filters = persistentListOf(),
                    listState = emptyPagingState,
                )
            FluentTheme {
                CompositionLocalProvider(
                    LocalWindowSizeClass provides WindowSizeClass.Compact,
                    LocalTimelineAppearance provides TimelineAppearance(showTranslateButton = false),
                ) {
                    Box(Modifier.size(width = 1000.dp, height = 700.dp), contentAlignment = Alignment.TopCenter) {
                        AdaptiveSplitLayout(
                            primaryWidthStrategy = PrimaryWidthStrategy.PostDetailDefault,
                            unfoldedGap = 8.dp,
                            compactContent = { padding ->
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                    LazyStatusVerticalStaggeredGrid(
                                        columns = StaggeredGridCells.Fixed(1),
                                        contentPadding = padding,
                                        modifier = Modifier.widthIn(max = 480.dp).testTag("deck-compact-grid"),
                                    ) {
                                        statusDetail(state, anchor.statusKey)
                                    }
                                }
                            },
                            primaryPane = { _, _ -> Box(Modifier.fillMaxSize().testTag("split-primary")) },
                            secondaryPane = { _, _ -> Box(Modifier.fillMaxSize().testTag("split-secondary")) },
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("deck-compact-grid").assertIsDisplayed()
        compose.onNodeWithText("Single column inside Deck pane.").assertIsDisplayed()
        compose.onNodeWithTag("split-primary").assertDoesNotExist()
    }

    private fun samplePost(
        id: String,
        author: String,
        body: String,
    ): UiTimelineV2.Post {
        val host = "bsky.social"
        val user =
            createSampleUser().copy(
                key = MicroBlogKey(author.lowercase(), host),
                handle = UiHandle(raw = author.lowercase(), host = host),
                avatar = null,
                banner = null,
                nameInternal = author.toUiPlainText(),
                platformId = "Bluesky",
            )
        return UiTimelineV2.Post(
            platformId = "Bluesky",
            images = persistentListOf(),
            sensitive = false,
            contentWarning = null,
            user = user,
            content = UiTranslatableText(body.toUiPlainText()),
            actions = persistentListOf(),
            poll = null,
            statusKey = MicroBlogKey(id, host),
            card = null,
            createdAt = Instant.parse("2026-11-15T07:00:00Z").toUi(),
            clickEvent = ClickEvent.Noop,
            accountType = AccountType.Specific(MicroBlogKey("viewer", host)),
        )
    }

    private fun fakeDetailState(
        current: UiState<UiTimelineV2>,
        header: ImmutableList<UiTimelineV2>,
        tabs: ImmutableList<StatusContextPresenter.Choice>,
        filters: ImmutableList<StatusContextPresenter.Choice>,
        listState: PagingState<UiTimelineV2>,
        onRefreshAsync: () -> Unit = {},
    ): StatusContextPresenter.State =
        object : StatusContextPresenter.State {
            override val current: UiState<UiTimelineV2> = current
            override val header: ImmutableList<UiTimelineV2> = header
            override val tabs: ImmutableList<StatusContextPresenter.Choice> = tabs
            override val selectedTabId: String? = tabs.firstOrNull()?.id
            override val filters: ImmutableList<StatusContextPresenter.Choice> = filters
            override val selectedFilterId: String? = filters.firstOrNull()?.id
            override val defaultFilterId: String? = filters.firstOrNull()?.id
            override val listState: PagingState<UiTimelineV2> = listState

            override fun selectTab(id: String) = Unit

            override fun selectFilter(id: String) = Unit

            override fun refreshAsync() = onRefreshAsync()

            override suspend fun refresh() = Unit
        }
}
