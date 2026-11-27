package dev.dimension.flare.data.datasource.instagram

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal data class InstagramUser(
    val pk: JsonPrimitive? = null,
    val id: JsonPrimitive? = null,
    val username: String,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("profile_pic_url_hd") val hdAvatar: String? = null,
    @SerialName("profile_pic_url") val avatar: String? = null,
    val biography: String? = null,
    @SerialName("is_private") val isPrivate: Boolean? = null,
    @SerialName("is_verified") val isVerified: Boolean? = null,
    @SerialName("follower_count") val followers: Long? = null,
    @SerialName("following_count") val following: Long? = null,
    @SerialName("media_count") val posts: Long? = null,
    @SerialName("edge_followed_by") val followerEdge: Count? = null,
    @SerialName("edge_follow") val followingEdge: Count? = null,
    @SerialName("edge_owner_to_timeline_media") val mediaEdge: Count? = null,
) {
    @Serializable
    data class Count(
        val count: Long? = null,
    )
}

@Serializable
internal data class InstagramMedia(
    val pk: JsonPrimitive? = null,
    val id: JsonPrimitive? = null,
    val code: String? = null,
    @SerialName("media_type") val mediaType: Int,
    @SerialName("image_versions2") val images: ImageVersions? = null,
    @SerialName("video_versions") val videos: List<Variant>? = null,
    @SerialName("video_duration") val durationSeconds: Double? = null,
    @SerialName("carousel_media") val children: List<InstagramMedia>? = null,
    @SerialName("original_width") val width: Int = 0,
    @SerialName("original_height") val height: Int = 0,
    @SerialName("accessibility_caption") val alt: String? = null,
    val caption: Caption? = null,
    val user: InstagramUser? = null,
    @SerialName("taken_at") val timestamp: Long? = null,
) {
    @Serializable
    data class Caption(
        val text: String? = null,
    )

    @Serializable
    data class ImageVersions(
        val candidates: List<Variant>? = null,
    )

    @Serializable
    data class Variant(
        val url: String,
        val width: Int = 0,
        val height: Int = 0,
    )
}

@Serializable
internal data class InstagramComment(
    val pk: JsonPrimitive? = null,
    val id: JsonPrimitive? = null,
    val user: InstagramUser? = null,
    val text: String,
    @SerialName("created_at") val timestamp: Long? = null,
    @SerialName("created_at_utc") val timestampUtc: Long? = null,
)
