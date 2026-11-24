#if os(macOS)
import AppKit
import FlareAppleCore
import Foundation
import KotlinSharedUI
import SwiftUI

public struct MacShareScreenshot {
    public let data: Data
    public let fileURL: URL

    public init(data: Data, fileURL: URL) {
        self.data = data
        self.fileURL = fileURL
    }
}

public enum MacSharePurpose {
    case shareScreenshot
    case crossPost
}

public enum MacSharePreviewStyle: Hashable {
    case card
    case content
}

public struct MacShareCardContainer<Content: View>: View {
    private let previewStyle: MacSharePreviewStyle
    private let colorScheme: ColorScheme
    private let timelineAppearance: TimelineAppearance
    private let translateConfig: AppSettings.TranslateConfig
    private let dynamicTypeSize: DynamicTypeSize
    private let appliesContentPadding: Bool
    private let content: () -> Content

    public init(
        previewStyle: MacSharePreviewStyle,
        colorScheme: ColorScheme,
        timelineAppearance: TimelineAppearance,
        translateConfig: AppSettings.TranslateConfig,
        dynamicTypeSize: DynamicTypeSize,
        appliesContentPadding: Bool = true,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.previewStyle = previewStyle
        self.colorScheme = colorScheme
        self.timelineAppearance = timelineAppearance
        self.translateConfig = translateConfig
        self.dynamicTypeSize = dynamicTypeSize
        self.appliesContentPadding = appliesContentPadding
        self.content = content
    }

    public var body: some View {
        content()
            .frame(width: 360, alignment: .leading)
            .if(appliesContentPadding) { view in
                view.padding()
            }
            .flareTextBackground(.flareSecondarySystemGroupedBackground)
            .background(Color.flareSecondarySystemGroupedBackground)
            .if(previewStyle == .card) { view in
                view
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .contentShape(RoundedRectangle(cornerRadius: 16))
                    .shadow(radius: 8)
                    .padding(64)
            }
            .background(Color.flareSystemGroupedBackground)
            .environment(\.colorScheme, colorScheme)
            .environment(\.timelineAppearance, timelineAppearance)
            .environment(\.translateConfig, translateConfig)
            .dynamicTypeSize(dynamicTypeSize)
    }
}

public enum MacShareScreenshotRenderer {
    @MainActor
    public static func render<Content: View>(
        view: Content,
        fileName: String,
        directoryPrefix: String = "flare-share",
        fallbackFileName: String = "flare-share.png"
    ) async throws -> MacShareScreenshot {
        let scale: CGFloat = 2
        let loading = ShareImageLoading()
        let hostingView = NSHostingView(rootView: view.environment(\.shareImageLoading, loading).fixedSize(horizontal: false, vertical: true))
        // Screenshot padding must not inherit the window's safe area.
        hostingView.safeAreaRegions = []
        var fittingSize = hostingView.fittingSize
        try ShareImageLoading.checkSize(fittingSize, scale: scale)
        guard fittingSize.width > 0, fittingSize.height > 0 else {
            throw MacShareError.invalidSnapshotSize
        }

        hostingView.frame = NSRect(origin: CGPoint(x: -fittingSize.width * 2, y: 0), size: fittingSize)
        guard let container = NSApp.keyWindow?.contentView
            ?? NSApp.mainWindow?.contentView
            ?? NSApp.windows.first(where: { $0.isVisible && $0.contentView != nil })?.contentView
        else { throw StatusShareRenderError.mediaUnavailable }
        container.addSubview(hostingView)
        defer { hostingView.removeFromSuperview() }
        hostingView.wantsLayer = true
        hostingView.layer?.contentsScale = scale
        hostingView.layoutSubtreeIfNeeded()
        try await Task.sleep(for: .milliseconds(100))
        try await loading.awaitReady()
        try await Task.sleep(for: .milliseconds(50))
        fittingSize = hostingView.fittingSize
        try ShareImageLoading.checkSize(fittingSize, scale: scale)
        hostingView.frame = NSRect(origin: CGPoint(x: -fittingSize.width * 2, y: 0), size: fittingSize)
        hostingView.layoutSubtreeIfNeeded()

        let pixelWidth = max(1, Int((fittingSize.width * scale).rounded(.up)))
        let pixelHeight = max(1, Int((fittingSize.height * scale).rounded(.up)))
        guard let representation = NSBitmapImageRep(
            bitmapDataPlanes: nil,
            pixelsWide: pixelWidth,
            pixelsHigh: pixelHeight,
            bitsPerSample: 8,
            samplesPerPixel: 4,
            hasAlpha: true,
            isPlanar: false,
            colorSpaceName: .deviceRGB,
            bytesPerRow: 0,
            bitsPerPixel: 0
        ) else {
            throw MacShareError.snapshotFailed
        }
        representation.size = fittingSize
        hostingView.cacheDisplay(in: hostingView.bounds, to: representation)

        guard let pngData = representation.representation(using: .png, properties: [:]) else {
            throw MacShareError.pngEncodingFailed
        }

        let directoryURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(directoryPrefix)-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)

