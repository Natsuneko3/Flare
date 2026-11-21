package dev.dimension.flare.ui.model

import androidx.compose.runtime.Immutable
import dev.dimension.flare.common.SerializableImmutableList
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.render.RenderContent
import dev.dimension.flare.ui.render.RenderRun
import dev.dimension.flare.ui.render.UiRichText
import dev.dimension.flare.ui.render.plainText
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import okio.ByteString.Companion.encodeUtf8

@Serializable
@Immutable
public enum class UiWidgetTone {
    Neutral,
    Accent,
    Positive,
    Negative,
    Warning,
}

@Serializable
@Immutable
public enum class UiWidgetGap {
    Xs,
    Sm,
    Md,
}

@Serializable
@Immutable
public enum class UiWidgetAlign {
    Start,
    Center,
    End,
    SpaceBetween,
}

@Serializable
@Immutable
public enum class UiWidgetTextStyle {
    Title,
    Subtitle,
    Body,
    Caption,
    Code,
}

@Serializable
@Immutable
public enum class UiWidgetButtonStyle {
    Secondary,
    Primary,
}

@Serializable
@Immutable
public enum class UiWidgetCornerStyle {
    Rounded,
    Circle,
}

@Serializable
@Immutable
public enum class UiWidgetSparklineHeight {
    Sm,
    Md,
}

@Serializable
@Immutable
public sealed interface UiWidgetAction {
    @Serializable
    @Immutable
    public data class OpenUrl public constructor(
        public val url: String,
    ) : UiWidgetAction

    @Serializable
    @Immutable
    public data class Copy public constructor(
        public val text: String,
        public val label: String? = null,
    ) : UiWidgetAction

    @Serializable
    @Immutable
    public data object Refresh : UiWidgetAction
}

@Serializable
@Immutable
public data class UiWidgetBadge public constructor(
    public val text: String,
    public val tone: UiWidgetTone = UiWidgetTone.Neutral,
)

@Serializable
@Immutable
public data class UiWidgetButton public constructor(
    public val label: String,
    public val tone: UiWidgetTone = UiWidgetTone.Neutral,
    public val style: UiWidgetButtonStyle = UiWidgetButtonStyle.Secondary,
    public val action: UiWidgetAction,
)

@Serializable
@Immutable
public sealed class UiWidgetNode {
    public open val weight: Float?
        get() = null

    @Serializable
    @Immutable
    public data class Text public constructor(
        public val text: String,
        public val style: UiWidgetTextStyle = UiWidgetTextStyle.Body,
        public val tone: UiWidgetTone = UiWidgetTone.Neutral,
        public val maxLines: Int? = null,
        public val copyable: Boolean = false,
    ) : UiWidgetNode()

    @Serializable
    @Immutable
    public data class Row public constructor(
        public val children: SerializableImmutableList<UiWidgetNode>,
        public val gap: UiWidgetGap = UiWidgetGap.Sm,
        public val align: UiWidgetAlign = UiWidgetAlign.Start,
        override val weight: Float? = null,
    ) : UiWidgetNode()

    @Serializable
    @Immutable
    public data class Column public constructor(
        public val children: SerializableImmutableList<UiWidgetNode>,
        public val gap: UiWidgetGap = UiWidgetGap.Sm,
        public val align: UiWidgetAlign = UiWidgetAlign.Start,
        override val weight: Float? = null,
    ) : UiWidgetNode()

    @Serializable
    @Immutable
    public data class Stat public constructor(
        public val label: String,
        public val value: String,
        public val delta: String? = null,
        public val deltaTone: UiWidgetTone = UiWidgetTone.Neutral,
        public val subtext: String? = null,
        override val weight: Float? = null,
    ) : UiWidgetNode()

    @Serializable
    @Immutable
    public data class MatchupSide public constructor(
        public val name: String,
        public val subtitle: String? = null,
        public val iconUrl: String? = null,
        public val score: String? = null,
    )

