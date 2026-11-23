import SwiftUI
import UIKit
import FlareAppleCore
import FlareAppleUI

struct ProfileShareSheetObserver {
    var onRendered: ((_ revision: String, _ image: UIImage, _ crossPost: @escaping @MainActor () -> Void) -> Void)?
}

extension EnvironmentValues {
    @Entry var profileShareSheetObserver: ProfileShareSheetObserver? = nil
}

enum SharePreviewStyle: Hashable {
    case card
    case content
}

struct ShareSheetCardContainer<Content: View>: View {
    let previewStyle: SharePreviewStyle
    let theme: ColorScheme
    let appliesContentPadding: Bool
    @ViewBuilder let content: () -> Content

    init(
        previewStyle: SharePreviewStyle,
        theme: ColorScheme,
        appliesContentPadding: Bool = true,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.previewStyle = previewStyle
        self.theme = theme
        self.appliesContentPadding = appliesContentPadding
        self.content = content
    }

    var body: some View {
        content()
            .frame(width: 360, alignment: .leading)
            .if(appliesContentPadding) { view in
                view.padding()
            }
            .flareTextBackground(.flareSecondarySystemGroupedBackground)
            .background(Color(.secondarySystemGroupedBackground))
            .if(previewStyle == .card) { view in
                view
                    .clipShape(.rect(cornerRadius: 16))
                    .contentShape(RoundedRectangle(cornerRadius: 16))
                    .shadow(radius: 8)
                    .padding(64)
            }
            .background(Color(.systemGroupedBackground))
            .environment(\.colorScheme, theme)
    }
}

extension View {
    @MainActor
    func snapshot(colorScheme: ColorScheme, dynamicTypeSize: DynamicTypeSize = .large) async throws -> UIImage {
        let loading = ShareImageLoading()
        let controller = UIHostingController(rootView: self.environment(\.shareImageLoading, loading).dynamicTypeSize(dynamicTypeSize).fixedSize())
        // Screenshot padding must not inherit the window's safe area.
        controller.safeAreaRegions = []
        controller.overrideUserInterfaceStyle = colorScheme == .dark ? .dark : .light
        let view = controller.view!
        var targetSize = view.intrinsicContentSize
        try ShareImageLoading.checkSize(targetSize, scale: 3)
        view.frame = CGRect(origin: CGPoint(x: -targetSize.width * 2, y: 0), size: targetSize)
        view.backgroundColor = .systemGroupedBackground
        guard let window = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene }).flatMap(\.windows).first(where: \.isKeyWindow) else {
            throw StatusShareRenderError.mediaUnavailable
        }
        window.addSubview(view)
        defer { view.removeFromSuperview() }
        view.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(100))
        try await loading.awaitReady()
        try await Task.sleep(for: .milliseconds(50))
        targetSize = view.intrinsicContentSize
        try ShareImageLoading.checkSize(targetSize, scale: 3)
        view.frame = CGRect(origin: CGPoint(x: -targetSize.width * 2, y: 0), size: targetSize)
        view.layoutIfNeeded()
        let format = UIGraphicsImageRendererFormat()
        format.scale = 3
        format.opaque = true
        return UIGraphicsImageRenderer(size: targetSize, format: format).image { _ in
            view.drawHierarchy(in: view.bounds, afterScreenUpdates: true)
        }
    }
}
