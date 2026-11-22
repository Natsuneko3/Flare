import FlareAppleCore
@testable import FlareAppleUI
import Foundation
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

        try await eventually(window: window) { controller.isBound }

        // Move to last slot (slot 1), then settle on next boundary page:
        // stepping inward must return to slot 1 (NOT skip to slot 0), and stepping outward opens Chapter 2
        fakeState.goToSlot(slotIndex: 1)
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

        // Webtoon mode: external scrubber jump scrolls target page into viewport without snapping back
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

        webtoonState.goToPage(pageIndex: 5)
        try await eventually(window: window) {
            guard let page5Frame = hostBox.pageFrames[5] else { return false }
            return page5Frame.minY < 800 && page5Frame.maxY > 0 && webtoonState.currentPageIndex == 5
        }
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
        XCTAssertTrue(renderedText.contains("Starlight Odyssey"), renderedText)
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
    var hudVisible = true
    var pageFrames: [Int: CGRect] = [:]
    var retryPageIds: Set<String> = []
    var scrubberLeadingLabelFrame: CGRect?
    var scrubberTrailingLabelFrame: CGRect?

    init(
        state: FakeMangaReaderState,
        controller: MangaReaderInteractionController
    ) {
        self.snapshot = IOSMangaStateSnapshot(backing: state)
        self.controller = controller
        state.onStateMutated = { [weak self, weak state] in
            guard let self, let state else { return }
            self.snapshot = IOSMangaStateSnapshot(backing: state)
        }
    }

    func setState(_ next: FakeMangaReaderState) {
        retryPageIds.removeAll()
        snapshot = IOSMangaStateSnapshot(backing: next)
        next.onStateMutated = { [weak self, weak next] in
            guard let self, let next else { return }
            self.snapshot = IOSMangaStateSnapshot(backing: next)
        }
    }
}

@MainActor
private final class IOSMangaStateSnapshot: NSObject, MangaReaderPresenterState {
    private let backing: FakeMangaReaderState

    init(backing: FakeMangaReaderState) {
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
