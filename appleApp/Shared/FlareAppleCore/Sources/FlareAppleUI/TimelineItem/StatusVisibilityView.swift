import SwiftUI
import KotlinSharedUI
import FlareAppleCore

public struct StatusVisibilityView: View {
    private let data: UiTimelineV2.PostVisibility
    private let iconSize: CGFloat?

    public init(data: UiTimelineV2.PostVisibility, iconSize: CGFloat? = nil) {
        self.data = data
        self.iconSize = iconSize
    }

    public var body: some View {
        icon(fontAwesomeIcon)
            .accessibilityLabel(Text(accessibilityLabelKey, bundle: FlareAppleUILocalization.bundle))
    }

    @ViewBuilder
    private func icon(_ value: FontAwesomeIcon) -> some View {
        if let iconSize {
            Image(fontAwesome: value)
                .resizable()
                .scaledToFit()
                .frame(width: iconSize, height: iconSize)
        } else {
            Image(fontAwesome: value)
        }
    }

    private var fontAwesomeIcon: FontAwesomeIcon {
        switch data {
        case .public:
            .globe
        case .home:
            .lockOpen
        case .followers:
            .lock
        case .specified:
            .at
        case .channel:
            .tv
        case .friends:
            .heart
        case .selfOnly:
            .lock
        }
    }

    private var accessibilityLabelKey: LocalizedStringKey {
        switch data {
        case .public:
            "status_visibility_public"
        case .home:
            "status_visibility_unlisted"
        case .followers:
            "matrix_followers"
        case .specified:
            "status_visibility_specified"
        case .channel:
            "channel_title"
        case .friends:
            "visibility_friends"
        case .selfOnly:
            "visibility_self"
        }
    }
}
