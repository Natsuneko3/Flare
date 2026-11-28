import AppKit
import Foundation
import KotlinSharedUI
import PDFKit
import SwiftUI
import XCTest
@testable import FlareAppleUI

@MainActor
final class PluginMarketplaceViewTests: XCTestCase {
    func testEntryExportsFullRepositoryNameAndBrowserURLToSwift() {
        let commit = String(repeating: "a", count: 40)
        let rootEntry = PluginMarketplaceEntryV1(
            id: "dev.maxlec.zhihu",
            name: "知乎",
            description: "知乎热榜、推荐、关注动态与回答/文章详情",
            repository: "https://github.com/anamaxlec/flare-zhihu-plugin",
            icon: "", version: "0.1.11", updatedAt: "2026-11-11", commitSha: nil
        )
        let subPathEntry = PluginMarketplaceEntryV1(
            id: "dev.alice.zhihu",
            name: "知乎",
            description: "轻量只读版知乎时间线与热榜浏览",
            repository: "https://github.com/alice/flare-plugins.git?path=/plugins/My%20Plugin/",
            icon: "", version: nil, updatedAt: nil, commitSha: commit
        )
        let malformed = PluginMarketplaceEntryV1(
            id: "broken.entry",
            name: "Broken",
            description: "Malformed source",
            repository: "not-a-github-url",
            icon: "", version: nil, updatedAt: nil, commitSha: nil
        )

        XCTAssertEqual(rootEntry.repositoryFullName, "anamaxlec/flare-zhihu-plugin")
        XCTAssertEqual(rootEntry.repositoryWebUrl, rootEntry.repository)
        XCTAssertEqual(subPathEntry.repositoryFullName, "alice/flare-plugins/plugins/My Plugin")
        XCTAssertEqual(subPathEntry.repositoryWebUrl, "https://github.com/alice/flare-plugins/tree/\(commit)/plugins/My%20Plugin")
        XCTAssertEqual(malformed.repositoryFullName, "not-a-github-url")
        XCTAssertEqual(rootEntry.version, "0.1.11")
    }

    func testReleaseDayUsesTheCalendarDayInTheUsersLocale() {
        let english = Locale(identifier: "en_US")
        XCTAssertEqual(PluginReleaseMetadata.formattedDay("2026-11-01", locale: english), "Nov 1, 2026")
        XCTAssertEqual(PluginReleaseMetadata.formattedDay("2026-11-01", locale: Locale(identifier: "zh_CN")), "2026年11月1日")
        XCTAssertNil(PluginReleaseMetadata.formattedDay(nil, locale: english))
        XCTAssertNil(PluginReleaseMetadata.formattedDay("2026-02-31", locale: english))
    }

    func testCommunityNoticeVisibilityDependsOnBlankQueryAndNonEmptyPlugins() {
        XCTAssertTrue(showsMarketplaceCommunityNotice(query: "  \n ", hasPlugins: true))
        XCTAssertFalse(showsMarketplaceCommunityNotice(query: "@alice", hasPlugins: true))
        XCTAssertFalse(showsMarketplaceCommunityNotice(query: "", hasPlugins: false))
    }

    func testMarketplaceShowsLocalStatusOnlyForManagedUninstalledPlugins() throws {
        let entry = PluginMarketplaceEntryV1(
            id: "dev.example.reader", name: "Reader", description: "Read timelines.",
            repository: "https://github.com/example/reader", icon: "",
            version: nil, updatedAt: nil, commitSha: nil
        )
        func rowText(installed: Bool, managed: Bool) throws -> String {
            let row = PluginMarketplaceRow(
                entry: entry, installed: installed, managed: managed, canInstall: true,
                onInstall: {}, onManage: {}
            ).frame(width: 720)
            let data = NSMutableData()
            let consumer = try XCTUnwrap(CGDataConsumer(data: data))
            var bounds = CGRect(x: 0, y: 0, width: 720, height: 180)
            let context = try XCTUnwrap(CGContext(consumer: consumer, mediaBox: &bounds, nil))
            context.beginPDFPage(nil)
            ImageRenderer(content: row).render { _, draw in draw(context) }
            context.endPDFPage()
            context.closePDF()
            let text = try XCTUnwrap(PDFDocument(data: data as Data)?.string)
            return text.filter { !$0.isWhitespace }
        }

        let localLabel = String(localized: "plugin_marketplace_local").filter { !$0.isWhitespace }
        let manageLabel = String(localized: "plugin_marketplace_manage").filter { !$0.isWhitespace }
        let local = try rowText(installed: false, managed: true)
        XCTAssertTrue(local.contains(localLabel), "Expected \(localLabel) among the visible row text: \(local)")
        XCTAssertTrue(local.contains(manageLabel), local)
        XCTAssertFalse(try rowText(installed: true, managed: true).contains(localLabel))
        XCTAssertFalse(try rowText(installed: false, managed: false).contains(localLabel))
    }

