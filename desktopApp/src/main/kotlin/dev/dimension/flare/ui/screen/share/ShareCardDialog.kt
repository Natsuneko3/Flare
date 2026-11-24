package dev.dimension.flare.ui.screen.share

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.Download
import compose.icons.fontawesomeicons.solid.Image
import compose.icons.fontawesomeicons.solid.Link
import compose.icons.fontawesomeicons.solid.Retweet
import dev.dimension.flare.Res
import dev.dimension.flare.cancel
import dev.dimension.flare.common.DesktopSaveDialog
import dev.dimension.flare.copied_to_clipboard
import dev.dimension.flare.settings_appearance_layout_group_title
import dev.dimension.flare.settings_appearance_theme
import dev.dimension.flare.settings_appearance_theme_dark
import dev.dimension.flare.settings_appearance_theme_light
import dev.dimension.flare.settings_appearance_timeline_display_mode_card
import dev.dimension.flare.share_replies_preparing
import dev.dimension.flare.share_replies_render_failed
import dev.dimension.flare.share_replies_too_large
import dev.dimension.flare.status_share
import dev.dimension.flare.status_share_crosspost
import dev.dimension.flare.status_share_crosspost_failed
import dev.dimension.flare.status_share_image
import dev.dimension.flare.status_share_save_screenshot
import dev.dimension.flare.status_share_sheet_preview_content
import dev.dimension.flare.ui.component.ComposeInAppNotification
import dev.dimension.flare.ui.component.FAIcon
import dev.dimension.flare.ui.component.LocalShareImageLoading
import dev.dimension.flare.ui.component.ShareImageLoading
import dev.dimension.flare.ui.component.ViewBox
import dev.dimension.flare.ui.presenter.status.StatusShareImageLimits
import dev.dimension.flare.ui.theme.FlareTheme
import dev.dimension.flare.ui.theme.LocalComposeWindow
import dev.dimension.flare.ui.theme.screenHorizontalPadding
import io.github.composefluent.FluentTheme
import io.github.composefluent.LocalContentColor
import io.github.composefluent.LocalTextStyle
import io.github.composefluent.background.Layer
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Button
import io.github.composefluent.component.FluentDialog
import io.github.composefluent.component.LiteFilter
import io.github.composefluent.component.PillButton
import io.github.composefluent.component.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

internal enum class SharePreviewTheme {
    Light,
    Dark,
}

internal enum class SharePreviewStyle {
    Card,
    Content,
}

internal fun normalizeShareUrl(shareUrl: String?): String? = shareUrl?.trim()?.takeIf { it.isNotEmpty() }

internal fun crossPostInitialText(shareUrl: String?): String = normalizeShareUrl(shareUrl)?.let { "\n\n$it" }.orEmpty()

