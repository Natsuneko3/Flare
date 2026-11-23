package dev.dimension.flare.ui.component.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import dev.dimension.flare.ui.component.CommonProfileHeader
import dev.dimension.flare.ui.component.MatricesDisplay
import dev.dimension.flare.ui.component.ProfileBottomContent
import dev.dimension.flare.ui.component.ProfileHeaderLoading
import dev.dimension.flare.ui.component.ProfileMarksAndTranslation
import dev.dimension.flare.ui.component.RichText
import dev.dimension.flare.ui.component.platform.PlatformTextStyle
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.theme.PlatformTheme

private object NoopUriHandler : UriHandler {
    override fun openUri(uri: String) = Unit
}

@Composable
public fun ProfileShareContent(
    profile: UiProfile?,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(
        PlatformTextStyle provides PlatformTheme.typography.body,
        LocalUriHandler provides NoopUriHandler,
    ) {
        if (profile == null) {
            ProfileHeaderLoading(
                withStatusBarHeight = false,
                modifier = modifier,
            )
        } else {
            CommonProfileHeader(
                banner = profile.banner,
                avatar = profile.avatar,
                displayName = profile.name,
                userKey = profile.key,
                handle = profile.handle,
                modifier = modifier,
                useCard = false,
                includeStatusBarInset = false,
                onAvatarClick = null,
                onBannerClick = null,
                headerTrailing = {},
                handleTrailing = {
                    ProfileMarksAndTranslation(profile)
                },
                content = {
                    profile.description?.let {
                        RichText(text = it)
                    }
                    ProfileBottomContent(profile.bottomContent)
                    MatricesDisplay(
                        data = profile.matrices,
                        expanded = false,
                        onClicked = {},
                    )
                },
            )
        }
    }
}
