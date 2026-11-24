import Foundation

public enum ProfileShareURLResolver {
    public static func resolve(_ shareUrl: String?) -> (raw: String, url: URL)? {
        guard let trimmed = shareUrl?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty,
              trimmed.rangeOfCharacter(from: .whitespacesAndNewlines) == nil,
              let url = URL(string: trimmed),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let host = url.host,
              !host.isEmpty
        else {
            return nil
        }
        return (trimmed, url)
    }
}

#if os(macOS)
import AppKit
import FlareAppleCore
import KotlinSharedUI
import SwiftUI

public struct MacProfileShareData {
    public let userKey: MicroBlogKey
    public let accountType: AccountType
    public let shareUrl: String?

    public init(
        userKey: MicroBlogKey,
        accountType: AccountType,
        shareUrl: String? = nil
    ) {
        self.userKey = userKey
        self.accountType = accountType
        self.shareUrl = shareUrl
    }
}

public struct MacProfileShareMenu<LabelContent: View>: View {
    let data: MacProfileShareData
    let onShareScreenshot: () -> Void
    let onCrossPost: () -> Void
    @ViewBuilder let label: () -> LabelContent
    @Environment(\.openURL) private var openURL

    public init(
        data: MacProfileShareData,
        onShareScreenshot: @escaping () -> Void,
        onCrossPost: @escaping () -> Void,
        @ViewBuilder label: @escaping () -> LabelContent
    ) {
        self.data = data
        self.onShareScreenshot = onShareScreenshot
        self.onCrossPost = onCrossPost
        self.label = label
    }

    public var body: some View {
        Menu {
            Button {
                onCrossPost()
            } label: {
                Label {
                    Text("share_crosspost")
                } icon: {
                    Image(fontAwesome: .retweet)
                }
            }

            if let share = normalizedShareURL {
                ShareLink(item: share.url) {
                    Label("share_link", systemImage: "link")
                }
            }

            Button {
                onShareScreenshot()
            } label: {
                Label("share_screenshot", systemImage: "photo")
            }

            if let share = normalizedShareURL {
                Divider()

                Button {
                    copyLink(share.raw)
                } label: {
                    Label("share_copy_link", systemImage: "doc.on.doc")
                }

                if let browserURL = URL(string: DeeplinkRoute.OpenLinkDirectly(url: share.raw).toUri()) {
                    Button {
                        openURL(browserURL)
                    } label: {
                        Label("deep_link_account_picker_open_in_browser", systemImage: "safari")
                    }
                }
            }
        } label: {
            label()
        }
    }

    var normalizedShareURL: (raw: String, url: URL)? {
        Self.resolveShareURL(from: data.shareUrl)
    }

    public static func resolveShareURL(from shareUrl: String?) -> (raw: String, url: URL)? {
        ProfileShareURLResolver.resolve(shareUrl)
    }

    private func copyLink(_ shareUrl: String) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(shareUrl, forType: .string)
    }
}

public struct MacProfileShareSheet: View {
    private let userKey: MicroBlogKey
    private let purpose: MacSharePurpose
    private let onCrossPost: ((Data, String, String?) -> Void)?

    @State private var presenter: KotlinPresenter<ProfileSharePresenterState>

    private var previewRevisionKey: String {
        if case .success(let data) = onEnum(of: presenter.state.profile) {
            return "loaded-\(data.data.hash)"
        }
        return "unloaded"
    }

    public init(
        userKey: MicroBlogKey,
        accountType: AccountType,
        purpose: MacSharePurpose = .shareScreenshot,
        onCrossPost: ((Data, String, String?) -> Void)? = nil
    ) {
        self.userKey = userKey
        self.purpose = purpose
        self.onCrossPost = onCrossPost
        _presenter = .init(
            wrappedValue: .init(
                presenter: ProfileSharePresenter(
                    accountType: accountType,
                    userKey: userKey
                )
            )
        )
    }

    public var body: some View {
        MacShareSheetScaffold(
            state: presenter.state.profile,
            purpose: purpose,
            previewRevisionKey: previewRevisionKey,
            fileName: "flare-profile-\(userKey.description()).png",
            directoryPrefix: "flare-profile-share",
            fallbackFileName: "flare-profile.png",
            appliesContentPadding: false,
            includesReplies: false,
            onCrossPost: { data, fileName in
                onCrossPost?(
                    data,
                    fileName,
                    ProfileShareURLResolver.resolve(presenter.state.shareUrl)?.url.absoluteString
                )
            }
        ) { profile in
            ProfileShareContent(profile: profile)
        } extraOptions: {
            EmptyView()
        } loadingContent: {
            ProgressView()
        }
    }
}
#endif
