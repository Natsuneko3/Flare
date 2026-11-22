package dev.dimension.flare.feature.plugin.wire

import dev.dimension.flare.ui.model.ExtractedPluginPostInput
import dev.dimension.flare.ui.model.UiPluginWidgetCard
import dev.dimension.flare.ui.model.UiWidgetAction
import dev.dimension.flare.ui.model.UiWidgetAlign
import dev.dimension.flare.ui.model.UiWidgetBadge
import dev.dimension.flare.ui.model.UiWidgetButton
import dev.dimension.flare.ui.model.UiWidgetButtonStyle
import dev.dimension.flare.ui.model.UiWidgetCornerStyle
import dev.dimension.flare.ui.model.UiWidgetGap
import dev.dimension.flare.ui.model.UiWidgetNode
import dev.dimension.flare.ui.model.UiWidgetSparklineHeight
import dev.dimension.flare.ui.model.UiWidgetTextStyle
import dev.dimension.flare.ui.model.UiWidgetTone
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

public object WireTimelineProcessorLimitsV1 {
    public const val MAX_BATCH_SIZE: Int = 50
    public const val MAX_CARDS_PER_ITEM: Int = 4
    public const val MAX_DISPLAYED_CARDS_PER_POST: Int = 8
    public const val MAX_TREE_DEPTH: Int = 4
    public const val MAX_NODES_PER_CARD: Int = 32
    public const val MAX_CARD_ACTIONS: Int = 3
    public const val MIN_SPARKLINE_POINTS: Int = 2
    public const val MAX_SPARKLINE_POINTS: Int = 120
    public const val MAX_BAR_SEGMENTS: Int = 4
    public const val MAX_KEY_VALUE_ITEMS: Int = 8
    public const val MAX_BADGES: Int = 8
    public const val MAX_CARD_ID_LENGTH: Int = 64
    public const val MAX_TYPE_DISCRIMINATOR_LENGTH: Int = 128
    public const val MAX_TEXT_LENGTH: Int = 4_096
    public const val MAX_LABEL_LENGTH: Int = 256
    public const val MAX_URL_OR_COPY_LENGTH: Int = 2_048
    public const val MIN_TTL_SECONDS: Long = 15L
    public const val MAX_TTL_SECONDS: Long = 604_800L
}

@Serializable
public enum class TimelineProcessTriggerV1 {
    @SerialName("auto")
    Auto,

    @SerialName("manualRefresh")
    ManualRefresh,
}

@Serializable
public enum class WidgetCachePolicyV1 {
    @SerialName("disk")
    Disk,

    @SerialName("memoryOnly")
    MemoryOnly,

    @SerialName("none")
    None,
}

public data class ValidatedPluginItemCardsV1(
    public val itemId: String,
    public val cards: List<CachedWidgetCardPayloadV1>,
    public val cachePolicy: WidgetCachePolicyV1,
    public val ttlSeconds: Long?,
)

@Serializable
public data class CachedWidgetCardPayloadV1(
    public val id: String,
    public val title: String? = null,
    public val badge: UiWidgetBadge? = null,
    public val accentTone: UiWidgetTone = UiWidgetTone.Neutral,
    public val refreshable: Boolean = false,
    public val cardAction: UiWidgetAction? = null,
    public val body: List<CachedWidgetNodeV1>,
    public val actions: List<UiWidgetButton> = emptyList(),
)

@Serializable
public sealed class CachedWidgetNodeV1 {
    @Serializable
    @SerialName("text")
    public data class Text(
        public val text: String,
        public val style: UiWidgetTextStyle = UiWidgetTextStyle.Body,
        public val tone: UiWidgetTone = UiWidgetTone.Neutral,
        public val maxLines: Int? = null,
        public val copyable: Boolean = false,
    ) : CachedWidgetNodeV1()

    @Serializable
    @SerialName("row")
    public data class Row(
        public val children: List<CachedWidgetNodeV1>,
        public val gap: UiWidgetGap = UiWidgetGap.Sm,
        public val align: UiWidgetAlign = UiWidgetAlign.Start,
        public val weight: Float? = null,
    ) : CachedWidgetNodeV1()

    @Serializable
    @SerialName("column")
    public data class Column(
        public val children: List<CachedWidgetNodeV1>,
        public val gap: UiWidgetGap = UiWidgetGap.Sm,
        public val align: UiWidgetAlign = UiWidgetAlign.Start,
        public val weight: Float? = null,
    ) : CachedWidgetNodeV1()

    @Serializable
    @SerialName("stat")
    public data class Stat(
        public val label: String,
        public val value: String,
        public val delta: String? = null,
        public val deltaTone: UiWidgetTone = UiWidgetTone.Neutral,
        public val subtext: String? = null,
        public val weight: Float? = null,
    ) : CachedWidgetNodeV1()

    @Serializable
    @SerialName("matchup")
    public data class Matchup(
        public val left: UiWidgetNode.MatchupSide,
        public val right: UiWidgetNode.MatchupSide,
        public val status: String? = null,
        public val statusTone: UiWidgetTone = UiWidgetTone.Neutral,
    ) : CachedWidgetNodeV1()

    @Serializable
    @SerialName("sparkline")
    public data class Sparkline(
        public val points: List<Double>,
        public val tone: UiWidgetTone = UiWidgetTone.Neutral,
        public val minLabel: String? = null,
        public val maxLabel: String? = null,
        public val height: UiWidgetSparklineHeight = UiWidgetSparklineHeight.Sm,
        public val weight: Float? = null,
    ) : CachedWidgetNodeV1()

