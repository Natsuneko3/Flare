import FlareAppleCore
import Foundation
import Kingfisher
@preconcurrency import KotlinSharedUI
import Observation
import SwiftUI

#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

private let mangaWideViewportBreakpoint: CGFloat = 720
private let mangaMaxConstrainedWebtoonWidth: CGFloat = 800
private let mangaLeftTapZoneFraction: CGFloat = 0.30
private let mangaRightTapZoneFraction: CGFloat = 0.70
private let mangaMaxReaderZoom: CGFloat = 4.0
private let mangaDoubleTapZoom: CGFloat = 2.5
private let mangaZoomEpsilon: CGFloat = 1.005
private let mangaDefaultWebtoonAspectRatio: CGFloat = 0.70

public nonisolated enum MangaReaderKeyInput: Equatable, Sendable {
    case leftArrow
    case rightArrow
    case upArrow
    case downArrow
    case pageUp
    case pageDown
    case home
    case end
    case space(shift: Bool)
    case escape
}

public struct MangaReaderPageFramesPreferenceKey: PreferenceKey {
    public static var defaultValue: [Int: CGRect] { [:] }

    public static func reduce(value: inout [Int: CGRect], nextValue: () -> [Int: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { _, latest in latest })
    }
}

private struct MangaReaderScrollSettledKey: EnvironmentKey {
    static let defaultValue: Bool = true
}

private struct MangaReaderScrollAlignmentRevisionKey: EnvironmentKey {
    static let defaultValue: Int = 0
}

private struct ZoomGeometryToken: Equatable {
    var scale: CGFloat = 1.0
    var offsetX: CGFloat = 0
    var offsetY: CGFloat = 0
}

private extension EnvironmentValues {
    var mangaReaderIsScrollSettled: Bool {
        get { self[MangaReaderScrollSettledKey.self] }
        set { self[MangaReaderScrollSettledKey.self] = newValue }
    }

    var mangaReaderScrollAlignmentRevision: Int {
        get { self[MangaReaderScrollAlignmentRevisionKey.self] }
        set { self[MangaReaderScrollAlignmentRevisionKey.self] = newValue }
    }
}

public struct MangaReaderRetryPageIdsPreferenceKey: PreferenceKey {
    public static var defaultValue: Set<String> { [] }

    public static func reduce(value: inout Set<String>, nextValue: () -> Set<String>) {
        value.formUnion(nextValue())
    }
}

@MainActor
@Observable
public final class MangaReaderInteractionController {
    public fileprivate(set) var isBound = false
    public fileprivate(set) var viewportSize: CGSize = .zero
    public fileprivate(set) var activePagerPage: Int?
    public fileprivate(set) var isOnPreviousBoundaryPage = false
    public fileprivate(set) var isOnNextBoundaryPage = false
    @ObservationIgnored public fileprivate(set) var activeZoomScale: CGFloat = 1.0
    @ObservationIgnored public fileprivate(set) var activeZoomOffset: CGSize = .zero
    fileprivate var activeChapterIdForPager: String?

    @ObservationIgnored var stateProvider: (() -> (any MangaReaderPresenterState)?)?
    @ObservationIgnored var hudVisibleProvider: (() -> Bool)?
    @ObservationIgnored var onToggleHudHandler: (() -> Void)?
    @ObservationIgnored var onBackHandler: (() -> Void)?
    @ObservationIgnored var isOverlayPresentedProvider: (() -> Bool)?
    @ObservationIgnored var onDismissOverlayHandler: (() -> Bool)?
    @ObservationIgnored var pagedStepForward: (() -> Void)?
    @ObservationIgnored var pagedStepBackward: (() -> Void)?
    @ObservationIgnored var pagedScrollToBoundary: ((Bool) -> Void)?
    @ObservationIgnored var pagedJumpToEdge: ((Bool) -> Void)?
    @ObservationIgnored var webtoonStepForward: (() -> Void)?
    @ObservationIgnored var webtoonStepBackward: (() -> Void)?
    @ObservationIgnored var webtoonZoomPanHandler: ((CGSize) -> Void)?
    @ObservationIgnored var zoomResetHandler: (() -> Void)?
    @ObservationIgnored var doubleTapZoomHandler: ((CGPoint) -> Void)?
    @ObservationIgnored fileprivate var isPerformingProgrammaticScroll = false
    @ObservationIgnored fileprivate var canvasID: UUID?
    @ObservationIgnored fileprivate weak var activeInteractionTracker: PagerScrollInteractionTracker?
    @ObservationIgnored fileprivate weak var activeWebtoonController: WebtoonScrollController?

    public init() {}

    var hasUnconsumedUserScroll: Bool {
        activeInteractionTracker?.hasUnconsumedUserScroll ?? false
    }

    var isOverlayPresented: Bool {
        isOverlayPresentedProvider?() == true
    }

    #if os(iOS)
    func processPanGestureStateForTesting(_ state: UIGestureRecognizer.State) {
        activeInteractionTracker?.processPanGestureState(state)
    }
    #endif

    func handleDoubleTap(at point: CGPoint) {
        doubleTapZoomHandler?(point)
    }

    func panWebtoonZoomForTesting(_ delta: CGSize) {
        webtoonZoomPanHandler?(delta)
    }

    public func handleTap(at point: CGPoint, in containerSize: CGSize) {
        let effectiveWidth = max(containerSize.width > 0 ? containerSize.width : viewportSize.width, 1)
        let fraction = point.x / effectiveWidth
        guard let readerState = stateProvider?() else { return }
        if fraction < mangaLeftTapZoneFraction {
            if readerState.direction == .rtl {
                performStepForward(readerState)
            } else {
                performStepBackward(readerState)
            }
        } else if fraction > mangaRightTapZoneFraction {
            if readerState.direction == .rtl {
                performStepBackward(readerState)
            } else {
                performStepForward(readerState)
            }
        } else {
            onToggleHudHandler?()
        }
    }

    @discardableResult
    public func handleKey(_ key: MangaReaderKeyInput) -> Bool {
        if key == .escape {
            if onDismissOverlayHandler?() == true {
                return true
            }
            if onDismissOverlayHandler == nil && isOverlayPresentedProvider?() == true {
                return false
            }
            guard stateProvider?() != nil else { return false }
            if let zoomResetHandler {
                zoomResetHandler()
                return true
            }
            if let onBackHandler {
                onBackHandler()
                return true
            }
            if hudVisibleProvider?() == true {
                onToggleHudHandler?()
                return true
            }
            return false
        }

        guard isOverlayPresentedProvider?() != true,
              let readerState = stateProvider?() else {
            return false
        }

        switch key {
        case .leftArrow:
            if readerState.direction == .rtl {
                performStepForward(readerState)
            } else {
                performStepBackward(readerState)
            }
            return true

        case .rightArrow:
            if readerState.direction == .rtl {
                performStepBackward(readerState)
            } else {
                performStepForward(readerState)
            }
            return true

        case .downArrow:
            performStepForward(readerState)
            return true

        case .upArrow:
            performStepBackward(readerState)
            return true

        case .space(let shift):
            if shift {
                performStepBackward(readerState)
            } else {
                performStepForward(readerState)
            }
            return true

        case .escape:
            return false

        case .pageDown:
            performStepForward(readerState)
            return true

        case .pageUp:
            performStepBackward(readerState)
            return true

        case .home:
            if !readerState.pages.isEmpty {
                if readerState.direction != .webtoon, let pagedJumpToEdge {
                    pagedJumpToEdge(false)
                } else {
                    readerState.goToPage(pageIndex: 0)
                }
            }
            return true

        case .end:
            if !readerState.pages.isEmpty {
                if readerState.direction != .webtoon, let pagedJumpToEdge {
                    pagedJumpToEdge(true)
                } else {
                    readerState.goToPage(pageIndex: Int32(readerState.pages.count - 1))
                }
            }
            return true
        }
    }

    public func scrollToBoundary(isNext: Bool) {
        pagedScrollToBoundary?(isNext)
    }

    fileprivate func markBound() {
        if !isBound {
            isBound = true
        }
    }

    fileprivate func updateViewportSize(_ size: CGSize) {
        if viewportSize != size {
            viewportSize = size
        }
    }

    fileprivate func updatePagerStatus(
        activePage: Int?,
        prevBoundaryPage: Int,
        nextBoundaryPage: Int,
        chapterId: String
    ) {
        activeChapterIdForPager = chapterId
        if activePagerPage != activePage {
            activePagerPage = activePage
        }
        let nextPrev = prevBoundaryPage >= 0 && activePage == prevBoundaryPage
        if isOnPreviousBoundaryPage != nextPrev {
            isOnPreviousBoundaryPage = nextPrev
        }
        let nextNext = nextBoundaryPage >= 0 && activePage == nextBoundaryPage
        if isOnNextBoundaryPage != nextNext {
            isOnNextBoundaryPage = nextNext
        }
    }

    fileprivate func clearPagerStatus() {
        activeChapterIdForPager = nil
        if activePagerPage != nil {
            activePagerPage = nil
        }
        if isOnPreviousBoundaryPage {
            isOnPreviousBoundaryPage = false
        }
        if isOnNextBoundaryPage {
            isOnNextBoundaryPage = false
        }
        pagedStepForward = nil
        pagedStepBackward = nil
        pagedScrollToBoundary = nil
        pagedJumpToEdge = nil
        activeInteractionTracker = nil
    }

    fileprivate func updateZoomMetrics(scale: CGFloat, offset: CGSize) {
        if activeZoomScale != scale {
            activeZoomScale = scale
        }
        if activeZoomOffset != offset {
            activeZoomOffset = offset
        }
    }

    fileprivate func clearWebtoonStatus() {
        activeWebtoonController = nil
        webtoonStepForward = nil
        webtoonStepBackward = nil
        webtoonZoomPanHandler = nil
    }

    private func performStepForward(_ readerState: any MangaReaderPresenterState) {
        if readerState.direction == .webtoon {
            if let webtoonStepForward {
                webtoonStepForward()
            } else {
                readerState.stepForward()
            }
        } else if let pagedStepForward {
            pagedStepForward()
        } else {
            readerState.stepForward()
        }
    }

    private func performStepBackward(_ readerState: any MangaReaderPresenterState) {
        if readerState.direction == .webtoon {
            if let webtoonStepBackward {
                webtoonStepBackward()
            } else {
                readerState.stepBackward()
            }
        } else if let pagedStepBackward {
            pagedStepBackward()
        } else {
            readerState.stepBackward()
        }
    }
}

public struct MangaReaderCanvas: View {
    @Environment(\.openURL) private var openURL
    public let state: any MangaReaderPresenterState
    public let hudVisible: Bool
    public let onToggleHud: () -> Void
    public let onBack: (() -> Void)?
    public let onOpenExternalURL: ((String) -> Void)?
    public let isOverlayPresented: Bool
    public let onDismissOverlay: (() -> Bool)?
    public let pageContextMenu: ((ReaderPageState, PageMediaStatusReady) -> AnyView)?
    @State private var fallbackController = MangaReaderInteractionController()
    @State private var canvasID = UUID()
    private let externalController: MangaReaderInteractionController?

    private var activeController: MangaReaderInteractionController {
        externalController ?? fallbackController
    }

    public init(
        state: any MangaReaderPresenterState,
        hudVisible: Bool,
        onToggleHud: @escaping () -> Void,
        onBack: (() -> Void)? = nil,
        onOpenExternalURL: ((String) -> Void)? = nil,
        isOverlayPresented: Bool = false,
        onDismissOverlay: (() -> Bool)? = nil,
        controller: MangaReaderInteractionController? = nil,
        pageContextMenu: ((ReaderPageState, PageMediaStatusReady) -> AnyView)? = nil
    ) {
        self.state = state
        self.hudVisible = hudVisible
        self.onToggleHud = onToggleHud
        self.onBack = onBack
        self.onOpenExternalURL = onOpenExternalURL
        self.isOverlayPresented = isOverlayPresented
        self.onDismissOverlay = onDismissOverlay
        self.externalController = controller
        self.pageContextMenu = pageContextMenu
    }

