package dev.dimension.flare.ui.presenter.login

import dev.dimension.flare.data.platform.INSTAGRAM_HOST
import dev.dimension.flare.data.platform.INSTAGRAM_PLATFORM_ID
import dev.dimension.flare.data.platform.INSTAGRAM_WEB_HOST
import dev.dimension.flare.data.platform.InstagramPlatformSpec
import dev.dimension.flare.model.PlatformMetadata
import dev.dimension.flare.model.RecommendedInstance
import dev.dimension.flare.ui.model.UiInstance
import dev.dimension.flare.ui.model.UiInstanceMetadata
import dev.dimension.flare.ui.model.UiStrings
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

internal data object InstagramLoginProvider : LoginPlatformProvider {
    override val platformId: String = INSTAGRAM_PLATFORM_ID
    override val metadata: PlatformMetadata get() = InstagramPlatformSpec.metadata
    override val detector: PlatformDetector = InstagramPlatformDetector
    override val methods: List<LoginMethodSpec> =
        listOf(
            LoginMethodSpec(LoginMethodType.WebCookie, UiStrings.WebCookieLogin, priority = 10),
            LoginMethodSpec(LoginMethodType.CredentialImport, UiStrings.ImportSessionCookies),
        )

    private val instance: UiInstance
        get() =
            UiInstance(
                name = metadata.displayName,
                domain = INSTAGRAM_HOST,
                platformId = platformId,
                platformDisplayName = metadata.displayName,
                platformIcon = metadata.icon,
                description = null,
                iconUrl = null,
                bannerUrl = null,
                usersCount = 0,
            )

    override fun agreementUrl(host: String): String = "https://www.instagram.com/legal/terms/"

    override suspend fun recommendInstances(): List<RecommendedInstance> = listOf(RecommendedInstance(instance, priority = 70))

    override suspend fun instanceMetadata(host: String): UiInstanceMetadata =
        UiInstanceMetadata(
            instance = instance,
            rules = persistentMapOf(),
            configuration =
                UiInstanceMetadata.Configuration(
                    registration = UiInstanceMetadata.Configuration.Registration(enabled = false),
                    statuses = UiInstanceMetadata.Configuration.Statuses(0, 0),
                    mediaAttachment = UiInstanceMetadata.Configuration.MediaAttachment(0, 0, persistentListOf()),
                    poll = UiInstanceMetadata.Configuration.Poll(0, 0, 0, 0),
                ),
        )

    override fun createHandler(context: LoginContext): LoginMethodHandler {
        require(context.host.equals(INSTAGRAM_HOST, true) || context.host.equals(INSTAGRAM_WEB_HOST, true))
        require(methods.any { it.type == context.methodType })
        return InstagramWebCookieLoginHandler(context)
    }
}

internal data object InstagramPlatformDetector : PlatformDetector {
    override val priority: Int = 110

    override suspend fun detect(host: String): NodeDetection? =
        if (host.equals(INSTAGRAM_HOST, true) || host.equals(INSTAGRAM_WEB_HOST, true)) {
            NodeDetection(host = INSTAGRAM_HOST, software = "Instagram", compatibleMode = false)
        } else {
            null
        }
}
