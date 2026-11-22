import AppKit
import FlareAppleCore
@testable import FlareAppleUI
import Foundation
import KotlinSharedUI
import Observation
import PDFKit
import SwiftUI
import XCTest

@MainActor
final class MangaScreenTests: XCTestCase {
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

    func testMacReaderRtlDoublePageScrubberTapZonesAndKeyboard() async throws {
        _ = NSApplication.shared
        let fakeState = FakeMacMangaReaderState(
            direction: .rtl,
            pages: makeMacSamplePages(count: 4),
            slots: [
                ReaderSlotDouble(leftPageIndex: 1, rightPageIndex: 0, primaryPageIndex: 0),
                ReaderSlotDouble(leftPageIndex: 3, rightPageIndex: 2, primaryPageIndex: 2),
            ]
        )
        let controller = MangaReaderInteractionController()
        let hostModel = MacReaderTestHostModel(state: fakeState, controller: controller)

        let window = NSWindow(
            contentRect: CGRect(x: 0, y: 0, width: 900, height: 640),
            styleMask: [.titled, .resizable],
            backing: .buffered,
            defer: false
        )
        window.isReleasedWhenClosed = false
        let hostingView = NSHostingView(rootView: MacReaderTestHostView(model: hostModel))
        hostingView.sizingOptions = []
        window.contentView = hostingView
        window.makeKeyAndOrderFront(nil)
        defer { window.close() }

        try await eventually(window: window) {
            fakeState.lastWideViewport == true &&
                hostModel.pageFrames[0] != nil &&
                hostModel.pageFrames[1] != nil &&
                hostModel.scrubberLeadingLabelFrame != nil &&
                hostModel.scrubberTrailingLabelFrame != nil
        }

        // 1. RTL double-page slot places earlier page (0) on physical right and page (1) on physical left
        let page0Frame = try XCTUnwrap(hostModel.pageFrames[0])
        let page1Frame = try XCTUnwrap(hostModel.pageFrames[1])
        XCTAssertLessThan(
            page1Frame.minX,
            page0Frame.minX,
            "Expected leftPageIndex (1) on physical left (\(page1Frame)) of rightPageIndex (0) (\(page0Frame))"
        )
        XCTAssertLessThanOrEqual(page1Frame.maxX, page0Frame.minX + 1.5)

        // 2. Scrubber mirrors direction in RTL
        let rtlCurrentLabel = try XCTUnwrap(hostModel.scrubberLeadingLabelFrame)
        let rtlTotalLabel = try XCTUnwrap(hostModel.scrubberTrailingLabelFrame)
        XCTAssertGreaterThan(rtlCurrentLabel.minX, rtlTotalLabel.minX)

        // 3. Tap zones (30% / 40% / 30%) in RTL
        controller.handleTap(at: CGPoint(x: 135, y: 320), in: CGSize(width: 900, height: 640))
        XCTAssertEqual(fakeState.stepForwardCount, 1)
        XCTAssertEqual(fakeState.currentSlotIndex, 1)

        controller.handleTap(at: CGPoint(x: 765, y: 320), in: CGSize(width: 900, height: 640))
        XCTAssertEqual(fakeState.stepBackwardCount, 1)
        XCTAssertEqual(fakeState.currentSlotIndex, 0)

        XCTAssertTrue(hostModel.hudVisible)
        controller.handleTap(at: CGPoint(x: 450, y: 320), in: CGSize(width: 900, height: 640))
        try await eventually(window: window) { !hostModel.hudVisible }

        // Restore HUD so scrubber bar is visible again
        controller.handleTap(at: CGPoint(x: 450, y: 320), in: CGSize(width: 900, height: 640))
        try await eventually(window: window) { hostModel.hudVisible }

        // 4. Hardware keyboard arrow events in RTL vs LTR
        XCTAssertTrue(controller.handleKey(.leftArrow))
        XCTAssertEqual(fakeState.stepForwardCount, 2)
        XCTAssertEqual(fakeState.currentSlotIndex, 1)

        fakeState.setDirection(direction: .ltr)
        try await eventually(window: window) {
            guard let leading = hostModel.scrubberLeadingLabelFrame,
                  let trailing = hostModel.scrubberTrailingLabelFrame else { return false }
            return leading.minX < trailing.minX
        }
        XCTAssertTrue(controller.handleKey(.leftArrow))
        XCTAssertEqual(fakeState.stepBackwardCount, 2)
        XCTAssertEqual(fakeState.currentSlotIndex, 0)

        // 5. Viewport < 720 updates setWideViewport(false) and hides spread toggles
        window.setContentSize(NSSize(width: 560, height: 640))
        try await eventually(window: window) { fakeState.lastWideViewport == false }

        let spreadLabel = FlareAppleUILocalization.string(
            "manga_reader_double_page_spread",
            fallback: "Double-page spread"
        ).precomposedStringWithCompatibilityMapping
        let singleCoverLabel = FlareAppleUILocalization.string(
            "manga_reader_spread_cover_single",
            fallback: "Single cover page"
        ).precomposedStringWithCompatibilityMapping

        let wideSettingsText = try renderPDFText(MangaReaderSettingsView(state: fakeState, isWideViewport: true), width: 360, height: 300)
            .precomposedStringWithCompatibilityMapping
        XCTAssertTrue(wideSettingsText.contains(spreadLabel), wideSettingsText)

        let narrowSettingsText = try renderPDFText(MangaReaderSettingsView(state: fakeState, isWideViewport: false), width: 360, height: 300)
            .precomposedStringWithCompatibilityMapping
        XCTAssertFalse(narrowSettingsText.contains(spreadLabel), narrowSettingsText)
        XCTAssertFalse(narrowSettingsText.contains(singleCoverLabel), narrowSettingsText)
    }

