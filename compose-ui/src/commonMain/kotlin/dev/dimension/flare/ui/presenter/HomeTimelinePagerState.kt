package dev.dimension.flare.ui.presenter

import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow

@Composable
public fun rememberHomeTimelinePagerState(
    tabIds: List<String>,
    initialSelectedTabId: String?,
    onSelectedTab: (String) -> Unit,
): PagerState {
    val latestOnSelectedTab by rememberUpdatedState(onSelectedTab)
    val pager =
        rememberPagerState(
            initialPage = tabIds.indexOf(initialSelectedTabId).coerceAtLeast(0),
        ) { tabIds.size }
    LaunchedEffect(pager, tabIds) {
        snapshotFlow { pager.settledPage }.collect { index ->
            tabIds.getOrNull(index)?.let(latestOnSelectedTab)
        }
    }
    return pager
}