    public var body: some View {
        let controller = activeController
        let _ = bindController(controller)

        GeometryReader { proxy in
            let viewportSize = proxy.size
            let isWide = viewportSize.width >= mangaWideViewportBreakpoint

            ZStack {
                Color.black

                switch onEnum(of: state.loadState) {
                case .loading:
                    Color.clear
                        .contentShape(Rectangle())
                        .onTapGesture {
                            onToggleHud()
                        }
                        .overlay {
                            ProgressView()
                                .tint(.white)
                        }
                        .onAppear {
                            controller.clearPagerStatus()
                            controller.clearWebtoonStatus()
                            controller.zoomResetHandler = nil
                            controller.doubleTapZoomHandler = nil
                            controller.updateZoomMetrics(scale: 1.0, offset: .zero)
                        }

                case .error(let errorState):
                    if let locked = state.lockedChapter {
                        Color.clear
                            .contentShape(Rectangle())
                            .onTapGesture {
                                onToggleHud()
                            }
                            .overlay {
                                MangaBoundaryCardView(
                                    boundary: locked,
                                    isPrevious: false,
                                    isStandaloneLocked: true,
                                    onOpenUnlocked: { state.openAdjacentChapter(boundary: $0) },
                                    onOpenLockedUrl: openLockedURL,
                                    actionIdentifier: "manga_locked_chapter_open"
                                )
                            }
                            .onAppear {
                                controller.clearPagerStatus()
                                controller.clearWebtoonStatus()
                                controller.zoomResetHandler = nil
                                controller.doubleTapZoomHandler = nil
                                controller.updateZoomMetrics(scale: 1.0, offset: .zero)
                            }
                    } else {
                        ListErrorView(error: errorState.throwable) {
                            state.retryChapter()
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .preferredColorScheme(.dark)
                        .onAppear {
                            controller.clearPagerStatus()
                            controller.clearWebtoonStatus()
                            controller.zoomResetHandler = nil
                            controller.doubleTapZoomHandler = nil
                            controller.updateZoomMetrics(scale: 1.0, offset: .zero)
                        }
                    }

                case .success:
                    Group {
                        if state.direction == .webtoon {
                            MangaWebtoonStrip(
                                state: state,
                                viewportSize: viewportSize,
                                controller: controller,
                                onOpenLockedUrl: openLockedURL,
                                pageContextMenu: pageContextMenu
                            )
                        } else {
                            let pagedKey = "\(state.activeChapterId)|\(state.direction == .rtl)"
                            MangaPagedCanvas(
                                state: state,
                                viewportSize: viewportSize,
                                controller: controller,
                                onOpenLockedUrl: openLockedURL,
                                pageContextMenu: pageContextMenu
                            )
                            .id(pagedKey)
                        }
                    }
                    .id(state.activeChapterId)
                }
            }
            .frame(width: viewportSize.width, height: viewportSize.height)
            .clipped()
            .coordinateSpace(name: "manga_reader_canvas")
            .background {
                MangaReaderHardwareKeyboardBridge(
                    controller: controller,
                    isOverlayPresented: isOverlayPresented
                )
            }
            .onAppear {
                bindController(controller)
                controller.markBound()
                controller.updateViewportSize(viewportSize)
                if viewportSize.width > 0 {
                    state.setWideViewport(isWide: isWide)
                }
            }
            .onChange(of: viewportSize) { _, newSize in
                controller.updateViewportSize(newSize)
                if newSize.width > 0 {
                    state.setWideViewport(isWide: newSize.width >= mangaWideViewportBreakpoint)
                }
            }
        }
        .onDisappear {
            guard controller.canvasID == canvasID else { return }
            controller.canvasID = nil
            controller.stateProvider = nil
            controller.hudVisibleProvider = nil
            controller.onToggleHudHandler = nil
            controller.onBackHandler = nil
            controller.isOverlayPresentedProvider = nil
            controller.onDismissOverlayHandler = nil
            controller.isBound = false
        }
        .mangaPrefetchMedia(state: state)
        .accessibilityIdentifier("manga_reader_canvas")
    }

    private func bindController(_ controller: MangaReaderInteractionController) {
        controller.canvasID = canvasID
        controller.stateProvider = { [state] in state }
        controller.hudVisibleProvider = { [hudVisible] in hudVisible }
        controller.onToggleHudHandler = onToggleHud
        controller.onBackHandler = onBack
        controller.isOverlayPresentedProvider = { [isOverlayPresented] in isOverlayPresented }
        controller.onDismissOverlayHandler = onDismissOverlay
    }

    private func openLockedURL(_ rawURL: String) {
        guard !rawURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        if let onOpenExternalURL {
            onOpenExternalURL(rawURL)
            return
        }
        let deeplinkURI = DeeplinkRoute.OpenLinkDirectly(url: rawURL).toUri()
        if let url = URL(string: deeplinkURI) {
            openURL(url)
        } else if let direct = URL(string: rawURL) {
            openURL(direct)
        }
    }
}

private extension View {
    func mangaPrefetchMedia(state: any MangaReaderPresenterState) -> some View {
        let urlsKey = state.prefetchMedia.map { "\($0.cacheKey)|\(String(describing: $0.proxy))" }.joined(separator: "\n")
        let taskKey = "\(urlsKey)|\(ProxyMediaImages.shared.revision)"
        return task(id: taskKey) {
            let items = state.prefetchMedia
            guard !items.isEmpty else { return }
            await withTaskGroup(of: Void.self) { group in
                for media in items {
                    group.addTask {
                        _ = try? await ProxyMediaImages.shared.retrieve(media, options: [.cacheOriginalImage])
                    }
                }
            }
        }
    }
}

// MARK: - Paged Reader

private struct FrozenPagerLayout {
    let chapterId: String
    let slots: [any ReaderSlot]
    let previousBoundary: any ChapterBoundary
    let nextBoundary: any ChapterBoundary
}

private struct MangaPagedCanvas: View {
    let state: any MangaReaderPresenterState
    let viewportSize: CGSize
    let controller: MangaReaderInteractionController
    let onOpenLockedUrl: (String) -> Void
    let pageContextMenu: ((ReaderPageState, PageMediaStatusReady) -> AnyView)?

    @State private var activePagerPage: Int?
    @State private var programmaticScrollTarget: Int?
    @State private var programmaticScrollGeneration = 0
    @State private var mountScrollGeneration = 0
    @State private var isCurrentPageZoomed = false
    @State private var needsPostLayoutReanchor = false
    @State private var scrollAlignmentRevision = 0
    @State private var lastObservedPageIndex: Int32
    @State private var pendingCommittedChapterId: String?
    @State private var pendingCommittedSlotIndex: Int?
    @State private var pendingCommittedPages: [Int32] = []
    @State private var frozenLayout: FrozenPagerLayout?
    @State private var interactionTracker = PagerScrollInteractionTracker()

    init(
        state: any MangaReaderPresenterState,
        viewportSize: CGSize,
        controller: MangaReaderInteractionController,
        onOpenLockedUrl: @escaping (String) -> Void,
        pageContextMenu: ((ReaderPageState, PageMediaStatusReady) -> AnyView)?
    ) {
        self.state = state
        self.viewportSize = viewportSize
        self.controller = controller
        self.onOpenLockedUrl = onOpenLockedUrl
        self.pageContextMenu = pageContextMenu
        self._lastObservedPageIndex = State(initialValue: state.currentPageIndex)

        let hasPrev = !(state.previousBoundary is ChapterBoundaryNone)
        let hasNext = !(state.nextBoundary is ChapterBoundaryNone)
        let offset = hasPrev ? 1 : 0
        let total = state.slots.isEmpty ? 0 : (state.slots.count + offset + (hasNext ? 1 : 0))
        if total > 0 {
            let initial = min(max(Int(state.currentSlotIndex) + offset, 0), total - 1)
            self._activePagerPage = State(initialValue: nil)
            self._programmaticScrollTarget = State(initialValue: initial > 0 ? initial : nil)
        } else {
            self._activePagerPage = State(initialValue: nil)
            self._programmaticScrollTarget = State(initialValue: nil)
        }
    }

    private var activeFrozenLayout: FrozenPagerLayout? {
        guard let frozenLayout, frozenLayout.chapterId == state.activeChapterId else { return nil }
        return frozenLayout
    }

    private var displayedSlots: [any ReaderSlot] {
        activeFrozenLayout?.slots ?? state.slots
    }

    private var displayedPreviousBoundary: any ChapterBoundary {
        activeFrozenLayout?.previousBoundary ?? state.previousBoundary
    }

    private var displayedNextBoundary: any ChapterBoundary {
        activeFrozenLayout?.nextBoundary ?? state.nextBoundary
    }

    private var hasPrevBoundary: Bool {
        !(displayedPreviousBoundary is ChapterBoundaryNone)
    }

    private var hasNextBoundary: Bool {
        !(displayedNextBoundary is ChapterBoundaryNone)
    }

    private var startBoundaryOffset: Int {
        hasPrevBoundary ? 1 : 0
    }

    private var totalPagerPages: Int {
        guard !displayedSlots.isEmpty else { return 0 }
        return displayedSlots.count + startBoundaryOffset + (hasNextBoundary ? 1 : 0)
    }

    private var prevBoundaryPage: Int {
        hasPrevBoundary ? 0 : -1
    }

    private var nextBoundaryPage: Int {
        hasNextBoundary && !displayedSlots.isEmpty ? (startBoundaryOffset + displayedSlots.count) : -1
    }

    private var isRtl: Bool {
        state.direction == .rtl
    }

    private var hasActivePendingCommit: Bool {
        pendingCommittedSlotIndex != nil &&
            !pendingCommittedPages.isEmpty &&
            pendingCommittedChapterId == state.activeChapterId
    }

    private var effectiveCurrentPageIndex: Int32 {
        if hasActivePendingCommit, let pendingPage = pendingCommittedPages.last {
            return pendingPage
        }
        return state.currentPageIndex
    }

    private var effectiveCurrentSlotIndex: Int {
        if hasActivePendingCommit {
            if let pendingPage = pendingCommittedPages.last, !state.slots.isEmpty {
                return Int(
                    MangaSpreadLayoutSolver.shared.slotIndexForPage(
                        slots: state.slots,
                        pageIndex: pendingPage
                    )
                )
            }
            if let pendingSlot = pendingCommittedSlotIndex {
                return min(max(pendingSlot, 0), max(state.slots.count - 1, 0))
            }
        }
        return Int(state.currentSlotIndex)
    }

    private func recordPendingSlotCommit(_ liveSlotIdx: Int) {
        guard state.slots.indices.contains(liveSlotIdx) else { return }
        let primaryPage = state.slots[liveSlotIdx].primaryPageIndex
        if pendingCommittedChapterId != state.activeChapterId {
            pendingCommittedPages.removeAll()
        }
        pendingCommittedChapterId = state.activeChapterId
        pendingCommittedSlotIndex = liveSlotIdx
        if pendingCommittedPages.last != primaryPage {
            pendingCommittedPages.append(primaryPage)
            if pendingCommittedPages.count > 8 {
                pendingCommittedPages.removeFirst(pendingCommittedPages.count - 8)
            }
        }
        lastObservedPageIndex = primaryPage
    }

    private func clearPendingSlotCommit() {
        pendingCommittedChapterId = nil
        pendingCommittedSlotIndex = nil
        pendingCommittedPages.removeAll()
    }

    private var effectiveActivePagerPage: Int {
        if let target = programmaticScrollTarget {
            return clampedPagerPage(target)
        }
        if let activePagerPage {
            return clampedPagerPage(activePagerPage)
        }
        return expectedPagerPage()
    }

    private func expectedPagerPage() -> Int {
        guard totalPagerPages > 0 else { return 0 }
        if controller.activeChapterIdForPager == state.activeChapterId {
            if controller.isOnPreviousBoundaryPage, prevBoundaryPage >= 0 {
                return prevBoundaryPage
            }
            if controller.isOnNextBoundaryPage, nextBoundaryPage >= 0 {
                return nextBoundaryPage
            }
        }
        if activeFrozenLayout != nil {
            let slotIdx = Int(
                MangaSpreadLayoutSolver.shared.slotIndexForPage(
                    slots: displayedSlots,
                    pageIndex: effectiveCurrentPageIndex
                )
            )
            return clampedPagerPage(slotIdx + startBoundaryOffset)
        }
        return clampedPagerPage(effectiveCurrentSlotIndex + startBoundaryOffset)
    }

