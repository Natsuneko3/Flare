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
    private static let servicesReady: Void = {
        AppleSharedHelper.shared.initialize(
            inAppNotification: SilentMacNotification(),
            swiftFormatter: Formatter.shared,
            swiftPlatformTextRenderer: PlatformTextRenderer.shared,
            swiftOnDeviceAI: FoundationModelOnDeviceAI.shared
        )
    }()

    override func setUp() async throws {
        try await super.setUp()
        _ = Self.servicesReady
    }

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
            content: makeMacRichSynopsis(["A journey across star seas."]),
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
        let showMoreLabel = FlareAppleUILocalization.string("mastodon_item_show_more", fallback: "Show more")
        let addBookmarkLabel = FlareAppleUILocalization.string("bookmark_add", fallback: "Add bookmark")
        XCTAssertTrue(renderedText.contains("Starlight Odyssey"), renderedText)
        XCTAssertTrue(renderedText.contains("A journey across star seas."), renderedText)
        XCTAssertFalse(renderedText.contains(showMoreLabel), "Short <= 4 line synopsis must not render expand toggle")
        XCTAssertFalse(renderedText.contains(addBookmarkLabel), "Read-only/guest detail with Noop bookmarkAction must not render standalone bookmark")
        XCTAssertTrue(renderedText.contains("Genre"), renderedText)
        XCTAssertTrue(renderedText.contains("Sci-Fi"), renderedText)
        XCTAssertTrue(renderedText.contains("Adventure"), renderedText)
        XCTAssertTrue(renderedText.contains("Main Story"), renderedText)
        XCTAssertTrue(renderedText.contains("Side Stories"), renderedText)
        XCTAssertTrue(renderedText.contains("Chapter 0: Prologue"), renderedText)
        XCTAssertTrue(renderedText.contains("Chapter 1: Dawn"), renderedText)
        XCTAssertTrue(renderedText.contains("Chapter 2: Locked Extra"), renderedText)

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
            accountType: AccountType.GuestPlugin(platformId: "manga-plat", host: "manga.example"),
            title: detail.title,
            altTitles: detail.altTitles,
            cover: nil,
            author: nil,
            authors: detail.authors,
            artists: detail.artists,
            status: detail.status,
            content: makeMacRichSynopsis((1...7).map { "Paragraph \($0): Long star-sea chronicle across uncharted galaxies." }),
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
        let guestHeroNoReadTarget = NSHostingView(
            rootView: MangaHeroCardView(
                detail: detail,
                translation: nil,
                primaryReadTarget: nil,
                onOpenCover: { _ in },
                onOpenProfile: { _, _ in },
                onOpenReader: { _, _ in }
            )
        )
        guestHeroNoReadTarget.frame = CGRect(x: 0, y: 0, width: 480, height: 240)
        guestHeroNoReadTarget.layoutSubtreeIfNeeded()
        XCTAssertEqual(
            countMacSubviews(ofType: NSButton.self, in: guestHeroNoReadTarget),
            0,
            "Read-only/guest hero card with empty actions and ClickEventNoop bookmarkAction must render zero NSButtons when primaryReadTarget is nil"
        )
        let longSynopsisRendered = try renderPDFText(
            MangaSynopsisAndTagsCardView(detail: longSynopsisDetail),
            width: 480,
            height: 500
        )
        XCTAssertTrue(longSynopsisRendered.contains(showMoreLabel), "Multi-paragraph > 4 line synopsis must render expand toggle")
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
        XCTAssertNotNil(controller.doubleTapZoomHandler, "Switching RTL -> LTR must preserve doubleTapZoomHandler on active slot")

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
        XCTAssertNotNil(controller.doubleTapZoomHandler, "Switching LTR -> RTL must preserve doubleTapZoomHandler on active slot")

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

        // 6. RTL totalPagerPages growing (4 -> 5), shrinking (5 -> 4), and idle viewport resize must preserve currentSlotIndex
        let sixPages = makeMacSamplePages(count: 6)
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
        window.contentView?.layoutSubtreeIfNeeded()
        let rtlScrollViewBeforeReflow = try XCTUnwrap(findHorizontalPagingScrollView(in: try XCTUnwrap(window.contentView)))
        let rtlScrollViewID = ObjectIdentifier(rtlScrollViewBeforeReflow)

        // Grow totalPagerPages 4 -> 5 in RTL while on slot 1 (simulating runtime landscape page split)
        fakeState.updateSlots(fiveSplitSlots)
        try await Task.sleep(for: .milliseconds(80))
        window.contentView?.layoutSubtreeIfNeeded()
        try await eventually(window: window) {
            guard let frame = hostModel.pageFrames[1] else { return false }
            return controller.activePagerPage == 1 && frame.minX >= -1.5 && frame.maxX <= 801.5
        }
        XCTAssertEqual(fakeState.currentSlotIndex, 1, "Growing totalPagerPages in RTL must preserve currentSlotIndex == 1")
        XCTAssertEqual(
            ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: try XCTUnwrap(window.contentView)))),
            rtlScrollViewID,
            "Growing totalPagerPages must not tear down NSScrollView"
        )

        // Shrink totalPagerPages 5 -> 4 in RTL while on slot 1 (simulating re-pairing / spread toggle)
        fakeState.updateSlots(fourSpreadSlots)
        try await Task.sleep(for: .milliseconds(80))
        window.contentView?.layoutSubtreeIfNeeded()
        try await eventually(window: window) {
            guard let frame = hostModel.pageFrames[1] else { return false }
            return controller.activePagerPage == 1 && frame.minX >= -1.5 && frame.maxX <= 801.5
        }
        XCTAssertEqual(fakeState.currentSlotIndex, 1, "Shrinking totalPagerPages in RTL must preserve currentSlotIndex == 1")
        XCTAssertEqual(
            ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: try XCTUnwrap(window.contentView)))),
            rtlScrollViewID,
            "Shrinking totalPagerPages must not tear down NSScrollView"
        )

        // Resize viewport width while idle in RTL (800 -> 520 -> 800) must preserve currentSlotIndex == 1 and NSScrollView identity
        window.setContentSize(NSSize(width: 520, height: 600))
        try await Task.sleep(for: .milliseconds(80))
        window.contentView?.layoutSubtreeIfNeeded()
        try await eventually(window: window) {
            guard let frame = hostModel.pageFrames[1] else { return false }
            return controller.activePagerPage == 1 && frame.minX >= -1.5 && frame.maxX <= 521.5
        }
        XCTAssertEqual(fakeState.currentSlotIndex, 1, "Idle viewport narrowing in RTL must preserve currentSlotIndex == 1")
        XCTAssertEqual(
            ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: try XCTUnwrap(window.contentView)))),
            rtlScrollViewID,
            "Narrowing NSWindow width must not tear down NSScrollView"
        )

        window.setContentSize(NSSize(width: 800, height: 600))
        try await Task.sleep(for: .milliseconds(80))
        window.contentView?.layoutSubtreeIfNeeded()
        try await eventually(window: window) {
            guard let frame = hostModel.pageFrames[1] else { return false }
            return controller.activePagerPage == 1 && frame.minX >= -1.5 && frame.maxX <= 801.5
        }
        XCTAssertEqual(fakeState.currentSlotIndex, 1, "Idle viewport widening in RTL must preserve currentSlotIndex == 1")
        XCTAssertEqual(
            ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: try XCTUnwrap(window.contentView)))),
            rtlScrollViewID,
            "Widening NSWindow width must not tear down NSScrollView"
        )

        // Manual user scroll after RTL reflows must still advance cleanly without yanking back
        let postReflowScrollView = try XCTUnwrap(findHorizontalPagingScrollView(in: try XCTUnwrap(window.contentView)))
        XCTAssertEqual(ObjectIdentifier(postReflowScrollView), rtlScrollViewID)
        simulateMacUserHorizontalScroll(postReflowScrollView, by: -800)
        try await eventually(window: window) {
            fakeState.currentSlotIndex == 2 && controller.activePagerPage == 2
        }
        XCTAssertEqual(fakeState.currentSlotIndex, 2)

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

    func testMacSubtreeIdentityZoomPreservationMidScrollSplitAndBoundaryReflow() async throws {
        _ = NSApplication.shared
        let prevChapter = UiMangaChapter(id: "ch-0", title: "Chapter 0", subtitle: nil, uploadedAt: nil, locked: false, url: nil)
        let nextChapter = UiMangaChapter(id: "ch-2", title: "Chapter 2", subtitle: nil, uploadedAt: nil, locked: false, url: nil)
        let sixPages = makeMacSamplePages(count: 6)
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

        let fakeState = FakeMacMangaReaderState(
            direction: .rtl,
            pages: sixPages,
            slots: fourSpreadSlots,
            previousBoundary: ChapterBoundaryUnlocked(chapter: prevChapter, startFromEnd: true),
            nextBoundary: ChapterBoundaryUnlocked(chapter: nextChapter, startFromEnd: false)
        )
        fakeState.goToSlot(slotIndex: 1) // pagerPage == 2 (startBoundaryOffset == 1)

        let controller = MangaReaderInteractionController()
        let hostModel = MacReaderTestHostModel(state: fakeState, controller: controller)

        let window = NSWindow(
            contentRect: CGRect(x: 0, y: 0, width: 800, height: 600),
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
            controller.isBound && controller.activePagerPage == 2 && hostModel.pageFrames[1] != nil
        }
        try await Task.sleep(for: .milliseconds(60))
        window.contentView?.layoutSubtreeIfNeeded()

        let initialScrollView = try XCTUnwrap(findHorizontalPagingScrollView(in: try XCTUnwrap(window.contentView)))
        let initialScrollViewID = ObjectIdentifier(initialScrollView)
        XCTAssertNil(controller.zoomResetHandler)

        // 1. Mid-live-scroll spread split in .rtl freezes ForEach layout during active gesture and lands on exact next page (page 3, without skipping to page 4)
        let rtlInitialDocWidth = try XCTUnwrap(initialScrollView.documentView?.frame.width)
        let rtlInitialStartOriginX = initialScrollView.contentView.bounds.origin.x
        XCTAssertEqual(rtlInitialStartOriginX, rtlInitialDocWidth - 2400, accuracy: 2.0)

        NotificationCenter.default.post(name: NSScrollView.willStartLiveScrollNotification, object: initialScrollView)
        initialScrollView.contentView.scroll(to: CGPoint(x: rtlInitialStartOriginX - 400, y: 0)) // halfway forward in RTL
        initialScrollView.reflectScrolledClipView(initialScrollView.contentView)
        NotificationCenter.default.post(name: NSScrollView.didLiveScrollNotification, object: initialScrollView)
        XCTAssertTrue(controller.hasUnconsumedUserScroll)

        // Trigger spread split mid-live-scroll in RTL (slot 2 Double(4,3) -> Single(3), Single(4))
        fakeState.updateSlots(fiveSplitSlots)
        window.contentView?.layoutSubtreeIfNeeded()
        XCTAssertEqual(
            ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: try XCTUnwrap(window.contentView)))),
            initialScrollViewID
        )
        XCTAssertEqual(
            try XCTUnwrap(initialScrollView.documentView?.frame.width),
            rtlInitialDocWidth,
            accuracy: 2.0,
            "Displayed slots must stay frozen during active RTL live scroll"
        )
        XCTAssertEqual(
            initialScrollView.contentView.bounds.origin.x,
            rtlInitialStartOriginX - 400,
            accuracy: 2.0,
            "Mid-live-scroll RTL spread split must not shift or yank active scroll"
        )

        // Complete 1-page forward live scroll in RTL (-800pt from start) -> must land on page 3 (slot 2), NOT skip to page 4
        initialScrollView.contentView.scroll(to: CGPoint(x: rtlInitialStartOriginX - 800, y: 0))
        initialScrollView.reflectScrolledClipView(initialScrollView.contentView)
        NotificationCenter.default.post(name: NSScrollView.didLiveScrollNotification, object: initialScrollView)
        NotificationCenter.default.post(name: NSScrollView.didEndLiveScrollNotification, object: initialScrollView)

        XCTAssertFalse(controller.hasUnconsumedUserScroll, "hasUnconsumedUserScroll must clear once live scroll settles to idle")
        try await eventually(window: window) {
            guard let page3Frame = hostModel.pageFrames[3] else { return false }
            return fakeState.currentPageIndex == 3 &&
                fakeState.currentSlotIndex == 2 &&
                controller.activePagerPage == 3 &&
                page3Frame.minX >= -1.5 &&
                page3Frame.maxX <= 801.5
        }
        XCTAssertEqual(
            ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: try XCTUnwrap(window.contentView)))),
            initialScrollViewID
        )

        fakeState.updateSlots(fourSpreadSlots)
        fakeState.goToSlot(slotIndex: 1)
        try await Task.sleep(for: .milliseconds(60))
        window.contentView?.layoutSubtreeIfNeeded()
        try await eventually(window: window) {
            controller.activePagerPage == 2 && hostModel.pageFrames[1] != nil
        }

        // 2. Zoom active slot (slot 1 / pagerPage 2) and verify 1pt live window resizes + non-active spread split preserve NSScrollView identity & zoom
        controller.handleDoubleTap(at: CGPoint(x: 400, y: 300))
        try await eventually(window: window) { controller.zoomResetHandler != nil }

        for width in [CGFloat(799), 798, 760, 800] {
            window.setContentSize(NSSize(width: width, height: 600))
            window.contentView?.layoutSubtreeIfNeeded()
            try await Task.sleep(for: .milliseconds(25))
            window.contentView?.layoutSubtreeIfNeeded()
            XCTAssertEqual(
                ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: try XCTUnwrap(window.contentView)))),
                initialScrollViewID,
                "1pt / live NSWindow resize (\(width)) must keep the same NSScrollView mounted"
            )
            XCTAssertNotNil(controller.zoomResetHandler, "NSWindow width resize (\(width)) must not wipe active zoom")
            XCTAssertEqual(fakeState.currentSlotIndex, 1)
            XCTAssertEqual(controller.activePagerPage, 2)
        }

        // Non-active spread split (totalPagerPages 6 -> 7 in RTL) while zoomed on slot 1 (pagerPage 2)
        fakeState.updateSlots(fiveSplitSlots)
        try await Task.sleep(for: .milliseconds(60))
        window.contentView?.layoutSubtreeIfNeeded()
        try await eventually(window: window) {
            let docWidth = initialScrollView.documentView?.frame.width ?? 0
            return docWidth >= 2400 && abs(initialScrollView.contentView.bounds.origin.x - (docWidth - 2400)) <= 2.0
        }
        XCTAssertEqual(
            ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: try XCTUnwrap(window.contentView)))),
            initialScrollViewID,
            "Runtime spread split while zoomed must not tear down NSScrollView"
        )
        XCTAssertNotNil(controller.zoomResetHandler, "Non-active spread split must not wipe active zoom")
        XCTAssertEqual(fakeState.currentSlotIndex, 1)
        XCTAssertEqual(controller.activePagerPage, 2)

        // Escape resets zoom cleanly
        XCTAssertTrue(controller.handleKey(.escape))
        try await eventually(window: window) { controller.zoomResetHandler == nil }

        // 3. Boundary card position preserved across NSWindow width resizes AND slot-count splits (nextBoundaryPage & prevBoundaryPage)
        fakeState.updateSlots(fourSpreadSlots) // totalPagerPages == 6, nextBoundaryPage == 5
        try await Task.sleep(for: .milliseconds(50))
        window.contentView?.layoutSubtreeIfNeeded()

        controller.scrollToBoundary(isNext: true)
        try await eventually(window: window) {
            controller.isOnNextBoundaryPage && controller.activePagerPage == 5
        }
        try await Task.sleep(for: .milliseconds(40))
        window.contentView?.layoutSubtreeIfNeeded()

        for width in [CGFloat(799), 540, 800] {
            window.setContentSize(NSSize(width: width, height: 600))
            try await Task.sleep(for: .milliseconds(50))
            window.contentView?.layoutSubtreeIfNeeded()
            try await eventually(window: window) {
                controller.isOnNextBoundaryPage && controller.activePagerPage == 5
            }
            XCTAssertEqual(
                ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: try XCTUnwrap(window.contentView)))),
                initialScrollViewID
            )
        }

        // Split spread while standing on nextBoundaryPage (slots 4 -> 5, shifting currentSlotIndex on last page AND moving nextBoundaryPage 5 -> 6)
        fakeState.goToSlot(slotIndex: 3)
        try await eventually(window: window) { controller.activePagerPage == 4 && !controller.isOnNextBoundaryPage }
        controller.scrollToBoundary(isNext: true)
        try await eventually(window: window) { controller.isOnNextBoundaryPage && controller.activePagerPage == 5 }
        fakeState.updateSlots(fiveSplitSlots)
        try await Task.sleep(for: .milliseconds(60))
        window.contentView?.layoutSubtreeIfNeeded()
        try await eventually(window: window) {
            controller.isOnNextBoundaryPage &&
                controller.activePagerPage == 6 &&
                abs(initialScrollView.contentView.bounds.origin.x - 0) <= 2.0
        }
        XCTAssertEqual(
            ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: try XCTUnwrap(window.contentView)))),
            initialScrollViewID
        )

        // Stand on prevBoundaryPage (0): preserved across width resize and slot-count shrink (5 -> 4)
        controller.scrollToBoundary(isNext: false)
        try await eventually(window: window) {
            controller.isOnPreviousBoundaryPage && controller.activePagerPage == 0
        }
        window.setContentSize(NSSize(width: 540, height: 600))
        try await Task.sleep(for: .milliseconds(60))
        window.contentView?.layoutSubtreeIfNeeded()
        try await eventually(window: window) {
            controller.isOnPreviousBoundaryPage && controller.activePagerPage == 0
        }

        window.setContentSize(NSSize(width: 800, height: 600))
        fakeState.updateSlots(fourSpreadSlots) // totalPagerPages 7 -> 6, prevBoundaryPage == 0 at right edge (docWidth - 800)
        try await Task.sleep(for: .milliseconds(60))
        window.contentView?.layoutSubtreeIfNeeded()
        try await eventually(window: window) {
            let docWidth = initialScrollView.documentView?.frame.width ?? 0
            return controller.isOnPreviousBoundaryPage &&
                controller.activePagerPage == 0 &&
                docWidth >= 800 &&
                abs(initialScrollView.contentView.bounds.origin.x - (docWidth - 800)) <= 2.0
        }
        XCTAssertEqual(
            ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: try XCTUnwrap(window.contentView)))),
            initialScrollViewID
        )

        // 4. Cancelled < 50% live scroll in .rtl clears hasUnconsumedUserScroll so subsequent .rtl slot splits & idle NSWindow resizes re-anchor cleanly
        fakeState.updateSlots(fourSpreadSlots)
        fakeState.goToSlot(slotIndex: 1) // page 1, slot 1, pagerPage == 2
        try await Task.sleep(for: .milliseconds(60))
        window.contentView?.layoutSubtreeIfNeeded()
        try await eventually(window: window) {
            let docWidth = initialScrollView.documentView?.frame.width ?? 0
            return controller.activePagerPage == 2 &&
                docWidth >= 2400 &&
                abs(initialScrollView.contentView.bounds.origin.x - (docWidth - 2400)) <= 2.0
        }
        let cancelStartOriginX = initialScrollView.contentView.bounds.origin.x

        NotificationCenter.default.post(name: NSScrollView.willStartLiveScrollNotification, object: initialScrollView)
        initialScrollView.contentView.scroll(to: CGPoint(x: cancelStartOriginX - 150, y: 0)) // < 50% scroll
        initialScrollView.reflectScrolledClipView(initialScrollView.contentView)
        NotificationCenter.default.post(name: NSScrollView.didLiveScrollNotification, object: initialScrollView)
        initialScrollView.contentView.scroll(to: CGPoint(x: cancelStartOriginX, y: 0)) // snap back
        initialScrollView.reflectScrolledClipView(initialScrollView.contentView)
        NotificationCenter.default.post(name: NSScrollView.didLiveScrollNotification, object: initialScrollView)
        NotificationCenter.default.post(name: NSScrollView.didEndLiveScrollNotification, object: initialScrollView)

        XCTAssertFalse(controller.hasUnconsumedUserScroll, "Cancelled < 50% live scroll must clear hasUnconsumedUserScroll on settle")
        XCTAssertEqual(fakeState.currentSlotIndex, 1)
        XCTAssertEqual(fakeState.currentPageIndex, 1)

        fakeState.updateSlots(fiveSplitSlots)
        window.contentView?.layoutSubtreeIfNeeded()
        try await eventually(window: window) {
            guard let frame = hostModel.pageFrames[1] else { return false }
            return fakeState.currentSlotIndex == 1 &&
                controller.activePagerPage == 2 &&
                frame.minX >= -1.5 &&
                frame.maxX <= 801.5
        }

        window.setContentSize(NSSize(width: 540, height: 600))
        try await Task.sleep(for: .milliseconds(60))
        window.contentView?.layoutSubtreeIfNeeded()
        try await eventually(window: window) {
            guard let frame = hostModel.pageFrames[1] else { return false }
            return fakeState.currentSlotIndex == 1 &&
                controller.activePagerPage == 2 &&
                frame.minX >= -1.5 &&
                frame.maxX <= 541.5
        }
        XCTAssertFalse(controller.hasUnconsumedUserScroll)

        window.setContentSize(NSSize(width: 800, height: 600))
        try await Task.sleep(for: .milliseconds(60))
        window.contentView?.layoutSubtreeIfNeeded()
        try await eventually(window: window) {
            fakeState.currentSlotIndex == 1 && controller.activePagerPage == 2
        }

        // 5. Earlier-slot split mid-live-scroll in .ltr also freezes layout during scroll and lands on exact destination page
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
        window.contentView?.layoutSubtreeIfNeeded()
        try await eventually(window: window) { controller.activePagerPage == 2 }
        let ltrScrollView = try XCTUnwrap(findHorizontalPagingScrollView(in: try XCTUnwrap(window.contentView)))
        let ltrScrollViewID = ObjectIdentifier(ltrScrollView)

        NotificationCenter.default.post(name: NSScrollView.willStartLiveScrollNotification, object: ltrScrollView)
        ltrScrollView.contentView.scroll(to: CGPoint(x: 2000, y: 0))
        ltrScrollView.reflectScrolledClipView(ltrScrollView.contentView)
        NotificationCenter.default.post(name: NSScrollView.didLiveScrollNotification, object: ltrScrollView)

        // Split earlier slot 0 mid-live-scroll in LTR
        fakeState.updateSlots(ltrEarlierSplitSlots)
        window.contentView?.layoutSubtreeIfNeeded()
        XCTAssertEqual(
            ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: try XCTUnwrap(window.contentView)))),
            ltrScrollViewID
        )
        XCTAssertEqual(
            ltrScrollView.contentView.bounds.origin.x,
            2000,
            accuracy: 2.0,
            "Mid-live-scroll earlier-slot split must not yank active scroll back"
        )

        ltrScrollView.contentView.scroll(to: CGPoint(x: 2400, y: 0))
        ltrScrollView.reflectScrolledClipView(ltrScrollView.contentView)
        NotificationCenter.default.post(name: NSScrollView.didLiveScrollNotification, object: ltrScrollView)
        NotificationCenter.default.post(name: NSScrollView.didEndLiveScrollNotification, object: ltrScrollView)

        try await eventually(window: window) {
            guard let page4Frame = hostModel.pageFrames[4] else { return false }
            return fakeState.currentPageIndex == 4 &&
                fakeState.currentSlotIndex == 3 &&
                controller.activePagerPage == 4 &&
                page4Frame.minX >= -1.5 &&
                page4Frame.maxX <= 801.5
        }
        XCTAssertFalse(controller.hasUnconsumedUserScroll)
        XCTAssertEqual(
            ObjectIdentifier(try XCTUnwrap(findHorizontalPagingScrollView(in: try XCTUnwrap(window.contentView)))),
            ltrScrollViewID
        )
    }

    func testMacAsyncSnapshotDeliveryMidScrollSplitStepNavigationAndLockedBoundary() async throws {
        _ = NSApplication.shared
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
        let sixPages = makeMacSamplePages(count: 6)
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

        let fakeState = FakeMacMangaReaderState(
            direction: .rtl,
            pages: sixPages,
            slots: fourSpreadSlots,
            previousBoundary: ChapterBoundaryLocked(chapter: prevLockedChapter, openUrl: "https://manga.example/paywall/ch-0"),
            nextBoundary: ChapterBoundaryLocked(chapter: nextLockedChapter, openUrl: "https://manga.example/paywall/ch-2")
        )
        fakeState.goToSlot(slotIndex: 1) // pagerPage == 2 (startBoundaryOffset == 1)

        let controller = MangaReaderInteractionController()
        let hostModel = MacReaderTestHostModel(state: fakeState, controller: controller, deferSnapshotDelivery: true)

        let window = NSWindow(
            contentRect: CGRect(x: 0, y: 0, width: 800, height: 600),
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
            controller.isBound && controller.activePagerPage == 2 && hostModel.pageFrames[1] != nil
        }
        try await Task.sleep(for: .milliseconds(60))
        window.contentView?.layoutSubtreeIfNeeded()

        let scrollView = try XCTUnwrap(findHorizontalPagingScrollView(in: try XCTUnwrap(window.contentView)))
        let startOriginX = scrollView.contentView.bounds.origin.x

        // (a) .rtl mid-live-scroll spread split with deferred async snapshot publication must NOT yank back to pre-scroll slot 1
        NotificationCenter.default.post(name: NSScrollView.willStartLiveScrollNotification, object: scrollView)
        scrollView.contentView.scroll(to: CGPoint(x: startOriginX - 400, y: 0))
        scrollView.reflectScrolledClipView(scrollView.contentView)
        NotificationCenter.default.post(name: NSScrollView.didLiveScrollNotification, object: scrollView)

        fakeState.updateSlots(fiveSplitSlots)
        try await Task.sleep(for: .milliseconds(25))
        window.contentView?.layoutSubtreeIfNeeded()

        scrollView.contentView.scroll(to: CGPoint(x: startOriginX - 800, y: 0))
        scrollView.reflectScrolledClipView(scrollView.contentView)
        NotificationCenter.default.post(name: NSScrollView.didLiveScrollNotification, object: scrollView)
        NotificationCenter.default.post(name: NSScrollView.didEndLiveScrollNotification, object: scrollView)

        XCTAssertEqual(controller.activePagerPage, 3, "Mid-scroll split must not yank back to pre-scroll pagerPage 2 while awaiting async snapshot")
        try await eventually(window: window) {
            guard let page3Frame = hostModel.pageFrames[3] else { return false }
            return hostModel.snapshot.currentPageIndex == 3 &&
                hostModel.snapshot.currentSlotIndex == 2 &&
                controller.activePagerPage == 3 &&
                page3Frame.minX >= -1.5 &&
                page3Frame.maxX <= 801.5
        }

        // (b) pagedStepForward / pagedStepBackward advance & animate accurately under deferred async snapshot delivery
        XCTAssertTrue(controller.handleKey(.leftArrow)) // RTL forward: slot 2 -> slot 3 (page 4, pagerPage 4)
        XCTAssertEqual(controller.activePagerPage, 4)
        try await eventually(window: window) {
            hostModel.snapshot.currentSlotIndex == 3 &&
                hostModel.snapshot.currentPageIndex == 4 &&
                controller.activePagerPage == 4
        }

        XCTAssertTrue(controller.handleKey(.leftArrow)) // RTL forward: slot 3 -> slot 4 (last slot, page 5, pagerPage 5)
        XCTAssertEqual(controller.activePagerPage, 5)
        try await eventually(window: window) {
            hostModel.snapshot.currentSlotIndex == 4 &&
                hostModel.snapshot.currentPageIndex == 5 &&
                controller.activePagerPage == 5
        }

        // (c) Stepping forward on the last slot when nextBoundary is ChapterBoundaryLocked scrolls onto nextBoundaryPage (pagerPage 6)
        XCTAssertFalse(controller.isOnNextBoundaryPage)
        XCTAssertTrue(controller.handleKey(.leftArrow))
        try await eventually(window: window) {
            controller.isOnNextBoundaryPage && controller.activePagerPage == 6
        }
        XCTAssertTrue(controller.handleKey(.leftArrow))
        XCTAssertTrue(controller.isOnNextBoundaryPage)
        XCTAssertEqual(controller.activePagerPage, 6)

        // Stepping backward from nextBoundaryPage returns to last slot (slot 4 / pagerPage 5)
        XCTAssertTrue(controller.handleKey(.rightArrow))
        try await eventually(window: window) {
            !controller.isOnNextBoundaryPage &&
                controller.activePagerPage == 5 &&
                hostModel.snapshot.currentSlotIndex == 4
        }

        // Stepping backward on slot 0 when previousBoundary is ChapterBoundaryLocked scrolls onto prevBoundaryPage (0)
        fakeState.goToSlot(slotIndex: 0)
        try await eventually(window: window) {
            hostModel.snapshot.currentSlotIndex == 0 && controller.activePagerPage == 1
        }
        XCTAssertTrue(controller.handleKey(.rightArrow))
        try await eventually(window: window) {
            controller.isOnPreviousBoundaryPage && controller.activePagerPage == 0
        }
    }

    func testMacWebtoonViewportSteppingZoomEdgesAndDoubleSlotBoundaryInwardGuard() async throws {
        _ = NSApplication.shared
        let nextChapter = UiMangaChapter(id: "ch-2", title: "Chapter 2", subtitle: nil, uploadedAt: nil, locked: false, url: nil)
        let prevChapter = UiMangaChapter(id: "ch-0", title: "Chapter 0", subtitle: nil, uploadedAt: nil, locked: false, url: nil)
        let tallSample = makeMacSamplePages(count: 1)[0]
        let tallPages = [
            ReaderPageState(
                id: "mac-tall-0",
                index: 0,
                status: tallSample.status,
                aspectRatio: KotlinFloat(value: 0.2)
            ),
        ]
        let webtoonState = FakeMacMangaReaderState(
            direction: .webtoon,
            pages: tallPages,
            slots: [ReaderSlotSingle(pageIndex: 0)],
            nextBoundary: ChapterBoundaryUnlocked(chapter: nextChapter, startFromEnd: false)
        )
        let controller = MangaReaderInteractionController()
        let hostModel = MacReaderTestHostModel(state: webtoonState, controller: controller, deferSnapshotDelivery: true)

        let window = NSWindow(
            contentRect: CGRect(x: 0, y: 0, width: 600, height: 800),
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

        try await eventually(window: window) {
            controller.isBound && hostModel.pageFrames[0] != nil
        }

        // 1. Tall strip (aspectRatio 0.2 -> 600x3000 in 600x800 viewport): right tap scrolls ~80% viewport (640pt) without advancing chapter
        controller.handleTap(at: CGPoint(x: 520, y: 400), in: CGSize(width: 600, height: 800))
        try await eventually(window: window) {
            guard let frame = hostModel.pageFrames[0] else { return false }
            return abs(frame.minY + 640) <= 10
        }
        XCTAssertNil(webtoonState.openedBoundaryChapterId)

        // Left tap scrolls back up ~80% viewport to y == 0
        controller.handleTap(at: CGPoint(x: 80, y: 400), in: CGSize(width: 600, height: 800))
        try await eventually(window: window) {
            guard let frame = hostModel.pageFrames[0] else { return false }
            return abs(frame.minY) <= 10
        }

        // 2. Webtoon double-tap zoom (1.0x -> 2.5x), top/bottom edge reachability, and Escape reset
        controller.handleDoubleTap(at: CGPoint(x: 300, y: 400))
        try await eventually(window: window) {
            controller.zoomResetHandler != nil && abs(controller.activeZoomScale - 2.5) <= 0.05
        }
        controller.panWebtoonZoomForTesting(CGSize(width: 0, height: 900))
        try await eventually(window: window) {
            guard let frame = hostModel.pageFrames[0] else { return false }
            return abs(controller.activeZoomOffset.height - 600) <= 2.0 && abs(frame.minY) <= 4.0
        }

        for _ in 0..<12 {
            controller.panWebtoonZoomForTesting(CGSize(width: 0, height: -900))
        }
        try await eventually(window: window) {
            abs(controller.activeZoomOffset.height + 600) <= 2.0
        }

        XCTAssertTrue(controller.handleKey(.escape))
        try await eventually(window: window) {
            controller.zoomResetHandler == nil && abs(controller.activeZoomScale - 1.0) <= 0.01
        }

        // Step back up to top via Shift+Space, then step forward via Space from top -> >= 4 ~80% steps before opening Chapter 2
        var rewindSteps = 0
        while (hostModel.pageFrames[0]?.minY ?? 0) < -5 && rewindSteps < 10 {
            XCTAssertTrue(controller.handleKey(.space(shift: true)))
            window.contentView?.layoutSubtreeIfNeeded()
            window.contentView?.displayIfNeeded()
            try await Task.sleep(for: .milliseconds(15))
            rewindSteps += 1
        }
        XCTAssertNil(webtoonState.openedBoundaryChapterId)

        var forwardSteps = 0
        while webtoonState.openedBoundaryChapterId == nil && forwardSteps < 12 {
            XCTAssertTrue(controller.handleKey(.space(shift: false)))
            window.contentView?.layoutSubtreeIfNeeded()
            window.contentView?.displayIfNeeded()
            try await Task.sleep(for: .milliseconds(15))
            forwardSteps += 1
        }
        XCTAssertGreaterThanOrEqual(forwardSteps, 4)
        XCTAssertEqual(webtoonState.openedBoundaryChapterId, "ch-2")

        // 4. Stepping inward from prevBoundary / nextBoundary onto a Double spread preserves secondary currentPageIndex
        let spreadPages = makeMacSamplePages(count: 4)
        let spreadSlots: [any ReaderSlot] = [
            ReaderSlotDouble(leftPageIndex: 1, rightPageIndex: 0, primaryPageIndex: 0),
            ReaderSlotDouble(leftPageIndex: 3, rightPageIndex: 2, primaryPageIndex: 2),
        ]
        let spreadState = FakeMacMangaReaderState(
            direction: .ltr,
            pages: spreadPages,
            slots: spreadSlots,
            previousBoundary: ChapterBoundaryUnlocked(chapter: prevChapter, startFromEnd: true),
            nextBoundary: ChapterBoundaryUnlocked(chapter: nextChapter, startFromEnd: false)
        )
        spreadState.goToPage(pageIndex: 3)
        hostModel.setState(spreadState)
        try await eventually(window: window) {
            controller.activePagerPage == 2 && hostModel.snapshot.currentPageIndex == 3
        }
        controller.scrollToBoundary(isNext: true)
        XCTAssertTrue(controller.isOnNextBoundaryPage)
        XCTAssertTrue(controller.handleKey(.leftArrow))
        try await eventually(window: window) {
            !controller.isOnNextBoundaryPage && controller.activePagerPage == 2
        }
        XCTAssertEqual(spreadState.currentPageIndex, 3, "Stepping inward onto Double slot must preserve secondary page index 3")
    }

    func testMacAdaptiveChapterGridAndNarrowPinnedTabs() async throws {
        _ = NSApplication.shared
        let mangaKey = MicroBlogKey(id: "manga-grid-1", host: "manga.example")
        let guestAccount = AccountType.GuestPlugin(platformId: "manga-plat", host: "manga.example")
        let ch0 = UiMangaChapter(id: "ch-0", title: "Chapter 0: Prologue", subtitle: "Vol. 1", uploadedAt: nil, locked: false, url: nil)
        let ch1 = UiMangaChapter(id: "ch-1", title: "Chapter 1: Dawn", subtitle: "Vol. 1", uploadedAt: nil, locked: false, url: nil)

        let detail = UiMangaDetail(
            pluginId: "dev.example.manga",
            platformId: "manga-plat",
            mangaKey: mangaKey,
            accountType: guestAccount,
            title: "Grid Horizon",
            altTitles: ["网格地平线"],
            cover: nil,
            author: nil,
            authors: ["Aria Lin"],
            artists: ["Kuro"],
            status: .ongoing,
            content: makeMacRichSynopsis(["A journey across star seas."]),
            url: "https://manga.example/series/grid-1",
            updatedAt: nil,
            readingDirection: .rtl,
            tagGroups: [
                UiMangaTagGroup(
                    id: "genre",
                    title: UiTextRaw(string: "Genre"),
                    tags: [UiMangaTag(name: "Sci-Fi", clickEvent: ClickEventNoop.shared)]
                ),
            ],
            chapterGroups: [
                UiMangaChapterGroup(id: "main", title: UiTextRaw(string: "Main Story"), chapters: [ch0, ch1]),
            ],
            isBookmarked: false,
            bookmarkAction: ClickEventNoop.shared,
            actions: [],
            sourceLanguages: ["en"]
        )
        let chapterItems = [
            UiMangaChapterItem(chapter: ch0, isRead: false, isCurrent: true, lastPageIndex: 3, pageCount: 20),
            UiMangaChapterItem(chapter: ch1, isRead: false, isCurrent: false, lastPageIndex: 0, pageCount: 18),
        ]
        let primaryTarget = PrimaryReadTarget(
            chapterId: "ch-0",
            chapterTitle: "Chapter 0: Prologue",
            initialPage: 3,
            isContinue: true,
            isSingleChapterWork: false
        )

        let window = NSWindow(
            contentRect: CGRect(x: 0, y: 0, width: 1080, height: 720),
            styleMask: [.titled, .resizable],
            backing: .buffered,
            defer: false
        )
        window.isReleasedWhenClosed = false
        var refreshCount = 0
        var paneFrames = MangaDetailPaneFrames()
        var chapterFrames: [String: CGRect] = [:]

        func makeHostRoot(
            supportsComments: Bool,
            supportsRecommendations: Bool
        ) -> some View {
            MangaDetailContentView(
                detail: UiStateSuccess(data: detail),
                selectedGroupId: "main",
                chapterSortDescending: false,
                chapters: chapterItems,
                primaryReadTarget: primaryTarget,
                supportsComments: supportsComments,
                supportsRecommendations: supportsRecommendations,
                onRefresh: { refreshCount += 1 },
                onNavigate: { _ in }
            )
            .onPreferenceChange(MangaDetailPaneFramesPreferenceKey.self) { paneFrames = $0 }
            .onPreferenceChange(MangaChapterFramesPreferenceKey.self) { chapterFrames = $0 }
        }

        let hostingView = NSHostingView(
            rootView: makeHostRoot(supportsComments: true, supportsRecommendations: true)
        )
        hostingView.sizingOptions = []
        window.contentView = hostingView
        window.makeKeyAndOrderFront(nil)
        defer { window.close() }

        // 1. 1080x720 (right chapters pane > 520pt): ch-0 and ch-1 sit side-by-side
        try await eventually(window: window) {
            paneFrames.heroCard != nil &&
                paneFrames.chaptersSection != nil &&
                chapterFrames["ch-0"] != nil &&
                chapterFrames["ch-1"] != nil
        }
        XCTAssertLessThan(
            try XCTUnwrap(paneFrames.heroCard).maxX,
            try XCTUnwrap(paneFrames.chaptersSection).minX
        )
        let ch0Wide = try XCTUnwrap(chapterFrames["ch-0"])
        let ch1Wide = try XCTUnwrap(chapterFrames["ch-1"])
        XCTAssertLessThanOrEqual(
            abs(ch0Wide.minY - ch1Wide.minY),
            2.0,
            "Expected ch-0 (\(ch0Wide)) and ch-1 (\(ch1Wide)) side-by-side at 1080x720"
        )
        XCTAssertLessThan(
            ch0Wide.minX,
            ch1Wide.minX,
            "Expected ch-0 (\(ch0Wide)) to the left of ch-1 (\(ch1Wide)) at 1080x720"
        )

        // 2. Resize to 760x720 (split layout >= 560pt, right chapters pane < 520pt): single column
        window.setContentSize(NSSize(width: 760, height: 720))
        try await eventually(window: window) {
            guard let f0 = chapterFrames["ch-0"],
                  let f1 = chapterFrames["ch-1"] else {
                return false
            }
            return abs(f0.minX - f1.minX) <= 2.0 && abs(f0.minY - f1.minY) > 10.0
        }
        let ch0Mid = try XCTUnwrap(chapterFrames["ch-0"])
        let ch1Mid = try XCTUnwrap(chapterFrames["ch-1"])
        XCTAssertLessThanOrEqual(abs(ch0Mid.minX - ch1Mid.minX), 2.0)
        XCTAssertNotEqual(ch0Mid.minY, ch1Mid.minY)

        // 3. Resize to 480x720 (< 560pt compact macOS): NSSegmentedControl for 3 tabs is mounted,
        //    and dynamic availableTabs shrinking resets removed tab to .chapters while preserving remaining selected tab
        window.setContentSize(NSSize(width: 480, height: 720))
        try await eventually(window: window) {
            let controls = self.findMacSubviews(ofType: NSSegmentedControl.self, in: hostingView)
            return controls.contains { $0.segmentCount == 3 }
        }
        let narrowSegmented = try XCTUnwrap(
            findMacSubviews(ofType: NSSegmentedControl.self, in: hostingView).first { $0.segmentCount == 3 }
        )
        narrowSegmented.selectedSegment = 2
        _ = narrowSegmented.sendAction(narrowSegmented.action, to: narrowSegmented.target)
        try await eventually(window: window) { narrowSegmented.selectedSegment == 2 }

        // Shrink 3 tabs -> 2 tabs while on removed Recommendations (index 2) -> must reset to Chapters (index 0)
        hostingView.rootView = makeHostRoot(supportsComments: true, supportsRecommendations: false)
        try await eventually(window: window) {
            guard let control = self.findMacSubviews(ofType: NSSegmentedControl.self, in: hostingView).first else {
                return false
            }
            return control.segmentCount == 2 && control.selectedSegment == 0
        }

        // Select Comments (index 1), restore 3 tabs, then shrink 3 -> 2 tabs -> Comments stays selected (index 1)
        hostingView.rootView = makeHostRoot(supportsComments: true, supportsRecommendations: true)
        try await eventually(window: window) {
            self.findMacSubviews(ofType: NSSegmentedControl.self, in: hostingView).first?.segmentCount == 3
        }
        let threeTabControl = try XCTUnwrap(findMacSubviews(ofType: NSSegmentedControl.self, in: hostingView).first)
        threeTabControl.selectedSegment = 1
        _ = threeTabControl.sendAction(threeTabControl.action, to: threeTabControl.target)
        try await eventually(window: window) { threeTabControl.selectedSegment == 1 }

        hostingView.rootView = makeHostRoot(supportsComments: true, supportsRecommendations: false)
        try await eventually(window: window) {
            guard let control = self.findMacSubviews(ofType: NSSegmentedControl.self, in: hostingView).first else {
                return false
            }
            return control.segmentCount == 2 && control.selectedSegment == 1
        }

        // Shrink to 1 tab (Chapters only) -> NSSegmentedControl hidden
        hostingView.rootView = makeHostRoot(supportsComments: false, supportsRecommendations: false)
        try await eventually(window: window) {
            self.findMacSubviews(ofType: NSSegmentedControl.self, in: hostingView).isEmpty
        }
        XCTAssertEqual(refreshCount, 0)

        // 4. Read-only guest MangaDetailContentView at 960x720 with primaryReadTarget: nil, actions: [], bookmarkAction: ClickEventNoop renders 0 NSButtons in left inspector hero section
        var guestPaneFrames = MangaDetailPaneFrames()
        let guestHosting = NSHostingView(
            rootView: MangaDetailContentView(
                detail: UiStateSuccess(data: detail),
                selectedGroupId: "main",
                chapterSortDescending: false,
                chapters: chapterItems,
                primaryReadTarget: nil,
                supportsComments: false,
                supportsRecommendations: false,
                onNavigate: { _ in }
            )
            .onPreferenceChange(MangaDetailPaneFramesPreferenceKey.self) { guestPaneFrames = $0 }
        )
        guestHosting.sizingOptions = []
        window.contentView = guestHosting
        window.setContentSize(NSSize(width: 960, height: 720))
        try await eventually(window: window) {
            guestPaneFrames.heroCard != nil && guestPaneFrames.chaptersSection != nil
        }
        let guestHeroFrame = try XCTUnwrap(guestPaneFrames.heroCard)
        let leftPaneButtons = findMacSubviews(ofType: NSButton.self, in: guestHosting).filter { button in
            let rectInWindow = button.convert(button.bounds, to: nil)
            return rectInWindow.midX < guestHeroFrame.maxX + 8
        }
        XCTAssertEqual(
            leftPaneButtons.count,
            0,
            "Read-only guest left inspector hero section must render zero NSButtons when primaryReadTarget is nil"
        )

        // 5. MacMangaChapterCell wraps long chapter titles up to 2 lines instead of truncating to 1 line
        let shortChapter = UiMangaChapter(
            id: "ch-short",
            title: "Chapter 1",
            subtitle: nil,
            uploadedAt: nil,
            locked: false,
            url: nil
        )
        let longChapter = UiMangaChapter(
            id: "ch-long",
            title: "Chapter 2: Across Uncharted Star Seas Toward The Far Horizon Of Eternity And Beyond",
            subtitle: nil,
            uploadedAt: nil,
            locked: false,
            url: nil
        )
        var wrapFrames: [String: CGRect] = [:]
        let wrapHosting = NSHostingView(
            rootView: MangaDetailContentView(
                detail: UiStateSuccess(data: detail),
                selectedGroupId: "main",
                chapterSortDescending: false,
                chapters: [
                    UiMangaChapterItem(chapter: shortChapter, isRead: false, isCurrent: false, lastPageIndex: 0, pageCount: 10),
                    UiMangaChapterItem(chapter: longChapter, isRead: false, isCurrent: true, lastPageIndex: 0, pageCount: 10),
                ],
                primaryReadTarget: nil,
                onNavigate: { _ in }
            )
            .onPreferenceChange(MangaChapterFramesPreferenceKey.self) { wrapFrames = $0 }
        )
        wrapHosting.sizingOptions = []
        window.contentView = wrapHosting
        window.setContentSize(NSSize(width: 960, height: 720))
        try await eventually(window: window) {
            wrapFrames["ch-short"] != nil && wrapFrames["ch-long"] != nil
        }
        let shortCellHeight = try XCTUnwrap(wrapFrames["ch-short"]).height
        let longCellHeight = try XCTUnwrap(wrapFrames["ch-long"]).height
        XCTAssertGreaterThan(
            longCellHeight,
            shortCellHeight + 10,
            "Expected 2-line wrapped MacMangaChapterCell (\(longCellHeight)) to grow taller than 1-line cell (\(shortCellHeight))"
        )
    }

    func testMacWebtoonConstrainedWidthPreservesScrubberJumpOn1200Viewport() async throws {
        _ = NSApplication.shared
        let webtoonPages = makeMacSamplePages(count: 6)
        let webtoonSlots: [any ReaderSlot] = (0..<6).map { ReaderSlotSingle(pageIndex: Int32($0)) }
        let webtoonState = FakeMacMangaReaderState(
            direction: .webtoon,
            pages: webtoonPages,
            slots: webtoonSlots
        )
        webtoonState.webtoonConstrainWidthOnWideScreen = true

        let controller = MangaReaderInteractionController()
        let hostModel = MacReaderTestHostModel(state: webtoonState, controller: controller)

        let window = NSWindow(
            contentRect: CGRect(x: 0, y: 0, width: 1200, height: 800),
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
            controller.isBound && hostModel.pageFrames[0] != nil
        }

        let constrainedPage0 = try XCTUnwrap(hostModel.pageFrames[0])
        XCTAssertEqual(constrainedPage0.width, 800, accuracy: 1.5)
        XCTAssertEqual(constrainedPage0.minX, 200, accuracy: 1.5)

        // Scrubber / programmatic jump to last page (5) lands inside viewport with constrained width
        webtoonState.goToPage(pageIndex: 5)
        try await eventually(window: window) {
            guard let page5 = hostModel.pageFrames[5] else { return false }
            return page5.minY < 800 && page5.maxY > 0 && webtoonState.currentPageIndex == 5
        }
        let constrainedPage5 = try XCTUnwrap(hostModel.pageFrames[5])
        XCTAssertEqual(constrainedPage5.width, 800, accuracy: 1.5)
        XCTAssertEqual(constrainedPage5.minX, 200, accuracy: 1.5)

        // Return to page 0 and flip webtoonConstrainWidthOnWideScreen = false -> expands to full 1200pt viewport width
        webtoonState.goToPage(pageIndex: 0)
        try await eventually(window: window) {
            guard let page0 = hostModel.pageFrames[0] else { return false }
            return page0.minY < 800 && page0.maxY > 0 && webtoonState.currentPageIndex == 0
        }
        webtoonState.setWebtoonConstrainWidthOnWideScreen(enabled: false)
        try await eventually(window: window) {
            guard let page0 = hostModel.pageFrames[0] else { return false }
            return abs(page0.width - 1200) <= 1.5 && abs(page0.minX) <= 1.5
        }
    }

    func testMacReaderWindowCoordinatorReuseAndEscapePriority() async throws {
        _ = NSApplication.shared
        let coordinator = MacMangaReaderWindowCoordinator()
        let accountA = AccountType.Specific(accountKey: MicroBlogKey(id: "user-1", host: "manga.example"))
        let accountGuest = AccountType.GuestPlugin(platformId: "manga-plat", host: "manga.example")
        let manga1 = MicroBlogKey(id: "manga-1", host: "manga.example")
        let manga2 = MicroBlogKey(id: "manga-2", host: "manga.example")

        let firstValue = coordinator.open(
            accountType: accountA,
            mangaKey: manga1,
            chapterId: "ch-1",
            initialPage: nil
        )
        let firstRequest = try XCTUnwrap(coordinator.request(for: firstValue))
        XCTAssertEqual(firstRequest.chapterId, "ch-1")
        XCTAssertNil(firstRequest.initialPage)
        XCTAssertEqual(firstRequest.revision, 1)

        let secondValue = coordinator.open(
            accountType: accountA,
            mangaKey: manga1,
            chapterId: "ch-2",
            initialPage: 4
        )
        XCTAssertEqual(firstValue, secondValue)
        XCTAssertEqual(firstValue.hashValue, secondValue.hashValue)

        let updatedRequest = try XCTUnwrap(coordinator.request(for: secondValue))
        XCTAssertEqual(updatedRequest.chapterId, "ch-2")
        XCTAssertEqual(updatedRequest.initialPage, 4)
        XCTAssertEqual(updatedRequest.revision, 2)

        let distinctMangaValue = coordinator.open(
            accountType: accountA,
            mangaKey: manga2,
            chapterId: "ch-1",
            initialPage: nil
        )
        XCTAssertNotEqual(firstValue, distinctMangaValue)
        XCTAssertEqual(try XCTUnwrap(coordinator.request(for: distinctMangaValue)).revision, 1)
        XCTAssertEqual(try XCTUnwrap(coordinator.request(for: firstValue)).chapterId, "ch-2")

        let distinctAccountValue = coordinator.open(
            accountType: accountGuest,
            mangaKey: manga1,
            chapterId: "ch-1",
            initialPage: nil
        )
        XCTAssertNotEqual(firstValue, distinctAccountValue)

        // Stale revision close is ignored; matching revision close removes request cleanly
        coordinator.close(value: firstValue, matchingRevision: 1)
        XCTAssertNotNil(
            coordinator.request(for: firstValue),
            "Closing with stale revision 1 must not clobber active revision 2 request"
        )
        coordinator.close(value: firstValue, matchingRevision: 2)
        XCTAssertNil(
            coordinator.request(for: firstValue),
            "Closing with matching revision 2 must remove window request"
        )
        XCTAssertNotNil(coordinator.request(for: distinctMangaValue))

        // Escape priority: overlay dismissal -> zoom reset -> onBackHandler (and step/jump keys yield while overlay is open)
        let pages = makeMacSamplePages(count: 4)
        let slots: [any ReaderSlot] = (0..<4).map { ReaderSlotSingle(pageIndex: Int32($0)) }
        let fakeState = FakeMacMangaReaderState(direction: .ltr, pages: pages, slots: slots)
        let controller = MangaReaderInteractionController()
        let hostModel = MacReaderTestHostModel(state: fakeState, controller: controller)
        var backCallCount = 0
        hostModel.onBack = { backCallCount += 1 }

        let window = NSWindow(
            contentRect: CGRect(x: 0, y: 0, width: 960, height: 820),
            styleMask: [.titled, .resizable],
            backing: .buffered,
            defer: false
        )
        window.isReleasedWhenClosed = false
        @MainActor
        struct OverlayCanvasHost: View {
            @Bindable var model: MacReaderTestHostModel
            var body: some View {
                MangaReaderCanvas(
                    state: model.snapshot,
                    hudVisible: model.hudVisible,
                    onToggleHud: { model.hudVisible.toggle() },
                    onBack: { model.onBack?() },
                    isOverlayPresented: model.isOverlayPresented,
                    onDismissOverlay: {
                        guard model.isOverlayPresented else { return false }
                        model.isOverlayPresented = false
                        return true
                    },
                    controller: model.controller
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .onPreferenceChange(MangaReaderPageFramesPreferenceKey.self) { model.pageFrames = $0 }
            }
        }
        let hostingView = NSHostingView(rootView: OverlayCanvasHost(model: hostModel))
        hostingView.sizingOptions = []
        window.contentView = hostingView
        window.makeKeyAndOrderFront(nil)
        defer { window.close() }

        try await eventually(window: window) {
            controller.isBound && hostModel.pageFrames[0] != nil && controller.doubleTapZoomHandler != nil
        }

        // Verify Home and End keyboard shortcuts jump to first and last page
        XCTAssertTrue(controller.handleKey(.end))
        XCTAssertEqual(fakeState.currentPageIndex, 3)
        XCTAssertTrue(controller.handleKey(.home))
        XCTAssertEqual(fakeState.currentPageIndex, 0)

        controller.handleDoubleTap(at: CGPoint(x: 480, y: 410))
        try await eventually(window: window) { controller.zoomResetHandler != nil }

        // Present overlay while zoomed
        hostModel.isOverlayPresented = true
        try await eventually(window: window) { controller.isOverlayPresented }

        // Step/jump keys yield (return false) without turning pages behind open overlay
        XCTAssertFalse(controller.handleKey(.rightArrow))
        XCTAssertFalse(controller.handleKey(.pageDown))
        XCTAssertFalse(controller.handleKey(.end))
        XCTAssertEqual(fakeState.currentPageIndex, 0)

        // 1st Escape dismisses overlay while preserving zoom and not calling onBack
        XCTAssertTrue(controller.handleKey(.escape))
        try await eventually(window: window) { !hostModel.isOverlayPresented && !controller.isOverlayPresented }
        XCTAssertNotNil(controller.zoomResetHandler, "First Escape with open overlay must preserve zoom")
        XCTAssertEqual(backCallCount, 0)

        // 2nd Escape resets zoom without invoking onBackHandler
        XCTAssertTrue(controller.handleKey(.escape))
        try await eventually(window: window) { controller.zoomResetHandler == nil }
        XCTAssertEqual(backCallCount, 0, "Second Escape while zoomed must reset zoom without invoking onBackHandler")

        // 3rd Escape once unzoomed with no overlay invokes onBackHandler
        XCTAssertTrue(controller.handleKey(.escape))
        XCTAssertEqual(backCallCount, 1, "Third Escape once unzoomed must invoke onBackHandler")

        // Native Full Screen toolbar child NSWindow + nested NSPanel popover / external NSPanel regression checks
        let keyMonitor = try XCTUnwrap(
            findMacSubviews(ofType: MacReaderKeyMonitorHostView.self, in: hostingView).first?.coordinator
        )
        fakeState.goToPage(pageIndex: 0)
        try await eventually(window: window) { fakeState.currentPageIndex == 0 }

        // 1) Visible non-NSPanel child NSWindow (simulating AppKit's NSToolbarFullScreenWindow) must NOT block shortcuts
        let toolbarChildWindow = NSWindow(
            contentRect: CGRect(x: 0, y: 780, width: 960, height: 40),
            styleMask: [.borderless],
            backing: .buffered,
            defer: false
        )
        toolbarChildWindow.isReleasedWhenClosed = false
        window.addChildWindow(toolbarChildWindow, ordered: .above)
        defer {
            window.removeChildWindow(toolbarChildWindow)
            toolbarChildWindow.close()
        }
        XCTAssertTrue(toolbarChildWindow.isVisible)
        XCTAssertFalse(toolbarChildWindow is NSPanel)

        XCTAssertTrue(
            keyMonitor.handleKeyDown(keyCode: 124, modifierFlags: [], activeKeyWindow: window),
            "Visible non-NSPanel child window (NSToolbarFullScreenWindow) must not disable Right Arrow shortcut"
        )
        XCTAssertEqual(fakeState.currentPageIndex, 1)
        XCTAssertTrue(
            keyMonitor.handleKeyDown(keyCode: 123, modifierFlags: [], activeKeyWindow: toolbarChildWindow),
            "Reader shortcuts must remain active when NSToolbarFullScreenWindow holds key focus"
        )
        XCTAssertEqual(fakeState.currentPageIndex, 0)

        // 2) Nested NSPanel child under toolbarChildWindow (simulating full-screen toolbar popover / share picker)
        let nestedPopoverPanel = NSPanel(
            contentRect: CGRect(x: 600, y: 400, width: 320, height: 360),
            styleMask: [.titled],
            backing: .buffered,
            defer: false
        )
        nestedPopoverPanel.isReleasedWhenClosed = false
        nestedPopoverPanel.hidesOnDeactivate = false
        toolbarChildWindow.addChildWindow(nestedPopoverPanel, ordered: .above)
        nestedPopoverPanel.makeKeyAndOrderFront(nil)
        defer {
            toolbarChildWindow.removeChildWindow(nestedPopoverPanel)
            nestedPopoverPanel.close()
        }
        XCTAssertTrue(nestedPopoverPanel.isVisible)
        XCTAssertTrue(nestedPopoverPanel.parent === toolbarChildWindow)
        XCTAssertTrue(nestedPopoverPanel.parent?.parent === window)

        // When !isOverlayPresented (untracked NSPanel such as NSSharingServicePicker), shortcuts must yield
        XCTAssertFalse(controller.isOverlayPresented)
        XCTAssertFalse(
            keyMonitor.handleKeyDown(keyCode: 124, modifierFlags: [], activeKeyWindow: window),
            "Untracked nested NSPanel must block page-step keys when !isOverlayPresented"
        )
        XCTAssertFalse(
            keyMonitor.handleKeyDown(keyCode: 53, modifierFlags: [], activeKeyWindow: nestedPopoverPanel),
            "Untracked nested NSPanel must yield Escape when !isOverlayPresented"
        )
        XCTAssertEqual(fakeState.currentPageIndex, 0)
        XCTAssertEqual(backCallCount, 1)

        // When isOverlayPresented == true (Chapters/Settings popover opened from full-screen toolbar, 2 levels deep),
        // arrow keys yield while Escape dismisses the overlay even when nestedPopoverPanel is key
        hostModel.isOverlayPresented = true
        try await eventually(window: window) { controller.isOverlayPresented }
        XCTAssertFalse(
            keyMonitor.handleKeyDown(keyCode: 124, modifierFlags: [], activeKeyWindow: nestedPopoverPanel),
            "Arrow keys must yield to nested full-screen toolbar popover while isOverlayPresented"
        )
        XCTAssertEqual(fakeState.currentPageIndex, 0)
        XCTAssertTrue(
            keyMonitor.handleKeyDown(keyCode: 53, modifierFlags: [], activeKeyWindow: nestedPopoverPanel),
            "Escape must dismiss nested full-screen toolbar popover when nestedPopoverPanel (parent?.parent === window) is key"
        )
        try await eventually(window: window) { !hostModel.isOverlayPresented && !controller.isOverlayPresented }
        XCTAssertEqual(backCallCount, 1)

        toolbarChildWindow.removeChildWindow(nestedPopoverPanel)
        nestedPopoverPanel.orderOut(nil)

        // 3) Direct untracked NSPanel child of reader window also blocks shortcuts when !isOverlayPresented,
        //    and unrelated external NSWindow key focus never triggers background reader shortcuts
        let directSharePanel = NSPanel(
            contentRect: CGRect(x: 500, y: 400, width: 280, height: 240),
            styleMask: [.titled],
            backing: .buffered,
            defer: false
        )
        directSharePanel.isReleasedWhenClosed = false
        directSharePanel.hidesOnDeactivate = false
        window.addChildWindow(directSharePanel, ordered: .above)
        defer {
            window.removeChildWindow(directSharePanel)
            directSharePanel.close()
        }
        XCTAssertTrue(directSharePanel.isVisible)
        XCTAssertFalse(
            keyMonitor.handleKeyDown(keyCode: 124, modifierFlags: [], activeKeyWindow: window),
            "Direct untracked NSPanel child must block reader shortcuts when !isOverlayPresented"
        )
        window.removeChildWindow(directSharePanel)
        directSharePanel.orderOut(nil)

        let unrelatedWindow = NSWindow(
            contentRect: CGRect(x: 50, y: 50, width: 400, height: 300),
            styleMask: [.titled],
            backing: .buffered,
            defer: false
        )
        unrelatedWindow.isReleasedWhenClosed = false
        unrelatedWindow.makeKeyAndOrderFront(nil)
        defer { unrelatedWindow.close() }
        XCTAssertFalse(
            keyMonitor.handleKeyDown(keyCode: 124, modifierFlags: [], activeKeyWindow: unrelatedWindow),
            "Unrelated key window must not trigger background reader shortcuts"
        )
        window.makeKeyAndOrderFront(nil)
        XCTAssertTrue(keyMonitor.handleKeyDown(keyCode: 124, modifierFlags: [], activeKeyWindow: window))
        XCTAssertEqual(fakeState.currentPageIndex, 1)
    }

    private func makeMacRichSynopsis(_ paragraphs: [String]) -> UiRichText {
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

    private func countMacSubviews<T: NSView>(ofType type: T.Type, in root: NSView) -> Int {
        findMacSubviews(ofType: type, in: root).count
    }

    private func findMacSubviews<T: NSView>(ofType type: T.Type, in root: NSView) -> [T] {
        let own = (root as? T).map { [$0] } ?? []
        return own + root.subviews.flatMap { findMacSubviews(ofType: type, in: $0) }
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
    let deferSnapshotDelivery: Bool
    var hudVisible = true
    var isOverlayPresented = false
    var pageFrames: [Int: CGRect] = [:]
    var retryPageIds: Set<String> = []
    var scrubberLeadingLabelFrame: CGRect?
    var scrubberTrailingLabelFrame: CGRect?
    var onBack: (() -> Void)?
    private var stateGeneration = 0

    init(
        state: FakeMacMangaReaderState,
        controller: MangaReaderInteractionController,
        deferSnapshotDelivery: Bool = false
    ) {
        self.snapshot = MacMangaStateSnapshot(backing: state)
        self.controller = controller
        self.deferSnapshotDelivery = deferSnapshotDelivery
        bindMutationCallback(to: state)
    }

    func setState(_ next: FakeMacMangaReaderState) {
        stateGeneration += 1
        retryPageIds.removeAll()
        snapshot = MacMangaStateSnapshot(backing: next)
        bindMutationCallback(to: next)
    }

    private func bindMutationCallback(to state: FakeMacMangaReaderState) {
        let boundGeneration = stateGeneration
        state.onStateMutated = { [weak self, weak state] in
            guard let self, let state else { return }
            if self.deferSnapshotDelivery {
                let captured = MacMangaStateSnapshot(backing: state)
                Task { @MainActor [weak self] in
                    guard let self, self.stateGeneration == boundGeneration else { return }
                    self.snapshot = captured
                }
            } else {
                self.snapshot = MacMangaStateSnapshot(backing: state)
            }
        }
    }
}

@MainActor
private final class MacMangaStateSnapshot: NSObject, MangaReaderPresenterState {
    private let backing: FakeMacMangaReaderState
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

    init(backing: FakeMacMangaReaderState) {
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

@MainActor
private struct MacReaderTestHostView: View {
    @Bindable var model: MacReaderTestHostModel

    var body: some View {
        MangaReaderContentView(
            state: model.snapshot,
            hudVisible: $model.hudVisible,
            goBack: { model.onBack?() },
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

private final class SilentMacNotification: NSObject, InAppNotification {
    nonisolated func onError(message: Message, throwable: KotlinThrowable) {}
    nonisolated func onProgress(message: Message, progress: Int32, total: Int32) {}
    nonisolated func onSuccess(message: Message) {}
}
