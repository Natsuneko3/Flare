package dev.dimension.flare.ui.screen.manga

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import dev.dimension.flare.common.PagingState
import dev.dimension.flare.common.toPagingState
import dev.dimension.flare.data.datasource.microblog.ActionMenu
import dev.dimension.flare.data.datasource.microblog.PostActionFamily
import dev.dimension.flare.data.model.appearance.GlobalAppearance
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.component.LocalGlobalAppearance
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.MangaReadingDirection
import dev.dimension.flare.ui.model.UiIcon
import dev.dimension.flare.ui.model.UiMangaChapter
import dev.dimension.flare.ui.model.UiMangaChapterGroup
import dev.dimension.flare.ui.model.UiMangaDetail
import dev.dimension.flare.ui.model.UiMangaStatus
import dev.dimension.flare.ui.model.UiMangaTag
import dev.dimension.flare.ui.model.UiMangaTagGroup
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.presenter.manga.ChapterBoundary
import dev.dimension.flare.ui.presenter.manga.MangaDetailPresenter
import dev.dimension.flare.ui.presenter.manga.MangaReaderPresenter
import dev.dimension.flare.ui.presenter.manga.MangaTranslatePresenter
import dev.dimension.flare.ui.presenter.manga.PageMediaStatus
import dev.dimension.flare.ui.presenter.manga.PrimaryReadTarget
import dev.dimension.flare.ui.presenter.manga.ReaderPageState
import dev.dimension.flare.ui.presenter.manga.ReaderSlot
import dev.dimension.flare.ui.presenter.manga.UiMangaChapterItem
import dev.dimension.flare.ui.presenter.media.ImageTranslationItem
import dev.dimension.flare.ui.presenter.media.ImageTranslationPresenter
import dev.dimension.flare.ui.render.toUiPlainText
import dev.dimension.flare.ui.route.DeeplinkRoute
import dev.dimension.flare.ui.route.Route
import dev.dimension.flare.ui.theme.FlareTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(coil3.annotation.DelicateCoilApi::class)
class MangaScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        SingletonImageLoader.setUnsafe { context ->
            ImageLoader
                .Builder(context)
                .components {
                    add(
                        Fetcher.Factory<Any> { data, _, _ ->
                            val text = data.toString()
                            if (!text.startsWith("local-")) return@Factory null
                            Fetcher {
                                ImageFetchResult(
                                    image = ColorImage(color = 0xFF445566.toInt(), width = 1000, height = 1400),
                                    isSampled = false,
                                    dataSource = DataSource.MEMORY,
                                )
                            }
                        },
                    )
                }.build()
        }
    }

    @After
    fun tearDown() {
        SingletonImageLoader.reset()
    }

    @Test
    fun rendersGroupedTagsAndChaptersAndHandlesLockedChapterClick() {
        val mangaKey = MicroBlogKey("manga-42", "manga.example")
        val accountType = AccountType.GuestPlugin(platformId = "manga-plat", host = "manga.example")
        val tankobonChapters =
            persistentListOf(
                UiMangaChapter(
                    id = "ch-1",
                    title = "Chapter 1: Dawn",
                    subtitle = "32p",
                    uploadedAt = null,
                    locked = false,
                    url = "https://manga.example/ch/1",
                ),
                UiMangaChapter(
                    id = "ch-locked",
                    title = "Chapter 2: Locked Bonus",
                    subtitle = "Paid",
                    uploadedAt = null,
                    locked = true,
                    url = "https://manga.example/ch/2-locked",
                ),
            )
        val serialChapters =
            persistentListOf(
                UiMangaChapter(
                    id = "ch-serial-1",
                    title = "Serial Episode 1",
                    subtitle = null,
                    uploadedAt = null,
                    locked = false,
                    url = "https://manga.example/serial/1",
                ),
            )
        val detail =
            UiMangaDetail(
                pluginId = "plugin.manga",
                platformId = "manga-plat",
                mangaKey = mangaKey,
                accountType = accountType,
                title = "Starlight Odyssey",
                altTitles = persistentListOf("星光奥德赛"),
                cover = null,
                author = null,
                authors = persistentListOf("Hana Sato"),
                artists = persistentListOf("Kenji Mori"),
                status = UiMangaStatus.Ongoing,
                content = "A journey across star seas.".toUiPlainText(),
                url = "https://manga.example/series/42",
                updatedAt = null,
                readingDirection = MangaReadingDirection.Rtl,
                tagGroups =
                    persistentListOf(
                        UiMangaTagGroup(
                            id = "genre",
                            title = UiText.Raw("Genre"),
                            tags =
                                persistentListOf(
                                    UiMangaTag("Action", ClickEvent.Noop),
                                    UiMangaTag("Sci-Fi", ClickEvent.Noop),
                                ),
                        ),
                        UiMangaTagGroup(
                            id = "theme",
                            title = UiText.Raw("Theme"),
                            tags =
                                persistentListOf(
                                    UiMangaTag("Space Opera", ClickEvent.Noop),
                                ),
                        ),
                    ),
                chapterGroups =
                    persistentListOf(
                        UiMangaChapterGroup(
                            id = "tankobon",
                            title = UiText.Raw("Tankobon"),
                            chapters = tankobonChapters,
                        ),
                        UiMangaChapterGroup(
                            id = "serial",
                            title = UiText.Raw("Serial"),
                            chapters = serialChapters,
                        ),
                    ),
                isBookmarked = false,
                bookmarkAction = ClickEvent.Noop,
                actions = persistentListOf(),
                sourceLanguages = persistentListOf("ja"),
            )

        val fakeState = FakeMangaDetailState(detail)
        val navigatedRoutes = mutableListOf<Route>()
        val openedUrls = mutableListOf<String>()
        val uriHandler =
            object : UriHandler {
                override fun openUri(uri: String) {
                    openedUrls += uri
                }
            }

        compose.setContent {
            CompositionLocalProvider(
                LocalUriHandler provides uriHandler,
                LocalTimelineAppearance provides TimelineAppearance(),
                LocalGlobalAppearance provides GlobalAppearance(),
            ) {
                FlareTheme(isDarkTheme = false) {
                    Box(Modifier.size(1100.dp, 850.dp)) {
                        MangaDetailContent(
                            state = fakeState,
                            navigate = { navigatedRoutes += it },
                        )
                    }
                }
            }
        }

        // Tag group headers and tags
        compose.onNodeWithText("A journey across star seas.").assertIsDisplayed()
        compose.onNodeWithTag("manga_synopsis_toggle").assertDoesNotExist()
        compose.onNodeWithTag("manga_cta_bookmark_fallback").assertDoesNotExist()
        compose.onNodeWithText("Genre").assertIsDisplayed()
        compose.onNodeWithText("Action").assertIsDisplayed()
        compose.onNodeWithText("Theme").assertIsDisplayed()
        compose.onNodeWithText("Space Opera").assertIsDisplayed()

        // Chapter group chips
        compose.onNodeWithText("Tankobon").assertIsDisplayed()
        compose.onNodeWithText("Serial").assertIsDisplayed()

        // Sort toggle
        compose.onNodeWithTag("manga_chapter_sort_toggle").performClick()
        compose.runOnIdle {
            assertEquals(true, fakeState.chapterSortDescending)
        }

        // Primary Read CTA
        compose.onNodeWithTag("manga_primary_read_cta").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(
                    Route.Manga.Reader(
                        mangaKey = mangaKey,
                        chapterId = "ch-1",
                        initialPage = 0,
                        accountType = accountType,
                    ),
                ),
                navigatedRoutes,
            )
        }

        // Locked chapter click shows locked chapter prompt and opens URL on confirm
        compose.onNodeWithText("Chapter 2: Locked Bonus").performClick()
        compose.onNodeWithTag("manga_locked_chapter_confirm").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(
                "Expected locked chapter URL to be opened, got $openedUrls",
                openedUrls.any { it.contains("https://manga.example/ch/2-locked") || DeeplinkRoute.parse(it) != null },
            )
        }

        // Chapter group selection switches chapters
        compose.onNodeWithText("Serial").performClick()
        compose.runOnIdle {
            assertEquals("serial", fakeState.selectedGroupId)
        }
        compose.onNodeWithText("Serial Episode 1").assertIsDisplayed()

        // Chapter context menu marks chapter as read
        compose.onNodeWithTag("manga_chapter_menu_ch-serial-1").performClick()
        compose.onNodeWithText("Mark as read").performClick()
        compose.runOnIdle {
            assertTrue(fakeState.chapters.single().isRead)
        }

        // Conditional Comments / Recommendations tabs only appear when supported
        compose.onNodeWithText("Comments").assertDoesNotExist()
        compose.onNodeWithText("Recommendations").assertDoesNotExist()
        compose.runOnIdle {
            fakeState.supportsComments = true
            fakeState.supportsRecommendations = true
        }
        compose.onNodeWithText("Comments").assertIsDisplayed()
        compose.onNodeWithText("Recommendations").assertIsDisplayed()
    }

    @Test
    fun deeplinkRoutesMapToDesktopMangaDetailAndReader() {
        val mangaKey = MicroBlogKey("m-1", "manga.example")
        val specificAccount = AccountType.Specific(MicroBlogKey("u-1", "manga.example"))
        val guestPluginAccount = AccountType.GuestPlugin(platformId = "plat", host = "manga.example")

        for (account in listOf(specificAccount, guestPluginAccount)) {
            val detailRoute =
                Route.from(
                    DeeplinkRoute.Manga.Detail(
                        mangaKey = mangaKey,
                        accountType = account,
                    ),
                )
            assertEquals(Route.Manga.Detail(mangaKey = mangaKey, accountType = account), detailRoute)

            val readerRoute =
                Route.from(
                    DeeplinkRoute.Manga.Reader(
                        mangaKey = mangaKey,
                        chapterId = "ch-7",
                        initialPage = 3,
                        accountType = account,
                    ),
                )
            assertEquals(
                Route.Manga.Reader(
                    mangaKey = mangaKey,
                    chapterId = "ch-7",
                    initialPage = 3,
                    accountType = account,
                ),
                readerRoute,
            )
        }
    }

    @Test
    fun readerScreenFlipsScrubberInRtlAndShowsCollapsedBadge() {
        val fakeReaderState = FakeDesktopReaderState(pageCount = 5, initialDirection = MangaReadingDirection.Rtl)

        compose.setContent {
            FlareTheme(isDarkTheme = true) {
                Box(Modifier.size(900.dp, 700.dp)) {
                    MangaReaderContent(
                        state = fakeReaderState,
                        onBack = {},
                    )
                }
            }
        }

        // In RTL mode, tapping near the left edge of the page slider advances toward the end of the chapter
        val sliderBounds = compose.onNodeWithTag("manga_reader_page_slider").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("manga_reader_page_slider").performTouchInput {
            click(Offset(sliderBounds.width * 0.1f, sliderBounds.height * 0.5f))
        }
        compose.runOnIdle {
            assertTrue(
                "Expected RTL left-side slider tap to advance pageIndex > 2, got ${fakeReaderState.currentPageIndex}",
                fakeReaderState.currentPageIndex >= 3,
            )
        }

        // Toggle HUD off via center tap zone and verify collapsed bottom-right page badge appears
        val canvasBounds = compose.onNodeWithTag("manga_reader_canvas").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("manga_reader_canvas").performTouchInput {
            click(Offset(canvasBounds.width * 0.5f, canvasBounds.height * 0.5f))
        }
        compose.onNodeWithTag("manga_reader_collapsed_page_badge").assertIsDisplayed()
    }

    @Test
    fun shareUsesTheSelectedPageWithoutAChapterUrl() {
        val reader = FakeDesktopReaderState(pageCount = 3, initialDirection = MangaReadingDirection.Ltr, chapterUrl = null)
        reader.goToPage(1)
        var shared: UiMedia? = null
        compose.setContent {
            FlareTheme(isDarkTheme = true) {
                MangaReaderContent(state = reader, onBack = {}, onSharePage = { _, displayed, _ -> shared = displayed })
            }
        }
        compose.onNodeWithTag("manga_reader_share_page").performClick()
        assertEquals("local-1", shared?.url)
    }

    @Test
    fun readerSettingsHidesDoublePageSpreadOnNarrowViewport() {
        val fakeReaderState = FakeDesktopReaderState(pageCount = 4, initialDirection = MangaReadingDirection.Rtl)
        var viewportWidth by mutableStateOf(500.dp)

        compose.setContent {
            FlareTheme(isDarkTheme = true) {
                Box(Modifier.size(viewportWidth, 700.dp)) {
                    MangaReaderContent(
                        state = fakeReaderState,
                        onBack = {},
                    )
                }
            }
        }

        // Open reader settings flyout on narrow viewport (< 720.dp) -> Double-page spread must be hidden
        compose.onNodeWithTag("manga_reader_settings_button").performClick()
        compose.onNodeWithText("Reading direction").assertIsDisplayed()
        compose.onNodeWithText("Double-page spread").assertDoesNotExist()

        // Widen viewport (>= 720.dp) -> Double-page spread becomes available
        compose.runOnIdle {
            viewportWidth = 900.dp
        }
        compose.onNodeWithText("Double-page spread").assertIsDisplayed()
    }

    @Test
    fun guestModeRendersSingleDisabledBookmarkAndCollapsesLongSynopsis() {
        val mangaKey = MicroBlogKey("manga-guest-1", "manga.example")
        val guestAccount = AccountType.GuestPlugin(platformId = "manga-plat", host = "manga.example")
        val longSynopsis =
            (1..8).joinToString(separator = "\n") { idx ->
                "Synopsis paragraph line $idx describing the long saga across distant star systems."
            }
        val guestBookmarkItem =
            ActionMenu.Item(
                updateKey = "bookmark",
                icon = UiIcon.Bookmark,
                text = ActionMenu.Item.Text.Localized(ActionMenu.Item.Text.Localized.Type.Bookmark),
                clickEvent = ClickEvent.Noop,
                actionFamily = PostActionFamily.Bookmark,
                enabled = false,
            )
        val detail =
            UiMangaDetail(
                pluginId = "plugin.manga",
                platformId = "manga-plat",
                mangaKey = mangaKey,
                accountType = guestAccount,
                title = "Guest Starlight",
                altTitles = persistentListOf(),
                cover = null,
                author = null,
                authors = persistentListOf("Hana Sato"),
                artists = persistentListOf(),
                status = UiMangaStatus.Ongoing,
                content = longSynopsis.toUiPlainText(),
                url = "https://manga.example/series/guest-1",
                updatedAt = null,
                readingDirection = MangaReadingDirection.Rtl,
                tagGroups = persistentListOf(),
                chapterGroups =
                    persistentListOf(
                        UiMangaChapterGroup(
                            id = "main",
                            title = null,
                            chapters =
                                persistentListOf(
                                    UiMangaChapter("ch-1", "Chapter 1: Dawn", null, null, locked = false, url = null),
                                ),
                        ),
                    ),
                isBookmarked = false,
                bookmarkAction = ClickEvent.Noop,
                actions = persistentListOf(guestBookmarkItem),
                sourceLanguages = persistentListOf("en"),
            )

        val fakeState = FakeMangaDetailState(detail)

        compose.setContent {
            CompositionLocalProvider(
                LocalTimelineAppearance provides TimelineAppearance(),
                LocalGlobalAppearance provides GlobalAppearance(),
            ) {
                FlareTheme(isDarkTheme = false) {
                    Box(Modifier.size(1100.dp, 850.dp)) {
                        MangaDetailContent(
                            state = fakeState,
                            navigate = {},
                        )
                    }
                }
            }
        }

        // No duplicate standalone CTA heart button when detail.actions is non-empty,
        // and the single Bookmark action in MangaActionsMenu is disabled in guest mode.
        compose.onNodeWithTag("manga_cta_bookmark_fallback").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add bookmark").assertIsDisplayed().assertIsNotEnabled()

        // Multi-line synopsis (> 4 lines) shows expand/collapse toggle and expands/collapses height on click.
        compose.onNodeWithTag("manga_synopsis_toggle").assertIsDisplayed()
        val collapsedToggleTop =
            compose
                .onNodeWithTag("manga_synopsis_toggle")
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        compose.onNodeWithTag("manga_synopsis_toggle").performClick()
        val expandedToggleTop =
            compose
                .onNodeWithTag("manga_synopsis_toggle")
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        assertTrue(
            "Expected expanding > 4 line synopsis to push toggle downward ($collapsedToggleTop -> $expandedToggleTop)",
            expandedToggleTop > collapsedToggleTop + 20f,
        )
        compose.onNodeWithTag("manga_synopsis_toggle").performClick()
        val recollapsedToggleTop =
            compose
                .onNodeWithTag("manga_synopsis_toggle")
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        assertEquals(collapsedToggleTop, recollapsedToggleTop, 2f)
    }

    @Test
    fun adaptiveChapterGridReflowsBetweenMultiColumnAndSingleColumn() {
        val mangaKey = MicroBlogKey("manga-grid-1", "manga.example")
        val accountType = AccountType.GuestPlugin(platformId = "manga-plat", host = "manga.example")
        val detail =
            UiMangaDetail(
                pluginId = "plugin.manga",
                platformId = "manga-plat",
                mangaKey = mangaKey,
                accountType = accountType,
                title = "Grid Reflow Saga",
                altTitles = persistentListOf(),
                cover = null,
                author = null,
                authors = persistentListOf("Hana Sato"),
                artists = persistentListOf(),
                status = UiMangaStatus.Ongoing,
                content = "Short synopsis.".toUiPlainText(),
                url = "https://manga.example/series/grid-1",
                updatedAt = null,
                readingDirection = MangaReadingDirection.Rtl,
                tagGroups = persistentListOf(),
                chapterGroups =
                    persistentListOf(
                        UiMangaChapterGroup(
                            id = "main",
                            title = null,
                            chapters =
                                persistentListOf(
                                    UiMangaChapter(
                                        id = "ch-1",
                                        title = "Chapter 1: Dawn",
                                        subtitle = "24p",
                                        uploadedAt = null,
                                        locked = false,
                                        url = "https://manga.example/ch/1",
                                    ),
                                    UiMangaChapter(
                                        id = "ch-2",
                                        title = "Chapter 2: Horizon",
                                        subtitle = "26p",
                                        uploadedAt = null,
                                        locked = false,
                                        url = "https://manga.example/ch/2",
                                    ),
                                ),
                        ),
                    ),
                isBookmarked = false,
                bookmarkAction = ClickEvent.Noop,
                actions = persistentListOf(),
                sourceLanguages = persistentListOf("en"),
            )

        val fakeState = FakeMangaDetailState(detail)
        var viewportWidth by mutableStateOf(1100.dp)

        compose.setContent {
            CompositionLocalProvider(
                LocalTimelineAppearance provides TimelineAppearance(),
                LocalGlobalAppearance provides GlobalAppearance(),
            ) {
                FlareTheme(isDarkTheme = false) {
                    Box(Modifier.size(viewportWidth, 850.dp)) {
                        MangaDetailContent(
                            state = fakeState,
                            navigate = {},
                        )
                    }
                }
            }
        }

        // 1. At viewportWidth = 1100.dp (chapters pane = 720.dp >= 520.dp): multi-column grid
        val row1 = compose.onNodeWithTag("manga_chapter_row_ch-1").fetchSemanticsNode().boundsInRoot
        val row2 = compose.onNodeWithTag("manga_chapter_row_ch-2").fetchSemanticsNode().boundsInRoot
        assertEquals(row1.top, row2.top, 2f)
        assertTrue(
            "Expected ch-1 left (${row1.left}) < ch-2 left (${row2.left})",
            row1.left < row2.left,
        )

        // 2. At viewportWidth = 820.dp (chapters pane = 440.dp < 520.dp): single-column stacked list
        compose.runOnIdle {
            viewportWidth = 820.dp
        }
        val stacked1 = compose.onNodeWithTag("manga_chapter_row_ch-1").fetchSemanticsNode().boundsInRoot
        val stacked2 = compose.onNodeWithTag("manga_chapter_row_ch-2").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Expected stacked ch-1 bottom (${stacked1.bottom}) <= ch-2 top (${stacked2.top}) + 2f",
            stacked1.bottom <= stacked2.top + 2f,
        )

        // 3. Clicking manga_detail_refresh invokes fakeState.refresh() and disables while refreshing
        compose.onNodeWithTag("manga_detail_refresh").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, fakeState.refreshCount)
            fakeState.isRefreshing = true
        }
        compose.onNodeWithTag("manga_detail_refresh").assertIsNotEnabled()
        compose.onNodeWithTag("manga_detail_refresh").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, fakeState.refreshCount)
        }
    }

    @Test
    fun narrowViewportShowsTabSwitcherOnlyWhenMultiTabAndFallsBackSafely() {
        val mangaKey = MicroBlogKey("manga-narrow-1", "manga.example")
        val accountType = AccountType.GuestPlugin(platformId = "manga-plat", host = "manga.example")
        val detail =
            UiMangaDetail(
                pluginId = "plugin.manga",
                platformId = "manga-plat",
                mangaKey = mangaKey,
                accountType = accountType,
                title = "Narrow Viewport Saga",
                altTitles = persistentListOf(),
                cover = null,
                author = null,
                authors = persistentListOf("Hana Sato"),
                artists = persistentListOf(),
                status = UiMangaStatus.Ongoing,
                content = "Compact synopsis.".toUiPlainText(),
                url = "https://manga.example/series/narrow-1",
                updatedAt = null,
                readingDirection = MangaReadingDirection.Rtl,
                tagGroups = persistentListOf(),
                chapterGroups =
                    persistentListOf(
                        UiMangaChapterGroup(
                            id = "main",
                            title = null,
                            chapters =
                                persistentListOf(
                                    UiMangaChapter("ch-1", "Chapter 1: Dawn", null, null, locked = false, url = null),
                                    UiMangaChapter("ch-2", "Chapter 2: Horizon", null, null, locked = false, url = null),
                                ),
                        ),
                    ),
                isBookmarked = false,
                bookmarkAction = ClickEvent.Noop,
                actions = persistentListOf(),
                sourceLanguages = persistentListOf("en"),
            )

        val fakeState = FakeMangaDetailState(detail)

        compose.setContent {
            CompositionLocalProvider(
                LocalTimelineAppearance provides TimelineAppearance(),
                LocalGlobalAppearance provides GlobalAppearance(),
            ) {
                FlareTheme(isDarkTheme = false) {
                    Box(Modifier.size(520.dp, 850.dp)) {
                        MangaDetailContent(
                            state = fakeState,
                            navigate = {},
                        )
                    }
                }
            }
        }

        // 1. Single-tab mode: "Chapters (2)" header shown, no PillButton tab bar, 2.dp gap between .listCard chapter rows
        compose.onNodeWithText("Chapters (2)").assertIsDisplayed()
        compose.onNodeWithText("Chapters").assertDoesNotExist()
        compose.onNodeWithText("Comments").assertDoesNotExist()
        compose.onNodeWithText("Recommendations").assertDoesNotExist()
        compose.onNodeWithTag("manga_chapter_row_ch-1").assertIsDisplayed()
        compose.onNodeWithTag("manga_chapter_row_ch-2").assertIsDisplayed()
        val expectedChapterGapPx = with(compose.density) { 2.dp.toPx() }
        val compactRow1 = compose.onNodeWithTag("manga_chapter_row_ch-1").fetchSemanticsNode().boundsInRoot
        val compactRow2 = compose.onNodeWithTag("manga_chapter_row_ch-2").fetchSemanticsNode().boundsInRoot
        assertEquals(expectedChapterGapPx, compactRow2.top - compactRow1.bottom, 0.5f)

        // 2. Multi-tab mode: tabs appear; header spacing stays consistent across tabs; losing Comments falls back to Chapters
        compose.runOnIdle {
            fakeState.supportsComments = true
            fakeState.supportsRecommendations = true
        }
        compose.onNodeWithText("Chapters").assertIsDisplayed()
        compose.onNodeWithText("Comments").assertIsDisplayed()
        compose.onNodeWithText("Recommendations").assertIsDisplayed()
        val chaptersSynopsisTop =
            compose
                .onNodeWithText("Compact synopsis.")
                .fetchSemanticsNode()
                .boundsInRoot
                .top

        compose.onNodeWithText("Recommendations").performClick()
        val recommendationsSynopsisTop =
            compose
                .onNodeWithText("Compact synopsis.")
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        assertEquals(chaptersSynopsisTop, recommendationsSynopsisTop, 0.5f)

        compose.onNodeWithText("Comments").performClick()
        val commentsSynopsisTop =
            compose
                .onNodeWithText("Compact synopsis.")
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        assertEquals(chaptersSynopsisTop, commentsSynopsisTop, 0.5f)
        compose.onNodeWithTag("manga_chapter_row_ch-1").assertDoesNotExist()

        compose.runOnIdle {
            fakeState.supportsComments = false
        }
        compose.onNodeWithText("Comments").assertDoesNotExist()
        compose.onNodeWithText("Recommendations").assertIsDisplayed()
        compose.onNodeWithText("Chapters (2)").assertIsDisplayed()
        compose.onNodeWithTag("manga_chapter_row_ch-1").assertIsDisplayed()
    }
}

