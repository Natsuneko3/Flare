import UIKit
import KotlinSharedUI
import FlareAppleUI

/// UIKit port of `ListErrorView`.
///
/// Matches the four cases of the SwiftUI implementation:
///   * `LoginExpiredException` → login-expired prompt with account key
///   * `RequireReLoginException` → permission-denied prompt
///   * `SessionChallengeException` → interactive challenge verification prompt
///   * everything else → generic error with retry + message
final class ListErrorUIView: UIView {
    var onOpenURL: ((URL) -> Void)?
    var onSessionChallenge: ((SessionChallengeException, @escaping () -> Void) -> Void)? {
        didSet {
            if mode == .sessionChallenge {
                button.setTitle(sessionChallengeButtonTitle, for: .normal)
            }
        }
    }
    private var onRetry: (() -> Void)?
    private var actionURL: URL?
    private var challengeException: SessionChallengeException?

    private let iconView: UIImageView = {
        let iv = UIImageView()
        iv.contentMode = .scaleAspectFit
        iv.tintColor = .label
        iv.translatesAutoresizingMaskIntoConstraints = false
        return iv
    }()
    private let titleLabel: UILabel = {
        let l = UILabel()
        l.textColor = .flareLabel
        l.font = .preferredFont(forTextStyle: .headline)
        l.textAlignment = .center
        l.numberOfLines = 0
        return l
    }()
    private let messageLabel: UILabel = {
        let l = UILabel()
        l.textColor = .flareLabel
        l.font = .preferredFont(forTextStyle: .body)
        l.textAlignment = .center
        l.numberOfLines = 0
        l.isHidden = true
        return l
    }()
    private let button: UIButton = {
        var cfg = UIButton.Configuration.borderedProminent()
        cfg.cornerStyle = .capsule
        let b = UIButton(configuration: cfg)
        return b
    }()
    private let detailLabel: UILabel = {
        let l = UILabel()
        l.font = .preferredFont(forTextStyle: .caption1)
        l.textColor = .secondaryLabel
        l.textAlignment = .center
        l.numberOfLines = 0
        l.isHidden = true
        return l
    }()
    private let stack = UIStackView()

    override init(frame: CGRect) {
        super.init(frame: frame)
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 8
        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.addArrangedSubview(iconView)
        stack.addArrangedSubview(titleLabel)
        stack.addArrangedSubview(messageLabel)
        stack.addArrangedSubview(button)
        stack.addArrangedSubview(detailLabel)
        addSubview(stack)
        NSLayoutConstraint.activate([
            iconView.widthAnchor.constraint(equalToConstant: 64),
            iconView.heightAnchor.constraint(equalToConstant: 64),
            stack.centerXAnchor.constraint(equalTo: centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: centerYAnchor),
            stack.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -16),
            stack.topAnchor.constraint(greaterThanOrEqualTo: topAnchor, constant: 16),
        ])
        button.addAction(UIAction { [weak self] _ in self?.handleButtonTap() }, for: .touchUpInside)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) not supported") }

    enum Mode: Equatable {
        case retry
        case openLogin
        case sessionChallenge
    }
    private var mode: Mode = .retry
    var currentModeForTesting: Mode { mode }

    private var sessionChallengeButtonTitle: String {
        onSessionChallenge != nil
            ? String(localized: "session_challenge_complete_verification")
            : String(localized: "action_retry")
    }

    func configure(error: KotlinThrowable, onRetry: @escaping () -> Void) {
        self.onRetry = onRetry
        actionURL = nil
        challengeException = nil
        detailLabel.isHidden = true
        messageLabel.isHidden = true

        if let expired = error as? LoginExpiredException {
            iconView.image = UIImage(systemName: "person.badge.shield.exclamationmark")
            titleLabel.text = String(format: String(localized: "error_login_expired %@"), "\(expired.accountKey)")
            button.setTitle(String(localized: "error_login_expired_action"), for: .normal)
            actionURL = reloginURL(accountKey: expired.accountKey, platformId: expired.platformId)
            mode = .openLogin
        } else if let reloginError = error as? RequireReLoginException {
            iconView.image = UIImage(systemName: "person.badge.shield.exclamationmark")
            titleLabel.text = String(localized: "permission_denied_title")
            messageLabel.text = String(localized: "permission_denied_message")
            messageLabel.isHidden = false
            button.setTitle(String(localized: "error_login_expired_action"), for: .normal)
            actionURL = reloginURL(accountKey: reloginError.accountKey, platformId: reloginError.platformId)
            mode = .openLogin
        } else if let challengeError = error as? SessionChallengeException {
            iconView.image = UIImage(systemName: "exclamationmark.shield")
            let trimmed = challengeError.displayMessage.trimmingCharacters(in: .whitespacesAndNewlines)
            titleLabel.text = trimmed.isEmpty
                ? String(localized: "session_challenge_required_message")
                : trimmed
            button.setTitle(sessionChallengeButtonTitle, for: .normal)
            challengeException = challengeError
            mode = .sessionChallenge
        } else {
            iconView.image = UIImage(systemName: "exclamationmark.triangle.text.page")
            titleLabel.text = String(localized: "error")
            button.setTitle(String(localized: "action_retry"), for: .normal)
            actionURL = nil
            mode = .retry
            if let message = error.message {
                detailLabel.text = message
                detailLabel.isHidden = false
            }
        }
    }

    private func handleButtonTap() {
        switch mode {
        case .retry:
            onRetry?()
        case .openLogin:
            if let url = actionURL { onOpenURL?(url) }
        case .sessionChallenge:
            if let challengeException, let onSessionChallenge {
                let retry = onRetry
                onSessionChallenge(challengeException) {
                    retry?()
                }
            } else {
                onRetry?()
            }
        }
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
