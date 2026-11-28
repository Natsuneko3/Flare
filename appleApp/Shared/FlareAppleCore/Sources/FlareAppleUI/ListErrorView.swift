import SwiftUI
import KotlinSharedUI
import SwiftUIBackports

struct ListErrorChallengeButtonFramePreferenceKey: PreferenceKey {
    static var defaultValue: CGRect? { nil }

    static func reduce(value: inout CGRect?, nextValue: () -> CGRect?) {
        value = nextValue() ?? value
    }
}

@MainActor
final class ListErrorChallengeActionBox: Sendable {
    let register: @MainActor ((@MainActor () -> Void)?) -> Void

    init(register: @escaping @MainActor ((@MainActor () -> Void)?) -> Void) {
        self.register = register
    }
}

private struct ListErrorChallengeActionBoxKey: EnvironmentKey {
    static let defaultValue: ListErrorChallengeActionBox? = nil
}

extension EnvironmentValues {
    var listErrorChallengeActionBox: ListErrorChallengeActionBox? {
        get { self[ListErrorChallengeActionBoxKey.self] }
        set { self[ListErrorChallengeActionBoxKey.self] = newValue }
    }
}

public struct ListErrorView: View {
    private let error: KotlinThrowable
    @Environment(\.openURL) private var openURL
    @Environment(\.launchSessionChallenge) private var launchSessionChallenge
    @Environment(\.listErrorChallengeActionBox) private var challengeActionBox
    private let onRetry: () -> Void

    public init(error: KotlinThrowable, onRetry: @escaping () -> Void) {
        self.error = error
        self.onRetry = onRetry
    }

    public var body: some View {
        VStack(spacing: 8) {
            if let expiredError = error as? LoginExpiredException {
                Image(systemName: "person.badge.shield.exclamationmark")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 64, height: 64)
                Text("error_login_expired \(expiredError.accountKey)", bundle: FlareAppleUILocalization.bundle)
                    .multilineTextAlignment(.center)
                    .font(.headline)
                Button {
                    if let url = reloginURL(
                        accountKey: expiredError.accountKey,
                        platformId: expiredError.platformId
                    ) {
                        openURL(url)
                    }
                } label: {
                    Text("error_login_expired_action", bundle: FlareAppleUILocalization.bundle)
                }
                .backport
                .glassProminentButtonStyle()
            } else if let reloginError = error as? RequireReLoginException {
                Image(systemName: "person.badge.shield.exclamationmark")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 64, height: 64)
                Text("permission_denied_title", bundle: FlareAppleUILocalization.bundle)
                    .flarePrimaryText()
                    .multilineTextAlignment(.center)
                    .font(.headline)
                Text("permission_denied_message", bundle: FlareAppleUILocalization.bundle)
                    .flarePrimaryText()
                Button {
                    if let url = reloginURL(
                        accountKey: reloginError.accountKey,
                        platformId: reloginError.platformId
                    ) {
                        openURL(url)
                    }
                } label: {
                    Text("error_login_expired_action", bundle: FlareAppleUILocalization.bundle)
                }
                .backport
                .glassProminentButtonStyle()
            } else if let challengeError = error as? SessionChallengeException {
                Image(systemName: "exclamationmark.shield")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 64, height: 64)
                let trimmedMessage = challengeError.displayMessage.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmedMessage.isEmpty {
                    Text(verbatim: trimmedMessage)
                        .flarePrimaryText()
                        .multilineTextAlignment(.center)
                        .font(.headline)
                } else {
                    Text("session_challenge_required_message", bundle: FlareAppleUILocalization.bundle)
                        .flarePrimaryText()
                        .multilineTextAlignment(.center)
                        .font(.headline)
                }
                let primaryAction: @MainActor () -> Void = {
                    if let launchSessionChallenge {
                        launchSessionChallenge(challengeError, onRetry)
                    } else {
                        onRetry()
                    }
                }
                Button(action: primaryAction) {
                    if launchSessionChallenge != nil {
                        Text("session_challenge_complete_verification", bundle: FlareAppleUILocalization.bundle)
                    } else {
                        Text("action_retry", bundle: FlareAppleUILocalization.bundle)
                    }
                }
                .backport
                .glassProminentButtonStyle()
                .background {
                    GeometryReader { proxy in
                        Color.clear.preference(
                            key: ListErrorChallengeButtonFramePreferenceKey.self,
                            value: proxy.frame(in: .global)
                        )
                    }
                }
                .onAppear {
                    challengeActionBox?.register(primaryAction)
                }
                .onChange(of: challengeError.challengeId) { _, _ in
                    challengeActionBox?.register(primaryAction)
                }
                .onDisappear {
                    challengeActionBox?.register(nil)
                }
                .accessibilityIdentifier("list_error_challenge_verify")
                if launchSessionChallenge != nil {
                    Button(action: onRetry) {
                        Text("action_retry", bundle: FlareAppleUILocalization.bundle)
                    }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("list_error_challenge_retry")
                }
            } else {
                Image(systemName: "exclamationmark.triangle.text.page")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 64, height: 64)
                Text("error", bundle: FlareAppleUILocalization.bundle)
                    .flarePrimaryText()
                    .multilineTextAlignment(.center)
                    .font(.headline)
                Button {
                    onRetry()
                } label: {
                    Text("action_retry", bundle: FlareAppleUILocalization.bundle)
                }
                .backport
                .glassProminentButtonStyle()
                if let message = error.message {
                    Text(message)
                        .font(.caption)
                        .flareSecondaryText()
                }
            }
        }
        .padding()
    }

    private func reloginURL(accountKey: MicroBlogKey, platformId: String) -> URL? {
        URL(
            string: DeeplinkRoute.Relogin(
                accountKey: accountKey,
                platformId: platformId
            ).toUri()
        )
    }
}