    @Serializable
    @SerialName("segmentedBar")
    public data class SegmentedBar(
        public val segments: List<UiWidgetNode.SegmentedBar.Segment>,
    ) : CachedWidgetNodeV1()

    @Serializable
    @SerialName("keyValue")
    public data class KeyValue(
        public val items: List<UiWidgetNode.KeyValue.Item>,
    ) : CachedWidgetNodeV1()

    @Serializable
    @SerialName("badgeRow")
    public data class BadgeRow(
        public val badges: List<UiWidgetBadge>,
    ) : CachedWidgetNodeV1()

    @Serializable
    @SerialName("image")
    public data class Image(
        public val url: String,
        public val alt: String? = null,
        public val aspectRatio: Float? = null,
        public val cornerStyle: UiWidgetCornerStyle = UiWidgetCornerStyle.Rounded,
        public val weight: Float? = null,
    ) : CachedWidgetNodeV1()

    @Serializable
    @SerialName("collapsible")
    public data class Collapsible(
        public val id: String,
        public val summary: String,
        public val defaultExpanded: Boolean = false,
        public val children: List<CachedWidgetNodeV1>,
    ) : CachedWidgetNodeV1()

    @Serializable
    @SerialName("divider")
    public data object Divider : CachedWidgetNodeV1()
}

public fun encodeTimelineProcessBatchRequest(
    locale: String,
    trigger: TimelineProcessTriggerV1,
    items: List<Pair<String, ExtractedPluginPostInput>>,
): JsonObject {
    require(locale.isNotBlank() && locale.length <= 128) { "Invalid timeline processor locale" }
    require(items.size in 1..WireTimelineProcessorLimitsV1.MAX_BATCH_SIZE) {
        "Timeline processor batch must contain 1..${WireTimelineProcessorLimitsV1.MAX_BATCH_SIZE} items"
    }
    val seenItemIds = HashSet<String>(items.size)
    items.forEach { (itemId, _) ->
        require(itemId.isNotBlank() && itemId.length <= WireLimitsV1.MAX_ID_LENGTH && seenItemIds.add(itemId)) {
            "Invalid or duplicate batch itemId: $itemId"
        }
    }

    val wireTrigger =
        when (trigger) {
            TimelineProcessTriggerV1.Auto -> "auto"
            TimelineProcessTriggerV1.ManualRefresh -> "manualRefresh"
        }

    return buildJsonObject {
        put("locale", locale)
        put("trigger", wireTrigger)
        put(
            "items",
            JsonArray(
                items.map { (itemId, input) ->
                    buildJsonObject {
                        put("itemId", itemId)
                        put("platformId", input.platformId)
                        putJsonObject("postKey") {
                            put("id", input.postKey.id)
                            put("host", input.postKey.host)
                        }
                        put("createdAt", input.createdAtIso)
                        if (input.authorHandle != null || input.authorDisplayName != null) {
                            putJsonObject("author") {
                                put("handle", input.authorHandle.orEmpty())
                                put("displayName", input.authorDisplayName.orEmpty())
                            }
                        } else {
                            put("author", JsonNull)
                        }
                        put("contentPlain", input.contentPlain)
                        put("contentWarningPlain", input.contentWarningPlain?.let(::JsonPrimitive) ?: JsonNull)
                        put("links", JsonArray(input.links.map(::JsonPrimitive)))
                        if (input.cardUrl != null) {
                            putJsonObject("card") {
                                put("url", input.cardUrl)
                                put("title", input.cardTitle?.let(::JsonPrimitive) ?: JsonNull)
                                put("description", input.cardDescription?.let(::JsonPrimitive) ?: JsonNull)
                            }
                        } else {
                            put("card", JsonNull)
                        }
                        put("sourceLanguages", JsonArray(input.sourceLanguages.map(::JsonPrimitive)))
                    }
                },
            ),
        )
    }
}

internal fun JsonObject.splitTimelineProcessBatchRequest(maxBytes: Int): List<Pair<IntRange, JsonObject?>> {
    val items = getValue("items").jsonArray

    fun withItems(slice: List<JsonElement>) = JsonObject(this + ("items" to JsonArray(slice)))

    val available = maxBytes - withItems(emptyList()).toString().encodeToByteArray().size
    val sizes = items.map { it.toString().encodeToByteArray().size }
    return buildList {
        var start = 0
        while (start < items.size) {
            var end = start
            var remaining = available
            while (end < items.size && sizes[end] <= remaining) {
                remaining -= sizes[end] + 1 // Reserve the JSON comma before the next item.
                end++
            }
            if (end == start) {
                add(start..start to null)
                start++
            } else {
                val request = if (end - start == items.size) this@splitTimelineProcessBatchRequest else withItems(items.subList(start, end))
                add(start until end to request)
                start = end
            }
        }
    }
}