private class FakeMangaDetailState(
    private val initialDetail: UiMangaDetail,
) : MangaDetailPresenter.State {
    override val detail: UiState<UiMangaDetail> = UiState.Success(initialDetail)
    override val translation: MangaTranslatePresenter.State? = null
    override var selectedGroupId: String? by mutableStateOf(initialDetail.chapterGroups.firstOrNull()?.id)
    override var chapterSortDescending: Boolean by mutableStateOf(false)
    private var readChapterIds by mutableStateOf(emptySet<String>())

    override val chapters: ImmutableList<UiMangaChapterItem>
        get() {
            val group = initialDetail.chapterGroups.firstOrNull { it.id == selectedGroupId } ?: return persistentListOf()
            val list = if (chapterSortDescending) group.chapters.asReversed() else group.chapters
            return list
                .map { ch ->
                    UiMangaChapterItem(
                        chapter = ch,
                        isRead = ch.id in readChapterIds,
                        isCurrent = false,
                        lastPageIndex = 0,
                        pageCount = 0,
                    )
                }.toImmutableList()
        }

    override val primaryReadTarget: PrimaryReadTarget =
        PrimaryReadTarget(
            chapterId = "ch-1",
            chapterTitle = "Chapter 1: Dawn",
            initialPage = 0,
            isContinue = false,
            isSingleChapterWork = false,
        )
    override var supportsComments: Boolean by mutableStateOf(false)
    override var supportsRecommendations: Boolean by mutableStateOf(false)
    override val comments: PagingState<UiTimelineV2> = UiState.Loading<LazyPagingItems<UiTimelineV2>>().toPagingState()
    override val recommendations: PagingState<UiTimelineV2> = UiState.Loading<LazyPagingItems<UiTimelineV2>>().toPagingState()
    override var isRefreshing: Boolean by mutableStateOf(false)
    var refreshCount: Int by mutableIntStateOf(0)

    override fun refresh() {
        refreshCount++
    }

    override fun selectGroup(groupId: String) {
        selectedGroupId = groupId
    }

    override fun toggleChapterSort() {
        chapterSortDescending = !chapterSortDescending
    }

    override fun setChapterRead(
        chapterId: String,
        isRead: Boolean,
    ) {
        readChapterIds = if (isRead) readChapterIds + chapterId else readChapterIds - chapterId
    }
}

