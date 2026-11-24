import FlareAppleCore
@testable import FlareAppleUI
import Foundation
import Kingfisher
@preconcurrency import KotlinSharedUI
@preconcurrency import Network
import Observation
import PDFKit
import SwiftUI
import UIKit
import WebKit
import XCTest
@testable import Flare

@MainActor
final class MangaReaderAndDetailTests: XCTestCase {
    func testMangaDeepLinkRoutesMapSpecificAndGuestPluginAccounts() {
        let mangaKey = MicroBlogKey(id: "manga-99", host: "manga.example")
        let accounts: [AccountType] = [
            AccountType.Specific(accountKey: MicroBlogKey(id: "user-1", host: "manga.example")),
            AccountType.GuestPlugin(platformId: "manga-plat", host: "manga.example"),
        ]

        for accountType in accounts {
            let detailDeeplink = DeeplinkRoute.MangaDetail(
                mangaKey: mangaKey,
                accountType: accountType
            )
            XCTAssertEqual(
                Route.fromDeepLinkRoute(deeplinkRoute: detailDeeplink),
                Route.mangaDetail(accountType, mangaKey)
            )
            XCTAssertEqual(
                Route.fromDeepLink(url: detailDeeplink.toUri()),
                Route.mangaDetail(accountType, mangaKey)
            )

            let readerDeeplink = DeeplinkRoute.MangaReader(
                mangaKey: mangaKey,
                chapterId: "ch-12",
                initialPage: KotlinInt(int: 5),
                accountType: accountType
            )
            XCTAssertEqual(
                Route.fromDeepLinkRoute(deeplinkRoute: readerDeeplink),
                Route.mangaReader(accountType, mangaKey, "ch-12", 5)
            )
            XCTAssertEqual(
                Route.fromDeepLink(url: readerDeeplink.toUri()),
                Route.mangaReader(accountType, mangaKey, "ch-12", 5)
            )

            let defaultPageReader = DeeplinkRoute.MangaReader(
                mangaKey: mangaKey,
                chapterId: "ch-01",
                initialPage: nil,
                accountType: accountType
            )
            XCTAssertEqual(
                Route.fromDeepLinkRoute(deeplinkRoute: defaultPageReader),
                Route.mangaReader(accountType, mangaKey, "ch-01", nil)
            )
        }
    }

