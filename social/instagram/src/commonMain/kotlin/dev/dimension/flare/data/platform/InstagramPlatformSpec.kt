package dev.dimension.flare.data.platform

import dev.dimension.flare.data.datasource.instagram.InstagramDataSource
import dev.dimension.flare.data.datasource.microblog.MicroblogDataSource
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.model.tab.TimelineSpec
import dev.dimension.flare.data.network.instagram.InstagramService
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.PlatformDataSourceContext
import dev.dimension.flare.model.PlatformDeepLink
import dev.dimension.flare.model.PlatformMetadata
import dev.dimension.flare.model.PlatformSpec
import dev.dimension.flare.ui.model.UiIcon
import dev.dimension.flare.ui.model.asType
import dev.dimension.flare.ui.presenter.login.InstagramLoginProvider
import dev.dimension.flare.ui.presenter.login.LoginPlatformProvider
import dev.dimension.flare.ui.route.DeeplinkRoute
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.Serializable
import kotlin.native.HiddenFromObjC

// The earlier installable plugin owns the case-insensitive platform ID "instagram".
internal const val INSTAGRAM_PLATFORM_ID = "InstagramNative"

@HiddenFromObjC
public data object InstagramPlatformSpec :
    PlatformSpec,
    LoginPlatformProvider by InstagramLoginProvider {
    override val platformId: String = INSTAGRAM_PLATFORM_ID
    override val metadata: PlatformMetadata =
        PlatformMetadata(displayName = "Instagram", icon = UiIcon.Instagram.asType())
    override val order: Int = 41

    override val timelineSpecs: ImmutableList<TimelineSpec<out TimelineSpec.Data>> =
        persistentListOf(CommonTimelineSpecs.home)

    override fun createDataSource(context: PlatformDataSourceContext): MicroblogDataSource =
        InstagramDataSource(
            accountKey = context.accountKey,
            service =
                InstagramService(
                    credentialFlow = context.credentialFlow(InstagramCredential.serializer()),
                    onCredentialRefreshed = { previous, refreshed ->
                        context.mutateCredential(InstagramCredential.serializer()) { current ->
                            refreshed.takeIf { current == previous }
                        }
                    },
                    proxy = ProxyRef.forAccount(context.accountKey),
                ),
        )

    override fun guestDataSource(
        host: String,
        locale: String,
    ): MicroblogDataSource = throw UnsupportedOperationException("Instagram requires a signed-in account")

    override fun deepLinks(accountKey: MicroBlogKey): ImmutableList<PlatformDeepLink<*>> {
        val account = AccountType.Specific(accountKey)
        return buildList {
            for (host in listOf(INSTAGRAM_HOST, INSTAGRAM_WEB_HOST)) {
                for (suffix in listOf("", "/")) {
                    for (path in listOf("p", "reel")) {
                        add(
                            PlatformDeepLink(
                                uriPattern = "https://$host/$path/{value}$suffix",
                                serializer = InstagramLink.serializer(),
                                matcher = { instagramMediaId(it.value) != null },
                                callback = {
                                    DeeplinkRoute.Status.Detail(
                                        statusKey = MicroBlogKey(requireNotNull(instagramMediaId(it.value)), INSTAGRAM_HOST),
                                        accountType = account,
                                    )
                                },
                            ),
                        )
                    }
                    add(
                        PlatformDeepLink(
                            uriPattern = "https://$host/{value}$suffix",
                            serializer = InstagramLink.serializer(),
                            matcher = { it.value.matches(usernamePattern) && it.value.lowercase() !in reservedSegments },
                            callback = { DeeplinkRoute.Profile.UserNameWithHost(account, it.value, INSTAGRAM_HOST) },
                        ),
                    )
                }
            }
        }.toImmutableList()
    }
}

@Serializable
private data class InstagramLink(
    val value: String,
)

private val usernamePattern = Regex("[A-Za-z0-9_.]{1,30}")
private val reservedSegments =
    setOf(
        "accounts",
        "about",
        "api",
        "developer",
        "developers",
        "direct",
        "directory",
        "emails",
        "explore",
        "legal",
        "p",
        "press",
        "privacy",
        "reel",
        "reels",
        "share",
        "static",
        "stories",
        "terms",
        "web",
    )

private const val SHORTCODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

internal fun instagramMediaId(code: String): String? {
    if (code.length !in 1..11) return null
    var id = 0uL
    for (character in code) {
        val digit = SHORTCODE_ALPHABET.indexOf(character)
        if (digit < 0 || id > ULong.MAX_VALUE / 64u) return null
        id = id * 64u + digit.toUInt()
    }
    return id.takeIf { it != 0uL }?.toString()
}