    func testMacActionsStaySideBySideAtTheHeightOfTheRowCenter() async throws {
        var frames = [String: CGRect]()
        func measure(_ view: some View, _ name: String) -> some View {
            view.onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frames[name] = $0 }
        }
        let row = pluginListRow(name: "Community Reader", iconPath: "") {
            measure(Text(verbatim: "example/community-plugins/social/reader")
                .frame(maxWidth: .infinity, minHeight: 100, alignment: .topLeading), "details")
        } actions: {
            measure(pluginRowButton("plugin_marketplace_install") {}.buttonStyle(.borderedProminent), "install")
            measure(Color.clear.frame(width: 28, height: 28), "more")
        }
        let view = NSHostingView(rootView: measure(row, "row").frame(width: 340))
        let window = NSWindow(contentRect: CGRect(x: 0, y: 0, width: 340, height: 180),
                              styleMask: [.borderless], backing: .buffered, defer: false)
        window.contentView = view
        for _ in 0..<60 where frames.count < 4 {
            view.layoutSubtreeIfNeeded()
            try await Task.sleep(for: .milliseconds(15))
        }
        let bounds = try XCTUnwrap(frames["row"])
        let details = try XCTUnwrap(frames["details"])
        let install = try XCTUnwrap(frames["install"])
        let more = try XCTUnwrap(frames["more"])
        XCTAssertLessThan(details.maxX, install.minX)
        XCTAssertLessThan(install.maxX, more.minX)
        XCTAssertEqual(install.midY, bounds.midY, accuracy: 1)
        XCTAssertEqual(more.midY, bounds.midY, accuracy: 1)
    }

    func testMacMarketplaceRowWrapsLongTitleOnNarrowWidthAndRendersCommunityNotice() throws {
        let longTitleEntry = PluginMarketplaceEntryV1(
            id: "dev.bob.long",
            name: "Super Long Community Plugin Title That Wraps Cleanly On Narrow Screens",
            description: "知乎热榜、推荐、关注动态与回答/文章详情",
            repository: "https://github.com/bob-developer/super-long-plugin",
            icon: "", version: "0.1.11", updatedAt: "2026-11-11", commitSha: nil
        )
        let second = PluginMarketplaceEntryV1(
            id: "dev.alice.zhihu",
            name: "知乎",
            description: "轻量只读版知乎时间线与热榜浏览",
            repository: "https://github.com/alice/flare-plugins?path=/plugins/zhihu-lite",
            icon: "", version: nil, updatedAt: nil, commitSha: nil
        )

        func measureHeight(width: CGFloat) -> CGFloat {
            let view = VStack(alignment: .leading, spacing: 14) {
                GroupBox {
                    PluginMarketplaceCommunityNoticeView().padding(4)
                }
                PluginMarketplaceRow(
                    entry: longTitleEntry,
                    installed: true,
                    managed: true,
                    canInstall: true,
                    onInstall: {},
                    onManage: {}
                )
                PluginMarketplaceRow(
                    entry: second,
                    installed: false,
                    managed: false,
                    canInstall: true,
                    onInstall: {},
                    onManage: {}
                )
            }
            .padding(16)
            .frame(width: width)
            let hosting = NSHostingView(rootView: view)
            hosting.layoutSubtreeIfNeeded()
            return hosting.fittingSize.height
        }

        let wideHeight = measureHeight(width: 720)
        let narrowHeight = measureHeight(width: 340)
        XCTAssertGreaterThan(wideHeight, 0)
        XCTAssertGreaterThan(narrowHeight, wideHeight, "The repository link and long title must wrap at narrow widths")

        if let outputPath = ProcessInfo.processInfo.environment["FLARE_MARKETPLACE_PREVIEW_PNG"], !outputPath.isEmpty {
            let preview = NSHostingView(
                rootView: VStack(alignment: .leading, spacing: 14) {
                    GroupBox { PluginMarketplaceCommunityNoticeView().padding(4) }
                    PluginMarketplaceRow(
                        entry: second,
                        installed: false,
                        managed: false,
                        canInstall: true,
                        onInstall: {},
                        onManage: {}
                    )
                }
                .padding(20)
                .frame(width: 560)
                .background(Color(nsColor: .windowBackgroundColor))
            )
            let fitting = preview.fittingSize
            preview.frame = NSRect(origin: .zero, size: NSSize(width: 560, height: max(fitting.height, 200)))
            preview.layoutSubtreeIfNeeded()
            let rep = try XCTUnwrap(preview.bitmapImageRepForCachingDisplay(in: preview.bounds))
            preview.cacheDisplay(in: preview.bounds, to: rep)
            let png = try XCTUnwrap(rep.representation(using: .png, properties: [:]))
            try png.write(to: URL(fileURLWithPath: outputPath))
        }
    }

    func testMacPluginPermissionDescriptionAndTimelineProcessorToggle() async throws {
        let permission = PluginPermission(
            type: .timelinePostAccess,
            origin: "",
            cookieName: nil,
            platformId: nil
        )
        let description = pluginPermissionText(permission)
        let expectedLocalized = String(
            localized: "plugin_permission_timeline_post_access",
            defaultValue: "Read displayed timeline posts to attach widgets, which may send post content to permitted network origins"
        )
        XCTAssertEqual(description, expectedLocalized)
        XCTAssertFalse(description.isEmpty)
        XCTAssertNotEqual(description, "plugin_permission_timeline_post_access")

        let plainItem = PluginManagementItemV1(
            pluginId: "dev.example.plain",
            platformId: "plain",
            nameText: UiTextRaw(string: "Plain Reader"),
            version: "0.1.0",
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
        let processorItem = PluginManagementItemV1(
            pluginId: "dev.example.widgets",
            platformId: "",
            nameText: UiTextRaw(string: "Score Enricher"),
            version: "0.2.0",
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

        var toggled: [(String, Bool)] = []
        let view = NSHostingView(
            rootView: VStack(alignment: .leading, spacing: 12) {
                PluginInstalledRow(
                    plugin: plainItem,
                    busy: false,
                    onUpdate: {},
                    onCancelAuthoringUpdate: {},
                    onOpenExtension: {},
                    onOpenSettings: {},
                    onUninstall: {},
                    onToggleTimelineProcessor: { toggled.append((plainItem.pluginId, $0)) }
                )
                PluginInstalledRow(
                    plugin: processorItem,
                    busy: false,
                    onUpdate: {},
                    onCancelAuthoringUpdate: {},
                    onOpenExtension: {},
                    onOpenSettings: {},
                    onUninstall: {},
                    onToggleTimelineProcessor: { toggled.append((processorItem.pluginId, $0)) }
                )
            }
            .frame(width: 560)
        )
        let window = NSWindow(
            contentRect: CGRect(x: 0, y: 0, width: 560, height: 280),
            styleMask: [.borderless],
            backing: .buffered,
            defer: false
        )
        window.contentView = view
        for _ in 0..<20 {
            view.layoutSubtreeIfNeeded()
            try await Task.sleep(for: .milliseconds(10))
        }

        func nsDescendants(_ root: NSView) -> [NSView] {
            [root] + root.subviews.flatMap(nsDescendants)
        }
        let switches = nsDescendants(view).compactMap { $0 as? NSSwitch }
        XCTAssertEqual(switches.count, 1, "Only hasTimelineProcessor plugins should render an NSSwitch on macOS")
        let toggle = try XCTUnwrap(switches.first)
        XCTAssertEqual(toggle.state, .off)
        toggle.performClick(nil)
        XCTAssertEqual(toggled.count, 1)
        XCTAssertEqual(toggled.first?.0, "dev.example.widgets")
        XCTAssertEqual(toggled.first?.1, true)
    }

    func testMacPluginWidgetStackEqualizesHeightAndExecutesPasteboardAndUrlActions() {
        let shortCard = UiPluginWidgetCard(
            pluginId: "dev.example.one",
            pluginName: "One",
            pluginIconPath: nil,
            id: "c1",
            title: "Compact",
            badge: nil,
            accentTone: .neutral,
            refreshable: false,
            isRefreshing: false,
            cardAction: nil,
            body: [
                UiWidgetNode.Text(text: "Single line", style: .body, tone: .neutral, maxLines: 1, copyable: false),
            ],
            actions: [],
            onRefresh: nil
        )
        let tallCard = UiPluginWidgetCard(
            pluginId: "dev.example.two",
            pluginName: "Two",
            pluginIconPath: nil,
            id: "c2",
            title: "Expanded Stats",
            badge: UiWidgetBadge(text: "LIVE", tone: .accent),
            accentTone: .accent,
            refreshable: true,
            isRefreshing: true,
            cardAction: nil,
            body: [
                UiWidgetNode.Stat(label: "Users", value: "8,420", delta: "+9%", deltaTone: .positive, subtext: "Online", weight: nil),
                UiWidgetNode.Sparkline(points: [1.0, 4.0, 2.0, 8.0], tone: .accent, minLabel: "1", maxLabel: "8", height: .md, weight: nil),
                UiWidgetNode.KeyValue(items: [
                    UiWidgetNode.KeyValueItem(label: "Cluster", value: "ap-northeast-1", tone: .neutral, action: nil),
                    UiWidgetNode.KeyValueItem(label: "Status", value: "Healthy", tone: .positive, action: nil),
                ]),
            ],
            actions: [
                UiWidgetButton(label: "Details", tone: .accent, style: .primary, action: UiWidgetActionOpenUrl(url: "https://example.com")),
            ],
            onRefresh: nil
        )

        func measureMac(_ cards: [UiPluginWidgetCard]) -> CGFloat {
            let host = NSHostingView(rootView: PluginWidgetStackView(cards: cards).frame(width: 420))
            host.layoutSubtreeIfNeeded()
            return host.fittingSize.height
        }

        let shortH = measureMac([shortCard])
        let tallH = measureMac([tallCard])
        let multiH = measureMac([shortCard, tallCard])
        XCTAssertGreaterThan(tallH, shortH + 60)
        XCTAssertEqual(multiH, tallH + 14, accuracy: 2.0)

        var refreshed = 0
        var opened: [URL] = []
        executePluginWidgetAction(
            UiWidgetActionCopy(text: "mac-copied-value", label: nil),
            openURL: { opened.append($0) },
            onRefresh: { refreshed += 1 }
        )
        XCTAssertEqual(NSPasteboard.general.string(forType: .string), "mac-copied-value")
        executePluginWidgetAction(
            UiWidgetActionOpenUrl(url: "https://example.com/mac"),
            openURL: { opened.append($0) },
            onRefresh: { refreshed += 1 }
        )
        executePluginWidgetAction(
            UiWidgetActionOpenUrl(url: "http://insecure.example.com"),
            openURL: { opened.append($0) },
            onRefresh: { refreshed += 1 }
        )
        XCTAssertEqual(opened.map(\.absoluteString), ["https://example.com/mac"])
        executePluginWidgetAction(
            UiWidgetActionRefresh.shared,
            openURL: { opened.append($0) },
            onRefresh: { refreshed += 1 }
        )
        XCTAssertEqual(refreshed, 1)
    }
}