public fun parseAndValidateTimelineProcessBatchResponse(
    element: JsonElement,
    validItemIds: Set<String>,
): Map<String, ValidatedPluginItemCardsV1> {
    val root = element.requireJsonObject("TimelineProcessBatchResponseV1")
    val resultsArray = root.requireArrayField("results")
    require(resultsArray.size <= maxOf(validItemIds.size + WireTimelineProcessorLimitsV1.MAX_BATCH_SIZE, WireLimitsV1.MAX_PAGE_ITEMS)) {
        "Too many results in timeline processor response"
    }

    val seenItemIds = HashSet<String>(resultsArray.size)
    val validatedByItemId = LinkedHashMap<String, ValidatedPluginItemCardsV1>()

    for (resultElement in resultsArray) {
        val resultObj = resultElement.requireJsonObject("TimelineProcessItemResultV1")
        val itemId = resultObj.requireNonBlankString("itemId", WireLimitsV1.MAX_ID_LENGTH)
        require(seenItemIds.add(itemId)) { "Duplicate itemId in timeline processor results: $itemId" }
        if (itemId !in validItemIds) {
            continue
        }

        val (cachePolicy, ttlSeconds) = parseCacheOptions(resultObj["cache"])
        val cardsArray = resultObj.requireArrayField("cards")
        require(cardsArray.size <= WireTimelineProcessorLimitsV1.MAX_CARDS_PER_ITEM) {
            "At most ${WireTimelineProcessorLimitsV1.MAX_CARDS_PER_ITEM} widget cards are allowed per item"
        }

        val seenCardIds = HashSet<String>(cardsArray.size)
        val parsedCards = ArrayList<CachedWidgetCardPayloadV1>(cardsArray.size)
        for (cardElement in cardsArray) {
            val card = parseCard(cardElement, seenCardIds, defaultRefreshable = ttlSeconds != null)
            if (card != null) {
                parsedCards.add(card)
            }
        }

        validatedByItemId[itemId] =
            ValidatedPluginItemCardsV1(
                itemId = itemId,
                cards = parsedCards,
                cachePolicy = cachePolicy,
                ttlSeconds = ttlSeconds,
            )
    }

    return validatedByItemId
}

/**
 * Maps a validated [CachedWidgetCardPayloadV1] into a [UiPluginWidgetCard] bound to live host permissions and state.
 *
 * Note on [expandedCollapsibles]: treats [expandedCollapsibles] as the set of toggled collapsible IDs for this card,
 * computing `expanded = (collapsible.id in expandedCollapsibles) xor collapsible.defaultExpanded`. Passing [emptySet]
 * preserves each node's `defaultExpanded` flag (`false` -> collapsed, `true` -> expanded), and toggling membership of
 * `collapsible.id` in the set inverts its current state in both cases.
 *
 * Returns `null` if stripping media nodes when `allowMediaNetwork == false` leaves the card `body` empty.
 */
public fun CachedWidgetCardPayloadV1.toUiCard(
    pluginId: String,
    pluginName: String,
    pluginIconPath: String?,
    allowMediaNetwork: Boolean,
    isRefreshing: Boolean,
    expandedCollapsibles: Set<String>,
    onToggleCollapsible: (String) -> Unit,
    onRefresh: (() -> Unit)?,
): UiPluginWidgetCard? {
    val uiBody =
        body.mapNotNull { node ->
            node.toUiNode(
                allowMediaNetwork = allowMediaNetwork,
                expandedCollapsibles = expandedCollapsibles,
                onToggleCollapsible = onToggleCollapsible,
            )
        }
    if (uiBody.isEmpty()) {
        return null
    }

    return UiPluginWidgetCard(
        pluginId = pluginId,
        pluginName = pluginName,
        pluginIconPath = pluginIconPath,
        id = id,
        title = title?.takeIf { it.isNotBlank() } ?: pluginName,
        badge = badge,
        accentTone = accentTone,
        refreshable = refreshable,
        isRefreshing = isRefreshing,
        cardAction = cardAction,
        body = uiBody.toImmutableList(),
        actions = actions.toImmutableList(),
        onRefresh = onRefresh,
    )
}

public fun requireValidHttpsUrl(url: String): String {
    require(url.isNotBlank() && url.length <= WireTimelineProcessorLimitsV1.MAX_URL_OR_COPY_LENGTH) {
        "URL must be 1..${WireTimelineProcessorLimitsV1.MAX_URL_OR_COPY_LENGTH} characters"
    }
    require(url.startsWith("https://", ignoreCase = true) && url.none(Char::isISOControl)) {
        "URL must be absolute HTTPS"
    }
    val authority =
        url
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
    require(authority.isNotBlank() && '@' !in authority && authority.none(Char::isWhitespace)) {
        "URL must have a valid host without credentials"
    }
    val parsed =
        runCatching { Url(url) }.getOrElse { cause ->
            throw IllegalArgumentException("Invalid HTTPS URL", cause)
        }
    require(
        parsed.protocol == URLProtocol.HTTPS &&
            parsed.host.isNotBlank() &&
            parsed.encodedUser == null &&
            parsed.encodedPassword == null &&
            parsed.user == null &&
            parsed.password == null,
    ) {
        "URL must use HTTPS without credentials"
    }
    return url
}

