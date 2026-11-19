import KotlinSharedUI
import SwiftUI
import UIKit
import XCTest
@testable import FlareAppleUI

@MainActor
final class PluginMarketplaceLayoutTests: XCTestCase {
    func testNarrowReleaseMetadataMovesTheWholeUpdatedDateBelowTheVersion() throws {
        let version = Text(verbatim: "v0.1.8")
        let updated = Text("plugin_updated_at \("2026年11月9日")")
        let metadata = PluginReleaseMetadata(version: "0.1.8", updatedAt: "2026-11-09")
        let stacked = VStack(alignment: .leading, spacing: 2) {
            version
            updated
        }
        .font(.caption)
        .monospacedDigit()
        .foregroundStyle(.secondary)

        func render(_ content: some View) throws -> Data {
            let view = content
                .frame(width: 140, alignment: .leading)
                .environment(\.locale, Locale(identifier: "zh_CN"))
                .dynamicTypeSize(.large)
            return try XCTUnwrap(ImageRenderer(content: view).uiImage?.pngData())
        }
        XCTAssertEqual(try render(metadata), try render(stacked))
    }

    func testPrimaryButtonKeepsAMinimumTouchTargetAtSmallTextSizes() {
        let button = pluginRowButton("plugin_update") {}.buttonStyle(.borderedProminent).dynamicTypeSize(.small)
        let size = UIHostingController(rootView: button).sizeThatFits(in: CGSize(width: 180, height: 120))
        XCTAssertGreaterThanOrEqual(size.width, 44)
        XCTAssertGreaterThanOrEqual(size.height, 44)
    }

    func testTrailingActionsStaySideBySideWithLargeLocalizedText() async throws {
        var frames = [String: CGRect]()
        func measure(_ view: some View, _ name: String) -> some View {
            view.onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frames[name] = $0 }
        }
        let more = Color.clear.frame(width: 44, height: 44)
        let rows = VStack {
            measure(pluginListRow(name: "Community Timeline Reader", iconPath: "") {
                measure(Text(verbatim: "example/community-plugins/social/timeline-reader")
                    .frame(maxWidth: .infinity, alignment: .leading), "details")
            } actions: {
                measure(pluginRowButton("plugin_ai_stop") {}.buttonStyle(.bordered), "stop")
                measure(more, "more")
            }, "row")
            pluginListRow(name: "Pixelfed", iconPath: "") {
                Text(verbatim: "v0.1.0")
            } actions: {
                measure(more, "onlyMore")
            }
        }
        .frame(width: 260)
        .environment(\.locale, Locale(identifier: "ca"))
        .dynamicTypeSize(.accessibility3)
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.rootViewController = UIHostingController(rootView: rows)
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        for _ in 0..<60 where frames.count < 5 {
            window.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(15))
        }
        let row = try XCTUnwrap(frames["row"])
        let details = try XCTUnwrap(frames["details"])
        let stop = try XCTUnwrap(frames["stop"])
        let menu = try XCTUnwrap(frames["more"])
        let onlyMenu = try XCTUnwrap(frames["onlyMore"])
        XCTAssertEqual(row.width, 260, accuracy: 1)
        XCTAssertGreaterThan(details.width, 80)
        XCTAssertLessThan(details.maxX, stop.minX)
        XCTAssertLessThan(stop.maxX, menu.minX)
        XCTAssertEqual(stop.midY, row.midY, accuracy: 1)
        XCTAssertEqual(menu.midY, row.midY, accuracy: 1)
        XCTAssertEqual(menu.maxX, onlyMenu.maxX, accuracy: 1)
    }

    func testLongRepositoryAndTitleFitAtAccessibilityTextSizes() {
        let entry = PluginMarketplaceEntryV1(
            id: "dev.example.reader", name: "Community Timeline Reader",
            description: "Read timelines and saved posts.",
            repository: "https://github.com/example/community-plugins?path=/social/timeline-reader",
            icon: "", version: "0.1.11", updatedAt: "2026-11-11", commitSha: nil
        )
        func rowSize(at textSize: DynamicTypeSize) -> CGSize {
            let row = PluginMarketplaceRow(entry: entry, installed: true, managed: true, canInstall: true, onInstall: {}, onManage: {})
                .dynamicTypeSize(textSize)
            let host = UIHostingController(rootView: row)
            return host.sizeThatFits(in: CGSize(width: 290, height: 2_000))
        }
        let normal = rowSize(at: .large)
        let accessible = rowSize(at: .accessibility3)
        XCTAssertEqual(normal.width, 290, accuracy: 1)
        XCTAssertEqual(accessible.width, 290, accuracy: 1)
        XCTAssertGreaterThan(accessible.height, normal.height)
    }
}
