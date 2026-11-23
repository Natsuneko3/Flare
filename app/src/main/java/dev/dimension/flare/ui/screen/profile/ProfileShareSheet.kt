package dev.dimension.flare.ui.screen.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.component.profile.ProfileShareContent
import dev.dimension.flare.ui.model.takeSuccess
import dev.dimension.flare.ui.presenter.invoke
import dev.dimension.flare.ui.presenter.profile.ProfileSharePresenter
import dev.dimension.flare.ui.screen.share.ShareCardSheet
import moe.tlaster.precompose.molecule.producePresenter

@Composable
internal fun ProfileShareSheet(
    userKey: MicroBlogKey,
    accountType: AccountType,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by producePresenter("profile_share_sheet_${accountType}_$userKey") {
        remember(accountType, userKey) {
            ProfileSharePresenter(accountType = accountType, userKey = userKey)
        }.invoke()
    }
    val profile = state.profile.takeSuccess()

    ShareCardSheet(
        isReady = profile != null,
        shareUrl = state.shareUrl,
        fileNamePrefix = "profile",
        fileKey = userKey.toString(),
        imageLoadingKey = profile,
        onBack = onBack,
        modifier = modifier,
    ) {
        ProfileShareContent(profile = profile)
    }
}