        let fileURL = directoryURL.appendingPathComponent(
            MediaFileNamePolicy.shared.safeLocalFileName(value: fileName, fallback: fallbackFileName)
        )
        try pngData.write(to: fileURL, options: .atomic)
        return MacShareScreenshot(data: pngData, fileURL: fileURL)
    }
}

public enum MacShareError: LocalizedError {
    case invalidSnapshotSize
    case snapshotFailed
    case pngEncodingFailed

    public var errorDescription: String? {
        switch self {
        case .invalidSnapshotSize:
            String(localized: "share_screenshot_invalid_size")
        case .snapshotFailed:
            String(localized: "share_screenshot_failed")
        case .pngEncodingFailed:
            String(localized: "share_screenshot_encoding_failed")
        }
    }
}

public struct MacShareAlert: Identifiable {
    public let id = UUID()
    public let message: String

    public init(message: String) {
        self.message = message
    }
}

struct MacShareSheetScaffold<
    Model: AnyObject,
    PreviewContent: View,
    ExtraOptions: View,
    LoadingContent: View
>: View {
    let state: UiState<Model>
    let purpose: MacSharePurpose
    let previewRevisionKey: String
    let fileName: String
    let directoryPrefix: String
    let fallbackFileName: String
    let appliesContentPadding: Bool
    var includesReplies: Bool = true
    let onCrossPost: ((Data, String) -> Void)?
    @ViewBuilder let previewContent: (Model) -> PreviewContent
    @ViewBuilder let extraOptions: () -> ExtraOptions
    @ViewBuilder let loadingContent: () -> LoadingContent

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    @Environment(\.timelineAppearance) private var timelineAppearance
    @Environment(\.translateConfig) private var translateConfig
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var theme: ColorScheme?
    @State private var previewStyle: MacSharePreviewStyle = .card
    @State private var screenshotURL: URL?
    @State private var isRenderingScreenshot = false
    @State private var renderRequestID: UUID?
    @State private var alert: MacShareAlert?
    @State private var crossPostDispatched = false
    @State private var renderedRevision: String?
    @State private var renderAttempt = 0

    private var previewRevision: String {
        "\(previewRevisionKey)-\(resolvedColorScheme)-\(previewStyle)-\(renderAttempt)-\(timelineAppearance.hash)-\(translateConfig.showOriginalWithTranslation)-\(dynamicTypeSize)"
    }

    private var currentScreenshotURL: URL? {
        renderedRevision == previewRevision ? screenshotURL : nil
    }

    private var resolvedColorScheme: ColorScheme {
        theme ?? colorScheme
    }

    private var navigationTitle: LocalizedStringKey {
        switch purpose {
        case .shareScreenshot:
            "share_screenshot"
        case .crossPost:
            "share_crosspost"
        }
    }

    var body: some View {
        StateView(state: state) { model in
            VStack(spacing: 0) {
                ScrollView {
                    previewView(model: model)
                        .frame(maxWidth: .infinity)
                        .allowsHitTesting(false)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                Divider()
                footer.padding(20)
            }
            .frame(width: 560, height: 720)
            .task(id: previewRevision) {
                await renderScreenshot(model: model)
            }
        } errorContent: { error in
            ContentUnavailableView {
                Label("share_screenshot_failed", systemImage: "exclamationmark.triangle")
            } description: {
                Text(error.message ?? String(localized: "share_screenshot_failed"))
            } actions: {
                Button("Cancel") {
                    dismiss()
                }
            }
            .frame(width: 560, height: 720)
        } loadingContent: {
            loadingContent()
                .frame(width: 560, height: 720)
        }
        .navigationTitle(navigationTitle)
        .alert(item: $alert) { alert in
            Alert(
                title: Text("share_screenshot_failed"),
                message: Text(verbatim: alert.message),
                dismissButton: .default(Text("Ok"))
            )
        }
    }

    @ViewBuilder
    private var footer: some View {
        VStack(alignment: .leading, spacing: 16) {
            GroupBox {
                VStack(spacing: 12) {
                    LabeledContent("appearance_layout_group_title") {
                        Picker("appearance_layout_group_title", selection: $previewStyle) {
                            Text("appearance_timeline_display_mode_card").tag(MacSharePreviewStyle.card)
                            Text("status_share_sheet_preview_content").tag(MacSharePreviewStyle.content)
                        }
                        .labelsHidden()
                        .pickerStyle(.segmented)
                    }
                    LabeledContent("appearance_theme") {
                        Picker("appearance_theme", selection: $theme) {
                            Text("appearance_theme_system").tag(nil as ColorScheme?)
                            Text("appearance_theme_light").tag(Optional(ColorScheme.light))
                            Text("appearance_theme_dark").tag(Optional(ColorScheme.dark))
                        }
                        .labelsHidden()
                        .pickerStyle(.segmented)
                    }
                    extraOptions()
                }
                .padding(8)
            }
            HStack {
                Button("Cancel") { dismiss() }
                Spacer()
                if let url = currentScreenshotURL {
                    switch purpose {
                    case .shareScreenshot:
                        ShareLink(item: url) { Text("done") }.buttonStyle(.borderedProminent)
                    case .crossPost:
                        Button("share_crosspost") {
                            do {
                                let data = try Data(contentsOf: url)
                                crossPostDispatched = true
                                onCrossPost?(data, url.lastPathComponent)
                            } catch {
                                alert = MacShareAlert(message: error.localizedDescription)
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(crossPostDispatched)
                    }
                } else if isRenderingScreenshot {
                    ProgressView().controlSize(.small)
                } else {
                    Button("share_replies_retry") { renderAttempt += 1 }
                }
            }
        }
    }

    private func previewView(model: Model) -> some View {
        MacShareCardContainer(
            previewStyle: previewStyle,
            colorScheme: resolvedColorScheme,
            timelineAppearance: timelineAppearance,
            translateConfig: translateConfig,
            dynamicTypeSize: dynamicTypeSize,
            appliesContentPadding: appliesContentPadding
        ) {
            previewContent(model)
        }
    }

    private func renderScreenshot(model: Model) async {
        guard !crossPostDispatched else { return }
        let requestID = UUID()
        let revision = previewRevision
        renderRequestID = requestID
        screenshotURL = nil
        isRenderingScreenshot = true
        do {
            let screenshot = try await MacShareScreenshotRenderer.render(
                view: previewView(model: model),
                fileName: fileName,
                directoryPrefix: directoryPrefix,
                fallbackFileName: fallbackFileName
            )
            guard !Task.isCancelled, renderRequestID == requestID, revision == previewRevision else {
                try? FileManager.default.removeItem(at: screenshot.fileURL.deletingLastPathComponent())
                return
            }
            screenshotURL = screenshot.fileURL
            renderedRevision = revision
        } catch is CancellationError {
        } catch {
            guard renderRequestID == requestID else { return }
            let message = (error as? StatusShareRenderError)?.localizedMessage(includesReplies: includesReplies)
                ?? error.localizedDescription
            alert = MacShareAlert(message: message)
        }
        guard renderRequestID == requestID else { return }
        isRenderingScreenshot = false
    }
}
#endif
