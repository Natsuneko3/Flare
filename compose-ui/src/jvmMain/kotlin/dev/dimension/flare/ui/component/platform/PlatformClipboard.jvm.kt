package dev.dimension.flare.ui.component.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.StringSelection

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun plainTextClipEntry(
    text: String,
    label: String?,
): ClipEntry = ClipEntry(StringSelection(text))
