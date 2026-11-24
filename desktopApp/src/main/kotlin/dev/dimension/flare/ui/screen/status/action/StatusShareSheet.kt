package dev.dimension.flare.ui.screen.status.action

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.dimension.flare.Res
import dev.dimension.flare.common.MediaFileNamePolicy
import dev.dimension.flare.copied_to_clipboard
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.share_replies_count
import dev.dimension.flare.share_replies_title
import dev.dimension.flare.status_share_via_fixvx
import dev.dimension.flare.status_share_via_fxembed
import dev.dimension.flare.ui.component.ComposeInAppNotification
import dev.dimension.flare.ui.component.status.StatusShareContent
import dev.dimension.flare.ui.model.takeSuccess
import dev.dimension.flare.ui.presenter.invoke
import dev.dimension.flare.ui.presenter.status.StatusSharePresenter
import dev.dimension.flare.ui.screen.share.ShareCardDialog
import dev.dimension.flare.ui.screen.share.shareText
import io.github.composefluent.component.Button
import io.github.composefluent.component.Text
import moe.tlaster.precompose.molecule.producePresenter
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import java.io.File

@Composable
internal fun StatusShareSheet(
    accountType: AccountType,
    statusKey: MicroBlogKey,
    shareUrl: String,
    fxShareUrl: String?,
    fixvxShareUrl: String?,
    onBack: () -> Unit,
    onCrossPost: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    val inAppNotification: ComposeInAppNotification = koinInject()
    val state by producePresenter("DesktopStatusShareSheet_${accountType}_$statusKey") {
        remember(accountType, statusKey) {
            StatusSharePresenter(accountType = accountType, statusKey = statusKey)
        }.invoke()
    }
    if (state.selecting) {
        StatusShareReplyDialog(state)
        return
    }
    val status = state.status.takeSuccess()
    ShareCardDialog(
        isReady = status != null,
        shareUrl = shareUrl,
        fileName = "status_${MediaFileNamePolicy.sanitizeFileName(statusKey.toString())}.png",
        imageLoadingKey = status to state.selectedReplies,
        onBack = onBack,
        onCrossPost = onCrossPost,
        extraOptions = { enabled ->
            Button(
                onClick = state::selectReplies,
                disabled = !enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.share_replies_title))
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(Res.string.share_replies_count, state.selectedCount))
                }
            }
        },
        extraActionButtons = {
            if (fxShareUrl != null) {
                Button(onClick = {
                    shareText(fxShareUrl)
                    inAppNotification.message(Res.string.copied_to_clipboard)
                }) {
                    Text(stringResource(Res.string.status_share_via_fxembed))
                }
            }
            if (fixvxShareUrl != null) {
                Button(onClick = {
                    shareText(fixvxShareUrl)
                    inAppNotification.message(Res.string.copied_to_clipboard)
                }) {
                    Text(stringResource(Res.string.status_share_via_fixvx))
                }
            }
        },
        modifier = modifier,
    ) {
        StatusShareContent(
            status = status,
            statusKey = statusKey,
            replies = state.selectedReplies,
        )
    }
}