    func testReaderRtlDoublePageScrubberTapZonesAndKeyboard() async throws {
        let fakeState = FakeMangaReaderState(
            direction: .rtl,
            pages: makeSamplePages(count: 4),
            slots: [
                ReaderSlotDouble(leftPageIndex: 1, rightPageIndex: 0, primaryPageIndex: 0),
                ReaderSlotDouble(leftPageIndex: 3, rightPageIndex: 2, primaryPageIndex: 2),
            ]
        )
        let controller = MangaReaderInteractionController()
        let hostBox = ReaderTestHostModel(state: fakeState, controller: controller)

        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 800, height: 600)
        window.rootViewController = UIHostingController(rootView: ReaderTestHostView(model: hostBox))
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKey()
        }

        try await eventually(window: window) {
            fakeState.lastWideViewport == true &&
                hostBox.pageFrames[0] != nil &&
                hostBox.pageFrames[1] != nil &&
                hostBox.scrubberLeadingLabelFrame != nil &&
                hostBox.scrubberTrailingLabelFrame != nil
        }

        // 1. RTL double-page slot places earlier page (0) on the physical right and later page (1) on the physical left
        let page0Frame = try XCTUnwrap(hostBox.pageFrames[0])
        let page1Frame = try XCTUnwrap(hostBox.pageFrames[1])
        XCTAssertLessThan(
            page1Frame.minX,
            page0Frame.minX,
            "Expected leftPageIndex (1) on physical left (\(page1Frame)) of rightPageIndex (0) (\(page0Frame))"
        )
        XCTAssertLessThanOrEqual(page1Frame.maxX, page0Frame.minX + 1.5)

        // 2. Scrubber mirrors layout direction in RTL (current page label on physical right, total page count on physical left)
        let rtlCurrentLabel = try XCTUnwrap(hostBox.scrubberLeadingLabelFrame)
        let rtlTotalLabel = try XCTUnwrap(hostBox.scrubberTrailingLabelFrame)
        XCTAssertGreaterThan(
            rtlCurrentLabel.minX,
            rtlTotalLabel.minX,
            "Expected RTL scrubber current-page label (\(rtlCurrentLabel)) to appear to the right of total-pages label (\(rtlTotalLabel))"
        )

        // 3. Tap zones (30% / 40% / 30%) in RTL: left advances, right steps back, center toggles HUD
        controller.handleTap(at: CGPoint(x: 120, y: 300), in: CGSize(width: 800, height: 600))
        XCTAssertEqual(fakeState.stepForwardCount, 1)
        XCTAssertEqual(fakeState.currentSlotIndex, 1)

        controller.handleTap(at: CGPoint(x: 680, y: 300), in: CGSize(width: 800, height: 600))
        XCTAssertEqual(fakeState.stepBackwardCount, 1)
        XCTAssertEqual(fakeState.currentSlotIndex, 0)

        XCTAssertTrue(hostBox.hudVisible)
        controller.handleTap(at: CGPoint(x: 400, y: 300), in: CGSize(width: 800, height: 600))
        try await eventually(window: window) { !hostBox.hudVisible }

        // Restore HUD so scrubber bar is mounted again
        controller.handleTap(at: CGPoint(x: 400, y: 300), in: CGSize(width: 800, height: 600))
        try await eventually(window: window) { hostBox.hudVisible }

        // 4. Hardware keyboard arrow events respect RTL vs LTR direction
        XCTAssertTrue(controller.handleKey(.leftArrow))
        XCTAssertEqual(fakeState.stepForwardCount, 2)
        XCTAssertEqual(fakeState.currentSlotIndex, 1)

        fakeState.setDirection(direction: .ltr)
        try await eventually(window: window) {
            guard let leading = hostBox.scrubberLeadingLabelFrame,
                  let trailing = hostBox.scrubberTrailingLabelFrame else { return false }
            return leading.minX < trailing.minX
        }
        XCTAssertTrue(controller.handleKey(.leftArrow))
        XCTAssertEqual(fakeState.stepBackwardCount, 2)
        XCTAssertEqual(fakeState.currentSlotIndex, 0)

        // 5. Narrow viewport (< 720) reports setWideViewport(false) and hides spread toggles in Reader Settings
        window.frame = CGRect(x: 0, y: 0, width: 500, height: 600)
        try await eventually(window: window) { fakeState.lastWideViewport == false }

        let wideSettingsHost = UIHostingController(
            rootView: MangaReaderSettingsView(state: fakeState, isWideViewport: true)
        )
        let narrowSettingsHost = UIHostingController(
            rootView: MangaReaderSettingsView(state: fakeState, isWideViewport: false)
        )
        let webtoonSettingsState = FakeMangaReaderState(
            direction: .webtoon,
            pages: fakeState.pages,
            slots: fakeState.slots
        )
        let webtoonSettingsHost = UIHostingController(
            rootView: MangaReaderSettingsView(state: webtoonSettingsState, isWideViewport: true)
        )

        let wideHeight = wideSettingsHost.sizeThatFits(in: CGSize(width: 360, height: 600)).height
        let narrowHeight = narrowSettingsHost.sizeThatFits(in: CGSize(width: 360, height: 600)).height
        XCTAssertLessThan(narrowHeight, wideHeight - 40)

        window.rootViewController = wideSettingsHost
        try await eventually(window: window) {
            self.countSubviews(ofType: UISwitch.self, in: wideSettingsHost.view) == 2
        }

        window.rootViewController = webtoonSettingsHost
        try await eventually(window: window) {
            self.countSubviews(ofType: UISwitch.self, in: webtoonSettingsHost.view) == 1
        }

        window.rootViewController = narrowSettingsHost
        window.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(30))
        XCTAssertEqual(
            countSubviews(ofType: UISwitch.self, in: narrowSettingsHost.view),
            0,
            "Narrow viewport (< 720) must hide Double-page spread and Single cover page toggles"
        )
    }

    func testReaderBoundaryTransitionAndReadyPageRetry() async throws {
        let prevChapter = UiMangaChapter(id: "ch-0", title: "Chapter 0", subtitle: nil, uploadedAt: nil, locked: false, url: nil)
        let nextChapter = UiMangaChapter(id: "ch-2", title: "Chapter 2", subtitle: nil, uploadedAt: nil, locked: false, url: nil)
        let fakeState = FakeMangaReaderState(
            direction: .ltr,
            pages: makeSamplePages(count: 2),
            slots: [ReaderSlotSingle(pageIndex: 0), ReaderSlotSingle(pageIndex: 1)],
            previousBoundary: ChapterBoundaryUnlocked(chapter: prevChapter, startFromEnd: true),
            nextBoundary: ChapterBoundaryUnlocked(chapter: nextChapter, startFromEnd: false)
        )
        let controller = MangaReaderInteractionController()
        let hostBox = ReaderTestHostModel(state: fakeState, controller: controller)

        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 600, height: 800)
        window.rootViewController = UIHostingController(rootView: ReaderTestHostView(model: hostBox))
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKey()
        }

        try await eventually(window: window) { controller.isBound && hostBox.pageFrames[0] != nil }
        try await Task.sleep(for: .milliseconds(60))
        window.layoutIfNeeded()

        // Manual horizontal swipe after initial mount with previousBoundary (initial == 1 > 0) must advance slot 0 -> slot 1 without yanking back
        let boundaryScrollView = try XCTUnwrap(findHorizontalPagingScrollView(in: window))
        await simulateUserHorizontalScroll(boundaryScrollView, pageDelta: 1, controller: controller)
        try await eventually(window: window) {
            fakeState.currentSlotIndex == 1 && controller.activePagerPage == 2
        }
        try await Task.sleep(for: .milliseconds(60))
        window.layoutIfNeeded()
        XCTAssertEqual(fakeState.currentSlotIndex, 1)
        XCTAssertEqual(controller.activePagerPage, 2)

        // Redundant scrollToBoundary when activePagerPage already equals clamped must not leave programmaticScrollTarget armed
        controller.scrollToBoundary(isNext: true)
        try await eventually(window: window) {
            controller.isOnNextBoundaryPage && boundaryScrollView.contentOffset.x >= boundaryScrollView.bounds.width * 3 - 10
        }
        controller.scrollToBoundary(isNext: true)
        try await Task.sleep(for: .milliseconds(30))
        window.layoutIfNeeded()
        XCTAssertTrue(controller.isOnNextBoundaryPage)
        await simulateUserHorizontalScroll(boundaryScrollView, pageDelta: -1, controller: controller)
        try await eventually(window: window) {
            controller.activePagerPage == 2 && !controller.isOnNextBoundaryPage
        }

        // Move to last slot (slot 1), then settle on next boundary page:
        // stepping inward must return to slot 1 (NOT skip to slot 0), and stepping outward opens Chapter 2
        fakeState.goToSlot(slotIndex: 1)
        try await eventually(window: window) { controller.activePagerPage == 2 && !controller.isOnNextBoundaryPage }
        controller.scrollToBoundary(isNext: true)
        XCTAssertTrue(controller.isOnNextBoundaryPage)

        XCTAssertTrue(controller.handleKey(.leftArrow))
        XCTAssertFalse(controller.isOnNextBoundaryPage)
        XCTAssertEqual(fakeState.currentSlotIndex, 1)

        controller.scrollToBoundary(isNext: true)
        XCTAssertTrue(controller.handleKey(.rightArrow))
        XCTAssertEqual(fakeState.openedBoundaryChapterId, "ch-2")

        // Settle on previous boundary page: stepping inward returns to slot 0 without skipping to slot 1
        fakeState.goToSlot(slotIndex: 0)
        try await eventually(window: window) { controller.activePagerPage == 1 && !controller.isOnNextBoundaryPage }
        controller.scrollToBoundary(isNext: false)
        XCTAssertTrue(controller.isOnPreviousBoundaryPage)
        XCTAssertTrue(controller.handleKey(.rightArrow))
        XCTAssertFalse(controller.isOnPreviousBoundaryPage)
        XCTAssertEqual(fakeState.currentSlotIndex, 0)

        // Ready page load failure shows retry UI (not just Deferred failure)
        let missingURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("missing-ios-manga-\(UUID().uuidString).png")
            .absoluteString
        let brokenMedia = UiMediaImage(
            url: missingURL,
            previewUrl: missingURL,
            description: nil,
            height: 1400,
            width: 1000,
            sensitive: false,
            customHeaders: ["Referer": "https://manga.example/ch/1"],
            proxy: ProxyRef.Unbound.shared
        )
        let flakyState = FakeMangaReaderState(
            direction: .ltr,
            pages: [
                ReaderPageState(
                    id: "flaky-p0",
                    index: 0,
                    status: PageMediaStatusReady(original: brokenMedia, displayed: brokenMedia),
                    aspectRatio: KotlinFloat(value: 0.71)
                ),
            ],
            slots: [ReaderSlotSingle(pageIndex: 0)]
        )
        hostBox.setState(flakyState)
        try await eventually(window: window) {
            hostBox.retryPageIds.contains("flaky-p0") && controller.pageRetryHandlers["flaky-p0"] != nil
        }
        XCTAssertTrue(controller.triggerPageRetryForTesting(pageId: "flaky-p0"))
        try await eventually(window: window) {
            flakyState.retriedPages.contains { $0.0 == "flaky-p0" && $0.1 == brokenMedia.url }
        }

        let failedResolveState = FakeMangaReaderState(
            direction: .ltr,
            pages: [
                ReaderPageState(
                    id: "failed-p0",
                    index: 0,
                    status: PageMediaStatusFailed(error: KotlinThrowable(message: "Resolve failed")),
                    aspectRatio: nil
                ),
            ],
            slots: [ReaderSlotSingle(pageIndex: 0)]
        )
        hostBox.setState(failedResolveState)
        try await eventually(window: window) {
            hostBox.retryPageIds.contains("failed-p0") && controller.pageRetryHandlers["failed-p0"] != nil
        }
        XCTAssertTrue(controller.triggerPageRetryForTesting(pageId: "failed-p0"))
        try await eventually(window: window) {
            failedResolveState.retriedPages.contains { $0.0 == "failed-p0" && $0.1 == nil }
        }
        hostBox.setState(flakyState)
        try await eventually(window: window) {
            hostBox.retryPageIds.contains("flaky-p0")
        }

        let original = try XCTUnwrap((makeSamplePages(count: 1)[0].status as? PageMediaStatusReady)?.original)
        let translation = try XCTUnwrap(flakyState.imageTranslation as? NoopImageTranslationState)
        translation.onImageFailure = { [weak flakyState] _, url in
            XCTAssertEqual(url, brokenMedia.url)
            flakyState?.replacePagesAndSlots(pages: [
                ReaderPageState(id: "translated-p0", index: 0,
                                status: PageMediaStatusReady(original: original, displayed: original), aspectRatio: nil),
            ], slots: [ReaderSlotSingle(pageIndex: 0)], currentPageIndex: 0)
        }
        flakyState.replacePagesAndSlots(pages: [
            ReaderPageState(id: "translated-p0", index: 0,
                            status: PageMediaStatusReady(original: original, displayed: brokenMedia), aspectRatio: nil),
        ], slots: [ReaderSlotSingle(pageIndex: 0)], currentPageIndex: 0)
        try await eventually(window: window) {
            flakyState.loadedPageIds.contains("translated-p0") && hostBox.retryPageIds.isEmpty
        }

        // Webtoon mode: unscaled 1x strip width, full bottom reachability, and external scrubber jump without snapping back
        let webtoonPages = makeSamplePages(count: 6)
        let webtoonSlots = (0..<6).map { ReaderSlotSingle(pageIndex: Int32($0)) as any ReaderSlot }
        let webtoonState = FakeMangaReaderState(
            direction: .webtoon,
            pages: webtoonPages,
            slots: webtoonSlots
        )
        hostBox.pageFrames.removeAll()
        hostBox.setState(webtoonState)
        try await eventually(window: window) {
            hostBox.pageFrames[0] != nil
        }
        let page0WebtoonFrame = try XCTUnwrap(hostBox.pageFrames[0])
        XCTAssertEqual(page0WebtoonFrame.width, 600, accuracy: 1.5)
        XCTAssertNil(controller.zoomResetHandler)

        webtoonState.goToPage(pageIndex: 5)
        try await eventually(window: window) {
            guard let page5Frame = hostBox.pageFrames[5] else { return false }
            return page5Frame.minY < 800 && page5Frame.maxY > 0 && webtoonState.currentPageIndex == 5
        }
        let page5WebtoonFrame = try XCTUnwrap(hostBox.pageFrames[5])
        XCTAssertEqual(page5WebtoonFrame.width, 600, accuracy: 1.5)
    }

    func testMidChapterRtlLtrSwitchPreservesSlotOnFourSlotFixture() async throws {
        let pages = makeSamplePages(count: 4)
        let slots: [any ReaderSlot] = (0..<4).map { ReaderSlotSingle(pageIndex: Int32($0)) }
        let fakeState = FakeMangaReaderState(
            direction: .rtl,
            pages: pages,
            slots: slots
        )
        let controller = MangaReaderInteractionController()
        let hostBox = ReaderTestHostModel(state: fakeState, controller: controller)

        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 800, height: 600)
        window.rootViewController = UIHostingController(rootView: ReaderTestHostView(model: hostBox))
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKey()
        }

        try await eventually(window: window) { controller.isBound && hostBox.pageFrames[0] != nil }
        // Let .task finish initial positioning so we exercise post-.task manual swipe behavior
        try await Task.sleep(for: .milliseconds(60))
        window.layoutIfNeeded()

        // 1. Simulated manual horizontal user swipe after initial mount (slot 0 -> slot 1 in RTL) must NOT yank back
        let initialScrollView = try XCTUnwrap(findHorizontalPagingScrollView(in: window))
        await simulateUserHorizontalScroll(initialScrollView, pageDelta: -1, controller: controller)
        try await eventually(window: window) {
            fakeState.currentSlotIndex == 1 && controller.activePagerPage == 1
        }
        try await Task.sleep(for: .milliseconds(60))
        window.layoutIfNeeded()
        XCTAssertEqual(fakeState.currentSlotIndex, 1, "Manual swipe after initial mount must update currentSlotIndex without yanking back")
        XCTAssertEqual(controller.activePagerPage, 1)

        // Advance one more slot to slot 2 (out of 0..3) in RTL
        XCTAssertTrue(controller.handleKey(.leftArrow))
        XCTAssertEqual(fakeState.currentSlotIndex, 2)
        try await eventually(window: window) {
            guard let frame = hostBox.pageFrames[2] else { return false }
            return frame.minX >= -1.5 && frame.maxX <= 801.5
        }

        // 2. Switch RTL -> LTR mid-chapter on slot 2: must NOT clobber currentSlotIndex to mirrored page (3 - 2 = 1)
        fakeState.setDirection(direction: .ltr)
        try await Task.sleep(for: .milliseconds(80))
        window.layoutIfNeeded()
        try await eventually(window: window) {
            guard let frame = hostBox.pageFrames[2] else { return false }
            return controller.activePagerPage == 2 && frame.minX >= -1.5 && frame.maxX <= 801.5
        }
        XCTAssertEqual(fakeState.currentSlotIndex, 2, "Switching RTL -> LTR mid-chapter must preserve currentSlotIndex == 2")
        XCTAssertNil(controller.zoomResetHandler)
        XCTAssertNotNil(controller.doubleTapZoomHandler, "Switching RTL -> LTR must preserve doubleTapZoomHandler on active slot")

        // 3. Simulated manual horizontal user swipe after RTL -> LTR switch (slot 2 -> slot 3 in LTR) must NOT yank back
        let ltrScrollView = try XCTUnwrap(findHorizontalPagingScrollView(in: window))
        await simulateUserHorizontalScroll(ltrScrollView, pageDelta: 1, controller: controller)
        try await eventually(window: window) {
            fakeState.currentSlotIndex == 3 && controller.activePagerPage == 3
        }
        try await Task.sleep(for: .milliseconds(60))
        window.layoutIfNeeded()
        XCTAssertEqual(fakeState.currentSlotIndex, 3, "Manual swipe after RTL -> LTR switch must not be yanked back")
        XCTAssertEqual(controller.activePagerPage, 3)

        // Step backward twice in LTR (slot 3 -> slot 1)
        XCTAssertTrue(controller.handleKey(.leftArrow))
        XCTAssertTrue(controller.handleKey(.leftArrow))
        XCTAssertEqual(fakeState.currentSlotIndex, 1)

        // 4. Switch LTR -> RTL mid-chapter on slot 1: must NOT clobber currentSlotIndex to mirrored page (3 - 1 = 2)
        fakeState.setDirection(direction: .rtl)
        try await Task.sleep(for: .milliseconds(80))
        window.layoutIfNeeded()
        try await eventually(window: window) {
            guard let frame = hostBox.pageFrames[1] else { return false }
            return controller.activePagerPage == 1 && frame.minX >= -1.5 && frame.maxX <= 801.5
        }
        XCTAssertEqual(fakeState.currentSlotIndex, 1, "Switching LTR -> RTL mid-chapter must preserve currentSlotIndex == 1")
        XCTAssertNil(controller.zoomResetHandler)
        XCTAssertNotNil(controller.doubleTapZoomHandler, "Switching LTR -> RTL must preserve doubleTapZoomHandler on active slot")

        // 5. Simulated manual horizontal user swipe after LTR -> RTL switch (slot 1 -> slot 2 in RTL) must NOT yank back
        let rtlScrollView = try XCTUnwrap(findHorizontalPagingScrollView(in: window))
        await simulateUserHorizontalScroll(rtlScrollView, pageDelta: -1, controller: controller)
        try await eventually(window: window) {
            fakeState.currentSlotIndex == 2 && controller.activePagerPage == 2
        }
        try await Task.sleep(for: .milliseconds(60))
        window.layoutIfNeeded()
        XCTAssertEqual(fakeState.currentSlotIndex, 2, "Manual swipe after LTR -> RTL switch must not be yanked back")
        XCTAssertEqual(controller.activePagerPage, 2)

        // 6. RTL totalPagerPages growing (4 -> 5), shrinking (5 -> 4), and idle viewport resize must preserve currentSlotIndex
        let sixPages = makeSamplePages(count: 6)
        let fourSpreadSlots: [any ReaderSlot] = [
            ReaderSlotSingle(pageIndex: 0),
            ReaderSlotDouble(leftPageIndex: 2, rightPageIndex: 1, primaryPageIndex: 1),
            ReaderSlotDouble(leftPageIndex: 4, rightPageIndex: 3, primaryPageIndex: 3),
            ReaderSlotSingle(pageIndex: 5),
        ]
        let fiveSplitSlots: [any ReaderSlot] = [
            ReaderSlotSingle(pageIndex: 0),
            ReaderSlotDouble(leftPageIndex: 2, rightPageIndex: 1, primaryPageIndex: 1),
            ReaderSlotSingle(pageIndex: 3),
            ReaderSlotSingle(pageIndex: 4),
            ReaderSlotSingle(pageIndex: 5),
        ]
        fakeState.replacePagesAndSlots(pages: sixPages, slots: fourSpreadSlots, currentPageIndex: 1)
        try await eventually(window: window) {
            fakeState.currentSlotIndex == 1 && controller.activePagerPage == 1
        }
        try await Task.sleep(for: .milliseconds(60))
        window.layoutIfNeeded()
        let rtlScrollViewBeforeReflow = try XCTUnwrap(findHorizontalPagingScrollView(in: window))
        let rtlScrollViewID = ObjectIdentifier(rtlScrollViewBeforeReflow)

        // Grow totalPagerPages 4 -> 5 in RTL while on slot 1 (simulating runtime landscape page split)
        fakeState.updateSlots(fiveSplitSlots)
        try await Task.sleep(for: .milliseconds(80))
        window.layoutIfNeeded()
        try await eventually(window: window) {
            guard let frame = hostBox.pageFrames[1] else { return false }
            return controller.activePagerPage == 1 && frame.minX >= -1.5 && frame.maxX <= 801.5
        }
        XCTAssertEqual(fakeState.currentSlotIndex, 1, "Growing totalPagerPages in RTL must preserve currentSlotIndex == 1")
        XCTAssertEqual(
            ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: window))),
            rtlScrollViewID,
            "Growing totalPagerPages must not tear down UIScrollView"
        )

        // Shrink totalPagerPages 5 -> 4 in RTL while on slot 1 (simulating re-pairing / spread toggle)
        fakeState.updateSlots(fourSpreadSlots)
        try await Task.sleep(for: .milliseconds(80))
        window.layoutIfNeeded()
        try await eventually(window: window) {
            guard let frame = hostBox.pageFrames[1] else { return false }
            return controller.activePagerPage == 1 && frame.minX >= -1.5 && frame.maxX <= 801.5
        }
        XCTAssertEqual(fakeState.currentSlotIndex, 1, "Shrinking totalPagerPages in RTL must preserve currentSlotIndex == 1")
        XCTAssertEqual(
            ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: window))),
            rtlScrollViewID,
            "Shrinking totalPagerPages must not tear down UIScrollView"
        )

        // Resize viewport width while idle in RTL (800 -> 520 -> 800) must preserve currentSlotIndex == 1 and UIScrollView identity
        window.frame = CGRect(x: 0, y: 0, width: 520, height: 600)
        window.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(80))
        window.layoutIfNeeded()
        try await eventually(window: window) {
            guard let frame = hostBox.pageFrames[1] else { return false }
            return controller.activePagerPage == 1 && frame.minX >= -1.5 && frame.maxX <= 521.5
        }
        XCTAssertEqual(fakeState.currentSlotIndex, 1, "Idle viewport narrowing in RTL must preserve currentSlotIndex == 1")
        XCTAssertEqual(
            ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: window))),
            rtlScrollViewID,
            "Narrowing viewport width must not tear down UIScrollView"
        )

        window.frame = CGRect(x: 0, y: 0, width: 800, height: 600)
        window.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(80))
        window.layoutIfNeeded()
        try await eventually(window: window) {
            guard let frame = hostBox.pageFrames[1] else { return false }
            return controller.activePagerPage == 1 && frame.minX >= -1.5 && frame.maxX <= 801.5
        }
        XCTAssertEqual(fakeState.currentSlotIndex, 1, "Idle viewport widening in RTL must preserve currentSlotIndex == 1")
        XCTAssertEqual(
            ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: window))),
            rtlScrollViewID,
            "Widening viewport width must not tear down UIScrollView"
        )

        // Manual user swipe after RTL reflows must still advance cleanly without yanking back
        let postReflowScrollView = try XCTUnwrap(findHorizontalPagingScrollView(in: window))
        XCTAssertEqual(ObjectIdentifier(postReflowScrollView), rtlScrollViewID)
        await simulateUserHorizontalScroll(postReflowScrollView, pageDelta: -1, controller: controller)
        try await eventually(window: window) {
            fakeState.currentSlotIndex == 2 && controller.activePagerPage == 2
        }
        XCTAssertEqual(fakeState.currentSlotIndex, 2)
    }

    func testSubtreeIdentityZoomPreservationMidDragSplitAndBoundaryReflow() async throws {
        let prevChapter = UiMangaChapter(id: "ch-0", title: "Chapter 0", subtitle: nil, uploadedAt: nil, locked: false, url: nil)
        let nextChapter = UiMangaChapter(id: "ch-2", title: "Chapter 2", subtitle: nil, uploadedAt: nil, locked: false, url: nil)
        let sixPages = makeSamplePages(count: 6)
        let fourSpreadSlots: [any ReaderSlot] = [
            ReaderSlotSingle(pageIndex: 0),
            ReaderSlotDouble(leftPageIndex: 2, rightPageIndex: 1, primaryPageIndex: 1),
            ReaderSlotDouble(leftPageIndex: 4, rightPageIndex: 3, primaryPageIndex: 3),
            ReaderSlotSingle(pageIndex: 5),
        ]
        let fiveSplitSlots: [any ReaderSlot] = [
            ReaderSlotSingle(pageIndex: 0),
            ReaderSlotDouble(leftPageIndex: 2, rightPageIndex: 1, primaryPageIndex: 1),
            ReaderSlotSingle(pageIndex: 3),
            ReaderSlotSingle(pageIndex: 4),
            ReaderSlotSingle(pageIndex: 5),
        ]

        let fakeState = FakeMangaReaderState(
            direction: .rtl,
            pages: sixPages,
            slots: fourSpreadSlots,
            previousBoundary: ChapterBoundaryUnlocked(chapter: prevChapter, startFromEnd: true),
            nextBoundary: ChapterBoundaryUnlocked(chapter: nextChapter, startFromEnd: false)
        )
        fakeState.goToSlot(slotIndex: 1) // pagerPage == 2 (startBoundaryOffset == 1)

        let controller = MangaReaderInteractionController()
        let hostBox = ReaderTestHostModel(state: fakeState, controller: controller)

        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 800, height: 600)
        window.rootViewController = UIHostingController(rootView: ReaderTestHostView(model: hostBox))
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKey()
        }

        try await eventually(window: window) {
            controller.isBound && controller.activePagerPage == 2 && hostBox.pageFrames[1] != nil
        }
        try await Task.sleep(for: .milliseconds(60))
        window.layoutIfNeeded()

        let initialScrollView = try XCTUnwrap(findHorizontalPagingScrollView(in: window))
        let initialScrollViewID = ObjectIdentifier(initialScrollView)
        XCTAssertNil(controller.zoomResetHandler)

        // 1. Zoom active slot (slot 1 / pagerPage 2) and verify 1pt live width resizes + non-active spread split do NOT tear down subtree or reset zoom
        controller.handleDoubleTap(at: CGPoint(x: 400, y: 300))
        try await eventually(window: window) { controller.zoomResetHandler != nil }

        for width in [CGFloat(799), 798, 760, 800] {
            window.frame = CGRect(x: 0, y: 0, width: width, height: 600)
            window.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(25))
            XCTAssertEqual(
                ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: window))),
                initialScrollViewID,
                "1pt / live width resize (\(width)) must keep the same UIScrollView mounted"
            )
            XCTAssertNotNil(controller.zoomResetHandler, "Width resize (\(width)) must not wipe active zoom")
            XCTAssertEqual(fakeState.currentSlotIndex, 1)
            XCTAssertEqual(controller.activePagerPage, 2)
        }

        // Non-active spread split (totalPagerPages 6 -> 7 in RTL) while zoomed on slot 1 (pagerPage 2)
        fakeState.updateSlots(fiveSplitSlots)
        try await Task.sleep(for: .milliseconds(60))
        window.layoutIfNeeded()
        try await eventually(window: window) {
            // In 7-page RTL at width 800, pagerPage 2 sits at visual index (7 - 1 - 2) = 4 -> x == 3200
            abs(initialScrollView.contentOffset.x - 3200) <= 2.0
        }
        XCTAssertEqual(
            ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: window))),
            initialScrollViewID,
            "Runtime spread split while zoomed must not tear down UIScrollView"
        )
        XCTAssertNotNil(controller.zoomResetHandler, "Non-active spread split must not wipe active zoom")
        XCTAssertEqual(fakeState.currentSlotIndex, 1)
        XCTAssertEqual(controller.activePagerPage, 2)

        // Escape resets zoom cleanly without navigating back
        XCTAssertTrue(controller.handleKey(.escape))
        try await eventually(window: window) { controller.zoomResetHandler == nil }

        // 2. Boundary card position preserved across width resizes AND slot-count splits (both nextBoundaryPage and prevBoundaryPage)
        fakeState.updateSlots(fourSpreadSlots) // totalPagerPages == 6, nextBoundaryPage == 5
        try await Task.sleep(for: .milliseconds(40))
        window.layoutIfNeeded()

        controller.scrollToBoundary(isNext: true)
        try await eventually(window: window) {
            controller.isOnNextBoundaryPage && controller.activePagerPage == 5
        }
        try await Task.sleep(for: .milliseconds(40))
        window.layoutIfNeeded()

        // Resize width while standing on nextBoundaryPage
        for width in [CGFloat(799), 540, 800] {
            window.frame = CGRect(x: 0, y: 0, width: width, height: 600)
            window.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(40))
            window.layoutIfNeeded()
            XCTAssertEqual(ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: window))), initialScrollViewID)
            XCTAssertTrue(controller.isOnNextBoundaryPage, "Width resize (\(width)) must not kick user off nextBoundaryPage")
            XCTAssertEqual(controller.activePagerPage, 5)
        }

        // Split spread while standing on nextBoundaryPage (slots 4 -> 5, shifting currentSlotIndex on last page AND moving nextBoundaryPage 5 -> 6)
        fakeState.goToSlot(slotIndex: 3) // page 5 -> slotIndex shifts 3 -> 4 when fiveSplitSlots applies, while on nextBoundaryPage!
        try await eventually(window: window) { controller.activePagerPage == 4 && !controller.isOnNextBoundaryPage }
        controller.scrollToBoundary(isNext: true)
        try await eventually(window: window) { controller.isOnNextBoundaryPage && controller.activePagerPage == 5 }
        fakeState.updateSlots(fiveSplitSlots)
        try await Task.sleep(for: .milliseconds(60))
        window.layoutIfNeeded()
        try await eventually(window: window) {
            controller.isOnNextBoundaryPage && controller.activePagerPage == 6 && abs(initialScrollView.contentOffset.x - 0) <= 2.0
        }
        XCTAssertEqual(ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: window))), initialScrollViewID)

        // Stand on prevBoundaryPage (0): preserved across width resize and slot-count shrink (5 -> 4)
        controller.scrollToBoundary(isNext: false)
        try await eventually(window: window) {
            controller.isOnPreviousBoundaryPage && controller.activePagerPage == 0
        }
        window.frame = CGRect(x: 0, y: 0, width: 540, height: 600)
        window.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(50))
        window.layoutIfNeeded()
        XCTAssertTrue(controller.isOnPreviousBoundaryPage, "Width resize must not kick user off prevBoundaryPage")
        XCTAssertEqual(controller.activePagerPage, 0)

        window.frame = CGRect(x: 0, y: 0, width: 800, height: 600)
        window.layoutIfNeeded()
        fakeState.updateSlots(fourSpreadSlots) // totalPagerPages 7 -> 6, prevBoundaryPage == 0 at x == 4000
        try await Task.sleep(for: .milliseconds(60))
        window.layoutIfNeeded()
        try await eventually(window: window) {
            controller.isOnPreviousBoundaryPage && controller.activePagerPage == 0 && abs(initialScrollView.contentOffset.x - 4000) <= 2.0
        }
        XCTAssertEqual(ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: window))), initialScrollViewID)

        // 3. Mid-swipe spread split in .rtl freezes ForEach layout during active gesture and lands on exact next page (page 3, without skipping to page 4)
        fakeState.goToSlot(slotIndex: 1) // slot 1 (pages 1 & 2) -> pagerPage == 2 in 6-page RTL (x == 2400)
        try await Task.sleep(for: .milliseconds(60))
        window.layoutIfNeeded()
        try await eventually(window: window) {
            !controller.isOnPreviousBoundaryPage &&
                controller.activePagerPage == 2 &&
                abs(initialScrollView.contentOffset.x - 2400) <= 2.0
        }

        controller.processPanGestureStateForTesting(.began)
        initialScrollView.delegate?.scrollViewWillBeginDragging?(initialScrollView)
        controller.processPanGestureStateForTesting(.changed)
        let halfway = initialScrollView.contentOffset.x - initialScrollView.bounds.width / 2
        initialScrollView.setContentOffset(CGPoint(x: halfway, y: 0), animated: false)
        window.layoutIfNeeded()
        XCTAssertEqual(initialScrollView.contentOffset.x, halfway, accuracy: 2)
        XCTAssertTrue(controller.hasUnconsumedUserScroll)

        // Trigger spread split mid-drag in RTL (slot 2 Double(4,3) -> Single(3), Single(4))
        fakeState.updateSlots(fiveSplitSlots)
        window.layoutIfNeeded()
        XCTAssertEqual(ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: window))), initialScrollViewID)
        XCTAssertEqual(initialScrollView.contentSize.width, 4800, accuracy: 2.0, "Displayed slots must stay frozen during active RTL swipe")
        XCTAssertEqual(initialScrollView.contentOffset.x, halfway, accuracy: 2.0, "Mid-drag RTL spread split must not shift or yank active swipe")

        // Cross 50% midpoint onto slot 2 (x == 1600 in frozen 6-page RTL) and emit additional pan .changed events before finger lift
        initialScrollView.bounds.origin = CGPoint(x: 1600, y: 0)
        initialScrollView.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(25))
        controller.processPanGestureStateForTesting(.changed) // post-midpoint .changed sets hasUnconsumedUserScroll = true again
        XCTAssertTrue(controller.hasUnconsumedUserScroll)

        var rtlTargetOffset = CGPoint(x: 1600, y: 0)
        initialScrollView.delegate?.scrollViewWillEndDragging?(initialScrollView, withVelocity: .zero, targetContentOffset: &rtlTargetOffset)
        initialScrollView.delegate?.scrollViewDidEndDragging?(initialScrollView, willDecelerate: false)
        controller.processPanGestureStateForTesting(.ended)

        XCTAssertFalse(controller.hasUnconsumedUserScroll, "hasUnconsumedUserScroll must clear once pan interaction settles to idle")
        try await eventually(window: window) {
            guard let page3Frame = hostBox.pageFrames[3] else { return false }
            return fakeState.currentPageIndex == 3 &&
                fakeState.currentSlotIndex == 2 &&
                controller.activePagerPage == 3 &&
                abs(initialScrollView.contentOffset.x - 2400) <= 2.0 &&
                page3Frame.minX >= -1.5 &&
                page3Frame.maxX <= 801.5
        }
        XCTAssertEqual(ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: window))), initialScrollViewID)

        // 4. Cancelled < 50% drag in .rtl clears hasUnconsumedUserScroll so subsequent .rtl slot splits & idle width resizes re-anchor cleanly
        fakeState.updateSlots(fourSpreadSlots)
        fakeState.goToSlot(slotIndex: 1) // page 1, slot 1, pagerPage == 2 at x == 2400
        try await Task.sleep(for: .milliseconds(60))
        window.layoutIfNeeded()
        try await eventually(window: window) {
            controller.activePagerPage == 2 && abs(initialScrollView.contentOffset.x - 2400) <= 2.0
        }

        controller.processPanGestureStateForTesting(.began)
        initialScrollView.delegate?.scrollViewWillBeginDragging?(initialScrollView)
        controller.processPanGestureStateForTesting(.changed)
        initialScrollView.bounds.origin = CGPoint(x: 2250, y: 0) // < 50% drag in RTL (150pt of 800pt)
        initialScrollView.layoutIfNeeded()
        var cancelledTarget = CGPoint(x: 2400, y: 0)
        initialScrollView.delegate?.scrollViewWillEndDragging?(initialScrollView, withVelocity: .zero, targetContentOffset: &cancelledTarget)
        initialScrollView.bounds.origin = CGPoint(x: 2400, y: 0)
        initialScrollView.layoutIfNeeded()
        initialScrollView.delegate?.scrollViewDidEndDragging?(initialScrollView, willDecelerate: false)
        controller.processPanGestureStateForTesting(.ended)

        XCTAssertFalse(controller.hasUnconsumedUserScroll, "Cancelled < 50% drag must clear hasUnconsumedUserScroll on settle")
        XCTAssertEqual(fakeState.currentSlotIndex, 1)
        XCTAssertEqual(fakeState.currentPageIndex, 1)

        // Follow cancelled drag immediately with an .rtl slot split (6 -> 7 pages) and idle width resize (800 -> 540 -> 800)
        fakeState.updateSlots(fiveSplitSlots)
        window.layoutIfNeeded()
        try await eventually(window: window) {
            fakeState.currentSlotIndex == 1 &&
                controller.activePagerPage == 2 &&
                abs(initialScrollView.contentOffset.x - 3200) <= 2.0
        }

        window.frame = CGRect(x: 0, y: 0, width: 540, height: 600)
        window.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(60))
        window.layoutIfNeeded()
        try await eventually(window: window) {
            guard let frame = hostBox.pageFrames[1] else { return false }
            return fakeState.currentSlotIndex == 1 &&
                controller.activePagerPage == 2 &&
                frame.minX >= -1.5 &&
                frame.maxX <= 541.5
        }
        XCTAssertFalse(controller.hasUnconsumedUserScroll)

        window.frame = CGRect(x: 0, y: 0, width: 800, height: 600)
        window.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(60))
        window.layoutIfNeeded()
        try await eventually(window: window) {
            fakeState.currentSlotIndex == 1 && controller.activePagerPage == 2
        }
        XCTAssertEqual(ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: window))), initialScrollViewID)

        // 5. Earlier-slot split mid-swipe in .ltr also freezes layout during drag and lands on exact destination page
        let ltrPreSplitSlots: [any ReaderSlot] = [
            ReaderSlotDouble(leftPageIndex: 0, rightPageIndex: 1, primaryPageIndex: 0),
            ReaderSlotDouble(leftPageIndex: 2, rightPageIndex: 3, primaryPageIndex: 2),
            ReaderSlotDouble(leftPageIndex: 4, rightPageIndex: 5, primaryPageIndex: 4),
        ]
        let ltrEarlierSplitSlots: [any ReaderSlot] = [
            ReaderSlotSingle(pageIndex: 0),
            ReaderSlotSingle(pageIndex: 1),
            ReaderSlotDouble(leftPageIndex: 2, rightPageIndex: 3, primaryPageIndex: 2),
            ReaderSlotDouble(leftPageIndex: 4, rightPageIndex: 5, primaryPageIndex: 4),
        ]
        fakeState.setDirection(direction: .ltr)
        fakeState.updateSlots(ltrPreSplitSlots)
        fakeState.goToSlot(slotIndex: 1) // pages [2, 3], pagerPage == 2 in 5-page LTR (x == 1600)
        try await Task.sleep(for: .milliseconds(80))
        window.layoutIfNeeded()
        try await eventually(window: window) {
            controller.activePagerPage == 2 && abs((findHorizontalPagingScrollView(in: window)?.contentOffset.x ?? 0) - 1600) <= 2.0
        }
        let ltrScrollView = try XCTUnwrap(findHorizontalPagingScrollView(in: window))
        let ltrScrollViewID = ObjectIdentifier(ltrScrollView)

        controller.processPanGestureStateForTesting(.began)
        ltrScrollView.delegate?.scrollViewWillBeginDragging?(ltrScrollView)
        controller.processPanGestureStateForTesting(.changed)
        ltrScrollView.bounds.origin = CGPoint(x: 2000, y: 0) // halfway between [2,3] (1600) and [4,5] (2400)
        window.layoutIfNeeded()
        // Split earlier slot 0 while swiping forward from slot 1 to slot 2 in LTR
        fakeState.updateSlots(ltrEarlierSplitSlots)
        window.layoutIfNeeded()
        XCTAssertEqual(ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: window))), ltrScrollViewID)
        XCTAssertEqual(ltrScrollView.contentSize.width, 4000, accuracy: 2.0)
        XCTAssertEqual(ltrScrollView.contentOffset.x, 2000, accuracy: 2.0)

        ltrScrollView.bounds.origin = CGPoint(x: 2400, y: 0)
        ltrScrollView.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(25))
        var ltrTargetOffset = CGPoint(x: 2400, y: 0)
        ltrScrollView.delegate?.scrollViewWillEndDragging?(ltrScrollView, withVelocity: .zero, targetContentOffset: &ltrTargetOffset)
        ltrScrollView.delegate?.scrollViewDidEndDragging?(ltrScrollView, willDecelerate: false)
        controller.processPanGestureStateForTesting(.ended)

        try await eventually(window: window) {
            fakeState.currentPageIndex == 4 &&
                fakeState.currentSlotIndex == 3 &&
                controller.activePagerPage == 4 &&
                abs(ltrScrollView.contentOffset.x - 3200) <= 2.0
        }
        XCTAssertFalse(controller.hasUnconsumedUserScroll)
        XCTAssertEqual(ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: window))), ltrScrollViewID)
    }

    func testAsyncSnapshotDeliveryMidSwipeSplitStepNavigationAndLockedBoundary() async throws {
        let prevLockedChapter = UiMangaChapter(
            id: "ch-0-locked",
            title: "Chapter 0 VIP",
            subtitle: nil,
            uploadedAt: nil,
            locked: true,
            url: "https://manga.example/paywall/ch-0"
        )
        let nextLockedChapter = UiMangaChapter(
            id: "ch-2-locked",
            title: "Chapter 2 VIP",
            subtitle: nil,
            uploadedAt: nil,
            locked: true,
            url: "https://manga.example/paywall/ch-2"
        )
        let sixPages = makeSamplePages(count: 6)
        let fourSpreadSlots: [any ReaderSlot] = [
            ReaderSlotSingle(pageIndex: 0),
            ReaderSlotDouble(leftPageIndex: 2, rightPageIndex: 1, primaryPageIndex: 1),
            ReaderSlotDouble(leftPageIndex: 4, rightPageIndex: 3, primaryPageIndex: 3),
            ReaderSlotSingle(pageIndex: 5),
        ]
        let fiveSplitSlots: [any ReaderSlot] = [
            ReaderSlotSingle(pageIndex: 0),
            ReaderSlotDouble(leftPageIndex: 2, rightPageIndex: 1, primaryPageIndex: 1),
            ReaderSlotSingle(pageIndex: 3),
            ReaderSlotSingle(pageIndex: 4),
            ReaderSlotSingle(pageIndex: 5),
        ]

        let fakeState = FakeMangaReaderState(
            direction: .rtl,
            pages: sixPages,
            slots: fourSpreadSlots,
            previousBoundary: ChapterBoundaryLocked(chapter: prevLockedChapter, openUrl: "https://manga.example/paywall/ch-0"),
            nextBoundary: ChapterBoundaryLocked(chapter: nextLockedChapter, openUrl: "https://manga.example/paywall/ch-2")
        )
        fakeState.goToSlot(slotIndex: 1) // pagerPage == 2 (startBoundaryOffset == 1)

        let controller = MangaReaderInteractionController()
        let hostBox = ReaderTestHostModel(state: fakeState, controller: controller, deferSnapshotDelivery: true)

        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 800, height: 600)
        window.rootViewController = UIHostingController(rootView: ReaderTestHostView(model: hostBox))
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKey()
        }

        try await eventually(window: window) {
            controller.isBound && controller.activePagerPage == 2 && hostBox.pageFrames[1] != nil
        }
        try await Task.sleep(for: .milliseconds(60))
        window.layoutIfNeeded()

        let scrollView = try XCTUnwrap(findHorizontalPagingScrollView(in: window))

        // (a) .rtl mid-swipe spread split with deferred async snapshot publication must NOT yank back to pre-swipe slot 1
        controller.processPanGestureStateForTesting(.began)
        scrollView.delegate?.scrollViewWillBeginDragging?(scrollView)
        controller.processPanGestureStateForTesting(.changed)
        scrollView.bounds.origin = CGPoint(x: 2000, y: 0)
        scrollView.layoutIfNeeded()

        fakeState.updateSlots(fiveSplitSlots)
        try await Task.sleep(for: .milliseconds(25))
        window.layoutIfNeeded()

        scrollView.bounds.origin = CGPoint(x: 1600, y: 0)
        scrollView.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(25))
        controller.processPanGestureStateForTesting(.changed)

        var rtlTargetOffset = CGPoint(x: 1600, y: 0)
        scrollView.delegate?.scrollViewWillEndDragging?(scrollView, withVelocity: .zero, targetContentOffset: &rtlTargetOffset)
        scrollView.delegate?.scrollViewDidEndDragging?(scrollView, willDecelerate: false)
        controller.processPanGestureStateForTesting(.ended)

        // Immediately on finger lift (before async snapshot settles) AND after async snapshot settles, must stay on pagerPage 3 (page 3, slot 2)
        XCTAssertEqual(controller.activePagerPage, 3, "Mid-swipe split must not yank back to pre-swipe pagerPage 2 while awaiting async snapshot")
        try await eventually(window: window) {
            guard let page3Frame = hostBox.pageFrames[3] else { return false }
            return hostBox.snapshot.currentPageIndex == 3 &&
                hostBox.snapshot.currentSlotIndex == 2 &&
                controller.activePagerPage == 3 &&
                abs(scrollView.contentOffset.x - 2400) <= 2.0 &&
                page3Frame.minX >= -1.5 &&
                page3Frame.maxX <= 801.5
        }

        // (b) pagedStepForward / pagedStepBackward advance & animate accurately under deferred async snapshot delivery
        XCTAssertTrue(controller.handleKey(.leftArrow)) // RTL forward: slot 2 -> slot 3 (page 4, pagerPage 4)
        XCTAssertEqual(controller.activePagerPage, 4)
        try await eventually(window: window) {
            hostBox.snapshot.currentSlotIndex == 3 &&
                hostBox.snapshot.currentPageIndex == 4 &&
                controller.activePagerPage == 4
        }

        XCTAssertTrue(controller.handleKey(.leftArrow)) // RTL forward: slot 3 -> slot 4 (last slot, page 5, pagerPage 5)
        XCTAssertEqual(controller.activePagerPage, 5)
        try await eventually(window: window) {
            hostBox.snapshot.currentSlotIndex == 4 &&
                hostBox.snapshot.currentPageIndex == 5 &&
                controller.activePagerPage == 5
        }

        // (c) Stepping forward on the last slot when nextBoundary is ChapterBoundaryLocked scrolls onto nextBoundaryPage (pagerPage 6)
        XCTAssertFalse(controller.isOnNextBoundaryPage)
        XCTAssertTrue(controller.handleKey(.leftArrow))
        try await eventually(window: window) {
            controller.isOnNextBoundaryPage && controller.activePagerPage == 6
        }
        // Stepping forward again on locked nextBoundaryPage remains on nextBoundaryPage
        XCTAssertTrue(controller.handleKey(.leftArrow))
        XCTAssertTrue(controller.isOnNextBoundaryPage)
        XCTAssertEqual(controller.activePagerPage, 6)

        // Stepping backward from nextBoundaryPage returns to last slot (slot 4 / pagerPage 5)
        XCTAssertTrue(controller.handleKey(.rightArrow))
        try await eventually(window: window) {
            !controller.isOnNextBoundaryPage &&
                controller.activePagerPage == 5 &&
                hostBox.snapshot.currentSlotIndex == 4
        }

        // Stepping backward on slot 0 when previousBoundary is ChapterBoundaryLocked scrolls onto prevBoundaryPage (0)
        fakeState.goToSlot(slotIndex: 0)
        try await eventually(window: window) {
            hostBox.snapshot.currentSlotIndex == 0 && controller.activePagerPage == 1
        }
        XCTAssertTrue(controller.handleKey(.rightArrow)) // RTL backward on slot 0 -> prevBoundaryPage (0)
        try await eventually(window: window) {
            controller.isOnPreviousBoundaryPage && controller.activePagerPage == 0
        }
    }

    func testReaderGifPageShowsShareButtonExportsGifAndSurfacesShareErrors() async throws {
        let gifURL = "https://manga.example/ch-1/page-\(UUID().uuidString)?format=gif&signature=fixture"
        let gifMedia = UiMediaGif(
            url: gifURL,
            previewUrl: gifURL,
            description: "Animated page",
            height: 1400,
            width: 1000,
            customHeaders: ["Referer": "https://manga.example/ch-1"],
            proxy: ProxyRef.Global.shared
        )
        let gifState = FakeMangaReaderState(
            direction: .rtl,
            pages: [
                ReaderPageState(
                    id: "gif-p0",
                    index: 0,
                    status: PageMediaStatusReady(original: gifMedia, displayed: gifMedia),
                    aspectRatio: KotlinFloat(value: 0.71)
                ),
            ],
            slots: [ReaderSlotSingle(pageIndex: 0)]
        )

        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKey()
        }

        let minimalGifBytes = Data([
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80, 0x00, 0x00,
            0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00,
            0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x4C, 0x01, 0x00, 0x3B,
        ])
        try await KingfisherManager.shared.cache.storeToDisk(minimalGifBytes, forKey: gifMedia.cacheKey)
        defer {
            Task { try? await KingfisherManager.shared.cache.removeImage(forKey: gifMedia.cacheKey) }
        }

        // 1. HUD shows Share button on UiMediaGif pages (and hides it on UiMediaUgoira)
        var shareVisibleForGif = false
        window.rootViewController = UIHostingController(
            rootView: MangaReaderContentView(
                state: gifState,
                hudVisible: .constant(true),
                goBack: {},
                onSharePage: { _, _, _ in }
            )
            .onPreferenceChange(IOSMangaReaderShareButtonVisiblePreferenceKey.self) { shareVisibleForGif = $0 }
        )
        window.makeKeyAndVisible()
        try await eventually(window: window) {
            shareVisibleForGif && descendants(window).contains {
                ($0 as? AnimatedImageView)?.animator?.frame(at: 1) != nil
            }
        }

        let ugoiraAccount = MicroBlogKey(id: "u1", host: "pixiv.net")
        let ugoiraMedia = UiMediaUgoira(
            statusKey: MicroBlogKey(id: "s1", host: "pixiv.net"),
            accountKey: ugoiraAccount,
            previewUrl: gifURL,
            originalFrameUrl: nil,
            description: nil,
            height: 1400,
            width: 1000,
            sensitive: false,
            customHeaders: nil,
            proxy: ProxyRef.companion.forAccount(accountKey: ugoiraAccount),
            sourceUrl: nil
        )
        let ugoiraState = FakeMangaReaderState(
            direction: .rtl,
            pages: [
                ReaderPageState(
                    id: "ugoira-p0",
                    index: 0,
                    status: PageMediaStatusReady(original: ugoiraMedia, displayed: ugoiraMedia),
                    aspectRatio: KotlinFloat(value: 0.71)
                ),
            ],
            slots: [ReaderSlotSingle(pageIndex: 0)]
        )
        var shareVisibleForUgoira = true
        window.rootViewController = UIHostingController(
            rootView: MangaReaderContentView(
                state: ugoiraState,
                hudVisible: .constant(true),
                goBack: {},
                onSharePage: { _, _, _ in }
            )
            .onPreferenceChange(IOSMangaReaderShareButtonVisiblePreferenceKey.self) { shareVisibleForUgoira = $0 }
        )
        window.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(40))
        XCTAssertFalse(shareVisibleForUgoira)

        // 2. Cached UiMediaGif exports a .gif share file with no error alert
        let coordinator = IOSMangaReaderShareCoordinator()
        var exportedShareURL: URL?
        await coordinator.sharePage(
            displayed: gifMedia,
            mangaId: "manga-99",
            chapterId: "ch-1",
            pageIndex: 0,
            chapterUrl: "https://manga.example/ch-1",
            onShareChapterURL: { _ in XCTFail("Should share GIF file, not fallback chapter URL") },
            onPreparingNeeded: {},
            presentFile: { url in exportedShareURL = url }
        )
        XCTAssertNil(coordinator.exportAlert)
        let sharedGifURL = try XCTUnwrap(exportedShareURL)
        defer { try? FileManager.default.removeItem(at: sharedGifURL) }
        XCTAssertEqual(sharedGifURL.pathExtension.lowercased(), "gif")
        XCTAssertEqual(try Data(contentsOf: sharedGifURL), minimalGifBytes)

        // 3. Unowned local file:// URL surfaces exportAlert instead of silently swallowing the failure
        let unownedLocalURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("unowned-translated-\(UUID().uuidString).png")
        let localDisplayed = UiMediaImage(
            url: unownedLocalURL.absoluteString,
            previewUrl: unownedLocalURL.absoluteString,
            description: nil,
            height: 100,
            width: 100,
            sensitive: false,
            customHeaders: nil,
            proxy: ProxyRef.Unbound.shared
        )
        await coordinator.sharePage(
            displayed: localDisplayed,
            mangaId: "manga-99",
            chapterId: "ch-1",
            pageIndex: 0,
            chapterUrl: nil,
            onShareChapterURL: { _ in },
            onPreparingNeeded: {},
            presentFile: { _ in XCTFail("Should not present unowned local file") }
        )
        XCTAssertNotNil(coordinator.exportAlert, "Local export failure must surface exportAlert")

        // 4. Unreachable remote UiMediaGif surfaces exportAlert after firing onPreparingNeeded
        coordinator.exportAlert = nil
        nonisolated(unsafe) var didNotifyPreparing = false
        let unreachableGif = UiMediaGif(
            url: "http://127.0.0.1:1/unreachable-\(UUID().uuidString).gif",
            previewUrl: "http://127.0.0.1:1/unreachable.png",
            description: nil,
            height: 100,
            width: 100,
            customHeaders: ["Referer": "https://manga.example"],
            proxy: ProxyRef.Unbound.shared
        )
        await coordinator.sharePage(
            displayed: unreachableGif,
            mangaId: "manga-99",
            chapterId: "ch-1",
            pageIndex: 0,
            chapterUrl: nil,
            onShareChapterURL: { _ in },
            onPreparingNeeded: { didNotifyPreparing = true },
            presentFile: { _ in XCTFail("Should not present failed download") }
        )
        XCTAssertTrue(didNotifyPreparing)
        XCTAssertNotNil(coordinator.exportAlert, "Remote download failure must surface exportAlert")
    }

    func testMangaDetailRendersGroupedTagsChapterPillsReadStateAndLockedFallback() async throws {
        let mangaKey = MicroBlogKey(id: "manga-99", host: "manga.example")
        let accountType = AccountType.GuestPlugin(platformId: "manga-plat", host: "manga.example")
        let ch0 = UiMangaChapter(id: "ch-0", title: "Chapter 0: Prologue", subtitle: "Vol. 1", uploadedAt: nil, locked: false, url: nil)
        let ch1 = UiMangaChapter(id: "ch-1", title: "Chapter 1: Dawn", subtitle: "Vol. 1", uploadedAt: nil, locked: false, url: nil)
        let ch2Locked = UiMangaChapter(id: "ch-2", title: "Chapter 2: VIP Epilogue", subtitle: "Subscriber", uploadedAt: nil, locked: true, url: nil)

        let detail = UiMangaDetail(
            pluginId: "dev.example.manga",
            platformId: "manga-plat",
            mangaKey: mangaKey,
            accountType: accountType,
            title: "Starlight Odyssey",
            altTitles: ["星光奥德赛"],
            cover: nil,
            author: nil,
            authors: ["Aria Lin"],
            artists: ["Kuro"],
            status: .ongoing,
            content: makeRichSynopsis(["A journey across star seas."]),
            url: "https://manga.example/series/99",
            updatedAt: nil,
            readingDirection: .rtl,
            tagGroups: [
                UiMangaTagGroup(
                    id: "genre",
                    title: UiTextRaw(string: "Genre"),
                    tags: [
                        UiMangaTag(name: "Sci-Fi", clickEvent: ClickEventNoop.shared),
                        UiMangaTag(name: "Adventure", clickEvent: ClickEventNoop.shared),
                    ]
                ),
                UiMangaTagGroup(
                    id: "theme",
                    title: UiTextRaw(string: "Theme"),
                    tags: [
                        UiMangaTag(name: "Space Opera", clickEvent: ClickEventNoop.shared),
                    ]
                ),
            ],
            chapterGroups: [
                UiMangaChapterGroup(id: "main", title: UiTextRaw(string: "Main Story"), chapters: [ch0, ch1, ch2Locked]),
                UiMangaChapterGroup(id: "extra", title: UiTextRaw(string: "Side Stories"), chapters: [ch0]),
            ],
            isBookmarked: false,
            bookmarkAction: ClickEventNoop.shared,
            actions: [],
            sourceLanguages: ["en"]
        )

        let chapterItems = [
            UiMangaChapterItem(chapter: ch0, isRead: true, isCurrent: false, lastPageIndex: 19, pageCount: 20),
            UiMangaChapterItem(chapter: ch1, isRead: false, isCurrent: true, lastPageIndex: 4, pageCount: 18),
            UiMangaChapterItem(chapter: ch2Locked, isRead: false, isCurrent: false, lastPageIndex: 0, pageCount: 0),
        ]

        // Read-state emphasis & locked-chapter browser fallback URL resolution
        XCTAssertEqual(MangaDetailFormatting.chapterTitleEmphasis(for: chapterItems[0]), .muted)
        XCTAssertEqual(MangaDetailFormatting.chapterTitleEmphasis(for: chapterItems[1]), .current)
        XCTAssertEqual(MangaDetailFormatting.chapterTitleEmphasis(for: chapterItems[2]), .muted)
        XCTAssertEqual(
            MangaDetailFormatting.chapterTitleEmphasis(
                for: UiMangaChapterItem(chapter: ch0, isRead: false, isCurrent: false, lastPageIndex: 0, pageCount: 0)
            ),
            .unread
        )
        XCTAssertEqual(
            MangaDetailFormatting.resolveLockedChapterURL(chapter: ch2Locked, detailURL: detail.url),
            "https://manga.example/series/99"
        )

        let primaryTarget = PrimaryReadTarget(
            chapterId: "ch-1",
            chapterTitle: "Chapter 1: Dawn",
            initialPage: 4,
            isContinue: true,
            isSingleChapterWork: false
        )

        // Verify compact vertical layout on iPhone vs split 2-pane layout on regular width (iPad)
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKey()
        }

        var compactFrames = MangaDetailPaneFrames()
        window.rootViewController = UIHostingController(
            rootView: MangaDetailContentView(
                detail: UiStateSuccess(data: detail),
                selectedGroupId: "main",
                chapterSortDescending: false,
                chapters: chapterItems,
                primaryReadTarget: primaryTarget,
                onNavigate: { _ in }
            )
            .environment(\.horizontalSizeClass, .compact)
            .onPreferenceChange(MangaDetailPaneFramesPreferenceKey.self) { compactFrames = $0 }
        )
        window.makeKeyAndVisible()

        try await eventually(window: window) {
            compactFrames.heroCard != nil && compactFrames.chaptersSection != nil
        }
        let compactHero = try XCTUnwrap(compactFrames.heroCard)
        let compactChapters = try XCTUnwrap(compactFrames.chaptersSection)
        XCTAssertLessThan(
            compactHero.maxY,
            compactChapters.minY,
            "Expected compact vertical layout with hero (\(compactHero)) above chapters (\(compactChapters))"
        )

        var regularFrames = MangaDetailPaneFrames()
        window.frame = CGRect(x: 0, y: 0, width: 1024, height: 768)
        window.rootViewController = UIHostingController(
            rootView: MangaDetailContentView(
                detail: UiStateSuccess(data: detail),
                selectedGroupId: "main",
                chapterSortDescending: false,
                chapters: chapterItems,
                primaryReadTarget: primaryTarget,
                onNavigate: { _ in }
            )
            .environment(\.horizontalSizeClass, .regular)
            .onPreferenceChange(MangaDetailPaneFramesPreferenceKey.self) { regularFrames = $0 }
        )

        try await eventually(window: window) {
            regularFrames.heroCard != nil && regularFrames.chaptersSection != nil
        }
        let regularHero = try XCTUnwrap(regularFrames.heroCard)
        let regularChapters = try XCTUnwrap(regularFrames.chaptersSection)
        XCTAssertLessThan(
            regularHero.maxX,
            regularChapters.minX,
            "Expected regular-width split layout with hero (\(regularHero)) to the left of chapters (\(regularChapters))"
        )

        // Verify vector text of rendered detail components
        let detailStack = VStack(alignment: .leading, spacing: 12) {
            MangaHeroCardView(
                detail: detail,
                translation: nil,
                primaryReadTarget: primaryTarget,
                onOpenCover: { _ in },
                onOpenProfile: { _, _ in },
                onOpenReader: { _, _ in }
            )
            MangaSynopsisAndTagsCardView(detail: detail)
            MangaChapterListSection(
                chapterGroups: detail.chapterGroups,
                selectedGroupId: "main",
                chapterSortDescending: false,
                chapters: chapterItems,
                onSelectGroup: { _ in },
                onToggleSort: {},
                onChapterTap: { _ in },
                onLockedChapterTap: { _ in },
                onSetChapterRead: { _, _ in }
            )
        }
        let renderedText = try renderPDFText(detailStack, width: 390, height: 900)
        let showMoreLabel = FlareAppleUILocalization.string("mastodon_item_show_more", fallback: "Show more")
        XCTAssertTrue(renderedText.contains("Starlight Odyssey"), renderedText)
        XCTAssertTrue(renderedText.contains("A journey across star seas."), renderedText)
        XCTAssertFalse(renderedText.contains(showMoreLabel), "Short <= 4 line synopsis must not show expand toggle")
        XCTAssertTrue(renderedText.contains("Genre"), renderedText)
        XCTAssertTrue(renderedText.contains("Sci-Fi"), renderedText)
        XCTAssertTrue(renderedText.contains("Adventure"), renderedText)
        XCTAssertTrue(renderedText.contains("Theme"), renderedText)
        XCTAssertTrue(renderedText.contains("Space Opera"), renderedText)
        XCTAssertTrue(renderedText.contains("Main Story"), renderedText)
        XCTAssertTrue(renderedText.contains("Side Stories"), renderedText)
        XCTAssertTrue(renderedText.contains("Chapter 0: Prologue"), renderedText)
        XCTAssertTrue(renderedText.contains("Chapter 1: Dawn"), renderedText)
        XCTAssertTrue(renderedText.contains("Chapter 2: VIP Epilogue"), renderedText)

        // Verify guest mode does not render a dead/duplicate standalone bookmark button, and long synopses show expand toggle
        let guestHeroHost = UIHostingController(
            rootView: MangaHeroCardView(
                detail: detail,
                translation: nil,
                primaryReadTarget: nil,
                onOpenCover: { _ in },
                onOpenProfile: { _, _ in },
                onOpenReader: { _, _ in }
            )
        )
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 400)
        window.rootViewController = guestHeroHost
        window.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(30))
        let addBookmarkLabel = FlareAppleUILocalization.string("bookmark_add", fallback: "Add bookmark")
        let renderedEmptyGuestHero = try renderPDFText(
            MangaHeroCardView(
                detail: detail,
                translation: nil,
                primaryReadTarget: primaryTarget,
                onOpenCover: { _ in },
                onOpenProfile: { _, _ in },
                onOpenReader: { _, _ in }
            ),
            width: 390,
            height: 300
        )
        XCTAssertFalse(renderedEmptyGuestHero.contains(addBookmarkLabel))
        XCTAssertEqual(
            countSubviews(ofType: UIButton.self, in: guestHeroHost.view),
            0,
            "Guest detail with empty actions and ClickEventNoop bookmarkAction must render zero interactive buttons when primaryReadTarget is nil"
        )

        let guestDisabledBookmark = ActionMenu.Item(
            updateKey: "guest-bm",
            icon: .bookmark,
            text: nil,
            count: nil,
            color: .contentColor,
            clickEvent: ClickEventNoop.shared,
            actionFamily: .bookmark,
            enabled: false
        )
        let longSynopsisDetail = UiMangaDetail(
            pluginId: detail.pluginId,
            platformId: detail.platformId,
            mangaKey: detail.mangaKey,
            accountType: detail.accountType,
            title: detail.title,
            altTitles: detail.altTitles,
            cover: nil,
            author: nil,
            authors: detail.authors,
            artists: detail.artists,
            status: detail.status,
            content: makeRichSynopsis((1...7).map { "Paragraph \($0): Long star-sea chronicle across uncharted galaxies." }),
            url: detail.url,
            updatedAt: nil,
            readingDirection: .rtl,
            tagGroups: detail.tagGroups,
            chapterGroups: detail.chapterGroups,
            isBookmarked: false,
            bookmarkAction: ClickEventNoop.shared,
            actions: [guestDisabledBookmark],
            sourceLanguages: ["en"]
        )
        let longSynopsisRendered = try renderPDFText(
            MangaSynopsisAndTagsCardView(detail: longSynopsisDetail),
            width: 390,
            height: 500
        )
        XCTAssertTrue(longSynopsisRendered.contains(showMoreLabel), "Multi-paragraph > 4 line synopsis must render expand toggle")
    }

    func testWebtoonViewportSteppingZoomEdgesAndDoubleSlotBoundaryInwardGuard() async throws {
        let nextChapter = UiMangaChapter(id: "ch-2", title: "Chapter 2", subtitle: nil, uploadedAt: nil, locked: false, url: nil)
        let prevChapter = UiMangaChapter(id: "ch-0", title: "Chapter 0", subtitle: nil, uploadedAt: nil, locked: false, url: nil)
        let tallSample = makeSamplePages(count: 1)[0]
        let tallPages = [
            ReaderPageState(
                id: "tall-0",
                index: 0,
                status: tallSample.status,
                aspectRatio: KotlinFloat(value: 0.2)
            ),
        ]
        let webtoonState = FakeMangaReaderState(
            direction: .webtoon,
            pages: tallPages,
            slots: [ReaderSlotSingle(pageIndex: 0)],
            nextBoundary: ChapterBoundaryUnlocked(chapter: nextChapter, startFromEnd: false)
        )
        let controller = MangaReaderInteractionController()
        let hostBox = ReaderTestHostModel(state: webtoonState, controller: controller, deferSnapshotDelivery: true)

        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 600, height: 800)
        window.rootViewController = UIHostingController(rootView: ReaderTestHostView(model: hostBox))
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKey()
        }

        try await eventually(window: window) {
            controller.isBound && hostBox.pageFrames[0] != nil
        }

        // 1. Tall strip (aspectRatio 0.2 -> 600x3000 in 600x800 viewport): right tap scrolls ~80% viewport (640pt) without advancing chapter
        controller.handleTap(at: CGPoint(x: 520, y: 400), in: CGSize(width: 600, height: 800))
        try await eventually(window: window) {
            guard let frame = hostBox.pageFrames[0] else { return false }
            return abs(frame.minY + 640) <= 10
        }
        XCTAssertNil(webtoonState.openedBoundaryChapterId)

        // Left tap scrolls back up ~80% viewport to y == 0
        controller.handleTap(at: CGPoint(x: 80, y: 400), in: CGSize(width: 600, height: 800))
        try await eventually(window: window) {
            guard let frame = hostBox.pageFrames[0] else { return false }
            return abs(frame.minY) <= 10
        }

        // 2. Webtoon double-tap zoom (1.0x -> 2.5x), top/bottom edge reachability, and Escape reset
        controller.handleDoubleTap(at: CGPoint(x: 300, y: 400))
        try await eventually(window: window) {
            controller.zoomResetHandler != nil && abs(controller.activeZoomScale - 2.5) <= 0.05
        }
        // Pan downward to reach very top edge (offsetY == +maxY == +600 -> page 0 minY == 0)
        controller.panWebtoonZoomForTesting(CGSize(width: 0, height: 900))
        try await eventually(window: window) {
            guard let frame = hostBox.pageFrames[0] else { return false }
            return abs(controller.activeZoomOffset.height - 600) <= 2.0 && abs(frame.minY) <= 4.0
        }

        // Pan upward all the way to the very bottom edge (offsetY == -maxY == -600)
        for _ in 0..<12 {
            controller.panWebtoonZoomForTesting(CGSize(width: 0, height: -900))
        }
        try await eventually(window: window) {
            abs(controller.activeZoomOffset.height + 600) <= 2.0
        }

        // Escape resets Webtoon zoom back to 1.0x
        XCTAssertTrue(controller.handleKey(.escape))
        try await eventually(window: window) {
            controller.zoomResetHandler == nil && abs(controller.activeZoomScale - 1.0) <= 0.01
        }

        // 3. Rewind to top via Shift+Space, then step forward via Space from top -> >= 4 ~80% steps before opening Chapter 2
        var rewindSteps = 0
        while (hostBox.pageFrames[0]?.minY ?? 0) < -5 && rewindSteps < 10 {
            XCTAssertTrue(controller.handleKey(.space(shift: true)))
            window.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(15))
            rewindSteps += 1
        }
        XCTAssertNil(webtoonState.openedBoundaryChapterId)

        var forwardSteps = 0
        while webtoonState.openedBoundaryChapterId == nil && forwardSteps < 12 {
            XCTAssertTrue(controller.handleKey(.space(shift: false)))
            window.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(15))
            forwardSteps += 1
        }
        XCTAssertGreaterThanOrEqual(forwardSteps, 4)
        XCTAssertEqual(webtoonState.openedBoundaryChapterId, "ch-2")

        // 4. Stepping inward from prevBoundary / nextBoundary onto a Double spread preserves secondary currentPageIndex
        let spreadPages = makeSamplePages(count: 4)
        let spreadSlots: [any ReaderSlot] = [
            ReaderSlotDouble(leftPageIndex: 1, rightPageIndex: 0, primaryPageIndex: 0),
            ReaderSlotDouble(leftPageIndex: 3, rightPageIndex: 2, primaryPageIndex: 2),
        ]
        let spreadState = FakeMangaReaderState(
            direction: .ltr,
            pages: spreadPages,
            slots: spreadSlots,
            previousBoundary: ChapterBoundaryUnlocked(chapter: prevChapter, startFromEnd: true),
            nextBoundary: ChapterBoundaryUnlocked(chapter: nextChapter, startFromEnd: false)
        )
        spreadState.goToPage(pageIndex: 3) // Secondary page of last Double slot (2, 3)
        hostBox.setState(spreadState)
        try await eventually(window: window) {
            controller.activePagerPage == 2 && hostBox.snapshot.currentPageIndex == 3
        }
        controller.scrollToBoundary(isNext: true)
        XCTAssertTrue(controller.isOnNextBoundaryPage)
        XCTAssertTrue(controller.handleKey(.leftArrow))
        try await eventually(window: window) {
            !controller.isOnNextBoundaryPage && controller.activePagerPage == 2
        }
        XCTAssertEqual(spreadState.currentPageIndex, 3, "Stepping inward onto Double slot must preserve secondary page index 3")
    }

    func testWebtoonReportsThePageAtTheViewportCenterAndTheShortFinalPageAtTheEnd() async throws {
        let sample = makeSamplePages(count: 1)[0]
        let ratios: [Float] = [3, 0.02, 6]
        let pages = ratios.enumerated().map { index, ratio in
            ReaderPageState(id: "strip-\(index)", index: Int32(index), status: sample.status,
                            aspectRatio: KotlinFloat(value: ratio))
        }
        let state = FakeMangaReaderState(direction: .webtoon, pages: pages,
                                         slots: pages.map { ReaderSlotSingle(pageIndex: $0.index) })
        let model = ReaderTestHostModel(state: state, controller: MangaReaderInteractionController())
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 600, height: 800)
        window.rootViewController = UIHostingController(rootView: ReaderTestHostView(model: model))
        window.isHidden = false
        defer {
            window.isHidden = true
            window.rootViewController = nil
        }

        try await eventually(window: window) { model.pageFrames[1] != nil }
        let tall = try XCTUnwrap(model.pageFrames[1])
        XCTAssertEqual(tall.height, tall.width / CGFloat(ratios[1]), accuracy: 2)
        XCTAssertEqual(state.currentPageIndex, 0)
        let scroll = try XCTUnwrap(descendants(window).compactMap { $0 as? UIScrollView }
            .first { $0.contentSize.height > $0.bounds.height + 1 })
        XCTAssertEqual(scroll.contentSize.height, ratios.reduce(0) { $0 + tall.width / CGFloat($1) }, accuracy: 2)
        XCTAssertNil(model.pageFrames[2], "The distant final page should remain lazy")
        scroll.setContentOffset(CGPoint(x: 0, y: 100), animated: false)
        try await eventually(window: window) {
            state.currentPageIndex == 1 && (model.pageFrames[0]?.maxY ?? 0) > 0
        }
        state.goToPage(pageIndex: 2)
        try await eventually(window: window) {
            guard let last = model.pageFrames[2] else { return false }
            return abs(last.maxY - scroll.bounds.height) < 2 && state.currentPageIndex == 2
        }
        let end = scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom
        scroll.setContentOffset(CGPoint(x: 0, y: end - 40), animated: false)
        try await eventually(window: window) {
            state.currentPageIndex == 1 && (model.pageFrames[2]?.minY ?? .infinity) < scroll.bounds.height
        }
        scroll.setContentOffset(CGPoint(x: 0, y: end), animated: false)
        try await eventually(window: window) { state.currentPageIndex == 2 }

        let next = UiMangaChapter(id: "next", title: "Next", subtitle: nil, uploadedAt: nil, locked: false, url: nil)
        state.nextBoundary = ChapterBoundaryUnlocked(chapter: next, startFromEnd: false)
        state.onStateMutated?()
        try await eventually(window: window) { scroll.contentSize.height - scroll.bounds.height > end + 40 }
        scroll.setContentOffset(CGPoint(x: 0, y: end + 10), animated: false)
        try await eventually(window: window) {
            guard let last = model.pageFrames[2] else { return false }
            return last.maxY < scroll.bounds.height && last.minY > scroll.bounds.height / 2 && state.currentPageIndex == 1
        }
        let chapterEnd = scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom
        scroll.setContentOffset(CGPoint(x: 0, y: chapterEnd), animated: false)
        try await eventually(window: window) { state.currentPageIndex == 2 }

        state.activeChapterId = "resumed"
        state.previousBoundary = ChapterBoundaryUnlocked(chapter: next, startFromEnd: true)
        state.goToPage(pageIndex: 1)
        try await eventually(window: window) {
            guard let tall = model.pageFrames[1] else { return false }
            return abs(tall.minY) < 2 && state.currentPageIndex == 1
        }
    }

    func testReaderReleasesChaptersAndUnbindsHandlersOnDisappear() async throws {
        let controller = MangaReaderInteractionController()
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        let host = UIHostingController(rootView: AnyView(EmptyView()))
        window.rootViewController = host
        window.isHidden = false
        defer {
            window.isHidden = true
            window.rootViewController = nil
        }
        func show(_ chapter: String, direction: MangaReadingDirection) -> FakeMangaReaderState {
            let state = FakeMangaReaderState(direction: direction, pages: makeSamplePages(count: 2),
                                             slots: [ReaderSlotSingle(pageIndex: 0), ReaderSlotSingle(pageIndex: 1)])
            state.activeChapterId = chapter
            host.rootView = AnyView(MangaReaderCanvas(state: state, hudVisible: false, onToggleHud: {}, controller: controller))
            return state
        }

        weak let first = show("first", direction: .rtl)
        try await eventually(window: window) { controller.isBound && controller.activePagerPage == 0 }
        weak let second = show("second", direction: .ltr)
        try await eventually(window: window) { first == nil && controller.activePagerPage == 0 }
        XCTAssertTrue(controller.handleKey(.rightArrow))
        XCTAssertEqual(second?.currentPageIndex, 1)
        weak let strip = show("strip", direction: .webtoon)
        try await eventually(window: window) { second == nil && controller.webtoonStepForward != nil }
        weak let nextStrip = show("next-strip", direction: .webtoon)
        try await eventually(window: window) { strip == nil && controller.doubleTapZoomHandler != nil }
        controller.handleDoubleTap(at: CGPoint(x: 100, y: 100))
        try await eventually(window: window) { controller.zoomResetHandler != nil }
        host.rootView = AnyView(EmptyView())
        try await eventually(window: window) { nextStrip == nil && !controller.isBound }
        XCTAssertFalse(controller.handleKey(.space(shift: false)))
        XCTAssertNil(controller.zoomResetHandler)
        XCTAssertNil(controller.doubleTapZoomHandler)
    }

    func testIOSCompactTabsEmptyChaptersAndIsolatedHeroButtons() async throws {
        let mangaKey = MicroBlogKey(id: "manga-compact-1", host: "manga.example")
        let guestAccount = AccountType.GuestPlugin(platformId: "manga-plat", host: "manga.example")
        let detail = UiMangaDetail(
            pluginId: "dev.example.manga",
            platformId: "manga-plat",
            mangaKey: mangaKey,
            accountType: guestAccount,
            title: "Compact Starlight",
            altTitles: ["紧凑星光"],
            cover: nil,
            author: nil,
            authors: ["Aria Lin"],
            artists: ["Kuro"],
            status: .ongoing,
            content: makeRichSynopsis(["   "]),
            url: "https://manga.example/series/compact-1",
            updatedAt: nil,
            readingDirection: .rtl,
            tagGroups: [],
            chapterGroups: [],
            isBookmarked: false,
            bookmarkAction: ClickEventNoop.shared,
            actions: [],
            sourceLanguages: ["en"]
        )

        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKey()
        }

        // 1. Compact mode (390x844) with chapters: [], supportsComments: true, supportsRecommendations: true
        //    must report both heroCard and chaptersSection (heroCard.maxY < chaptersSection.maxY) AND mount UISegmentedControl
        var compactFrames = MangaDetailPaneFrames()
        var refreshCount = 0
        func makeCompactRoot(
            detailValue: UiMangaDetail = detail,
            primaryTarget: PrimaryReadTarget? = nil,
            supportsComments: Bool,
            supportsRecommendations: Bool,
            onNavigate: @escaping (Route) -> Void = { _ in }
        ) -> some View {
            MangaDetailContentView(
                detail: UiStateSuccess(data: detailValue),
                chapters: [],
                primaryReadTarget: primaryTarget,
                supportsComments: supportsComments,
                supportsRecommendations: supportsRecommendations,
                onRefresh: { refreshCount += 1 },
                onNavigate: onNavigate
            )
            .environment(\.horizontalSizeClass, .compact)
            .onPreferenceChange(MangaDetailPaneFramesPreferenceKey.self) { compactFrames = $0 }
        }

        let compactWithTabsHost = UIHostingController(
            rootView: makeCompactRoot(supportsComments: true, supportsRecommendations: true)
        )
        window.rootViewController = compactWithTabsHost
        window.makeKeyAndVisible()

        try await eventually(window: window) {
            compactFrames.heroCard != nil &&
                compactFrames.chaptersSection != nil &&
                self.countSubviews(ofType: UISegmentedControl.self, in: compactWithTabsHost.view) == 1
        }
        let heroFrame = try XCTUnwrap(compactFrames.heroCard)
        let chaptersFrame = try XCTUnwrap(compactFrames.chaptersSection)
        XCTAssertLessThan(
            heroFrame.maxY,
            chaptersFrame.maxY,
            "Expected heroCard (\(heroFrame)) above chaptersSection (\(chaptersFrame)) even when chapters is empty"
        )
        let segmented = try XCTUnwrap(descendants(compactWithTabsHost.view).compactMap { $0 as? UISegmentedControl }.first)
        XCTAssertEqual(segmented.numberOfSegments, 3)

        func assertNoOuterSegmentedBackground(for control: UISegmentedControl, contextLabel: String) throws {
            let headerContainer = try XCTUnwrap(
                sequence(first: control.superview, next: \.?.superview)
                    .compactMap({ $0 })
                    .first(where: { $0 is UICollectionReusableView })
            )
            func allLayers(in layer: CALayer) -> [CALayer] {
                [layer] + (layer.sublayers ?? []).flatMap(allLayers)
            }
            let segmentedLayers = Set(allLayers(in: control.layer).map(ObjectIdentifier.init))
            let ancestorLayers = Set(
                sequence(first: control.layer.superlayer, next: \.?.superlayer)
                    .compactMap { $0 }
                    .map(ObjectIdentifier.init)
            )
            let externalBackingLayers = allLayers(in: headerContainer.layer).filter { layer in
                let id = ObjectIdentifier(layer)
                guard !segmentedLayers.contains(id), !ancestorLayers.contains(id), !layer.isHidden, layer.opacity > 0.01 else {
                    return false
                }
                let typeName = String(describing: type(of: layer))
                let hasFill = (layer.backgroundColor?.alpha ?? 0) > 0.01
                let isBackdrop = typeName.localizedCaseInsensitiveContains("backdrop") || typeName.localizedCaseInsensitiveContains("material")
                return hasFill || isBackdrop
            }
            let externalVisualEffects = descendants(headerContainer)
                .compactMap { $0 as? UIVisualEffectView }
                .filter { !$0.isDescendant(of: control) && !$0.isHidden && $0.alpha > 0.01 && $0.effect != nil }
            if let headerCell = headerContainer as? UICollectionViewCell {
                XCTAssertNil(
                    headerCell.backgroundConfiguration?.visualEffect,
                    "Tab switcher (\(contextLabel)) header cell must not carry a visualEffect backgroundConfiguration"
                )
                let configAlpha = headerCell.backgroundConfiguration?.backgroundColor?.cgColor.alpha ?? 0
                XCTAssertLessThanOrEqual(
                    configAlpha,
                    0.01,
                    "Tab switcher (\(contextLabel)) header cell must not carry an opaque backgroundColor configuration"
                )
            }
            XCTAssertTrue(
                externalBackingLayers.isEmpty && externalVisualEffects.isEmpty,
                "Tab switcher (\(contextLabel)) must not render an outer background around UISegmentedControl; layers=\(externalBackingLayers) effects=\(externalVisualEffects)"
            )
        }
        try assertNoOuterSegmentedBackground(for: segmented, contextLabel: "at rest")

        // Blank UiRichText synopsis with empty tagGroups must render zero height in MangaSynopsisAndTagsCardView
        let blankSynopsisHost = UIHostingController(rootView: MangaSynopsisAndTagsCardView(detail: detail))
        let blankSynopsisSize = blankSynopsisHost.sizeThatFits(in: CGSize(width: 390, height: 600))
        XCTAssertEqual(blankSynopsisSize.height, 0, accuracy: 1.0, "Blank UiRichText with empty tagGroups must not render an empty card")

        // Dynamic availableTabs shrinking: 3 -> 2 resets removed Recommendations tab (index 2) to Chapters (index 0),
        // while preserving Comments (index 1) when still present
        segmented.selectedSegmentIndex = 2
        segmented.sendActions(for: .valueChanged)
        try await eventually(window: window) { segmented.selectedSegmentIndex == 2 }

        compactWithTabsHost.rootView = makeCompactRoot(supportsComments: true, supportsRecommendations: false)
        try await eventually(window: window) {
            guard let control = self.descendants(compactWithTabsHost.view).compactMap({ $0 as? UISegmentedControl }).first else {
                return false
            }
            return control.numberOfSegments == 2 && control.selectedSegmentIndex == 0
        }

        compactWithTabsHost.rootView = makeCompactRoot(supportsComments: true, supportsRecommendations: true)
        try await eventually(window: window) {
            self.descendants(compactWithTabsHost.view).compactMap({ $0 as? UISegmentedControl }).first?.numberOfSegments == 3
        }
        let threeTabControl = try XCTUnwrap(descendants(compactWithTabsHost.view).compactMap { $0 as? UISegmentedControl }.first)
        threeTabControl.selectedSegmentIndex = 1
        threeTabControl.sendActions(for: .valueChanged)
        try await eventually(window: window) { threeTabControl.selectedSegmentIndex == 1 }

        compactWithTabsHost.rootView = makeCompactRoot(supportsComments: true, supportsRecommendations: false)
        try await eventually(window: window) {
            guard let control = self.descendants(compactWithTabsHost.view).compactMap({ $0 as? UISegmentedControl }).first else {
                return false
            }
            return control.numberOfSegments == 2 && control.selectedSegmentIndex == 1
        }

        // 2. Compact mode when both supportsComments and supportsRecommendations are false must omit UISegmentedControl
        compactWithTabsHost.rootView = makeCompactRoot(supportsComments: false, supportsRecommendations: false)
        try await eventually(window: window) {
            self.visibleSubviews(ofType: UISegmentedControl.self, in: compactWithTabsHost.view).isEmpty
        }
        let freshSingleTabHost = UIHostingController(
            rootView: makeCompactRoot(supportsComments: false, supportsRecommendations: false)
        )
        window.rootViewController = freshSingleTabHost
        window.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(50))
        window.layoutIfNeeded()
        XCTAssertEqual(
            countSubviews(ofType: UISegmentedControl.self, in: freshSingleTabHost.view),
            0,
            "UISegmentedControl must not be mounted when only the Chapters tab is available"
        )
        XCTAssertEqual(refreshCount, 0)

        // 3. Hero controls inside List row use explicit button styles so List cell selection never merges cover + Read CTA taps
        let sampleCover = try XCTUnwrap((makeSamplePages(count: 1)[0].status as? PageMediaStatusReady)?.original as? UiMediaImage)
        let detailWithCover = UiMangaDetail(
            pluginId: detail.pluginId,
            platformId: detail.platformId,
            mangaKey: detail.mangaKey,
            accountType: detail.accountType,
            title: detail.title,
            altTitles: detail.altTitles,
            cover: sampleCover,
            author: nil,
            authors: detail.authors,
            artists: detail.artists,
            status: detail.status,
            content: detail.content,
            url: detail.url,
            updatedAt: nil,
            readingDirection: .rtl,
            tagGroups: [],
            chapterGroups: [],
            isBookmarked: false,
            bookmarkAction: ClickEventNoop.shared,
            actions: [],
            sourceLanguages: ["en"]
        )
        let readTarget = PrimaryReadTarget(
            chapterId: "ch-1",
            chapterTitle: "Chapter 1",
            initialPage: 0,
            isContinue: false,
            isSingleChapterWork: false
        )
        var navigatedRoutes: [Route] = []
        let isolatedHeroHost = UIHostingController(
            rootView: makeCompactRoot(
                detailValue: detailWithCover,
                primaryTarget: readTarget,
                supportsComments: false,
                supportsRecommendations: false,
                onNavigate: { navigatedRoutes.append($0) }
            )
        )
        window.rootViewController = isolatedHeroHost
        try await eventually(window: window) {
            self.descendants(isolatedHeroHost.view).contains { $0 is UICollectionView }
        }
        let listCollectionView = try XCTUnwrap(
            descendants(isolatedHeroHost.view).compactMap { $0 as? UICollectionView }.first
        )
        XCTAssertNotNil(listCollectionView.refreshControl, "Compact List must wire pull-to-refresh via .refreshable")
        XCTAssertGreaterThan(listCollectionView.numberOfSections, 0)
        XCTAssertGreaterThan(listCollectionView.numberOfItems(inSection: 0), 0)
        let heroIndexPath = IndexPath(item: 0, section: 0)
        listCollectionView.delegate?.collectionView?(listCollectionView, didSelectItemAt: heroIndexPath)
        XCTAssertTrue(
            navigatedRoutes.isEmpty,
            "Selecting the hero List row must not trigger merged cover or Read CTA actions (\(navigatedRoutes))"
        )

        // 4. Compact NavigationStack backdrop extends behind transparent nav bar and scroll-up prefetch does not flicker title
        let manyChapterItems: [UiMangaChapterItem] = (1...24).map { idx in
            UiMangaChapterItem(
                chapter: UiMangaChapter(
                    id: "ch-\(idx)",
                    title: "Chapter \(idx): Starlight Voyage",
                    subtitle: "24p",
                    uploadedAt: nil,
                    locked: false,
                    url: nil
                ),
                isRead: false,
                isCurrent: idx == 1,
                lastPageIndex: 0,
                pageCount: 24
            )
        }
        let navModel = CompactNavPathModel()
        var navHeroFrames = MangaDetailPaneFrames()
        var navBackdropFrame: CGRect?
        var navHeroVisible: Bool?
        var isDummyDestinationMounted = false
        let navHost = UIHostingController(
            rootView: CompactNavTestHost(
                model: navModel,
                detail: detailWithCover,
                chapters: manyChapterItems,
                readTarget: readTarget,
                onHeroFrames: { navHeroFrames = $0 },
                onBackdropFrame: { navBackdropFrame = $0 },
                onHeroVisible: { navHeroVisible = $0 },
                onDummyDestinationLifecycle: { isDummyDestinationMounted = $0 }
            )
        )
        window.rootViewController = navHost
        window.layoutIfNeeded()

        try await eventually(window: window) {
            guard let hero = navHeroFrames.heroCard,
                  let backdrop = navBackdropFrame else {
                return false
            }
            return hero.minY >= 8 && backdrop.height > hero.height && navHeroVisible == true
        }
        let initialHero = try XCTUnwrap(navHeroFrames.heroCard)
        let initialBackdrop = try XCTUnwrap(navBackdropFrame)
        XCTAssertLessThanOrEqual(
            initialBackdrop.minY,
            1.0,
            "Expected compact blurred backdrop (\(initialBackdrop)) to extend behind top navigation bar"
        )
        XCTAssertGreaterThan(
            initialHero.minY,
            initialBackdrop.minY + 5.0,
            "Expected hero row (\(initialHero)) to sit below top safe area while backdrop starts at top"
        )
        XCTAssertEqual(
            initialBackdrop.maxY,
            initialHero.maxY,
            accuracy: 2.0,
            "Expected compact blurred backdrop bottom (\(initialBackdrop.maxY)) to match hero row bottom (\(initialHero.maxY))"
        )
        XCTAssertEqual(navHeroVisible, true)

        // Push dummy destination onto NavigationStack at scroll offset 0, then pop back:
        // blurred cover backdrop and isHeroVisible == true must be preserved (no lost backdrop or duplicate inline title)
        navModel.path = [1, 2]
        try await eventually(window: window) {
            isDummyDestinationMounted
        }
        try await Task.sleep(for: .milliseconds(40))
        window.layoutIfNeeded()

        navModel.path = [1]
        try await eventually(window: window) {
            !isDummyDestinationMounted
        }
        try await Task.sleep(for: .milliseconds(60))
        window.layoutIfNeeded()

        XCTAssertEqual(
            navHeroVisible,
            true,
            "Popping back to MangaDetailContentView at scroll offset 0 must keep isHeroVisible == true"
        )
        let poppedBackdrop = try XCTUnwrap(
            navBackdropFrame,
            "Popping back to MangaDetailContentView at scroll offset 0 must preserve manga_compact_hero_backdrop"
        )
        XCTAssertGreaterThan(poppedBackdrop.height, initialHero.height)
        XCTAssertEqual(poppedBackdrop.maxY, initialHero.maxY, accuracy: 2.0)

        let navListCollection = try XCTUnwrap(
            descendants(navHost.view).compactMap { $0 as? UICollectionView }.first
        )
        // Step-scroll down so UICollectionView materializes chapter rows and hero row leaves viewport -> isHeroVisible becomes false
        for offsetY in stride(from: CGFloat(180), through: 720, by: 180) {
            navListCollection.setContentOffset(CGPoint(x: 0, y: offsetY), animated: false)
            window.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(20))
        }
        try await eventually(window: window) {
            navHeroVisible == false
        }
        let pinnedControl = try XCTUnwrap(
            descendants(navHost.view).compactMap { $0 as? UISegmentedControl }.first
        )
        try assertNoOuterSegmentedBackground(for: pinnedControl, contextLabel: "pinned while scrolled")

        // Scroll back up so cell 0 is remounted with remainingHero == 16 (< 40 expand hysteresis) -> must NOT flicker isHeroVisible back to true
        let scrolledPastForPartial = initialHero.height - 16
        let partialOffset = scrolledPastForPartial - navListCollection.adjustedContentInset.top
        navListCollection.setContentOffset(CGPoint(x: 0, y: partialOffset), animated: false)
        window.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(50))
        window.layoutIfNeeded()
        XCTAssertEqual(
            navHeroVisible,
            false,
            "Remounting hero cell while remainingHero (16pt) < 40pt expand threshold must not flicker isHeroVisible back to true"
        )
        if let midHero = navHeroFrames.heroCard, let midBackdrop = navBackdropFrame, midHero.maxY > 5 {
            XCTAssertEqual(midBackdrop.maxY, midHero.maxY, accuracy: 2.5)
        }

        // Scroll back to top -> transparent hero state restored
        navListCollection.setContentOffset(
            CGPoint(x: 0, y: -navListCollection.adjustedContentInset.top),
            animated: false
        )
        try await eventually(window: window) {
            navHeroVisible == true
        }
    }

    func testIOSReaderChapterSheetMoreMenuAndSpreadPageActions() async throws {
        let ch1 = UiMangaChapter(id: "ch-1", title: "Chapter 1: Dawn", subtitle: "Vol. 1", uploadedAt: nil, locked: false, url: "https://manga.example/ch/1")
        let ch2 = UiMangaChapter(id: "ch-2", title: "Chapter 2: Horizon", subtitle: "Vol. 1", uploadedAt: nil, locked: false, url: "https://manga.example/ch/2")
        let ch3Locked = UiMangaChapter(id: "ch-3", title: "Chapter 3: VIP", subtitle: nil, uploadedAt: nil, locked: true, url: "https://manga.example/ch/3")

        let pages = makeSamplePages(count: 4)
        let spreadSlots: [any ReaderSlot] = [
            ReaderSlotDouble(leftPageIndex: 1, rightPageIndex: 0, primaryPageIndex: 0),
            ReaderSlotDouble(leftPageIndex: 3, rightPageIndex: 2, primaryPageIndex: 2),
        ]
        let fakeState = FakeMangaReaderState(
            direction: .rtl,
            pages: pages,
            slots: spreadSlots
        )
        fakeState.chapterGroups = [
            UiMangaChapterGroup(id: "main", title: UiTextRaw(string: "Main Story"), chapters: [ch1, ch2, ch3Locked]),
            UiMangaChapterGroup(id: "extra", title: UiTextRaw(string: "Extras"), chapters: [ch1]),
        ]
        fakeState.selectedChapterGroupId = "main"
        fakeState.chapterSortDescending = true
        fakeState.chapterItems = [
            ReaderChapterItem(chapter: ch3Locked, isCurrent: false, isRead: false, lastPageIndex: 0),
            ReaderChapterItem(chapter: ch2, isCurrent: false, isRead: false, lastPageIndex: 0),
            ReaderChapterItem(chapter: ch1, isCurrent: true, isRead: true, lastPageIndex: 3),
        ]

        let controller = MangaReaderInteractionController()
        let hostModel = ReaderTestHostModel(state: fakeState, controller: controller)

        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 800, height: 600)
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKey()
        }

        // 1. Streamlined topHudBar: chapters switcher + settings + overflow Menu (no loose download/safari buttons),
        //    while IOSMangaReaderShareButtonVisiblePreferenceKey reports true for Image/Gif and false for Ugoira.
        var shareVisibleForImage = false
        var savedPageIndices: [Int] = []
        var sharedPageIndices: [Int] = []
        let readerHost = UIHostingController(
            rootView: MangaReaderContentView(
                state: hostModel.snapshot,
                hudVisible: .constant(true),
                goBack: {},
                controller: controller,
                onSavePage: { _, _, pageIdx in savedPageIndices.append(pageIdx) },
                onSharePage: { _, _, pageIdx in sharedPageIndices.append(pageIdx) }
            )
            .onPreferenceChange(IOSMangaReaderShareButtonVisiblePreferenceKey.self) { shareVisibleForImage = $0 }
        )
        window.rootViewController = readerHost
        window.makeKeyAndVisible()

        try await eventually(window: window) {
            controller.isBound &&
                shareVisibleForImage &&
                self.visibleSubviews(ofType: UIButton.self, in: readerHost.view).contains(where: \.showsMenuAsPrimaryAction)
        }

        let topBarButtons = visibleSubviews(ofType: UIButton.self, in: readerHost.view).filter { button in
            button.convert(button.bounds, to: nil).minY <= 120
        }
        let looseNonMenuButtons = topBarButtons.filter { !$0.showsMenuAsPrimaryAction }
        XCTAssertTrue(
            looseNonMenuButtons.isEmpty,
            "Top HUD must not mount loose non-Menu UIButtons for safari/download: \(looseNonMenuButtons)"
        )
        let moreMenuButton = try XCTUnwrap(topBarButtons.first(where: \.showsMenuAsPrimaryAction))
        XCTAssertTrue(moreMenuButton.showsMenuAsPrimaryAction)
        if let menu = moreMenuButton.menu {
            let actionTitles = menu.children.compactMap { ($0 as? UIAction)?.title }
            if !actionTitles.isEmpty {
                XCTAssertTrue(actionTitles.contains { $0.contains("(P2)") }, "Expected left spread page action in Menu: \(actionTitles)")
                XCTAssertTrue(actionTitles.contains { $0.contains("(P1)") }, "Expected right spread page action in Menu: \(actionTitles)")
            }
        }
        let spreadTargets = MangaReaderContentView.resolveVisibleReadyPageTargets(state: hostModel.snapshot)
        XCTAssertEqual(spreadTargets.map(\.pageIndex), [1, 0])

        // Verify Home, End, PageDown, and PageUp keyboard shortcuts
        XCTAssertTrue(controller.handleKey(.end))
        XCTAssertEqual(fakeState.currentPageIndex, 3)
        XCTAssertTrue(controller.handleKey(.home))
        XCTAssertEqual(fakeState.currentPageIndex, 0)
        XCTAssertTrue(controller.handleKey(.pageDown))
        XCTAssertEqual(fakeState.currentSlotIndex, 1)
        XCTAssertTrue(controller.handleKey(.pageUp))
        XCTAssertEqual(fakeState.currentSlotIndex, 0)

        // Verify iOS overlay Escape/key priority without triggering UIKit modal sheet transition race
        var iosBackCount = 0
        let overlayController = MangaReaderInteractionController()
        @MainActor
        struct IOSOverlayCanvasHost: View {
            @Bindable var model: ReaderTestHostModel
            let controller: MangaReaderInteractionController
            let onBack: () -> Void
            var body: some View {
                MangaReaderCanvas(
                    state: model.snapshot,
                    hudVisible: true,
                    onToggleHud: {},
                    onBack: onBack,
                    isOverlayPresented: model.isOverlayPresented,
                    onDismissOverlay: {
                        guard model.isOverlayPresented else { return false }
                        model.isOverlayPresented = false
                        return true
                    },
                    controller: controller
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        window.rootViewController = UIHostingController(
            rootView: IOSOverlayCanvasHost(
                model: hostModel,
                controller: overlayController,
                onBack: { iosBackCount += 1 }
            )
        )
        try await eventually(window: window) { overlayController.isBound && overlayController.doubleTapZoomHandler != nil }
        overlayController.handleDoubleTap(at: CGPoint(x: 400, y: 300))
        try await eventually(window: window) { overlayController.zoomResetHandler != nil }
        hostModel.isOverlayPresented = true
        try await eventually(window: window) { overlayController.isOverlayPresented }
        XCTAssertFalse(overlayController.handleKey(.leftArrow))
        XCTAssertFalse(overlayController.handleKey(.end))
        XCTAssertEqual(fakeState.currentPageIndex, 0)
        XCTAssertTrue(overlayController.handleKey(.escape))
        try await eventually(window: window) { !hostModel.isOverlayPresented && !overlayController.isOverlayPresented }
        XCTAssertNotNil(overlayController.zoomResetHandler)
        XCTAssertEqual(iosBackCount, 0)
        XCTAssertTrue(overlayController.handleKey(.escape))
        try await eventually(window: window) { overlayController.zoomResetHandler == nil }
        XCTAssertEqual(iosBackCount, 0)
        XCTAssertTrue(overlayController.handleKey(.escape))
        XCTAssertEqual(iosBackCount, 1)

        // Verify real UIKit modal presentation & animated programmatic dismissal restores IOSReaderKeyCommandViewController.isFirstResponder,
        // and stacked modal/alert over open overlay (plus external modal when !isOverlayPresented) yields keyCommands == nil
        func findKeyCommandVC(in root: UIViewController?) -> IOSReaderKeyCommandViewController? {
            guard let root else { return nil }
            if let match = root as? IOSReaderKeyCommandViewController {
                return match
            }
            for child in root.children {
                if let match = findKeyCommandVC(in: child) {
                    return match
                }
            }
            return nil
        }

        final class ModalFocusTargetView: UIView {
            override var canBecomeFirstResponder: Bool { true }
        }

        final class FocusStealingModalViewController: UIViewController {
            let focusView = ModalFocusTargetView(frame: CGRect(x: 0, y: 0, width: 100, height: 44))
            override func viewDidLoad() {
                super.viewDidLoad()
                view.backgroundColor = .systemBackground
                view.addSubview(focusView)
            }
            override func viewDidAppear(_ animated: Bool) {
                super.viewDidAppear(animated)
                _ = focusView.becomeFirstResponder()
            }
        }

        let overlayRootVC = try XCTUnwrap(window.rootViewController)
        let keyCommandVC = try XCTUnwrap(findKeyCommandVC(in: overlayRootVC))
        try await eventually(window: window) {
            keyCommandVC.isFirstResponder && keyCommandVC.keyCommands?.count == 11
        }

        // Present modal sheet representing showChapters / showSettings while isOverlayPresented == true
        let modalSheetVC = FocusStealingModalViewController()
        modalSheetVC.modalPresentationStyle = .pageSheet
        hostModel.isOverlayPresented = true
        overlayRootVC.present(modalSheetVC, animated: false)
        try await eventually(window: window) {
            overlayController.isOverlayPresented &&
                keyCommandVC.presentedViewController === modalSheetVC &&
                modalSheetVC.focusView.isFirstResponder &&
                !keyCommandVC.isFirstResponder
        }
        XCTAssertEqual(keyCommandVC.keyCommands?.map(\.input), [UIKeyCommand.inputEscape])

        // Stacked modal/alert presented on top of open overlay must yield keyCommands == nil so system dismisses top alert first
        let stackedAlertVC = UIAlertController(title: "Locked Chapter", message: nil, preferredStyle: .alert)
        modalSheetVC.present(stackedAlertVC, animated: false)
        try await eventually(window: window) {
            modalSheetVC.presentedViewController === stackedAlertVC
        }
        XCTAssertNil(
            keyCommandVC.keyCommands,
            "Stacked modal/alert over open overlay must yield keyCommands == nil"
        )
        await withCheckedContinuation { continuation in
            stackedAlertVC.dismiss(animated: false) {
                continuation.resume()
            }
        }
        XCTAssertEqual(keyCommandVC.keyCommands?.map(\.input), [UIKeyCommand.inputEscape])

        // Programmatic overlay dismissal: flip isOverlayPresented = false synchronously BEFORE UIKit finishes animated dismissal
        hostModel.isOverlayPresented = false
        window.layoutIfNeeded()
        XCTAssertNotNil(
            keyCommandVC.presentedViewController,
            "UIKit modal is still presented when SwiftUI synchronously updates IOSReaderKeyCommandRepresentable"
        )
        XCTAssertFalse(
            keyCommandVC.isFirstResponder,
            "IOSReaderKeyCommandViewController must defer becomeFirstResponder until presentedViewController finishes dismissing"
        )
        modalSheetVC.dismiss(animated: true)
        try await eventually(window: window) {
            keyCommandVC.presentedViewController == nil &&
                keyCommandVC.isFirstResponder &&
                keyCommandVC.keyCommands?.count == 11
        }

        // Also verify fullScreen modal dismissal where UIKit detaches the underlying view until animated dismissal completes
        let fullScreenOverlayVC = FocusStealingModalViewController()
        fullScreenOverlayVC.modalPresentationStyle = .fullScreen
        hostModel.isOverlayPresented = true
        try await eventually(window: window) { overlayController.isOverlayPresented }
        overlayRootVC.present(fullScreenOverlayVC, animated: false)
        try await eventually(window: window) {
            keyCommandVC.presentedViewController === fullScreenOverlayVC &&
                !keyCommandVC.isFirstResponder
        }
        hostModel.isOverlayPresented = false
        fullScreenOverlayVC.dismiss(animated: true)
        try await eventually(window: window) {
            keyCommandVC.presentedViewController == nil &&
                !overlayController.isOverlayPresented &&
                keyCommandVC.isFirstResponder &&
                keyCommandVC.keyCommands?.count == 11
        }

        // External modal presented while !isOverlayPresented (e.g. UIActivityViewController, including on intermediate definesPresentationContext) yields keyCommands == nil
        let externalShareModalVC = FocusStealingModalViewController()
        externalShareModalVC.modalPresentationStyle = .pageSheet
        overlayRootVC.present(externalShareModalVC, animated: false)
        try await eventually(window: window) {
            keyCommandVC.presentedViewController === externalShareModalVC
        }
        XCTAssertNil(
            keyCommandVC.keyCommands,
            "External presented modal while !isOverlayPresented must yield keyCommands == nil"
        )
        await withCheckedContinuation { continuation in
            externalShareModalVC.dismiss(animated: false) {
                continuation.resume()
            }
        }
        try await eventually(window: window) { keyCommandVC.presentedViewController == nil }

        // Verify IOSMangaReaderShareButtonVisiblePreferenceKey still reports true for Gif and false for Ugoira
        let gifMedia = UiMediaGif(
            url: "https://manga.example/ch-1/p0.gif",
            previewUrl: "https://manga.example/ch-1/p0.gif",
            description: nil,
            height: 140,
            width: 100,
            customHeaders: nil,
            proxy: ProxyRef.Unbound.shared
        )
        let gifState = FakeMangaReaderState(
            direction: .rtl,
            pages: [
                ReaderPageState(
                    id: "gif-0",
                    index: 0,
                    status: PageMediaStatusReady(original: gifMedia, displayed: gifMedia),
                    aspectRatio: KotlinFloat(value: 0.71)
                ),
            ],
            slots: [ReaderSlotSingle(pageIndex: 0)]
        )
        var shareVisibleForGif = false
        window.rootViewController = UIHostingController(
            rootView: MangaReaderContentView(
                state: gifState,
                hudVisible: .constant(true),
                goBack: {},
                onSharePage: { _, _, _ in }
            )
            .onPreferenceChange(IOSMangaReaderShareButtonVisiblePreferenceKey.self) { shareVisibleForGif = $0 }
        )
        try await eventually(window: window) { shareVisibleForGif }

        let ugoiraAccount = MicroBlogKey(id: "u1", host: "pixiv.net")
        let ugoiraMedia = UiMediaUgoira(
            statusKey: MicroBlogKey(id: "s1", host: "pixiv.net"),
            accountKey: ugoiraAccount,
            previewUrl: "https://manga.example/ch-1/p0.png",
            originalFrameUrl: nil,
            description: nil,
            height: 140,
            width: 100,
            sensitive: false,
            customHeaders: nil,
            proxy: ProxyRef.companion.forAccount(accountKey: ugoiraAccount),
            sourceUrl: nil
        )
        let ugoiraState = FakeMangaReaderState(
            direction: .rtl,
            pages: [
                ReaderPageState(
                    id: "ugoira-0",
                    index: 0,
                    status: PageMediaStatusReady(original: ugoiraMedia, displayed: ugoiraMedia),
                    aspectRatio: KotlinFloat(value: 0.71)
                ),
            ],
            slots: [ReaderSlotSingle(pageIndex: 0)]
        )
        var shareVisibleForUgoira = true
        window.rootViewController = UIHostingController(
            rootView: MangaReaderContentView(
                state: ugoiraState,
                hudVisible: .constant(true),
                goBack: {},
                onSharePage: { _, _, _ in }
            )
            .onPreferenceChange(IOSMangaReaderShareButtonVisiblePreferenceKey.self) { shareVisibleForUgoira = $0 }
        )
        window.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(40))
        XCTAssertFalse(shareVisibleForUgoira)

        // 2. MangaReaderChapterListView renders chapters, toggles sort order, and selects unlocked chapter
        let descendingLabel = FlareAppleUILocalization.string("manga_detail_sort_descending", fallback: "Descending")
        let ascendingLabel = FlareAppleUILocalization.string("manga_detail_sort_ascending", fallback: "Ascending")
        let initialChapterPDF = try renderPDFText(
            MangaReaderChapterListView(state: hostModel.snapshot),
            width: 390,
            height: 520
        )
        XCTAssertTrue(initialChapterPDF.contains(descendingLabel), initialChapterPDF)
        XCTAssertTrue(initialChapterPDF.contains("Main Story"), initialChapterPDF)
        XCTAssertTrue(initialChapterPDF.contains("Extras"), initialChapterPDF)

        var chapterRowFrames: [String: CGRect] = [:]
        @MainActor
        struct ChapterListTestHost: View {
            @Bindable var model: ReaderTestHostModel
            let onFrames: ([String: CGRect]) -> Void

            var body: some View {
                MangaReaderChapterListView(state: model.snapshot)
                    .onPreferenceChange(MangaChapterFramesPreferenceKey.self, perform: onFrames)
            }
        }
        window.rootViewController = UIHostingController(
            rootView: ChapterListTestHost(model: hostModel) { chapterRowFrames = $0 }
        )
        try await eventually(window: window) {
            chapterRowFrames["ch-1"] != nil &&
                chapterRowFrames["ch-2"] != nil &&
                chapterRowFrames["ch-3"] != nil
        }
        XCTAssertLessThan(
            try XCTUnwrap(chapterRowFrames["ch-3"]).minY,
            try XCTUnwrap(chapterRowFrames["ch-1"]).minY,
            "Descending chapter list should place ch-3 above ch-1"
        )

        XCTAssertTrue(fakeState.chapterSortDescending)
        MangaReaderChapterListView.handleToggleSort(state: hostModel.snapshot)
        XCTAssertFalse(fakeState.chapterSortDescending)
        XCTAssertEqual(fakeState.toggleChapterSortCount, 1)

        try await eventually(window: window) {
            guard let ch1Rect = chapterRowFrames["ch-1"],
                  let ch3Rect = chapterRowFrames["ch-3"] else { return false }
            return ch1Rect.minY < ch3Rect.minY
        }

        let ascendingChapterPDF = try renderPDFText(
            MangaReaderChapterListView(state: hostModel.snapshot),
            width: 390,
            height: 520
        )
        XCTAssertTrue(ascendingChapterPDF.contains(ascendingLabel), ascendingChapterPDF)

        var dismissedChapterSheet = false
        let lockedPrompt = MangaReaderChapterListView.handleChapterTap(
            ReaderChapterItem(chapter: ch3Locked, isCurrent: false, isRead: false, lastPageIndex: 0),
            state: hostModel.snapshot,
            onDismiss: { dismissedChapterSheet = true }
        )
        XCTAssertEqual(lockedPrompt?.id, "ch-3")
        XCTAssertNil(fakeState.selectedChapterId)
        XCTAssertFalse(dismissedChapterSheet)

        let unlockedPrompt = MangaReaderChapterListView.handleChapterTap(
            ReaderChapterItem(chapter: ch2, isCurrent: false, isRead: false, lastPageIndex: 0),
            state: hostModel.snapshot,
            onDismiss: { dismissedChapterSheet = true }
        )
        XCTAssertNil(unlockedPrompt)
        XCTAssertEqual(fakeState.selectedChapterId, "ch-2")
        XCTAssertTrue(dismissedChapterSheet)
        _ = (savedPageIndices, sharedPageIndices)
    }

    func testIOSReaderChapterProgressAndNonPrimaryTranslationErrorResolution() throws {
        let ch1 = UiMangaChapter(id: "ch-1", title: "Chapter 1: Start", subtitle: "20p", uploadedAt: nil, locked: false, url: nil)
        let ch2 = UiMangaChapter(id: "ch-2", title: "Chapter 2: Resume", subtitle: "24p", uploadedAt: nil, locked: false, url: nil)
        let ch3 = UiMangaChapter(id: "ch-3", title: "Chapter 3: Finished", subtitle: "28p", uploadedAt: nil, locked: false, url: nil)

        let pages = makeSamplePages(count: 4)
        let spreadSlots: [any ReaderSlot] = [
            ReaderSlotDouble(leftPageIndex: 1, rightPageIndex: 0, primaryPageIndex: 0),
            ReaderSlotDouble(leftPageIndex: 3, rightPageIndex: 2, primaryPageIndex: 2),
        ]
        let fakeTranslation = StatefulIOSImageTranslationState()
        let fakeState = FakeMangaReaderState(
            direction: .rtl,
            pages: pages,
            slots: spreadSlots,
            imageTranslation: fakeTranslation
        )
        fakeState.chapterItems = [
            ReaderChapterItem(chapter: ch1, isCurrent: true, isRead: false, lastPageIndex: 0),
            ReaderChapterItem(chapter: ch2, isCurrent: false, isRead: false, lastPageIndex: 5),
            ReaderChapterItem(chapter: ch3, isCurrent: false, isRead: true, lastPageIndex: 11),
        ]

        let chapterListPDF = try renderPDFText(
            VStack(alignment: .leading, spacing: 4) {
                MangaReaderChapterListView(state: fakeState).chapterRows
            },
            width: 390,
            height: 480
        )
        let expectedResumeLabel = FlareAppleUILocalization.string(
            "manga_reader_page_number",
            fallback: "Page %lld",
            arguments: [Int64(6)]
        )
        let unexpectedZeroPageLabel = FlareAppleUILocalization.string(
            "manga_reader_page_number",
            fallback: "Page %lld",
            arguments: [Int64(1)]
        )
        let unexpectedReadPageLabel = FlareAppleUILocalization.string(
            "manga_reader_page_number",
            fallback: "Page %lld",
            arguments: [Int64(12)]
        )
        let normalizedChapterPDF = chapterListPDF
            .replacingOccurrences(of: "\u{2EDA}", with: "\u{9875}")
            .replacingOccurrences(of: "\u{2FB4}", with: "\u{9801}")
            .precomposedStringWithCompatibilityMapping
        XCTAssertTrue(
            normalizedChapterPDF.contains(expectedResumeLabel.precomposedStringWithCompatibilityMapping),
            chapterListPDF
        )
        XCTAssertFalse(
            normalizedChapterPDF.contains(unexpectedZeroPageLabel.precomposedStringWithCompatibilityMapping),
            chapterListPDF
        )
        XCTAssertFalse(
            normalizedChapterPDF.contains(unexpectedReadPageLabel.precomposedStringWithCompatibilityMapping),
            chapterListPDF
        )

        let page0Original = try XCTUnwrap((pages[0].status as? PageMediaStatusReady)?.original)
        let page1Original = try XCTUnwrap((pages[1].status as? PageMediaStatusReady)?.original)
        let page3Original = try XCTUnwrap((pages[3].status as? PageMediaStatusReady)?.original)

        // Default fallback returns currentReadyPage (page 0)
        XCTAssertEqual(
            MangaReaderContentView.resolveActiveTranslationErrorMedia(state: fakeState)?.url,
            page0Original.url
        )

        // Secondary spread page (page 1) translation error resolves to page 1 while currentPageIndex == 0
        fakeTranslation.itemsByURL[page1Original.url] = ImageTranslationItem(
            loading: false,
            translated: nil,
            showingTranslated: false,
            error: .authentication,
            previous: nil,
            previousWasShown: false,
            errorDetails: "ios-spread-secondary-error",
            errorDetailsVisible: false
        )
        XCTAssertEqual(
            MangaReaderContentView.resolveActiveTranslationErrorMedia(state: fakeState)?.url,
            page1Original.url
        )

        // Viewing error details (error == nil, errorDetailsVisible == true) keeps page 1 bound
        fakeTranslation.setErrorDetailsVisible(media: page1Original, visible: true)
        XCTAssertEqual(
            MangaReaderContentView.resolveActiveTranslationErrorMedia(state: fakeState)?.url,
            page1Original.url
        )
        fakeTranslation.setErrorDetailsVisible(media: page1Original, visible: false)

        // Scrolled Webtoon off-primary page (page 3 while currentPageIndex == 0) also resolves to page 3
        fakeState.setDirection(direction: .webtoon)
        fakeTranslation.itemsByURL[page3Original.url] = ImageTranslationItem(
            loading: false,
            translated: nil,
            showingTranslated: false,
            error: .network,
            previous: nil,
            previousWasShown: false,
            errorDetails: nil,
            errorDetailsVisible: false
        )
        XCTAssertEqual(
            MangaReaderContentView.resolveActiveTranslationErrorMedia(state: fakeState)?.url,
            page3Original.url
        )
        fakeTranslation.clearError(media: page3Original)
        XCTAssertEqual(
            MangaReaderContentView.resolveActiveTranslationErrorMedia(state: fakeState)?.url,
            page0Original.url
        )
    }

    func testIOSReaderBottomScrubberCapsuleAndWideViewportBounds() async throws {
        let pages = makeSamplePages(count: 20)
        let slots: [any ReaderSlot] = pages.indices.map { ReaderSlotSingle(pageIndex: Int32($0)) }
        let fakeState = FakeMangaReaderState(
            direction: .rtl,
            pages: pages,
            slots: slots,
            previousBoundary: ChapterBoundaryUnlocked(
                chapter: UiMangaChapter(id: "ch-prev", title: "Prev", subtitle: nil, uploadedAt: nil, locked: false, url: nil),
                startFromEnd: true
            ),
            nextBoundary: ChapterBoundaryUnlocked(
                chapter: UiMangaChapter(id: "ch-next", title: "Next", subtitle: nil, uploadedAt: nil, locked: false, url: nil),
                startFromEnd: false
            )
        )
        fakeState.mangaTitle = "Wide Viewport Manga Title"
        fakeState.chapterTitle = "Chapter 12"
        fakeState.goToPage(pageIndex: 5)
        let controller = MangaReaderInteractionController()
        let hostModel = ReaderTestHostModel(state: fakeState, controller: controller)

        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        let window = UIWindow(windowScene: scene)
        window.rootViewController = UIHostingController(rootView: ReaderTestHostView(model: hostModel))
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKey()
        }

        func assertScrubberLayout(size: CGSize) async throws {
            hostModel.scrubberLeadingLabelFrame = nil
            hostModel.scrubberTrailingLabelFrame = nil
            window.frame = CGRect(origin: .zero, size: size)
            window.layoutIfNeeded()

            try await eventually(window: window) {
                hostModel.scrubberLeadingLabelFrame != nil &&
                    hostModel.scrubberTrailingLabelFrame != nil &&
                    !self.visibleSubviews(ofType: UISlider.self, in: window).isEmpty
            }
            window.layoutIfNeeded()

            let slider: UISlider = try XCTUnwrap(visibleSubviews(ofType: UISlider.self, in: window).first)
            let sliderFrame = slider.convert(slider.bounds, to: window)
            let leadingFrame: CGRect = try XCTUnwrap(hostModel.scrubberLeadingLabelFrame)
            let trailingFrame: CGRect = try XCTUnwrap(hostModel.scrubberTrailingLabelFrame)

            let safeRect = window.bounds.inset(by: window.safeAreaInsets)
            let expectedCapsuleWidth = min(safeRect.width - 32, 640)
            let expectedCapsuleMinX = safeRect.minX + (safeRect.width - expectedCapsuleWidth) / 2
            let expectedCapsuleMaxX = expectedCapsuleMinX + expectedCapsuleWidth

            XCTAssertGreaterThan(sliderFrame.width, 100)
            XCTAssertLessThan(abs(leadingFrame.midY - sliderFrame.midY), 10)
            XCTAssertLessThan(abs(trailingFrame.midY - sliderFrame.midY), 10)

            // All scrubber subviews must remain strictly inside the 16pt-inset safe area and <=640pt centered capsule.
            XCTAssertGreaterThanOrEqual(sliderFrame.minX, expectedCapsuleMinX + 44)
            XCTAssertLessThanOrEqual(sliderFrame.maxX, expectedCapsuleMaxX - 44)
            XCTAssertGreaterThanOrEqual(trailingFrame.minX, expectedCapsuleMinX + 44)
            XCTAssertLessThanOrEqual(leadingFrame.maxX, expectedCapsuleMaxX - 44)
            XCTAssertGreaterThanOrEqual(min(trailingFrame.minX, leadingFrame.minX), safeRect.minX + 16)
            XCTAssertLessThanOrEqual(max(trailingFrame.maxX, leadingFrame.maxX), safeRect.maxX - 16)

            // Any enclosing glass/HUD bar container wrapping the slider must have exact clamped width and centered X.
            let enclosingBarFrames = descendants(window).compactMap { view -> CGRect? in
                let frame = view.convert(view.bounds, to: window)
                guard frame.height >= 44, frame.height <= 76, frame.contains(sliderFrame) else {
                    return nil
                }
                return frame
            }
            XCTAssertFalse(enclosingBarFrames.isEmpty, "Expected an enclosing bottom HUD capsule around UISlider")
            for barFrame in enclosingBarFrames {
                XCTAssertEqual(barFrame.width, expectedCapsuleWidth, accuracy: 2.0)
                XCTAssertEqual(barFrame.midX, safeRect.midX, accuracy: 2.0)
                XCTAssertGreaterThanOrEqual(barFrame.minX, safeRect.minX + 16 - 1.0)
                XCTAssertLessThanOrEqual(barFrame.maxX, safeRect.maxX - 16 + 1.0)
            }
        }

        try await assertScrubberLayout(size: CGSize(width: 393, height: 852))
        try await assertScrubberLayout(size: CGSize(width: 1024, height: 1366))
        try await assertScrubberLayout(size: CGSize(width: 1366, height: 1024))
    }

    func testIOSSessionChallengeSeedCookiesAndSnapshotFiltering() throws {
        let challengeURL = try XCTUnwrap(URL(string: "https://sub.challenge.example/verify?step=1"))
        let seeds = [
            SessionChallengeSeedCookie(name: "ipb_member_id", value: "42"),
            SessionChallengeSeedCookie(name: "ipb_pass_hash", value: "secret"),
        ]
        let cookies = sessionChallengeSeedCookies(seeds, challengeURL: challengeURL)
        XCTAssertEqual(cookies.map(\.name), ["ipb_member_id", "ipb_pass_hash"])
        for cookie in cookies {
            XCTAssertEqual(cookie.domain, "sub.challenge.example")
            XCTAssertFalse(cookie.domain.hasPrefix("."), "Seed cookies must be strict host-only without leading dot")
            XCTAssertEqual(cookie.path, "/")
            XCTAssertTrue(cookie.isSecure)
            XCTAssertTrue(cookie.isSessionOnly)
            XCTAssertNil(cookie.expiresDate)
        }

        let storage = HTTPCookieStorage.shared
        storage.removeCookies(since: .distantPast)
        defer { storage.removeCookies(since: .distantPast) }
        cookies.forEach { storage.setCookie($0) }

        XCTAssertEqual(Set((storage.cookies(for: URL(string: "https://sub.challenge.example/gallery/1")!) ?? []).map(\.name)), ["ipb_member_id", "ipb_pass_hash"])
        XCTAssertTrue((storage.cookies(for: URL(string: "http://sub.challenge.example/gallery/1")!) ?? []).isEmpty)
        XCTAssertTrue((storage.cookies(for: URL(string: "https://a.sub.challenge.example/gallery/1")!) ?? []).isEmpty)
        XCTAssertTrue((storage.cookies(for: URL(string: "https://other.challenge.example/gallery/1")!) ?? []).isEmpty)
        XCTAssertTrue((storage.cookies(for: URL(string: "https://challenge.example/gallery/1")!) ?? []).isEmpty)

        let clearance = try XCTUnwrap(HTTPCookie(properties: [
            .originURL: challengeURL,
            .path: "/",
            .name: "cf_clearance",
            .value: "clearance-1",
            .secure: "TRUE",
        ]))
        let undeclared = try XCTUnwrap(HTTPCookie(properties: [
            .originURL: challengeURL,
            .path: "/",
            .name: "undeclared",
            .value: "leak",
            .secure: "TRUE",
        ]))
        let probes = [
            SessionChallengeCookieProbe(
                url: "https://sub.challenge.example/verify",
                cookies: [SessionChallengeCookieRequirement(name: "cf_clearance", required: true)]
            ),
        ]
        let snapshot = sessionChallengeCookieSnapshot(
            cookies: cookies + [clearance, undeclared],
            probes: probes,
            userAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15"
        )
        XCTAssertEqual(snapshot.cookies.map(\.name), ["cf_clearance"])
        XCTAssertEqual(snapshot.cookies.first?.value, "clearance-1")
        XCTAssertEqual(snapshot.userAgent, "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15")
    }

    func testIOSWebViewProxyUsesTargetAccountProxyRefAndTracksAccountChanges() async throws {
        let presenter = KotlinPresenter(presenter: ProxySettingsPresenter())
        let editor = KotlinPresenter(presenter: ProxyEditorPresenter())
        try await eventuallyCondition { presenter.state.settings is UiStateSuccess<ProxySettings> }
        let originalGlobal = (presenter.state.settings as! UiStateSuccess<ProxySettings>).data.global
        let account = MicroBlogKey(id: "ios-proxy-\(UUID().uuidString)", host: "challenge.example")
        let accountRef = ProxyRef.companion.forAccount(accountKey: account)

        let firstSocks = try await IOSTestSocksProxyServer()
        let secondSocks = try await IOSTestSocksProxyServer()
        var createdProfileIds: [String] = []
        let accountProxy = WebViewProxy(proxyRef: accountRef)
        let globalProxy = WebViewProxy()

        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        let window = UIWindow(windowScene: scene)
        window.rootViewController = UIViewController()
        window.makeKeyAndVisible()

        defer {
            accountProxy.stop()
            globalProxy.stop()
            firstSocks.stop()
            secondSocks.stop()
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKey()
            presenter.state.select(accountKey: account, policy: ProxyPolicy(mode: .inherit, proxyId: nil))
            presenter.state.select(accountKey: nil, policy: originalGlobal)
            for id in createdProfileIds {
                presenter.state.remove(id: id)
            }
        }

        func createProfile(port: UInt16) async throws -> String {
            let id = UUID().uuidString
            let profile = ProxyProfile(id: id, name: "iOS Test \(id)", protocol: .socks5, host: "127.0.0.1", port: Int32(port))
            createdProfileIds.append(id)
            editor.state.save(profile: profile)
            try await eventuallyCondition {
                (presenter.state.settings as? UiStateSuccess<ProxySettings>)?.data.profiles.contains(where: { $0.id == id }) == true
            }
            return id
        }

        let firstProfileId = try await createProfile(port: firstSocks.port)
        let secondProfileId = try await createProfile(port: secondSocks.port)

        presenter.state.select(accountKey: nil, policy: ProxyPolicy(mode: .direct, proxyId: nil))
        try await eventuallyCondition {
            (presenter.state.settings as? UiStateSuccess<ProxySettings>)?.data.global.mode == .direct
        }
        presenter.state.select(accountKey: account, policy: ProxyPolicy(mode: .proxy, proxyId: firstProfileId))
        try await eventuallyCondition {
            (presenter.state.settings as? UiStateSuccess<ProxySettings>)?.data.policy(accountKey: account).proxyId == firstProfileId
        }

        globalProxy.start()
        accountProxy.start()
        try await eventuallyCondition {
            globalProxy.route?.isValid == true && accountProxy.route?.isValid == true
        }
        let initialAccountRoute = try XCTUnwrap(accountProxy.route)
        let initialGlobalRoute = try XCTUnwrap(globalProxy.route)

        let webConfig = WKWebViewConfiguration()
        webConfig.websiteDataStore = initialAccountRoute.dataStore
        let webView = WKWebView(frame: window.bounds, configuration: webConfig)
        initialAccountRoute.bind(webView)
        window.rootViewController?.view.addSubview(webView)
        webView.load(URLRequest(url: URL(string: "http://ios-account-proxy.local/ping")!))
        try await eventuallyCondition { firstSocks.requestedHosts.contains("ios-account-proxy.local") }

        presenter.state.select(accountKey: nil, policy: ProxyPolicy(mode: .system, proxyId: nil))
        try await eventuallyCondition {
            globalProxy.route != nil && globalProxy.route !== initialGlobalRoute
        }
        XCTAssertTrue(accountProxy.route === initialAccountRoute)
        XCTAssertTrue(initialAccountRoute.isValid)

        presenter.state.select(accountKey: account, policy: ProxyPolicy(mode: .proxy, proxyId: secondProfileId))
        try await eventuallyCondition {
            accountProxy.route != nil && accountProxy.route !== initialAccountRoute
        }
        let updatedAccountRoute = try XCTUnwrap(accountProxy.route)
        let updatedConfig = WKWebViewConfiguration()
        updatedConfig.websiteDataStore = updatedAccountRoute.dataStore
        let updatedWebView = WKWebView(frame: window.bounds, configuration: updatedConfig)
        updatedAccountRoute.bind(updatedWebView)
        window.rootViewController?.view.addSubview(updatedWebView)
        updatedWebView.load(URLRequest(url: URL(string: "http://ios-account-proxy-2.local/ping")!))
        try await eventuallyCondition { secondSocks.requestedHosts.contains("ios-account-proxy-2.local") }
    }

    func testIOSListErrorViewAndUIKitBridgesTriggerSessionChallenge() async throws {
        let accountKey = MicroBlogKey(id: "user-1", host: "manga.example")
        let challenge = SessionChallengeException(
            challengeId: "ios-ui-ticket-1",
            accountKey: accountKey,
            platformId: "manga-plat",
            challengeUrl: "https://manga.example/challenge",
            displayMessage: "Verify in browser"
        )

        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 420, height: 760)
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKey()
        }

        // 1. Direct ListErrorUIView .sessionChallenge mode + onSessionChallenge callback
        let errorUIView = ListErrorUIView(frame: CGRect(x: 0, y: 0, width: 360, height: 240))
        var uiKitLaunched: [(SessionChallengeException, () -> Void)] = []
        var uiKitRetryCount = 0
        errorUIView.onSessionChallenge = { err, onComplete in
            uiKitLaunched.append((err, onComplete))
        }
        errorUIView.configure(error: challenge) {
            uiKitRetryCount += 1
        }
        XCTAssertEqual(errorUIView.currentModeForTesting, .sessionChallenge)
        let uiKitButton = try XCTUnwrap(descendants(errorUIView).compactMap { $0 as? UIButton }.first)
        uiKitButton.sendActions(for: .touchUpInside)
        XCTAssertEqual(uiKitLaunched.count, 1)
        XCTAssertEqual(uiKitLaunched[0].0.challengeId, "ios-ui-ticket-1")
        XCTAssertEqual(uiKitRetryCount, 0)
        uiKitLaunched[0].1()
        XCTAssertEqual(uiKitRetryCount, 1)

        // 2. SwiftUI ListErrorView
        var swiftUILaunched: [(SessionChallengeException, () -> Void)] = []
        var swiftUIRetryCount = 0
        var challengeButtonFrame: CGRect?
        var challengeAction: (@MainActor () -> Void)?
        let listErrorHost = UIHostingController(
            rootView: ListErrorView(error: challenge) {
                swiftUIRetryCount += 1
            }
            .onPreferenceChange(ListErrorChallengeButtonFramePreferenceKey.self) { frame in
                challengeButtonFrame = frame
            }
            .environment(
                \.listErrorChallengeActionBox,
                ListErrorChallengeActionBox { action in
                    challengeAction = action
                }
            )
            .environment(\.launchSessionChallenge) { err, onComplete in
                swiftUILaunched.append((err, onComplete))
            }
        )
        window.rootViewController = listErrorHost
        window.makeKeyAndVisible()
        try await eventually(window: window) {
            challengeButtonFrame != nil && challengeAction != nil
        }
        let triggerListErrorChallenge = try XCTUnwrap(challengeAction)
        triggerListErrorChallenge()
        XCTAssertEqual(swiftUILaunched.count, 1)
        XCTAssertEqual(swiftUILaunched[0].0.challengeId, "ios-ui-ticket-1")
        swiftUILaunched[0].1()
        XCTAssertEqual(swiftUIRetryCount, 1)

        // 3. UITimelineCollectionView bridges @Environment(\.launchSessionChallenge) -> ListErrorUIView
        var timelineRetryCount = 0
        var timelineLaunched: [(SessionChallengeException, () -> Void)] = []
        let timelineHost = UIHostingController(
            rootView: UITimelineCollectionView(data: nil, detailStatusKey: nil)
                .environment(\.launchSessionChallenge) { err, onComplete in
                    timelineLaunched.append((err, onComplete))
                }
        )
        window.rootViewController = timelineHost
        try await eventually(window: window) {
            self.findViewController(ofType: UITimelineCollectionViewController.self, in: timelineHost)?.onSessionChallenge != nil
        }
        let timelineController = try XCTUnwrap(
            findViewController(ofType: UITimelineCollectionViewController.self, in: timelineHost)
        )
        timelineController.submit(
            TimelineContent(
                kind: .timeline,
                state: .error,
                failure: TimelineContent.Failure(error: challenge) {
                    timelineRetryCount += 1
                }
            ),
            columns: 1
        )
        try await eventually(window: window) {
            self.descendants(timelineHost.view).contains { ($0 as? ListErrorUIView)?.currentModeForTesting == .sessionChallenge }
        }
        let timelineErrorView = try XCTUnwrap(
            descendants(timelineHost.view).compactMap { $0 as? ListErrorUIView }.first
        )
        let timelineButton = try XCTUnwrap(descendants(timelineErrorView).compactMap { $0 as? UIButton }.first)
        timelineButton.sendActions(for: .touchUpInside)
        XCTAssertEqual(timelineLaunched.count, 1)
        timelineLaunched[0].1()
        XCTAssertEqual(timelineRetryCount, 1)

        // 4. UIGalleryTimelinePagingView bridges @Environment(\.launchSessionChallenge) -> ListErrorUIView
        var galleryRetryCount = 0
        var galleryLaunched: [(SessionChallengeException, () -> Void)] = []
        let galleryHost = UIHostingController(
            rootView: UIGalleryTimelinePagingView(data: nil)
                .environment(\.launchSessionChallenge) { err, onComplete in
                    galleryLaunched.append((err, onComplete))
                }
        )
        window.rootViewController = galleryHost
        try await eventually(window: window) {
            self.findViewController(ofType: UIGalleryTimelineController.self, in: galleryHost)?.onSessionChallenge != nil
        }
        let galleryController = try XCTUnwrap(
            findViewController(ofType: UIGalleryTimelineController.self, in: galleryHost)
        )
        galleryController.submit(
            TimelineContent(
                kind: .timeline,
                state: .error,
                failure: TimelineContent.Failure(error: challenge) {
                    galleryRetryCount += 1
                }
            )
        )
        try await eventually(window: window) {
            self.descendants(galleryHost.view).contains { ($0 as? ListErrorUIView)?.currentModeForTesting == .sessionChallenge }
        }
        let galleryErrorView = try XCTUnwrap(
            descendants(galleryHost.view).compactMap { $0 as? ListErrorUIView }.first
        )
        let galleryButton = try XCTUnwrap(descendants(galleryErrorView).compactMap { $0 as? UIButton }.first)
        galleryButton.sendActions(for: .touchUpInside)
        XCTAssertEqual(galleryLaunched.count, 1)
        galleryLaunched[0].1()
        XCTAssertEqual(galleryRetryCount, 1)
    }

    private func findViewController<T: UIViewController>(ofType type: T.Type, in root: UIViewController) -> T? {
        if let match = root as? T {
            return match
        }
        for child in root.children {
            if let found = findViewController(ofType: type, in: child) {
                return found
            }
        }
        if let presented = root.presentedViewController,
           let found = findViewController(ofType: type, in: presented) {
            return found
        }
        return nil
    }

    func testIOSMangaReaderCanvasSessionChallengeBatchRetry() async throws {
        let accountKey = MicroBlogKey(id: "user-1", host: "manga.example")
        let chapterChallenge = SessionChallengeException(
            challengeId: "ios-ch-ticket",
            accountKey: accountKey,
            platformId: "manga-plat",
            challengeUrl: "https://manga.example/challenge",
            displayMessage: "Chapter challenge"
        )
        let pageChallenge = SessionChallengeException(
            challengeId: "ios-pg-ticket",
            accountKey: accountKey,
            platformId: "manga-plat",
            challengeUrl: "https://manga.example/challenge",
            displayMessage: "Page challenge"
        )

        var launched: [(SessionChallengeException, () -> Void)] = []
        let launcher: (SessionChallengeException, @escaping () -> Void) -> Void = { err, onComplete in
            launched.append((err, onComplete))
        }

        let fakeState = FakeMangaReaderState(direction: .ltr, pages: [], slots: [])
        fakeState.loadState = UiStateError(throwable: chapterChallenge)
        let controller = MangaReaderInteractionController()
        let hostModel = ReaderTestHostModel(state: fakeState, controller: controller)
        hostModel.hudVisible = false

        var chapterChallengeButtonFrame: CGRect?
        var chapterChallengeAction: (@MainActor () -> Void)?

        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 600, height: 800)
        window.rootViewController = UIHostingController(
            rootView: ReaderTestHostView(model: hostModel)
                .onPreferenceChange(ListErrorChallengeButtonFramePreferenceKey.self) { frame in
                    chapterChallengeButtonFrame = frame
                }
                .environment(
                    \.listErrorChallengeActionBox,
                    ListErrorChallengeActionBox { action in
                        chapterChallengeAction = action
                    }
                )
                .environment(\.launchSessionChallenge, launcher)
        )
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKey()
        }

        try await eventually(window: window) {
            chapterChallengeButtonFrame != nil && chapterChallengeAction != nil
        }
        let triggerChapterChallenge = try XCTUnwrap(chapterChallengeAction)
        triggerChapterChallenge()
        XCTAssertEqual(launched.count, 1)
        let firstLaunch = try XCTUnwrap(launched.first)
        XCTAssertEqual(firstLaunch.0.challengeId, "ios-ch-ticket")
        firstLaunch.1()
        XCTAssertEqual(fakeState.retryChallengeFailuresCount, 1)
        XCTAssertEqual(fakeState.retryChapterCount, 0)

        // Chunk placeholder / page-level challenge triggers retryChallengeFailures()
        launched.removeAll()
        let pageErrorState = FakeMangaReaderState(
            direction: .ltr,
            pages: [
                ReaderPageState(
                    id: "__chunk_placeholder_0",
                    index: 0,
                    status: PageMediaStatusFailed(error: pageChallenge),
                    aspectRatio: nil
                ),
            ],
            slots: [ReaderSlotSingle(pageIndex: 0)]
        )
        hostModel.setState(pageErrorState)
        try await eventually(window: window) {
            hostModel.retryPageIds.contains("__chunk_placeholder_0") &&
                controller.pageRetryHandlers["__chunk_placeholder_0"] != nil
        }
        XCTAssertTrue(controller.triggerPageRetryForTesting(pageId: "__chunk_placeholder_0"))
        XCTAssertEqual(launched.count, 1)
        XCTAssertEqual(launched[0].0.challengeId, "ios-pg-ticket")
        launched[0].1()
        XCTAssertEqual(pageErrorState.retryChallengeFailuresCount, 1)
        XCTAssertTrue(pageErrorState.retriedPages.isEmpty)
    }

    func testIOSSessionChallengeHostLifecycleOriginPolicyBusyReloginAndCancel() async throws {
        let accountKey = MicroBlogKey(id: "user-1", host: "challenge.example")
        let challengeURL = "https://challenge.example/gate"
        let handle = FakeIOSSessionChallengeHandle(
            challengeId: "ios-ticket-1",
            accountKey: accountKey,
            platformId: "manga-plat",
            challengeUrl: challengeURL,
            seedCookies: [SessionChallengeSeedCookie(name: "seed_a", value: "val_a")],
            probes: [
                SessionChallengeCookieProbe(
                    url: challengeURL,
                    cookies: [SessionChallengeCookieRequirement(name: "cf_clearance", required: true)]
                ),
            ]
        )
        handle.nextResults = [
            SessionChallengeCheckResultBusy.shared,
            SessionChallengeCheckResultPending.shared,
            SessionChallengeCheckResultCompleted.shared,
        ]
        var completedCount = 0
        var reloginTargets: [RequireReLoginException] = []
        var dismissedCount = 0

        let controller = SessionChallengeSessionController(
            exception: SessionChallengeException(
                challengeId: "ios-ticket-1",
                accountKey: accountKey,
                platformId: "manga-plat",
                challengeUrl: challengeURL,
                displayMessage: "Verify"
            ),
            service: SessionChallengeService(
                beginSession: { _ in handle },
                check: { target, snapshot in
                    try await (target as! FakeIOSSessionChallengeHandle).performCheck(snapshot)
                },
                cancel: { $0.cancel() },
                close: { $0.close() }
            ),
            onCompleted: { completedCount += 1 },
            onRequireRelogin: { reloginTargets.append($0) },
            onDismiss: { dismissedCount += 1 }
        )

        await controller.start()
        try await eventuallyCondition { controller.proxy?.route?.isValid == true }
        let route = try XCTUnwrap(controller.proxy?.route)
        await controller.prepareRouteIfNeeded(route)

        let clearance = try XCTUnwrap(HTTPCookie(properties: [
            .originURL: URL(string: challengeURL)!,
            .path: "/",
            .name: "cf_clearance",
            .value: "cleared-ios",
            .secure: "TRUE",
        ]))
        await route.dataStore.httpCookieStore.setCookie(clearance)

        let webConfig = WKWebViewConfiguration()
        webConfig.websiteDataStore = route.dataStore
        let webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 320, height: 320), configuration: webConfig)
        let navDelegate = SessionChallengeNavigationDelegate(route: route) { origin in
            controller.updateCurrentOrigin(origin)
        } onNavigationFinished: { finishedURL in
            controller.handleNavigationFinished(url: finishedURL, route: route)
        }
        webView.navigationDelegate = navDelegate
        route.bind(webView)
        controller.attachWebView(webView, route: route)
        webView.loadHTMLString("<html><body>ok</body></html>", baseURL: URL(string: challengeURL)!)

        let httpDecision = navDelegate.allowsMainFrameURLForTesting(URL(string: "http://challenge.example/gate")!)
        XCTAssertFalse(httpDecision)
        let deviatedDecision = navDelegate.allowsMainFrameURLForTesting(URL(string: "https://other.example/login")!)
        XCTAssertTrue(deviatedDecision)
        XCTAssertTrue(controller.isOriginDeviated)

        try await eventuallyCondition { handle.recordedSnapshots.count >= 1 }
        controller.requestCheck(route: route)
        try await eventuallyCondition { handle.recordedSnapshots.count >= 2 }
        controller.requestCheck(route: route)
        try await eventuallyCondition { completedCount == 1 }
        XCTAssertEqual(handle.closeCount, 1)
        XCTAssertFalse(route.isValid)
        XCTAssertTrue(handle.recordedSnapshots.contains { snapshot in
            snapshot.cookies.contains { $0.name == "cf_clearance" && $0.value == "cleared-ios" } &&
                (snapshot.userAgent?.contains("AppleWebKit") == true)
        })

        // RequireReLoginException & Cancel
        let reloginHandle = FakeIOSSessionChallengeHandle(
            challengeId: "ios-relogin",
            accountKey: accountKey,
            platformId: "manga-plat",
            challengeUrl: challengeURL,
            seedCookies: [],
            probes: []
        )
        reloginHandle.errorToThrow = NSError(
            domain: "KotlinException",
            code: 0,
            userInfo: [
                "KotlinException": RequireReLoginException(accountKey: accountKey, platformId: "manga-plat"),
            ]
        )
        let reloginController = SessionChallengeSessionController(
            exception: SessionChallengeException(
                challengeId: "ios-relogin",
                accountKey: accountKey,
                platformId: "manga-plat",
                challengeUrl: challengeURL,
                displayMessage: "Verify"
            ),
            service: SessionChallengeService(
                beginSession: { _ in reloginHandle },
                check: { target, snapshot in
                    try await (target as! FakeIOSSessionChallengeHandle).performCheck(snapshot)
                },
                cancel: { $0.cancel() },
                close: { $0.close() }
            ),
            onCompleted: {},
            onRequireRelogin: { reloginTargets.append($0) },
            onDismiss: {}
        )
        await reloginController.start()
        try await eventuallyCondition { reloginController.proxy?.route?.isValid == true }
        let reloginRoute = try XCTUnwrap(reloginController.proxy?.route)
        await reloginController.prepareRouteIfNeeded(reloginRoute)
        reloginController.requestCheck(route: reloginRoute)
        try await eventuallyCondition { reloginTargets.count == 1 }
        XCTAssertEqual(reloginHandle.closeCount, 1)
        XCTAssertFalse(reloginRoute.isValid)

        let cancelHandle = FakeIOSSessionChallengeHandle(
            challengeId: "ios-cancel",
            accountKey: accountKey,
            platformId: "manga-plat",
            challengeUrl: challengeURL,
            seedCookies: [],
            probes: []
        )
        let cancelController = SessionChallengeSessionController(
            exception: SessionChallengeException(
                challengeId: "ios-cancel",
                accountKey: accountKey,
                platformId: "manga-plat",
                challengeUrl: challengeURL,
                displayMessage: "Verify"
            ),
            service: SessionChallengeService(
                beginSession: { _ in cancelHandle },
                check: { target, snapshot in
                    try await (target as! FakeIOSSessionChallengeHandle).performCheck(snapshot)
                },
                cancel: { $0.cancel() },
                close: { $0.close() }
            ),
            onCompleted: {},
            onRequireRelogin: { _ in },
            onDismiss: { dismissedCount += 1 }
        )
        await cancelController.start()
        try await eventuallyCondition { cancelController.proxy?.route?.isValid == true }
        let cancelRoute = try XCTUnwrap(cancelController.proxy?.route)
        cancelController.cancelAndDismiss()
        XCTAssertEqual(cancelHandle.cancelCount, 1)
        XCTAssertEqual(cancelHandle.closeCount, 1)
        XCTAssertFalse(cancelRoute.isValid)
        XCTAssertEqual(dismissedCount, 1)
    }

    func testIOSConsecutiveSessionChallengePresentationsDoNotReuseClosedController() async throws {
        let accountKey = MicroBlogKey(id: "user-1", host: "challenge.example")
        var begunChallengeIds: [String] = []
        let service = SessionChallengeService(
            beginSession: { challengeId in
                begunChallengeIds.append(challengeId)
                return nil
            },
            check: { _, _ in SessionChallengeCheckResultPending.shared },
            cancel: { _ in },
            close: { _ in }
        )

        var capturedLauncher: ((SessionChallengeException, @escaping () -> Void) -> Void)?
        @MainActor
        struct IOSChallengeHostProbeView: View {
            @Environment(\.launchSessionChallenge) private var launchSessionChallenge
            let onCapture: (@escaping (SessionChallengeException, @escaping () -> Void) -> Void) -> Void

            var body: some View {
                Color.clear
                    .onAppear {
                        if let launchSessionChallenge {
                            onCapture(launchSessionChallenge)
                        }
                    }
            }
        }

        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let rootView = IOSChallengeHostProbeView { launcher in
            capturedLauncher = launcher
        }
        .flareSessionChallengeHost(service: service)
        window.rootViewController = UIHostingController(rootView: rootView)
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
        }

        try await eventually(window: window) {
            capturedLauncher != nil
        }

        let launch = try XCTUnwrap(capturedLauncher)
        let first = SessionChallengeException(
            challengeId: "ios-seq-1",
            accountKey: accountKey,
            platformId: "manga-plat",
            challengeUrl: "https://challenge.example/gate-1",
            displayMessage: "Verify 1"
        )
        let second = SessionChallengeException(
            challengeId: "ios-seq-2",
            accountKey: accountKey,
            platformId: "manga-plat",
            challengeUrl: "https://challenge.example/gate-2",
            displayMessage: "Verify 2"
        )

        launch(first) {}
        try await eventuallyCondition { begunChallengeIds == ["ios-seq-1"] }

        launch(second) {}
        try await eventuallyCondition { begunChallengeIds == ["ios-seq-1", "ios-seq-2"] }
    }

    func testIOSSessionChallengeRefreshUserAgentCoalescesAndPopupTargetFrameNilDoesNotMutateOrigin() async throws {
        let accountKey = MicroBlogKey(id: "user-1", host: "challenge.example")
        let challengeURL = "https://challenge.example/gate"
        let handle = FakeIOSSessionChallengeHandle(
            challengeId: "ios-ua-popup",
            accountKey: accountKey,
            platformId: "manga-plat",
            challengeUrl: challengeURL,
            seedCookies: [],
            probes: []
        )
        handle.nextResults = [SessionChallengeCheckResultCompleted.shared]
        var completedCount = 0
        let controller = SessionChallengeSessionController(
            exception: SessionChallengeException(
                challengeId: "ios-ua-popup",
                accountKey: accountKey,
                platformId: "manga-plat",
                challengeUrl: challengeURL,
                displayMessage: "Verify"
            ),
            service: SessionChallengeService(
                beginSession: { _ in handle },
                check: { target, snapshot in
                    try await (target as! FakeIOSSessionChallengeHandle).performCheck(snapshot)
                },
                cancel: { $0.cancel() },
                close: { $0.close() }
            ),
            onCompleted: { completedCount += 1 },
            onRequireRelogin: { _ in },
            onDismiss: {}
        )

        await controller.start()
        try await eventuallyCondition { controller.proxy?.route?.isValid == true }
        let route = try XCTUnwrap(controller.proxy?.route)
        await controller.prepareRouteIfNeeded(route)

        let webConfig = WKWebViewConfiguration()
        webConfig.websiteDataStore = route.dataStore
        let slowWebView = DelayedIOSUAWKWebView(frame: CGRect(x: 0, y: 0, width: 320, height: 320), configuration: webConfig)
        slowWebView.resolvedUA = "FlareSlowIOSUA/2.0"

        // Finding 3: targetFrame == nil popup must never mutate currentOrigin / isOriginDeviated.
        let navDelegate = controller.makeNavigationDelegate(for: route)
        let popupAction = FakeIOSPopupNavigationAction(url: URL(string: "https://popup.other.example/help")!)
        _ = await navDelegate.webView(slowWebView, decidePolicyFor: popupAction)
        XCTAssertEqual(controller.currentOrigin, "https://challenge.example")
        XCTAssertFalse(controller.isOriginDeviated)

        // Finding 2: attachWebView starts UA evaluation; concurrent refreshUserAgent & initial check must await it rather than sending nil.
        route.bind(slowWebView)
        controller.attachWebView(slowWebView, route: route)
        try await eventuallyCondition { slowWebView.evaluateCount == 1 }

        let concurrentRefresh = Task { await controller.refreshUserAgent() }
        controller.requestCheck(route: route)
        try await Task.sleep(for: .milliseconds(60))
        XCTAssertTrue(handle.recordedSnapshots.isEmpty, "Initial check must wait for in-flight UA evaluation rather than sending nil")

        slowWebView.completePendingUserAgentEvaluations()
        await concurrentRefresh.value
        try await eventuallyCondition { completedCount == 1 }

        XCTAssertEqual(slowWebView.evaluateCount, 1)
        XCTAssertEqual(handle.recordedSnapshots.first?.userAgent, "FlareSlowIOSUA/2.0")
    }

    private func eventuallyCondition(
        timeout: TimeInterval = 15,
        _ condition: @MainActor () -> Bool,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline {
            try await Task.sleep(for: .milliseconds(15))
        }
        XCTAssertTrue(condition(), file: file, line: line)
    }

    private func makeRichSynopsis(_ paragraphs: [String]) -> UiRichText {
        let style = RenderTextStyle(
            link: nil,
            bold: false,
            italic: false,
            strikethrough: false,
            monospace: false,
            code: false,
            underline: false,
            small: false,
            time: false,
            imageLink: nil
        )
        let blocks: [RenderContent] = paragraphs.map {
            RenderContent.Text(runs: [RenderRun.Text(text: $0, style: style)], block: RenderBlockStyle())
        }
        let raw = paragraphs.joined(separator: "\n")
        return UiRichText(renderRuns: blocks, isRtl: false, raw: raw, innerText: raw, imageUrls: [])
    }

    private func renderPDFText<V: View>(_ view: V, width: CGFloat, height: CGFloat) throws -> String {
        let content = view.frame(width: width, height: height)
        let data = NSMutableData()
        let consumer = try XCTUnwrap(CGDataConsumer(data: data))
        var bounds = CGRect(x: 0, y: 0, width: width, height: height)
        let context = try XCTUnwrap(CGContext(consumer: consumer, mediaBox: &bounds, nil))
        context.beginPDFPage(nil)
        ImageRenderer(content: content).render { _, draw in draw(context) }
        context.endPDFPage()
        context.closePDF()
        return try XCTUnwrap(PDFDocument(data: data as Data)?.string)
    }

    private func countSubviews<T: UIView>(ofType type: T.Type, in root: UIView) -> Int {
        let own = root is T ? 1 : 0
        return own + root.subviews.reduce(0) { $0 + countSubviews(ofType: type, in: $1) }
    }

    private func visibleSubviews<T: UIView>(ofType type: T.Type, in root: UIView) -> [T] {
        descendants(root).compactMap { $0 as? T }.filter { view in
            guard view.window != nil, view.bounds.width > 0, view.bounds.height > 0 else { return false }
            var current: UIView? = view
            while let node = current {
                if node.isHidden || node.alpha <= 0.01 {
                    return false
                }
                current = node.superview
            }
            return true
        }
    }

    private func descendants(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(descendants) }

    private func findHorizontalPagingScrollView(in root: UIView) -> UIScrollView? {
        if let scrollView = root as? UIScrollView,
           scrollView.bounds.width >= 300,
           scrollView.contentSize.width > scrollView.bounds.width + 10 {
            return scrollView
        }
        for subview in root.subviews {
            if let found = findHorizontalPagingScrollView(in: subview) {
                return found
            }
        }
        return nil
    }

    private func simulateUserHorizontalScroll(_ scrollView: UIScrollView, pageDelta: Int, controller: MangaReaderInteractionController) async {
        let pageWidth = max(scrollView.bounds.width, 1)
        let maxX = max(scrollView.contentSize.width - pageWidth, 0)
        let nextX = min(max(scrollView.contentOffset.x + CGFloat(pageDelta) * pageWidth, 0), maxX)
        controller.processPanGestureStateForTesting(.began)
        scrollView.delegate?.scrollViewWillBeginDragging?(scrollView)
        controller.processPanGestureStateForTesting(.changed)
        scrollView.setContentOffset(CGPoint(x: nextX, y: 0), animated: false)
        scrollView.window?.layoutIfNeeded()
        try? await Task.sleep(for: .milliseconds(25))
        var target = CGPoint(x: nextX, y: 0)
        scrollView.delegate?.scrollViewWillEndDragging?(scrollView, withVelocity: .zero, targetContentOffset: &target)
        scrollView.delegate?.scrollViewDidEndDragging?(scrollView, willDecelerate: false)
        controller.processPanGestureStateForTesting(.ended)
    }

    private func eventually(
        window: UIWindow,
        _ condition: () -> Bool,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async throws {
        let deadline = Date().addingTimeInterval(5)
        while !condition(), Date() < deadline {
            window.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(15))
        }
        XCTAssertTrue(condition(), file: file, line: line)
    }
}