    @Serializable
    @Immutable
    public data class Matchup public constructor(
        public val left: UiWidgetNode.MatchupSide,
        public val right: UiWidgetNode.MatchupSide,
        public val status: String? = null,
        public val statusTone: UiWidgetTone = UiWidgetTone.Neutral,
    ) : UiWidgetNode() {
        public typealias MatchupSide = UiWidgetNode.MatchupSide
    }

    @Serializable
    @Immutable
    public data class Sparkline public constructor(
        public val points: SerializableImmutableList<Double>,
        public val tone: UiWidgetTone = UiWidgetTone.Neutral,
        public val minLabel: String? = null,
        public val maxLabel: String? = null,
        public val height: UiWidgetSparklineHeight = UiWidgetSparklineHeight.Sm,
        override val weight: Float? = null,
    ) : UiWidgetNode() {
        public val normalizedPoints: ImmutableList<Float> by lazy {
            if (points.isEmpty()) {
                persistentListOf()
            } else {
                val finitePoints = points.map { if (it.isFinite()) it else 0.0 }
                val min = finitePoints.min()
                val max = finitePoints.max()
                val span = max - min
                if (span == 0.0 || !span.isFinite()) {
                    finitePoints.map { 0.5f }.toImmutableList()
                } else {
                    finitePoints.map { ((it - min) / span).toFloat().coerceIn(0f, 1f) }.toImmutableList()
                }
            }
        }
    }

    @Serializable
    @Immutable
    public data class SegmentedBar public constructor(
        public val segments: SerializableImmutableList<Segment>,
    ) : UiWidgetNode() {
        public val normalizedFractions: ImmutableList<Float> by lazy {
            if (segments.isEmpty()) {
                persistentListOf()
            } else {
                val sanitized = segments.map { if (it.weight.isFinite() && it.weight > 0.0) it.weight else 0.0 }
                val sum = sanitized.sum()
                if (sum <= 0.0 || !sum.isFinite()) {
                    val equal = 1f / segments.size.toFloat()
                    segments.map { equal }.toImmutableList()
                } else {
                    sanitized.map { (it / sum).toFloat() }.toImmutableList()
                }
            }
        }

        @Serializable
        @Immutable
        public data class Segment public constructor(
            public val label: String,
            public val valueText: String? = null,
            public val weight: Double,
            public val tone: UiWidgetTone = UiWidgetTone.Neutral,
        )
    }

    @Serializable
    @Immutable
    public data class KeyValue public constructor(
        public val items: SerializableImmutableList<Item>,
    ) : UiWidgetNode() {
        @Serializable
        @Immutable
        public data class Item public constructor(
            public val label: String,
            public val value: String,
            public val tone: UiWidgetTone = UiWidgetTone.Neutral,
            public val action: UiWidgetAction? = null,
        )
    }

    @Serializable
    @Immutable
    public data class BadgeRow public constructor(
        public val badges: SerializableImmutableList<UiWidgetBadge>,
    ) : UiWidgetNode()

    @Serializable
    @Immutable
    public data class Image public constructor(
        public val url: String,
        public val alt: String? = null,
        public val aspectRatio: Float? = null,
        public val cornerStyle: UiWidgetCornerStyle = UiWidgetCornerStyle.Rounded,
        override val weight: Float? = null,
    ) : UiWidgetNode()

    @Serializable
    @Immutable
    public data class Collapsible public constructor(
        public val id: String,
        public val summary: String,
        public val children: SerializableImmutableList<UiWidgetNode>,
        public val expanded: Boolean = false,
        @Transient
        public val onToggle: (() -> Unit)? = null,
    ) : UiWidgetNode()

    @Serializable
    @Immutable
    public data object Divider : UiWidgetNode()
}

