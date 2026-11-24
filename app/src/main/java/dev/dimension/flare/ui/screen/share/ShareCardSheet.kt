package dev.dimension.flare.ui.screen.share

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.nativePaint
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.CircleExclamation
import compose.icons.fontawesomeicons.solid.Download
import compose.icons.fontawesomeicons.solid.Image
import compose.icons.fontawesomeicons.solid.Link
import compose.icons.fontawesomeicons.solid.Retweet
import dev.dimension.flare.R
import dev.dimension.flare.common.AndroidDownloadManager
import dev.dimension.flare.common.MediaFileNamePolicy
import dev.dimension.flare.common.ShareUrlPolicy
import dev.dimension.flare.ui.component.FAIcon
import dev.dimension.flare.ui.component.LocalNetworkImageAllowHardware
import dev.dimension.flare.ui.component.LocalShareImageLoading
import dev.dimension.flare.ui.component.ShareImageLoading
import dev.dimension.flare.ui.component.ViewBox
import dev.dimension.flare.ui.presenter.status.StatusShareImageLimits
import dev.dimension.flare.ui.screen.compose.ShortcutComposeActivity
import dev.dimension.flare.ui.theme.FlareTheme
import dev.dimension.flare.ui.theme.first
import dev.dimension.flare.ui.theme.last
import dev.dimension.flare.ui.theme.screenHorizontalPadding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

internal enum class SharePreviewTheme {
    Light,
    Dark,
}

internal enum class SharePreviewStyle {
    Card,
    Content,
}

private val SharePreviewMaxHeight = 360.dp
private val ShareCardWidth = 360.dp
private val ShareCardPadding = 64.dp
private val ShareCardShapeCornerRadius = 16.dp
private val ShareCardShadowCornerRadius = 12.dp
private val ShareCardShadowRadius = 16.dp
private val ShareCaptureWidth = ShareCardWidth + ShareCardPadding * 2
private const val SHARE_LONG_CAPTURE_HEIGHT_THRESHOLD_PX = 4096

internal fun normalizeShareUrl(shareUrl: String?): String? = ShareUrlPolicy.normalize(shareUrl)

internal fun crossPostInitialText(shareUrl: String?): String = normalizeShareUrl(shareUrl)?.let { "\n\n$it" }.orEmpty()