@MainActor
@Observable
private final class ReaderTestHostModel {
    var snapshot: any MangaReaderPresenterState
    let controller: MangaReaderInteractionController
    let deferSnapshotDelivery: Bool
    var hudVisible = true
    var isOverlayPresented = false
    var pageFrames: [Int: CGRect] = [:]
    var retryPageIds: Set<String> = []
    var scrubberLeadingLabelFrame: CGRect?
    var scrubberTrailingLabelFrame: CGRect?
    private var stateGeneration = 0

    init(
        state: FakeMangaReaderState,
        controller: MangaReaderInteractionController,
        deferSnapshotDelivery: Bool = false
    ) {
        self.snapshot = IOSMangaStateSnapshot(backing: state)
        self.controller = controller
        self.deferSnapshotDelivery = deferSnapshotDelivery
        bindMutationCallback(to: state)
    }

    func setState(_ next: FakeMangaReaderState) {
        stateGeneration += 1
        retryPageIds.removeAll()
        snapshot = IOSMangaStateSnapshot(backing: next)
        bindMutationCallback(to: next)
    }

    private func bindMutationCallback(to state: FakeMangaReaderState) {
        let boundGeneration = stateGeneration
        state.onStateMutated = { [weak self, weak state] in
            guard let self, let state else { return }
            if self.deferSnapshotDelivery {
                let captured = IOSMangaStateSnapshot(backing: state)
                Task { @MainActor [weak self] in
                    guard let self, self.stateGeneration == boundGeneration else { return }
                    self.snapshot = captured
                }
            } else {
                self.snapshot = IOSMangaStateSnapshot(backing: state)
            }
        }
    }
}