    var body: some View {
        Group {
            if totalPagerPages == 0 {
                let _ = installStepHandlers(scrollProxy: nil)
                Color.clear
                    .contentShape(Rectangle())
                    .mangaZoneTap(in: viewportSize, controller: controller, onDoubleTap: nil)
                    .onAppear {
                        controller.clearPagerStatus()
                        controller.zoomResetHandler = nil
                        controller.doubleTapZoomHandler = nil
                    }
            } else {
                ScrollViewReader { scrollProxy in
                    let _ = installStepHandlers(scrollProxy: scrollProxy)
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: 0) {
                            ForEach(0..<totalPagerPages, id: \.self) { pagerPage in
                                pagerCell(for: pagerPage)
                                    .containerRelativeFrame(.horizontal)
                                    .frame(height: max(viewportSize.height, 1))
                                    .background {
                                        #if os(macOS)
                                        PagerCellViewProbe(tracker: interactionTracker, pagerPage: pagerPage)
                                            .accessibilityHidden(true)
                                        #endif
                                    }
                                    .id(pagerPage)
                            }
                        }
                        .scrollTargetLayout()
                        // Lazy estimates for offscreen pages can retain old widths after a live resize.
                        .frame(width: CGFloat(totalPagerPages) * max(viewportSize.width, 1), alignment: .leading)
                        .background {
                            GeometryReader { stackProxy in
                                PagerScrollViewProbe(tracker: interactionTracker)
                                    .frame(width: 0, height: 0)
                                    .accessibilityHidden(true)
                                    .onChange(of: stackProxy.size.width) { oldStackWidth, newStackWidth in
                                        #if os(macOS)
                                        guard totalPagerPages > 0,
                                              oldStackWidth > 0,
                                              newStackWidth > 0,
                                              abs(newStackWidth - oldStackWidth) > 0.5,
                                              !interactionTracker.isUserInteracting,
                                              !interactionTracker.hasUnconsumedUserScroll else { return }
                                        if needsPostLayoutReanchor {
                                            needsPostLayoutReanchor = false
                                            reanchorScrollPosition(to: expectedPagerPage(), scrollProxy: scrollProxy)
                                        } else {
                                            DispatchQueue.main.async {
                                                guard !interactionTracker.isUserInteracting,
                                                      !interactionTracker.hasUnconsumedUserScroll else { return }
                                                if interactionTracker.alignScrollOffsetIfNeeded(
                                                    pagerPage: expectedPagerPage(),
                                                    totalPages: totalPagerPages,
                                                    expectedWidth: viewportSize.width,
                                                    isRtl: isRtl,
                                                    isZoomed: isCurrentPageZoomed
                                                ) {
                                                    scrollAlignmentRevision &+= 1
                                                }
                                            }
                                        }
                                        #endif
                                    }
                            }
                        }
                    }
                    .trackPagerScrollPhase(tracker: interactionTracker)
                    .scrollIndicators(.hidden)
                    .scrollTargetBehavior(.paging)
                    .scrollPosition(id: $activePagerPage)
                    .scrollDisabled(isCurrentPageZoomed)
                    .environment(\.mangaReaderIsScrollSettled, programmaticScrollTarget == nil)
                    .environment(\.mangaReaderScrollAlignmentRevision, scrollAlignmentRevision)
                    .environment(\.layoutDirection, isRtl ? .rightToLeft : .leftToRight)
                    .id(isRtl)
                    .onAppear {
                        installStepHandlers(scrollProxy: scrollProxy)
                        controller.clearWebtoonStatus()
                        isCurrentPageZoomed = false
                        controller.zoomResetHandler = nil
                        controller.updateZoomMetrics(scale: 1.0, offset: .zero)
                        lastObservedPageIndex = state.currentPageIndex
                        mountScrollGeneration = programmaticScrollGeneration
                        let initial = clampedPagerPage(Int(state.currentSlotIndex) + startBoundaryOffset)
                        if initial == prevBoundaryPage || initial == nextBoundaryPage {
                            controller.doubleTapZoomHandler = nil
                        }
                        if activePagerPage == initial {
                            programmaticScrollTarget = nil
                        } else if initial > 0 || activePagerPage != nil {
                            programmaticScrollTarget = initial
                        }
                        controller.updatePagerStatus(
                            activePage: initial,
                            prevBoundaryPage: prevBoundaryPage,
                            nextBoundaryPage: nextBoundaryPage,
                            chapterId: state.activeChapterId
                        )
                    }
                    .task {
                        let expectedGen = mountScrollGeneration
                        guard programmaticScrollGeneration == expectedGen else { return }
                        let initial = clampedPagerPage(Int(state.currentSlotIndex) + startBoundaryOffset)
                        if activePagerPage != initial {
                            programmaticScrollTarget = initial
                            activePagerPage = initial
                            scrollProxy.scrollTo(initial, anchor: .leading)
                        } else if initial > 0 {
                            scrollProxy.scrollTo(initial, anchor: .leading)
                        }
                        await Task.yield()
                        guard programmaticScrollGeneration == expectedGen else { return }
                        let settledTarget = expectedPagerPage()
                        if programmaticScrollTarget != nil, activePagerPage != settledTarget {
                            activePagerPage = settledTarget
                            scrollProxy.scrollTo(settledTarget, anchor: .leading)
                            await Task.yield()
                            guard programmaticScrollGeneration == expectedGen else { return }
                        }
                        if programmaticScrollTarget != nil {
                            programmaticScrollTarget = nil
                        }
                        controller.updatePagerStatus(
                            activePage: activePagerPage ?? settledTarget,
                            prevBoundaryPage: prevBoundaryPage,
                            nextBoundaryPage: nextBoundaryPage,
                            chapterId: state.activeChapterId
                        )
                    }
                    .onChange(of: state.currentPageIndex) { _, newPageIdx in
                        if hasActivePendingCommit {
                            if newPageIdx == pendingCommittedPages.last {
                                clearPendingSlotCommit()
                                lastObservedPageIndex = newPageIdx
                                return
                            }
                            if let matchedIdx = pendingCommittedPages.firstIndex(of: newPageIdx) {
                                pendingCommittedPages.removeFirst(matchedIdx + 1)
                                return
                            }
                            clearPendingSlotCommit()
                        }
                        let previousObserved = lastObservedPageIndex
                        lastObservedPageIndex = newPageIdx
                        guard !controller.isPerformingProgrammaticScroll,
                              !interactionTracker.isUserInteracting else { return }
                        if controller.isOnPreviousBoundaryPage || controller.isOnNextBoundaryPage,
                           newPageIdx != previousObserved {
                            let target = clampedPagerPage(Int(state.currentSlotIndex) + startBoundaryOffset)
                            scrollProgrammatically(to: target, animated: false, scrollProxy: scrollProxy)
                        }
                    }
                    .onChange(of: state.currentSlotIndex) { _, newSlot in
                        guard !controller.isPerformingProgrammaticScroll,
                              !interactionTracker.isUserInteracting else { return }
                        if hasActivePendingCommit {
                            let target = clampedPagerPage(effectiveCurrentSlotIndex + startBoundaryOffset)
                            if Int(newSlot) == effectiveCurrentSlotIndex,
                               state.currentPageIndex == pendingCommittedPages.last {
                                clearPendingSlotCommit()
                            }
                            if activePagerPage != target {
                                scrollProgrammatically(to: target, animated: false, scrollProxy: scrollProxy)
                            }
                            return
                        }
                        if (controller.isOnPreviousBoundaryPage || controller.isOnNextBoundaryPage),
                           state.currentPageIndex == lastObservedPageIndex {
                            return
                        }
                        let target = clampedPagerPage(Int(newSlot) + startBoundaryOffset)
                        if activePagerPage != target {
                            scrollProgrammatically(to: target, animated: false, scrollProxy: scrollProxy)
                        }
                    }
                    .onChange(of: totalPagerPages) { oldTotal, newTotal in
                        guard newTotal > 0, newTotal != oldTotal else { return }
                        guard !interactionTracker.isUserInteracting else { return }
                        needsPostLayoutReanchor = true
                        reanchorScrollPosition(to: expectedPagerPage(), scrollProxy: scrollProxy)
                    }
                    .onChange(of: viewportSize.width) { oldWidth, newWidth in
                        guard totalPagerPages > 0, newWidth > 0, abs(newWidth - oldWidth) > 0.01 else { return }
                        guard !interactionTracker.isUserInteracting else {
                            interactionTracker.needsReanchorWhenIdle = true
                            return
                        }
                        needsPostLayoutReanchor = true
                        reanchorScrollPosition(to: expectedPagerPage(), scrollProxy: scrollProxy)
                    }
                    .onChange(of: activePagerPage) { _, newPage in
                        guard let newPage else { return }
                        if let target = programmaticScrollTarget {
                            if newPage != target {
                                let currentGen = programmaticScrollGeneration
                                DispatchQueue.main.async {
                                    guard programmaticScrollGeneration == currentGen,
                                          programmaticScrollTarget == target else { return }
                                    var transaction = Transaction()
                                    transaction.disablesAnimations = true
                                    withTransaction(transaction) {
                                        if activePagerPage != target {
                                            activePagerPage = target
                                        }
                                        scrollProxy.scrollTo(target, anchor: .leading)
                                    }
                                    _ = interactionTracker.alignScrollOffsetIfNeeded(
                                        pagerPage: target,
                                        totalPages: totalPagerPages,
                                        expectedWidth: viewportSize.width,
                                        isRtl: isRtl,
                                        isZoomed: isCurrentPageZoomed
                                    )
                                }
                            }
                            controller.updatePagerStatus(
                                activePage: target,
                                prevBoundaryPage: prevBoundaryPage,
                                nextBoundaryPage: nextBoundaryPage,
                                chapterId: state.activeChapterId
                            )
                            if target == prevBoundaryPage || target == nextBoundaryPage {
                                isCurrentPageZoomed = false
                                controller.zoomResetHandler = nil
                                controller.doubleTapZoomHandler = nil
                            }
                            return
                        }
                        let fromUserScroll = interactionTracker.consumePendingUserScroll()
                        if !fromUserScroll,
                           !interactionTracker.isUserInteracting,
                           interactionTracker.hasViewportWidthMismatch(expectedWidth: viewportSize.width) {
                            reanchorScrollPosition(to: expectedPagerPage(), scrollProxy: scrollProxy)
                            return
                        }
                        commitDisplayedPagerPage(newPage)
                    }
                }
            }
        }
        .onDisappear {
            interactionTracker.onUserInteractionBegan = nil
            interactionTracker.onUserInteractionEnded = nil
            interactionTracker.onContentWidthChanged = nil
            if controller.activeInteractionTracker === interactionTracker {
                controller.clearPagerStatus()
                controller.zoomResetHandler = nil
                controller.doubleTapZoomHandler = nil
            }
        }
    }

    @ViewBuilder
    private func pagerCell(for pagerPage: Int) -> some View {
        if hasPrevBoundary && pagerPage == 0 {
            ZStack {
                Color.black
                MangaBoundaryCardView(
                    boundary: displayedPreviousBoundary,
                    isPrevious: true,
                    isStandaloneLocked: false,
                    onOpenUnlocked: { state.openAdjacentChapter(boundary: $0) },
                    onOpenLockedUrl: onOpenLockedUrl,
                    actionIdentifier: "manga_boundary_prev_action"
                )
            }
            .contentShape(Rectangle())
            .mangaZoneTap(in: viewportSize, controller: controller, onDoubleTap: nil)
            .environment(\.layoutDirection, .leftToRight)
            .onAppear {
                if effectiveActivePagerPage == pagerPage {
                    isCurrentPageZoomed = false
                    controller.zoomResetHandler = nil
                    controller.doubleTapZoomHandler = nil
                }
            }
        } else if hasNextBoundary && pagerPage == startBoundaryOffset + displayedSlots.count {
            ZStack {
                Color.black
                MangaBoundaryCardView(
                    boundary: displayedNextBoundary,
                    isPrevious: false,
                    isStandaloneLocked: false,
                    onOpenUnlocked: { state.openAdjacentChapter(boundary: $0) },
                    onOpenLockedUrl: onOpenLockedUrl,
                    actionIdentifier: "manga_boundary_next_action"
                )
            }
            .contentShape(Rectangle())
            .mangaZoneTap(in: viewportSize, controller: controller, onDoubleTap: nil)
            .environment(\.layoutDirection, .leftToRight)
            .onAppear {
                if effectiveActivePagerPage == pagerPage {
                    isCurrentPageZoomed = false
                    controller.zoomResetHandler = nil
                    controller.doubleTapZoomHandler = nil
                }
            }
        } else {
            let slotIndex = min(max(pagerPage - startBoundaryOffset, 0), max(displayedSlots.count - 1, 0))
            let slot = displayedSlots[slotIndex]
            let isActive = effectiveActivePagerPage == pagerPage
            ZoomablePagedSlot(
                slot: slot,
                pages: state.pages,
                state: state,
                viewportSize: viewportSize,
                isActivePagerPage: isActive,
                controller: controller,
                pageContextMenu: pageContextMenu,
                onZoomChanged: { zoomed, resetAction in
                    if isActive && controller.activeInteractionTracker === interactionTracker {
                        if isCurrentPageZoomed != zoomed {
                            isCurrentPageZoomed = zoomed
                        }
                        if zoomed {
                            controller.zoomResetHandler = resetAction
                        } else {
                            controller.zoomResetHandler = nil
                        }
                    }
                }
            )
            .environment(\.layoutDirection, .leftToRight)
        }
    }

    private func installStepHandlers(scrollProxy: ScrollViewProxy?) {
        controller.activeInteractionTracker = interactionTracker
        interactionTracker.onUserInteractionBegan = {
            needsPostLayoutReanchor = false
            if let pendingTarget = programmaticScrollTarget {
                programmaticScrollGeneration += 1
                programmaticScrollTarget = nil
                if activePagerPage != pendingTarget {
                    activePagerPage = pendingTarget
                }
            }
            if frozenLayout == nil || frozenLayout?.chapterId != state.activeChapterId {
                frozenLayout = FrozenPagerLayout(
                    chapterId: state.activeChapterId,
                    slots: state.slots,
                    previousBoundary: state.previousBoundary,
                    nextBoundary: state.nextBoundary
                )
            }
        }
        interactionTracker.onUserInteractionEnded = {
            let pendingResizeReanchor = interactionTracker.needsReanchorWhenIdle
            interactionTracker.needsReanchorWhenIdle = false

            if let frozen = activeFrozenLayout {
                let layoutChangedWhileFrozen = !pagerLayoutMatchesCurrentState(frozen)
                if layoutChangedWhileFrozen || pendingResizeReanchor {
                    if let settledFrozenPage = interactionTracker.settledPagerPage(
                        totalPages: totalPagerPages,
                        expectedWidth: viewportSize.width,
                        isRtl: isRtl
                    ) ?? activePagerPage {
                        commitDisplayedPagerPage(clampedPagerPage(settledFrozenPage))
                    }
                    frozenLayout = nil
                    needsPostLayoutReanchor = true
                    reanchorScrollPosition(to: expectedPagerPage(), scrollProxy: scrollProxy)
                    return
                }
                frozenLayout = nil
            } else if pendingResizeReanchor {
                needsPostLayoutReanchor = true
                reanchorScrollPosition(to: expectedPagerPage(), scrollProxy: scrollProxy)
            }
        }
        interactionTracker.onContentWidthChanged = {
            guard totalPagerPages > 0,
                  !interactionTracker.isUserInteracting,
                  !interactionTracker.hasUnconsumedUserScroll else { return }
            if needsPostLayoutReanchor {
                needsPostLayoutReanchor = false
                reanchorScrollPosition(to: expectedPagerPage(), scrollProxy: scrollProxy)
            } else {
                #if os(macOS)
                if interactionTracker.alignScrollOffsetIfNeeded(
                    pagerPage: expectedPagerPage(),
                    totalPages: totalPagerPages,
                    expectedWidth: viewportSize.width,
                    isRtl: isRtl,
                    isZoomed: isCurrentPageZoomed
                ) {
                    scrollAlignmentRevision &+= 1
                }
                #endif
            }
        }

        controller.pagedStepForward = {
            guard totalPagerPages > 0 else { return }
            let current = resolveLivePagerPageReleasingFrozenLayout()
            guard totalPagerPages > 0 else { return }
            if prevBoundaryPage >= 0, current == prevBoundaryPage {
                guard !state.slots.isEmpty else { return }
                if !readerSlot(state.slots[0], containsPage: effectiveCurrentPageIndex) {
                    recordPendingSlotCommit(0)
                }
                state.goToSlot(slotIndex: 0)
                scrollProgrammatically(to: startBoundaryOffset, animated: true, scrollProxy: scrollProxy)
            } else if nextBoundaryPage >= 0, current == nextBoundaryPage {
                if let nextUnlocked = state.nextBoundary as? ChapterBoundaryUnlocked {
                    clearPendingSlotCommit()
                    state.openAdjacentChapter(boundary: nextUnlocked)
                }
            } else if !state.slots.isEmpty {
                let slotIdx = min(max(current - startBoundaryOffset, 0), state.slots.count - 1)
                if slotIdx != effectiveCurrentSlotIndex {
                    state.goToSlot(slotIndex: Int32(slotIdx))
                }
                if slotIdx + 1 < state.slots.count {
                    let nextSlot = slotIdx + 1
                    recordPendingSlotCommit(nextSlot)
                    state.stepForward()
                    scrollProgrammatically(to: startBoundaryOffset + nextSlot, animated: true, scrollProxy: scrollProxy)
                } else if state.nextBoundary is ChapterBoundaryUnlocked {
                    clearPendingSlotCommit()
                    state.stepForward()
                } else if nextBoundaryPage >= 0 {
                    scrollProgrammatically(to: nextBoundaryPage, animated: true, scrollProxy: scrollProxy)
                }
            }
        }

        controller.pagedStepBackward = {
            guard totalPagerPages > 0 else { return }
            let current = resolveLivePagerPageReleasingFrozenLayout()
            guard totalPagerPages > 0 else { return }
            if nextBoundaryPage >= 0, current == nextBoundaryPage {
                guard !state.slots.isEmpty else { return }
                let lastIdx = state.slots.count - 1
                if !readerSlot(state.slots[lastIdx], containsPage: effectiveCurrentPageIndex) {
                    recordPendingSlotCommit(lastIdx)
                }
                state.goToSlot(slotIndex: Int32(lastIdx))
                scrollProgrammatically(to: startBoundaryOffset + lastIdx, animated: true, scrollProxy: scrollProxy)
            } else if prevBoundaryPage >= 0, current == prevBoundaryPage {
                if let prevUnlocked = state.previousBoundary as? ChapterBoundaryUnlocked {
                    clearPendingSlotCommit()
                    state.openAdjacentChapter(boundary: prevUnlocked)
                }
            } else if !state.slots.isEmpty {
                let slotIdx = min(max(current - startBoundaryOffset, 0), state.slots.count - 1)
                if slotIdx != effectiveCurrentSlotIndex {
                    state.goToSlot(slotIndex: Int32(slotIdx))
                }
                if slotIdx > 0 {
                    let prevSlot = slotIdx - 1
                    recordPendingSlotCommit(prevSlot)
                    state.stepBackward()
                    scrollProgrammatically(to: startBoundaryOffset + prevSlot, animated: true, scrollProxy: scrollProxy)
                } else if state.previousBoundary is ChapterBoundaryUnlocked {
                    clearPendingSlotCommit()
                    state.stepBackward()
                } else if prevBoundaryPage >= 0 {
                    scrollProgrammatically(to: prevBoundaryPage, animated: true, scrollProxy: scrollProxy)
                }
            }
        }

        controller.pagedScrollToBoundary = { isNext in
            if activeFrozenLayout != nil {
                frozenLayout = nil
            }
            if isNext, nextBoundaryPage >= 0 {
                scrollProgrammatically(to: nextBoundaryPage, animated: false, scrollProxy: scrollProxy)
            } else if !isNext, prevBoundaryPage >= 0 {
                scrollProgrammatically(to: prevBoundaryPage, animated: false, scrollProxy: scrollProxy)
            }
        }

        controller.pagedJumpToEdge = { isEnd in
            guard !state.pages.isEmpty else { return }
            if activeFrozenLayout != nil {
                frozenLayout = nil
            }
            clearPendingSlotCommit()
            let targetPage: Int32 = isEnd ? Int32(state.pages.count - 1) : 0
            state.goToPage(pageIndex: targetPage)
            guard !state.slots.isEmpty else { return }
            let slotIdx = Int(
                MangaSpreadLayoutSolver.shared.slotIndexForPage(
                    slots: state.slots,
                    pageIndex: targetPage
                )
            )
            scrollProgrammatically(to: startBoundaryOffset + slotIdx, animated: false, scrollProxy: scrollProxy)
        }
    }

    private func resolveLivePagerPageReleasingFrozenLayout() -> Int {
        guard activeFrozenLayout != nil else { return effectiveActivePagerPage }
        let currentFrozen = effectiveActivePagerPage
        let wasOnPrev = prevBoundaryPage >= 0 && currentFrozen == prevBoundaryPage
        let wasOnNext = nextBoundaryPage >= 0 && currentFrozen == nextBoundaryPage
        let displayedIdx = currentFrozen - startBoundaryOffset
        let displayedPrimaryPage: Int32 =
            displayedSlots.indices.contains(displayedIdx)
                ? displayedSlots[displayedIdx].primaryPageIndex
                : effectiveCurrentPageIndex
        frozenLayout = nil
        if wasOnPrev, prevBoundaryPage >= 0 {
            return prevBoundaryPage
        }
        if wasOnNext, nextBoundaryPage >= 0 {
            return nextBoundaryPage
        }
        guard !state.slots.isEmpty else { return 0 }
        let liveSlotIdx = Int(
            MangaSpreadLayoutSolver.shared.slotIndexForPage(
                slots: state.slots,
                pageIndex: displayedPrimaryPage
            )
        )
        return clampedPagerPage(startBoundaryOffset + liveSlotIdx)
    }

    private func scrollProgrammatically(to target: Int, animated: Bool, scrollProxy: ScrollViewProxy?) {
        let clamped = clampedPagerPage(target)
        programmaticScrollGeneration += 1
        let generation = programmaticScrollGeneration
        lastObservedPageIndex = effectiveCurrentPageIndex
        controller.isPerformingProgrammaticScroll = true
        defer { controller.isPerformingProgrammaticScroll = false }

        if clamped == prevBoundaryPage || clamped == nextBoundaryPage {
            isCurrentPageZoomed = false
            controller.zoomResetHandler = nil
            controller.doubleTapZoomHandler = nil
        }

        guard activePagerPage != clamped else {
            programmaticScrollTarget = nil
            controller.updatePagerStatus(
                activePage: clamped,
                prevBoundaryPage: prevBoundaryPage,
                nextBoundaryPage: nextBoundaryPage,
                chapterId: state.activeChapterId
            )
            return
        }
        programmaticScrollTarget = clamped
        if animated {
            withAnimation(.snappy(duration: 0.22)) {
                activePagerPage = clamped
                scrollProxy?.scrollTo(clamped, anchor: .leading)
            }
        } else {
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) {
                activePagerPage = clamped
                scrollProxy?.scrollTo(clamped, anchor: .leading)
            }
        }
        controller.updatePagerStatus(
            activePage: clamped,
            prevBoundaryPage: prevBoundaryPage,
            nextBoundaryPage: nextBoundaryPage,
            chapterId: state.activeChapterId
        )
        DispatchQueue.main.async {
            if programmaticScrollGeneration == generation, programmaticScrollTarget == clamped {
                programmaticScrollTarget = nil
            }
        }
    }

    private func reanchorScrollPosition(to target: Int, scrollProxy: ScrollViewProxy?) {
        guard totalPagerPages > 0 else { return }
        let clamped = clampedPagerPage(target)
        programmaticScrollGeneration += 1
        let generation = programmaticScrollGeneration
        controller.isPerformingProgrammaticScroll = true
        defer { controller.isPerformingProgrammaticScroll = false }

        programmaticScrollTarget = clamped
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            if activePagerPage != clamped {
                activePagerPage = clamped
            }
            scrollProxy?.scrollTo(clamped, anchor: .leading)
        }
        _ = interactionTracker.alignScrollOffsetIfNeeded(
            pagerPage: clamped,
            totalPages: totalPagerPages,
            expectedWidth: viewportSize.width,
            isRtl: isRtl,
            isZoomed: isCurrentPageZoomed
        )
        controller.updatePagerStatus(
            activePage: clamped,
            prevBoundaryPage: prevBoundaryPage,
            nextBoundaryPage: nextBoundaryPage,
            chapterId: state.activeChapterId
        )

        DispatchQueue.main.async {
            guard programmaticScrollGeneration == generation,
                  !interactionTracker.isUserInteracting else {
                if programmaticScrollGeneration == generation {
                    programmaticScrollTarget = nil
                }
                return
            }
            let settled = expectedPagerPage()
            programmaticScrollTarget = settled
            var asyncTransaction = Transaction()
            asyncTransaction.disablesAnimations = true
            withTransaction(asyncTransaction) {
                if activePagerPage != settled {
                    activePagerPage = settled
                }
                scrollProxy?.scrollTo(settled, anchor: .leading)
            }
            _ = interactionTracker.alignScrollOffsetIfNeeded(
                pagerPage: settled,
                totalPages: totalPagerPages,
                expectedWidth: viewportSize.width,
                isRtl: isRtl,
                isZoomed: isCurrentPageZoomed
            )
            controller.updatePagerStatus(
                activePage: settled,
                prevBoundaryPage: prevBoundaryPage,
                nextBoundaryPage: nextBoundaryPage,
                chapterId: state.activeChapterId
            )
            DispatchQueue.main.async {
                if programmaticScrollGeneration == generation {
                    if !interactionTracker.isUserInteracting {
                        if interactionTracker.alignScrollOffsetIfNeeded(
                            pagerPage: expectedPagerPage(),
                            totalPages: totalPagerPages,
                            expectedWidth: viewportSize.width,
                            isRtl: isRtl,
                            isZoomed: isCurrentPageZoomed
                        ) {
                            scrollAlignmentRevision &+= 1
                        }
                    }
                    programmaticScrollTarget = nil
                }
            }
        }
    }

    private func clampedPagerPage(_ value: Int) -> Int {
        guard totalPagerPages > 0 else { return 0 }
        return min(max(value, 0), totalPagerPages - 1)
    }

    private func commitDisplayedPagerPage(_ page: Int) {
        controller.updatePagerStatus(
            activePage: page,
            prevBoundaryPage: prevBoundaryPage,
            nextBoundaryPage: nextBoundaryPage,
            chapterId: state.activeChapterId
        )
        if page == prevBoundaryPage || page == nextBoundaryPage {
            isCurrentPageZoomed = false
            controller.zoomResetHandler = nil
            controller.doubleTapZoomHandler = nil
            return
        }
        let displayedSlotIdx = page - startBoundaryOffset
        guard displayedSlots.indices.contains(displayedSlotIdx) else { return }
        if activeFrozenLayout != nil {
            let displayedSlot = displayedSlots[displayedSlotIdx]
            if !readerSlot(displayedSlot, containsPage: effectiveCurrentPageIndex) {
                let liveSlotIdx = Int(
                    MangaSpreadLayoutSolver.shared.slotIndexForPage(
                        slots: state.slots,
                        pageIndex: displayedSlot.primaryPageIndex
                    )
                )
                if state.slots.indices.contains(liveSlotIdx) {
                    recordPendingSlotCommit(liveSlotIdx)
                    state.goToSlot(slotIndex: Int32(liveSlotIdx))
                }
            }
        } else if state.slots.indices.contains(displayedSlotIdx),
                  displayedSlotIdx != effectiveCurrentSlotIndex {
            recordPendingSlotCommit(displayedSlotIdx)
            state.goToSlot(slotIndex: Int32(displayedSlotIdx))
        }
    }

    private func pagerLayoutMatchesCurrentState(_ frozen: FrozenPagerLayout) -> Bool {
        frozen.chapterId == state.activeChapterId &&
            readerSlotsEqual(frozen.slots, state.slots) &&
            chapterBoundaryMatches(frozen.previousBoundary, state.previousBoundary) &&
            chapterBoundaryMatches(frozen.nextBoundary, state.nextBoundary)
    }

    private func readerSlot(_ slot: any ReaderSlot, containsPage pageIndex: Int32) -> Bool {
        if let single = slot as? ReaderSlotSingle {
            return single.pageIndex == pageIndex
        }
        if let doubleSlot = slot as? ReaderSlotDouble {
            return doubleSlot.leftPageIndex == pageIndex || doubleSlot.rightPageIndex == pageIndex
        }
        return slot.primaryPageIndex == pageIndex
    }

    private func readerSlotsEqual(_ lhs: [any ReaderSlot], _ rhs: [any ReaderSlot]) -> Bool {
        guard lhs.count == rhs.count else { return false }
        for (a, b) in zip(lhs, rhs) {
            if let sa = a as? ReaderSlotSingle, let sb = b as? ReaderSlotSingle {
                if sa.pageIndex != sb.pageIndex { return false }
            } else if let da = a as? ReaderSlotDouble, let db = b as? ReaderSlotDouble {
                if da.leftPageIndex != db.leftPageIndex ||
                    da.rightPageIndex != db.rightPageIndex ||
                    da.primaryPageIndex != db.primaryPageIndex {
                    return false
                }
            } else {
                return false
            }
        }
        return true
    }

    private func chapterBoundaryMatches(_ lhs: any ChapterBoundary, _ rhs: any ChapterBoundary) -> Bool {
        if lhs is ChapterBoundaryNone, rhs is ChapterBoundaryNone {
            return true
        }
        if let ul = lhs as? ChapterBoundaryUnlocked, let ur = rhs as? ChapterBoundaryUnlocked {
            return ul.chapter.id == ur.chapter.id && ul.startFromEnd == ur.startFromEnd
        }
        if let ll = lhs as? ChapterBoundaryLocked, let lr = rhs as? ChapterBoundaryLocked {
            return ll.chapter.id == lr.chapter.id
        }
        return false
    }
}