internal fun buildCrossPostIntent(
    context: Context,
    imageUri: Uri,
    shareUrl: String?,
): Intent {
    val normalizedShareUrl = normalizeShareUrl(shareUrl)
    return Intent(context, ShortcutComposeActivity::class.java).apply {
        action = Intent.ACTION_SEND
        type = "image/png"
        if (normalizedShareUrl != null) {
            putExtra(Intent.EXTRA_TEXT, normalizedShareUrl)
        }
        putExtra(Intent.EXTRA_STREAM, imageUri)
        putExtra(ShortcutComposeActivity.EXTRA_INITIAL_TEXT, crossPostInitialText(normalizedShareUrl))
        putExtra(ShortcutComposeActivity.EXTRA_INITIAL_CURSOR_POSITION, 0)
        clipData = ClipData.newUri(context.contentResolver, "Cross-post image", imageUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ShareCardSheet(
    isReady: Boolean,
    shareUrl: String?,
    fileNamePrefix: String,
    fileKey: String,
    imageLoadingKey: Any?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    loadError: Throwable? = null,
    @StringRes tooLargeErrorRes: Int = R.string.share_replies_too_large,
    @StringRes renderFailedErrorRes: Int = R.string.share_replies_render_failed,
    extraActionButtons: @Composable RowScope.() -> Unit = {},
    extraOptionItem: (@Composable (enabled: Boolean) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val downloadManager = koinInject<AndroidDownloadManager>()
    val density = LocalDensity.current
    val view = LocalView.current
    val parentCompositionContext = rememberCompositionContext()
    val lifecycleOwner = LocalLifecycleOwner.current
    val savedStateRegistryOwner = LocalSavedStateRegistryOwner.current
    val viewModelStoreOwner = LocalViewModelStoreOwner.current
    val scope = rememberCoroutineScope()
    val previewGraphicsLayer = rememberGraphicsLayer()
    var captureRequested by remember { mutableStateOf(false) }
    var isCrossPosting by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var captureFailed by remember { mutableStateOf(false) }
    var imageAttempt by remember { mutableIntStateOf(0) }
    var previewContentHeightPx by remember { mutableIntStateOf(0) }
    var previewTheme by remember { mutableStateOf(SharePreviewTheme.Light) }
    var previewStyle by remember { mutableStateOf(SharePreviewStyle.Card) }
    val normalizedShareUrl = normalizeShareUrl(shareUrl)
    val imageLoading = remember(imageLoadingKey, previewTheme, previewStyle, imageAttempt) { ShareImageLoading() }
    val shareCaptureWidthPx =
        with(density) {
            when (previewStyle) {
                SharePreviewStyle.Card -> ShareCaptureWidth
                SharePreviewStyle.Content -> ShareCardWidth
            }.roundToPx()
        }

    val captureFits = StatusShareImageLimits.canRender(shareCaptureWidthPx, previewContentHeightPx)
    val actionsEnabled = isReady && loadError == null && !isCapturing && !isCrossPosting && captureFits
    val controlsEnabled = !isCapturing && !isCrossPosting && loadError == null

    suspend fun captureBitmap(): Bitmap? {
        if (isCapturing || !captureFits) return null
        isCapturing = true
        captureFailed = false
        try {
            withFrameNanos { }
            imageLoading.awaitReady()
            val isLongCapture =
                previewContentHeightPx > SHARE_LONG_CAPTURE_HEIGHT_THRESHOLD_PX
            return if (isLongCapture) {
                captureOffscreenShareBitmap(
                    context = context,
                    view = view,
                    parentCompositionContext = parentCompositionContext,
                    lifecycleOwner = lifecycleOwner,
                    savedStateRegistryOwner = savedStateRegistryOwner,
                    viewModelStoreOwner = viewModelStoreOwner,
                    widthPx = shareCaptureWidthPx,
                ) {
                    CompositionLocalProvider(
                        LocalNetworkImageAllowHardware provides false,
                    ) {
                        FlareTheme(
                            darkTheme = previewTheme == SharePreviewTheme.Dark,
                        ) {
                            ShareCardContainer(
                                previewStyle = previewStyle,
                                content = content,
                            )
                        }
                    }
                }
            } else {
                capturePreviewShareBitmap(
                    graphicsLayer = previewGraphicsLayer,
                    onCaptureRequestedChange = { captureRequested = it },
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            captureFailed = true
            imageAttempt++
            return null
        } finally {
            isCapturing = false
        }
    }

    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = screenHorizontalPadding)
                .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loadError != null) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FAIcon(
                    imageVector = FontAwesomeIcons.Solid.CircleExclamation,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = loadError.message?.takeIf { it.isNotBlank() } ?: stringResource(renderFailedErrorRes),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            key(imageLoading) {
                ShareCardPreview(
                    imageLoading = imageLoading,
                    previewTheme = previewTheme,
                    previewStyle = previewStyle,
                    captureRequested = captureRequested,
                    previewGraphicsLayer = previewGraphicsLayer,
                    onContentHeightChanged = {
                        previewContentHeightPx = it
                    },
                    content = content,
                )
            }
        }

        if (loadError == null) {
            if (isCapturing) Text(stringResource(R.string.share_replies_preparing))
            if (previewContentHeightPx > 0 && !captureFits) Text(stringResource(tooLargeErrorRes))
            if (captureFailed) Text(stringResource(renderFailedErrorRes))
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                enabled = actionsEnabled,
                onClick = {
                    scope.launch {
                        isCrossPosting = true
                        try {
                            val bitmap = captureBitmap()
                            val uri =
                                bitmap?.let {
                                    createShareImageUri(
                                        context = context,
                                        bitmap = it,
                                        fileNamePrefix = fileNamePrefix,
                                        fileKey = fileKey,
                                    )
                                }
                            if (uri == null) {
                                Toast
                                    .makeText(
                                        context,
                                        R.string.status_share_crosspost_failed,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                return@launch
                            }

                            val intent =
                                buildCrossPostIntent(
                                    context = context,
                                    imageUri = uri,
                                    shareUrl = normalizedShareUrl,
                                )
                            if (runCatching { context.startActivity(intent) }.isFailure) {
                                Toast
                                    .makeText(
                                        context,
                                        R.string.status_share_crosspost_failed,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                return@launch
                            }
                            onBack()
                        } finally {
                            isCrossPosting = false
                        }
                    }
                },
            ) {
                if (isCrossPosting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    FAIcon(
                        FontAwesomeIcons.Solid.Retweet,
                        contentDescription = stringResource(id = R.string.status_share_crosspost),
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = stringResource(id = R.string.status_share_crosspost))
            }

            if (normalizedShareUrl != null) {
                FilledTonalButton(
                    onClick = {
                        shareText(context = context, content = normalizedShareUrl)
                        onBack()
                    },
                ) {
                    FAIcon(
                        FontAwesomeIcons.Solid.Link,
                        contentDescription = stringResource(id = R.string.rss_detail_share),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = stringResource(id = R.string.rss_detail_share))
                }
            }
            FilledTonalButton(
                enabled = actionsEnabled,
                onClick = {
                    scope.launch {
                        val bitmap = captureBitmap()
                        if (bitmap == null) {
                            Toast
                                .makeText(context, R.string.media_save_fail, Toast.LENGTH_SHORT)
                                .show()
                            return@launch
                        }
                        val imageFile =
                            shareBitmapAsImage(
                                context = context,
                                bitmap = bitmap,
                                fileNamePrefix = fileNamePrefix,
                                fileKey = fileKey,
                            )
                        if (imageFile == null) {
                            Toast
                                .makeText(context, R.string.media_save_fail, Toast.LENGTH_SHORT)
                                .show()
                            return@launch
                        }
                        val uri =
                            FileProvider.getUriForFile(
                                context,
                                context.packageName + ".provider",
                                imageFile,
                            )
                        val intent =
                            Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_STREAM, uri)
                                setDataAndType(uri, "image/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        context.startActivity(
                            Intent.createChooser(
                                intent,
                                @SuppressLint("LocalContextGetResourceValueCall")
                                context.getString(R.string.media_menu_share_image),
                            ),
                        )
                        onBack()
                    }
                },
            ) {
                FAIcon(
                    FontAwesomeIcons.Solid.Image,
                    contentDescription = stringResource(id = R.string.media_menu_share_image),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = stringResource(id = R.string.media_menu_share_image))
            }

            FilledTonalButton(
                enabled = actionsEnabled,
                onClick = {
                    scope.launch {
                        val bitmap = captureBitmap()
                        if (bitmap == null) {
                            Toast
                                .makeText(context, R.string.media_save_fail, Toast.LENGTH_SHORT)
                                .show()
                            return@launch
                        }
                        saveBitmapToDownloads(
                            context = context,
                            downloadManager = downloadManager,
                            bitmap = bitmap,
                            fileNamePrefix = fileNamePrefix,
                            fileKey = fileKey,
                        )
                    }
                },
            ) {
                FAIcon(
                    FontAwesomeIcons.Solid.Download,
                    contentDescription = stringResource(id = R.string.media_menu_save),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = stringResource(id = R.string.media_menu_save))
            }

            extraActionButtons()
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            SharePreviewOptionRow(
                title = stringResource(id = R.string.settings_appearance_layout_group_title),
                entries = SharePreviewStyle.entries,
                selected = previewStyle,
                enabled = controlsEnabled,
                shapes = ListItemDefaults.first(),
                onSelect = { previewStyle = it },
                label = { value ->
                    when (value) {
                        SharePreviewStyle.Card -> stringResource(R.string.settings_appearance_timeline_display_mode_card)
                        SharePreviewStyle.Content -> stringResource(R.string.status_share_sheet_preview_content)
                    }
                },
            )
            SharePreviewOptionRow(
                title = stringResource(id = R.string.status_share_sheet_theme_title),
                entries = SharePreviewTheme.entries,
                selected = previewTheme,
                enabled = controlsEnabled,
                shapes = if (extraOptionItem != null) ListItemDefaults.segmentedShapes(1, 3) else ListItemDefaults.last(),
                onSelect = { previewTheme = it },
                label = { value ->
                    when (value) {
                        SharePreviewTheme.Light -> stringResource(R.string.settings_appearance_theme_light)
                        SharePreviewTheme.Dark -> stringResource(R.string.settings_appearance_theme_dark)
                    }
                },
            )
            extraOptionItem?.invoke(controlsEnabled)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> SharePreviewOptionRow(
    title: String,
    entries: List<T>,
    selected: T,
    enabled: Boolean,
    shapes: ListItemShapes,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
) {
    SegmentedListItem(
        onClick = {},
        modifier = modifier,
        shapes = shapes,
        content = {
            Text(text = title)
        },
        trailingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                entries.forEachIndexed { index, value ->
                    ToggleButton(
                        enabled = enabled,
                        checked = selected == value,
                        onCheckedChange = {
                            if (it) onSelect(value)
                        },
                        shapes =
                            when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                    ) {
                        Text(text = label(value))
                    }
                }
            }
        },
    )
}

@Composable
private fun ShareCardPreview(
    imageLoading: ShareImageLoading,
    previewTheme: SharePreviewTheme,
    previewStyle: SharePreviewStyle,
    captureRequested: Boolean,
    previewGraphicsLayer: GraphicsLayer,
    onContentHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    FlareTheme(
        darkTheme = previewTheme == SharePreviewTheme.Dark,
    ) {
        ViewBox(
            modifier =
                modifier
                    .sizeIn(maxHeight = SharePreviewMaxHeight)
                    .verticalScroll(rememberScrollState()),
        ) {
            Box(
                modifier =
                    Modifier
                        .onSizeChanged {
                            onContentHeightChanged(it.height)
                        }.drawWithContent {
                            if (captureRequested) {
                                previewGraphicsLayer.record {
                                    this@drawWithContent.drawContent()
                                }
                            }
                            drawContent()
                        },
            ) {
                CompositionLocalProvider(LocalShareImageLoading provides imageLoading) {
                    ShareCardContainer(
                        previewStyle = previewStyle,
                        blockInteractions = true,
                        content = content,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShareCardContainer(
    previewStyle: SharePreviewStyle,
    modifier: Modifier = Modifier,
    blockInteractions: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
    ) {
        val cardShape = RoundedCornerShape(ShareCardShapeCornerRadius)
        val cardContent: @Composable () -> Unit = {
            Box(
                modifier =
                    if (previewStyle == SharePreviewStyle.Card) {
                        Modifier.clip(cardShape)
                    } else {
                        Modifier
                    },
            ) {
                content()
                if (blockInteractions) {
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
        when (previewStyle) {
            SharePreviewStyle.Card -> {
                Surface(
                    modifier =
                        Modifier
                            .padding(ShareCardPadding)
                            .width(ShareCardWidth)
                            .captureableShadow(
                                cornerRadius = ShareCardShadowCornerRadius,
                                shadowRadius = ShareCardShadowRadius,
                            ),
                    shape = cardShape,
                    content = cardContent,
                )
            }

            SharePreviewStyle.Content -> {
                Surface(
                    modifier = Modifier.width(ShareCardWidth),
                    content = cardContent,
                )
            }
        }
    }
}

internal fun shareText(
    context: Context,
    content: String,
) {
    val sendIntent =
        Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, content)
            type = "text/plain"
        }
    context.startActivity(Intent.createChooser(sendIntent, null))
}

private suspend fun capturePreviewShareBitmap(
    graphicsLayer: GraphicsLayer,
    onCaptureRequestedChange: (Boolean) -> Unit,
): Bitmap? =
    try {
        onCaptureRequestedChange(true)
        withFrameNanos { }
        withFrameNanos { }
        runCatching {
            check(StatusShareImageLimits.canRender(graphicsLayer.size.width, graphicsLayer.size.height))
            graphicsLayer.toImageBitmap().asAndroidBitmap()
        }.getOrNull()
    } finally {
        onCaptureRequestedChange(false)
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
    runCatching {
        val themedContext = ContextThemeWrapper(context, context.theme)
        val captureHost =
            checkNotNull(view.findCaptureHostView()) {
                "Unable to find a host view for share capture"
            }
        val loading = ShareImageLoading()
        val composeView =
            ComposeView(themedContext).apply {
                setParentCompositionContext(parentCompositionContext)
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
                viewModelStoreOwner?.let {
                    setViewTreeViewModelStoreOwner(it)
                }
                setContent {
                    CompositionLocalProvider(LocalShareImageLoading provides loading) { content() }
                }
            }
        val container =
            FrameLayout(themedContext).apply {
                alpha = 0f
                translationX = -10_000f
                clipChildren = false
                clipToPadding = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(
                    composeView,
                    FrameLayout.LayoutParams(
                        widthPx,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        try {
            captureHost.addView(
                container,
                ViewGroup.LayoutParams(
                    widthPx,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

            repeat(2) {
                withFrameNanos { }
            }

            val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

            repeat(2) {
                container.measure(widthSpec, heightSpec)
                container.layout(
                    -container.measuredWidth * 2,
                    0,
                    -container.measuredWidth,
                    container.measuredHeight,
                )
                withFrameNanos { }
            }

            loading.awaitReady()
            withFrameNanos { }
            container.measure(widthSpec, heightSpec)
            container.layout(-container.measuredWidth * 2, 0, -container.measuredWidth, container.measuredHeight)
            val captureWidth = composeView.measuredWidth
            val captureHeight = composeView.measuredHeight

            check(StatusShareImageLimits.canRender(captureWidth, captureHeight)) {
                "Unable to measure share content"
            }

            createBitmap(captureWidth, captureHeight).also { bitmap ->
                composeView.draw(Canvas(bitmap))
            }
        } finally {
            captureHost.removeView(container)
            composeView.disposeComposition()
        }
    }.onFailure {
        it.printStackTrace()
    }.getOrNull()

private fun View.findCaptureHostView(): ViewGroup? =
    rootView.findViewById<ViewGroup?>(android.R.id.content)
        ?: rootView as? ViewGroup
        ?: parent as? ViewGroup

private suspend fun saveBitmapToDownloads(
    context: Context,
    downloadManager: AndroidDownloadManager,
    bitmap: Bitmap,
    fileNamePrefix: String,
    fileKey: String,
) {
    val bytes =
        ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
    val fileName = MediaFileNamePolicy.screenshotFileName(key = fileKey, prefix = fileNamePrefix)
    val success =
        downloadManager.saveByteArray(
            byteArray = bytes,
            fileName = fileName,
            mimeType = "image/png",
        )
    Toast
        .makeText(
            context,
            context.getString(
                if (success) {
                    R.string.media_save_success
                } else {
                    R.string.media_save_fail
                },
            ),
            Toast.LENGTH_SHORT,
        ).show()
}

private fun shareBitmapAsImage(
    context: Context,
    bitmap: Bitmap,
    fileNamePrefix: String,
    fileKey: String,
): File? {
    if (bitmap.width <= 0 || bitmap.height <= 0) {
        return null
    }
    val file =
        File(
            context.cacheDir,
            "${fileNamePrefix}_share_${MediaFileNamePolicy.sanitizeFileName(fileKey)}_${System.currentTimeMillis()}.png",
        )
    FileOutputStream(file).use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    return file
}

private fun createShareImageUri(
    context: Context,
    bitmap: Bitmap,
    fileNamePrefix: String,
    fileKey: String,
) = runCatching {
    val imageFile = shareBitmapAsImage(context, bitmap, fileNamePrefix, fileKey) ?: return@runCatching null
    FileProvider.getUriForFile(
        context,
        context.packageName + ".provider",
        imageFile,
    )
}.getOrNull()

private fun Modifier.captureableShadow(
    color: Color = Color.Black,
    cornerRadius: Dp = 0.dp,
    shadowRadius: Dp = 8.dp,
    offsetY: Dp = 0.dp,
    offsetX: Dp = 0.dp,
    alpha: Float = 0.2f,
) = this.drawBehind {
    val shadowColor = color.copy(alpha = alpha).toArgb()
    val transparentColor = color.copy(alpha = 0f).toArgb()

    this.drawIntoCanvas {
        val paint = Paint()
        val frameworkPaint = paint.nativePaint
        frameworkPaint.color = transparentColor

        frameworkPaint.setShadowLayer(
            shadowRadius.toPx(),
            offsetX.toPx(),
            offsetY.toPx(),
            shadowColor,
        )

        it.drawRoundRect(
            0f,
            0f,
            this.size.width,
            this.size.height,
            cornerRadius.toPx(),
            cornerRadius.toPx(),
            paint,
        )
    }
}
