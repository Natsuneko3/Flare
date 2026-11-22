import AppKit
import Kingfisher
import KotlinSharedUI
import PDFKit
import SwiftUI
import XCTest
@testable import FlareAppleUI

@MainActor
final class PluginWidgetLayoutTests: XCTestCase {
    private func card(
        body: [UiWidgetNode] = [], badge: String? = nil,
        icon: String? = nil, onRefresh: (() -> Void)? = nil
    ) -> UiPluginWidgetCard {
        UiPluginWidgetCard(
            pluginId: "fixture", pluginName: "Fixture", pluginIconPath: icon, id: "card", title: "Card title",
            badge: badge.map { UiWidgetBadge(text: $0, tone: .positive) }, accentTone: .neutral,
            refreshable: onRefresh != nil, isRefreshing: false,
            cardAction: UiWidgetActionOpenUrl(url: "https://example.org/card"), body: body,
            actions: [], onRefresh: onRefresh
        )
    }

    private func window<Content: View>(hosting host: NSHostingView<Content>) -> NSWindow {
        let window = NSWindow(contentRect: .zero, styleMask: [.borderless], backing: .buffered, defer: false)
        window.contentView = host
        window.setContentSize(host.fittingSize)
        window.setFrameOrigin(CGPoint(x: -2500, y: -2500))
        window.orderBack(nil)
        return window
    }

    func testRefreshStillWinsTheTapWithALongBadge() async throws {
        var refreshed = 0
        var opened: [URL] = []
        let badge = "Pending confirmation until the revised schedule has been announced for this regional event"
        let view = PluginWidgetStackView(cards: [card(badge: badge) { refreshed += 1 }])
            .environment(\.openURL, OpenURLAction { opened.append($0); return .handled })
        let host = NSHostingView(rootView: view.frame(width: 350))
        let window = window(hosting: host)
        defer { window.orderOut(nil) }
        try await Task.sleep(for: .milliseconds(50))
        host.layoutSubtreeIfNeeded()
        let point = host.convert(CGPoint(x: 327, y: host.isFlipped ? 23 : host.bounds.height - 23), to: nil)
        for type in [NSEvent.EventType.leftMouseDown, .leftMouseUp] {
            let event = try XCTUnwrap(NSEvent.mouseEvent(
                with: type, location: point, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime,
                windowNumber: window.windowNumber, context: nil, eventNumber: 1, clickCount: 1,
                pressure: type == .leftMouseDown ? 1 : 0
            ))
            window.sendEvent(event)
        }
        XCTAssertEqual(refreshed, 1)
        XCTAssertTrue(opened.isEmpty)
    }

    func testColumnPositionsItsTextAndKeepsItsChartFullWidth() throws {
        func column(_ text: String, _ align: UiWidgetAlign, _ extra: [UiWidgetNode] = []) -> UiWidgetNode {
            let child = UiWidgetNode.Text(text: text, style: .body, tone: .neutral, maxLines: 1, copyable: false)
            return UiWidgetNode.Column(children: [child] + extra, gap: .xs, align: align, weight: nil)
        }
        let chart = UiWidgetNode.Sparkline(points: [1.0, 2.0], tone: .neutral, minLabel: "Low axis",
                                           maxLabel: "High axis", height: .sm, weight: nil)
        let stack = PluginWidgetStackView(cards: [card(body: [
            column("First", .start), column("Middle", .center, [chart]), column("Last", .end)
        ])]).frame(width: 350)
        let data = NSMutableData()
        let consumer = try XCTUnwrap(CGDataConsumer(data: data))
        var bounds = CGRect(x: 0, y: 0, width: 350, height: 300)
        let context = try XCTUnwrap(CGContext(consumer: consumer, mediaBox: &bounds, nil))
        context.beginPDFPage(nil)
        ImageRenderer(content: stack).render { _, draw in draw(context) }
        context.endPDFPage()
        context.closePDF()
        let document = try XCTUnwrap(PDFDocument(data: data as Data))
        let page = try XCTUnwrap(document.page(at: 0))
        func frame(_ text: String) throws -> CGRect {
            try XCTUnwrap(document.findString(text, withOptions: []).first).bounds(for: page)
        }
        XCTAssertEqual(try frame("First").minX, 12, accuracy: 2)
        XCTAssertEqual(try frame("Middle").midX, 175, accuracy: 2)
        XCTAssertEqual(try frame("Last").maxX, 338, accuracy: 2)
        XCTAssertEqual(try frame("Low axis").minX, 12, accuracy: 2)
        XCTAssertEqual(try frame("High axis").maxX, 338, accuracy: 2)
    }

    func testLocalIconMeasurementReuseAndPathChanges() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        func writeIcon(_ color: Color, name: String) throws -> URL {
            let url = directory.appendingPathComponent(name)
            let image = try XCTUnwrap(ImageRenderer(content: color.frame(width: 512, height: 512)).cgImage)
            try XCTUnwrap(NSBitmapImageRep(cgImage: image).representation(using: .png, properties: [:])).write(to: url)
            return url
        }
        let red = try writeIcon(.red, name: "red icon.png")
        let blue = try writeIcon(.blue, name: "blue icon.png")
        func content(_ path: String, measuring: Bool = false) -> some View {
            PluginWidgetStackView(cards: [card(icon: path)])
                .environment(\.isPluginWidgetHiddenSizingPass, measuring)
                .frame(width: 350)
        }
        let view = NSHostingView(rootView: content(red.path, measuring: true))
        let window = window(hosting: view)
        defer { window.orderOut(nil) }
        let key = LocalFileImageDataProvider(fileURL: red).cacheKey
        let options: KingfisherOptionsInfo = [.processor(DownsamplingImageProcessor(size: CGSize(width: 48, height: 48)))]
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertNil(ImageCache.default.retrieveImageInMemoryCache(forKey: key, options: options))
        func showsIcon(red expectedRed: Bool) async throws {
            for _ in 0..<100 {
                try await Task.sleep(for: .milliseconds(20))
                view.layoutSubtreeIfNeeded()
                let rep = try XCTUnwrap(view.bitmapImageRepForCachingDisplay(in: view.bounds))
                view.cacheDisplay(in: view.bounds, to: rep)
                let x = Int(20 * CGFloat(rep.pixelsWide) / view.bounds.width)
                let found = (0..<rep.pixelsHigh).contains { y in
                    guard let color = rep.colorAt(x: x, y: y)?.usingColorSpace(.sRGB) else { return false }
                    return expectedRed ? color.redComponent > color.blueComponent + 0.45
                        : color.blueComponent > color.redComponent + 0.45
                }
                if found { return }
            }
            XCTFail("The current icon did not appear")
        }
        view.rootView = content(red.path)
        try await showsIcon(red: true)
        let thumbnail = try XCTUnwrap(ImageCache.default.retrieveImageInMemoryCache(forKey: key, options: options))
        XCTAssertEqual(try XCTUnwrap(thumbnail.representations.first).pixelsWide, 48)
        view.rootView = content(blue.absoluteString)
        try await showsIcon(red: false)
        try FileManager.default.removeItem(at: red)
        view.rootView = content(red.absoluteString)
        try await showsIcon(red: true)
    }
}
