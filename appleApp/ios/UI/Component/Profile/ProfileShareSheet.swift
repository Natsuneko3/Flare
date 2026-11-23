import SwiftUI
import UIKit
import KotlinSharedUI
import FlareAppleCore
import FlareAppleUI

struct ProfileShareSheet: View {
    let userKey: MicroBlogKey
    let accountType: AccountType
    let onCrossPost: (ComposePrefill) -> Void

    @State private var presenter: KotlinPresenter<ProfileSharePresenterState>

    init(
        userKey: MicroBlogKey,
        accountType: AccountType,
        onCrossPost: @escaping (ComposePrefill) -> Void
    ) {
        self.userKey = userKey
        self.accountType = accountType
        self.onCrossPost = onCrossPost
        self._presenter = .init(
            wrappedValue: .init(
                presenter: ProfileSharePresenter(accountType: accountType, userKey: userKey)
            )
        )
    }

    var body: some View {
        ProfileShareSheetContent(
            userKey: userKey,
            onCrossPost: onCrossPost,
            state: presenter.state
        )
    }
}

struct ProfileShareSheetContent: View {
    let userKey: MicroBlogKey
    let onCrossPost: (ComposePrefill) -> Void
    let state: ProfileSharePresenterState

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    @Environment(\.timelineAppearance) private var timelineAppearance
    @Environment(\.translateConfig) private var translateConfig
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.profileShareSheetObserver) private var observer
    @State private var theme: ColorScheme? = nil
    @State private var previewStyle: SharePreviewStyle = .card
    @State private var image: UIImage? = nil
    @State private var renderedRevision: String?
    @State private var renderError: String?
    @State private var renderAttempt = 0

    init(
        userKey: MicroBlogKey,
        onCrossPost: @escaping (ComposePrefill) -> Void,
        state: ProfileSharePresenterState
    ) {
        self.userKey = userKey
        self.onCrossPost = onCrossPost
        self.state = state
    }

    private var previewRevision: String {
        let profileHash: Int = if case .success(let data) = onEnum(of: state.profile) {
            data.data.hash
        } else {
            0
        }
        return "\(profileHash)-\(String(describing: theme ?? colorScheme))-\(previewStyle)-\(renderAttempt)-\(timelineAppearance.hash)-\(translateConfig.showOriginalWithTranslation)-\(dynamicTypeSize)"
    }

    private var currentImage: UIImage? {
        renderedRevision == previewRevision ? image : nil
    }

    private var trimmedShareUrl: String? {
        guard let trimmed = state.shareUrl?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty
        else {
            return nil
        }
        return trimmed
    }

    private var shareLinkURL: URL? {
        guard let trimmedShareUrl,
              let url = URL(string: trimmedShareUrl),
              let scheme = url.scheme,
              !scheme.isEmpty
        else {
            return nil
        }
        return url
    }

    var body: some View {
        List {
            StateView(state: state.profile) { data in
                ViewBox {
                    previewView(data: data)
                        .padding()
                        .allowsHitTesting(false)
                }
                .frame(height: 360)
                .listRowSeparator(.hidden)
                .listRowInsets(.init(top: 0, leading: 0, bottom: 0, trailing: 0))
                .flareTextBackground(.flareSystemGroupedBackground)
                .listRowBackground(Color.clear)

                if let renderError {
                    Section {
                        Text(renderError)
                        Button("share_replies_retry") { renderAttempt += 1 }
                    }
                }

                Section {
                    Button {
                        crossPost()
                    } label: {
                        Label {
                            Text("share_crosspost")
                        } icon: {
                            Image(fontAwesome: .retweet)
                        }
                    }
                    .disabled(currentImage == nil)
                    .accessibilityIdentifier("profile_share_crosspost")

                    if let url = shareLinkURL {
                        ShareLink(item: url) {
                            Label("share_link", systemImage: "link")
                        }
                        .accessibilityIdentifier("profile_share_link")
                    }

                    if let image = currentImage {
                        ShareLink(
                            item: Image(uiImage: image),
                            preview: SharePreview("share_screenshot", image: Image(uiImage: image))
                        ) {
                            Label("share_screenshot", systemImage: "photo")
                        }
                        .accessibilityIdentifier("profile_share_screenshot")
                    }

                    Button {
                        saveImage()
                    } label: {
                        Label("save_screenshot", systemImage: "arrow.down.circle")
                    }
                    .disabled(currentImage == nil)
                    .accessibilityIdentifier("profile_share_save_screenshot")
                }

                Section {
                    Picker("appearance_layout_group_title", selection: $previewStyle) {
                        Text("appearance_timeline_display_mode_card").tag(SharePreviewStyle.card)
                        Text("status_share_sheet_preview_content").tag(SharePreviewStyle.content)
                    }

                    Picker("appearance_theme", selection: $theme) {
                        Text("appearance_theme_system").tag(nil as ColorScheme?)
                        Text("appearance_theme_light").tag(Optional(ColorScheme.light))
                        Text("appearance_theme_dark").tag(Optional(ColorScheme.dark))
                    }
                }
            } errorContent: { error in
                ContentUnavailableView {
                    Label("share_screenshot_failed", systemImage: "exclamationmark.triangle")
                } description: {
                    Text(error.message ?? String(localized: "share_screenshot_failed"))
                }
            } loadingContent: {
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .padding()
            }
        }
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(role: .cancel) {
                    dismiss()
                } label: {
                    Label {
                        Text("Cancel")
                    } icon: {
                        Image(fontAwesome: .xmark)
                    }
                }
            }
        }
        .navigationTitle("fx_share")
        .task(id: previewRevision) {
            let revision = previewRevision
            image = nil
            renderError = nil
            guard case .success(let data) = onEnum(of: state.profile) else { return }
            do {
                let result = try await previewView(data: data.data)
                    .snapshot(colorScheme: theme ?? colorScheme, dynamicTypeSize: dynamicTypeSize)
                try Task.checkCancellation()
                guard revision == previewRevision else { return }
                image = result
                renderedRevision = revision
                observer?.onRendered?(revision, result, { crossPost() })
            } catch is CancellationError {
            } catch {
                guard revision == previewRevision else { return }
                renderError = error.localizedDescription
            }
        }
    }

    @ViewBuilder
    private func previewView(data: UiProfile) -> some View {
        ShareSheetCardContainer(
            previewStyle: previewStyle,
            theme: theme ?? colorScheme,
            appliesContentPadding: false
        ) {
            ProfileShareContent(profile: data)
        }
        .environment(\.timelineAppearance, timelineAppearance)
        .environment(\.translateConfig, translateConfig)
    }

    private func saveImage() {
        guard let image = currentImage else { return }
        MediaSaver.shared.saveImage(image)
        dismiss()
    }

    private func crossPost() {
        guard let imageData = currentImage?.pngData() else { return }
        let text = trimmedShareUrl.map { "\n\n\($0)" } ?? ""
        onCrossPost(
            ComposePrefill(
                text: text,
                cursorPosition: 0,
                imageData: imageData,
                fileName: "flare-profile-crosspost.png"
            )
        )
    }
}
