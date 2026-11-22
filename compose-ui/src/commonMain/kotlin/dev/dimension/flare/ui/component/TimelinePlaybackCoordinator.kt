package dev.dimension.flare.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

internal val LocalTimelinePlayback = compositionLocalOf<TimelinePlaybackCoordinator?> { null }
internal val LocalTimelineCarouselItem = compositionLocalOf<TimelineCarouselItem?> { null }

internal data class TimelineCarouselItem(
    val groupId: Any,
    val mediaIndex: Int,
    val selected: Boolean,
    val isCarousel: Boolean = true,
)

internal class TimelinePlaybackCoordinator(
    val scope: CoroutineScope,
    private val arbiter: VideoPlaybackArbiter = VideoPlaybackArbiter.shared,
    private val memory: MediaPlaybackMemory = MediaPlaybackMemory.shared,
) {
    private val policy = TimelineAutoplayPolicy()
    private val candidates = linkedMapOf<Any, TimelineAutoplayPolicy.CandidateSource>()
    private val players = mutableMapOf<Any, (Boolean) -> Unit>()
    private val scrollingSources = mutableSetOf<Any>()
    private var selectionJob: Job? = null
    private var playingId: Any? = null
    private var closed = false
    private var suspended = false
    private var isPresentation = false
    private var immediateReturn = false
    var handoffUri: String? = null
        private set
    val mediaSelections = TimelineMediaSelections()
    private var viewerSelection: Pair<List<String>, Int>? = null
    private val cleanup = mutableMapOf<Any, () -> Unit>()
    var viewport: Rect? = null
        set(value) {
            if (field == value) return
            field = value
            if (candidates.isNotEmpty()) {
                // Check visibility after the children's position callbacks catch up to the viewport.
                scope.launch {
                    yield()
                    reconcile(allowStart = false)
                    scheduleSelection()
                }
            }
        }
    var multipleColumns: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (candidates.isNotEmpty()) scheduleSelection()
        }

    init {
        arbiter.register(
            this,
            ::stopCurrentPlayer,
            ::scheduleSelection,
            mediaSelections::returned,
            resume = {
                immediateReturn = true
                scheduleSelection()
            },
            willHandoff = { handoffUri = it },
        )
    }

    fun position(key: String): Double = memory.position(key)

    fun savePosition(
        key: String,
        seconds: Double,
    ) {
        memory.save(key, seconds)
    }

    fun selectMedia(
        groupId: Any,
        mediaIndex: Int,
        userInitiated: Boolean,
    ) {
        if (userInitiated) {
            immediateReturn = false
            arbiter.interacted(this)
        }
        policy.returnedToMedia(groupId, mediaIndex)
        scheduleSelection()
    }

    fun selectViewerMedia(
        urls: List<String>,
        selectedIndex: Int?,
    ) {
        val selection = selectedIndex?.takeIf { it in urls.indices }?.let { urls.toList() to it }
        if (viewerSelection == selection) return
        viewerSelection = selection
        if (isPresentation) scheduleSelection()
    }

    fun present() {
        if (isPresentation) return
        isPresentation = true
        immediateReturn = true
        arbiter.present(this, viewerSelection?.let { it.first[it.second] })
        scheduleSelection()
    }

    fun onClose(
        key: Any,
        action: () -> Unit,
    ) {
        cleanup[key] = action
    }

    fun register(
        id: Any,
        playback: (Boolean) -> Unit,
    ) {
        players[id] = playback
    }

    fun update(candidate: TimelineAutoplayPolicy.CandidateSource) {
        if (candidates[candidate.id] == candidate) return
        candidates[candidate.id] = candidate
        candidateBoundsChanged(candidate.id)
    }

    fun candidateBoundsChanged(id: Any) {
        if (id == playingId) reconcile(allowStart = false)
        scheduleSelection()
    }

    fun remove(id: Any) {
        candidates.remove(id)
        reconcile(allowStart = false)
        players.remove(id)
        scheduleSelection()
    }

    fun setScrolling(
        source: Any,
        scrolling: Boolean,
        vertical: Boolean,
    ) {
        if (scrolling) {
            immediateReturn = false
            if (scrollingSources.add(source)) {
                arbiter.interacted(this)
                if (vertical) policy.verticalScrollBegan() else policy.interactWithCarousel(source)
            }
            selectionJob?.cancel()
        } else {
            scrollingSources.remove(source)
            scheduleSelection()
        }
    }

    fun close() {
        closed = true
        selectionJob?.cancel()
        arbiter.remove(this, viewerSelection?.first.orEmpty(), viewerSelection?.second)
        stopCurrentPlayer()
        players.clear()
        candidates.clear()
        cleanup.values.toList().forEach { it() }
        cleanup.clear()
    }

    fun setSuspended(value: Boolean) {
        if (suspended == value) return
        suspended = value
        if (value) {
            selectionJob?.cancel()
            stopCurrentPlayer()
            arbiter.release(this)
        } else {
            scheduleSelection()
        }
    }

    private fun scheduleSelection() {
        if (closed || suspended || scrollingSources.isNotEmpty()) return
        selectionJob?.cancel()
        if (immediateReturn) {
            reconcile(allowStart = true)
            if (playingId != null) immediateReturn = false
            return
        }
        selectionJob =
            scope.launch {
                delay(200)
                reconcile(allowStart = true)
            }
    }

    private fun reconcile(allowStart: Boolean) {
        if (closed || suspended) return
        // Scrolling only invalidates the current item; full selection runs at idle.
        if (!allowStart || scrollingSources.isNotEmpty()) {
            if (playingId != null && candidates[playingId]?.visible != true) {
                stopCurrentPlayer()
                arbiter.release(this)
            }
            return
        }
        val selectedUri = viewerSelection?.let { it.first[it.second] }.takeIf { isPresentation }
        val eligible =
            candidates.values.mapNotNull { source ->
                source.snapshot().takeIf { selectedUri == null || it.mediaUri == selectedUri }
            }
        val next = policy.select(eligible, scrolling = false)
        if (next == null) arbiter.settledWithoutVideo(this)
        if (next == playingId) return
        if (next != null && !arbiter.acquire(this)) {
            stopCurrentPlayer()
            return
        }
        playingId?.let { players[it]?.invoke(false) }
        playingId = next
        next?.let {
            immediateReturn = false
            players[it]?.invoke(true)
        }
        if (next == null) arbiter.release(this)
    }

    private fun stopCurrentPlayer() {
        val old = playingId
        playingId = null
        old?.let { players[it]?.invoke(false) }
        handoffUri = null
        policy.select(emptyList(), scrolling = true)
    }
}