private fun CachedWidgetNodeV1.toUiNode(
    allowMediaNetwork: Boolean,
    expandedCollapsibles: Set<String>,
    onToggleCollapsible: (String) -> Unit,
): UiWidgetNode? =
    when (this) {
        is CachedWidgetNodeV1.Text -> {
            UiWidgetNode.Text(
                text = text,
                style = style,
                tone = tone,
                maxLines = maxLines,
                copyable = copyable,
            )
        }

        is CachedWidgetNodeV1.Row -> {
            val mappedChildren =
                children.mapNotNull { child ->
                    child.toUiNode(
                        allowMediaNetwork = allowMediaNetwork,
                        expandedCollapsibles = expandedCollapsibles,
                        onToggleCollapsible = onToggleCollapsible,
                    )
                }
            if (mappedChildren.isEmpty()) {
                null
            } else {
                UiWidgetNode.Row(
                    children = mappedChildren.toImmutableList(),
                    gap = gap,
                    align = align,
                    weight = weight,
                )
            }
        }

        is CachedWidgetNodeV1.Column -> {
            val mappedChildren =
                children.mapNotNull { child ->
                    child.toUiNode(
                        allowMediaNetwork = allowMediaNetwork,
                        expandedCollapsibles = expandedCollapsibles,
                        onToggleCollapsible = onToggleCollapsible,
                    )
                }
            if (mappedChildren.isEmpty()) {
                null
            } else {
                UiWidgetNode.Column(
                    children = mappedChildren.toImmutableList(),
                    gap = gap,
                    align = align,
                    weight = weight,
                )
            }
        }

        is CachedWidgetNodeV1.Stat -> {
            UiWidgetNode.Stat(
                label = label,
                value = value,
                delta = delta,
                deltaTone = deltaTone,
                subtext = subtext,
                weight = weight,
            )
        }

        is CachedWidgetNodeV1.Matchup -> {
            val safeLeft = if (allowMediaNetwork || left.iconUrl == null) left else left.copy(iconUrl = null)
            val safeRight = if (allowMediaNetwork || right.iconUrl == null) right else right.copy(iconUrl = null)
            UiWidgetNode.Matchup(
                left = safeLeft,
                right = safeRight,
                status = status,
                statusTone = statusTone,
            )
        }

        is CachedWidgetNodeV1.Sparkline -> {
            UiWidgetNode.Sparkline(
                points = points.toImmutableList(),
                tone = tone,
                minLabel = minLabel,
                maxLabel = maxLabel,
                height = height,
                weight = weight,
            )
        }

        is CachedWidgetNodeV1.SegmentedBar -> {
            UiWidgetNode.SegmentedBar(
                segments = segments.toImmutableList(),
            )
        }

        is CachedWidgetNodeV1.KeyValue -> {
            UiWidgetNode.KeyValue(
                items = items.toImmutableList(),
            )
        }

        is CachedWidgetNodeV1.BadgeRow -> {
            UiWidgetNode.BadgeRow(
                badges = badges.toImmutableList(),
            )
        }

        is CachedWidgetNodeV1.Image -> {
            if (!allowMediaNetwork) {
                null
            } else {
                UiWidgetNode.Image(
                    url = url,
                    alt = alt,
                    aspectRatio = aspectRatio,
                    cornerStyle = cornerStyle,
                    weight = weight,
                )
            }
        }

        is CachedWidgetNodeV1.Collapsible -> {
            val mappedChildren =
                children.mapNotNull { child ->
                    child.toUiNode(
                        allowMediaNetwork = allowMediaNetwork,
                        expandedCollapsibles = expandedCollapsibles,
                        onToggleCollapsible = onToggleCollapsible,
                    )
                }
            if (mappedChildren.isEmpty()) {
                null
            } else {
                val collapsibleId = id
                UiWidgetNode.Collapsible(
                    id = collapsibleId,
                    summary = summary,
                    children = mappedChildren.toImmutableList(),
                    expanded = (collapsibleId in expandedCollapsibles) xor defaultExpanded,
                    onToggle = { onToggleCollapsible(collapsibleId) },
                )
            }
        }

        CachedWidgetNodeV1.Divider -> {
            UiWidgetNode.Divider
        }
    }

private fun parseCacheOptions(element: JsonElement?): Pair<WidgetCachePolicyV1, Long?> {
    if (element == null || element is JsonNull) {
        return WidgetCachePolicyV1.Disk to null
    }
    val obj = element.requireJsonObject("cache")
    val policy =
        when (val policyStr = obj.optionalString("policy", WireTimelineProcessorLimitsV1.MAX_TYPE_DISCRIMINATOR_LENGTH)) {
            null, "disk" -> WidgetCachePolicyV1.Disk
            "memoryOnly" -> WidgetCachePolicyV1.MemoryOnly
            "none" -> WidgetCachePolicyV1.None
            else -> throw IllegalArgumentException("Unknown widget cache policy: $policyStr")
        }
    val ttlSeconds =
        obj["ttlSeconds"]?.let { raw ->
            if (raw is JsonNull) {
                null
            } else {
                val value = raw.requireFiniteDouble("cache.ttlSeconds")
                require(value >= 0.0 && value % 1.0 == 0.0) { "cache.ttlSeconds must be a non-negative integer" }
                value
                    .coerceIn(
                        WireTimelineProcessorLimitsV1.MIN_TTL_SECONDS.toDouble(),
                        WireTimelineProcessorLimitsV1.MAX_TTL_SECONDS.toDouble(),
                    ).toLong()
            }
        }
    return policy to ttlSeconds
}

private class CardValidationBudget {
    var totalNodes: Int = 0
    val collapsibleIds: HashSet<String> = HashSet()
}

