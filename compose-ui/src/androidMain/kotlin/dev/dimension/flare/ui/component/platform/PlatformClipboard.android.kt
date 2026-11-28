package dev.dimension.flare.ui.component.platform

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

internal actual fun plainTextClipEntry(
    text: String,
    label: String?,
): ClipEntry = ClipEntry(ClipData.newPlainText(label.orEmpty(), text))
