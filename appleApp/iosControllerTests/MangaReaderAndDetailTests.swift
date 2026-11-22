import FlareAppleCore
@testable import FlareAppleUI
import Foundation
import Kingfisher
import KotlinSharedUI
import Observation
import PDFKit
import SwiftUI
import UIKit
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
        let webtoonHeight = webtoonSettingsHost.sizeThatFits(in: CGSize(width: 360, height: 600)).height
        XCTAssertLessThan(narrowHeight, wideHeight - 40)
        XCTAssertEqual(narrowHeight, webtoonHeight, accuracy: 1.0)

        window.rootViewController = wideSettingsHost
        try await eventually(window: window) {
            self.countSubviews(ofType: UISwitch.self, in: wideSettingsHost.view) == 2
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
        await simulateUserHorizontalScroll(boundaryScrollView, pageDelta: 1)
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
        await simulateUserHorizontalScroll(boundaryScrollView, pageDelta: -1)
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
            hostBox.retryPageIds.contains("flaky-p0")
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
        await simulateUserHorizontalScroll(initialScrollView, pageDelta: -1)
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
        await simulateUserHorizontalScroll(ltrScrollView, pageDelta: 1)
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
        await simulateUserHorizontalScroll(rtlScrollView, pageDelta: -1)
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
        await simulateUserHorizontalScroll(postReflowScrollView, pageDelta: -1)
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
        initialScrollView.bounds.origin = CGPoint(x: 2000, y: 0) // halfway between slot 1 (2400) and slot 2 (1600) in RTL
        initialScrollView.layoutIfNeeded()
        XCTAssertTrue(controller.hasUnconsumedUserScroll)

        // Trigger spread split mid-drag in RTL (slot 2 Double(4,3) -> Single(3), Single(4))
        fakeState.updateSlots(fiveSplitSlots)
        window.layoutIfNeeded()
        XCTAssertEqual(ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: window))), initialScrollViewID)
        XCTAssertEqual(initialScrollView.contentSize.width, 4800, accuracy: 2.0, "Displayed slots must stay frozen during active RTL swipe")
        XCTAssertEqual(initialScrollView.contentOffset.x, 2000, accuracy: 2.0, "Mid-drag RTL spread split must not shift or yank active swipe")

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
        let gifURL = "https://manga.example/ch-1/page-\(UUID().uuidString).gif"
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
        try await eventually(window: window) { shareVisibleForGif }

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
        let minimalGifBytes = Data([
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80, 0x00, 0x00,
            0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3B,
        ])
        try await KingfisherManager.shared.cache.storeToDisk(minimalGifBytes, forKey: gifMedia.cacheKey)
        defer {
            Task { try? await KingfisherManager.shared.cache.removeImage(forKey: gifMedia.cacheKey) }
        }

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

    private func simulateUserHorizontalScroll(_ scrollView: UIScrollView, pageDelta: Int) async {
        let pageWidth = max(scrollView.bounds.width, 1)
        let maxX = max(scrollView.contentSize.width - pageWidth, 0)
        let nextX = min(max(scrollView.contentOffset.x + CGFloat(pageDelta) * pageWidth, 0), maxX)
        scrollView.delegate?.scrollViewWillBeginDragging?(scrollView)
        scrollView.bounds.origin = CGPoint(x: nextX, y: 0)
        scrollView.layoutIfNeeded()
        try? await Task.sleep(for: .milliseconds(25))
        var target = CGPoint(x: nextX, y: 0)
        scrollView.delegate?.scrollViewWillEndDragging?(scrollView, withVelocity: .zero, targetContentOffset: &target)
        scrollView.delegate?.scrollViewDidEndDragging?(scrollView, willDecelerate: false)
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
    func setDirection(direction: MangaReadingDirection) { backing.setDirection(direction: direction) }
    func setDoublePageSpread(enabled: Bool) { backing.setDoublePageSpread(enabled: enabled) }
    func setSpreadCoverSinglePage(enabled: Bool) { backing.setSpreadCoverSinglePage(enabled: enabled) }
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
    let imageTranslation: any ImageTranslationPresenterState = NoopImageTranslationState()
    var lockedChapter: ChapterBoundaryLocked? { nil }

    var lastWideViewport: Bool?
    var stepForwardCount = 0
    var stepBackwardCount = 0
    var openedBoundaryChapterId: String?
    var retriedPageIds: [String] = []

    init(
        direction: MangaReadingDirection,
        pages: [ReaderPageState],
        slots: [any ReaderSlot],
        previousBoundary: any ChapterBoundary = ChapterBoundaryNone.shared,
        nextBoundary: any ChapterBoundary = ChapterBoundaryNone.shared
    ) {
        self.direction = direction
        self.pages = pages
        self.slots = slots
        self.doublePageSpread = slots.contains { $0 is ReaderSlotDouble }
        self.previousBoundary = previousBoundary
        self.nextBoundary = nextBoundary
    }

    var currentSlotIndex: Int32 {
        Int32(MangaSpreadLayoutSolver.shared.slotIndexForPage(slots: slots, pageIndex: currentPageIndex))
    }

    func setWideViewport(isWide: Bool) {
        lastWideViewport = isWide
    }

    func onPageDimensionsLoaded(pageId: String, width: Float, height: Float) {}

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

    func retryChapter() {}

    func retryPage(pageId: String) {
        retriedPageIds.append(pageId)
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

    func canRegenerate(media: any UiMedia) -> Bool { false }
    func canToggle(media: any UiMedia) -> Bool { false }
    func clearError(media: any UiMedia) {}
    func displayed(media: any UiMedia) -> any UiMedia { media }
    func imageFailed(media: any UiMedia, url: String) {}
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