private fun parseCard(
    element: JsonElement,
    seenCardIds: MutableSet<String>,
    defaultRefreshable: Boolean,
): CachedWidgetCardPayloadV1? {
    val cardObj = element.requireJsonObject("PluginWidgetCardV1")
    val id = cardObj.requireNonBlankString("id", WireTimelineProcessorLimitsV1.MAX_CARD_ID_LENGTH)
    require(seenCardIds.add(id)) { "Duplicate widget card id: $id" }

    val title = cardObj.optionalString("title", WireTimelineProcessorLimitsV1.MAX_LABEL_LENGTH)?.takeIf { it.isNotBlank() }
    val badge = cardObj.optionalObjectField("badge")?.let(::parseBadge)
    val accentTone = parseTone(cardObj["accentTone"])
    val refreshable = cardObj.optionalBoolean("refreshable") ?: defaultRefreshable
    val cardAction = cardObj.optionalObjectField("cardAction")?.let(::parseAction)

    val actionsArray = cardObj.optionalArrayField("actions")
    val actions =
        if (actionsArray == null) {
            emptyList()
        } else {
            require(actionsArray.size <= WireTimelineProcessorLimitsV1.MAX_CARD_ACTIONS) {
                "At most ${WireTimelineProcessorLimitsV1.MAX_CARD_ACTIONS} card actions are allowed"
            }
            actionsArray.mapNotNull(::parseButton)
        }

    val bodyArray = cardObj.requireArrayField("body")
    require(bodyArray.size in 1..WireTimelineProcessorLimitsV1.MAX_NODES_PER_CARD) {
        "Card body must contain 1..${WireTimelineProcessorLimitsV1.MAX_NODES_PER_CARD} nodes"
    }

    val budget = CardValidationBudget()
    val filteredBody =
        bodyArray.mapNotNull { nodeElement ->
            parseNode(nodeElement, depth = 1, budget = budget)
        }
    if (filteredBody.isEmpty()) {
        return null
    }

    return CachedWidgetCardPayloadV1(
        id = id,
        title = title,
        badge = badge,
        accentTone = accentTone,
        refreshable = refreshable,
        cardAction = cardAction,
        body = filteredBody,
        actions = actions,
    )
}

