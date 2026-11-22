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
    case space(shift: Bool)
    case escape
}

public struct MangaReaderPageFramesPreferenceKey: PreferenceKey {
    public static var defaultValue: [Int: CGRect] { [:] }

    public static func reduce(value: inout [Int: CGRect], nextValue: () -> [Int: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { _, latest in latest })
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

    @ObservationIgnored var stateProvider: (() -> (any MangaReaderPresenterState)?)?
    @ObservationIgnored var hudVisibleProvider: (() -> Bool)?
    @ObservationIgnored var onToggleHudHandler: (() -> Void)?
    @ObservationIgnored var onBackHandler: (() -> Void)?
    @ObservationIgnored var pagedStepForward: (() -> Void)?
    @ObservationIgnored var pagedStepBackward: (() -> Void)?
    @ObservationIgnored var pagedScrollToBoundary: ((Bool) -> Void)?
    @ObservationIgnored var webtoonScrollByPage: ((Bool) -> Bool)?
    @ObservationIgnored var zoomResetHandler: (() -> Void)?
    @ObservationIgnored fileprivate var isPerformingProgrammaticScroll = false

    public init() {}

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
        guard let readerState = stateProvider?() else { return false }
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
            if readerState.direction == .webtoon {
                if webtoonScrollByPage?(true) != true {
                    readerState.stepForward()
                }
            } else {
                performStepForward(readerState)
            }
            return true

        case .upArrow:
            if readerState.direction == .webtoon {
                if webtoonScrollByPage?(false) != true {
                    readerState.stepBackward()
                }
            } else {
                performStepBackward(readerState)
            }
            return true

        case .space(let shift):
            if shift {
                if readerState.direction == .webtoon, webtoonScrollByPage?(false) == true {
                    return true
                }
                performStepBackward(readerState)
            } else {
                if readerState.direction == .webtoon, webtoonScrollByPage?(true) == true {
                    return true
                }
                performStepForward(readerState)
            }
            return true

        case .escape:
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
        nextBoundaryPage: Int
    ) {
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
    }

    private func performStepForward(_ readerState: any MangaReaderPresenterState) {
        if readerState.direction != .webtoon, let pagedStepForward {
            pagedStepForward()
        } else {
            readerState.stepForward()
        }
    }

