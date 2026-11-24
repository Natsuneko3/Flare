package dev.dimension.flare.ui.screen.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.dimension.flare.Res
import dev.dimension.flare.common.MediaFileNamePolicy
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.share_image_render_failed
import dev.dimension.flare.share_image_too_large
import dev.dimension.flare.ui.component.profile.ProfileShareContent
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.takeSuccess
import dev.dimension.flare.ui.presenter.invoke
import dev.dimension.flare.ui.presenter.profile.ProfileSharePresenter
import dev.dimension.flare.ui.screen.share.ShareCardDialog
import moe.tlaster.precompose.molecule.producePresenter
import java.io.File

@Composable
internal fun ProfileShareSheet(
    accountType: AccountType,
    userKey: MicroBlogKey,
    onBack: () -> Unit,
    onCrossPost: (File, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by producePresenter("DesktopProfileShareSheet_${accountType}_$userKey") {
        remember(accountType, userKey) {
            ProfileSharePresenter(accountType = accountType, userKey = userKey)
        }.invoke()
    }
    val profile = state.profile.takeSuccess()
    val loadError = (state.profile as? UiState.Error)?.throwable
    ShareCardDialog(
        isReady = profile != null,
        shareUrl = state.shareUrl,
        fileName = "profile_${MediaFileNamePolicy.sanitizeFileName(userKey.toString())}.png",
        imageLoadingKey = profile,
        onBack = onBack,
        onCrossPost = { imageFile ->
            onCrossPost(imageFile, state.shareUrl)
        },
        modifier = modifier,
        loadError = loadError,
        tooLargeError = Res.string.share_image_too_large,
        renderFailedError = Res.string.share_image_render_failed,
    ) {
        ProfileShareContent(profile = profile)
    }
}
