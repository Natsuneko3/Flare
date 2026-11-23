import AppKit
import FlareAppleCore
import Foundation
import KotlinSharedUI
import SwiftUI
import XCTest
@testable import FlareAppleUI

@MainActor
final class ProfileShareViewTests: XCTestCase {
    func testProfileShareMenuResolvesNonBlankShareURL() {
        XCTAssertNil(MacProfileShareMenu<Text>.resolveShareURL(from: nil))
        XCTAssertNil(MacProfileShareMenu<Text>.resolveShareURL(from: ""))
        XCTAssertNil(MacProfileShareMenu<Text>.resolveShareURL(from: "   \n\t "))

        let clean = MacProfileShareMenu<Text>.resolveShareURL(from: "https://example.org/@alice")
        XCTAssertEqual(clean?.raw, "https://example.org/@alice")
        XCTAssertEqual(clean?.url, URL(string: "https://example.org/@alice"))

        let trimmed = MacProfileShareMenu<Text>.resolveShareURL(from: "  https://example.org/@alice  ")
        XCTAssertEqual(trimmed?.raw, "https://example.org/@alice")
        XCTAssertEqual(trimmed?.url, URL(string: "https://example.org/@alice"))
    }

    func testProfileShareTypesAndPurposeCompatibility() {
        let userKey = MicroBlogKey(id: "alice", host: "example.org")
        let accountType = AccountType.Guest.shared
        let dataWithURL = MacProfileShareData(
            userKey: userKey,
            accountType: accountType,
            shareUrl: "https://example.org/@alice"
        )
        let dataWithoutURL = MacProfileShareData(userKey: userKey, accountType: accountType)

        XCTAssertEqual(dataWithURL.userKey, userKey)
        XCTAssertEqual(dataWithURL.shareUrl, "https://example.org/@alice")
        XCTAssertNil(dataWithoutURL.shareUrl)

        let screenshotPurpose: MacStatusSharePurpose = .shareScreenshot
        let crossPostPurpose: MacStatusSharePurpose = .crossPost
        XCTAssertEqual(screenshotPurpose, MacSharePurpose.shareScreenshot)
        XCTAssertEqual(crossPostPurpose, MacSharePurpose.crossPost)

        _ = MacProfileShareMenu(
            data: dataWithoutURL,
            onShareScreenshot: {},
            onCrossPost: {}
        ) {
            Text(verbatim: "Share")
        }

        _ = MacProfileShareSheet(
            userKey: userKey,
            accountType: accountType,
            purpose: .crossPost,
            onCrossPost: { _, _, _ in }
        )
    }