@MainActor
private final class PagerScrollInteractionTracker: NSObject {
    #if os(iOS)
    private(set) weak var scrollView: UIScrollView?
    private nonisolated(unsafe) var contentSizeObservation: NSKeyValueObservation?
    private var isPanGestureActive = false
    private var isPollingNativeIdle = false
    #elseif os(macOS)
    private struct WeakNSViewBox {
        weak var view: NSView?
    }

    private(set) weak var scrollView: NSScrollView?
    private var cellViews: [Int: WeakNSViewBox] = [:]
    private nonisolated(unsafe) var willStartObserver: NSObjectProtocol?
    private nonisolated(unsafe) var didEndObserver: NSObjectProtocol?
    private var isMacLiveScrolling = false
    #endif

    private var isScrollPhaseUserActive = false
    private var isNotifiedInteracting = false
    private(set) var hasUnconsumedUserScroll = false
    var needsReanchorWhenIdle = false
    var onUserInteractionBegan: (() -> Void)?
    var onUserInteractionEnded: (() -> Void)?
    var onContentWidthChanged: (() -> Void)?

    var isUserInteracting: Bool {
        #if os(iOS)
        let nativeActive = scrollView.map { $0.isTracking || $0.isDragging || $0.isDecelerating } ?? false
        return isScrollPhaseUserActive || isPanGestureActive || nativeActive
        #elseif os(macOS)
        return isScrollPhaseUserActive || isMacLiveScrolling
        #else
        return isScrollPhaseUserActive
        #endif
    }

    func consumePendingUserScroll() -> Bool {
        let pending = hasUnconsumedUserScroll
        hasUnconsumedUserScroll = false
        return pending
    }

    func setScrollPhaseUserActive(_ active: Bool) {
        guard isScrollPhaseUserActive != active else { return }
        isScrollPhaseUserActive = active
        if active {
            hasUnconsumedUserScroll = true
        }
        syncInteractionState()
        #if os(iOS)
        if !active {
            scheduleIdleVerificationIfNeeded()
        }
        #endif
    }

    private func syncInteractionState() {
        let currentlyInteracting = isUserInteracting
        if !isNotifiedInteracting && currentlyInteracting {
            isNotifiedInteracting = true
            onUserInteractionBegan?()
        } else if isNotifiedInteracting && !currentlyInteracting {
            isNotifiedInteracting = false
            onUserInteractionEnded?()
            hasUnconsumedUserScroll = false
        } else if !currentlyInteracting && hasUnconsumedUserScroll {
            hasUnconsumedUserScroll = false
        }
    }

    func settledPagerPage(totalPages: Int, expectedWidth: CGFloat, isRtl: Bool) -> Int? {
        guard totalPages > 0, expectedWidth > 1 else { return nil }
        #if os(iOS)
        guard let sv = scrollView else { return nil }
        let svWidth = sv.bounds.width
        guard svWidth > 1 else { return nil }
        let expectedContentWidth = CGFloat(totalPages) * svWidth
        guard abs(sv.contentSize.width - expectedContentWidth) <= max(svWidth * 0.25, 4.0) else { return nil }
        let maxVisualIndex = totalPages - 1
        let visualIndex = min(max(Int((sv.contentOffset.x / svWidth).rounded()), 0), maxVisualIndex)
        return isRtl ? (maxVisualIndex - visualIndex) : visualIndex
        #elseif os(macOS)
        guard let sv = scrollView else { return nil }
        let svWidth = sv.contentView.bounds.width
        let docWidth = sv.documentView?.frame.width ?? 0
        guard svWidth > 1, docWidth >= svWidth - 1 else { return nil }
        let originX = sv.contentView.bounds.origin.x
        let rawPage: Int
        if isRtl {
            rawPage = Int(((docWidth - originX) / svWidth).rounded()) - 1
        } else {
            rawPage = Int((originX / svWidth).rounded())
        }
        return min(max(rawPage, 0), totalPages - 1)
        #else
        return nil
        #endif
    }