@Serializable
@Immutable
public data class UiPluginWidgetCard public constructor(
    public val pluginId: String,
    public val pluginName: String,
    public val pluginIconPath: String?,
    public val id: String,
    public val title: String,
    public val badge: UiWidgetBadge? = null,
    public val accentTone: UiWidgetTone = UiWidgetTone.Neutral,
    public val refreshable: Boolean = false,
    public val isRefreshing: Boolean = false,
    public val cardAction: UiWidgetAction? = null,
    public val body: SerializableImmutableList<UiWidgetNode>,
    public val actions: SerializableImmutableList<UiWidgetButton> = persistentListOf(),
    @Transient
    public val onRefresh: (() -> Unit)? = null,
)

public data class ExtractedPluginPostInput public constructor(
    public val postEntityKey: String,
    public val platformId: String,
    public val postKey: MicroBlogKey,
    public val createdAtIso: String,
    public val authorHandle: String?,
    public val authorDisplayName: String?,
    public val contentPlain: String,
    public val contentWarningPlain: String?,
    public val links: List<String>,
    public val cardUrl: String?,
    public val cardTitle: String?,
    public val cardDescription: String?,
    public val sourceLanguages: List<String>,
    public val sourceHash: String,
)

public fun UiTimelineV2.Post.pluginPostEntityKey(): String = "${accountType}_$statusKey"

public fun UiTimelineV2.Post.extractPluginPostInput(): ExtractedPluginPostInput {
    val contentPlain = content.original.extractParagraphPlainText()
    val contentWarningPlain = contentWarning?.original?.extractParagraphPlainText()?.takeIf { it.isNotEmpty() }
    val safeCardUrl = card?.url?.trim()?.takeIf { it.isHttpsUrlWithoutUserInfo() }
    val safeCardTitle = if (safeCardUrl != null) card.title.takeIf { it.isNotEmpty() } else null
    val safeCardDescription = if (safeCardUrl != null) card.description?.takeIf { it.isNotEmpty() } else null

    val collectedLinks = LinkedHashSet<String>()
    content.original.collectSafeHttpsLinksInto(collectedLinks)
    contentWarning?.original?.collectSafeHttpsLinksInto(collectedLinks)
    if (safeCardUrl != null) {
        collectedLinks.add(safeCardUrl)
    }
    val links = collectedLinks.toList()
    val languages = sourceLanguages.toList()
    val sourceHash =
        computePluginPostSourceHash(
            contentPlain = contentPlain,
            contentWarningPlain = contentWarningPlain,
            links = links,
            cardUrl = safeCardUrl,
            cardTitle = safeCardTitle,
            cardDescription = safeCardDescription,
        )

    return ExtractedPluginPostInput(
        postEntityKey = pluginPostEntityKey(),
        platformId = platformId,
        postKey = statusKey,
        createdAtIso = createdAt.value.toString(),
        authorHandle = user?.handle?.display,
        authorDisplayName = user?.name?.innerText,
        contentPlain = contentPlain,
        contentWarningPlain = contentWarningPlain,
        links = links,
        cardUrl = safeCardUrl,
        cardTitle = safeCardTitle,
        cardDescription = safeCardDescription,
        sourceLanguages = languages,
        sourceHash = sourceHash,
    )
}

