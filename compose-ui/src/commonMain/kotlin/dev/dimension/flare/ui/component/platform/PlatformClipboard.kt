package dev.dimension.flare.ui.component.platform

import androidx.compose.ui.platform.ClipEntry

internal expect fun plainTextClipEntry(
    text: String,
    label: String? = null,
): ClipEntry
