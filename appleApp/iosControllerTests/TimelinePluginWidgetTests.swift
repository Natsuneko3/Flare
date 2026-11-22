import XCTest
import UIKit
import SwiftUI
import KotlinSharedUI
import FlareAppleCore
@testable import FlareAppleUI
@testable import Flare

@MainActor
final class TimelinePluginWidgetTests: XCTestCase {
    func testWidgetVisibilityFollowsWarningRevealRefreshAndCellReuse() throws {
        let plain = makeSampleTimelinePost(id: "match", warning: "Spoiler", pluginWidgets: [])
        let card = makeSamplePluginWidgetCard(id: "score", title: "Final score")
        let enriched = makeSampleTimelinePost(id: "match", warning: "Spoiler", pluginWidgets: [card])
        let updated = makeSampleTimelinePost(id: "match", warning: "Spoiler", pluginWidgets: [
            makeSamplePluginWidgetCard(id: "score", title: "Updated score"),
        ])
        let next = makeSampleTimelinePost(id: "next", warning: "Spoiler", pluginWidgets: [card])
        let edited = makeSampleTimelinePost(id: "match", text: "Edited body", warning: "Spoiler", pluginWidgets: [card])
        let appearance = StatusUIKitAppearance(timeline: TimelineAppearance.companion.Default)
        let status = StatusUIKitView(frame: CGRect(x: 0, y: 0, width: 390, height: 200))

        func configure(_ post: UiTimelineV2.Post, appearance: StatusUIKitAppearance = appearance) {
            status.configure(data: post, appearance: appearance)
            status.prepareForFitting(width: 390)
            status.layoutIfNeeded()
        }
        func hasWidget() -> Bool { status.subviews.contains { $0 is TimelineHostedAccessoryView } }
        func toggleWarning() throws {
            let toggle = try XCTUnwrap(status.subviews.compactMap { $0 as? UIButton }.first { !$0.isHidden })
            UIView.performWithoutAnimation { toggle.sendActions(for: .touchUpInside) }
        }

        configure(plain)
        let collapsedHeight = try XCTUnwrap(status.timelineHeight(for: 390))
        configure(enriched)
        XCTAssertFalse(hasWidget())
        XCTAssertEqual(try XCTUnwrap(status.timelineHeight(for: 390)), collapsedHeight)
        try toggleWarning()
        XCTAssertTrue(hasWidget())
        XCTAssertGreaterThan(try XCTUnwrap(status.timelineHeight(for: 390)), collapsedHeight + 40)
        configure(updated)
        XCTAssertTrue(hasWidget())
        try toggleWarning()
        XCTAssertFalse(hasWidget())
        XCTAssertEqual(try XCTUnwrap(status.timelineHeight(for: 390)), collapsedHeight)

        try toggleWarning()
        configure(edited)
        XCTAssertFalse(hasWidget())
        try toggleWarning()
        configure(next)
        XCTAssertFalse(hasWidget())
        configure(next, appearance: StatusUIKitAppearance(timeline: autoExpandWarnings))
        XCTAssertTrue(hasWidget())
        configure(next)
        XCTAssertFalse(hasWidget())
    }

    func testOrdinaryPostsDoNotAllocateHostedViewAndReuseDetachesHostedView() throws {
        let plainPost = makeSampleTimelinePost(id: "plain-1", pluginWidgets: [])
        let appearance = StatusUIKitAppearance(timeline: TimelineAppearance.companion.Default)
        let cellView = StatusUIKitView(frame: CGRect(x: 0, y: 0, width: 390, height: 200))
        cellView.configure(data: plainPost, appearance: appearance, isDetail: false, isQuote: false, quotes: [])
        cellView.prepareForFitting(width: 390)
        let plainHeight = try XCTUnwrap(cellView.timelineHeight(for: 390))
        cellView.frame.size.height = plainHeight
        cellView.layoutIfNeeded()
        XCTAssertFalse(cellView.subviews.contains { $0 is TimelineHostedAccessoryView })

        let widgetCard = makeSamplePluginWidgetCard(id: "score-1", title: "NBA Live")
        let enrichedPost = makeSampleTimelinePost(id: "plain-1", pluginWidgets: [widgetCard])
        cellView.configure(data: enrichedPost, appearance: appearance, isDetail: false, isQuote: false, quotes: [])
        cellView.prepareForFitting(width: 390)
        let enrichedHeight = try XCTUnwrap(cellView.timelineHeight(for: 390))
        cellView.frame.size.height = enrichedHeight
        cellView.layoutIfNeeded()
        let hostedView = try XCTUnwrap(cellView.subviews.compactMap { $0 as? TimelineHostedAccessoryView }.first)
        XCTAssertGreaterThan(enrichedHeight, plainHeight + 40)

        let rootTap = try XCTUnwrap(cellView.gestureRecognizers?.first)
        XCTAssertFalse(
            cellView.gestureRecognizer(rootTap, shouldReceive: HostedMockTouch(target: hostedView)),
            "Touches inside TimelineHostedAccessoryView must not trigger StatusUIKitView root tap"
        )
        XCTAssertTrue(
            cellView.gestureRecognizer(rootTap, shouldReceive: HostedMockTouch(target: cellView))
        )

        // Quotes must never host plugin widgets even if pluginWidgets is non-empty.
        cellView.configure(data: enrichedPost, appearance: appearance, isDetail: false, isQuote: true, quotes: [])
        cellView.prepareForFitting(width: 340)
        cellView.layoutIfNeeded()
        XCTAssertFalse(cellView.subviews.contains { $0 is TimelineHostedAccessoryView })

        // Reusing the cell back for an ordinary post detaches the hosted accessory view and restores height.
        let plainPost2 = makeSampleTimelinePost(id: "plain-2", pluginWidgets: [])
        cellView.configure(data: plainPost2, appearance: appearance, isDetail: false, isQuote: false, quotes: [])
        cellView.prepareForFitting(width: 390)
        let restoredHeight = try XCTUnwrap(cellView.timelineHeight(for: 390))
        cellView.frame.size.height = restoredHeight
        cellView.layoutIfNeeded()
        XCTAssertFalse(cellView.subviews.contains { $0 is TimelineHostedAccessoryView })
        XCTAssertEqual(restoredHeight, plainHeight, accuracy: 1)
    }