    func hasViewportWidthMismatch(expectedWidth: CGFloat) -> Bool {
        guard expectedWidth > 1 else { return false }
        #if os(iOS)
        guard let sv = scrollView, sv.bounds.width > 1 else { return false }
        return abs(sv.bounds.width - expectedWidth) > 1.5
        #elseif os(macOS)
        guard let sv = scrollView, sv.contentView.bounds.width > 1 else { return false }
        return abs(sv.contentView.bounds.width - expectedWidth) > 1.5
        #else
        return false
        #endif
    }

    @discardableResult
    func alignScrollOffsetIfNeeded(
        pagerPage: Int,
        totalPages: Int,
        expectedWidth: CGFloat,
        isRtl: Bool,
        isZoomed: Bool
    ) -> Bool {
        guard totalPages > 0, expectedWidth > 1, !isUserInteracting else { return false }
        #if os(iOS)
        guard let sv = scrollView else { return false }
        sv.layoutIfNeeded()
        let svWidth = sv.bounds.width
        guard svWidth > 1,
              abs(svWidth - expectedWidth) <= 1.5,
              abs(sv.contentSize.width - CGFloat(totalPages) * svWidth) <= 2.5 else { return false }
        let visualIndex = isRtl ? max(totalPages - 1 - pagerPage, 0) : min(max(pagerPage, 0), totalPages - 1)
        let expectedX = CGFloat(visualIndex) * svWidth
        if abs(sv.contentOffset.x - expectedX) > 1.0 {
            sv.setContentOffset(CGPoint(x: expectedX, y: 0), animated: false)
            return true
        }
        return false
        #elseif os(macOS)
        guard let sv = scrollView, let docView = sv.documentView else { return false }
        sv.layoutSubtreeIfNeeded()
        let svWidth = sv.contentView.bounds.width
        let docWidth = docView.frame.width
        guard svWidth > 1,
              abs(svWidth - expectedWidth) <= 1.5,
              docWidth >= svWidth else { return false }
        let clampedPage = min(max(pagerPage, 0), totalPages - 1)
        let expectedX: CGFloat
        if let cellView = cellViews[clampedPage]?.view,
           cellView.window != nil,
           cellView.isDescendant(of: docView),
           abs(cellView.bounds.width - svWidth) <= 2.0 {
            let cellFrame = docView.convert(cellView.bounds, from: cellView)
            expectedX = min(max(cellFrame.minX, 0), max(docWidth - svWidth, 0))
        } else if isZoomed {
            if isRtl {
                guard docWidth >= CGFloat(clampedPage + 1) * svWidth - 2.0 else { return false }
                expectedX = max(docWidth - CGFloat(clampedPage + 1) * svWidth, 0)
            } else {
                guard docWidth >= CGFloat(clampedPage + 1) * svWidth - 2.0 else { return false }
                expectedX = CGFloat(clampedPage) * svWidth
            }
        } else {
            return false
        }
        if abs(sv.contentView.bounds.origin.x - expectedX) > 1.0 {
            sv.contentView.scroll(to: CGPoint(x: expectedX, y: 0))
            sv.reflectScrolledClipView(sv.contentView)
            return true
        }
        return false
        #else
        return false
        #endif
    }

    #if os(iOS)
    func attach(to candidate: UIScrollView) {
        guard scrollView !== candidate else { return }
        if let previous = scrollView {
            previous.panGestureRecognizer.removeTarget(self, action: #selector(handlePanGesture(_:)))
        }
        contentSizeObservation?.invalidate()
        isPanGestureActive = false
        scrollView = candidate
        candidate.panGestureRecognizer.addTarget(self, action: #selector(handlePanGesture(_:)))
        contentSizeObservation = candidate.observe(\.contentSize, options: [.old, .new]) { [weak self] _, change in
            guard let oldWidth = change.oldValue?.width,
                  let newWidth = change.newValue?.width,
                  oldWidth > 0,
                  newWidth > 0,
                  abs(newWidth - oldWidth) > 0.5 else { return }
            let work: @MainActor @Sendable () -> Void = { [weak self] in
                self?.onContentWidthChanged?()
            }
            DispatchQueue.main.async(execute: work)
        }
    }

    deinit {
        contentSizeObservation?.invalidate()
    }

    func processPanGestureState(_ state: UIGestureRecognizer.State) {
        switch state {
        case .began, .changed:
            isPanGestureActive = true
            hasUnconsumedUserScroll = true
            syncInteractionState()
        case .ended, .cancelled, .failed:
            isPanGestureActive = false
            let nativeStillActive = scrollView.map { $0.isTracking || $0.isDragging || $0.isDecelerating } ?? false
            if !nativeStillActive {
                isScrollPhaseUserActive = false
            }
            syncInteractionState()
            scheduleIdleVerificationIfNeeded()
        default:
            break
        }
    }

    private func scheduleIdleVerificationIfNeeded() {
        guard isNotifiedInteracting, !isPollingNativeIdle else { return }
        isPollingNativeIdle = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.016) { [weak self] in
            guard let self else { return }
            self.isPollingNativeIdle = false
            self.syncInteractionState()
            if self.isNotifiedInteracting && !self.isScrollPhaseUserActive && !self.isPanGestureActive {
                self.scheduleIdleVerificationIfNeeded()
            }
        }
    }

    @objc private func handlePanGesture(_ recognizer: UIPanGestureRecognizer) {
        processPanGestureState(recognizer.state)
    }
    #elseif os(macOS)
    func attach(to candidate: NSScrollView) {
        guard scrollView !== candidate else { return }
        detach()
        scrollView = candidate
        willStartObserver = NotificationCenter.default.addObserver(
            forName: NSScrollView.willStartLiveScrollNotification,
            object: candidate,
            queue: nil
        ) { [weak self] _ in
            let work = { @MainActor [weak self] in
                guard let self else { return }
                self.isMacLiveScrolling = true
                self.hasUnconsumedUserScroll = true
                self.syncInteractionState()
            }
            if Thread.isMainThread {
                MainActor.assumeIsolated { work() }
            } else {
                DispatchQueue.main.async(execute: work)
            }
        }
        didEndObserver = NotificationCenter.default.addObserver(
            forName: NSScrollView.didEndLiveScrollNotification,
            object: candidate,
            queue: nil
        ) { [weak self] _ in
            let work = { @MainActor [weak self] in
                guard let self else { return }
                self.isMacLiveScrolling = false
                self.isScrollPhaseUserActive = false
                self.syncInteractionState()
            }
            if Thread.isMainThread {
                MainActor.assumeIsolated { work() }
            } else {
                DispatchQueue.main.async(execute: work)
            }
        }
    }

    func registerCellView(_ view: NSView, for pagerPage: Int) {
        cellViews[pagerPage] = WeakNSViewBox(view: view)
    }

    func unregisterCellView(_ view: NSView, for pagerPage: Int) {
        if cellViews[pagerPage]?.view === view {
            cellViews.removeValue(forKey: pagerPage)
        }
    }

    func detach() {
        if let willStartObserver {
            NotificationCenter.default.removeObserver(willStartObserver)
            self.willStartObserver = nil
        }
        if let didEndObserver {
            NotificationCenter.default.removeObserver(didEndObserver)
            self.didEndObserver = nil
        }
        isMacLiveScrolling = false
        isNotifiedInteracting = false
        hasUnconsumedUserScroll = false
        cellViews.removeAll()
        scrollView = nil
    }

    deinit {
        if let willStartObserver {
            NotificationCenter.default.removeObserver(willStartObserver)
        }
        if let didEndObserver {
            NotificationCenter.default.removeObserver(didEndObserver)
        }
    }
    #endif
}

private extension View {
    @ViewBuilder
    func trackPagerScrollPhase(tracker: PagerScrollInteractionTracker) -> some View {
        #if os(iOS)
        if #available(iOS 18.0, *) {
            onScrollPhaseChange { _, newPhase in
                let active = newPhase == .tracking || newPhase == .interacting || newPhase == .decelerating
                tracker.setScrollPhaseUserActive(active)
            }
        } else {
            self
        }
        #else
        self
        #endif
    }
}

#if os(iOS)
private struct PagerScrollViewProbe: UIViewRepresentable {
    let tracker: PagerScrollInteractionTracker

    func makeUIView(context: Context) -> PagerScrollProbeUIView {
        let view = PagerScrollProbeUIView()
        view.tracker = tracker
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ uiView: PagerScrollProbeUIView, context: Context) {
        uiView.tracker = tracker
        uiView.resolveScrollViewIfNeeded()
    }
}

private final class PagerScrollProbeUIView: UIView {
    weak var tracker: PagerScrollInteractionTracker?

    override func didMoveToWindow() {
        super.didMoveToWindow()
        resolveScrollViewIfNeeded()
    }

    override func didMoveToSuperview() {
        super.didMoveToSuperview()
        resolveScrollViewIfNeeded()
    }

    func resolveScrollViewIfNeeded() {
        var current = superview
        while let candidate = current {
            if let scrollView = candidate as? UIScrollView {
                tracker?.attach(to: scrollView)
                return
            }
            current = candidate.superview
        }
    }
}
#elseif os(macOS)
private struct PagerScrollViewProbe: NSViewRepresentable {
    let tracker: PagerScrollInteractionTracker

    func makeNSView(context: Context) -> PagerScrollProbeNSView {
        let view = PagerScrollProbeNSView()
        view.tracker = tracker
        return view
    }

    func updateNSView(_ nsView: PagerScrollProbeNSView, context: Context) {
        nsView.tracker = tracker
        nsView.resolveScrollViewIfNeeded()
    }

    static func dismantleNSView(_ nsView: PagerScrollProbeNSView, coordinator: ()) {
        nsView.tracker?.detach()
    }
}

private final class PagerScrollProbeNSView: NSView {
    weak var tracker: PagerScrollInteractionTracker?

    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        resolveScrollViewIfNeeded()
    }

    override func viewDidMoveToSuperview() {
        super.viewDidMoveToSuperview()
        resolveScrollViewIfNeeded()
    }

    func resolveScrollViewIfNeeded() {
        if let scrollView = enclosingScrollView {
            tracker?.attach(to: scrollView)
        }
    }
}

private struct PagerCellViewProbe: NSViewRepresentable {
    let tracker: PagerScrollInteractionTracker
    let pagerPage: Int

    func makeNSView(context: Context) -> PagerCellProbeNSView {
        let view = PagerCellProbeNSView()
        view.tracker = tracker
        view.pagerPage = pagerPage
        tracker.registerCellView(view, for: pagerPage)
        return view
    }

    func sizeThatFits(_ proposal: ProposedViewSize, nsView: PagerCellProbeNSView, context: Context) -> CGSize {
        CGSize(width: proposal.width ?? 0, height: proposal.height ?? 0)
    }

    func updateNSView(_ nsView: PagerCellProbeNSView, context: Context) {
        if nsView.pagerPage != pagerPage {
            nsView.tracker?.unregisterCellView(nsView, for: nsView.pagerPage)
            nsView.pagerPage = pagerPage
        }
        nsView.tracker = tracker
        tracker.registerCellView(nsView, for: pagerPage)
    }

    static func dismantleNSView(_ nsView: PagerCellProbeNSView, coordinator: ()) {
        nsView.tracker?.unregisterCellView(nsView, for: nsView.pagerPage)
    }
}

private final class PagerCellProbeNSView: NSView {
    weak var tracker: PagerScrollInteractionTracker?
    var pagerPage: Int = 0
    private var lastWidth: CGFloat = 0

    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    override func setFrameSize(_ newSize: NSSize) {
        let oldWidth = lastWidth
        super.setFrameSize(newSize)
        lastWidth = newSize.width
        guard oldWidth > 0, newSize.width > 0, abs(newSize.width - oldWidth) > 0.5 else { return }
        let work: @MainActor @Sendable () -> Void = { [weak self] in
            guard let self else { return }
            self.tracker?.onContentWidthChanged?()
        }
        DispatchQueue.main.async(execute: work)
    }
}
#endif

private struct ZoomablePagedSlot: View {
    let slot: any ReaderSlot
    let pages: [ReaderPageState]
    let state: any MangaReaderPresenterState
    let viewportSize: CGSize
    let isActivePagerPage: Bool
    let controller: MangaReaderInteractionController
    let pageContextMenu: ((ReaderPageState, PageMediaStatusReady) -> AnyView)?
    let onZoomChanged: (Bool, @escaping () -> Void) -> Void

    @State private var committedScale: CGFloat = 1.0
    @State private var pinchScale: CGFloat = 1.0
    @State private var committedOffset: CGSize = .zero
    @State private var dragDelta: CGSize = .zero

    private var effectiveScale: CGFloat {
        min(max(committedScale * pinchScale, 1.0), mangaMaxReaderZoom)
    }

    private var isZoomed: Bool {
        effectiveScale > mangaZoomEpsilon
    }

    private var clampedOffset: CGSize {
        clampOffset(
            CGSize(
                width: committedOffset.width + dragDelta.width,
                height: committedOffset.height + dragDelta.height
            ),
            for: effectiveScale,
            in: viewportSize
        )
    }

