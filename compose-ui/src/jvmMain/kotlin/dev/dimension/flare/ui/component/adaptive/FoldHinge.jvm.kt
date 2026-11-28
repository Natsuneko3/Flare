package dev.dimension.flare.ui.component.adaptive

import androidx.compose.runtime.Composable

@Composable
public actual fun rememberActiveFoldHinge(): FoldHinge? = LocalFoldHingeOverride.current
