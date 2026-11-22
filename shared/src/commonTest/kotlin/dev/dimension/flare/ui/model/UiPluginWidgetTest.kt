package dev.dimension.flare.ui.model

import dev.dimension.flare.common.decodeProtobuf
import dev.dimension.flare.common.encodeProtobuf
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.render.RenderContent
import dev.dimension.flare.ui.render.RenderRun
import dev.dimension.flare.ui.render.RenderTextStyle
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.render.toUiPlainText
import dev.dimension.flare.ui.render.uiRichTextOf
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

internal fun createSamplePost(
    id: String = "123",
    host: String = "sampleHost",
    text: String? = null,
    link: String? = null,
): UiTimelineV2.Post {
    val user = createSampleUser().copy(key = MicroBlogKey("sampleKey", host))
    val base =
        createSampleStatus(user).copy(
            statusKey = MicroBlogKey(id = id, host = host),
            createdAt = Instant.fromEpochSeconds(1_700_000_000L).toUi(),
        )
    if (text == null && link == null) {
        return base
    }
    val runs =
        buildList {
            if (text != null) {
                add(RenderRun.Text(text))
            }
            if (link != null) {
                if (text != null) {
                    add(RenderRun.Text(" "))
                }
                add(RenderRun.Text(link, RenderTextStyle(link = link)))
            }
        }
    return base.copy(
        content =
            UiTranslatableText(
                original = uiRichTextOf(renderRuns = listOf(RenderContent.Text(persistentListOf(*runs.toTypedArray())))),
            ),
    )
}

class UiPluginWidgetTest {
    @Test
    fun extractInputPreservesParagraphBlankLinesForOpenPgpAndCollectsLinks() {
        val rich =
            uiRichTextOf(
                renderRuns =
                    listOf(
                        RenderContent.Text(persistentListOf(RenderRun.Text("-----BEGIN PGP MESSAGE-----"))),
                        RenderContent.Text(
                            persistentListOf(
                                RenderRun.Text("hQEMA123\n=abcd\n-----END PGP MESSAGE----- "),
                                RenderRun.Text("market", RenderTextStyle(link = "https://polymarket.com/event/fed")),
                            ),
                        ),
                    ),
            )
        val post =
            createSamplePost().copy(
                content = UiTranslatableText(original = rich),
                card = UiCard(url = "https://polymarket.com/event/fed", title = "Fed Decision", description = null, media = null),
            )
        val extracted = post.extractPluginPostInput()
        assertTrue(extracted.contentPlain.startsWith("-----BEGIN PGP MESSAGE-----\n\nhQEMA123\n=abcd"))
        assertEquals(listOf("https://polymarket.com/event/fed"), extracted.links)
    }

    @Test
    fun flatSparklineNormalizesWithoutNaNAndRenderHashReflectsWidgetChanges() {
        val spark = UiWidgetNode.Sparkline(points = persistentListOf(42.0, 42.0, 42.0))
        assertEquals(listOf(0.5f, 0.5f, 0.5f), spark.normalizedPoints)

        val base = createSamplePost()
        val card =
            UiPluginWidgetCard(
                pluginId = "org.example.stock",
                pluginName = "Stocks",
                pluginIconPath = "/tmp/icon.png",
                id = "aapl",
                title = "AAPL",
                body = persistentListOf(spark),
            )
        val enriched = base.copy(pluginWidgets = persistentListOf(card))
        assertNotEquals(base.renderHash, enriched.renderHash)
        assertNotEquals(enriched.renderHash, enriched.copy(pluginWidgets = persistentListOf(card.copy(isRefreshing = true))).renderHash)
    }

    @Test
    fun withPluginWidgetsUpdatesRepostDisplayPostAndSkipsQuotes() {
        val original = createSamplePost()
        val quoted = createSamplePost(id = "quoted-id").copy(itemKey = "quoted")
        val wrapper = createSamplePost(id = "wrapper-id").copy(itemKey = "wrapper")
        val card =
            UiPluginWidgetCard(
                pluginId = "p1",
                pluginName = "P1",
                pluginIconPath = null,
                id = "c1",
                title = "Score",
                body = persistentListOf(UiWidgetNode.Text("108 - 105")),
            )
        val item =
            UiTimelineV2.TimelinePostItem(
                post = wrapper,
                presentation = UiTimelineV2.PostPresentation(repost = original, quotes = persistentListOf(quoted)),
            )
        val updated =
            item.withPluginWidgets { target ->
                if (target.statusKey == original.statusKey) persistentListOf(card) else persistentListOf()
            } as UiTimelineV2.TimelinePostItem
        assertEquals(1, updated.displayPost.pluginWidgets.size)
        assertTrue(
            updated.presentation.quotes
                .single()
                .pluginWidgets
                .isEmpty(),
        )
    }