@MainActor
private final class IOSMangaStateSnapshot: NSObject, MangaReaderPresenterState {
    private let backing: FakeMangaReaderState
    let mangaTitle: String
    let chapterTitle: String
    let chapterUrl: String?
    let activeChapterId: String
    let loadState: UiState<KotlinUnit>
    let pages: [ReaderPageState]
    let slots: [any ReaderSlot]
    let currentPageIndex: Int32
    let currentSlotIndex: Int32
    let direction: MangaReadingDirection
    let doublePageSpread: Bool
    let spreadCoverSinglePage: Bool
    let previousBoundary: any ChapterBoundary
    let nextBoundary: any ChapterBoundary
    let prefetchMedia: [UiMediaImage]
    let imageTranslation: any ImageTranslationPresenterState
    let chapterGroups: [UiMangaChapterGroup]
    let selectedChapterGroupId: String?
    let chapterSortDescending: Bool
    let chapterItems: [ReaderChapterItem]
    let webtoonConstrainWidthOnWideScreen: Bool
    let volumeKeysNavigation: Bool
    let lockedChapter: ChapterBoundaryLocked?

    init(backing: FakeMangaReaderState) {
        self.backing = backing
        self.mangaTitle = backing.mangaTitle
        self.chapterTitle = backing.chapterTitle
        self.chapterUrl = backing.chapterUrl
        self.activeChapterId = backing.activeChapterId
        self.loadState = backing.loadState
        self.pages = backing.pages
        self.slots = backing.slots
        self.currentPageIndex = backing.currentPageIndex
        self.currentSlotIndex = backing.currentSlotIndex
        self.direction = backing.direction
        self.doublePageSpread = backing.doublePageSpread
        self.spreadCoverSinglePage = backing.spreadCoverSinglePage
        self.previousBoundary = backing.previousBoundary
        self.nextBoundary = backing.nextBoundary
        self.prefetchMedia = backing.prefetchMedia
        self.imageTranslation = backing.imageTranslation
        self.chapterGroups = backing.chapterGroups
        self.selectedChapterGroupId = backing.selectedChapterGroupId
        self.chapterSortDescending = backing.chapterSortDescending
        self.chapterItems = backing.chapterItems
        self.webtoonConstrainWidthOnWideScreen = backing.webtoonConstrainWidthOnWideScreen
        self.volumeKeysNavigation = backing.volumeKeysNavigation
        self.lockedChapter = backing.lockedChapter
    }