    func testMacBoundarySteppingAndMangaDetailRendering() async throws {
        _ = NSApplication.shared
        let prevChapter = UiMangaChapter(id: "ch-0", title: "Chapter 0", subtitle: nil, uploadedAt: nil, locked: false, url: nil)
        let nextChapter = UiMangaChapter(id: "ch-2", title: "Chapter 2", subtitle: nil, uploadedAt: nil, locked: false, url: nil)
        let fakeState = FakeMacMangaReaderState(
            direction: .ltr,
            pages: makeMacSamplePages(count: 2),
            slots: [ReaderSlotSingle(pageIndex: 0), ReaderSlotSingle(pageIndex: 1)],
            previousBoundary: ChapterBoundaryUnlocked(chapter: prevChapter, startFromEnd: true),
            nextBoundary: ChapterBoundaryUnlocked(chapter: nextChapter, startFromEnd: false)
        )
        let controller = MangaReaderInteractionController()
        let hostModel = MacReaderTestHostModel(state: fakeState, controller: controller)

        let window = NSWindow(
            contentRect: CGRect(x: 0, y: 0, width: 800, height: 600),
            styleMask: [.titled],
            backing: .buffered,
            defer: false
        )
        window.isReleasedWhenClosed = false
        let readerHosting = NSHostingView(rootView: MacReaderTestHostView(model: hostModel))
        readerHosting.sizingOptions = []
        window.contentView = readerHosting
        window.makeKeyAndOrderFront(nil)
        defer { window.close() }

        try await eventually(window: window) { controller.isBound && hostModel.pageFrames[0] != nil }
        try await Task.sleep(for: .milliseconds(60))
        window.contentView?.layoutSubtreeIfNeeded()

        // Manual horizontal scroll after initial mount with previousBoundary (initial == 1 > 0) must advance slot 0 -> slot 1 without yanking back
        let boundaryScrollView = try XCTUnwrap(findHorizontalPagingScrollView(in: try XCTUnwrap(window.contentView)))
        simulateMacUserHorizontalScroll(boundaryScrollView, by: 800)
        try await eventually(window: window) {
            fakeState.currentSlotIndex == 1 && controller.activePagerPage == 2
        }
        try await Task.sleep(for: .milliseconds(60))
        window.contentView?.layoutSubtreeIfNeeded()
        XCTAssertEqual(fakeState.currentSlotIndex, 1)
        XCTAssertEqual(controller.activePagerPage, 2)

        // Redundant scrollToBoundary when activePagerPage already equals clamped must not leave programmaticScrollTarget armed
        controller.scrollToBoundary(isNext: true)
        try await eventually(window: window) {
            controller.isOnNextBoundaryPage && boundaryScrollView.contentView.bounds.origin.x >= 2390
        }
        controller.scrollToBoundary(isNext: true)
        try await Task.sleep(for: .milliseconds(30))
        window.contentView?.layoutSubtreeIfNeeded()
        window.contentView?.displayIfNeeded()
        XCTAssertTrue(controller.isOnNextBoundaryPage)
        simulateMacUserHorizontalScroll(boundaryScrollView, by: -800)
        try await eventually(window: window) {
            controller.activePagerPage == 2 && !controller.isOnNextBoundaryPage
        }

        controller.scrollToBoundary(isNext: true)
        XCTAssertTrue(controller.isOnNextBoundaryPage)
        XCTAssertTrue(controller.handleKey(.leftArrow))
        XCTAssertFalse(controller.isOnNextBoundaryPage)
        XCTAssertEqual(fakeState.currentSlotIndex, 1)

        controller.scrollToBoundary(isNext: true)
        XCTAssertTrue(controller.handleKey(.rightArrow))
        XCTAssertEqual(fakeState.openedBoundaryChapterId, "ch-2")

        // Ready page load failure shows retry UI
        let missingURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("missing-mac-manga-\(UUID().uuidString).png")
            .absoluteString
        let brokenMedia = UiMediaImage(
            url: missingURL,
            previewUrl: missingURL,
            description: nil,
            height: 140,
            width: 100,
            sensitive: false,
            customHeaders: ["Referer": "https://manga.example/ch/1"],
            proxy: ProxyRef.Unbound.shared
        )
        let flakyState = FakeMacMangaReaderState(
            direction: .ltr,
            pages: [
                ReaderPageState(
                    id: "mac-flaky-p0",
                    index: 0,
                    status: PageMediaStatusReady(original: brokenMedia, displayed: brokenMedia),
                    aspectRatio: KotlinFloat(value: 0.71)
                ),
            ],
            slots: [ReaderSlotSingle(pageIndex: 0)]
        )
        hostModel.setState(flakyState)
        try await eventually(window: window) {
            hostModel.retryPageIds.contains("mac-flaky-p0")
        }

        // MangaDetailContentView split 2-pane layout + grouped tags, chapter pills, read-state & locked fallback
        let mangaKey = MicroBlogKey(id: "manga-99", host: "manga.example")
        let accountType = AccountType.Specific(accountKey: MicroBlogKey(id: "user-1", host: "manga.example"))
        let ch0 = UiMangaChapter(id: "ch-0", title: "Chapter 0: Prologue", subtitle: "Vol. 1", uploadedAt: nil, locked: false, url: nil)
        let ch1 = UiMangaChapter(id: "ch-1", title: "Chapter 1: Dawn", subtitle: "Vol. 1", uploadedAt: nil, locked: false, url: nil)
        let ch2Locked = UiMangaChapter(id: "ch-2", title: "Chapter 2: Locked Extra", subtitle: nil, uploadedAt: nil, locked: true, url: nil)

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
            content: nil,
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

        XCTAssertEqual(MangaDetailFormatting.chapterTitleEmphasis(for: chapterItems[0]), .muted)
        XCTAssertEqual(MangaDetailFormatting.chapterTitleEmphasis(for: chapterItems[1]), .current)
        XCTAssertEqual(MangaDetailFormatting.chapterTitleEmphasis(for: chapterItems[2]), .muted)
        XCTAssertEqual(
            MangaDetailFormatting.resolveLockedChapterURL(chapter: ch2Locked, detailURL: detail.url),
            "https://manga.example/series/99"
        )

        // Verify MangaDetailContentView split 2-pane geometry in NSWindow (metadata pane on left, chapters pane on right)
        var detailFrames = MangaDetailPaneFrames()
        let detailWindow = NSWindow(
            contentRect: CGRect(x: 0, y: 0, width: 960, height: 720),
            styleMask: [.titled],
            backing: .buffered,
            defer: false
        )
        detailWindow.isReleasedWhenClosed = false
        let primaryTarget = PrimaryReadTarget(
            chapterId: "ch-1",
            chapterTitle: "Chapter 1: Dawn",
            initialPage: 4,
            isContinue: true,
            isSingleChapterWork: false
        )
        let detailHosting = NSHostingView(
            rootView: MangaDetailContentView(
                detail: UiStateSuccess(data: detail),
                selectedGroupId: "main",
                chapterSortDescending: false,
                chapters: chapterItems,
                primaryReadTarget: primaryTarget,
                onNavigate: { _ in }
            )
            .onPreferenceChange(MangaDetailPaneFramesPreferenceKey.self) { detailFrames = $0 }
        )
        detailHosting.sizingOptions = []
        detailWindow.contentView = detailHosting
        detailWindow.makeKeyAndOrderFront(nil)
        defer { detailWindow.close() }

        try await eventually(window: detailWindow) {
            detailFrames.heroCard != nil && detailFrames.chaptersSection != nil
        }
        let heroFrame = try XCTUnwrap(detailFrames.heroCard)
        let chaptersFrame = try XCTUnwrap(detailFrames.chaptersSection)
        XCTAssertLessThan(
            heroFrame.maxX,
            chaptersFrame.minX,
            "Expected split 2-pane layout on macOS with hero metadata (\(heroFrame)) to the left of chapters (\(chaptersFrame))"
        )

        // Verify vector text of shared detail components (hero card, grouped tags, chapter group pills, chapter rows)
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
        let renderedText = try renderPDFText(detailStack, width: 640, height: 900)
        XCTAssertTrue(renderedText.contains("Starlight Odyssey"), renderedText)
        XCTAssertTrue(renderedText.contains("Genre"), renderedText)
        XCTAssertTrue(renderedText.contains("Sci-Fi"), renderedText)
        XCTAssertTrue(renderedText.contains("Adventure"), renderedText)
        XCTAssertTrue(renderedText.contains("Main Story"), renderedText)
        XCTAssertTrue(renderedText.contains("Side Stories"), renderedText)
        XCTAssertTrue(renderedText.contains("Chapter 0: Prologue"), renderedText)
        XCTAssertTrue(renderedText.contains("Chapter 1: Dawn"), renderedText)
        XCTAssertTrue(renderedText.contains("Chapter 2: Locked Extra"), renderedText)
    }