    private func performStepBackward(_ readerState: any MangaReaderPresenterState) {
        if readerState.direction != .webtoon, let pagedStepBackward {
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
    @State private var fallbackController = MangaReaderInteractionController()
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
        controller: MangaReaderInteractionController? = nil
    ) {
        self.state = state
        self.hudVisible = hudVisible
        self.onToggleHud = onToggleHud
        self.onBack = onBack
        self.onOpenExternalURL = onOpenExternalURL
        self.externalController = controller
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
                            controller.webtoonScrollByPage = nil
                            controller.zoomResetHandler = nil
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
                                controller.webtoonScrollByPage = nil
                                controller.zoomResetHandler = nil
                            }
                    } else {
                        ListErrorView(error: errorState.throwable) {
                            state.retryChapter()
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .preferredColorScheme(.dark)
                        .onAppear {
                            controller.clearPagerStatus()
                            controller.webtoonScrollByPage = nil
                            controller.zoomResetHandler = nil
                        }
                    }

                case .success:
                    Group {
                        if state.direction == .webtoon {
                            MangaWebtoonStrip(
                                state: state,
                                viewportSize: viewportSize,
                                controller: controller,
                                onOpenLockedUrl: openLockedURL
                            )
                        } else {
                            MangaPagedCanvas(
                                state: state,
                                viewportSize: viewportSize,
                                controller: controller,
                                onOpenLockedUrl: openLockedURL
                            )
                            .id("\(state.activeChapterId)|\(state.direction == .rtl)")
                        }
                    }
                    .id(state.activeChapterId)
                }
            }
            .frame(width: viewportSize.width, height: viewportSize.height)
            .clipped()
            .coordinateSpace(name: "manga_reader_canvas")
            .background {
                MangaReaderHardwareKeyboardBridge(controller: controller)
            }
            .onAppear {
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
        .mangaPrefetchMedia(state: state)
        .accessibilityIdentifier("manga_reader_canvas")
    }

    private func bindController(_ controller: MangaReaderInteractionController) {
        controller.stateProvider = { [state] in state }
        controller.hudVisibleProvider = { [hudVisible] in hudVisible }
        controller.onToggleHudHandler = onToggleHud
        controller.onBackHandler = onBack
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

private struct MangaPagedCanvas: View {
    let state: any MangaReaderPresenterState
    let viewportSize: CGSize
    let controller: MangaReaderInteractionController
    let onOpenLockedUrl: (String) -> Void

    @State private var activePagerPage: Int?
    @State private var programmaticScrollTarget: Int?
    @State private var programmaticScrollGeneration = 0
    @State private var mountScrollGeneration = 0
    @State private var hasCompletedInitialScroll = false
    @State private var isCurrentPageZoomed = false

    init(
        state: any MangaReaderPresenterState,
        viewportSize: CGSize,
        controller: MangaReaderInteractionController,
        onOpenLockedUrl: @escaping (String) -> Void
    ) {
        self.state = state
        self.viewportSize = viewportSize
        self.controller = controller
        self.onOpenLockedUrl = onOpenLockedUrl

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

    private var hasPrevBoundary: Bool {
        !(state.previousBoundary is ChapterBoundaryNone)
    }

    private var hasNextBoundary: Bool {
        !(state.nextBoundary is ChapterBoundaryNone)
    }

    private var startBoundaryOffset: Int {
        hasPrevBoundary ? 1 : 0
    }

    private var totalPagerPages: Int {
        guard !state.slots.isEmpty else { return 0 }
        return state.slots.count + startBoundaryOffset + (hasNextBoundary ? 1 : 0)
    }

    private var prevBoundaryPage: Int {
        hasPrevBoundary ? 0 : -1
    }

    private var nextBoundaryPage: Int {
        hasNextBoundary && !state.slots.isEmpty ? (startBoundaryOffset + state.slots.count) : -1
    }

    private var isRtl: Bool {
        state.direction == .rtl
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
                    }
            } else {
                ScrollViewReader { scrollProxy in
                    let _ = installStepHandlers(scrollProxy: scrollProxy)
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: 0) {
                            ForEach(0..<totalPagerPages, id: \.self) { pagerPage in
                                pagerCell(for: pagerPage)
                                    .frame(width: max(viewportSize.width, 1), height: max(viewportSize.height, 1))
                                    .id(pagerPage)
                            }
                        }
                        .scrollTargetLayout()
                    }
                    .syncSettledScrollPage(totalPages: totalPagerPages) { settledPage in
                        guard hasCompletedInitialScroll,
                              !controller.isPerformingProgrammaticScroll,
                              activePagerPage != settledPage else { return }
                        activePagerPage = settledPage
                    }
                    .scrollIndicators(.hidden)
                    .scrollTargetBehavior(.paging)
                    .scrollPosition(id: $activePagerPage)
                    .scrollDisabled(isCurrentPageZoomed)
                    .environment(\.layoutDirection, isRtl ? .rightToLeft : .leftToRight)
                    .id(isRtl)
                    .onAppear {
                        controller.webtoonScrollByPage = nil
                        isCurrentPageZoomed = false
                        controller.zoomResetHandler = nil
                        hasCompletedInitialScroll = false
                        mountScrollGeneration = programmaticScrollGeneration
                        let initial = clampedPagerPage(Int(state.currentSlotIndex) + startBoundaryOffset)
                        if activePagerPage == initial {
                            programmaticScrollTarget = nil
                        } else if initial > 0 {
                            programmaticScrollTarget = initial
                        }
                        controller.updatePagerStatus(
                            activePage: initial,
                            prevBoundaryPage: prevBoundaryPage,
                            nextBoundaryPage: nextBoundaryPage
                        )
                    }
                    .task {
                        defer { hasCompletedInitialScroll = true }
                        let expectedGen = mountScrollGeneration
                        guard programmaticScrollGeneration == expectedGen else { return }
                        let initial = clampedPagerPage(Int(state.currentSlotIndex) + startBoundaryOffset)
                        if activePagerPage != initial {
                            programmaticScrollTarget = initial
                            activePagerPage = initial
                            scrollProxy.scrollTo(initial)
                        } else if initial > 0 {
                            scrollProxy.scrollTo(initial)
                        }
                        await Task.yield()
                        guard programmaticScrollGeneration == expectedGen else { return }
                        let settledTarget = clampedPagerPage(Int(state.currentSlotIndex) + startBoundaryOffset)
                        if programmaticScrollTarget != nil, activePagerPage != settledTarget {
                            activePagerPage = settledTarget
                            scrollProxy.scrollTo(settledTarget)
                            await Task.yield()
                            guard programmaticScrollGeneration == expectedGen else { return }
                        }
                        if programmaticScrollTarget != nil {
                            programmaticScrollTarget = nil
                        }
                        controller.updatePagerStatus(
                            activePage: activePagerPage ?? settledTarget,
                            prevBoundaryPage: prevBoundaryPage,
                            nextBoundaryPage: nextBoundaryPage
                        )
                    }
                    .onChange(of: state.currentSlotIndex) { _, newSlot in
                        guard !controller.isPerformingProgrammaticScroll else { return }
                        let target = clampedPagerPage(Int(newSlot) + startBoundaryOffset)
                        if activePagerPage != target {
                            scrollProgrammatically(to: target, animated: false, scrollProxy: scrollProxy)
                        }
                    }
                    .onChange(of: totalPagerPages) { oldTotal, newTotal in
                        let target = clampedPagerPage(Int(state.currentSlotIndex) + startBoundaryOffset)
                        if activePagerPage == nil || (activePagerPage ?? 0) >= newTotal {
                            scrollProgrammatically(to: target, animated: false, scrollProxy: scrollProxy)
                        } else {
                            if newTotal < oldTotal {
                                DispatchQueue.main.async {
                                    scrollProxy.scrollTo(clampedPagerPage(Int(state.currentSlotIndex) + startBoundaryOffset))
                                }
                            }
                            controller.updatePagerStatus(
                                activePage: activePagerPage,
                                prevBoundaryPage: prevBoundaryPage,
                                nextBoundaryPage: nextBoundaryPage
                            )
                        }
                    }
                    .onChange(of: activePagerPage) { _, newPage in
                        guard let newPage else { return }
                        if let target = programmaticScrollTarget {
                            if newPage == target {
                                programmaticScrollTarget = nil
                                controller.updatePagerStatus(
                                    activePage: newPage,
                                    prevBoundaryPage: prevBoundaryPage,
                                    nextBoundaryPage: nextBoundaryPage
                                )
                            } else {
                                activePagerPage = target
                                scrollProxy.scrollTo(target)
                                controller.updatePagerStatus(
                                    activePage: target,
                                    prevBoundaryPage: prevBoundaryPage,
                                    nextBoundaryPage: nextBoundaryPage
                                )
                            }
                            return
                        }
                        controller.updatePagerStatus(
                            activePage: newPage,
                            prevBoundaryPage: prevBoundaryPage,
                            nextBoundaryPage: nextBoundaryPage
                        )
                        let slotIdx = newPage - startBoundaryOffset
                        if state.slots.indices.contains(slotIdx), slotIdx != Int(state.currentSlotIndex) {
                            state.goToSlot(slotIndex: Int32(slotIdx))
                        }
                    }
                }
            }
        }
        .onDisappear {
            controller.zoomResetHandler = nil
        }
    }

    @ViewBuilder
    private func pagerCell(for pagerPage: Int) -> some View {
        if hasPrevBoundary && pagerPage == 0 {
            ZStack {
                Color.black
                MangaBoundaryCardView(
                    boundary: state.previousBoundary,
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
                if activePagerPage == pagerPage {
                    isCurrentPageZoomed = false
                    controller.zoomResetHandler = nil
                }
            }
        } else if hasNextBoundary && pagerPage == startBoundaryOffset + state.slots.count {
            ZStack {
                Color.black
                MangaBoundaryCardView(
                    boundary: state.nextBoundary,
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
                if activePagerPage == pagerPage {
                    isCurrentPageZoomed = false
                    controller.zoomResetHandler = nil
                }
            }
        } else {
            let slotIndex = min(max(pagerPage - startBoundaryOffset, 0), max(state.slots.count - 1, 0))
            let slot = state.slots[slotIndex]
            let isActive = (activePagerPage ?? (Int(state.currentSlotIndex) + startBoundaryOffset)) == pagerPage
            ZoomablePagedSlot(
                slot: slot,
                pages: state.pages,
                state: state,
                viewportSize: viewportSize,
                isActivePagerPage: isActive,
                controller: controller,
                onZoomChanged: { zoomed, resetAction in
                    if isActive {
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
        controller.pagedStepForward = {
            guard totalPagerPages > 0 else { return }
            let current = activePagerPage ?? clampedPagerPage(Int(state.currentSlotIndex) + startBoundaryOffset)
            if current == prevBoundaryPage {
                guard !state.slots.isEmpty else { return }
                state.goToSlot(slotIndex: 0)
                scrollProgrammatically(to: startBoundaryOffset, animated: true, scrollProxy: scrollProxy)
            } else if current == nextBoundaryPage {
                if let nextUnlocked = state.nextBoundary as? ChapterBoundaryUnlocked {
                    state.openAdjacentChapter(boundary: nextUnlocked)
                }
            } else if !state.slots.isEmpty {
                let slotIdx = min(max(current - startBoundaryOffset, 0), state.slots.count - 1)
                if slotIdx != Int(state.currentSlotIndex) {
                    state.goToSlot(slotIndex: Int32(slotIdx))
                }
                let beforeSlot = Int(state.currentSlotIndex)
                state.stepForward()
                let afterSlot = Int(state.currentSlotIndex)
                if afterSlot != beforeSlot {
                    scrollProgrammatically(to: startBoundaryOffset + afterSlot, animated: true, scrollProxy: scrollProxy)
                }
            }
        }

        controller.pagedStepBackward = {
            guard totalPagerPages > 0 else { return }
            let current = activePagerPage ?? clampedPagerPage(Int(state.currentSlotIndex) + startBoundaryOffset)
            if current == nextBoundaryPage {
                guard !state.slots.isEmpty else { return }
                let lastIdx = state.slots.count - 1
                state.goToSlot(slotIndex: Int32(lastIdx))
                scrollProgrammatically(to: startBoundaryOffset + lastIdx, animated: true, scrollProxy: scrollProxy)
            } else if current == prevBoundaryPage {
                if let prevUnlocked = state.previousBoundary as? ChapterBoundaryUnlocked {
                    state.openAdjacentChapter(boundary: prevUnlocked)
                }
            } else if !state.slots.isEmpty {
                let slotIdx = min(max(current - startBoundaryOffset, 0), state.slots.count - 1)
                if slotIdx != Int(state.currentSlotIndex) {
                    state.goToSlot(slotIndex: Int32(slotIdx))
                }
                let beforeSlot = Int(state.currentSlotIndex)
                state.stepBackward()
                let afterSlot = Int(state.currentSlotIndex)
                if afterSlot != beforeSlot {
                    scrollProgrammatically(to: startBoundaryOffset + afterSlot, animated: true, scrollProxy: scrollProxy)
                }
            }
        }

        controller.pagedScrollToBoundary = { isNext in
            if isNext, nextBoundaryPage >= 0 {
                scrollProgrammatically(to: nextBoundaryPage, animated: false, scrollProxy: scrollProxy)
            } else if !isNext, prevBoundaryPage >= 0 {
                scrollProgrammatically(to: prevBoundaryPage, animated: false, scrollProxy: scrollProxy)
            }
        }
    }

    private func scrollProgrammatically(to target: Int, animated: Bool, scrollProxy: ScrollViewProxy?) {
        let clamped = clampedPagerPage(target)
        programmaticScrollGeneration += 1
        let generation = programmaticScrollGeneration
        controller.isPerformingProgrammaticScroll = true
        defer { controller.isPerformingProgrammaticScroll = false }

        guard activePagerPage != clamped else {
            programmaticScrollTarget = nil
            controller.updatePagerStatus(
                activePage: clamped,
                prevBoundaryPage: prevBoundaryPage,
                nextBoundaryPage: nextBoundaryPage
            )
            return
        }
        programmaticScrollTarget = clamped
        if animated {
            withAnimation(.snappy(duration: 0.22)) {
                activePagerPage = clamped
                scrollProxy?.scrollTo(clamped)
            }
        } else {
            activePagerPage = clamped
            scrollProxy?.scrollTo(clamped)
        }
        controller.updatePagerStatus(
            activePage: clamped,
            prevBoundaryPage: prevBoundaryPage,
            nextBoundaryPage: nextBoundaryPage
        )
        DispatchQueue.main.async {
            if programmaticScrollGeneration == generation, programmaticScrollTarget == clamped {
                programmaticScrollTarget = nil
            }
        }
    }

    private func clampedPagerPage(_ value: Int) -> Int {
        guard totalPagerPages > 0 else { return 0 }
        return min(max(value, 0), totalPagerPages - 1)
    }
}

#if os(iOS)
@available(iOS 18.0, *)
private struct SettledScrollPageSyncModifier: ViewModifier {
    let totalPages: Int
    let onSettled: (Int) -> Void

    @State private var isScrollIdle = true

    func body(content: Content) -> some View {
        content
            .onScrollPhaseChange { _, phase in
                isScrollIdle = (phase == .idle)
            }
            .onScrollGeometryChange(for: Int?.self) { geometry in
                let width = geometry.containerSize.width
                guard width > 1, totalPages > 0, geometry.contentSize.width >= width else { return nil }
                let raw = geometry.contentOffset.x / width
                let rawIndex = Int(raw.rounded())
                guard abs(raw - CGFloat(rawIndex)) < 0.02 else { return nil }
                return min(max(rawIndex, 0), totalPages - 1)
            } action: { _, settledPage in
                if isScrollIdle, let settledPage {
                    onSettled(settledPage)
                }
            }
    }
}
#endif

private extension View {
    @ViewBuilder
    func syncSettledScrollPage(
        totalPages: Int,
        onSettled: @escaping (Int) -> Void
    ) -> some View {
        #if os(iOS)
        if #available(iOS 18.0, *) {
            modifier(SettledScrollPageSyncModifier(totalPages: totalPages, onSettled: onSettled))
        } else {
            self
        }
        #else
        self
        #endif
    }
}

private struct ZoomablePagedSlot: View {
    let slot: any ReaderSlot
    let pages: [ReaderPageState]
    let state: any MangaReaderPresenterState
    let viewportSize: CGSize
    let isActivePagerPage: Bool
    let controller: MangaReaderInteractionController
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
                        alignment: .center
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
                            alignment: .trailing
                        )
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .trailing)
                    }

                    let rightIndex = Int(doubleSlot.rightPageIndex)
                    if pages.indices.contains(rightIndex) {
                        ReaderPageCell(
                            page: pages[rightIndex],
                            state: state,
                            contentMode: .fit,
                            alignment: .leading
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
                withAnimation(.spring(duration: 0.22)) {
                    if isZoomed {
                        resetZoom()
                    } else {
                        let targetScale = mangaDoubleTapZoom
                        let centerX = viewportSize.width / 2
                        let centerY = viewportSize.height / 2
                        let proposed = CGSize(
                            width: (centerX - tapLocation.x) * (targetScale - 1),
                            height: (centerY - tapLocation.y) * (targetScale - 1)
                        )
                        committedScale = targetScale
                        committedOffset = clampOffset(proposed, for: targetScale, in: viewportSize)
                    }
                }
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
        .onChange(of: isActivePagerPage) { _, active in
            if !active && isZoomed {
                resetZoom()
            } else if active {
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
    }
}

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

    @State private var syncBox: WebtoonScrollSyncBox

    init(
        state: any MangaReaderPresenterState,
        viewportSize: CGSize,
        controller: MangaReaderInteractionController,
        onOpenLockedUrl: @escaping (String) -> Void
    ) {
        self.state = state
        self.viewportSize = viewportSize
        self.controller = controller
        self.onOpenLockedUrl = onOpenLockedUrl
        self._syncBox = State(initialValue: WebtoonScrollSyncBox(initialPage: Int(state.currentPageIndex)))
    }

    private var hasPrevBoundary: Bool {
        !(state.previousBoundary is ChapterBoundaryNone)
    }

    private var hasNextBoundary: Bool {
        !(state.nextBoundary is ChapterBoundaryNone)
    }

    var body: some View {
        ScrollViewReader { scrollProxy in
            webtoonScrollView
                .onPreferenceChange(MangaWebtoonFramesPreferenceKey.self) { frames in
                    handleVisibleWebtoonFrames(frames, scrollProxy: scrollProxy)
                }
                .onAppear {
                    controller.clearPagerStatus()
                    controller.zoomResetHandler = nil
                    installWebtoonKeyScroll(scrollProxy: scrollProxy)
                    let initialPage = Int(state.currentPageIndex)
                    syncBox.lastReportedPage = initialPage
                    if initialPage > 0 && state.pages.indices.contains(initialPage) {
                        syncBox.programmaticTargetPage = initialPage
                        syncBox.settledCenterPage = nil
                        scrollProxy.scrollTo(initialPage, anchor: .top)
                    }
                }
                .onChange(of: state.currentPageIndex) { _, newIndex in
                    let targetPage = Int(newIndex)
                    guard !state.pages.isEmpty, state.pages.indices.contains(targetPage) else { return }
                    if targetPage != syncBox.lastReportedPage {
                        syncBox.lastReportedPage = targetPage
                        syncBox.programmaticTargetPage = targetPage
                        syncBox.settledCenterPage = nil
                        scrollProxy.scrollTo(targetPage, anchor: .top)
                    }
                }
        }
    }

    private var webtoonScrollView: some View {
        ScrollView(.vertical, showsIndicators: true) {
            webtoonStackContent
        }
        .coordinateSpace(name: "manga_webtoon_scroll")
        .accessibilityIdentifier("manga_webtoon_list")
        .contentShape(Rectangle())
        .mangaZoneTap(
            in: viewportSize,
            controller: controller,
            onDoubleTap: nil
        )
    }

    private var webtoonStackContent: some View {
        LazyVStack(spacing: 0) {
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
                .id("webtoon_boundary_prev")
            }

            ForEach(0..<state.pages.count, id: \.self) { pageIdx in
                webtoonPageRow(state.pages[pageIdx], pageIdx: pageIdx)
            }

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
                .id("webtoon_boundary_next")
            }
        }
    }

    private func webtoonPageRow(_ page: ReaderPageState, pageIdx: Int) -> some View {
        let ratio = clampedWebtoonAspectRatio(page.aspectRatio?.floatValue)
        return ReaderPageCell(
            page: page,
            state: state,
            contentMode: .fit,
            alignment: .top
        )
        .aspectRatio(ratio, contentMode: .fit)
        .frame(maxWidth: .infinity)
        .background {
            GeometryReader { itemProxy in
                Color.clear.preference(
                    key: MangaWebtoonFramesPreferenceKey.self,
                    value: [pageIdx: itemProxy.frame(in: .named("manga_webtoon_scroll"))]
                )
            }
        }
        .id(pageIdx)
    }

    private func installWebtoonKeyScroll(scrollProxy: ScrollViewProxy) {
        controller.webtoonScrollByPage = { forward in
            let current = Int(state.currentPageIndex)
            if forward {
                let next = current + 1
                guard next < state.pages.count else { return false }
                syncBox.lastReportedPage = next
                syncBox.programmaticTargetPage = next
                syncBox.settledCenterPage = nil
                state.goToPage(pageIndex: Int32(next))
                withAnimation(.easeOut(duration: 0.2)) {
                    scrollProxy.scrollTo(next, anchor: .top)
                }
                return true
            } else {
                let prev = current - 1
                guard prev >= 0 else { return false }
                syncBox.lastReportedPage = prev
                syncBox.programmaticTargetPage = prev
                syncBox.settledCenterPage = nil
                state.goToPage(pageIndex: Int32(prev))
                withAnimation(.easeOut(duration: 0.2)) {
                    scrollProxy.scrollTo(prev, anchor: .top)
                }
                return true
            }
        }
    }

    private func handleVisibleWebtoonFrames(_ frames: [Int: CGRect], scrollProxy: ScrollViewProxy) {
        guard !state.pages.isEmpty, viewportSize.height > 0 else { return }
        let viewportCenterY = viewportSize.height / 2
        let visibleCandidates = frames.filter { index, rect in
            index >= 0 && index < state.pages.count && rect.maxY > 0 && rect.minY < viewportSize.height
        }
        guard let nearest = visibleCandidates.min(by: { lhs, rhs in
            abs(lhs.value.midY - viewportCenterY) < abs(rhs.value.midY - viewportCenterY)
        }) else {
            if let target = syncBox.programmaticTargetPage {
                scrollProxy.scrollTo(target, anchor: .top)
            }
            return
        }

        let centerPage = nearest.key
        if let target = syncBox.programmaticTargetPage {
            if let targetRect = frames[target],
               targetRect.maxY > 0 && targetRect.minY < viewportSize.height {
                syncBox.programmaticTargetPage = nil
                syncBox.lastReportedPage = target
                syncBox.settledCenterPage = centerPage
            } else {
                scrollProxy.scrollTo(target, anchor: .top)
            }
            return
        }

        if let settled = syncBox.settledCenterPage {
            if centerPage == settled {
                return
            }
            syncBox.settledCenterPage = nil
        }

        syncBox.lastReportedPage = centerPage
        if centerPage != Int(state.currentPageIndex) {
            state.goToPage(pageIndex: Int32(centerPage))
        }
    }

    private func clampedWebtoonAspectRatio(_ rawRatio: Float?) -> CGFloat {
        guard let rawRatio, rawRatio.isFinite, rawRatio > 0 else {
            return mangaDefaultWebtoonAspectRatio
        }
        return min(max(CGFloat(rawRatio), 0.05), 10.0)
    }
}

// MARK: - Page Cell & Retry UI

private struct ReaderPageCell: View {
    let page: ReaderPageState
    let state: any MangaReaderPresenterState
    let contentMode: SwiftUI.ContentMode
    let alignment: Alignment

    @State private var readyLoadFailed = false
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
                if readyLoadFailed {
                    ReaderPageErrorView(
                        message: nil,
                        pageId: page.id,
                        onRetry: {
                            readyLoadFailed = false
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
                    .onAppear {
                        if !ready.displayed.isRemote {
                            imageLoaded = true
                        }
                    }
                    .onChange(of: ready.displayed.url) { _, _ in
                        readyLoadFailed = false
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
            GeometryReader { proxy in
                Color.clear.preference(
                    key: MangaReaderPageFramesPreferenceKey.self,
                    value: [Int(page.index): proxy.frame(in: .named("manga_reader_canvas"))]
                )
            }
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
                    }
                    imageLoaded = false
                    readyLoadFailed = true
                },
                onImageSize: { size in
                    imageLoaded = true
                    readyLoadFailed = false
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

    var body: some View {
        #if os(macOS)
        MacReaderKeyEventMonitorRepresentable(controller: controller)
            .frame(width: 0, height: 0)
            .accessibilityHidden(true)
        #elseif os(iOS)
        IOSReaderKeyCommandRepresentable(controller: controller)
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
              window.isKeyWindow || NSApp.keyWindow == nil,
              !(window.firstResponder is NSText),
              window.attachedSheet == nil,
              !modifierFlags.contains(.command),
              !modifierFlags.contains(.control),
              !modifierFlags.contains(.option),
              let controller else {
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

    func makeUIViewController(context: Context) -> IOSReaderKeyCommandViewController {
        let viewController = IOSReaderKeyCommandViewController()
        viewController.controller = controller
        return viewController
    }

    func updateUIViewController(_ uiViewController: IOSReaderKeyCommandViewController, context: Context) {
        uiViewController.controller = controller
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

    override var keyCommands: [UIKeyCommand]? {
        let commands: [(String, UIKeyModifierFlags, Selector)] = [
            (UIKeyCommand.inputLeftArrow, [], #selector(handleLeftArrow)),
            (UIKeyCommand.inputRightArrow, [], #selector(handleRightArrow)),
            (UIKeyCommand.inputUpArrow, [], #selector(handleUpArrow)),
            (UIKeyCommand.inputDownArrow, [], #selector(handleDownArrow)),
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