    func setWideViewport(isWide: Bool) { backing.setWideViewport(isWide: isWide) }
    func onPageDimensionsLoaded(pageId: String, width: Float, height: Float) {
        backing.onPageDimensionsLoaded(pageId: pageId, width: width, height: height)
    }
    func goToPage(pageIndex: Int32) { backing.goToPage(pageIndex: pageIndex) }
    func goToSlot(slotIndex: Int32) { backing.goToSlot(slotIndex: slotIndex) }
    func stepForward() { backing.stepForward() }
    func stepBackward() { backing.stepBackward() }
    func openAdjacentChapter(boundary: ChapterBoundaryUnlocked) { backing.openAdjacentChapter(boundary: boundary) }
    func retryChapter() { backing.retryChapter() }
    func retryPage(pageId: String) { backing.retryPage(pageId: pageId) }
    func retryPage(pageId: String, failedUrl: String?) { backing.retryPage(pageId: pageId, failedUrl: failedUrl) }
    func retryChallengeFailures() { backing.retryChallengeFailures() }
    func setDirection(direction: MangaReadingDirection) { backing.setDirection(direction: direction) }
    func setDoublePageSpread(enabled: Bool) { backing.setDoublePageSpread(enabled: enabled) }
    func setSpreadCoverSinglePage(enabled: Bool) { backing.setSpreadCoverSinglePage(enabled: enabled) }
    func selectChapterGroup(groupId: String) { backing.selectChapterGroup(groupId: groupId) }
    func toggleChapterSort() { backing.toggleChapterSort() }
    func selectChapter(chapterId: String) { backing.selectChapter(chapterId: chapterId) }
    func openChapter(chapterId: String, initialPage: KotlinInt?) {
        backing.openChapter(chapterId: chapterId, initialPage: initialPage)
    }
    func resolveChapterOpenUrl(chapter: UiMangaChapter) -> String? {
        backing.resolveChapterOpenUrl(chapter: chapter)
    }
    func setWebtoonConstrainWidthOnWideScreen(enabled: Bool) {
        backing.setWebtoonConstrainWidthOnWideScreen(enabled: enabled)
    }
    func setVolumeKeysNavigation(enabled: Bool) {
        backing.setVolumeKeysNavigation(enabled: enabled)
    }
}

