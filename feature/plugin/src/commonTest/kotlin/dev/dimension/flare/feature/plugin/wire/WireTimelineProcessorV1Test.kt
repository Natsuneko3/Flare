package dev.dimension.flare.feature.plugin.wire

import dev.dimension.flare.feature.plugin.abi.PluginJsonV1
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.ExtractedPluginPostInput
import dev.dimension.flare.ui.model.UiWidgetAction
import dev.dimension.flare.ui.model.UiWidgetAlign
import dev.dimension.flare.ui.model.UiWidgetButtonStyle
import dev.dimension.flare.ui.model.UiWidgetCornerStyle
import dev.dimension.flare.ui.model.UiWidgetGap
import dev.dimension.flare.ui.model.UiWidgetNode
import dev.dimension.flare.ui.model.UiWidgetSparklineHeight
import dev.dimension.flare.ui.model.UiWidgetTextStyle
import dev.dimension.flare.ui.model.UiWidgetTone
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WireTimelineProcessorV1Test {
    @Test
    fun parsesAllSupportedNodesClampsTtlAndStripsImagesWithoutMediaPermission() {
        val raw =
            PluginJsonV1.parseToJsonElement(
                """
                {
                  "results": [{
                    "itemId": "i1",
                    "cache": { "policy": "disk", "ttlSeconds": 3 },
                    "cards": [{
                      "id": "c1",
                      "title": "NBA",
                      "badge": { "text": "LIVE", "tone": "positive" },
                      "body": [
                        { "type": "matchup", "left": { "name": "LAL", "iconUrl": "https://cdn.example.com/lal.png", "score": "108" }, "right": { "name": "GSW", "score": "105" } },
                        { "type": "sparkline", "points": [100, 104.5, 102] },
                        { "type": "segmentedBar", "segments": [{ "label": "Yes", "weight": 68, "tone": "positive" }, { "label": "No", "weight": 32, "tone": "negative" }] },
                        { "type": "image", "url": "https://cdn.example.com/chart.png" }
                      ],
                      "actions": [
                        { "label": "Open", "action": { "type": "openUrl", "url": "https://polymarket.com/event/1" } },
                        { "label": "FutureBet", "action": { "type": "pluginAction", "actionId": "bet" } }
                      ]
                    }]
                  }]
                }
                """.trimIndent(),
            )
        val parsed = parseAndValidateTimelineProcessBatchResponse(raw, setOf("i1")).getValue("i1")
        assertEquals(15L, parsed.ttlSeconds)
        val withoutMedia =
            parsed.cards
                .single()
                .toUiCard(
                    pluginId = "p1",
                    pluginName = "P1",
                    pluginIconPath = null,
                    allowMediaNetwork = false,
                    isRefreshing = false,
                    expandedCollapsibles = emptySet(),
                    onToggleCollapsible = {},
                    onRefresh = null,
                )!!
        assertTrue(withoutMedia.body.none { it is UiWidgetNode.Image })
        assertNull((withoutMedia.body.first() as UiWidgetNode.Matchup).left.iconUrl)
        assertEquals(1, withoutMedia.actions.size) // unknown v2 pluginAction button omitted additively
    }

    @Test
    fun dropsCardWhoseBodyBecomesEmptyAfterOmittingUnknownFutureNodes() {
        val raw =
            PluginJsonV1.parseToJsonElement(
                """
                {
                  "results": [{
                    "itemId": "i1",
                    "cards": [{
                      "id": "future-only",
                      "body": [{ "type": "futureHologramV2", "data": 123 }]
                    }]
                  }]
                }
                """.trimIndent(),
            )
        val parsed = parseAndValidateTimelineProcessBatchResponse(raw, setOf("i1")).getValue("i1")
        assertTrue(parsed.cards.isEmpty())
    }

    @Test
    fun encodesBatchRequestWithExpectedWireFieldsAndNullOptionalObjects() {
        val richInput =
            ExtractedPluginPostInput(
                postEntityKey = "Mastodon_101@mastodon.social",
                platformId = "mastodon",
                postKey = MicroBlogKey("101", "mastodon.social"),
                createdAtIso = "2026-11-21T12:00:00Z",
                authorHandle = "@alice@mastodon.social",
                authorDisplayName = "Alice",
                contentPlain = "Watching \$AAPL\n\nSecond paragraph",
                contentWarningPlain = "Market talk",
                links = listOf("https://polymarket.com/event/1"),
                cardUrl = "https://polymarket.com/event/1",
                cardTitle = "Fed Rate Cut",
                cardDescription = "Market odds",
                sourceLanguages = listOf("en"),
                sourceHash = "internal-hash-1",
            )
        val minimalInput =
            ExtractedPluginPostInput(
                postEntityKey = "Mastodon_102@mastodon.social",
                platformId = "mastodon",
                postKey = MicroBlogKey("102", "mastodon.social"),
                createdAtIso = "2026-11-21T12:05:00Z",
                authorHandle = null,
                authorDisplayName = null,
                contentPlain = "Plain post",
                contentWarningPlain = null,
                links = emptyList(),
                cardUrl = null,
                cardTitle = null,
                cardDescription = null,
                sourceLanguages = emptyList(),
                sourceHash = "internal-hash-2",
            )

        val encoded =
            encodeTimelineProcessBatchRequest(
                locale = "en-US",
                trigger = TimelineProcessTriggerV1.ManualRefresh,
                items = listOf("item-0" to richInput, "item-1" to minimalInput),
            )

        assertEquals("en-US", encoded.getValue("locale").jsonPrimitive.content)
        assertEquals("manualRefresh", encoded.getValue("trigger").jsonPrimitive.content)
        val items = encoded.getValue("items").jsonArray
        assertEquals(2, items.size)

        val first = items[0].jsonObject
        assertEquals("item-0", first.getValue("itemId").jsonPrimitive.content)
        assertEquals("mastodon", first.getValue("platformId").jsonPrimitive.content)
        assertEquals(
            "101",
            first
                .getValue("postKey")
                .jsonObject
                .getValue("id")
                .jsonPrimitive.content,
        )
        assertEquals(
            "mastodon.social",
            first
                .getValue("postKey")
                .jsonObject
                .getValue("host")
                .jsonPrimitive.content,
        )
        assertEquals("2026-11-21T12:00:00Z", first.getValue("createdAt").jsonPrimitive.content)
        assertEquals(
            "@alice@mastodon.social",
            first
                .getValue("author")
                .jsonObject
                .getValue("handle")
                .jsonPrimitive.content,
        )
        assertEquals(
            "Alice",
            first
                .getValue("author")
                .jsonObject
                .getValue("displayName")
                .jsonPrimitive.content,
        )
        assertEquals("Watching \$AAPL\n\nSecond paragraph", first.getValue("contentPlain").jsonPrimitive.content)
        assertEquals("Market talk", first.getValue("contentWarningPlain").jsonPrimitive.content)
        assertEquals(listOf("https://polymarket.com/event/1"), first.getValue("links").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(
            "https://polymarket.com/event/1",
            first
                .getValue("card")
                .jsonObject
                .getValue("url")
                .jsonPrimitive.content,
        )
        assertEquals(
            "Fed Rate Cut",
            first
                .getValue("card")
                .jsonObject
                .getValue("title")
                .jsonPrimitive.content,
        )
        assertEquals(
            "Market odds",
            first
                .getValue("card")
                .jsonObject
                .getValue("description")
                .jsonPrimitive.content,
        )
        assertEquals(listOf("en"), first.getValue("sourceLanguages").jsonArray.map { it.jsonPrimitive.content })
        assertFalse("sourceHash" in first)
        assertFalse("postEntityKey" in first)

        val second = items[1].jsonObject
        assertEquals(JsonNull, second.getValue("author"))
        assertEquals(JsonNull, second.getValue("contentWarningPlain"))
        assertEquals(JsonNull, second.getValue("card"))
        assertTrue(second.getValue("links").jsonArray.isEmpty())
        assertTrue(second.getValue("sourceLanguages").jsonArray.isEmpty())

        assertFailsWith<IllegalArgumentException> {
            encodeTimelineProcessBatchRequest("en", TimelineProcessTriggerV1.Auto, emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            encodeTimelineProcessBatchRequest("en", TimelineProcessTriggerV1.Auto, List(51) { "i$it" to minimalInput })
        }
        assertFailsWith<IllegalArgumentException> {
            encodeTimelineProcessBatchRequest("en", TimelineProcessTriggerV1.Auto, listOf("dup" to minimalInput, "dup" to minimalInput))
        }
    }

    @Test
    fun parsesAllTwelveNodeTypesCollapsibleStateMediaPermissionAndDiskRoundTrip() {
        val raw =
            PluginJsonV1.parseToJsonElement(
                """
                {
                  "results": [{
                    "itemId": "i1",
                    "cache": { "policy": "memoryOnly", "ttlSeconds": 9999999 },
                    "cards": [
                      {
                        "id": "full-card",
                        "title": "   ",
                        "accentTone": "accent",
                        "cardAction": { "type": "copy", "text": "copied-card", "label": "Card" },
                        "body": [
                          {
                            "type": "row",
                            "gap": "xs",
                            "align": "spaceBetween",
                            "children": [
                              { "type": "stat", "label": "AAPL", "value": "${'$'}192.40", "delta": "+1.8%", "deltaTone": "positive", "subtext": "NASDAQ", "weight": 1.5 },
                              { "type": "sparkline", "points": [188.0, 190.5, 192.4], "tone": "positive", "minLabel": "188", "maxLabel": "193", "height": "md", "weight": 1.0 }
                            ]
                          },
                          { "type": "divider" },
                          {
                            "type": "column",
                            "gap": "md",
                            "align": "end",
                            "children": [
                              { "type": "text", "text": "-----BEGIN PGP-----", "style": "code", "tone": "warning", "maxLines": 4, "copyable": true },
                              { "type": "badgeRow", "badges": [{ "text": "VERIFIED", "tone": "positive" }, { "text": "PGP" }] },
                              {
                                "type": "keyValue",
                                "items": [
                                  { "label": "Explorer", "value": "View", "tone": "accent", "action": { "type": "openUrl", "url": "https://example.com/tx/1" } },
                                  { "label": "Future", "value": "Inert", "action": { "type": "futureV2", "x": 1 } }
                                ]
                              },
                              { "type": "image", "url": "https://cdn.example.com/ spark.png".replace(" ", ""), "alt": "Chart", "aspectRatio": 1.777, "cornerStyle": "circle", "weight": 2.0 }
                            ]
                          },
                          {
                            "type": "collapsible",
                            "id": "details-collapsed",
                            "summary": "More details",
                            "defaultExpanded": false,
                            "children": [{ "type": "text", "text": "Hidden leaf" }]
                          },
                          {
                            "type": "collapsible",
                            "id": "details-open",
                            "summary": "Open by default",
                            "defaultExpanded": true,
                            "children": [{ "type": "text", "text": "Visible leaf" }]
                          }
                        ],
                        "actions": [
                          { "label": "Refresh Now", "tone": "accent", "style": "primary", "action": { "type": "refresh" } }
                        ]
                      },
                      {
                        "id": "image-only",
                        "refreshable": false,
                        "body": [
                          {
                            "type": "row",
                            "children": [
                              { "type": "image", "url": "https://cdn.example.com/only.png" }
                            ]
                          }
                        ]
                      }
                    ]
                  }]
                }
                """.trimIndent().replace(
                    "\"https://cdn.example.com/ spark.png\".replace(\" \", \"\")",
                    "\"https://cdn.example.com/spark.png\"",
                ),
            )

        val itemResult = parseAndValidateTimelineProcessBatchResponse(raw, setOf("i1")).getValue("i1")
        assertEquals(WidgetCachePolicyV1.MemoryOnly, itemResult.cachePolicy)
        assertEquals(604_800L, itemResult.ttlSeconds)
        assertEquals(2, itemResult.cards.size)

        // Verify JSON serialization round-trip for SQLite cardsJson storage
        val serializer = ListSerializer(CachedWidgetCardPayloadV1.serializer())
        val encodedJson = PluginJsonV1.encodeToString(serializer, itemResult.cards)
        val decodedCards = PluginJsonV1.decodeFromString(serializer, encodedJson)
        assertEquals(itemResult.cards, decodedCards)

        val toggledIds = mutableListOf<String>()
        var refreshed = false
        val uiWithMedia =
            decodedCards[0].toUiCard(
                pluginId = "org.example.plugin",
                pluginName = "Fallback Plugin Name",
                pluginIconPath = "/tmp/icon.png",
                allowMediaNetwork = true,
                isRefreshing = true,
                expandedCollapsibles = emptySet(),
                onToggleCollapsible = { toggledIds += it },
                onRefresh = { refreshed = true },
            )
        assertNotNull(uiWithMedia)
        assertEquals("Fallback Plugin Name", uiWithMedia.title) // blank title fell back to pluginName
        assertEquals(UiWidgetTone.Accent, uiWithMedia.accentTone)
        assertTrue(uiWithMedia.refreshable) // defaulted to (ttlSeconds != null)
        assertTrue(uiWithMedia.isRefreshing)
        assertEquals(UiWidgetAction.Copy("copied-card", "Card"), uiWithMedia.cardAction)
        assertEquals(1, uiWithMedia.actions.size)
        assertEquals(UiWidgetButtonStyle.Primary, uiWithMedia.actions[0].style)
        assertEquals(UiWidgetAction.Refresh, uiWithMedia.actions[0].action)
        uiWithMedia.onRefresh?.invoke()
        assertTrue(refreshed)

        val row = assertIs<UiWidgetNode.Row>(uiWithMedia.body[0])
        assertEquals(UiWidgetGap.Xs, row.gap)
        assertEquals(UiWidgetAlign.SpaceBetween, row.align)
        val stat = assertIs<UiWidgetNode.Stat>(row.children[0])
        assertEquals(1.5f, stat.weight)
        val spark = assertIs<UiWidgetNode.Sparkline>(row.children[1])
        assertEquals(UiWidgetSparklineHeight.Md, spark.height)
        assertIs<UiWidgetNode.Divider>(uiWithMedia.body[1])

        val col = assertIs<UiWidgetNode.Column>(uiWithMedia.body[2])
        val codeText = assertIs<UiWidgetNode.Text>(col.children[0])
        assertEquals(UiWidgetTextStyle.Code, codeText.style)
        assertEquals(4, codeText.maxLines)
        assertTrue(codeText.copyable)
        val kv = assertIs<UiWidgetNode.KeyValue>(col.children[2])
        assertIs<UiWidgetAction.OpenUrl>(kv.items[0].action)
        assertNull(kv.items[1].action)
        val img = assertIs<UiWidgetNode.Image>(col.children[3])
        assertEquals(UiWidgetCornerStyle.Circle, img.cornerStyle)

        val collapsedByDefault = assertIs<UiWidgetNode.Collapsible>(uiWithMedia.body[3])
        val openByDefault = assertIs<UiWidgetNode.Collapsible>(uiWithMedia.body[4])
        assertFalse(collapsedByDefault.expanded)
        assertTrue(openByDefault.expanded)
        collapsedByDefault.onToggle?.invoke()
        openByDefault.onToggle?.invoke()
        assertEquals(listOf("details-collapsed", "details-open"), toggledIds)

        // Toggling flips both defaultExpanded=false -> true and defaultExpanded=true -> false
        val uiAfterToggle =
            decodedCards[0].toUiCard(
                pluginId = "org.example.plugin",
                pluginName = "Fallback Plugin Name",
                pluginIconPath = null,
                allowMediaNetwork = true,
                isRefreshing = false,
                expandedCollapsibles = setOf("details-collapsed", "details-open"),
                onToggleCollapsible = {},
                onRefresh = null,
            )!!
        assertTrue((uiAfterToggle.body[3] as UiWidgetNode.Collapsible).expanded)
        assertFalse((uiAfterToggle.body[4] as UiWidgetNode.Collapsible).expanded)

        // Second card has only an image inside a row -> returns null when allowMediaNetwork == false, non-null when true
        assertNotNull(
            decodedCards[1].toUiCard(
                pluginId = "p1",
                pluginName = "P1",
                pluginIconPath = null,
                allowMediaNetwork = true,
                isRefreshing = false,
                expandedCollapsibles = emptySet(),
                onToggleCollapsible = {},
                onRefresh = null,
            ),
        )
        assertNull(
            decodedCards[1].toUiCard(
                pluginId = "p1",
                pluginName = "P1",
                pluginIconPath = null,
                allowMediaNetwork = false,
                isRefreshing = false,
                expandedCollapsibles = emptySet(),
                onToggleCollapsible = {},
                onRefresh = null,
            ),
        )
    }

    @Test
    fun additiveCompatibilityIgnoresUnknownItemIdsDefaultsUnknownEnumsAndDropsEmptyContainers() {
        val raw =
            PluginJsonV1.parseToJsonElement(
                """
                {
                  "futureTopLevel": true,
                  "results": [
                    {
                      "itemId": "unrequested-item",
                      "cards": []
                    },
                    {
                      "itemId": "i1",
                      "cards": [{
                        "id": "c1",
                        "accentTone": "neonFuture",
                        "cardAction": { "type": "walletSignV3", "chainId": 1 },
                        "body": [
                          {
                            "type": "row",
                            "gap": "xxl",
                            "align": "baseline",
                            "children": [
                              {
                                "type": "collapsible",
                                "id": "col-empty",
                                "summary": "Future section",
                                "children": [{ "type": "chart3dV2", "mesh": [] }]
                              }
                            ]
                          },
                          {
                            "type": "text",
                            "text": "Fallback styles work",
                            "style": "marquee",
                            "tone": "ultraviolet"
                          },
                          {
                            "type": "sparkline",
                            "points": [1, 2],
                            "height": "xl"
                          },
                          {
                            "type": "image",
                            "url": "https://cdn.example.com/pic.png",
                            "cornerStyle": "hexagon"
                          }
                        ],
                        "actions": [
                          { "label": "Copy", "style": "ghost", "tone": "cyber", "action": { "type": "copy", "text": "ok" } }
                        ]
                      }]
                    }
                  ]
                }
                """.trimIndent(),
            )

        val results = parseAndValidateTimelineProcessBatchResponse(raw, setOf("i1"))
        assertEquals(setOf("i1"), results.keys)
        val item = results.getValue("i1")
        assertEquals(WidgetCachePolicyV1.Disk, item.cachePolicy)
        assertNull(item.ttlSeconds)

        val ui =
            item.cards.single().toUiCard(
                pluginId = "p1",
                pluginName = "Plugin",
                pluginIconPath = null,
                allowMediaNetwork = true,
                isRefreshing = false,
                expandedCollapsibles = emptySet(),
                onToggleCollapsible = {},
                onRefresh = null,
            )!!
        assertFalse(ui.refreshable)
        assertNull(ui.cardAction)
        assertEquals(UiWidgetTone.Neutral, ui.accentTone)
        // Nested row -> collapsible -> unknown child collapsed and omitted entirely!
        assertEquals(3, ui.body.size)
        val text = assertIs<UiWidgetNode.Text>(ui.body[0])
        assertEquals(UiWidgetTextStyle.Body, text.style)
        assertEquals(UiWidgetTone.Neutral, text.tone)
        val spark = assertIs<UiWidgetNode.Sparkline>(ui.body[1])
        assertEquals(UiWidgetSparklineHeight.Sm, spark.height)
        val image = assertIs<UiWidgetNode.Image>(ui.body[2])
        assertEquals(UiWidgetCornerStyle.Rounded, image.cornerStyle)
        assertEquals(UiWidgetButtonStyle.Secondary, ui.actions.single().style)
        assertEquals(UiWidgetTone.Neutral, ui.actions.single().tone)
    }

    @Test
    fun enforcesDepthNodeCardButtonAndCacheLimitsBeforeAdditiveFiltering() {
        // Depth 4 succeeds: column (1) -> row (2) -> collapsible (3) -> text (4)
        val validDepth4 =
            PluginJsonV1.parseToJsonElement(
                """
                {
                  "results": [{
                    "itemId": "i1",
                    "cards": [{
                      "id": "d4",
                      "body": [{
                        "type": "column",
                        "children": [{
                          "type": "row",
                          "children": [{
                            "type": "collapsible",
                            "id": "c",
                            "summary": "S",
                            "children": [{ "type": "text", "text": "Leaf at depth 4" }]
                          }]
                        }]
                      }]
                    }]
                  }]
                }
                """.trimIndent(),
            )
        assertEquals(1, parseAndValidateTimelineProcessBatchResponse(validDepth4, setOf("i1")).getValue("i1").cards.size)

        // Depth 5 fails even if leaf is an unknown future node type
        val invalidDepth5 =
            PluginJsonV1.parseToJsonElement(
                """
                {
                  "results": [{
                    "itemId": "i1",
                    "cards": [{
                      "id": "d5",
                      "body": [{
                        "type": "column",
                        "children": [{
                          "type": "row",
                          "children": [{
                            "type": "collapsible",
                            "id": "c",
                            "summary": "S",
                            "children": [{
                              "type": "row",
                              "children": [{ "type": "futureLeafV2" }]
                            }]
                          }]
                        }]
                      }]
                    }]
                  }]
                }
                """.trimIndent(),
            )
        assertFailsWith<IllegalArgumentException> {
            parseAndValidateTimelineProcessBatchResponse(invalidDepth5, setOf("i1"))
        }

        // 33 nodes fail even when 32 of them are unknown future nodes
        val nodes33 = (listOf("""{"type":"text","text":"ok"}""") + List(32) { """{"type":"futureV2"}""" }).joinToString(",")
        assertFailsWith<IllegalArgumentException> {
            parseAndValidateTimelineProcessBatchResponse(
                PluginJsonV1.parseToJsonElement("""{"results":[{"itemId":"i1","cards":[{"id":"c1","body":[$nodes33]}]}]}"""),
                setOf("i1"),
            )
        }

        // > 4 cards fail even when cards have unknown body nodes
        val cards5 = (1..5).joinToString(",") { """{"id":"c$it","body":[{"type":"futureV2"}]}""" }
        assertFailsWith<IllegalArgumentException> {
            parseAndValidateTimelineProcessBatchResponse(
                PluginJsonV1.parseToJsonElement("""{"results":[{"itemId":"i1","cards":[$cards5]}]}"""),
                setOf("i1"),
            )
        }

        // > 3 action buttons fail even when 4th button has unknown action type
        val actions4 = List(4) { """{"label":"B$it","action":{"type":"futureAction"}}""" }.joinToString(",")
        assertFailsWith<IllegalArgumentException> {
            parseAndValidateTimelineProcessBatchResponse(
                PluginJsonV1.parseToJsonElement(
                    """{"results":[{"itemId":"i1","cards":[{"id":"c1","body":[{"type":"divider"}],"actions":[$actions4]}]}]}""",
                ),
                setOf("i1"),
            )
        }

        // Duplicate itemId, duplicate card id, unknown cache policy, negative/fractional ttlSeconds
        assertFailsWith<IllegalArgumentException> {
            parseAndValidateTimelineProcessBatchResponse(
                PluginJsonV1.parseToJsonElement("""{"results":[{"itemId":"i1","cards":[]},{"itemId":"i1","cards":[]}]}"""),
                setOf("i1"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseAndValidateTimelineProcessBatchResponse(
                PluginJsonV1.parseToJsonElement(
                    """{"results":[{"itemId":"i1","cards":[{"id":"dup","body":[{"type":"divider"}]},{"id":"dup","body":[{"type":"divider"}]}]}]}""",
                ),
                setOf("i1"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseAndValidateTimelineProcessBatchResponse(
                PluginJsonV1.parseToJsonElement("""{"results":[{"itemId":"i1","cache":{"policy":"cloud"},"cards":[]}]}"""),
                setOf("i1"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseAndValidateTimelineProcessBatchResponse(
                PluginJsonV1.parseToJsonElement("""{"results":[{"itemId":"i1","cache":{"ttlSeconds":-5},"cards":[]}]}"""),
                setOf("i1"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseAndValidateTimelineProcessBatchResponse(
                PluginJsonV1.parseToJsonElement("""{"results":[{"itemId":"i1","cache":{"ttlSeconds":15.5},"cards":[]}]}"""),
                setOf("i1"),
            )
        }
    }

    @Test
    fun rejectsInsecureUrlsOversizedStringsInvalidBoundsAndMalformedDiscriminators() {
        fun assertCardRejected(cardFieldsJson: String) {
            val element = PluginJsonV1.parseToJsonElement("""{"results":[{"itemId":"i1","cards":[$cardFieldsJson]}]}""")
            assertFailsWith<IllegalArgumentException>(cardFieldsJson) {
                parseAndValidateTimelineProcessBatchResponse(element, setOf("i1"))
            }
        }

        val long257 = "a".repeat(257)
        val long2049 = "a".repeat(2049)
        val long4097 = "a".repeat(4097)

        // Empty body before filtering
        assertCardRejected("""{"id":"c1","body":[]}""")
        // Missing / non-string node type
        assertCardRejected("""{"id":"c1","body":[{}]}""")
        assertCardRejected("""{"id":"c1","body":[{"type":123}]}""")
        // URL security: http, credentials, > 2048 chars
        assertCardRejected("""{"id":"c1","body":[{"type":"image","url":"http://cdn.example.com/a.png"}]}""")
        assertCardRejected("""{"id":"c1","body":[{"type":"image","url":"https://user:pass@cdn.example.com/a.png"}]}""")
        assertCardRejected("""{"id":"c1","body":[{"type":"image","url":"https://@cdn.example.com/a.png"}]}""")
        assertCardRejected("""{"id":"c1","body":[{"type":"image","url":"https://cdn.example.com/$long2049"}]}""")
        assertCardRejected(
            """{"id":"c1","body":[{"type":"matchup","left":{"name":"A","iconUrl":"http://cdn.example.com/a.png"},"right":{"name":"B"}}]}""",
        )
        assertCardRejected(
            """{"id":"c1","cardAction":{"type":"openUrl","url":"javascript:alert(1)"},"body":[{"type":"divider"}]}""",
        )
        // Copy payload > 2048 or empty
        assertCardRejected("""{"id":"c1","cardAction":{"type":"copy","text":""},"body":[{"type":"divider"}]}""")
        assertCardRejected("""{"id":"c1","cardAction":{"type":"copy","text":"$long2049"},"body":[{"type":"divider"}]}""")
        // String caps
        assertCardRejected("""{"id":"${"x".repeat(65)}","body":[{"type":"divider"}]}""")
        assertCardRejected("""{"id":"c1","title":"$long257","body":[{"type":"divider"}]}""")
        assertCardRejected("""{"id":"c1","body":[{"type":"text","text":""}]}""")
        assertCardRejected("""{"id":"c1","body":[{"type":"text","text":"$long4097"}]}""")
        assertCardRejected("""{"id":"c1","body":[{"type":"stat","label":"$long257","value":"1"}]}""")
        // Numeric & collection bounds
        assertCardRejected("""{"id":"c1","body":[{"type":"sparkline","points":[1.0]}]}""")
        assertCardRejected("""{"id":"c1","body":[{"type":"segmentedBar","segments":[{"label":"A","weight":0}]}]}""")
        assertCardRejected("""{"id":"c1","body":[{"type":"segmentedBar","segments":[{"label":"A","weight":-1}]}]}""")
        assertCardRejected("""{"id":"c1","body":[{"type":"keyValue","items":[]}]}""")
        assertCardRejected("""{"id":"c1","body":[{"type":"badgeRow","badges":[]}]}""")
        assertCardRejected("""{"id":"c1","body":[{"type":"text","text":"ok","maxLines":0}]}""")
        assertCardRejected("""{"id":"c1","body":[{"type":"image","url":"https://cdn.example.com/a.png","aspectRatio":0}]}""")
        assertCardRejected("""{"id":"c1","body":[{"type":"stat","label":"A","value":"1","weight":0}]}""")
    }
}
