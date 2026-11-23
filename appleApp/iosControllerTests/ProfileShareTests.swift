import XCTest
import SwiftUI
import UIKit
import KotlinSharedUI
@testable import Flare
@testable import FlareAppleUI

@MainActor
final class ProfileShareTests: XCTestCase {
    func testProfileShareScreenshotKeepsEqualPaddingOnEveryEdge() async throws {
        let (bannerMedia, bannerURL) = try await cachedSolidImage(
            name: "profile-banner",
            color: UIColor(red: 1, green: 0, blue: 0, alpha: 1),
            warmSize: CGSize(width: 360, height: 200)
        )
        defer { try? FileManager.default.removeItem(at: bannerURL) }

        let profile = makeProfile(
            id: "centered-profile",
            descriptionText: "Centered profile bio",
            banner: bannerMedia
        )

        for theme in [ColorScheme.light, .dark] {
            for padding in [CGFloat.zero, 64] {
                let image = try await ProfileShareContent(profile: profile)
                    .background(Color(red: 1, green: 0, blue: 0))
                    .clipShape(.rect(cornerRadius: padding == 0 ? 0 : 16))
                    .shadow(radius: padding == 0 ? 0 : 8)
                    .padding(padding)
                    .background(Color(red: 0, green: 0, blue: 1))
                    .environment(\.colorScheme, theme)
                    .snapshot(colorScheme: theme)
                let cgImage = try XCTUnwrap(image.cgImage)
                let width = cgImage.width
                let height = cgImage.height
                var pixels = [UInt8](repeating: 0, count: width * height * 4)
                let context = try XCTUnwrap(
                    CGContext(
                        data: &pixels,
                        width: width,
                        height: height,
                        bitsPerComponent: 8,
                        bytesPerRow: width * 4,
                        space: CGColorSpaceCreateDeviceRGB(),
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
                    )
                )
                context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
                func isCard(_ x: Int, _ y: Int) -> Bool {
                    let offset = (y * width + x) * 4
                    return pixels[offset] > 240 && pixels[offset + 1] < 15 && pixels[offset + 2] < 15
                }
                let columns = (0..<width).filter { isCard($0, height / 2) }
                let rows = (0..<height).filter { isCard(width / 2, $0) }
                let expected = padding * image.scale
                XCTAssertEqual(CGFloat(try XCTUnwrap(columns.first)), expected, accuracy: 1, "Left padding")
                XCTAssertEqual(CGFloat(width - (try XCTUnwrap(columns.last)) - 1), expected, accuracy: 1, "Right padding")
                XCTAssertEqual(CGFloat(try XCTUnwrap(rows.first)), expected, accuracy: 1, "Top padding")
                XCTAssertEqual(CGFloat(height - (try XCTUnwrap(rows.last)) - 1), expected, accuracy: 1, "Bottom padding")
                let attachment = XCTAttachment(image: image)
                attachment.name = "profile-share-padding-\(Int(padding))-\(theme)"
                attachment.lifetime = .keepAlways
                add(attachment)
            }
        }
    }

    func testProfileShareScreenshotScalesWithDynamicTypeSize() async throws {
        let profile = makeProfile(
            id: "font-size",
            descriptionText: Array(repeating: "A line of shared profile bio", count: 15).joined(separator: "\n")
        )
        let content = ProfileShareContent(profile: profile)
        let small = try await content.snapshot(colorScheme: .light, dynamicTypeSize: .small)
        let large = try await content.snapshot(colorScheme: .light, dynamicTypeSize: .xxxLarge)
        XCTAssertEqual(small.size.width, large.size.width)
        XCTAssertGreaterThan(
            large.size.height,
            small.size.height * 1.2,
            "Exported profile screenshot must use the current Dynamic Type size"
        )
    }