    var body: some View {
        ZStack {
            if let single = slot as? ReaderSlotSingle {
                let pageIndex = Int(single.pageIndex)
                if pages.indices.contains(pageIndex) {
                    ReaderPageCell(
                        page: pages[pageIndex],
                        state: state,
                        contentMode: .fit,
                        alignment: .center,
                        pageContextMenu: pageContextMenu
                    )
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            } else if let doubleSlot = slot as? ReaderSlotDouble {
                HStack(spacing: 0) {
                    let leftIndex = Int(doubleSlot.leftPageIndex)
                    if pages.indices.contains(leftIndex) {
                        ReaderPageCell(
                            page: pages[leftIndex],
                            state: state,
                            contentMode: .fit,
                            alignment: .trailing,
                            pageContextMenu: pageContextMenu
                        )
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .trailing)
                    }

                    let rightIndex = Int(doubleSlot.rightPageIndex)
                    if pages.indices.contains(rightIndex) {
                        ReaderPageCell(
                            page: pages[rightIndex],
                            state: state,
                            contentMode: .fit,
                            alignment: .leading,
                            pageContextMenu: pageContextMenu
                        )
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                    }
                }
                .environment(\.layoutDirection, .leftToRight)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .scaleEffect(effectiveScale)
        .offset(clampedOffset)
        .frame(width: max(viewportSize.width, 1), height: max(viewportSize.height, 1))
        .clipped()
        .contentShape(Rectangle())
        .mangaZoneTap(
            in: viewportSize,
            controller: controller,
            onDoubleTap: { tapLocation in
                toggleDoubleTapZoom(at: tapLocation)
            }
        )
        .simultaneousGesture(
            MagnifyGesture()
                .onChanged { value in
                    pinchScale = value.magnification
                }
                .onEnded { value in
                    let nextScale = min(max(committedScale * value.magnification, 1.0), mangaMaxReaderZoom)
                    pinchScale = 1.0
                    if nextScale <= mangaZoomEpsilon {
                        resetZoom()
                    } else {
                        committedScale = nextScale
                        committedOffset = clampOffset(committedOffset, for: nextScale, in: viewportSize)
                    }
                }
        )
        .modifier(
            ZoomPanGestureModifier(
                isEnabled: isZoomed,
                onChanged: { translation in
                    dragDelta = translation
                },
                onEnded: { translation in
                    committedOffset = clampOffset(
                        CGSize(
                            width: committedOffset.width + translation.width,
                            height: committedOffset.height + translation.height
                        ),
                        for: effectiveScale,
                        in: viewportSize
                    )
                    dragDelta = .zero
                }
            )
        )
        .onAppear {
            if isActivePagerPage {
                bindActiveDoubleTapHandler()
                onZoomChanged(isZoomed, resetZoom)
            }
        }
        .onChange(of: isActivePagerPage) { _, active in
            if !active && isZoomed {
                resetZoom()
            } else if active {
                bindActiveDoubleTapHandler()
                onZoomChanged(isZoomed, resetZoom)
            }
        }
        .onChange(of: isZoomed) { _, zoomed in
            if isActivePagerPage {
                onZoomChanged(zoomed, resetZoom)
            }
        }
        .onDisappear {
            if isActivePagerPage && isZoomed {
                onZoomChanged(false, {})
            }
        }
    }

    private func bindActiveDoubleTapHandler() {
        controller.doubleTapZoomHandler = { point in
            toggleDoubleTapZoom(at: point)
        }
    }

    private func toggleDoubleTapZoom(at tapLocation: CGPoint) {
        let activeViewport = controller.viewportSize.width > 1 ? controller.viewportSize : viewportSize
        withAnimation(.spring(duration: 0.22)) {
            if isZoomed {
                resetZoom()
            } else {
                let targetScale = mangaDoubleTapZoom
                let centerX = activeViewport.width / 2
                let centerY = activeViewport.height / 2
                let proposed = CGSize(
                    width: (centerX - tapLocation.x) * (targetScale - 1),
                    height: (centerY - tapLocation.y) * (targetScale - 1)
                )
                committedScale = targetScale
                committedOffset = clampOffset(proposed, for: targetScale, in: activeViewport)
            }
        }
    }

    private func resetZoom() {
        committedScale = 1.0
        pinchScale = 1.0
        committedOffset = .zero
        dragDelta = .zero
    }

    private func clampOffset(_ offset: CGSize, for scale: CGFloat, in size: CGSize) -> CGSize {
        guard scale > mangaZoomEpsilon else { return .zero }
        let maxX = max((size.width * (scale - 1)) / 2, 0)
        let maxY = max((size.height * (scale - 1)) / 2, 0)
        return CGSize(
            width: min(max(offset.width, -maxX), maxX),
            height: min(max(offset.height, -maxY), maxY)
        )
    }
}

private struct ZoomPanGestureModifier: ViewModifier {
    let isEnabled: Bool
    let onChanged: (CGSize) -> Void
    let onEnded: (CGSize) -> Void

    @ViewBuilder
    func body(content: Content) -> some View {
        if isEnabled {
            content.gesture(
                DragGesture(minimumDistance: 2)
                    .onChanged { value in
                        onChanged(value.translation)
                    }
                    .onEnded { value in
                        onEnded(value.translation)
                    }
            )
        } else {
            content
        }
    }
}

// MARK: - Webtoon Strip

@MainActor
private final class WebtoonScrollSyncBox {
    var lastReportedPage: Int
    var programmaticTargetPage: Int?
    var settledCenterPage: Int?

    init(initialPage: Int) {
        self.lastReportedPage = initialPage
        self.programmaticTargetPage = initialPage
    }
}

@MainActor
private final class WebtoonScrollController {
    #if os(iOS)
    private(set) weak var scrollView: UIScrollView?
    #elseif os(macOS)
    private(set) weak var scrollView: NSScrollView?
    #endif

    var hasAttachedScrollView: Bool {
        #if os(iOS)
        guard let sv = scrollView else { return false }
        return sv.bounds.height > 1
        #elseif os(macOS)
        guard let sv = scrollView else { return false }
        return sv.contentView.bounds.height > 1 && sv.documentView != nil
        #else
        return false
        #endif
    }

    var canScrollForward: Bool {
        guard let metrics = currentVerticalMetrics() else { return false }
        return metrics.currentY < metrics.maxY - 1.0
    }

    var canScrollBackward: Bool {
        guard let metrics = currentVerticalMetrics() else { return false }
        return metrics.currentY > metrics.minY + 1.0
    }

    @discardableResult
    func scrollByRawDeltaY(_ deltaY: CGFloat, animated: Bool) -> CGFloat {
        guard abs(deltaY) > 0.001, let metrics = currentVerticalMetrics() else { return 0 }
        let clampedY = min(max(metrics.currentY + deltaY, metrics.minY), metrics.maxY)
        let consumed = clampedY - metrics.currentY
        guard abs(consumed) > 0.001 else { return 0 }
        #if os(iOS)
        guard let sv = scrollView else { return 0 }
        let targetOffset = CGPoint(x: sv.contentOffset.x, y: clampedY)
        if animated {
            UIView.animate(
                withDuration: 0.18,
                delay: 0,
                options: [.curveEaseOut, .allowUserInteraction, .beginFromCurrentState]
            ) {
                sv.contentOffset = targetOffset
            }
        } else {
            sv.setContentOffset(targetOffset, animated: false)
        }
        return consumed
        #elseif os(macOS)
        guard let sv = scrollView, let docView = sv.documentView else { return 0 }
        let clipOriginX = sv.contentView.bounds.origin.x
        let targetOriginY: CGFloat
        if docView.isFlipped {
            targetOriginY = clampedY
        } else {
            let docHeight = docView.frame.height
            let clipHeight = sv.contentView.bounds.height
            let topOriginY = docHeight - clipHeight + sv.contentInsets.top
            targetOriginY = topOriginY - clampedY
        }
        sv.contentView.scroll(to: CGPoint(x: clipOriginX, y: targetOriginY))
        sv.reflectScrolledClipView(sv.contentView)
        return consumed
        #else
        return 0
        #endif
    }

    private func currentVerticalMetrics() -> (currentY: CGFloat, minY: CGFloat, maxY: CGFloat)? {
        #if os(iOS)
        guard let sv = scrollView else { return nil }
        sv.layoutIfNeeded()
        let svHeight = sv.bounds.height
        guard svHeight > 1 else { return nil }
        let inset = sv.adjustedContentInset
        let minY = -inset.top
        let maxY = max(minY, sv.contentSize.height - svHeight + inset.bottom)
        return (sv.contentOffset.y, minY, maxY)
        #elseif os(macOS)
        guard let sv = scrollView else { return nil }
        sv.layoutSubtreeIfNeeded()
        let clipBounds = sv.contentView.bounds
        guard clipBounds.height > 1, let docView = sv.documentView else { return nil }
        let docHeight = docView.frame.height
        let insets = sv.contentInsets
        if docView.isFlipped {
            let minY = -insets.top
            let maxY = max(minY, docHeight - clipBounds.height + insets.bottom)
            return (clipBounds.origin.y, minY, maxY)
        } else {
            let minY: CGFloat = 0
            let maxY = max(0, docHeight - clipBounds.height + insets.top + insets.bottom)
            let topOriginY = docHeight - clipBounds.height + insets.top
            let logicalY = min(max(topOriginY - clipBounds.origin.y, minY), maxY)
            return (logicalY, minY, maxY)
        }
        #else
        return nil
        #endif
    }

    #if os(iOS)
    func attach(to candidate: UIScrollView) {
        scrollView = candidate
    }
    #elseif os(macOS)
    func attach(to candidate: NSScrollView) {
        scrollView = candidate
    }

    func detach() {
        scrollView = nil
    }
    #endif
}

#if os(iOS)
private struct WebtoonScrollViewProbe: UIViewRepresentable {
    let controller: WebtoonScrollController

    func makeUIView(context: Context) -> WebtoonScrollProbeUIView {
        let view = WebtoonScrollProbeUIView()
        view.controller = controller
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ uiView: WebtoonScrollProbeUIView, context: Context) {
        uiView.controller = controller
        uiView.resolveScrollViewIfNeeded()
    }
}

private final class WebtoonScrollProbeUIView: UIView {
    weak var controller: WebtoonScrollController?

    override func didMoveToWindow() {
        super.didMoveToWindow()
        resolveScrollViewIfNeeded()
    }

    override func didMoveToSuperview() {
        super.didMoveToSuperview()
        resolveScrollViewIfNeeded()
    }

    func resolveScrollViewIfNeeded() {
        var current = superview
        while let candidate = current {
            if let scrollView = candidate as? UIScrollView {
                controller?.attach(to: scrollView)
                return
            }
            current = candidate.superview
        }
    }
}
#elseif os(macOS)
private struct WebtoonScrollViewProbe: NSViewRepresentable {
    let controller: WebtoonScrollController

    func makeNSView(context: Context) -> WebtoonScrollProbeNSView {
        let view = WebtoonScrollProbeNSView()
        view.controller = controller
        return view
    }

    func updateNSView(_ nsView: WebtoonScrollProbeNSView, context: Context) {
        nsView.controller = controller
        nsView.resolveScrollViewIfNeeded()
    }

    static func dismantleNSView(_ nsView: WebtoonScrollProbeNSView, coordinator: ()) {
        nsView.controller?.detach()
    }
}

private final class WebtoonScrollProbeNSView: NSView {
    weak var controller: WebtoonScrollController?

    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        resolveScrollViewIfNeeded()
    }

    override func viewDidMoveToSuperview() {
        super.viewDidMoveToSuperview()
        resolveScrollViewIfNeeded()
    }

    func resolveScrollViewIfNeeded() {
        if let scrollView = enclosingScrollView {
            controller?.attach(to: scrollView)
        }
    }
}
#endif

private struct MangaWebtoonFramesPreferenceKey: PreferenceKey {
    static var defaultValue: [Int: CGRect] { [:] }

    static func reduce(value: inout [Int: CGRect], nextValue: () -> [Int: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { _, latest in latest })
    }
}

private struct MangaWebtoonStrip: View {
    let state: any MangaReaderPresenterState
    let viewportSize: CGSize
    let controller: MangaReaderInteractionController
    let onOpenLockedUrl: (String) -> Void
    let pageContextMenu: ((ReaderPageState, PageMediaStatusReady) -> AnyView)?

    @State private var syncBox: WebtoonScrollSyncBox
    @State private var scrollController = WebtoonScrollController()
    @State private var committedScale: CGFloat = 1.0
    @State private var pinchScale: CGFloat = 1.0
    @State private var committedOffsetX: CGFloat = 0
    @State private var committedOffsetY: CGFloat = 0
    @State private var lastPanTranslation: CGSize = .zero

    init(
        state: any MangaReaderPresenterState,
        viewportSize: CGSize,
        controller: MangaReaderInteractionController,
        onOpenLockedUrl: @escaping (String) -> Void,
        pageContextMenu: ((ReaderPageState, PageMediaStatusReady) -> AnyView)?
    ) {
        self.state = state
        self.viewportSize = viewportSize
        self.controller = controller
        self.onOpenLockedUrl = onOpenLockedUrl
        self.pageContextMenu = pageContextMenu
        self._syncBox = State(initialValue: WebtoonScrollSyncBox(initialPage: Int(state.currentPageIndex)))
    }

    private var effectiveStripWidth: CGFloat {
        if viewportSize.width >= mangaWideViewportBreakpoint && state.webtoonConstrainWidthOnWideScreen {
            return min(viewportSize.width, mangaMaxConstrainedWebtoonWidth)
        }
        return viewportSize.width
    }

    private var hasPrevBoundary: Bool {
        !(state.previousBoundary is ChapterBoundaryNone)
    }

    private var hasNextBoundary: Bool {
        !(state.nextBoundary is ChapterBoundaryNone)
    }

    private var activeViewport: CGSize {
        controller.viewportSize.width > 1 ? controller.viewportSize : viewportSize
    }

    private var effectiveScale: CGFloat {
        min(max(committedScale * pinchScale, 1.0), mangaMaxReaderZoom)
    }

    private var isZoomed: Bool {
        effectiveScale > mangaZoomEpsilon
    }

    private var clampedOffset: CGSize {
        guard isZoomed else { return .zero }
        let maxX = max((activeViewport.width * (effectiveScale - 1)) / 2, 0)
        let maxY = max((activeViewport.height * (effectiveScale - 1)) / 2, 0)
        return CGSize(
            width: min(max(committedOffsetX, -maxX), maxX),
            height: min(max(committedOffsetY, -maxY), maxY)
        )
    }