    @Test
    fun extractInputFiltersUnsafeLinksAndProducesDeterministicSourceHash() {
        val rich =
            uiRichTextOf(
                renderRuns =
                    listOf(
                        RenderContent.Text(
                            persistentListOf(
                                RenderRun.Text("http", RenderTextStyle(link = "http://insecure.example/path")),
                                RenderRun.Text(" ", RenderTextStyle()),
                                RenderRun.Text("cred", RenderTextStyle(link = "https://user:secret@evil.example/path")),
                                RenderRun.Text(" ", RenderTextStyle()),
                                RenderRun.Text("emptyUser", RenderTextStyle(link = "https://@evil.example/path")),
                                RenderRun.Text(" ", RenderTextStyle()),
                                RenderRun.Text("upper", RenderTextStyle(link = "HTTPS://app.polymarket.com:443/event/fed")),
                                RenderRun.Text(" ", RenderTextStyle()),
                                RenderRun.Text("ok", RenderTextStyle(link = "https://app.polymarket.com:443/event/fed")),
                            ),
                        ),
                    ),
            )
        val post =
            createSamplePost().copy(
                content = UiTranslatableText(original = rich),
                contentWarning = UiTranslatableText(original = "Spoiler".toUiPlainText()),
                card = UiCard(url = "https://user@evil.example/card", title = "Bad Card", description = "Desc", media = null),
            )
        val extracted = post.extractPluginPostInput()
        assertEquals("${post.accountType}_${post.statusKey}", post.pluginPostEntityKey())
        assertEquals(post.pluginPostEntityKey(), extracted.postEntityKey)
        assertEquals("Spoiler", extracted.contentWarningPlain)
        assertEquals(listOf("https://app.polymarket.com:443/event/fed"), extracted.links)
        assertNull(extracted.cardUrl)
        assertNull(extracted.cardTitle)
        assertEquals(extracted.sourceHash, post.extractPluginPostInput().sourceHash)
        assertNotEquals(extracted.sourceHash, extracted.copy(authorDisplayName = "Renamed").sourceHash)

        val edited = post.copy(contentWarning = UiTranslatableText(original = "Updated Spoiler".toUiPlainText()))
        assertNotEquals(extracted.sourceHash, edited.extractPluginPostInput().sourceHash)

        val upperCardPost =
            post.copy(
                card = UiCard(url = "HTTPS://app.polymarket.com:443/event/fed", title = "Fed Card", description = null, media = null),
            )
        val upperCardExtracted = upperCardPost.extractPluginPostInput()
        assertEquals("https://app.polymarket.com:443/event/fed", upperCardExtracted.cardUrl)
        assertEquals(listOf("https://app.polymarket.com:443/event/fed"), upperCardExtracted.links)
    }