    func testCachedProfilePreviewRemainsVisibleWhenMediaFails() async throws {
        let (avatarMedia, avatarURL) = try await cachedSolidImage(
            name: "profile-avatar",
            color: UIColor.green,
            warmSize: CGSize(width: 96, height: 96)
        )
        defer { try? FileManager.default.removeItem(at: avatarURL) }

        let missingBanner = UiMediaImage(
            url: "file:///nonexistent-banner.png",
            proxy: ProxyRef.Global.shared
        )
        let profile = makeProfile(
            id: "cached-avatar",
            descriptionText: "Cached avatar bio",
            avatar: avatarMedia,
            banner: missingBanner
        )

        try await withProfileShareSheet(profile: profile, shareUrl: profile.url) { host in
            for _ in 0..<100 {
                host.view.layoutIfNeeded()
                if greenPixels(in: host.view, attachOnFailure: false) > 1_000 { break }
                try await Task.sleep(for: .milliseconds(20))
            }
            // Wait for background snapshot failure from missing banner and ensure preview stays visible.
            try await Task.sleep(for: .milliseconds(300))
            host.view.layoutIfNeeded()
            XCTAssertGreaterThan(
                greenPixels(in: host.view),
                1_000,
                "Cached profile avatar must remain visible even when banner media preparation fails"
            )
        }
    }

    func testProfileShareSheetWithNilUrlAllowsImageOnlyCrossPost() async throws {
        let profile = makeProfile(
            id: "nil-url",
            descriptionText: "Profile without canonical URL",
            url: nil
        )
        var capturedPrefill: ComposePrefill?
        var renderedRevision: String?
        var triggerCrossPost: (@MainActor () -> Void)?

        let observer = ProfileShareSheetObserver { revision, _, crossPost in
            renderedRevision = revision
            triggerCrossPost = crossPost
        }

        try await withProfileShareSheet(
            profile: profile,
            shareUrl: nil,
            observer: observer,
            onCrossPost: { capturedPrefill = $0 }
        ) { host in
            try await waitUntil {
                host.view.layoutIfNeeded()
                guard renderedRevision != nil,
                      triggerCrossPost != nil,
                      let collectionView = self.descendants(of: host.view).compactMap({ $0 as? UICollectionView }).first
                else {
                    return false
                }
                let counts = (0..<collectionView.numberOfSections).map { collectionView.numberOfItems(inSection: $0) }
                return counts == [1, 3, 2]
            }
            triggerCrossPost?()
        }

        let prefill = try XCTUnwrap(capturedPrefill)
        XCTAssertEqual(prefill.text, "")
        XCTAssertEqual(prefill.cursorPosition, 0)
        XCTAssertEqual(prefill.fileName, "flare-profile-crosspost.png")
        XCTAssertFalse(prefill.imageData.isEmpty)
        XCTAssertEqual(
            Array(prefill.imageData.prefix(8)),
            [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A],
            "Cross-post imageData must be valid PNG bytes"
        )
        XCTAssertNotNil(UIImage(data: prefill.imageData))
    }

