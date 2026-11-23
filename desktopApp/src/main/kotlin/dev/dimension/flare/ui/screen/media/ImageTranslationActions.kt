package dev.dimension.flare.ui.screen.media

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.dimension.flare.Res
import dev.dimension.flare.ok
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.presenter.media.ImageTranslationPresenter
import dev.dimension.flare.ui.screen.settings.imageTranslationText
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import java.awt.datatransfer.StringSelection

@Composable
internal fun ImageTranslationActions(
    translation: ImageTranslationPresenter.State,
    media: UiMedia,
    showErrorDialog: Boolean = true,
) {
    if (media !is UiMedia.Image) return
    val item = translation.item(media)
    Row {
        if (translation.canToggle(media)) {
            SubtleButton(
                onClick = { translation.toggle(media) },
                disabled = item.loading,
            ) {
                Text(
                    imageTranslationText(
                        if (item.showingTranslated) {
                            "Show original"
                        } else if (item.translated !=
                            null
                        ) {
                            "Show translation"
                        } else {
                            "Translate image"
                        },
                    ),
                )
            }
        }
        if (translation.canRegenerate(media)) {
            SubtleButton(
                onClick = { translation.regenerate(media) },
                disabled = item.loading,
            ) { Text(imageTranslationText("Translate again")) }
        }
    }
    if (showErrorDialog) {
        ImageTranslationErrorDialog(
            translation = translation,
            media = media,
        )
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun ImageTranslationErrorDialog(
    translation: ImageTranslationPresenter.State,
    media: UiMedia,
) {
    if (media !is UiMedia.Image) return
    val item = translation.item(media)
    if (item.error == null && !item.errorDetailsVisible) return
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val dismiss = {
        translation.clearError(media)
        translation.setErrorDetailsVisible(media, false)
    }
    ContentDialog(
        title = imageTranslationText(if (item.errorDetailsVisible) "Error details" else "Image translation"),
        visible = true,
        content = {
            val dialogFocusRequester = remember { FocusRequester() }
            LaunchedEffect(media, item.errorDetailsVisible) {
                runCatching { dialogFocusRequester.requestFocus() }
            }
            Box(
                modifier =
                    Modifier
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                dismiss()
                                true
                            } else {
                                false
                            }
                        }.onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                dismiss()
                                true
                            } else {
                                false
                            }
                        }.focusRequester(dialogFocusRequester)
                        .focusable()
                        .testTag("image_translation_error_dialog"),
            ) {
                if (item.errorDetailsVisible) {
                    SelectionContainer {
                        Text(
                            item.errorDetails.orEmpty(),
                            modifier = Modifier.padding(16.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                } else {
                    item.error?.let { Text(imageTranslationText(it.message), modifier = Modifier.padding(16.dp)) }
                }
            }
        },
        primaryButtonText = stringResource(Res.string.ok),
        secondaryButtonText =
            if (item.errorDetails != null) {
                imageTranslationText(if (item.errorDetailsVisible) "Copy error details" else "View error details")
            } else {
                ""
            },
        onButtonClick = { button ->
            if (button == ContentDialogButton.Secondary && item.errorDetails != null) {
                if (item.errorDetailsVisible) {
                    scope.launch { clipboard.setClipEntry(ClipEntry(StringSelection(item.errorDetails))) }
                } else {
                    translation.setErrorDetailsVisible(media, true)
                }
            } else {
                dismiss()
            }
        },
    )
}
