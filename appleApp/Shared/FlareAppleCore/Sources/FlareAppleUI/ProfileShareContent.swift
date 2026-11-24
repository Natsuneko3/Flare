import FlareAppleCore
import KotlinSharedUI
import SwiftUI

public struct ProfileShareContent: View {
    private let profile: UiProfile

    public init(profile: UiProfile) {
        self.profile = profile
    }

    public var body: some View {
        CommonProfileHeader(
            user: profile,
            relation: UiStateLoading(),
            followButtonState: UiStateLoading(),
            isMe: UiStateSuccess(data: KotlinBoolean(bool: true)),
            bannerTopInset: 0,
            showsFollowButton: false,
            onFollowClick: { _ in },
            onFollowingClick: {},
            onFansClick: {}
        )
        .padding(.bottom)
        .environment(\.profileUsesPlainStyle, true)
        .frame(width: 360, alignment: .leading)
    }
}