private fun parseNode(
    element: JsonElement,
    depth: Int,
    budget: CardValidationBudget,
): CachedWidgetNodeV1? {
    require(depth <= WireTimelineProcessorLimitsV1.MAX_TREE_DEPTH) {
        "Widget node tree exceeds max depth of ${WireTimelineProcessorLimitsV1.MAX_TREE_DEPTH}"
    }
    budget.totalNodes += 1
    require(budget.totalNodes <= WireTimelineProcessorLimitsV1.MAX_NODES_PER_CARD) {
        "Widget card exceeds max node count of ${WireTimelineProcessorLimitsV1.MAX_NODES_PER_CARD}"
    }

    val obj = element.requireJsonObject("WidgetNodeV1")
    val type = obj.requireNonBlankString("type", WireTimelineProcessorLimitsV1.MAX_TYPE_DISCRIMINATOR_LENGTH)

    return when (type) {
        "text" -> {
            val text = obj.requireNonBlankString("text", WireTimelineProcessorLimitsV1.MAX_TEXT_LENGTH)
            val style = parseTextStyle(obj["style"])
            val tone = parseTone(obj["tone"])
            val maxLines = obj.optionalPositiveInt("maxLines")
            val copyable = obj.optionalBoolean("copyable") ?: false
            CachedWidgetNodeV1.Text(
                text = text,
                style = style,
                tone = tone,
                maxLines = maxLines,
                copyable = copyable,
            )
        }

        "row" -> {
            val gap = parseGap(obj["gap"])
            val align = parseAlign(obj["align"])
            val weight = obj.optionalPositiveFloat("weight")
            val children = parseContainerChildren(obj, depth = depth, budget = budget)
            if (children.isEmpty()) {
                null
            } else {
                CachedWidgetNodeV1.Row(
                    children = children,
                    gap = gap,
                    align = align,
                    weight = weight,
                )
            }
        }

        "column" -> {
            val gap = parseGap(obj["gap"])
            val align = parseAlign(obj["align"])
            val weight = obj.optionalPositiveFloat("weight")
            val children = parseContainerChildren(obj, depth = depth, budget = budget)
            if (children.isEmpty()) {
                null
            } else {
                CachedWidgetNodeV1.Column(
                    children = children,
                    gap = gap,
                    align = align,
                    weight = weight,
                )
            }
        }

        "stat" -> {
            val label = obj.requireNonBlankString("label", WireTimelineProcessorLimitsV1.MAX_LABEL_LENGTH)
            val value = obj.requireNonBlankString("value", WireTimelineProcessorLimitsV1.MAX_LABEL_LENGTH)
            val delta = obj.optionalNonBlankOrNullString("delta", WireTimelineProcessorLimitsV1.MAX_LABEL_LENGTH)
            val deltaTone = parseTone(obj["deltaTone"])
            val subtext = obj.optionalNonBlankOrNullString("subtext", WireTimelineProcessorLimitsV1.MAX_LABEL_LENGTH)
            val weight = obj.optionalPositiveFloat("weight")
            CachedWidgetNodeV1.Stat(
                label = label,
                value = value,
                delta = delta,
                deltaTone = deltaTone,
                subtext = subtext,
                weight = weight,
            )
        }

        "matchup" -> {
            val left = parseMatchupSide(obj.requireObjectField("left"))
            val right = parseMatchupSide(obj.requireObjectField("right"))
            val status = obj.optionalNonBlankOrNullString("status", WireTimelineProcessorLimitsV1.MAX_LABEL_LENGTH)
            val statusTone = parseTone(obj["statusTone"])
            CachedWidgetNodeV1.Matchup(
                left = left,
                right = right,
                status = status,
                statusTone = statusTone,
            )
        }

        "sparkline" -> {
            val pointsArray = obj.requireArrayField("points")
            require(
                pointsArray.size in
                    WireTimelineProcessorLimitsV1.MIN_SPARKLINE_POINTS..WireTimelineProcessorLimitsV1.MAX_SPARKLINE_POINTS,
            ) {
                "Sparkline requires ${WireTimelineProcessorLimitsV1.MIN_SPARKLINE_POINTS}..${WireTimelineProcessorLimitsV1.MAX_SPARKLINE_POINTS} points"
            }
            val points = pointsArray.map { it.requireFiniteDouble("sparkline.points[]") }
            val tone = parseTone(obj["tone"])
            val minLabel = obj.optionalNonBlankOrNullString("minLabel", WireTimelineProcessorLimitsV1.MAX_LABEL_LENGTH)
            val maxLabel = obj.optionalNonBlankOrNullString("maxLabel", WireTimelineProcessorLimitsV1.MAX_LABEL_LENGTH)
            val height = parseSparklineHeight(obj["height"])
            val weight = obj.optionalPositiveFloat("weight")
            CachedWidgetNodeV1.Sparkline(
                points = points,
                tone = tone,
                minLabel = minLabel,
                maxLabel = maxLabel,
                height = height,
                weight = weight,
            )
        }

        "segmentedBar" -> {
            val segmentsArray = obj.requireArrayField("segments")
            require(segmentsArray.size in 1..WireTimelineProcessorLimitsV1.MAX_BAR_SEGMENTS) {
                "SegmentedBar requires 1..${WireTimelineProcessorLimitsV1.MAX_BAR_SEGMENTS} segments"
            }
            val segments =
                segmentsArray.map { segElement ->
                    val segObj = segElement.requireJsonObject("WidgetBarSegmentV1")
                    val label = segObj.requireNonBlankString("label", WireTimelineProcessorLimitsV1.MAX_LABEL_LENGTH)
                    val valueText = segObj.optionalNonBlankOrNullString("valueText", WireTimelineProcessorLimitsV1.MAX_LABEL_LENGTH)
                    val weight = segObj.requirePositiveFiniteDouble("weight")
                    val tone = parseTone(segObj["tone"])
                    UiWidgetNode.SegmentedBar.Segment(
                        label = label,
                        valueText = valueText,
                        weight = weight,
                        tone = tone,
                    )
                }
            require(segments.sumOf { it.weight }.isFinite()) { "SegmentedBar total weight must be finite" }
            CachedWidgetNodeV1.SegmentedBar(segments = segments)
        }

        "keyValue" -> {
            val itemsArray = obj.requireArrayField("items")
            require(itemsArray.size in 1..WireTimelineProcessorLimitsV1.MAX_KEY_VALUE_ITEMS) {
                "KeyValue requires 1..${WireTimelineProcessorLimitsV1.MAX_KEY_VALUE_ITEMS} items"
            }
            val items =
                itemsArray.map { itemElement ->
                    val itemObj = itemElement.requireJsonObject("WidgetKeyValueItemV1")
                    val label = itemObj.requireNonBlankString("label", WireTimelineProcessorLimitsV1.MAX_LABEL_LENGTH)
                    val value = itemObj.requireNonBlankString("value", WireTimelineProcessorLimitsV1.MAX_LABEL_LENGTH)
                    val tone = parseTone(itemObj["tone"])
                    val action = itemObj.optionalObjectField("action")?.let(::parseAction)
                    UiWidgetNode.KeyValue.Item(
                        label = label,
                        value = value,
                        tone = tone,
                        action = action,
                    )
                }
            CachedWidgetNodeV1.KeyValue(items = items)
        }

        "badgeRow" -> {
            val badgesArray = obj.requireArrayField("badges")
            require(badgesArray.size in 1..WireTimelineProcessorLimitsV1.MAX_BADGES) {
                "BadgeRow requires 1..${WireTimelineProcessorLimitsV1.MAX_BADGES} badges"
            }
            val badges = badgesArray.map { parseBadge(it.requireJsonObject("WidgetBadgeV1")) }
            CachedWidgetNodeV1.BadgeRow(badges = badges)
        }

        "image" -> {
            val url = requireValidHttpsUrl(obj.requireNonBlankString("url", WireTimelineProcessorLimitsV1.MAX_URL_OR_COPY_LENGTH))
            val alt = obj.optionalNonBlankOrNullString("alt", WireTimelineProcessorLimitsV1.MAX_LABEL_LENGTH)
            val aspectRatio = obj.optionalPositiveFloat("aspectRatio")
            val cornerStyle = parseCornerStyle(obj["cornerStyle"])
            val weight = obj.optionalPositiveFloat("weight")
            CachedWidgetNodeV1.Image(
                url = url,
                alt = alt,
                aspectRatio = aspectRatio,
                cornerStyle = cornerStyle,
                weight = weight,
            )
        }

        "collapsible" -> {
            val id = obj.requireNonBlankString("id", WireTimelineProcessorLimitsV1.MAX_CARD_ID_LENGTH)
            require(budget.collapsibleIds.add(id)) { "Duplicate collapsible id in widget card: $id" }
            val summary = obj.requireNonBlankString("summary", WireTimelineProcessorLimitsV1.MAX_LABEL_LENGTH)
            val defaultExpanded = obj.optionalBoolean("defaultExpanded") ?: false
            val children = parseContainerChildren(obj, depth = depth, budget = budget)
            if (children.isEmpty()) {
                null
            } else {
                CachedWidgetNodeV1.Collapsible(
                    id = id,
                    summary = summary,
                    defaultExpanded = defaultExpanded,
                    children = children,
                )
            }
        }

        "divider" -> {
            CachedWidgetNodeV1.Divider
        }

        else -> {
            null
        }
    }
}