private class FakeDesktopReaderState(
    pageCount: Int,
    initialDirection: MangaReadingDirection,
    override val chapterUrl: String? = "https://manga.example/ch/1",
) : MangaReaderPresenter.State {
    override val mangaTitle: String = "Starlight Odyssey"
    override val chapterTitle: String = "Chapter 1"
    override val activeChapterId: String = "ch-1"
    override val loadState: UiState<Unit> = UiState.Success(Unit)
    override val pages: ImmutableList<ReaderPageState> =
        (0 until pageCount)
            .map { idx ->
                val media = UiMedia.Image("local-$idx", "local-$idx", null, 1400f, 1000f, false)
                ReaderPageState(
                    id = "p-$idx",
                    index = idx,
                    status = PageMediaStatus.Ready(media, media),
                    aspectRatio = 1000f / 1400f,
                )
            }.toImmutableList()
    override val slots: ImmutableList<ReaderSlot> = (0 until pageCount).map(ReaderSlot::Single).toImmutableList()
    override var currentPageIndex: Int by mutableIntStateOf(0)
    override val currentSlotIndex: Int get() = currentPageIndex
    private var _direction by mutableStateOf(initialDirection)
    private var _doublePageSpread by mutableStateOf(false)
    private var _spreadCoverSinglePage by mutableStateOf(true)
    override val direction: MangaReadingDirection get() = _direction
    override val doublePageSpread: Boolean get() = _doublePageSpread
    override val spreadCoverSinglePage: Boolean get() = _spreadCoverSinglePage
    override val previousBoundary: ChapterBoundary = ChapterBoundary.None
    override val nextBoundary: ChapterBoundary = ChapterBoundary.None
    override val prefetchMedia: ImmutableList<UiMedia.Image> = persistentListOf()
    override val imageTranslation: ImageTranslationPresenter.State =
        object : ImageTranslationPresenter.State {
            override val configured: Boolean = false

            override fun item(media: UiMedia): ImageTranslationItem = ImageTranslationItem()

            override fun displayed(media: UiMedia): UiMedia = media

            override fun toggle(media: UiMedia) {}

            override fun regenerate(media: UiMedia) {}

            override fun clearError(media: UiMedia) {}

            override fun setErrorDetailsVisible(
                media: UiMedia,
                visible: Boolean,
            ) {}

            override fun imageFailed(
                media: UiMedia,
                url: String,
            ) {}
        }

    override fun setWideViewport(isWide: Boolean) {}

    override fun onPageDimensionsLoaded(
        pageId: String,
        width: Float,
        height: Float,
    ) {}

    override fun goToPage(pageIndex: Int) {
        currentPageIndex = pageIndex.coerceIn(0, pages.lastIndex)
    }

    override fun goToSlot(slotIndex: Int) {
        currentPageIndex = slotIndex.coerceIn(0, pages.lastIndex)
    }

    override fun stepForward() {
        if (currentPageIndex < pages.lastIndex) currentPageIndex++
    }

    override fun stepBackward() {
        if (currentPageIndex > 0) currentPageIndex--
    }

    override fun openAdjacentChapter(boundary: ChapterBoundary.Unlocked) {}

    override fun retryChapter() {}

    override fun retryPage(pageId: String) {}

    override fun setDirection(direction: MangaReadingDirection) {
        _direction = direction
    }

    override fun setDoublePageSpread(enabled: Boolean) {
        _doublePageSpread = enabled
    }

    override fun setSpreadCoverSinglePage(enabled: Boolean) {
        _spreadCoverSinglePage = enabled
    }
}