    func testMacMidChapterRtlLtrSwitchOnFourSlotsAndWebtoonReachability() async throws {
        _ = NSApplication.shared
        let pages = makeMacSamplePages(count: 4)
        let slots: [any ReaderSlot] = (0..<4).map { ReaderSlotSingle(pageIndex: Int32($0)) }
        let fakeState = FakeMacMangaReaderState(
            direction: .rtl,
            pages: pages,
            slots: slots
        )
        let controller = MangaReaderInteractionController()
        let hostModel = MacReaderTestHostModel(state: fakeState, controller: controller)

        let window = NSWindow(
            contentRect: CGRect(x: 0, y: 0, width: 800, height: 600),
            styleMask: [.titled],
            backing: .buffered,
            defer: false
        )
        window.isReleasedWhenClosed = false
        let hostingView = NSHostingView(rootView: MacReaderTestHostView(model: hostModel))
        hostingView.sizingOptions = []
        window.contentView = hostingView
        window.makeKeyAndOrderFront(nil)
        defer { window.close() }

        try await eventually(window: window) { controller.isBound && hostModel.pageFrames[0] != nil }
        // Let .task finish initial positioning so we exercise post-.task manual scroll behavior
        try await Task.sleep(for: .milliseconds(60))
        window.contentView?.layoutSubtreeIfNeeded()

        // 1. Simulated manual horizontal user scroll after initial mount (slot 0 -> slot 1 in RTL) must NOT yank back
        let initialScrollView = try XCTUnwrap(findHorizontalPagingScrollView(in: try XCTUnwrap(window.contentView)))
        simulateMacUserHorizontalScroll(initialScrollView, by: -800)
        try await eventually(window: window) {
            fakeState.currentSlotIndex == 1 && controller.activePagerPage == 1
        }
        try await Task.sleep(for: .milliseconds(60))
        window.contentView?.layoutSubtreeIfNeeded()
        XCTAssertEqual(fakeState.currentSlotIndex, 1, "Manual scroll after initial mount must update currentSlotIndex without yanking back")
        XCTAssertEqual(controller.activePagerPage, 1)

        // Advance one more slot to slot 2 (out of 0..3) in RTL
        XCTAssertTrue(controller.handleKey(.leftArrow))
        XCTAssertEqual(fakeState.currentSlotIndex, 2)
        try await eventually(window: window) {
            guard let frame = hostModel.pageFrames[2] else { return false }
            return frame.minX >= -1.5 && frame.maxX <= 801.5
        }

        // 2. Switch RTL -> LTR mid-chapter on slot 2: must NOT clobber currentSlotIndex to mirrored page (3 - 2 = 1)
        fakeState.setDirection(direction: .ltr)
        try await Task.sleep(for: .milliseconds(80))
        window.contentView?.layoutSubtreeIfNeeded()
        try await eventually(window: window) {
            guard let frame = hostModel.pageFrames[2] else { return false }
            return controller.activePagerPage == 2 && frame.minX >= -1.5 && frame.maxX <= 801.5
        }
        XCTAssertEqual(fakeState.currentSlotIndex, 2, "Switching RTL -> LTR mid-chapter must preserve currentSlotIndex == 2")
        XCTAssertNil(controller.zoomResetHandler)

        // 3. Simulated manual horizontal user scroll after RTL -> LTR switch (slot 2 -> slot 3 in LTR) must NOT yank back
        let ltrScrollView = try XCTUnwrap(findHorizontalPagingScrollView(in: try XCTUnwrap(window.contentView)))
        simulateMacUserHorizontalScroll(ltrScrollView, by: 800)
        try await eventually(window: window) {
            fakeState.currentSlotIndex == 3 && controller.activePagerPage == 3
        }
        try await Task.sleep(for: .milliseconds(60))
        window.contentView?.layoutSubtreeIfNeeded()
        XCTAssertEqual(fakeState.currentSlotIndex, 3, "Manual scroll after RTL -> LTR switch must not be yanked back")
        XCTAssertEqual(controller.activePagerPage, 3)

        // Step backward twice in LTR (slot 3 -> slot 1)
        XCTAssertTrue(controller.handleKey(.leftArrow))
        XCTAssertTrue(controller.handleKey(.leftArrow))
        XCTAssertEqual(fakeState.currentSlotIndex, 1)

        // 4. Switch LTR -> RTL mid-chapter on slot 1: must NOT clobber currentSlotIndex to mirrored page (3 - 1 = 2)
        fakeState.setDirection(direction: .rtl)
        try await Task.sleep(for: .milliseconds(80))
        window.contentView?.layoutSubtreeIfNeeded()
        try await eventually(window: window) {
            guard let frame = hostModel.pageFrames[1] else { return false }
            return controller.activePagerPage == 1 && frame.minX >= -1.5 && frame.maxX <= 801.5
        }
        XCTAssertEqual(fakeState.currentSlotIndex, 1, "Switching LTR -> RTL mid-chapter must preserve currentSlotIndex == 1")
        XCTAssertNil(controller.zoomResetHandler)

        // 5. Simulated manual horizontal user scroll after LTR -> RTL switch (slot 1 -> slot 2 in RTL) must NOT yank back
        let rtlScrollView = try XCTUnwrap(findHorizontalPagingScrollView(in: try XCTUnwrap(window.contentView)))
        simulateMacUserHorizontalScroll(rtlScrollView, by: -800)
        try await eventually(window: window) {
            fakeState.currentSlotIndex == 2 && controller.activePagerPage == 2
        }
        try await Task.sleep(for: .milliseconds(60))
        window.contentView?.layoutSubtreeIfNeeded()
        XCTAssertEqual(fakeState.currentSlotIndex, 2, "Manual scroll after LTR -> RTL switch must not be yanked back")
        XCTAssertEqual(controller.activePagerPage, 2)

        // Webtoon mode: unscaled 1x strip width and full bottom page reachability
        let webtoonPages = makeMacSamplePages(count: 6)
        let webtoonSlots: [any ReaderSlot] = (0..<6).map { ReaderSlotSingle(pageIndex: Int32($0)) }
        let webtoonState = FakeMacMangaReaderState(
            direction: .webtoon,
            pages: webtoonPages,
            slots: webtoonSlots
        )
        hostModel.pageFrames.removeAll()
        hostModel.setState(webtoonState)
        try await eventually(window: window) { hostModel.pageFrames[0] != nil }
        XCTAssertEqual(try XCTUnwrap(hostModel.pageFrames[0]).width, 800, accuracy: 1.5)
        XCTAssertNil(controller.zoomResetHandler)

        webtoonState.goToPage(pageIndex: 5)
        try await eventually(window: window) {
            guard let page5Frame = hostModel.pageFrames[5] else { return false }
            return page5Frame.minY < 600 && page5Frame.maxY > 0 && webtoonState.currentPageIndex == 5
        }
        XCTAssertEqual(try XCTUnwrap(hostModel.pageFrames[5]).width, 800, accuracy: 1.5)
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

    private func findHorizontalPagingScrollView(in root: NSView) -> NSScrollView? {
        if let scrollView = root as? NSScrollView,
           scrollView.contentView.bounds.width >= 300,
           (scrollView.documentView?.frame.width ?? 0) > scrollView.contentView.bounds.width + 10 {
            return scrollView
        }
        for subview in root.subviews {
            if let found = findHorizontalPagingScrollView(in: subview) {
                return found
            }
        }
        return nil
    }

    private func simulateMacUserHorizontalScroll(_ scrollView: NSScrollView, by deltaX: CGFloat) {
        let docWidth = scrollView.documentView?.frame.width ?? 0
        let maxX = max(docWidth - scrollView.contentView.bounds.width, 0)
        let nextX = min(max(scrollView.contentView.bounds.origin.x + deltaX, 0), maxX)
        NotificationCenter.default.post(name: NSScrollView.willStartLiveScrollNotification, object: scrollView)
        scrollView.contentView.scroll(to: CGPoint(x: nextX, y: 0))
        scrollView.reflectScrolledClipView(scrollView.contentView)
        NotificationCenter.default.post(name: NSScrollView.didLiveScrollNotification, object: scrollView)
        NotificationCenter.default.post(name: NSScrollView.didEndLiveScrollNotification, object: scrollView)
    }

    private func eventually(
        window: NSWindow,
        _ condition: () -> Bool,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async throws {
        let deadline = Date().addingTimeInterval(5)
        while !condition(), Date() < deadline {
            window.contentView?.layoutSubtreeIfNeeded()
            window.contentView?.displayIfNeeded()
            try await Task.sleep(for: .milliseconds(15))
        }
        XCTAssertTrue(condition(), file: file, line: line)
    }
}

@MainActor
@Observable
private final class MacReaderTestHostModel {
    var snapshot: any MangaReaderPresenterState
    let controller: MangaReaderInteractionController
    var hudVisible = true
    var pageFrames: [Int: CGRect] = [:]
    var retryPageIds: Set<String> = []
    var scrubberLeadingLabelFrame: CGRect?
    var scrubberTrailingLabelFrame: CGRect?

    init(
        state: FakeMacMangaReaderState,
        controller: MangaReaderInteractionController
    ) {
        self.snapshot = MacMangaStateSnapshot(backing: state)
        self.controller = controller
        state.onStateMutated = { [weak self, weak state] in
            guard let self, let state else { return }
            self.snapshot = MacMangaStateSnapshot(backing: state)
        }
    }

    func setState(_ next: FakeMacMangaReaderState) {
        retryPageIds.removeAll()
        snapshot = MacMangaStateSnapshot(backing: next)
        next.onStateMutated = { [weak self, weak next] in
            guard let self, let next else { return }
            self.snapshot = MacMangaStateSnapshot(backing: next)
        }
    }
}

@MainActor
private final class MacMangaStateSnapshot: NSObject, MangaReaderPresenterState {
    private let backing: FakeMacMangaReaderState

    init(backing: FakeMacMangaReaderState) {
        self.backing = backing
    }

    var mangaTitle: String { backing.mangaTitle }
    var chapterTitle: String { backing.chapterTitle }
    var chapterUrl: String? { backing.chapterUrl }
    var activeChapterId: String { backing.activeChapterId }
    var loadState: UiState<KotlinUnit> { backing.loadState }
    var pages: [ReaderPageState] { backing.pages }
    var slots: [any ReaderSlot] { backing.slots }
    var currentPageIndex: Int32 { backing.currentPageIndex }
    var currentSlotIndex: Int32 { backing.currentSlotIndex }
    var direction: MangaReadingDirection { backing.direction }
    var doublePageSpread: Bool { backing.doublePageSpread }
    var spreadCoverSinglePage: Bool { backing.spreadCoverSinglePage }
    var previousBoundary: any ChapterBoundary { backing.previousBoundary }
    var nextBoundary: any ChapterBoundary { backing.nextBoundary }
    var prefetchMedia: [UiMediaImage] { backing.prefetchMedia }
    var imageTranslation: any ImageTranslationPresenterState { backing.imageTranslation }
    var lockedChapter: ChapterBoundaryLocked? { backing.lockedChapter }

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

@MainActor
private struct MacReaderTestHostView: View {
    @Bindable var model: MacReaderTestHostModel

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
private func makeMacSamplePages(count: Int) -> [ReaderPageState] {
    (0..<count).map { index in
        let fileURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("mac-manga-test-page-\(index).png")
        if !FileManager.default.fileExists(atPath: fileURL.path) {
            let image = NSImage(size: NSSize(width: 100, height: 140))
            image.lockFocus()
            NSColor.darkGray.setFill()
            NSRect(x: 0, y: 0, width: 100, height: 140).fill()
            image.unlockFocus()
            if let tiff = image.tiffRepresentation,
               let rep = NSBitmapImageRep(data: tiff),
               let png = rep.representation(using: .png, properties: [:]) {
                try? png.write(to: fileURL)
            }
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
private final class FakeMacMangaReaderState: NSObject, MangaReaderPresenterState {
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
    let imageTranslation: any ImageTranslationPresenterState = NoopMacImageTranslationState()
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
        currentPageIndex = slots[clamped].primaryPageIndex
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
}

@MainActor
private final class NoopMacImageTranslationState: NSObject, ImageTranslationPresenterState {
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