    var body: some View {
        ScrollViewReader { scrollProxy in
            let _ = installWebtoonHandlers()
            webtoonScrollView
                .onPreferenceChange(MangaWebtoonFramesPreferenceKey.self) { frames in
                    handleVisibleWebtoonFrames(frames, scrollProxy: scrollProxy)
                }
                .onAppear {
                    installWebtoonHandlers()
                    controller.clearPagerStatus()
                    bindZoomHandlers()
                    let initialPage = Int(state.currentPageIndex)
                    syncBox.lastReportedPage = initialPage
                    if initialPage > 0 && state.pages.indices.contains(initialPage) {
                        syncBox.programmaticTargetPage = initialPage
                        syncBox.settledCenterPage = nil
                        scrollToWebtoonPage(initialPage, using: scrollProxy)
                    }
                }
                .onChange(of: state.currentPageIndex) { _, newIndex in
                    let targetPage = Int(newIndex)
                    guard !state.pages.isEmpty, state.pages.indices.contains(targetPage) else { return }
                    if targetPage != syncBox.lastReportedPage {
                        syncBox.lastReportedPage = targetPage
                        syncBox.programmaticTargetPage = targetPage
                        syncBox.settledCenterPage = nil
                        committedOffsetY = 0
                        controller.updateZoomMetrics(scale: effectiveScale, offset: clampedOffset)
                        scrollToWebtoonPage(targetPage, using: scrollProxy)
                    }
                }
                .onChange(of: isZoomed) { _, _ in
                    bindZoomHandlers()
                }
                .onChange(of: state.webtoonConstrainWidthOnWideScreen) { _, _ in
                    let targetPage = Int(state.currentPageIndex)
                    guard !state.pages.isEmpty, state.pages.indices.contains(targetPage) else { return }
                    syncBox.programmaticTargetPage = targetPage
                    syncBox.settledCenterPage = nil
                    scrollToWebtoonPage(targetPage, using: scrollProxy)
                }
                .onChange(of: clampedOffset) { _, newOffset in
                    controller.updateZoomMetrics(scale: effectiveScale, offset: newOffset)
                }
                .onDisappear {
                    guard controller.activeWebtoonController === scrollController else { return }
                    controller.clearWebtoonStatus()
                    controller.zoomResetHandler = nil
                    controller.doubleTapZoomHandler = nil
                    controller.updateZoomMetrics(scale: 1.0, offset: .zero)
                }
        }
    }

    private var webtoonScrollView: some View {
        ScrollView(.vertical, showsIndicators: true) {
            webtoonStackContent
        }
        #if os(iOS)
        .scrollDisabled(isZoomed)
        #endif
        .coordinateSpace(name: "manga_webtoon_scroll")
        .scaleEffect(effectiveScale)
        .offset(clampedOffset)
        .frame(width: max(viewportSize.width, 1), height: max(viewportSize.height, 1))
        .clipped()
        .accessibilityIdentifier("manga_webtoon_list")
        .contentShape(Rectangle())
        .mangaZoneTap(
            in: viewportSize,
            controller: controller,
            onDoubleTap: { tapLocation in
                toggleDoubleTapZoom(at: tapLocation)
            }
        )
        .simultaneousGesture(
            MagnifyGesture()
                .onChanged { value in
                    pinchScale = value.magnification
                    let scale = effectiveScale
                    if scale <= mangaZoomEpsilon {
                        controller.updateZoomMetrics(scale: 1.0, offset: .zero)
                    } else {
                        controller.updateZoomMetrics(scale: scale, offset: clampedOffset)
                    }
                }
                .onEnded { value in
                    let nextScale = min(max(committedScale * value.magnification, 1.0), mangaMaxReaderZoom)
                    pinchScale = 1.0
                    if nextScale <= mangaZoomEpsilon {
                        resetZoom()
                    } else {
                        let maxX = max((activeViewport.width * (nextScale - 1)) / 2, 0)
                        let maxY = max((activeViewport.height * (nextScale - 1)) / 2, 0)
                        committedScale = nextScale
                        committedOffsetX = min(max(committedOffsetX, -maxX), maxX)
                        committedOffsetY = min(max(committedOffsetY, -maxY), maxY)
                        controller.updateZoomMetrics(scale: nextScale, offset: clampedOffset)
                    }
                }
        )
        .modifier(
            ZoomPanGestureModifier(
                isEnabled: isZoomed,
                onChanged: { translation in
                    let delta = CGSize(
                        width: translation.width - lastPanTranslation.width,
                        height: translation.height - lastPanTranslation.height
                    )
                    lastPanTranslation = translation
                    applyZoomPanDelta(delta)
                },
                onEnded: { translation in
                    let delta = CGSize(
                        width: translation.width - lastPanTranslation.width,
                        height: translation.height - lastPanTranslation.height
                    )
                    lastPanTranslation = .zero
                    applyZoomPanDelta(delta)
                }
            )
        )
    }

    private var webtoonStackContent: some View {
        let pages = state.pages
        return VStack(spacing: 0) {
            if hasPrevBoundary {
                MangaBoundaryCardView(
                    boundary: state.previousBoundary,
                    isPrevious: true,
                    isStandaloneLocked: false,
                    onOpenUnlocked: { state.openAdjacentChapter(boundary: $0) },
                    onOpenLockedUrl: onOpenLockedUrl,
                    actionIdentifier: "manga_boundary_prev_action"
                )
                .frame(maxWidth: .infinity)
                .padding(.vertical, 24)
                .background { webtoonFrame(-1) }
                .id("webtoon_boundary_prev")
            }

            LazyVStack(spacing: 0) {
                ForEach(pages.indices, id: \.self) { pageIdx in
                    webtoonPageRow(pages[pageIdx], pageIdx: pageIdx)
                }
            }
            .frame(height: webtoonHeight(pages[...]), alignment: .top)
            .id("webtoon_pages")

            if hasNextBoundary {
                MangaBoundaryCardView(
                    boundary: state.nextBoundary,
                    isPrevious: false,
                    isStandaloneLocked: false,
                    onOpenUnlocked: { state.openAdjacentChapter(boundary: $0) },
                    onOpenLockedUrl: onOpenLockedUrl,
                    actionIdentifier: "manga_boundary_next_action"
                )
                .frame(maxWidth: .infinity)
                .padding(.vertical, 24)
                .background { webtoonFrame(state.pages.count) }
                .id("webtoon_boundary_next")
            }
        }
        .background {
            WebtoonScrollViewProbe(controller: scrollController)
                .frame(width: 0, height: 0)
                .accessibilityHidden(true)
        }
    }

    private func webtoonPageRow(_ page: ReaderPageState, pageIdx: Int) -> some View {
        let ratio = webtoonAspectRatio(page.aspectRatio?.floatValue)
        let currentOffset = clampedOffset
        return ReaderPageCell(
            page: page,
            state: state,
            contentMode: .fit,
            alignment: .top,
            zoomGeometryToken: ZoomGeometryToken(
                scale: effectiveScale,
                offsetX: currentOffset.width,
                offsetY: currentOffset.height
            ),
            pageContextMenu: pageContextMenu
        )
        .aspectRatio(ratio, contentMode: .fit)
        .frame(width: max(effectiveStripWidth, 1))
        .frame(maxWidth: .infinity)
        .background { webtoonFrame(pageIdx) }
        .id(pageIdx)
    }

    private func webtoonFrame(_ index: Int) -> some View {
        GeometryReader { proxy in
            Color.clear.preference(
                key: MangaWebtoonFramesPreferenceKey.self,
                value: [index: proxy.frame(in: .named("manga_webtoon_scroll"))]
            )
        }
    }

    private func bindZoomHandlers() {
        controller.doubleTapZoomHandler = { point in
            toggleDoubleTapZoom(at: point)
        }
        controller.webtoonZoomPanHandler = { delta in
            applyZoomPanDelta(delta)
        }
        if isZoomed {
            controller.zoomResetHandler = {
                withAnimation(.spring(duration: 0.22)) {
                    resetZoom()
                }
            }
            controller.updateZoomMetrics(scale: effectiveScale, offset: clampedOffset)
        } else {
            controller.zoomResetHandler = nil
            controller.updateZoomMetrics(scale: 1.0, offset: .zero)
        }
    }

    private func toggleDoubleTapZoom(at tapLocation: CGPoint) {
        let viewport = activeViewport
        if isZoomed {
            withAnimation(.spring(duration: 0.22)) {
                resetZoom()
            }
        } else {
            let targetScale = mangaDoubleTapZoom
            let centerX = viewport.width / 2
            let centerY = viewport.height / 2
            let rawOffsetX = (centerX - tapLocation.x) * (targetScale - 1)
            let desiredScreenDeltaY = (centerY - tapLocation.y) * (targetScale - 1)
            let maxX = max((viewport.width * (targetScale - 1)) / 2, 0)
            let maxY = max((viewport.height * (targetScale - 1)) / 2, 0)
            let consumedScrollY = scrollController.scrollByRawDeltaY(-desiredScreenDeltaY / targetScale, animated: false)
            let unconsumedScreenY = desiredScreenDeltaY + consumedScrollY * targetScale
            let nextX = min(max(rawOffsetX, -maxX), maxX)
            let nextY = min(max(unconsumedScreenY, -maxY), maxY)
            withAnimation(.spring(duration: 0.22)) {
                committedScale = targetScale
                pinchScale = 1.0
                committedOffsetX = nextX
                committedOffsetY = nextY
                lastPanTranslation = .zero
            }
            controller.updateZoomMetrics(scale: targetScale, offset: CGSize(width: nextX, height: nextY))
        }
    }

    private func resetZoom() {
        committedScale = 1.0
        pinchScale = 1.0
        committedOffsetX = 0
        committedOffsetY = 0
        lastPanTranslation = .zero
        controller.updateZoomMetrics(scale: 1.0, offset: .zero)
    }

    @discardableResult
    private func applyZoomPanDelta(_ delta: CGSize) -> Bool {
        let scale = effectiveScale
        guard scale > mangaZoomEpsilon else { return false }
        let viewport = activeViewport
        let maxX = max((viewport.width * (scale - 1)) / 2, 0)
        let maxY = max((viewport.height * (scale - 1)) / 2, 0)

        let prevX = committedOffsetX
        let prevY = committedOffsetY
        committedOffsetX = min(max(committedOffsetX + delta.width, -maxX), maxX)

        var nextY = min(max(committedOffsetY, -maxY), maxY)
        var consumedScrollY: CGFloat = 0
        if abs(delta.height) > 0.001 {
            var remY = delta.height
            if abs(nextY) > 0.001, nextY * remY < 0 {
                let drained = nextY > 0 ? max(remY, -nextY) : min(remY, -nextY)
                nextY += drained
                remY -= drained
            }
            if abs(remY) > 0.001 {
                consumedScrollY = scrollController.scrollByRawDeltaY(-remY / scale, animated: false)
                let unconsumedScreenY = remY + consumedScrollY * scale
                nextY = min(max(nextY + unconsumedScreenY, -maxY), maxY)
            }
        }
        committedOffsetY = nextY
        controller.updateZoomMetrics(scale: scale, offset: CGSize(width: committedOffsetX, height: committedOffsetY))
        return abs(committedOffsetX - prevX) > 0.5 || abs(committedOffsetY - prevY) > 0.5 || abs(consumedScrollY) > 0.5
    }

    private func installWebtoonHandlers() {
        controller.activeWebtoonController = scrollController
        controller.webtoonStepForward = {
            let stepPx = max(activeViewport.height * 0.8, 1)
            if isZoomed {
                if applyZoomPanDelta(CGSize(width: 0, height: -stepPx)) {
                    return
                }
                if state.nextBoundary is ChapterBoundaryUnlocked {
                    if !state.slots.isEmpty, Int(state.currentSlotIndex) != state.slots.count - 1 {
                        state.goToSlot(slotIndex: Int32(state.slots.count - 1))
                    }
                    state.stepForward()
                }
                return
            }
            if scrollController.hasAttachedScrollView {
                if scrollController.canScrollForward {
                    _ = scrollController.scrollByRawDeltaY(stepPx, animated: true)
                } else if state.nextBoundary is ChapterBoundaryUnlocked {
                    if !state.slots.isEmpty, Int(state.currentSlotIndex) != state.slots.count - 1 {
                        state.goToSlot(slotIndex: Int32(state.slots.count - 1))
                    }
                    state.stepForward()
                }
            } else {
                state.stepForward()
            }
        }

        controller.webtoonStepBackward = {
            let stepPx = max(activeViewport.height * 0.8, 1)
            if isZoomed {
                if applyZoomPanDelta(CGSize(width: 0, height: stepPx)) {
                    return
                }
                if state.previousBoundary is ChapterBoundaryUnlocked {
                    if !state.slots.isEmpty, Int(state.currentSlotIndex) != 0 {
                        state.goToSlot(slotIndex: 0)
                    }
                    state.stepBackward()
                }
                return
            }
            if scrollController.hasAttachedScrollView {
                if scrollController.canScrollBackward {
                    _ = scrollController.scrollByRawDeltaY(-stepPx, animated: true)
                } else if state.previousBoundary is ChapterBoundaryUnlocked {
                    if !state.slots.isEmpty, Int(state.currentSlotIndex) != 0 {
                        state.goToSlot(slotIndex: 0)
                    }
                    state.stepBackward()
                }
            } else {
                state.stepBackward()
            }
        }
    }

    private func handleVisibleWebtoonFrames(_ frames: [Int: CGRect], scrollProxy: ScrollViewProxy) {
        guard !state.pages.isEmpty, viewportSize.height > 0 else { return }
        let viewportCenterY = viewportSize.height / 2
        let visibleCandidates = frames.filter { index, rect in
            index >= 0 && index < state.pages.count && rect.maxY > 0 && rect.minY < viewportSize.height
        }
        // The lazy stack's estimated content height can lag behind the measured first and last rows.
        let lastPage = state.pages.count - 1
        let centerPage: Int?
        if let first = frames[hasPrevBoundary ? -1 : 0], first.minY >= -1 {
            centerPage = 0
        } else if let last = frames[hasNextBoundary ? state.pages.count : lastPage], last.maxY <= viewportSize.height + 1 {
            centerPage = lastPage
        } else if let containing = visibleCandidates.first(where: { $0.value.minY <= viewportCenterY && viewportCenterY < $0.value.maxY }) {
            centerPage = containing.key
        } else {
            centerPage = visibleCandidates.min(by: { abs($0.value.midY - viewportCenterY) < abs($1.value.midY - viewportCenterY) })?.key
        }
        guard let centerPage else {
            if let target = syncBox.programmaticTargetPage {
                scrollToWebtoonPage(target, using: scrollProxy)
            }
            return
        }
        if let target = syncBox.programmaticTargetPage {
            if let targetRect = frames[target],
               targetRect.maxY > 0 && targetRect.minY < viewportSize.height {
                syncBox.programmaticTargetPage = nil
                syncBox.lastReportedPage = target
                syncBox.settledCenterPage = centerPage
            } else {
                scrollToWebtoonPage(target, using: scrollProxy)
            }
            return
        }

        if let settled = syncBox.settledCenterPage {
            if centerPage == settled {
                return
            }
            syncBox.settledCenterPage = nil
        }

        guard centerPage != syncBox.lastReportedPage else { return }
        syncBox.lastReportedPage = centerPage
        if centerPage != Int(state.currentPageIndex) {
            state.goToPage(pageIndex: Int32(centerPage))
        }
    }

    private func webtoonHeight(_ pages: ArraySlice<ReaderPageState>) -> CGFloat {
        let stripWidth = max(effectiveStripWidth, 1)
        return pages.reduce(0) { $0 + stripWidth / webtoonAspectRatio($1.aspectRatio?.floatValue) }
    }

