package dev.dimension.flare.feature.plugin.wire

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder

@Serializable(with = MangaStatusV1Serializer::class)
public enum class MangaStatusV1 {
    @SerialName("ongoing")
    Ongoing,

    @SerialName("completed")
    Completed,

    @SerialName("hiatus")
    Hiatus,

    @SerialName("cancelled")
    Cancelled,

    Unknown,
}

@Serializable(with = MangaReadingDirectionV1Serializer::class)
public enum class MangaReadingDirectionV1 {
    @SerialName("rtl")
    Rtl,

    @SerialName("ltr")
    Ltr,

    @SerialName("webtoon")
    Webtoon,

    Unknown,
}

@Serializable
public data class MangaTagV1(
    val name: String,
    val target: NavigationTargetV1? = null,
)

@Serializable
public data class MangaTagGroupV1(
    val id: String,
    val title: WireTextV1,
    val tags: List<MangaTagV1>,
)

@Serializable
public data class MangaChapterV1(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val uploadedAt: String? = null,
    val locked: Boolean = false,
    val url: String? = null,
    val entityToken: String? = null,
)

@Serializable
public data class MangaChapterGroupV1(
    val id: String,
    val title: WireTextV1,
    val chapters: List<MangaChapterV1>,
)

@Serializable
public data class MangaV1(
    val key: EntityKeyV1,
    val title: String,
    val altTitles: List<String> = emptyList(),
    val cover: MediaV1? = null,
    val author: ProfileV1? = null,
    val authors: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val status: MangaStatusV1? = null,
    val content: RichTextV1? = null,
    val url: String? = null,
    val updatedAt: String? = null,
    val readingDirection: MangaReadingDirectionV1? = null,
    val tags: List<MangaTagV1>? = null,
    val tagGroups: List<MangaTagGroupV1>? = null,
    val chapters: List<MangaChapterV1>? = null,
    val chapterGroups: List<MangaChapterGroupV1>? = null,
    val entityToken: String? = null,
    @Serializable(with = KnownActionsV1Serializer::class)
    val actions: List<ActionDescriptorV1> = emptyList(),
    val sourceLanguages: List<String> = emptyList(),
)

@Serializable
public data class MangaPagesRequestV1(
    val key: EntityKeyV1,
    val chapterId: String,
    val entityToken: String? = null,
    val chapterToken: String? = null,
    val offset: Int? = null,
    val limit: Int? = null,
    val cursor: String? = null,
)

@Serializable
public data class MangaPageV1(
    val id: String,
    val media: MediaV1? = null,
    val pageToken: String? = null,
    val previewUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val customHeaders: Map<String, String> = emptyMap(),
)

@Serializable
public data class MangaPagesV1(
    val pages: List<MangaPageV1>,
    val offset: Int? = null,
    val totalCount: Int? = null,
    val nextCursor: String? = null,
    val endReached: Boolean = true,
)

@Serializable
public enum class MangaPageImageReasonV1 {
    @SerialName("initial")
    Initial,

    @SerialName("resolveRetry")
    ResolveRetry,

    @SerialName("imageLoadFailed")
    ImageLoadFailed,
}

@Serializable
public data class MangaPageImageRequestV1(
    val key: EntityKeyV1,
    val chapterId: String,
    val pageId: String,
    val pageToken: String,
    val entityToken: String? = null,
    val chapterToken: String? = null,
    val attempt: Int? = null,
    val reason: MangaPageImageReasonV1? = null,
    val failedUrl: String? = null,
)

internal object MangaStatusV1Serializer : KSerializer<MangaStatusV1> {
    override val descriptor = PrimitiveSerialDescriptor("flare.MangaStatusV1", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): MangaStatusV1 =
        when ((decoder as JsonDecoder).decodeJsonElement().wireString()) {
            "ongoing" -> MangaStatusV1.Ongoing
            "completed" -> MangaStatusV1.Completed
            "hiatus" -> MangaStatusV1.Hiatus
            "cancelled" -> MangaStatusV1.Cancelled
            else -> MangaStatusV1.Unknown
        }

    override fun serialize(
        encoder: Encoder,
        value: MangaStatusV1,
    ) {
        encoder.encodeString(
            when (value) {
                MangaStatusV1.Ongoing -> "ongoing"
                MangaStatusV1.Completed -> "completed"
                MangaStatusV1.Hiatus -> "hiatus"
                MangaStatusV1.Cancelled -> "cancelled"
                MangaStatusV1.Unknown -> "unknown"
            },
        )
    }
}

internal object MangaReadingDirectionV1Serializer : KSerializer<MangaReadingDirectionV1> {
    override val descriptor = PrimitiveSerialDescriptor("flare.MangaReadingDirectionV1", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): MangaReadingDirectionV1 =
        when ((decoder as JsonDecoder).decodeJsonElement().wireString()) {
            "rtl" -> MangaReadingDirectionV1.Rtl
            "ltr" -> MangaReadingDirectionV1.Ltr
            "webtoon" -> MangaReadingDirectionV1.Webtoon
            else -> MangaReadingDirectionV1.Unknown
        }

    override fun serialize(
        encoder: Encoder,
        value: MangaReadingDirectionV1,
    ) {
        encoder.encodeString(
            when (value) {
                MangaReadingDirectionV1.Rtl -> "rtl"
                MangaReadingDirectionV1.Ltr -> "ltr"
                MangaReadingDirectionV1.Webtoon -> "webtoon"
                MangaReadingDirectionV1.Unknown -> "unknown"
            },
        )
    }
}