private struct ReaderTestHostView: View {
    @Bindable var model: ReaderTestHostModel

    var body: some View {
        MangaReaderContentView(
            state: model.snapshot,
            hudVisible: $model.hudVisible,
            goBack: {},
            controller: model.controller
        )
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onPreferenceChange(MangaReaderPageFramesPreferenceKey.self) { model.pageFrames = $0 }
        .onPreferenceChange(MangaReaderRetryPageIdsPreferenceKey.self) { model.retryPageIds = $0 }
        .onPreferenceChange(MangaReaderScrubberFramesPreferenceKey.self) { frames in
            model.scrubberLeadingLabelFrame = frames.currentLabel
            model.scrubberTrailingLabelFrame = frames.totalLabel
        }
    }
}

@MainActor
private func makeSamplePages(count: Int) -> [ReaderPageState] {
    (0..<count).map { index in
        let fileURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("manga-test-page-\(index).png")
        if !FileManager.default.fileExists(atPath: fileURL.path) {
            let renderer = UIGraphicsImageRenderer(size: CGSize(width: 100, height: 140))
            let png = renderer.pngData { ctx in
                UIColor.darkGray.setFill()
                ctx.fill(CGRect(x: 0, y: 0, width: 100, height: 140))
            }
            try? png.write(to: fileURL)
        }
        let media = UiMediaImage(
            url: fileURL.absoluteString,
            previewUrl: fileURL.absoluteString,
            description: "Page \(index + 1)",
            height: 140,
            width: 100,
            sensitive: false,
            customHeaders: nil,
            proxy: ProxyRef.Unbound.shared
        )
        return ReaderPageState(
            id: "p-\(index)",
            index: Int32(index),
            status: PageMediaStatusReady(original: media, displayed: media),
            aspectRatio: KotlinFloat(value: 100.0 / 140.0)
        )
    }
}

