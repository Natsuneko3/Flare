package dev.dimension.flare.ui.screen.status.action

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.AngleRight
import dev.dimension.flare.R
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.component.FAIcon
import dev.dimension.flare.ui.component.status.StatusShareContent
import dev.dimension.flare.ui.model.takeSuccess
import dev.dimension.flare.ui.presenter.invoke
import dev.dimension.flare.ui.presenter.status.StatusSharePresenter
import dev.dimension.flare.ui.screen.share.ShareCardSheet
import dev.dimension.flare.ui.screen.share.shareText
import dev.dimension.flare.ui.theme.last
import moe.tlaster.precompose.molecule.producePresenter

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StatusShareSheet(
    statusKey: MicroBlogKey,
    accountType: AccountType,
    shareUrl: String,
    fxShareUrl: String?,
    fixvxShareUrl: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by producePresenter("status_share_sheet_${statusKey}_$shareUrl") {
        remember(accountType, statusKey) {
            StatusSharePresenter(accountType = accountType, statusKey = statusKey)
        }.invoke()
    }
    val status = state.status.takeSuccess()

    if (state.selecting) {
        StatusShareReplySheet(state)
    }

    ShareCardSheet(
        isReady = status != null,
        shareUrl = shareUrl,
        fileNamePrefix = "status",
        fileKey = statusKey.toString(),
        imageLoadingKey = status to state.selectedReplies,
        onBack = onBack,
        extraActionButtons = {
            if (fxShareUrl != null) {
                FilledTonalButton(
                    onClick = {
                        shareText(context = context, content = fxShareUrl)
                        onBack()
                    },
                ) {
                    Text(text = stringResource(id = R.string.status_menu_share_via_fxembed))
                }
            }

            if (fixvxShareUrl != null) {
                FilledTonalButton(
                    onClick = {
                        shareText(context = context, content = fixvxShareUrl)
                        onBack()
                    },
                ) {
                    Text(text = stringResource(id = R.string.status_menu_share_via_fixvx))
                }
            }
        },
        extraOptionItem = { enabled ->
            SegmentedListItem(
                onClick = state::selectReplies,
                enabled = enabled,
                shapes = ListItemDefaults.last(),
                content = { Text(stringResource(R.string.share_replies_title)) },
                trailingContent = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.share_replies_count, state.selectedCount))
                        FAIcon(
                            FontAwesomeIcons.Solid.AngleRight,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
            )
        },
        modifier = modifier,
    ) {
        StatusShareContent(status = status, statusKey = statusKey, replies = state.selectedReplies)
    }
}

internal suspend fun captureOffscreenShareBitmap(
    context: Context,
    view: View,
    parentCompositionContext: CompositionContext,
    lifecycleOwner: LifecycleOwner,
    savedStateRegistryOwner: SavedStateRegistryOwner,
    viewModelStoreOwner: ViewModelStoreOwner?,
    widthPx: Int,
    content: @Composable () -> Unit,
): Bitmap? =
    dev.dimension.flare.ui.screen.share.captureOffscreenShareBitmap(
        context = context,
        view = view,
        parentCompositionContext = parentCompositionContext,
        lifecycleOwner = lifecycleOwner,
        savedStateRegistryOwner = savedStateRegistryOwner,
        viewModelStoreOwner = viewModelStoreOwner,
        widthPx = widthPx,
        content = content,
    )