private fun parseContainerChildren(
    obj: JsonObject,
    depth: Int,
    budget: CardValidationBudget,
): List<CachedWidgetNodeV1> {
    val childrenArray = obj.requireArrayField("children")
    require(childrenArray.size in 1..WireTimelineProcessorLimitsV1.MAX_NODES_PER_CARD) {
        "Container children must contain 1..${WireTimelineProcessorLimitsV1.MAX_NODES_PER_CARD} nodes"
    }
    return childrenArray.mapNotNull { child ->
        parseNode(child, depth = depth + 1, budget = budget)
    }
}

private fun parseMatchupSide(obj: JsonObject): UiWidgetNode.MatchupSide {
    val name = obj.requireNonBlankString("name", WireTimelineProcessorLimitsV1.MAX_LABEL_LENGTH)
    val subtitle = obj.optionalNonBlankOrNullString("subtitle", WireTimelineProcessorLimitsV1.MAX_LABEL_LENGTH)
    val iconUrl =
        obj.optionalString("iconUrl", WireTimelineProcessorLimitsV1.MAX_URL_OR_COPY_LENGTH)?.let { rawUrl ->
            requireValidHttpsUrl(rawUrl)
        }
    val score = obj.optionalNonBlankOrNullString("score", WireTimelineProcessorLimitsV1.MAX_LABEL_LENGTH)
    return UiWidgetNode.MatchupSide(
        name = name,
        subtitle = subtitle,
        iconUrl = iconUrl,
        score = score,
    )
}

private fun parseBadge(obj: JsonObject): UiWidgetBadge {
    val text = obj.requireNonBlankString("text", WireTimelineProcessorLimitsV1.MAX_LABEL_LENGTH)
    val tone = parseTone(obj["tone"])
    return UiWidgetBadge(text = text, tone = tone)
}

private fun parseButton(element: JsonElement): UiWidgetButton? {
    val obj = element.requireJsonObject("WidgetButtonV1")
    val label = obj.requireNonBlankString("label", WireTimelineProcessorLimitsV1.MAX_LABEL_LENGTH)
    val tone = parseTone(obj["tone"])
    val style = parseButtonStyle(obj["style"])
    val actionObj = obj.requireObjectField("action")
    val action = parseAction(actionObj) ?: return null
    return UiWidgetButton(
        label = label,
        tone = tone,
        style = style,
        action = action,
    )
}

private fun parseAction(obj: JsonObject): UiWidgetAction? {
    val type = obj.requireNonBlankString("type", WireTimelineProcessorLimitsV1.MAX_TYPE_DISCRIMINATOR_LENGTH)
    return when (type) {
        "openUrl" -> {
            val url = requireValidHttpsUrl(obj.requireNonBlankString("url", WireTimelineProcessorLimitsV1.MAX_URL_OR_COPY_LENGTH))
            UiWidgetAction.OpenUrl(url = url)
        }

        "copy" -> {
            val text = obj.requireNonEmptyString("text", WireTimelineProcessorLimitsV1.MAX_URL_OR_COPY_LENGTH)
            val label = obj.optionalNonBlankOrNullString("label", WireTimelineProcessorLimitsV1.MAX_LABEL_LENGTH)
            UiWidgetAction.Copy(text = text, label = label)
        }

        "refresh" -> {
            UiWidgetAction.Refresh
        }

        else -> {
            null
        }
    }
}

private fun parseTone(element: JsonElement?): UiWidgetTone {
    val raw = element.optionalEnumWireString("tone") ?: return UiWidgetTone.Neutral
    return when (raw) {
        "neutral" -> UiWidgetTone.Neutral
        "accent" -> UiWidgetTone.Accent
        "positive" -> UiWidgetTone.Positive
        "negative" -> UiWidgetTone.Negative
        "warning" -> UiWidgetTone.Warning
        else -> UiWidgetTone.Neutral
    }
}

private fun parseTextStyle(element: JsonElement?): UiWidgetTextStyle {
    val raw = element.optionalEnumWireString("style") ?: return UiWidgetTextStyle.Body
    return when (raw) {
        "title" -> UiWidgetTextStyle.Title
        "subtitle" -> UiWidgetTextStyle.Subtitle
        "body" -> UiWidgetTextStyle.Body
        "caption" -> UiWidgetTextStyle.Caption
        "code" -> UiWidgetTextStyle.Code
        else -> UiWidgetTextStyle.Body
    }
}

private fun parseButtonStyle(element: JsonElement?): UiWidgetButtonStyle {
    val raw = element.optionalEnumWireString("style") ?: return UiWidgetButtonStyle.Secondary
    return when (raw) {
        "secondary" -> UiWidgetButtonStyle.Secondary
        "primary" -> UiWidgetButtonStyle.Primary
        else -> UiWidgetButtonStyle.Secondary
    }
}

private fun parseGap(element: JsonElement?): UiWidgetGap {
    val raw = element.optionalEnumWireString("gap") ?: return UiWidgetGap.Sm
    return when (raw) {
        "xs" -> UiWidgetGap.Xs
        "sm" -> UiWidgetGap.Sm
        "md" -> UiWidgetGap.Md
        else -> UiWidgetGap.Sm
    }
}

private fun parseAlign(element: JsonElement?): UiWidgetAlign {
    val raw = element.optionalEnumWireString("align") ?: return UiWidgetAlign.Start
    return when (raw) {
        "start" -> UiWidgetAlign.Start
        "center" -> UiWidgetAlign.Center
        "end" -> UiWidgetAlign.End
        "spaceBetween" -> UiWidgetAlign.SpaceBetween
        else -> UiWidgetAlign.Start
    }
}