@MainActor
private final class FakeMangaReaderState: NSObject, MangaReaderPresenterState {
    var onStateMutated: (() -> Void)?

    var mangaTitle: String = "Starlight Odyssey"
    var chapterTitle: String = "Chapter 1"
    var chapterUrl: String? = "https://manga.example/ch/1"
    var activeChapterId: String = "ch-1"
    var loadState: UiState<KotlinUnit> = UiStateSuccess(data: KotlinUnit())
    var pages: [ReaderPageState]
    var slots: [any ReaderSlot]
    var currentPageIndex: Int32 = 0
    var direction: MangaReadingDirection
    var doublePageSpread: Bool
    var spreadCoverSinglePage: Bool = false
    var previousBoundary: any ChapterBoundary
    var nextBoundary: any ChapterBoundary
    var prefetchMedia: [UiMediaImage] = []
    let imageTranslation: any ImageTranslationPresenterState
    var chapterGroups: [UiMangaChapterGroup] = []
    var selectedChapterGroupId: String?
    var chapterSortDescending: Bool = true
    var chapterItems: [ReaderChapterItem] = []
    var webtoonConstrainWidthOnWideScreen: Bool = true
    var volumeKeysNavigation: Bool = false
    var lockedChapter: ChapterBoundaryLocked? { nil }