    private func scrollToWebtoonPage(_ page: Int, using proxy: ScrollViewProxy) {
        let pages = state.pages
        guard pages.indices.contains(page) else { return }
        // Target the measured group so realizing a distant lazy row cannot reposition the scroll view.
        let travel = webtoonHeight(pages[...]) - viewportSize.height
        let fraction = travel > 0 ? min(webtoonHeight(pages.prefix(page)) / travel, 1) : 0
        proxy.scrollTo("webtoon_pages", anchor: UnitPoint(x: 0.5, y: fraction))
    }

    private func webtoonAspectRatio(_ rawRatio: Float?) -> CGFloat {
        guard let rawRatio, rawRatio.isFinite, rawRatio > 0 else {
            return mangaDefaultWebtoonAspectRatio
        }
        return CGFloat(rawRatio)
    }
}

// MARK: - Page Cell & Retry UI

private struct ReaderPageCell: View {
    let page: ReaderPageState
    let state: any MangaReaderPresenterState
    let contentMode: SwiftUI.ContentMode
    let alignment: Alignment
    var zoomGeometryToken: ZoomGeometryToken = .init()
    var pageContextMenu: ((ReaderPageState, PageMediaStatusReady) -> AnyView)? = nil

    @State private var failedOriginalURL: String?
    @State private var retryNonce = 0
    @State private var imageLoaded = false

    var body: some View {
        ZStack(alignment: alignment) {
            switch onEnum(of: page.status) {
            case .loading(let loading):
                ZStack {
                    if let previewUrl = loading.previewUrl, !previewUrl.isEmpty {
                        NetworkImage(
                            data: UiMediaImage(
                                url: previewUrl,
                                proxy: loading.proxy,
                                headers: loading.customHeaders
                            ),
                            contentMode: contentMode
                        )
                    } else {
                        Rectangle()
                            .fill(Color.white.opacity(0.06))
                    }
                    ProgressView()
                        .tint(.white)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)

            case .ready(let ready):
                let translating = state.imageTranslation.item(media: ready.original).loading
                if failedOriginalURL == ready.displayed.url && ready.displayed.url == ready.original.url {
                    ReaderPageErrorView(
                        message: nil,
                        pageId: page.id,
                        onRetry: {
                            failedOriginalURL = nil
                            imageLoaded = false
                            retryNonce += 1
                            state.retryPage(pageId: page.id)
                        }
                    )
                } else {
                    ZStack(alignment: alignment) {
                        readyMediaView(ready: ready)
                            .blur(radius: translating ? 8 : 0)
                            .id("\(page.id)|\(ready.displayed.url)|\(retryNonce)")

                        if translating || (!imageLoaded && ready.displayed.isRemote && !(ready.displayed is UiMediaUgoira)) {
                            ProgressView()
                                .tint(.white)
                                .frame(maxWidth: .infinity, maxHeight: .infinity)
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: alignment)
                    .contentShape(Rectangle())
                    .modifier(
                        ReaderPageContextMenuModifier(
                            page: page,
                            ready: ready,
                            pageContextMenu: pageContextMenu
                        )
                    )
                    .onAppear {
                        if !ready.displayed.isRemote {
                            imageLoaded = true
                        }
                    }
                    .onChange(of: ready.displayed.url) { _, _ in
                        failedOriginalURL = nil
                        imageLoaded = !ready.displayed.isRemote
                    }
                }

            case .failed(let failed):
                ReaderPageErrorView(
                    message: failed.error.message,
                    pageId: page.id,
                    onRetry: {
                        state.retryPage(pageId: page.id)
                    }
                )
            }
        }
        .background {
            MangaPageFrameReporterView(pageIndex: Int(page.index), zoomToken: zoomGeometryToken)
        }
        .accessibilityIdentifier("manga_reader_page_\(page.index)")
    }

    @ViewBuilder
    private func readyMediaView(ready: PageMediaStatusReady) -> some View {
        if let ugoira = ready.displayed as? UiMediaUgoira {
            UgoiraPlayer(media: ugoira, contentMode: contentMode)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: alignment)
        } else {
            NetworkImage(
                data: ready.displayed,
                placeholder: ready.displayed.preview,
                contentMode: contentMode,
                onImageFailure: {
                    if ready.displayed.url != ready.original.url {
                        state.imageTranslation.imageFailed(media: ready.original, url: ready.displayed.url)
                    } else {
                        failedOriginalURL = ready.original.url
                    }
                    imageLoaded = false
                },
                onImageSize: { size in
                    imageLoaded = true
                    failedOriginalURL = nil
                    state.onPageDimensionsLoaded(
                        pageId: page.id,
                        width: Float(size.width),
                        height: Float(size.height)
                    )
                }
            )
            .originalImage()
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: alignment)
        }
    }
}

private struct ReaderPageContextMenuModifier: ViewModifier {
    let page: ReaderPageState
    let ready: PageMediaStatusReady
    let pageContextMenu: ((ReaderPageState, PageMediaStatusReady) -> AnyView)?

    @ViewBuilder
    func body(content: Content) -> some View {
        if let pageContextMenu {
            content.contextMenu {
                pageContextMenu(page, ready)
            }
        } else {
            content
        }
    }
}

private struct MangaPageFrameReporterView: View {
    let pageIndex: Int
    var zoomToken: ZoomGeometryToken = .init()
    @Environment(\.mangaReaderIsScrollSettled) private var isScrollSettled
    @Environment(\.mangaReaderScrollAlignmentRevision) private var alignmentRevision

    var body: some View {
        if isScrollSettled {
            GeometryReader { proxy in
                let _ = zoomToken
                Color.clear
                    .id(alignmentRevision)
                    .preference(
                        key: MangaReaderPageFramesPreferenceKey.self,
                        value: [pageIndex: proxy.frame(in: .named("manga_reader_canvas"))]
                    )
            }
        }
    }
}

private struct ReaderPageErrorView: View {
    let message: String?
    let pageId: String
    let onRetry: () -> Void

    private var retryLabel: String {
        FlareAppleUILocalization.string("manga_page_retry", fallback: "Retry page")
    }

    var body: some View {
        VStack(spacing: 12) {
            Text(verbatim: message?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false ? (message ?? retryLabel) : retryLabel)
                .font(.subheadline)
                .foregroundStyle(.white.opacity(0.85))
                .multilineTextAlignment(.center)

            Button(action: onRetry) {
                HStack(spacing: 6) {
                    Image(systemName: "arrow.clockwise")
                    Text(verbatim: retryLabel)
                }
            }
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier("manga_page_retry_\(pageId)")
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .preference(key: MangaReaderRetryPageIdsPreferenceKey.self, value: [pageId])
    }
}

// MARK: - Tap & Keyboard Modifiers

private extension View {
    @ViewBuilder
    func mangaZoneTap(
        in viewportSize: CGSize,
        controller: MangaReaderInteractionController,
        onDoubleTap: ((CGPoint) -> Void)?
    ) -> some View {
        if let onDoubleTap {
            gesture(
                SpatialTapGesture(count: 2)
                    .onEnded { value in
                        onDoubleTap(value.location)
                    }
                    .exclusively(
                        before: SpatialTapGesture(count: 1)
                            .onEnded { value in
                                controller.handleTap(at: value.location, in: viewportSize)
                            }
                    )
            )
        } else {
            gesture(
                SpatialTapGesture(count: 1)
                    .onEnded { value in
                        controller.handleTap(at: value.location, in: viewportSize)
                    }
            )
        }
    }
}

private struct MangaReaderHardwareKeyboardBridge: View {
    let controller: MangaReaderInteractionController
    let isOverlayPresented: Bool

    var body: some View {
        #if os(macOS)
        MacReaderKeyEventMonitorRepresentable(controller: controller)
            .frame(width: 0, height: 0)
            .accessibilityHidden(true)
        #elseif os(iOS)
        IOSReaderKeyCommandRepresentable(
            controller: controller,
            isOverlayPresented: isOverlayPresented
        )
        .frame(width: 0, height: 0)
        .accessibilityHidden(true)
        #endif
    }
}

#if os(macOS)
private struct MacReaderKeyEventMonitorRepresentable: NSViewRepresentable {
    let controller: MangaReaderInteractionController

    func makeCoordinator() -> MacReaderKeyMonitorCoordinator {
        MacReaderKeyMonitorCoordinator()
    }

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        context.coordinator.attach(to: view, controller: controller)
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        context.coordinator.attach(to: nsView, controller: controller)
    }

    static func dismantleNSView(_ nsView: NSView, coordinator: MacReaderKeyMonitorCoordinator) {
        coordinator.detach()
    }
}

@MainActor
private final class MacReaderKeyMonitorCoordinator {
    private weak var hostView: NSView?
    private weak var controller: MangaReaderInteractionController?
    private nonisolated(unsafe) var monitor: Any?

    func attach(to view: NSView, controller: MangaReaderInteractionController) {
        self.hostView = view
        self.controller = controller
        guard monitor == nil else { return }
        monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self else { return event }
            let keyCode = event.keyCode
            let modifierFlags = event.modifierFlags
            let consumed = MainActor.assumeIsolated {
                self.handleKeyDown(keyCode: keyCode, modifierFlags: modifierFlags)
            }
            return consumed ? nil : event
        }
    }

    func detach() {
        if let monitor {
            NSEvent.removeMonitor(monitor)
            self.monitor = nil
        }
    }

    deinit {
        if let monitor {
            NSEvent.removeMonitor(monitor)
        }
    }

    private func handleKeyDown(keyCode: UInt16, modifierFlags: NSEvent.ModifierFlags) -> Bool {
        guard let hostView,
              let window = hostView.window,
              let controller,
              window.isKeyWindow || NSApp.keyWindow == nil || NSApp.keyWindow?.parent === window,
              !(window.firstResponder is NSText),
              !(NSApp.keyWindow?.firstResponder is NSText),
              window.attachedSheet == nil,
              !modifierFlags.contains(.command),
              !modifierFlags.contains(.control),
              !modifierFlags.contains(.option) else {
            return false
        }

        let hasVisibleChildWindows = !(window.childWindows ?? []).filter(\.isVisible).isEmpty
        if hasVisibleChildWindows && !controller.isOverlayPresented {
            return false
        }

        let mapped: MangaReaderKeyInput?
        switch keyCode {
        case 123:
            mapped = .leftArrow
        case 124:
            mapped = .rightArrow
        case 126:
            mapped = .upArrow
        case 125:
            mapped = .downArrow
        case 116:
            mapped = .pageUp
        case 121:
            mapped = .pageDown
        case 115:
            mapped = .home
        case 119:
            mapped = .end
        case 49:
            mapped = .space(shift: modifierFlags.contains(.shift))
        case 53:
            mapped = .escape
        default:
            mapped = nil
        }

        guard let mapped else { return false }
        return controller.handleKey(mapped)
    }
}
#elseif os(iOS)
private struct IOSReaderKeyCommandRepresentable: UIViewControllerRepresentable {
    let controller: MangaReaderInteractionController
    let isOverlayPresented: Bool

    func makeUIViewController(context: Context) -> IOSReaderKeyCommandViewController {
        let viewController = IOSReaderKeyCommandViewController()
        viewController.controller = controller
        return viewController
    }

    func updateUIViewController(_ uiViewController: IOSReaderKeyCommandViewController, context: Context) {
        uiViewController.controller = controller
        if !isOverlayPresented, uiViewController.view.window != nil, !uiViewController.isFirstResponder {
            uiViewController.becomeFirstResponder()
        }
    }
}

private final class IOSReaderKeyCommandViewController: UIViewController {
    weak var controller: MangaReaderInteractionController?

    override var canBecomeFirstResponder: Bool { true }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if view.window != nil {
            becomeFirstResponder()
        }
    }

    private var hasExternalPresentedModal: Bool {
        var root: UIViewController = self
        while let parent = root.parent {
            root = parent
        }
        return root.presentedViewController != nil
    }

    override var keyCommands: [UIKeyCommand]? {
        let overlayOpen = controller?.isOverlayPresented == true
        if !overlayOpen && hasExternalPresentedModal {
            return nil
        }
        if overlayOpen {
            let escapeCommand = UIKeyCommand(
                input: UIKeyCommand.inputEscape,
                modifierFlags: [],
                action: #selector(handleEscape)
            )
            escapeCommand.wantsPriorityOverSystemBehavior = true
            return [escapeCommand]
        }
        let commands: [(String, UIKeyModifierFlags, Selector)] = [
            (UIKeyCommand.inputLeftArrow, [], #selector(handleLeftArrow)),
            (UIKeyCommand.inputRightArrow, [], #selector(handleRightArrow)),
            (UIKeyCommand.inputUpArrow, [], #selector(handleUpArrow)),
            (UIKeyCommand.inputDownArrow, [], #selector(handleDownArrow)),
            (UIKeyCommand.inputPageUp, [], #selector(handlePageUp)),
            (UIKeyCommand.inputPageDown, [], #selector(handlePageDown)),
            (UIKeyCommand.inputHome, [], #selector(handleHome)),
            (UIKeyCommand.inputEnd, [], #selector(handleEnd)),
            (" ", [], #selector(handleSpace)),
            (" ", [.shift], #selector(handleShiftSpace)),
            (UIKeyCommand.inputEscape, [], #selector(handleEscape)),
        ]
        return commands.map { input, modifiers, action in
            let command = UIKeyCommand(input: input, modifierFlags: modifiers, action: action)
            command.wantsPriorityOverSystemBehavior = true
            return command
        }
    }

    @objc private func handleLeftArrow() {
        controller?.handleKey(.leftArrow)
    }

    @objc private func handleRightArrow() {
        controller?.handleKey(.rightArrow)
    }

    @objc private func handleUpArrow() {
        controller?.handleKey(.upArrow)
    }

    @objc private func handleDownArrow() {
        controller?.handleKey(.downArrow)
    }

    @objc private func handlePageUp() {
        controller?.handleKey(.pageUp)
    }

    @objc private func handlePageDown() {
        controller?.handleKey(.pageDown)
    }

    @objc private func handleHome() {
        controller?.handleKey(.home)
    }

    @objc private func handleEnd() {
        controller?.handleKey(.end)
    }

    @objc private func handleSpace() {
        controller?.handleKey(.space(shift: false))
    }

    @objc private func handleShiftSpace() {
        controller?.handleKey(.space(shift: true))
    }

    @objc private func handleEscape() {
        controller?.handleKey(.escape)
    }
}
#endif