    @Test
    fun segmentedBarNormalizesWeightsAndCollapsibleStateUpdatesTimelineItemRenderHash() {
        val bar =
            UiWidgetNode.SegmentedBar(
                segments =
                    persistentListOf(
                        UiWidgetNode.SegmentedBar.Segment(label = "Yes", valueText = "68%", weight = 68.0, tone = UiWidgetTone.Positive),
                        UiWidgetNode.SegmentedBar.Segment(label = "No", valueText = "32%", weight = 32.0, tone = UiWidgetTone.Negative),
                    ),
            )
        assertEquals(listOf(0.68f, 0.32f), bar.normalizedFractions)

        val collapsedCard =
            UiPluginWidgetCard(
                pluginId = "p1",
                pluginName = "P1",
                pluginIconPath = null,
                id = "c1",
                title = "Details",
                body =
                    persistentListOf(
                        bar,
                        UiWidgetNode.Collapsible(
                            id = "sec-1",
                            summary = "More",
                            children = persistentListOf(UiWidgetNode.Text("Hidden line"), UiWidgetNode.Divider),
                            expanded = false,
                            onToggle = {},
                        ),
                    ),
                onRefresh = {},
            )
        val sameVisualDifferentCallbacks =
            collapsedCard.copy(
                body =
                    persistentListOf(
                        bar,
                        UiWidgetNode.Collapsible(
                            id = "sec-1",
                            summary = "More",
                            children = persistentListOf(UiWidgetNode.Text("Hidden line"), UiWidgetNode.Divider),
                            expanded = false,
                            onToggle = {},
                        ),
                    ),
                onRefresh = {},
            )
        val expandedCard =
            collapsedCard.copy(
                body =
                    persistentListOf(
                        bar,
                        UiWidgetNode.Collapsible(
                            id = "sec-1",
                            summary = "More",
                            children = persistentListOf(UiWidgetNode.Text("Hidden line"), UiWidgetNode.Divider),
                            expanded = true,
                        ),
                    ),
            )

        assertEquals(collapsedCard.renderSummaryHash(), sameVisualDifferentCallbacks.renderSummaryHash())
        assertNotEquals(collapsedCard.renderSummaryHash(), expandedCard.renderSummaryHash())

        val post = createSamplePost()
        val itemCollapsed = UiTimelineV2.TimelinePostItem(post = post.copy(pluginWidgets = persistentListOf(collapsedCard)))
        val itemExpanded = UiTimelineV2.TimelinePostItem(post = post.copy(pluginWidgets = persistentListOf(expandedCard)))
        assertNotEquals(itemCollapsed.renderHash, itemExpanded.renderHash)
    }

    @Test
    fun withPluginWidgetsUpdatesInlineParentsAndSecondaryConstructorAndProtobufPreserveDefaults() {
        val parentPost = createSamplePost(id = "parent-1")
        val mainPost = createSamplePost(id = "main-1")
        val card =
            UiPluginWidgetCard(
                pluginId = "p1",
                pluginName = "P1",
                pluginIconPath = null,
                id = "c1",
                title = "Parent Card",
                body = persistentListOf(UiWidgetNode.Text("Parent info")),
            )
        val item =
            UiTimelineV2.TimelinePostItem(
                post = mainPost,
                presentation =
                    UiTimelineV2.PostPresentation(
                        inlineParents = persistentListOf(UiTimelineV2.TimelinePostItem(post = parentPost)),
                    ),
            )
        val updated =
            item.withPluginWidgets { target ->
                if (target.statusKey == parentPost.statusKey) persistentListOf(card) else persistentListOf()
            } as UiTimelineV2.TimelinePostItem
        assertTrue(updated.displayPost.pluginWidgets.isEmpty())
        assertEquals(
            1,
            updated.presentation.inlineParents
                .single()
                .displayPost.pluginWidgets.size,
        )

        val enrichedPost = mainPost.copy(pluginWidgets = persistentListOf(card))
        val restored = assertIs<UiTimelineV2.Post>(enrichedPost.encodeProtobuf<UiTimelineV2>().decodeProtobuf<UiTimelineV2>())
        assertTrue(restored.pluginWidgets.isEmpty())

        val legacyConstructed =
            UiTimelineV2.Post(
                mainPost.platformId,
                mainPost.images,
                mainPost.sensitive,
                mainPost.contentWarning,
                mainPost.user,
                mainPost.platformIcon,
                mainPost.sourceLanguages,
                mainPost.translationDisplayState,
                mainPost.content,
                mainPost.actions,
                mainPost.poll,
                mainPost.statusKey,
                mainPost.card,
                mainPost.createdAt,
                mainPost.emojiReactions,
                mainPost.sourceChannel,
                mainPost.visibility,
                mainPost.replyToHandle,
                mainPost.references,
                mainPost.clickEvent,
                mainPost.mediaClickPolicy,
                mainPost.accountType,
                mainPost.itemKey,
                mainPost.pluginId,
                mainPost.authorSubtitleInternal,
                mainPost.communityNote,
            )
        assertTrue(legacyConstructed.pluginWidgets.isEmpty())
    }
}