    var lastWideViewport: Bool?
    var stepForwardCount = 0
    var stepBackwardCount = 0
    var openedBoundaryChapterId: String?
    var selectedChapterId: String?
    var toggleChapterSortCount = 0
    var openedChapterRequests: [(String, Int32?)] = []
    var retriedPageIds: [String] = []
    var retriedPages: [(String, String?)] = []
    var retryChapterCount = 0
    var retryChallengeFailuresCount = 0
    var loadedPageIds: Set<String> = []

    init(
        direction: MangaReadingDirection,
        pages: [ReaderPageState],
        slots: [any ReaderSlot],
        previousBoundary: any ChapterBoundary = ChapterBoundaryNone.shared,
        nextBoundary: any ChapterBoundary = ChapterBoundaryNone.shared,
        imageTranslation: any ImageTranslationPresenterState = NoopImageTranslationState()
    ) {
        self.direction = direction
        self.pages = pages
        self.slots = slots
        self.doublePageSpread = slots.contains { $0 is ReaderSlotDouble }
        self.previousBoundary = previousBoundary
        self.nextBoundary = nextBoundary
        self.imageTranslation = imageTranslation
    }

    var currentSlotIndex: Int32 {
        Int32(MangaSpreadLayoutSolver.shared.slotIndexForPage(slots: slots, pageIndex: currentPageIndex))
    }

    func setWideViewport(isWide: Bool) {
        lastWideViewport = isWide
    }

    func onPageDimensionsLoaded(pageId: String, width: Float, height: Float) { loadedPageIds.insert(pageId) }

    func goToPage(pageIndex: Int32) {
        guard !pages.isEmpty else { return }
        currentPageIndex = min(max(pageIndex, 0), Int32(pages.count - 1))
        onStateMutated?()
    }

    func goToSlot(slotIndex: Int32) {
        guard !slots.isEmpty else { return }
        let clamped = min(max(Int(slotIndex), 0), slots.count - 1)
        let slot = slots[clamped]
        let containsCurrent: Bool
        if let single = slot as? ReaderSlotSingle {
            containsCurrent = single.pageIndex == currentPageIndex
        } else if let doubleSlot = slot as? ReaderSlotDouble {
            containsCurrent = doubleSlot.leftPageIndex == currentPageIndex || doubleSlot.rightPageIndex == currentPageIndex
        } else {
            containsCurrent = slot.primaryPageIndex == currentPageIndex
        }
        if !containsCurrent {
            currentPageIndex = slot.primaryPageIndex
        }
        onStateMutated?()
    }

    func stepForward() {
        stepForwardCount += 1
        let next = Int(currentSlotIndex) + 1
        if next < slots.count {
            currentPageIndex = slots[next].primaryPageIndex
        } else if let unlocked = nextBoundary as? ChapterBoundaryUnlocked {
            openedBoundaryChapterId = unlocked.chapter.id
        }
        onStateMutated?()
    }

    func stepBackward() {
        stepBackwardCount += 1
        let prev = Int(currentSlotIndex) - 1
        if prev >= 0 {
            currentPageIndex = slots[prev].primaryPageIndex
        } else if let unlocked = previousBoundary as? ChapterBoundaryUnlocked {
            openedBoundaryChapterId = unlocked.chapter.id
        }
        onStateMutated?()
    }

    func openAdjacentChapter(boundary: ChapterBoundaryUnlocked) {
        openedBoundaryChapterId = boundary.chapter.id
        onStateMutated?()
    }

    func retryChapter() {
        retryChapterCount += 1
        onStateMutated?()
    }

    func retryPage(pageId: String) {
        retryPage(pageId: pageId, failedUrl: nil)
    }

    func retryPage(pageId: String, failedUrl: String?) {
        retriedPageIds.append(pageId)
        retriedPages.append((pageId, failedUrl))
        onStateMutated?()
    }

    func retryChallengeFailures() {
        retryChallengeFailuresCount += 1
        onStateMutated?()
    }

    func setDirection(direction: MangaReadingDirection) {
        self.direction = direction
        onStateMutated?()
    }

    func setDoublePageSpread(enabled: Bool) {
        doublePageSpread = enabled
        onStateMutated?()
    }

    func setSpreadCoverSinglePage(enabled: Bool) {
        spreadCoverSinglePage = enabled
        onStateMutated?()
    }

    func selectChapterGroup(groupId: String) {
        selectedChapterGroupId = groupId
        onStateMutated?()
    }

    func toggleChapterSort() {
        toggleChapterSortCount += 1
        chapterSortDescending.toggle()
        chapterItems.reverse()
        onStateMutated?()
    }

    func selectChapter(chapterId: String) {
        selectedChapterId = chapterId
        activeChapterId = chapterId
        onStateMutated?()
    }

    func openChapter(chapterId: String, initialPage: KotlinInt?) {
        openedChapterRequests.append((chapterId, initialPage?.int32Value))
        activeChapterId = chapterId
        if let page = initialPage?.int32Value {
            currentPageIndex = page
        }
        onStateMutated?()
    }

    func resolveChapterOpenUrl(chapter: UiMangaChapter) -> String? {
        chapter.openUrl(mangaUrl: chapterUrl)
    }

    func setWebtoonConstrainWidthOnWideScreen(enabled: Bool) {
        webtoonConstrainWidthOnWideScreen = enabled
        onStateMutated?()
    }

    func setVolumeKeysNavigation(enabled: Bool) {
        volumeKeysNavigation = enabled
        onStateMutated?()
    }

    func updateSlots(_ newSlots: [any ReaderSlot]) {
        slots = newSlots
        doublePageSpread = newSlots.contains { $0 is ReaderSlotDouble }
        onStateMutated?()
    }

    func replacePagesAndSlots(pages: [ReaderPageState], slots: [any ReaderSlot], currentPageIndex: Int32) {
        self.pages = pages
        self.slots = slots
        self.currentPageIndex = currentPageIndex
        self.doublePageSpread = slots.contains { $0 is ReaderSlotDouble }
        onStateMutated?()
    }
}

@MainActor
private final class NoopImageTranslationState: NSObject, ImageTranslationPresenterState {
    let configured = false
    var onImageFailure: ((any UiMedia, String) -> Void)?

    func canRegenerate(media: any UiMedia) -> Bool { false }
    func canToggle(media: any UiMedia) -> Bool { false }
    func clearError(media: any UiMedia) {}
    func displayed(media: any UiMedia) -> any UiMedia { media }
    func imageFailed(media: any UiMedia, url: String) { onImageFailure?(media, url) }
    func item(media: any UiMedia) -> ImageTranslationItem {
        ImageTranslationItem(
            loading: false,
            translated: nil,
            showingTranslated: false,
            error: nil,
            previous: nil,
            previousWasShown: false,
            errorDetails: nil,
            errorDetailsVisible: false
        )
    }
    func regenerate(media: any UiMedia) {}
    func setErrorDetailsVisible(media: any UiMedia, visible: Bool) {}
    func toggle(media: any UiMedia) {}
}

@MainActor
private final class StatefulIOSImageTranslationState: NSObject, ImageTranslationPresenterState {
    let configured = true
    var itemsByURL: [String: ImageTranslationItem] = [:]

    func canRegenerate(media: any UiMedia) -> Bool { itemsByURL[media.url]?.translated != nil }
    func canToggle(media: any UiMedia) -> Bool { media is UiMediaImage }
    func clearError(media: any UiMedia) {
        guard let existing = itemsByURL[media.url] else { return }
        itemsByURL[media.url] = ImageTranslationItem(
            loading: existing.loading,
            translated: existing.translated,
            showingTranslated: existing.showingTranslated,
            error: nil,
            previous: existing.previous,
            previousWasShown: existing.previousWasShown,
            errorDetails: existing.errorDetails,
            errorDetailsVisible: existing.errorDetailsVisible
        )
    }
    func displayed(media: any UiMedia) -> any UiMedia { media }
    func imageFailed(media: any UiMedia, url: String) {}
    func item(media: any UiMedia) -> ImageTranslationItem {
        itemsByURL[media.url] ?? ImageTranslationItem(
            loading: false,
            translated: nil,
            showingTranslated: false,
            error: nil,
            previous: nil,
            previousWasShown: false,
            errorDetails: nil,
            errorDetailsVisible: false
        )
    }
    func regenerate(media: any UiMedia) {}
    func setErrorDetailsVisible(media: any UiMedia, visible: Bool) {
        let existing = item(media: media)
        itemsByURL[media.url] = ImageTranslationItem(
            loading: existing.loading,
            translated: existing.translated,
            showingTranslated: existing.showingTranslated,
            error: nil,
            previous: existing.previous,
            previousWasShown: existing.previousWasShown,
            errorDetails: existing.errorDetails,
            errorDetailsVisible: visible
        )
    }
    func toggle(media: any UiMedia) {}
}

@MainActor
@Observable
private final class CompactNavPathModel {
    var path: [Int] = [1]
}

private struct CompactNavTestHost: View {
    @Bindable var model: CompactNavPathModel
    let detail: UiMangaDetail
    let chapters: [UiMangaChapterItem]
    let readTarget: PrimaryReadTarget
    let onHeroFrames: (MangaDetailPaneFrames) -> Void
    let onBackdropFrame: (CGRect?) -> Void
    let onHeroVisible: (Bool?) -> Void
    let onDummyDestinationLifecycle: (Bool) -> Void

    var body: some View {
        NavigationStack(path: $model.path) {
            Color.clear
                .navigationDestination(for: Int.self) { step in
                    if step == 1 {
                        MangaDetailContentView(
                            detail: UiStateSuccess(data: detail),
                            chapters: chapters,
                            primaryReadTarget: readTarget,
                            supportsComments: true,
                            onNavigate: { _ in }
                        )
                        .onPreferenceChange(MangaDetailPaneFramesPreferenceKey.self, perform: onHeroFrames)
                        .onPreferenceChange(IOSMangaCompactBackdropFramePreferenceKey.self, perform: onBackdropFrame)
                        .onPreferenceChange(IOSMangaCompactHeroVisiblePreferenceKey.self, perform: onHeroVisible)
                    } else {
                        Text("Dummy Reader Destination")
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .onAppear { onDummyDestinationLifecycle(true) }
                            .onDisappear { onDummyDestinationLifecycle(false) }
                    }
                }
        }
        .environment(\.horizontalSizeClass, .compact)
    }
}

private final class FakeIOSSessionChallengeHandle: NSObject, SessionChallengeHandle, @unchecked Sendable {
    let challengeId: String
    let accountKey: MicroBlogKey
    let platformId: String
    let proxyRef: ProxyRef
    let challengeUrl: String
    let seedCookies: [SessionChallengeSeedCookie]
    let probes: [SessionChallengeCookieProbe]

    private let lock = NSLock()
    var nextResults: [any SessionChallengeCheckResult] = [SessionChallengeCheckResultCompleted.shared]
    var errorToThrow: Error?
    var recordedSnapshots: [SessionChallengeCookieSnapshot] = []
    var cancelCount = 0
    var closeCount = 0

    init(
        challengeId: String,
        accountKey: MicroBlogKey,
        platformId: String,
        challengeUrl: String,
        seedCookies: [SessionChallengeSeedCookie],
        probes: [SessionChallengeCookieProbe]
    ) {
        self.challengeId = challengeId
        self.accountKey = accountKey
        self.platformId = platformId
        self.proxyRef = ProxyRef.companion.forAccount(accountKey: accountKey)
        self.challengeUrl = challengeUrl
        self.seedCookies = seedCookies
        self.probes = probes
    }

    func performCheck(_ snapshot: SessionChallengeCookieSnapshot) async throws -> any SessionChallengeCheckResult {
        enum Outcome {
            case result(any SessionChallengeCheckResult)
            case failure(Error)
        }
        let outcome: Outcome = lock.withLock {
            recordedSnapshots.append(snapshot)
            if let errorToThrow {
                return .failure(errorToThrow)
            }
            if nextResults.isEmpty {
                return .result(SessionChallengeCheckResultPending.shared)
            }
            return .result(nextResults.removeFirst())
        }
        switch outcome {
        case .result(let result):
            return result
        case .failure(let error):
            throw error
        }
    }

    @objc(checkSnapshot:completionHandler:)
    func __check(
        snapshot: SessionChallengeCookieSnapshot,
        completionHandler: @escaping @Sendable ((any SessionChallengeCheckResult)?, (any Error)?) -> Void
    ) {
        enum Outcome {
            case result(any SessionChallengeCheckResult)
            case failure(Error)
        }
        let outcome: Outcome = lock.withLock {
            recordedSnapshots.append(snapshot)
            if let errorToThrow {
                return .failure(errorToThrow)
            }
            if nextResults.isEmpty {
                return .result(SessionChallengeCheckResultPending.shared)
            }
            return .result(nextResults.removeFirst())
        }
        switch outcome {
        case .result(let result):
            completionHandler(result, nil)
        case .failure(let error):
            completionHandler(nil, error)
        }
    }

    func cancel() {
        lock.withLock { cancelCount += 1 }
    }

    func close() {
        lock.withLock { closeCount += 1 }
    }
}

@MainActor
private final class IOSTestSocksProxyServer {
    let listener: NWListener
    var port: UInt16 { listener.port?.rawValue ?? 0 }
    var requestedHosts: [String] = []
    private var connections: [NWConnection] = []

    init() async throws {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters)
        listener.newConnectionHandler = { [weak self] connection in
            Task { @MainActor in
                guard let self else { connection.cancel(); return }
                self.connections.append(connection)
                connection.start(queue: .main)
                self.negotiate(connection)
            }
        }
        listener.start(queue: .main)
        let deadline = Date().addingTimeInterval(5)
        while port == 0 && Date() < deadline {
            try await Task.sleep(for: .milliseconds(10))
        }
    }

    private func negotiate(_ connection: NWConnection) {
        read(2, from: connection) { prefix in
            self.read(Int(prefix[1]), from: connection) { _ in
                connection.send(content: Data([5, 0]), completion: .contentProcessed { _ in })
                self.read(4, from: connection) { request in
                    if request[3] == 3 {
                        self.read(1, from: connection) { length in
                            self.readAddress(connection, length: Int(length[0]), domain: true)
                        }
                    } else {
                        self.readAddress(connection, length: request[3] == 1 ? 4 : 16, domain: false)
                    }
                }
            }
        }
    }

    private func readAddress(_ connection: NWConnection, length: Int, domain: Bool) {
        read(length, from: connection) { address in
            if domain {
                self.requestedHosts.append(String(decoding: address, as: UTF8.self))
            }
            self.read(2, from: connection) { _ in
                connection.send(content: Data([5, 0, 0, 1, 0, 0, 0, 0, 0, 0]), completion: .contentProcessed { _ in })
                self.readHTTPAndRespond(connection)
            }
        }
    }

    private func readHTTPAndRespond(_ connection: NWConnection, buffered: Data = Data()) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { bytes, _, _, error in
            Task { @MainActor in
                guard error == nil, let bytes else { connection.cancel(); return }
                let data = buffered + bytes
                let text = String(decoding: data, as: UTF8.self)
                guard text.contains("\r\n\r\n") else {
                    self.readHTTPAndRespond(connection, buffered: data)
                    return
                }
                let body = "<html><body>ok</body></html>"
                let response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n\(body)"
                connection.send(content: Data(response.utf8), isComplete: true, completion: .contentProcessed { _ in })
            }
        }
    }

    private func read(_ count: Int, from connection: NWConnection, then: @escaping @MainActor @Sendable (Data) -> Void) {
        connection.receive(minimumIncompleteLength: count, maximumLength: count) { data, _, _, _ in
            Task { @MainActor in
                guard let data, data.count == count else { connection.cancel(); return }
                then(data)
            }
        }
    }

    func stop() {
        listener.cancel()
        connections.forEach { $0.cancel() }
    }
}

@MainActor
private final class DelayedIOSUAWKWebView: WKWebView {
    var evaluateCount = 0
    var resolvedUA = "FlareSlowIOSUA/2.0"
    private var pendingCompletions: [(Any?, Error?) -> Void] = []

    override func evaluateJavaScript(
        _ javaScriptString: String,
        completionHandler: (@MainActor @Sendable (Any?, (any Error)?) -> Void)? = nil
    ) {
        if javaScriptString == "navigator.userAgent" {
            evaluateCount += 1
            if let completionHandler {
                pendingCompletions.append(completionHandler)
            }
            return
        }
        super.evaluateJavaScript(javaScriptString, completionHandler: completionHandler)
    }

    func completePendingUserAgentEvaluations() {
        let completions = pendingCompletions
        pendingCompletions.removeAll()
        for completion in completions {
            completion(resolvedUA, nil)
        }
    }
}

private final class FakeIOSPopupNavigationAction: WKNavigationAction {
    private let mockRequest: URLRequest

    init(url: URL) {
        mockRequest = URLRequest(url: url)
        super.init()
    }

    override var request: URLRequest { mockRequest }
    override var targetFrame: WKFrameInfo? { nil }
}