    func testAsyncWidgetArrivalAndCollapsibleTogglePreserveTimelineReadingAnchor() async throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 760)
        let controller = UITimelineCollectionViewController(detailStatusKey: nil)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKey()
        }

        var input = TimelineContent()
        input.state = .loaded
        input.items = (0..<30).map { index in
            let post = makeSampleTimelinePost(id: "post-\(index)", text: "Post \(index) body text on the timeline.", pluginWidgets: [])
            return .post(makeSampleTimelineItem(post: post))
        }
        controller.submit(input, columns: 1)
        controller.loadViewIfNeeded()
        controller.view.frame = window.bounds
        let collection = try XCTUnwrap(descendants(controller.view).compactMap { $0 as? TimelineCollectionView }.first)
        collection.contentInsetAdjustmentBehavior = .never
        await settle(controller: controller, collection: collection)

        controller.restoreEffectiveContentOffset(1_200, animated: false)
        await settle(controller: controller, collection: collection)

        // All visible plain cells must have zero TimelineHostedAccessoryView allocations.
        for visibleCell in collection.visibleCells {
            XCTAssertTrue(descendants(visibleCell).compactMap { $0 as? TimelineHostedAccessoryView }.isEmpty)
        }

        let initialBookmark = try XCTUnwrap(collection.captureReadingPosition())
        guard case .item(let initialID, _, _, _) = initialBookmark else {
            return XCTFail("Expected item reading bookmark")
        }
        let initialPath = try XCTUnwrap(collection.readingIndexPath?(initialID))
        let initialFrame = try XCTUnwrap(collection.layoutAttributesForItem(at: initialPath)?.frame)
        // Align the anchor item's top edge inside the visible viewport so both captureReadingPosition
        // and TimelineCollectionView's visible-top layout compensation track the same anchor item.
        controller.restoreEffectiveContentOffset(initialFrame.minY - collection.adjustedContentInset.top, animated: false)
        await settle(controller: controller, collection: collection)

        let bookmarkBefore = try XCTUnwrap(collection.captureReadingPosition())
        guard case .item(let anchorID, let anchorOffset, _, _) = bookmarkBefore else {
            return XCTFail("Expected item reading bookmark")
        }
        let anchorIndex = try XCTUnwrap(Int(anchorID.replacingOccurrences(of: "t:post-", with: "")))
        XCTAssertGreaterThan(anchorIndex, 3)

        // Enrich both the immediately preceding post above the viewport and the visible anchor post asynchronously.
        let collapsedCard = makeSamplePluginWidgetCard(
            id: "details-card",
            title: "Build Status",
            body: [
                UiWidgetNode.Stat(label: "Duration", value: "2m 14s", delta: "-12%", deltaTone: .positive, subtext: "Cached", weight: nil),
                UiWidgetNode.Collapsible(
                    id: "logs",
                    summary: "Build steps (4)",
                    children: [
                        UiWidgetNode.Text(text: "1. Checkout repository", style: .body, tone: .neutral, maxLines: nil, copyable: false),
                        UiWidgetNode.Text(text: "2. Compile KotlinSharedUI", style: .body, tone: .neutral, maxLines: nil, copyable: false),
                        UiWidgetNode.Text(text: "3. Run unit tests", style: .body, tone: .neutral, maxLines: nil, copyable: false),
                        UiWidgetNode.Text(text: "4. Archive artifacts", style: .body, tone: .neutral, maxLines: nil, copyable: false),
                    ],
                    expanded: false,
                    onToggle: nil
                ),
            ]
        )
        for targetIndex in [anchorIndex - 1, anchorIndex] {
            let enriched = makeSampleTimelinePost(
                id: "post-\(targetIndex)",
                text: "Post \(targetIndex) body text on the timeline.",
                pluginWidgets: [collapsedCard]
            )
            input.items[targetIndex] = .post(makeSampleTimelineItem(post: enriched))
        }
        controller.submit(input, columns: 1)
        await settle(controller: controller, collection: collection)

        let anchorPath = try XCTUnwrap(collection.readingIndexPath?(anchorID))
        let collapsedFrame = try XCTUnwrap(collection.layoutAttributesForItem(at: anchorPath)?.frame)
        XCTAssertEqual(
            collapsedFrame.minY - collection.contentOffset.y - collection.adjustedContentInset.top,
            anchorOffset,
            accuracy: 1.0,
            "Async plugin widget arrival must preserve reading scroll anchor"
        )
        let anchorCell = try XCTUnwrap(collection.cellForItem(at: anchorPath))
        XCTAssertFalse(descendants(anchorCell).compactMap { $0 as? TimelineHostedAccessoryView }.isEmpty)

        // Toggle Collapsible expanded: false -> true on the anchor post and verify cell grows & anchor stays preserved.
        let expandedCard = makeSamplePluginWidgetCard(
            id: "details-card",
            title: "Build Status",
            body: [
                UiWidgetNode.Stat(label: "Duration", value: "2m 14s", delta: "-12%", deltaTone: .positive, subtext: "Cached", weight: nil),
                UiWidgetNode.Collapsible(
                    id: "logs",
                    summary: "Build steps (4)",
                    children: [
                        UiWidgetNode.Text(text: "1. Checkout repository", style: .body, tone: .neutral, maxLines: nil, copyable: false),
                        UiWidgetNode.Text(text: "2. Compile KotlinSharedUI", style: .body, tone: .neutral, maxLines: nil, copyable: false),
                        UiWidgetNode.Text(text: "3. Run unit tests", style: .body, tone: .neutral, maxLines: nil, copyable: false),
                        UiWidgetNode.Text(text: "4. Archive artifacts", style: .body, tone: .neutral, maxLines: nil, copyable: false),
                    ],
                    expanded: true,
                    onToggle: nil
                ),
            ]
        )
        let expandedPost = makeSampleTimelinePost(
            id: "post-\(anchorIndex)",
            text: "Post \(anchorIndex) body text on the timeline.",
            pluginWidgets: [expandedCard]
        )
        input.items[anchorIndex] = .post(makeSampleTimelineItem(post: expandedPost))
        controller.submit(input, columns: 1)
        await settle(controller: controller, collection: collection)

        let expandedFrame = try XCTUnwrap(collection.layoutAttributesForItem(at: anchorPath)?.frame)
        XCTAssertGreaterThan(expandedFrame.height, collapsedFrame.height + 40)
        XCTAssertEqual(
            expandedFrame.minY - collection.contentOffset.y - collection.adjustedContentInset.top,
            anchorOffset,
            accuracy: 1.0,
            "Toggling a Collapsible widget section must preserve reading scroll anchor"
        )
    }

    func testMultiCardStackEqualizesHeightToTallestCardAndShowsPageDotsOnlyForMultipleCards() throws {
        let shortCard = makeSamplePluginWidgetCard(
            id: "short",
            title: "Short Summary",
            body: [
                UiWidgetNode.Text(text: "One short line.", style: .body, tone: .neutral, maxLines: 1, copyable: false),
            ]
        )
        let tallCard = makeSamplePluginWidgetCard(
            id: "tall",
            title: "Detailed Breakdown",
            body: [
                UiWidgetNode.Stat(label: "Requests", value: "128,490", delta: "+14.2%", deltaTone: .positive, subtext: "Last 24h", weight: nil),
                UiWidgetNode.Sparkline(points: [10.0, 25.0, 18.0, 42.0, 39.0], tone: .accent, minLabel: "10", maxLabel: "42", height: .md, weight: nil),
                UiWidgetNode.Divider.shared,
                UiWidgetNode.KeyValue(items: [
                    UiWidgetNode.KeyValueItem(label: "Region", value: "us-east-1", tone: .neutral, action: nil),
                    UiWidgetNode.KeyValueItem(label: "P99 Latency", value: "48 ms", tone: .positive, action: nil),
                    UiWidgetNode.KeyValueItem(label: "Error Rate", value: "0.02%", tone: .neutral, action: nil),
                ]),
            ]
        )

        let singleShortHost = UIHostingController(rootView: PluginWidgetStackView(cards: [shortCard]))
        let singleTallHost = UIHostingController(rootView: PluginWidgetStackView(cards: [tallCard]))
        let multiHost = UIHostingController(rootView: PluginWidgetStackView(cards: [shortCard, tallCard]))

        let shortHeight = singleShortHost.sizeThatFits(in: CGSize(width: 340, height: CGFloat.greatestFiniteMagnitude)).height
        let tallHeight = singleTallHost.sizeThatFits(in: CGSize(width: 340, height: CGFloat.greatestFiniteMagnitude)).height
        let multiHeight = multiHost.sizeThatFits(in: CGSize(width: 340, height: CGFloat.greatestFiniteMagnitude)).height

        XCTAssertGreaterThan(tallHeight, shortHeight + 80)
        // Multi-card stack (even with shortCard on page 0) must equal tallHeight + 8pt gap + 6pt page dots (= +14pt).
        XCTAssertEqual(multiHeight, tallHeight + 14, accuracy: 2.0)

        // Also verify StatusUIKitView offscreen measurement immediately accounts for the tallest card.
        let appearance = StatusUIKitAppearance(timeline: TimelineAppearance.companion.Default)
        let statusShort = StatusUIKitView()
        statusShort.configure(data: makeSampleTimelinePost(id: "s1", pluginWidgets: [shortCard]), appearance: appearance)
        let statusMulti = StatusUIKitView()
        statusMulti.configure(data: makeSampleTimelinePost(id: "s2", pluginWidgets: [shortCard, tallCard]), appearance: appearance)

        let statusShortHeight = try XCTUnwrap(statusShort.timelineHeight(for: 390))
        let statusMultiHeight = try XCTUnwrap(statusMulti.timelineHeight(for: 390))
        XCTAssertGreaterThan(statusMultiHeight, statusShortHeight + 80)
    }

    func testMultiCardReuseShrinksHeightSynchronouslyAndScopesSelectedPageToPostAndCardIdentity() async throws {
        let appearance = StatusUIKitAppearance(timeline: TimelineAppearance.companion.Default)
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let hostController = UIViewController()
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        window.rootViewController = hostController
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
        }

        let cellView = StatusUIKitView(frame: CGRect(x: 0, y: 0, width: 390, height: 400))
        hostController.view.addSubview(cellView)

        func makeStockCard(id: String, title: String, price: String, expanded: Bool? = nil) -> UiPluginWidgetCard {
            var bodyNodes: [UiWidgetNode] = [
                UiWidgetNode.Stat(label: title, value: price, delta: "+1.2%", deltaTone: .positive, subtext: nil, weight: nil),
            ]
            if let expanded {
                bodyNodes.append(
                    UiWidgetNode.Collapsible(
                        id: "lots",
                        summary: "Order book (4)",
                        children: [
                            UiWidgetNode.Text(text: "Bid 1: 100 @ \(price)", style: .body, tone: .neutral, maxLines: nil, copyable: false),
                            UiWidgetNode.Text(text: "Bid 2: 250 @ \(price)", style: .body, tone: .neutral, maxLines: nil, copyable: false),
                            UiWidgetNode.Text(text: "Ask 1: 150 @ \(price)", style: .body, tone: .neutral, maxLines: nil, copyable: false),
                            UiWidgetNode.Text(text: "Ask 2: 300 @ \(price)", style: .body, tone: .neutral, maxLines: nil, copyable: false),
                        ],
                        expanded: expanded,
                        onToggle: nil
                    )
                )
            }
            return UiPluginWidgetCard(
                pluginId: "org.example.stock",
                pluginName: "Stocks",
                pluginIconPath: nil,
                id: id,
                title: title,
                badge: nil,
                accentTone: .neutral,
                refreshable: true,
                isRefreshing: false,
                cardAction: nil,
                body: bodyNodes,
                actions: [],
                onRefresh: nil
            )
        }

        // 1. Configure Post A with a tall expanded 2-card stack and let SwiftUI lay out in-window.
        let postAExpanded = makeSampleTimelinePost(
            id: "post-a",
            pluginWidgets: [
                makeStockCard(id: "nvda", title: "NVDA", price: "$140.00"),
                makeStockCard(id: "aapl", title: "AAPL", price: "$228.00", expanded: true),
            ]
        )
        cellView.configure(data: postAExpanded, appearance: appearance)
        cellView.prepareForFitting(width: 390)
        let tallExpandedHeight = try XCTUnwrap(cellView.timelineHeight(for: 390))
        cellView.frame = CGRect(x: 0, y: 0, width: 390, height: tallExpandedHeight)
        for _ in 0..<15 {
            window.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(10))
        }

        // Swipe Post A's horizontal widget ScrollView to page 2 (AAPL).
        let scrollViewA = try XCTUnwrap(descendants(cellView).compactMap { $0 as? UIScrollView }.first)
        let pageStep = scrollViewA.bounds.width + 8
        XCTAssertGreaterThan(pageStep, 250)
        scrollViewA.setContentOffset(CGPoint(x: pageStep, y: 0), animated: false)
        scrollViewA.delegate?.scrollViewDidScroll?(scrollViewA)
        scrollViewA.delegate?.scrollViewDidEndDecelerating?(scrollViewA)
        for _ in 0..<10 {
            window.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(scrollViewA.contentOffset.x, pageStep, accuracy: 2.0)

        // 2. Collapse Collapsible on Post A (in-place update on same post & same card ID "org.example.stock:aapl"):
        //    - Height must shrink SYNCHRONOUSLY to match a fresh collapsed StatusUIKitView (no stale max height!).
        //    - Page 2 selection must be preserved on Post A!
        let postACollapsed = makeSampleTimelinePost(
            id: "post-a",
            pluginWidgets: [
                makeStockCard(id: "nvda", title: "NVDA", price: "$140.50"),
                makeStockCard(id: "aapl", title: "AAPL", price: "$228.50", expanded: false),
            ]
        )
        let freshCollapsedRef = StatusUIKitView()
        freshCollapsedRef.configure(data: postACollapsed, appearance: appearance)
        freshCollapsedRef.prepareForFitting(width: 390)
        let expectedCollapsedHeight = try XCTUnwrap(freshCollapsedRef.timelineHeight(for: 390))

        cellView.configure(data: postACollapsed, appearance: appearance)
        cellView.prepareForFitting(width: 390)
        let shrunkCollapsedHeight = try XCTUnwrap(cellView.timelineHeight(for: 390))
        XCTAssertLessThan(shrunkCollapsedHeight, tallExpandedHeight - 50, "Collapsing multi-card stack must shrink synchronously")
        XCTAssertEqual(shrunkCollapsedHeight, expectedCollapsedHeight, accuracy: 1.0)

        cellView.frame.size.height = shrunkCollapsedHeight
        for _ in 0..<10 {
            window.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(10))
        }
        let scrollViewAAfterUpdate = try XCTUnwrap(descendants(cellView).compactMap { $0 as? UIScrollView }.first)
        XCTAssertEqual(
            scrollViewAAfterUpdate.contentOffset.x,
            pageStep,
            accuracy: 2.0,
            "In-place update on the same post must preserve selected widget page"
        )

        // 3. Reuse the SAME StatusUIKitView for Post B with shorter 2-card content (even sharing the exact same
        //    "org.example.stock:nvda" and "org.example.stock:aapl" card IDs!):
        //    - Height must shrink SYNCHRONOUSLY to Post B's shorter height.
        //    - Selected page must reset to page 1 (offset 0) instead of bleeding from Post A!
        let postBShort = makeSampleTimelinePost(
            id: "post-b",
            pluginWidgets: [
                makeStockCard(id: "nvda", title: "NVDA", price: "$141.00"),
                makeStockCard(id: "aapl", title: "AAPL", price: "$229.00"),
            ]
        )
        let freshPostBRef = StatusUIKitView()
        freshPostBRef.configure(data: postBShort, appearance: appearance)
        freshPostBRef.prepareForFitting(width: 390)
        let expectedPostBHeight = try XCTUnwrap(freshPostBRef.timelineHeight(for: 390))

        cellView.configure(data: postBShort, appearance: appearance)
        cellView.prepareForFitting(width: 390)
        let reusedPostBHeight = try XCTUnwrap(cellView.timelineHeight(for: 390))
        XCTAssertLessThan(reusedPostBHeight, shrunkCollapsedHeight - 15)
        XCTAssertEqual(reusedPostBHeight, expectedPostBHeight, accuracy: 1.0)

        cellView.frame.size.height = reusedPostBHeight
        for _ in 0..<10 {
            window.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(10))
        }
        let scrollViewB = try XCTUnwrap(descendants(cellView).compactMap { $0 as? UIScrollView }.first)
        XCTAssertEqual(
            scrollViewB.contentOffset.x,
            0,
            accuracy: 1.0,
            "Reusing StatusUIKitView for a different post must reset widget pager to page 1"
        )
    }

    func testAllTwelveWidgetNodesLayoutConstraintsActionsAndPrimaryButtonContrast() async throws {
        let iconURL = FileManager.default.temporaryDirectory.appendingPathComponent("plugin-icon-\(UUID().uuidString).png")
        defer { try? FileManager.default.removeItem(at: iconURL) }
        let iconData = try XCTUnwrap(
            UIGraphicsImageRenderer(size: CGSize(width: 32, height: 32)).pngData { ctx in
                UIColor.systemBlue.setFill()
                ctx.fill(CGRect(x: 0, y: 0, width: 32, height: 32))
            }
        )
        try iconData.write(to: iconURL)

        var refreshedCount = 0
        var toggledCount = 0
        let longDigest = "sha256:" + String(repeating: "abcdef0123456789", count: 14)

        let allNodesCard = UiPluginWidgetCard(
            pluginId: "dev.example.metrics",
            pluginName: "Metrics",
            pluginIconPath: iconURL.path,
            id: "all-nodes",
            title: "Comprehensive Metrics & Live Matchup Dashboard Title That Uses Remaining Header Space",
            badge: UiWidgetBadge(text: "LIVE", tone: .positive),
            accentTone: .accent,
            refreshable: true,
            isRefreshing: false,
            cardAction: UiWidgetActionOpenUrl(url: "https://example.com/dashboard"),
            body: [
                UiWidgetNode.Row(
                    children: [
                        UiWidgetNode.Stat(label: "Primary", value: "200", delta: "+5%", deltaTone: .positive, subtext: nil, weight: KotlinFloat(value: 2.0)),
                        UiWidgetNode.Stat(label: "Secondary", value: "100", delta: "-1%", deltaTone: .negative, subtext: nil, weight: KotlinFloat(value: 1.0)),
                    ],
                    gap: .sm,
                    align: .start,
                    weight: nil
                ),
                UiWidgetNode.Row(
                    children: [
                        UiWidgetNode.Column(
                            children: [UiWidgetNode.Text(text: "Left Col", style: .subtitle, tone: .neutral, maxLines: 1, copyable: false)],
                            gap: .xs,
                            align: .start,
                            weight: nil
                        ),
                        UiWidgetNode.BadgeRow(badges: [UiWidgetBadge(text: "MID", tone: .warning)]),
                        UiWidgetNode.Text(text: "CODE_TAG", style: .code, tone: .neutral, maxLines: 1, copyable: true),
                    ],
                    gap: .sm,
                    align: .spaceBetween,
                    weight: nil
                ),
                UiWidgetNode.Matchup(
                    left: UiWidgetNode.MatchupSide(name: "Lakers", subtitle: "42-18", iconUrl: nil, score: "108"),
                    right: UiWidgetNode.MatchupSide(name: "Celtics", subtitle: "46-14", iconUrl: nil, score: "104"),
                    status: "Q4 01:42",
                    statusTone: .warning
                ),
                UiWidgetNode.Sparkline(points: [5.0, 5.0, 5.0, 5.0], tone: .positive, minLabel: "5.0", maxLabel: "5.0", height: .sm, weight: nil),
                UiWidgetNode.SegmentedBar(segments: [
                    UiWidgetNode.SegmentedBarSegment(label: "Pass", valueText: "80%", weight: 80.0, tone: .positive),
                    UiWidgetNode.SegmentedBarSegment(label: "Warn", valueText: "15%", weight: 15.0, tone: .warning),
                    UiWidgetNode.SegmentedBarSegment(label: "Fail", valueText: "5%", weight: 5.0, tone: .negative),
                ]),
                UiWidgetNode.KeyValue(items: [
                    UiWidgetNode.KeyValueItem(
                        label: "Tx Hash",
                        value: longDigest,
                        tone: .neutral,
                        action: UiWidgetActionCopy(text: longDigest, label: "Hash")
                    ),
                ]),
                UiWidgetNode.Image(url: "https://example.invalid/chart.png", alt: "Chart preview", aspectRatio: KotlinFloat(value: 2.0), cornerStyle: .rounded, weight: nil),
                UiWidgetNode.Divider.shared,
                UiWidgetNode.Collapsible(
                    id: "col-1",
                    summary: "More details",
                    children: [
                        UiWidgetNode.Text(text: "Expanded child", style: .caption, tone: .neutral, maxLines: nil, copyable: false),
                    ],
                    expanded: false,
                    onToggle: { toggledCount += 1 }
                ),
            ],
            actions: [
                UiWidgetButton(label: "Open Report", tone: .accent, style: .primary, action: UiWidgetActionOpenUrl(url: "https://example.com/report")),
                UiWidgetButton(label: "Refresh Data", tone: .neutral, style: .secondary, action: UiWidgetActionRefresh.shared),
            ],
            onRefresh: { refreshedCount += 1 }
        )

        let host = UIHostingController(rootView: AnyView(PluginWidgetStackView(cards: [allNodesCard]).frame(width: 360)))
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 900)
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
        }
        for _ in 0..<20 {
            window.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(10))
        }

        let fitted = host.sizeThatFits(in: CGSize(width: 360, height: CGFloat.greatestFiniteMagnitude))
        XCTAssertGreaterThan(fitted.height, 260)

        // Verify weighted Row layout (2:1 ratio) directly via WidgetWeightedRowLayout.
        var measuredFrames: [String: CGRect] = [:]
        func track(_ view: some View, _ key: String) -> some View {
            view.onGeometryChange(for: CGRect.self) { $0.frame(in: .named("cardSpace")) } action: { measuredFrames[key] = $0 }
        }
        let layoutProbe = VStack(spacing: 0) {
            PluginWidgetStackView(cards: [allNodesCard])
        }
        .coordinateSpace(name: "cardSpace")
        .frame(width: 360)
        host.rootView = AnyView(
            VStack {
                layoutProbe
                WidgetWeightedRowLayout(gap: 8, align: .start, fillWidth: true) {
                    track(Color.blue.frame(height: 20), "w2")
                        .layoutValue(key: WidgetRowWeightKey.self, value: 2.0)
                    track(Color.green.frame(height: 20), "w1")
                        .layoutValue(key: WidgetRowWeightKey.self, value: 1.0)
                    track(Color.orange.frame(width: 50, height: 20), "unweighted")
                }
                .coordinateSpace(name: "cardSpace")
                .frame(width: 360)
            }
            .frame(width: 360)
        )
        for _ in 0..<25 where measuredFrames.count < 3 {
            window.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(10))
        }
        let w2 = try XCTUnwrap(measuredFrames["w2"])
        let w1 = try XCTUnwrap(measuredFrames["w1"])
        let unweighted = try XCTUnwrap(measuredFrames["unweighted"])
        XCTAssertEqual(unweighted.width, 50, accuracy: 1.0)
        XCTAssertEqual(w2.width, w1.width * 2.0, accuracy: 1.5)

        // Verify WCAG AA contrast (>= 4.5) for Primary buttons across all tones in light and dark schemes.
        let tones: [UiWidgetTone] = [.neutral, .accent, .positive, .negative, .warning]
        for scheme in [ColorScheme.light, .dark] {
            var env = EnvironmentValues()
            env.colorScheme = scheme
            for tone in tones {
                let colors = pluginWidgetButtonColors(style: .primary, tone: tone, in: env)
                let ratio = pluginWidgetWCAGContrastRatio(colors.background, colors.foreground)
                XCTAssertGreaterThanOrEqual(
                    ratio,
                    4.5,
                    "Expected WCAG AA contrast >= 4.5 for Primary button tone \(tone) in \(scheme), got \(ratio)"
                )
            }
        }

        // Verify action execution helper: OpenUrl (https only), Copy (pasteboard), Refresh (callback).
        var openedURLs: [URL] = []
        executePluginWidgetAction(
            UiWidgetActionOpenUrl(url: "https://example.com/score"),
            openURL: { openedURLs.append($0) },
            onRefresh: { refreshedCount += 1 }
        )
        executePluginWidgetAction(
            UiWidgetActionOpenUrl(url: "javascript:alert(1)"),
            openURL: { openedURLs.append($0) },
            onRefresh: { refreshedCount += 1 }
        )
        XCTAssertEqual(openedURLs.map(\.absoluteString), ["https://example.com/score"])

        executePluginWidgetAction(
            UiWidgetActionCopy(text: "copied-token-123", label: "Token"),
            openURL: { openedURLs.append($0) },
            onRefresh: { refreshedCount += 1 }
        )
        XCTAssertEqual(UIPasteboard.general.string, "copied-token-123")

        executePluginWidgetAction(
            UiWidgetActionRefresh.shared,
            openURL: { openedURLs.append($0) },
            onRefresh: { refreshedCount += 1 }
        )
        XCTAssertEqual(refreshedCount, 1)
        _ = toggledCount
    }

    func testStatusViewRendersPluginWidgetsOnlyWhenNotQuoteAndAttachmentsEnabled() {
        let card = makeSamplePluginWidgetCard(id: "w-1", title: "Live Score")
        let plain = makeSampleTimelinePost(id: "sv-1", pluginWidgets: [])
        let enriched = makeSampleTimelinePost(id: "sv-1", pluginWidgets: [card])

        func measureStatus(
            _ post: UiTimelineV2.Post,
            isQuote: Bool = false,
            showAttachments: Bool = true,
            isGalleryFallback: Bool = false,
            appearance: TimelineAppearance = TimelineAppearance.companion.Default
        ) -> CGFloat {
            let view = StatusView(data: post, isQuote: isQuote, showAttachments: showAttachments, forceHideActions: true)
                .environment(\.timelineGalleryFallback, isGalleryFallback)
                .environment(\.timelineAppearance, appearance)
            return UIHostingController(rootView: view).sizeThatFits(in: CGSize(width: 360, height: CGFloat.greatestFiniteMagnitude)).height
        }

        let plainHeight = measureStatus(plain)
        let enrichedHeight = measureStatus(enriched)
        XCTAssertGreaterThan(enrichedHeight, plainHeight + 40)
        XCTAssertEqual(measureStatus(enriched, showAttachments: false), plainHeight, accuracy: 1.0)
        XCTAssertEqual(measureStatus(enriched, isQuote: true), measureStatus(plain, isQuote: true), accuracy: 1.0)
        XCTAssertEqual(
            measureStatus(enriched, isGalleryFallback: true),
            measureStatus(plain, isGalleryFallback: true),
            accuracy: 1.0
        )
        let warned = makeSampleTimelinePost(id: "sv-1", warning: "Spoiler", pluginWidgets: [])
        let warnedWithCard = makeSampleTimelinePost(id: "sv-1", warning: "Spoiler", pluginWidgets: [card])
        XCTAssertEqual(measureStatus(warnedWithCard), measureStatus(warned), accuracy: 1.0)
        XCTAssertGreaterThan(measureStatus(warnedWithCard, appearance: autoExpandWarnings),
                             measureStatus(warned, appearance: autoExpandWarnings) + 40)
        XCTAssertNotEqual(warned.renderHash, warnedWithCard.renderHash)
        XCTAssertEqual(StatusReadingContent(warned), StatusReadingContent(warnedWithCard))
        XCTAssertNotEqual(StatusReadingContent(warned), StatusReadingContent(plain))
    }

    func testPluginManagementPermissionDescriptionAndTimelineProcessorToggle() async throws {
        let permission = PluginPermission(
            type: .timelinePostAccess,
            origin: "",
            cookieName: nil,
            platformId: nil
        )
        let permissionDescription = pluginPermissionText(permission)
        let expectedLocalized = String(
            localized: "plugin_permission_timeline_post_access",
            defaultValue: "Read displayed timeline posts to attach widgets, which may send post content to permitted network origins"
        )
        XCTAssertEqual(permissionDescription, expectedLocalized)
        XCTAssertFalse(permissionDescription.isEmpty)
        XCTAssertNotEqual(permissionDescription, "plugin_permission_timeline_post_access")

        let withoutProcessor = PluginManagementItemV1(
            pluginId: "dev.example.plain",
            platformId: "plain",
            nameText: UiTextRaw(string: "Plain Plugin"),
            version: "1.0.0",
            iconPath: "",
            sourceUrl: nil,
            availableUpdate: nil,
            authoring: nil,
            hasSettings: false,
            hasPlatformExtension: false,
            hasTimelineProcessor: false,
            timelineProcessorEnabled: false,
            updatedAt: nil,
            sourceCommitSha: nil
        )
        let withProcessor = PluginManagementItemV1(
            pluginId: "dev.example.widgets",
            platformId: "",
            nameText: UiTextRaw(string: "Widget Enricher"),
            version: "1.2.0",
            iconPath: "",
            sourceUrl: nil,
            availableUpdate: nil,
            authoring: nil,
            hasSettings: false,
            hasPlatformExtension: false,
            hasTimelineProcessor: true,
            timelineProcessorEnabled: false,
            updatedAt: nil,
            sourceCommitSha: nil
        )

        var toggledEvents: [(String, Bool)] = []
        let host = UIHostingController(
            rootView: VStack {
                PluginInstalledRow(
                    plugin: withoutProcessor,
                    busy: false,
                    onUpdate: {},
                    onCancelAuthoringUpdate: {},
                    onOpenExtension: {},
                    onOpenSettings: {},
                    onUninstall: {},
                    onToggleTimelineProcessor: { toggledEvents.append((withoutProcessor.pluginId, $0)) }
                )
                PluginInstalledRow(
                    plugin: withProcessor,
                    busy: false,
                    onUpdate: {},
                    onCancelAuthoringUpdate: {},
                    onOpenExtension: {},
                    onOpenSettings: {},
                    onUninstall: {},
                    onToggleTimelineProcessor: { toggledEvents.append((withProcessor.pluginId, $0)) }
                )
            }
            .frame(width: 340)
        )
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 400)
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
        }
        for _ in 0..<20 {
            window.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(10))
        }

        let switches = descendants(host.view).compactMap { $0 as? UISwitch }
        XCTAssertEqual(switches.count, 1, "Only hasTimelineProcessor plugins should render a UISwitch")
        let toggleSwitch = try XCTUnwrap(switches.first)
        XCTAssertFalse(toggleSwitch.isOn)
        toggleSwitch.setOn(true, animated: false)
        toggleSwitch.sendActions(for: .valueChanged)
        XCTAssertEqual(toggledEvents.count, 1)
        XCTAssertEqual(toggledEvents.first?.0, "dev.example.widgets")
        XCTAssertEqual(toggledEvents.first?.1, true)
    }

    func testExecuteActionBlocksUserInfoUrlsAndMultiCardImageStackMeasuresCleanly() throws {
        var opened: [URL] = []
        executePluginWidgetAction(
            UiWidgetActionOpenUrl(url: "https://user:secret@evil.example/path"),
            openURL: { opened.append($0) },
            onRefresh: nil
        )
        executePluginWidgetAction(
            UiWidgetActionOpenUrl(url: "https://@evil.example/path"),
            openURL: { opened.append($0) },
            onRefresh: nil
        )
        executePluginWidgetAction(
            UiWidgetActionOpenUrl(url: "https://app.polymarket.com/event/fed"),
            openURL: { opened.append($0) },
            onRefresh: nil
        )
        XCTAssertEqual(opened.map(\.absoluteString), ["https://app.polymarket.com/event/fed"])

        let imageCard = UiPluginWidgetCard(
            pluginId: "dev.example.media",
            pluginName: "Media",
            pluginIconPath: "/nonexistent/missing-icon.png",
            id: "img-1",
            title: "Chart",
            badge: nil,
            accentTone: .neutral,
            refreshable: false,
            isRefreshing: false,
            cardAction: nil,
            body: [
                UiWidgetNode.Stat(
                    label: "Notional",
                    value: "$123,456,789,012,345.67 USD PER UNIT",
                    delta: "+9.8%",
                    deltaTone: .positive,
                    subtext: nil,
                    weight: nil
                ),
                UiWidgetNode.Image(
                    url: "https://example.com/chart.png",
                    alt: "Chart",
                    aspectRatio: KotlinFloat(value: 2.0),
                    cornerStyle: .rounded,
                    weight: nil
                ),
            ],
            actions: [],
            onRefresh: nil
        )
        let shortCard = makeSamplePluginWidgetCard(
            id: "img-2",
            title: "Short",
            body: [UiWidgetNode.Text(text: "Note", style: .caption, tone: .neutral, maxLines: 1, copyable: false)]
        )
        let singleHost = UIHostingController(rootView: PluginWidgetStackView(cards: [imageCard]))
        let multiHost = UIHostingController(rootView: PluginWidgetStackView(cards: [shortCard, imageCard], postIdentity: "post-img"))
        let singleHeight = singleHost.sizeThatFits(in: CGSize(width: 340, height: CGFloat.greatestFiniteMagnitude)).height
        let multiHeight = multiHost.sizeThatFits(in: CGSize(width: 340, height: CGFloat.greatestFiniteMagnitude)).height
        XCTAssertEqual(multiHeight, singleHeight + 14, accuracy: 2.0)
    }

    // MARK: - Helpers

    private func settle(controller: UITimelineCollectionViewController, collection: TimelineCollectionView) async {
        for _ in 0..<25 {
            controller.view.setNeedsLayout()
            controller.view.layoutIfNeeded()
            collection.layoutIfNeeded()
            try? await Task.sleep(for: .milliseconds(10))
        }
    }

    private func descendants(_ view: UIView) -> [UIView] {
        [view] + view.subviews.flatMap(descendants)
    }

    private func makeSamplePluginWidgetCard(
        id: String,
        title: String,
        body: [UiWidgetNode]? = nil
    ) -> UiPluginWidgetCard {
        UiPluginWidgetCard(
            pluginId: "dev.example.sports",
            pluginName: "Sports Live",
            pluginIconPath: nil,
            id: id,
            title: title,
            badge: UiWidgetBadge(text: "LIVE", tone: .positive),
            accentTone: .neutral,
            refreshable: true,
            isRefreshing: false,
            cardAction: nil,
            body: body ?? [
                UiWidgetNode.Matchup(
                    left: UiWidgetNode.MatchupSide(name: "Lakers", subtitle: "LAL", iconUrl: nil, score: "102"),
                    right: UiWidgetNode.MatchupSide(name: "Warriors", subtitle: "GSW", iconUrl: nil, score: "99"),
                    status: "Q4 2:15",
                    statusTone: .accent
                ),
            ],
            actions: [],
            onRefresh: nil
        )
    }

    private var autoExpandWarnings: TimelineAppearance {
        let helper = TimelinePresentationAppearancePatchHelper.shared
        return helper.resolve(base: TimelineAppearance.companion.Default,
                              patch: helper.setExpandContentWarning(patch: helper.empty, value: true))
    }

    private func makeSampleTimelinePost(
        id: String,
        text: String = "Checking the game tonight!",
        warning: String? = nil,
        pluginWidgets: [UiPluginWidgetCard]
    ) -> UiTimelineV2.Post {
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
        func richText(_ text: String) -> UiRichText {
            UiRichText(renderRuns: [RenderContent.Text(runs: [RenderRun.Text(text: text, style: style)], block: RenderBlockStyle())],
                       isRtl: false, raw: text, innerText: text, imageUrls: [])
        }
        let rich = richText(text)
        let profile = UiProfile(
            key: MicroBlogKey(id: "user", host: "example.invalid"),
            handle: UiHandle(raw: "reader", host: "example.invalid", displayOverride: "@reader"),
            avatar: nil as UiMediaImage?,
            nameInternal: rich,
            platformId: "mastodon",
            platformIcon: IconType.Material(icon: .world),
            clickEvent: ClickEventNoop.shared,
            banner: nil,
            description: nil,
            sourceLanguages: [],
            translationDisplayState: .hidden,
            matrices: UiProfile.Matrices(fansCount: 0, followsCount: 0, statusesCount: 0, platformFansCount: nil),
            mark: [],
            bottomContent: nil,
            pluginId: nil,
            clearedMarks: []
        )
        let action = ActionMenu.Item(
            updateKey: "like",
            icon: .like,
            text: nil,
            count: UiNumber(value: 1),
            color: .contentColor,
            clickEvent: ClickEventNoop.shared,
            actionFamily: .like,
            enabled: true
        )
        return UiTimelineV2.Post(
            platformId: "mastodon",
            images: [],
            sensitive: false,
            contentWarning: warning.map { UiTranslatableText(original: richText($0), translation: nil) },
            user: profile,
            platformIcon: IconType.Material(icon: .world),
            sourceLanguages: [],
            translationDisplayState: .hidden,
            content: UiTranslatableText(original: rich, translation: nil),
            actions: [action],
            poll: nil,
            statusKey: MicroBlogKey(id: id, host: "example.invalid"),
            card: nil,
            createdAt: KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 0).toUi(),
            emojiReactions: [],
            sourceChannel: nil,
            visibility: nil,
            replyToHandle: nil,
            references: [],
            clickEvent: ClickEventNoop.shared,
            mediaClickPolicy: .openStatusMedia,
            accountType: AccountType.Guest.shared,
            itemKey: id,
            pluginId: nil,
            authorSubtitleInternal: nil,
            communityNote: nil,
            pluginWidgets: pluginWidgets
        )
    }

    private func makeSampleTimelineItem(post: UiTimelineV2.Post) -> UiTimelineV2.TimelinePostItem {
        UiTimelineV2.TimelinePostItem(
            post: post,
            presentation: UiTimelineV2.PostPresentation(
                message: nil,
                inlineParents: [],
                quotes: [],
                repost: nil,
                notificationKey: nil
            ),
            itemKey: post.itemKey ?? post.statusKey.id
        )
    }
}

private final class HostedMockTouch: UITouch {
    private let target: UIView

    init(target: UIView) {
        self.target = target
        super.init()
    }

    override var view: UIView? { target }
}
