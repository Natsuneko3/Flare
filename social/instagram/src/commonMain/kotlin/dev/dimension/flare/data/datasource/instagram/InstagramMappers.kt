package dev.dimension.flare.data.datasource.instagram

import dev.dimension.flare.common.JSON
import dev.dimension.flare.data.datasource.microblog.ActionMenu
import dev.dimension.flare.data.datasource.microblog.PostActionFamily
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.platform.INSTAGRAM_HOST
import dev.dimension.flare.data.platform.INSTAGRAM_PLATFORM_ID
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.ReferenceType
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.UiHandle
import dev.dimension.flare.ui.model.UiIcon
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.UiTranslatableText
import dev.dimension.flare.ui.model.asType
import dev.dimension.flare.ui.model.bindMediaProxy
import dev.dimension.flare.ui.model.toUiImage
import dev.dimension.flare.ui.model.withProxy
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.render.toUiPlainText
import dev.dimension.flare.ui.route.DeeplinkRoute
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.time.Instant

private val cdnUrl = Regex("""https://(?:www\.instagram\.com|(?:[a-zA-Z0-9-]+\.)*(?:cdninstagram\.com|fbcdn\.net))/[^\s\\]*""")
private val digits = Regex("[1-9][0-9]{0,29}")
private val qualifiedId = Regex("([1-9][0-9]{0,29})_[0-9]+")
private val shortcode = Regex("[A-Za-z0-9_-]{1,30}")
private val excludedUnits =
    setOf(
        "ad",
        "ad_id",
        "is_ad",
        "ad_token",
        "injected",
        "suggested_users",
        "clips_netego",
        "suggested_user_feed_unit",
        "recommended_users",
    )

private fun String?.mediaUrl(): String? = this?.takeIf(cdnUrl::matches)

private fun entityKey(
    pk: JsonPrimitive?,
    id: JsonPrimitive?,
): MicroBlogKey {
    val primary = pk?.contentOrNull?.takeIf(digits::matches)
    val alternate = id?.contentOrNull
    val value = primary ?: alternate?.takeIf(digits::matches) ?: alternate?.let { qualifiedId.matchEntire(it)?.groupValues?.get(1) }
    return MicroBlogKey(id = requireNotNull(value) { "Instagram response has no valid entity ID" }, host = INSTAGRAM_HOST)
}

internal fun JsonObject.toInstagramProfile(accountKey: MicroBlogKey): UiProfile =
    JSON.decodeFromJsonElement<InstagramUser>(this).toUiProfile(accountKey)

private fun InstagramUser.toUiProfile(accountKey: MicroBlogKey): UiProfile {
    require(username.isNotBlank()) { "Instagram response has no username" }
    val key = entityKey(pk, id)
    return UiProfile(
        key = key,
        platformId = INSTAGRAM_PLATFORM_ID,
        platformIcon = UiIcon.Instagram.asType(),
        handle = UiHandle(raw = username, host = key.host),
        nameInternal = (fullName?.takeIf(String::isNotBlank) ?: username).toUiPlainText(),
        avatar = (hdAvatar.mediaUrl() ?: avatar.mediaUrl()).toUiImage(),
        clickEvent = ClickEvent.Deeplink(DeeplinkRoute.Profile.User(accountType = AccountType.Specific(accountKey), userKey = key)),
        banner = null,
        description = biography?.takeIf(String::isNotBlank)?.toUiPlainText(),
        matrices =
            UiProfile.Matrices(
                fansCount = followers ?: followerEdge?.count ?: 0L,
                followsCount = following ?: followingEdge?.count ?: 0L,
                statusesCount = posts ?: mediaEdge?.count ?: 0L,
            ),
        mark =
            buildList {
                if (isVerified == true) add(UiProfile.Mark.Verified)
                if (isPrivate == true) add(UiProfile.Mark.Locked)
            }.toImmutableList(),
        bottomContent = null,
    ).withProxy(ProxyRef.forAccount(accountKey))
}

private fun List<InstagramMedia.Variant>?.ranked() =
    orEmpty()
        .filter { it.url.mediaUrl() != null }
        .sortedByDescending { it.width.coerceAtLeast(0).toLong() * it.height.coerceAtLeast(0) }
        .distinctBy { it.url }