@Composable
internal fun ShareCardPreviewFrame(
    previewStyle: SharePreviewStyle,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .background(FluentTheme.colors.background.mica.base),
    ) {
        when (previewStyle) {
            SharePreviewStyle.Card -> {
                val cardShape = RoundedCornerShape(12.dp)
                Layer(
                    modifier =
                        Modifier
                            .padding(64.dp)
                            .width(360.dp),
                    shape = cardShape,
                    elevation = 32.dp,
                ) {
                    Box(modifier = Modifier.clip(cardShape)) {
                        content()
                    }
                }
            }

            SharePreviewStyle.Content -> {
                Box(
                    modifier =
                        Modifier
                            .width(360.dp)
                            .background(FluentTheme.colors.background.layer.default),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
internal fun ShareCardDialog(
    isReady: Boolean,
    shareUrl: String?,
    fileName: String,
    imageLoadingKey: Any?,
    onBack: () -> Unit,
    onCrossPost: (File) -> Unit,
    extraOptions: @Composable ColumnScope.(enabled: Boolean) -> Unit = {},
    extraActionButtons: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val inAppNotification: ComposeInAppNotification = koinInject()
    val window = LocalComposeWindow.current
    val scope = rememberCoroutineScope()
    val previewGraphicsLayer = rememberGraphicsLayer()
    var previewTheme by remember { mutableStateOf(SharePreviewTheme.Light) }
    var previewStyle by remember { mutableStateOf(SharePreviewStyle.Card) }
    var isPreparingCrossPost by remember { mutableStateOf(false) }
    var isPreparingImage by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<StringResource?>(null) }
    var imageAttempt by remember { mutableStateOf(0) }
    val normalizedShareUrl = normalizeShareUrl(shareUrl)
    val imageLoading = remember(imageLoadingKey, previewTheme, previewStyle, imageAttempt) { ShareImageLoading() }
    val controlsEnabled = !isPreparingImage && !isPreparingCrossPost

    suspend fun captureImage(): ImageBitmap? {
        if (isPreparingImage) return null
        isPreparingImage = true
        imageError = null
        try {
            repeat(2) { withFrameNanos { } }
            imageLoading.awaitReady()
            repeat(2) { withFrameNanos { } }
            if (!StatusShareImageLimits.canRender(previewGraphicsLayer.size.width, previewGraphicsLayer.size.height)) {
                imageError = Res.string.share_replies_too_large
                return null
            }
            return capturePreviewImage(previewGraphicsLayer).also {
                if (it == null) imageError = Res.string.share_replies_render_failed
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            imageError = Res.string.share_replies_render_failed
            imageAttempt++
            return null
        } finally {
            isPreparingImage = false
        }
    }

    FluentDialog(
        visible = true,
    ) {
        Column(
            modifier =
                modifier
                    .padding(horizontal = screenHorizontalPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.weight(1f),
            ) {
                FlareTheme(
                    isDarkTheme = previewTheme == SharePreviewTheme.Dark,
                ) {
                    ViewBox(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Box {
                            Box(
                                modifier =
                                    Modifier.drawWithContent {
                                        previewGraphicsLayer.record {
                                            this@drawWithContent.drawContent()
                                        }
                                        drawContent()
                                    },
                            ) {
                                ShareCardPreviewFrame(previewStyle = previewStyle) {
                                    CompositionLocalProvider(
                                        LocalShareImageLoading provides imageLoading,
                                        LocalContentColor provides FluentTheme.colors.text.text.primary,
                                        LocalTextStyle provides LocalTextStyle.current.copy(Color.Unspecified),
                                    ) {
                                        key(imageLoading) {
                                            content()
                                        }
                                    }
                                }
                            }
                            Box(
                                modifier =
                                    Modifier
                                        .matchParentSize()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = {},
                                        ),
                            )
                        }
                    }
                }
            }
            Layer(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.settings_appearance_layout_group_title))
                        Spacer(Modifier.weight(1f))
                        LiteFilter {
                            PillButton(
                                selected = previewStyle == SharePreviewStyle.Card,
                                onSelectedChanged = { if (controlsEnabled) previewStyle = SharePreviewStyle.Card },
                            ) {
                                Text(stringResource(Res.string.settings_appearance_timeline_display_mode_card))
                            }
                            PillButton(
                                selected = previewStyle == SharePreviewStyle.Content,
                                onSelectedChanged = { if (controlsEnabled) previewStyle = SharePreviewStyle.Content },
                            ) {
                                Text(stringResource(Res.string.status_share_sheet_preview_content))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.settings_appearance_theme))
                        Spacer(Modifier.weight(1f))
                        LiteFilter {
                            PillButton(
                                selected = previewTheme == SharePreviewTheme.Light,
                                onSelectedChanged = { if (controlsEnabled) previewTheme = SharePreviewTheme.Light },
                            ) {
                                Text(stringResource(Res.string.settings_appearance_theme_light))
                            }
                            PillButton(
                                selected = previewTheme == SharePreviewTheme.Dark,
                                onSelectedChanged = { if (controlsEnabled) previewTheme = SharePreviewTheme.Dark },
                            ) {
                                Text(stringResource(Res.string.settings_appearance_theme_dark))
                            }
                        }
                    }
                    extraOptions(controlsEnabled)
                }
            }
            if (isPreparingImage) Text(stringResource(Res.string.share_replies_preparing))
            imageError?.let { Text(stringResource(it)) }
            FlowRow(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AccentButton(
                    onClick = {
                        isPreparingCrossPost = true
                        scope.launch {
                            val image = captureImage()
                            val file =
                                image?.let {
                                    writeCrossPostImage(
                                        image = it,
                                        fileName = fileName,
                                    )
                                }
                            if (file == null) {
                                isPreparingCrossPost = false
                                inAppNotification.message(Res.string.status_share_crosspost_failed)
                            } else {
                                onCrossPost(file)
                            }
                        }
                    },
                    disabled = isPreparingCrossPost || isPreparingImage || !isReady,
                ) {
                    FAIcon(
                        FontAwesomeIcons.Solid.Retweet,
                        contentDescription = stringResource(Res.string.status_share_crosspost),
                    )
                    Text(stringResource(Res.string.status_share_crosspost))
                }
                normalizedShareUrl?.let { url ->
                    Button(onClick = {
                        shareText(url)
                        inAppNotification.message(Res.string.copied_to_clipboard)
                    }) {
                        FAIcon(
                            FontAwesomeIcons.Solid.Link,
                            contentDescription = stringResource(Res.string.status_share),
                        )
                        Text(stringResource(Res.string.status_share))
                    }
                }
                Button(
                    disabled = isPreparingImage || isPreparingCrossPost || !isReady,
                    onClick = {
                        scope.launch {
                            val image = captureImage() ?: return@launch
                            shareImageToClipboard(image)
                            inAppNotification.message(Res.string.copied_to_clipboard)
                        }
                    },
                ) {
                    FAIcon(
                        FontAwesomeIcons.Solid.Image,
                        contentDescription = stringResource(Res.string.status_share_image),
                    )
                    Text(stringResource(Res.string.status_share_image))
                }
                Button(
                    disabled = isPreparingImage || isPreparingCrossPost || !isReady,
                    onClick = {
                        scope.launch {
                            val image = captureImage() ?: return@launch
                            saveImageWithDialog(
                                window,
                                image,
                                fileName,
                            )
                        }
                    },
                ) {
                    FAIcon(
                        FontAwesomeIcons.Solid.Download,
                        contentDescription = stringResource(Res.string.status_share_save_screenshot),
                    )
                    Text(stringResource(Res.string.status_share_save_screenshot))
                }
                extraActionButtons()
            }

            AccentButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.cancel))
            }
        }
    }
}