    func testProfileRefreshUpdatesPreviewRevision() async throws {
        let initialProfile = makeProfile(
            id: "refresh",
            descriptionText: "Initial bio",
            translationDisplayState: .hidden,
            url: "https://example.org/@alice"
        )
        let refreshedProfile = initialProfile.doCopy(
            key: initialProfile.key,
            handle: initialProfile.handle,
            avatar: initialProfile.avatar,
            nameInternal: initialProfile.name,
            platformId: initialProfile.platformId,
            platformIcon: initialProfile.platformIcon,
            clickEvent: initialProfile.clickEvent,
            banner: initialProfile.banner,
            description: makeRichText("Updated translated bio\nWith an extra line\nAnd another line"),
            sourceLanguages: initialProfile.sourceLanguages,
            translationDisplayState: .translated,
            matrices: initialProfile.matrices,
            mark: initialProfile.mark,
            bottomContent: initialProfile.bottomContent,
            pluginId: initialProfile.pluginId,
            clearedMarks: [],
            url: initialProfile.url
        )

        XCTAssertNotEqual(
            initialProfile.hash,
            refreshedProfile.hash,
            "Updated description and translation state must change UiProfile.hash"
        )

        var capturedPrefills: [ComposePrefill] = []
        var renderedRevisions: [String] = []
        var triggerCrossPost: (@MainActor () -> Void)?
        let onCrossPost: (ComposePrefill) -> Void = { capturedPrefills.append($0) }
        let observer = ProfileShareSheetObserver { revision, _, crossPost in
            renderedRevisions.append(revision)
            triggerCrossPost = crossPost
        }

        try await withProfileShareSheet(
            profile: initialProfile,
            shareUrl: initialProfile.url,
            observer: observer,
            onCrossPost: onCrossPost
        ) { host in
            try await waitUntil {
                host.view.layoutIfNeeded()
                guard renderedRevisions.count >= 1,
                      triggerCrossPost != nil,
                      let collectionView = self.descendants(of: host.view).compactMap({ $0 as? UICollectionView }).first
                else {
                    return false
                }
                let counts = (0..<collectionView.numberOfSections).map { collectionView.numberOfItems(inSection: $0) }
                return counts == [1, 4, 2]
            }
            triggerCrossPost?()
            let firstRevision = try XCTUnwrap(renderedRevisions.first)
            let firstImageData = try XCTUnwrap(capturedPrefills.first?.imageData)

            host.rootView = makeShareSheetView(
                profile: refreshedProfile,
                shareUrl: refreshedProfile.url,
                observer: observer,
                onCrossPost: onCrossPost
            )
            host.view.layoutIfNeeded()

            try await waitUntil {
                renderedRevisions.count >= 2 && renderedRevisions.last != firstRevision
            }
            triggerCrossPost?()

            let secondRevision = try XCTUnwrap(renderedRevisions.last)
            let secondPrefill = try XCTUnwrap(capturedPrefills.last)
            XCTAssertNotEqual(firstRevision, secondRevision, "Refreshing UiProfile must advance previewRevision")
            XCTAssertNotEqual(
                firstImageData,
                secondPrefill.imageData,
                "Refreshing UiProfile must produce a fresh preview snapshot"
            )
            XCTAssertEqual(secondPrefill.text, "\n\nhttps://example.org/@alice")
        }
    }

    // MARK: - Helpers

    private final class StubProfileShareState: NSObject, ProfileSharePresenterState {
        let profile: UiState<UiProfile>
        let shareUrl: String?

        init(profile: UiState<UiProfile>, shareUrl: String?) {
            self.profile = profile
            self.shareUrl = shareUrl
        }
    }

    private func makeRichText(_ text: String) -> UiRichText {
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
        return UiRichText(
            renderRuns: [
                RenderContent.Text(
                    runs: [RenderRun.Text(text: text, style: style)],
                    block: RenderBlockStyle()
                )
            ],
            isRtl: false,
            raw: text,
            innerText: text,
            imageUrls: []
        )
    }

    private func makeProfile(
        id: String,
        descriptionText: String,
        avatar: UiMediaImage? = nil,
        banner: UiMediaImage? = nil,
        translationDisplayState: TranslationDisplayState = .hidden,
        url: String? = "https://example.org/@alice"
    ) -> UiProfile {
        UiProfile(
            key: MicroBlogKey(id: id, host: "example.org"),
            handle: UiHandle(raw: "alice", host: "example.org", displayOverride: "@alice"),
            avatar: avatar,
            nameInternal: makeRichText("Alice Rivera"),
            platformId: "mastodon",
            platformIcon: IconType.Material(icon: .world),
            clickEvent: ClickEventNoop.shared,
            banner: banner,
            description: makeRichText(descriptionText),
            sourceLanguages: [],
            translationDisplayState: translationDisplayState,
            matrices: UiProfile.Matrices(
                fansCount: 420,
                followsCount: 120,
                statusesCount: 88,
                platformFansCount: nil
            ),
            mark: [.verified],
            bottomContent: nil,
            pluginId: nil,
            clearedMarks: [],
            url: url
        )
    }