public fun UiTimelineV2.withPluginWidgets(
    lookup: (UiTimelineV2.Post) -> ImmutableList<UiPluginWidgetCard>,
): UiTimelineV2 =
    when (this) {
        is UiTimelineV2.Post -> {
            val nextWidgets = lookup(this)
            if (nextWidgets.isEmpty() && pluginWidgets.isEmpty()) {
                this
            } else if (nextWidgets == pluginWidgets) {
                this
            } else {
                copy(pluginWidgets = nextWidgets)
            }
        }

        is UiTimelineV2.TimelinePostItem -> {
            val existingRepost = presentation.repost
            val updatedPost: UiTimelineV2.Post
            val updatedRepost: UiTimelineV2.Post?
            if (existingRepost != null) {
                updatedPost = post
                updatedRepost = existingRepost.withPluginWidgets(lookup) as UiTimelineV2.Post
            } else {
                updatedPost = post.withPluginWidgets(lookup) as UiTimelineV2.Post
                updatedRepost = null
            }
            val updatedParents =
                presentation.inlineParents.mapIfAnyChanged { parent ->
                    parent.withPluginWidgets(lookup) as UiTimelineV2.TimelinePostItem
                }
            if (
                updatedPost === post &&
                updatedRepost === existingRepost &&
                updatedParents === presentation.inlineParents
            ) {
                this
            } else {
                copy(
                    post = updatedPost,
                    presentation =
                        presentation.copy(
                            inlineParents = updatedParents,
                            repost = updatedRepost,
                        ),
                )
            }
        }

        else -> {
            this
        }
    }

public fun UiPluginWidgetCard.renderSummaryHash(): Int {
    var hash = 17
    hash = 31 * hash + pluginId.hashCode()
    hash = 31 * hash + pluginName.hashCode()
    hash = 31 * hash + (pluginIconPath?.hashCode() ?: 0)
    hash = 31 * hash + id.hashCode()
    hash = 31 * hash + title.hashCode()
    hash = 31 * hash + (badge?.hashCode() ?: 0)
    hash = 31 * hash + accentTone.ordinal
    hash = 31 * hash + refreshable.hashCode()
    hash = 31 * hash + isRefreshing.hashCode()
    hash = 31 * hash + (cardAction?.hashCode() ?: 0)
    var bodyHash = 17
    body.forEach { node ->
        bodyHash = 31 * bodyHash + node.renderSummaryHash()
    }
    hash = 31 * hash + bodyHash
    hash = 31 * hash + actions.hashCode()
    return hash
}

private fun UiWidgetNode.renderSummaryHash(): Int =
    when (this) {
        is UiWidgetNode.Text -> {
            var h = 1
            h = 31 * h + text.hashCode()
            h = 31 * h + style.ordinal
            h = 31 * h + tone.ordinal
            h = 31 * h + (maxLines ?: -1)
            h = 31 * h + copyable.hashCode()
            h
        }

        is UiWidgetNode.Row -> {
            var h = 2
            h = 31 * h + gap.ordinal
            h = 31 * h + align.ordinal
            h = 31 * h + (weight?.toBits() ?: 0)
            children.forEach { child -> h = 31 * h + child.renderSummaryHash() }
            h
        }

        is UiWidgetNode.Column -> {
            var h = 3
            h = 31 * h + gap.ordinal
            h = 31 * h + align.ordinal
            h = 31 * h + (weight?.toBits() ?: 0)
            children.forEach { child -> h = 31 * h + child.renderSummaryHash() }
            h
        }

        is UiWidgetNode.Stat -> {
            var h = 4
            h = 31 * h + label.hashCode()
            h = 31 * h + value.hashCode()
            h = 31 * h + (delta?.hashCode() ?: 0)
            h = 31 * h + deltaTone.ordinal
            h = 31 * h + (subtext?.hashCode() ?: 0)
            h = 31 * h + (weight?.toBits() ?: 0)
            h
        }

        is UiWidgetNode.Matchup -> {
            var h = 5
            h = 31 * h + left.hashCode()
            h = 31 * h + right.hashCode()
            h = 31 * h + (status?.hashCode() ?: 0)
            h = 31 * h + statusTone.ordinal
            h
        }

        is UiWidgetNode.Sparkline -> {
            var h = 6
            points.forEach { point -> h = 31 * h + point.toBits().hashCode() }
            h = 31 * h + tone.ordinal
            h = 31 * h + (minLabel?.hashCode() ?: 0)
            h = 31 * h + (maxLabel?.hashCode() ?: 0)
            h = 31 * h + height.ordinal
            h = 31 * h + (weight?.toBits() ?: 0)
            h
        }

        is UiWidgetNode.SegmentedBar -> {
            var h = 7
            segments.forEach { segment ->
                h = 31 * h + segment.label.hashCode()
                h = 31 * h + (segment.valueText?.hashCode() ?: 0)
                h = 31 * h + segment.weight.toBits().hashCode()
                h = 31 * h + segment.tone.ordinal
            }
            h
        }

        is UiWidgetNode.KeyValue -> {
            var h = 8
            h = 31 * h + items.hashCode()
            h
        }

        is UiWidgetNode.BadgeRow -> {
            var h = 9
            h = 31 * h + badges.hashCode()
            h
        }

        is UiWidgetNode.Image -> {
            var h = 10
            h = 31 * h + url.hashCode()
            h = 31 * h + (alt?.hashCode() ?: 0)
            h = 31 * h + (aspectRatio?.toBits() ?: 0)
            h = 31 * h + cornerStyle.ordinal
            h = 31 * h + (weight?.toBits() ?: 0)
            h
        }

        is UiWidgetNode.Collapsible -> {
            var h = 11
            h = 31 * h + id.hashCode()
            h = 31 * h + summary.hashCode()
            h = 31 * h + expanded.hashCode()
            children.forEach { child -> h = 31 * h + child.renderSummaryHash() }
            h
        }

        UiWidgetNode.Divider -> {
            12
        }
    }