private suspend fun capturePreviewImage(graphicsLayer: GraphicsLayer): ImageBitmap? =
    runCatching { graphicsLayer.toImageBitmap() }.getOrNull()

private suspend fun writeCrossPostImage(
    image: ImageBitmap,
    fileName: String,
): File? {
    val bufferedImage = image.toBufferedImage()
    return withContext(Dispatchers.IO) {
        var directory: File? = null
        runCatching {
            directory = Files.createTempDirectory("flare-crosspost-").toFile()
            val file = File(directory, fileName)
            check(ImageIO.write(bufferedImage, "png", file))
            file
        }.getOrElse {
            directory?.deleteRecursively()
            null
        }
    }
}

private fun saveImageWithDialog(
    window: androidx.compose.ui.awt.ComposeWindow?,
    image: ImageBitmap,
    defaultName: String,
) {
    val target =
        DesktopSaveDialog.chooseFile(
            window = window,
            defaultName = defaultName,
        ) ?: return
    ImageIO.write(image.toBufferedImage(), "png", target)
}

private fun shareImageToClipboard(image: ImageBitmap) {
    val buffered = image.toBufferedImage()
    val selection =
        object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor

            override fun getTransferData(flavor: DataFlavor): Any {
                require(flavor == DataFlavor.imageFlavor)
                return buffered
            }
        }
    Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
}

internal fun shareText(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(
        StringSelection(text),
        null,
    )
}

private fun ImageBitmap.toBufferedImage(): BufferedImage {
    val awt = this.toAwtImage()
    val buffered =
        BufferedImage(awt.getWidth(null), awt.getHeight(null), BufferedImage.TYPE_INT_ARGB)
    val graphics = buffered.createGraphics()
    graphics.drawImage(awt, 0, 0, null)
    graphics.dispose()
    return buffered
}