    func testProfileShareContentHidesFollowButtonAndPreservesFullBleedCardLayout() async throws {
        try XCTSkipUnless(
            ProcessInfo.processInfo.environment["CFFIXED_USER_HOME"]?.hasSuffix("/MacTimelineTestsHome") == true
                && NSHomeDirectory().hasSuffix("/MacTimelineTestsHome"),
            "Run the MacTimelineTests scheme to isolate application data"
        )
        _ = MacStatusActionAnimationTests.servicesReady
        _ = NSApplication.shared

        let profile = makeSampleProfile(url: "https://example.org/@alice")
        let appearance = TimelineAppearance.companion.Default
        let translateConfig = AppSettings.companion.default.translateConfig

        let contentPreview = MacShareCardContainer(
            previewStyle: .content,
            colorScheme: .light,
            timelineAppearance: appearance,
            translateConfig: translateConfig,
            dynamicTypeSize: .medium,
            appliesContentPadding: false
        ) {
            ProfileShareContent(profile: profile)
        }
        let cardPreview = MacShareCardContainer(
            previewStyle: .card,
            colorScheme: .light,
            timelineAppearance: appearance,
            translateConfig: translateConfig,
            dynamicTypeSize: .medium,
            appliesContentPadding: false
        ) {
            ProfileShareContent(profile: profile)
        }

        let contentHosting = NSHostingView(rootView: contentPreview.fixedSize(horizontal: false, vertical: true))
        let cardHosting = NSHostingView(rootView: cardPreview.fixedSize(horizontal: false, vertical: true))
        contentHosting.safeAreaRegions = []
        cardHosting.safeAreaRegions = []
        contentHosting.layoutSubtreeIfNeeded()
        cardHosting.layoutSubtreeIfNeeded()

        XCTAssertEqual(contentHosting.fittingSize.width, 360, accuracy: 0.5)
        XCTAssertEqual(cardHosting.fittingSize.width, 360 + 128, accuracy: 0.5)
        XCTAssertEqual(cardHosting.fittingSize.height, contentHosting.fittingSize.height + 128, accuracy: 0.5)

        let window = NSWindow(
            contentRect: CGRect(x: 40, y: 40, width: 600, height: 800),
            styleMask: [.titled],
            backing: .buffered,
            defer: false
        )
        window.isReleasedWhenClosed = false
        window.contentView = NSView(frame: NSRect(x: 0, y: 0, width: 600, height: 800))
        window.orderFrontRegardless()
        defer { window.close() }

        let screenshot = try await MacShareScreenshotRenderer.render(
            view: cardPreview,
            fileName: "flare-profile-alice/example.org.png",
            directoryPrefix: "flare-profile-share-test",
            fallbackFileName: "flare-profile.png"
        )
        defer { try? FileManager.default.removeItem(at: screenshot.fileURL.deletingLastPathComponent()) }
        XCTAssertFalse(screenshot.data.isEmpty)
        XCTAssertEqual(screenshot.fileURL.lastPathComponent, "flare-profile-alice_example.org.png")

        let headerWithFollow = CommonProfileHeader(
            user: profile,
            relation: UiStateLoading(),
            followButtonState: UiStateLoading(),
            isMe: UiStateSuccess(data: KotlinBoolean(bool: false)),
            bannerTopInset: 0,
            showsFollowButton: true,
            onFollowClick: { _ in },
            onFollowingClick: {},
            onFansClick: {}
        )
        .padding(.bottom)
        .environment(\.profileUsesPlainStyle, true)
        .frame(width: 360, alignment: .leading)

        let headerWithoutFollow = CommonProfileHeader(
            user: profile,
            relation: UiStateLoading(),
            followButtonState: UiStateLoading(),
            isMe: UiStateSuccess(data: KotlinBoolean(bool: false)),
            bannerTopInset: 0,
            showsFollowButton: false,
            onFollowClick: { _ in },
            onFollowingClick: {},
            onFansClick: {}
        )
        .padding(.bottom)
        .environment(\.profileUsesPlainStyle, true)
        .frame(width: 360, alignment: .leading)

        let shareContentScreenshot = try await MacShareScreenshotRenderer.render(
            view: ProfileShareContent(profile: profile),
            fileName: "share-content.png",
            directoryPrefix: "flare-profile-share-test"
        )
        defer { try? FileManager.default.removeItem(at: shareContentScreenshot.fileURL.deletingLastPathComponent()) }

        let withoutFollowScreenshot = try await MacShareScreenshotRenderer.render(
            view: headerWithoutFollow,
            fileName: "without-follow.png",
            directoryPrefix: "flare-profile-share-test"
        )
        defer { try? FileManager.default.removeItem(at: withoutFollowScreenshot.fileURL.deletingLastPathComponent()) }

        let withFollowScreenshot = try await MacShareScreenshotRenderer.render(
            view: headerWithFollow,
            fileName: "with-follow.png",
            directoryPrefix: "flare-profile-share-test"
        )
        defer { try? FileManager.default.removeItem(at: withFollowScreenshot.fileURL.deletingLastPathComponent()) }

        XCTAssertEqual(shareContentScreenshot.data, withoutFollowScreenshot.data)
        XCTAssertNotEqual(shareContentScreenshot.data, withFollowScreenshot.data)
    }

    private func makeSampleProfile(url: String?) -> UiProfile {
        func rich(_ text: String) -> UiRichText {
            let style = RenderTextStyle(
                link: nil, bold: false, italic: false, strikethrough: false,
                monospace: false, code: false, underline: false, small: false,
                time: false, imageLink: nil
            )
            return UiRichText(
                renderRuns: [RenderContent.Text(runs: [RenderRun.Text(text: text, style: style)], block: RenderBlockStyle())],
                isRtl: false,
                raw: text,
                innerText: text,
                imageUrls: []
            )
        }

        return UiProfile(
            key: MicroBlogKey(id: "alice", host: "example.org"),
            handle: UiHandle(raw: "alice", host: "example.org", displayOverride: "@alice"),
            avatar: nil as UiMediaImage?,
            nameInternal: rich("Alice Rivera"),
            platformId: "mastodon",
            platformIcon: IconType.Material(icon: .world),
            clickEvent: ClickEventNoop.shared,
            banner: nil,
            description: rich("Building cross-platform apps."),
            sourceLanguages: [],
            translationDisplayState: .hidden,
            matrices: UiProfile.Matrices(fansCount: 420, followsCount: 120, statusesCount: 88, platformFansCount: nil),
            mark: [.verified],
            bottomContent: nil,
            pluginId: nil,
            clearedMarks: [],
            url: url
        )
    }
}