private fun parseSparklineHeight(element: JsonElement?): UiWidgetSparklineHeight {
    val raw = element.optionalEnumWireString("height") ?: return UiWidgetSparklineHeight.Sm
    return when (raw) {
        "sm" -> UiWidgetSparklineHeight.Sm
        "md" -> UiWidgetSparklineHeight.Md
        else -> UiWidgetSparklineHeight.Sm
    }
}

private fun parseCornerStyle(element: JsonElement?): UiWidgetCornerStyle {
    val raw = element.optionalEnumWireString("cornerStyle") ?: return UiWidgetCornerStyle.Rounded
    return when (raw) {
        "rounded" -> UiWidgetCornerStyle.Rounded
        "circle" -> UiWidgetCornerStyle.Circle
        else -> UiWidgetCornerStyle.Rounded
    }
}

private fun JsonElement?.optionalEnumWireString(fieldName: String): String? {
    if (this == null || this is JsonNull) {
        return null
    }
    val primitive = this as? JsonPrimitive
    require(primitive != null && primitive.isString) { "Expected string for $fieldName" }
    val value = primitive.content
    require(value.isNotBlank() && value.length <= WireTimelineProcessorLimitsV1.MAX_TYPE_DISCRIMINATOR_LENGTH) {
        "Invalid enum string for $fieldName"
    }
    return value
}

private fun JsonElement.requireJsonObject(context: String): JsonObject =
    this as? JsonObject ?: throw IllegalArgumentException("Expected JSON object for $context")

private fun JsonObject.requireObjectField(name: String): JsonObject {
    val element = this[name] ?: throw IllegalArgumentException("Missing required object field '$name'")
    return element.requireJsonObject(name)
}

private fun JsonObject.optionalObjectField(name: String): JsonObject? {
    val element = this[name] ?: return null
    if (element is JsonNull) return null
    return element.requireJsonObject(name)
}

private fun JsonObject.requireArrayField(name: String): JsonArray {
    val element = this[name] ?: throw IllegalArgumentException("Missing required array field '$name'")
    return element as? JsonArray ?: throw IllegalArgumentException("Expected JSON array for '$name'")
}

private fun JsonObject.optionalArrayField(name: String): JsonArray? {
    val element = this[name] ?: return null
    if (element is JsonNull) return null
    return element as? JsonArray ?: throw IllegalArgumentException("Expected JSON array for '$name'")
}

private fun JsonObject.requireNonBlankString(
    name: String,
    maxLength: Int,
): String {
    val value = requireString(name, maxLength)
    require(value.isNotBlank()) { "Field '$name' must not be blank" }
    return value
}

private fun JsonObject.requireNonEmptyString(
    name: String,
    maxLength: Int,
): String {
    val value = requireString(name, maxLength)
    require(value.isNotEmpty()) { "Field '$name' must not be empty" }
    return value
}

private fun JsonObject.requireString(
    name: String,
    maxLength: Int,
): String {
    val element = this[name] ?: throw IllegalArgumentException("Missing required string field '$name'")
    val primitive = element as? JsonPrimitive
    require(primitive != null && primitive.isString) { "Expected string for '$name'" }
    val value = primitive.content
    require(value.length <= maxLength) { "Field '$name' exceeds max length of $maxLength" }
    return value
}

private fun JsonObject.optionalString(
    name: String,
    maxLength: Int,
): String? {
    val element = this[name] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive
    require(primitive != null && primitive.isString) { "Expected string for '$name'" }
    val value = primitive.content
    require(value.length <= maxLength) { "Field '$name' exceeds max length of $maxLength" }
    return value
}

private fun JsonObject.optionalNonBlankOrNullString(
    name: String,
    maxLength: Int,
): String? = optionalString(name, maxLength)?.takeIf { it.isNotBlank() }

private fun JsonObject.optionalBoolean(name: String): Boolean? {
    val element = this[name] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive
    require(primitive != null && !primitive.isString) { "Expected boolean for '$name'" }
    return primitive.booleanOrNull ?: throw IllegalArgumentException("Expected boolean for '$name'")
}

private fun JsonElement.requireFiniteDouble(name: String): Double {
    val primitive = this as? JsonPrimitive
    require(primitive != null && !primitive.isString) { "Expected number for '$name'" }
    val value = primitive.doubleOrNull
    require(value != null && value.isFinite()) { "Expected finite number for '$name'" }
    return value
}

private fun JsonObject.requirePositiveFiniteDouble(name: String): Double {
    val element = this[name] ?: throw IllegalArgumentException("Missing required number field '$name'")
    val value = element.requireFiniteDouble(name)
    require(value > 0.0) { "Field '$name' must be > 0" }
    return value
}

private fun JsonObject.optionalPositiveFloat(name: String): Float? {
    val element = this[name] ?: return null
    if (element is JsonNull) return null
    val doubleValue = element.requireFiniteDouble(name)
    val floatValue = doubleValue.toFloat()
    require(doubleValue > 0.0 && floatValue.isFinite() && floatValue > 0f) { "Field '$name' must be finite and > 0" }
    return floatValue
}

private fun JsonObject.optionalPositiveInt(name: String): Int? {
    val element = this[name] ?: return null
    if (element is JsonNull) return null
    val doubleValue = element.requireFiniteDouble(name)
    require(doubleValue % 1.0 == 0.0 && doubleValue >= 1.0 && doubleValue <= Int.MAX_VALUE.toDouble()) {
        "Field '$name' must be an integer >= 1"
    }
    return doubleValue.toInt()
}