private fun InstagramMedia.attachment(): UiMedia? {
    val candidates = images?.candidates.ranked()
    val image = candidates.firstOrNull()
    return when (mediaType) {
        1 -> {
            image?.let {
                UiMedia.Image(
                    url = it.url,
                    previewUrl = candidates.getOrNull(1)?.url ?: it.url,
                    description = alt,
                    width = (it.width.takeIf { w -> w > 0 } ?: width).toFloat(),
                    height = (it.height.takeIf { h -> h > 0 } ?: height).toFloat(),
                    sensitive = false,
                )
            }
        }

        2 -> {
            videos.ranked().firstOrNull()?.let {
                UiMedia.Video(
                    url = it.url,
                    thumbnailUrl = image?.url.orEmpty(),
                    description = alt,
                    width = (it.width.takeIf { w -> w > 0 } ?: width).toFloat(),
                    height = (it.height.takeIf { h -> h > 0 } ?: height).toFloat(),
                    durationMillis =
                        durationSeconds
                            ?.takeIf { seconds ->
                                seconds in 0.0..604800.0
                            }?.let { seconds -> (seconds * 1000).toLong() },
                )
            }
        }

        else -> {
            null
        }
    }
}

private fun JsonObject.isExcluded() =
    excludedUnits.any { key ->
        val flag = this[key]
        flag != null && flag != JsonNull && (flag !is JsonPrimitive || flag.content !in listOf("", "false", "0"))
    }

internal fun JsonObject.toInstagramPostOrNull(accountKey: MicroBlogKey): UiTimelineV2.Post? {
    if (isExcluded() || (get("media_type") as? JsonPrimitive)?.content !in listOf("1", "2", "8")) return null
    val media = JSON.decodeFromJsonElement<InstagramMedia>(this)
    val statusKey = entityKey(media.pk, media.id)
    val accountType = AccountType.Specific(accountKey)
    val mediaItems = if (media.mediaType == 8) media.children.orEmpty() else listOf(media)
    val attachments = mediaItems.mapNotNull { it.attachment() }
    val shareAction =
        media.code?.takeIf(shortcode::matches)?.let { code ->
            ActionMenu.Item(
                icon = UiIcon.Share,
                text = ActionMenu.Item.Text.Localized(ActionMenu.Item.Text.Localized.Type.Share),
                actionFamily = PostActionFamily.Share,
                clickEvent =
                    ClickEvent.Deeplink(
                        DeeplinkRoute.Status.ShareSheet(statusKey, accountType, "https://www.instagram.com/p/$code/"),
                    ),
            )
        }
    return UiTimelineV2
        .Post(
            platformId = INSTAGRAM_PLATFORM_ID,
            platformIcon = UiIcon.Instagram.asType(),
            images = attachments.toImmutableList(),
            sensitive = false,
            contentWarning = null,
            user = media.user?.toUiProfile(accountKey),
            content =
                UiTranslatableText(
                    original =
                        media.caption
                            ?.text
                            .orEmpty()
                            .toUiPlainText(),
                ),
            actions = listOfNotNull(shareAction).toImmutableList(),
            poll = null,
            card = null,
            statusKey = statusKey,
            createdAt = Instant.fromEpochSeconds(requireNotNull(media.timestamp) { "Instagram post has no timestamp" }).toUi(),
            visibility =
                media.user?.isPrivate?.let {
                    if (it) UiTimelineV2.Post.Visibility.Followers else UiTimelineV2.Post.Visibility.Public
                },
            clickEvent = ClickEvent.Deeplink(DeeplinkRoute.Status.Detail(statusKey, accountType)),
            accountType = accountType,
        ).bindMediaProxy()
}

internal fun JsonObject.toInstagramComment(
    accountKey: MicroBlogKey,
    parent: MicroBlogKey,
): UiTimelineV2.Post {
    val comment = JSON.decodeFromJsonElement<InstagramComment>(this)
    val id = entityKey(comment.pk, comment.id).id
    val accountType = AccountType.Specific(accountKey)
    return UiTimelineV2
        .Post(
            platformId = INSTAGRAM_PLATFORM_ID,
            platformIcon = UiIcon.Instagram.asType(),
            images = persistentListOf(),
            sensitive = false,
            contentWarning = null,
            user = comment.user?.toUiProfile(accountKey),
            content = UiTranslatableText(original = comment.text.toUiPlainText()),
            actions = persistentListOf(),
            poll = null,
            card = null,
            statusKey = MicroBlogKey("${parent.id}:comment:$id", parent.host),
            createdAt = Instant.fromEpochSeconds(requireNotNull(comment.timestamp ?: comment.timestampUtc)).toUi(),
            references = persistentListOf(UiTimelineV2.Post.Reference(parent, ReferenceType.Reply)),
            clickEvent = ClickEvent.Deeplink(DeeplinkRoute.Status.Detail(parent, accountType)),
            accountType = accountType,
        ).bindMediaProxy()
}