    private func cachedSolidImage(
        name: String,
        color: UIColor,
        warmSize: CGSize
    ) async throws -> (UiMediaImage, URL) {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(name)-\(UUID().uuidString).png")
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: 64, height: 64), format: format).image { context in
            color.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 64, height: 64))
        }
        try XCTUnwrap(image.pngData()).write(to: url)
        let media = UiMediaImage(
            url: url.absoluteString,
            previewUrl: url.absoluteString,
            description: nil,
            height: 64,
            width: 64,
            sensitive: false,
            customHeaders: nil,
            proxy: ProxyRef.Global.shared
        )
        _ = try await NetworkImage(data: media).frame(width: warmSize.width, height: warmSize.height).snapshot(colorScheme: .light)
        return (media, url)
    }

    private func makeShareSheetView(
        profile: UiProfile,
        shareUrl: String?,
        observer: ProfileShareSheetObserver? = nil,
        onCrossPost: @escaping (ComposePrefill) -> Void = { _ in }
    ) -> AnyView {
        let config = AppSettings.companion.default.translateConfig
        return AnyView(
            NavigationStack {
                ProfileShareSheetContent(
                    userKey: profile.key,
                    onCrossPost: onCrossPost,
                    state: StubProfileShareState(
                        profile: UiStateSuccess(data: profile),
                        shareUrl: shareUrl
                    )
                )
            }
            .environment(\.colorScheme, .light)
            .environment(\.translateConfig, config)
            .environment(\.profileShareSheetObserver, observer)
        )
    }

    private func withProfileShareSheet(
        profile: UiProfile,
        shareUrl: String?,
        observer: ProfileShareSheetObserver? = nil,
        onCrossPost: @escaping (ComposePrefill) -> Void = { _ in },
        operation: (UIHostingController<AnyView>) async throws -> Void
    ) async throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let previousWindow = scene.windows.first(where: \.isKeyWindow)
        let window = UIWindow(windowScene: scene)
        let host = UIHostingController(
            rootView: makeShareSheetView(
                profile: profile,
                shareUrl: shareUrl,
                observer: observer,
                onCrossPost: onCrossPost
            )
        )
        window.rootViewController = host
        window.makeKeyAndVisible()
        host.view.layoutIfNeeded()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousWindow?.makeKey()
        }
        try await operation(host)
    }

    private func descendants(of view: UIView) -> [UIView] {
        [view] + view.subviews.flatMap { descendants(of: $0) }
    }

    private func waitUntil(
        iterations: Int = 200,
        interval: Duration = .milliseconds(30),
        condition: () -> Bool
    ) async throws {
        for _ in 0..<iterations {
            if condition() { return }
            try await Task.sleep(for: interval)
        }
        XCTFail("Timed out waiting for condition")
    }

    private func greenPixels(in view: UIView, attachOnFailure: Bool = true) -> Int {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.preferredRange = .standard
        let image = UIGraphicsImageRenderer(bounds: view.bounds, format: format).image { _ in
            view.drawHierarchy(in: view.bounds, afterScreenUpdates: true)
        }
        guard let cgImage = image.cgImage else { return 0 }
        var pixels = [UInt8](repeating: 0, count: cgImage.width * cgImage.height * 4)
        let context = CGContext(
            data: &pixels,
            width: cgImage.width,
            height: cgImage.height,
            bitsPerComponent: 8,
            bytesPerRow: cgImage.width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )!
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: cgImage.width, height: cgImage.height))
        let count = stride(from: 0, to: pixels.count, by: 4).filter {
            Int(pixels[$0 + 1]) > Int(pixels[$0]) + 20 && Int(pixels[$0 + 1]) > Int(pixels[$0 + 2]) + 20
        }.count
        if attachOnFailure && count < 1_000 {
            let attachment = XCTAttachment(image: image)
            attachment.name = "profile-share-preview"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        return count
    }
}