/** Keeps background timelines paused while any page of the media viewer is open. */
@Composable
public fun MediaViewerPlayback(content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val playback = remember(scope) { TimelinePlaybackCoordinator(scope) }
    CompositionLocalProvider(LocalTimelinePlayback provides playback, content = content)
    // Selection from the child is committed before preempting the timeline, so
    // the outgoing binding knows whether the same media is being handed over.
    SideEffect { playback.present() }
    DisposableEffect(playback) {
        onDispose { playback.close() }
    }
}

/** Records the last selected page for the timeline that opened this viewer. */
@Composable
public fun MediaViewerSelection(
    mediaUrls: List<String>,
    selectedIndex: Int?,
) {
    val playback = LocalTimelinePlayback.current
    SideEffect { playback?.selectViewerMedia(mediaUrls, selectedIndex) }
}

@Composable
internal fun rememberTimelinePlayback(): TimelinePlaybackCoordinator {
    val inherited = LocalTimelinePlayback.current
    val scope = rememberCoroutineScope()
    val playback = inherited ?: remember(scope) { TimelinePlaybackCoordinator(scope) }
    DisposableEffect(playback, inherited) {
        onDispose { if (inherited == null) playback.close() }
    }
    return playback
}

private class VideoGeometry {
    var bounds: Rect = Rect.Zero
    var visible: Rect = Rect.Zero

    fun clippedTo(viewport: Rect?): Rect = viewport?.let { visible.intersect(it) } ?: visible
}

@Composable
internal fun Modifier.timelineVideoAutoplay(
    playback: TimelinePlaybackCoordinator,
    id: Any,
    enabled: Boolean,
    mediaUri: String,
): Modifier {
    val item = LocalTimelineCarouselItem.current
    val geometry = remember(playback, id) { VideoGeometry() }
    val density = LocalDensity.current.density

    val candidate =
        remember(playback, id, geometry, item, enabled, mediaUri, density) {
            object : TimelineAutoplayPolicy.CandidateSource {
                override val id: Any = id
                override val visible: Boolean
                    get() = enabled && !geometry.clippedTo(playback.viewport).isEmpty

                override fun snapshot(): TimelineAutoplayPolicy.Candidate {
                    val bounds = geometry.bounds
                    val viewport = playback.viewport
                    val visible = geometry.clippedTo(viewport)
                    return TimelineAutoplayPolicy.Candidate(
                        id = id,
                        groupId = item?.groupId,
                        visible = enabled && !visible.isEmpty,
                        selected = item?.selected ?: true,
                        canStart = enabled && (item?.isCarousel != true || visible.width >= bounds.width * 0.6f),
                        distance = TimelineAutoplayPolicy.centerDistance(bounds, viewport ?: bounds, playback.multipleColumns, density),
                        mediaUri = mediaUri,
                        mediaIndex = item?.mediaIndex,
                    )
                }
            }
        }
    SideEffect { playback.update(candidate) }
    return onGloballyPositioned {
        val bounds = Rect(it.positionInWindow(), it.size.toSize())
        val visible = it.boundsInWindow()
        if (geometry.bounds != bounds || geometry.visible != visible) {
            geometry.bounds = bounds
            geometry.visible = visible
            playback.candidateBoundsChanged(id)
        }
    }
}