private fun UiRichText.extractParagraphPlainText(): String =
    renderRuns
        .filterIsInstance<RenderContent.Text>()
        .map { it.plainText() }
        .filter { it.isNotEmpty() }
        .joinToString(separator = "\n\n")

private fun UiRichText.collectSafeHttpsLinksInto(target: MutableSet<String>) {
    renderRuns.forEach { content ->
        if (content is RenderContent.Text) {
            content.runs.forEach { run ->
                if (run is RenderRun.Text) {
                    val candidate = run.style.link?.trim()
                    if (candidate != null && candidate.isHttpsUrlWithoutUserInfo()) {
                        target.add(candidate)
                    }
                }
            }
        }
    }
}

private fun String.isHttpsUrlWithoutUserInfo(): Boolean {
    if (!startsWith("https://", ignoreCase = true)) {
        return false
    }
    val authority = substring(8).substringBefore('/').substringBefore('?').substringBefore('#')
    if (authority.isEmpty() || '@' in authority) {
        return false
    }
    return runCatching {
        val parsed = Url(this)
        parsed.protocol == URLProtocol.HTTPS &&
            parsed.host.isNotBlank() &&
            parsed.encodedUser == null &&
            parsed.encodedPassword == null
    }.getOrDefault(false)
}

private fun computePluginPostSourceHash(
    contentPlain: String,
    contentWarningPlain: String?,
    links: List<String>,
    cardUrl: String?,
    cardTitle: String?,
    cardDescription: String?,
): String =
    buildString {
        appendFramed(contentPlain)
        appendFramedNullable(contentWarningPlain)
        append(links.size).append(':')
        links.forEach { appendFramed(it) }
        appendFramedNullable(cardUrl)
        appendFramedNullable(cardTitle)
        appendFramedNullable(cardDescription)
    }.encodeUtf8().sha256().hex()

private fun StringBuilder.appendFramed(value: String) {
    append(value.length).append(':').append(value).append('|')
}

private fun StringBuilder.appendFramedNullable(value: String?) {
    if (value == null) {
        append("null|")
    } else {
        append("str:")
        appendFramed(value)
    }
}

private inline fun <T> SerializableImmutableList<T>.mapIfAnyChanged(transform: (T) -> T): SerializableImmutableList<T> {
    if (isEmpty()) {
        return this
    }
    var changed = false
    val mapped =
        map { item ->
            val updated = transform(item)
            if (updated !== item) {
                changed = true
            }
            updated
        }
    return if (changed) mapped.toImmutableList() else this
}
