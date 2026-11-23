import AppKit
import FlareAppleCore
import FlareAppleUI
@preconcurrency import KotlinSharedUI
import Observation
import SwiftUI

struct MacMangaReaderWindowValue: Codable, Hashable, Sendable {
    let accountTypeKey: String
    let mangaKeyString: String

    init(accountTypeKey: String, mangaKeyString: String) {
        self.accountTypeKey = accountTypeKey
        self.mangaKeyString = mangaKeyString
    }

    init(accountType: AccountType, mangaKey: MicroBlogKey) {
        self.accountTypeKey = Self.makeAccountTypeKey(accountType)
        self.mangaKeyString = mangaKey.description()
    }

    static func makeAccountTypeKey(_ accountType: AccountType) -> String {
        switch onEnum(of: accountType) {
        case .specific(let specific):
            return "specific:\(specific.accountKey.description())"
        case .guest:
            return "guest"
        case .guestHost(let guestHost):
            return "guest_host:\(guestHost.host)"
        case .guestPlugin(let guestPlugin):
            return "guest_plugin:\(guestPlugin.platformId.count):\(guestPlugin.platformId):\(guestPlugin.host)"
        }
    }
}

struct MacMangaReaderWindowRequest: Identifiable {
    var id: MacMangaReaderWindowValue { value }
    let value: MacMangaReaderWindowValue
    let accountType: AccountType
    let mangaKey: MicroBlogKey
    let chapterId: String
    let initialPage: Int?
    let revision: Int
}

@MainActor
@Observable
final class MacMangaReaderWindowCoordinator {
    static let windowID = "manga-reader"
    static let shared = MacMangaReaderWindowCoordinator()

    private(set) var requests: [MacMangaReaderWindowValue: MacMangaReaderWindowRequest] = [:]

    init() {}

    @discardableResult
    func open(
        accountType: AccountType,
        mangaKey: MicroBlogKey,
        chapterId: String,
        initialPage: Int?,
        openWindow: OpenWindowAction? = nil
    ) -> MacMangaReaderWindowValue {
        let value = MacMangaReaderWindowValue(accountType: accountType, mangaKey: mangaKey)
        let nextRevision = (requests[value]?.revision ?? 0) + 1
        let request = MacMangaReaderWindowRequest(
            value: value,
            accountType: accountType,
            mangaKey: mangaKey,
            chapterId: chapterId,
            initialPage: initialPage,
            revision: nextRevision
        )
        requests[value] = request
        openWindow?(id: Self.windowID, value: value)
        return value
    }

    func request(for value: MacMangaReaderWindowValue?) -> MacMangaReaderWindowRequest? {
        guard let value else { return nil }
        return requests[value]
    }

    func close(value: MacMangaReaderWindowValue?) {
        guard let value else { return }
        requests.removeValue(forKey: value)
    }
}

@MainActor
struct MacMangaReaderWindowRoot: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @Environment(\.openWindow) private var openWindow

    let value: MacMangaReaderWindowValue?
    let coordinator: MacMangaReaderWindowCoordinator

    init(
        value: MacMangaReaderWindowValue?,
        coordinator: MacMangaReaderWindowCoordinator = .shared
    ) {
        self.value = value
        self.coordinator = coordinator
    }

    var body: some View {
        if let request = coordinator.request(for: value) {
            NavigationStack {
                MangaReaderScreen(
                    accountType: request.accountType,
                    mangaKey: request.mangaKey,
                    chapterId: request.chapterId,
                    initialPage: request.initialPage,
                    requestRevision: request.revision,
                    usesWindowToolbar: true,
                    onNavigate: { route in
                        handleNavigate(route)
                    },
                    goBack: {
                        dismiss()
                    }
                )
            }
            .frame(minWidth: 560, idealWidth: 960, minHeight: 520, idealHeight: 820)
            .background(MangaReaderWindowConfigurator(revision: request.revision))
            .id(request.value)
        } else {
            EmptyView()
                .onAppear {
                    dismiss()
                }
        }
    }

    private func handleNavigate(_ route: Route) {
        switch route {
        case .externalLink(let rawURL):
            if let url = URL(string: rawURL) {
                openURL(url)
            }
        case .mangaReader(let accountType, let mangaKey, let chapterId, let initialPage):
            coordinator.open(
                accountType: accountType,
                mangaKey: mangaKey,
                chapterId: chapterId,
                initialPage: initialPage,
                openWindow: openWindow
            )
        default:
            #if !FLARE_MAC_UNIT_TESTS
            MacMainWindowCoordinator.shared.open(route: route, openWindow: openWindow)
            #else
            break
            #endif
        }
    }
}

@MainActor
private struct MangaReaderWindowConfigurator: NSViewRepresentable {
    let revision: Int

    func makeCoordinator() -> Coordinator {
        Coordinator(initialRevision: revision)
    }

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        DispatchQueue.main.async {
            context.coordinator.configure(window: view.window, revision: revision, isInitial: true)
        }
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        let currentRevision = revision
        DispatchQueue.main.async {
            context.coordinator.configure(window: nsView.window, revision: currentRevision, isInitial: false)
        }
    }

    @MainActor
    final class Coordinator {
        private var lastFocusedRevision: Int

        init(initialRevision: Int) {
            self.lastFocusedRevision = initialRevision
        }

        func configure(window: NSWindow?, revision: Int, isInitial: Bool) {
            guard let window else { return }
            window.titlebarAppearsTransparent = true
            window.styleMask.insert(.fullSizeContentView)
            window.backgroundColor = .black
            if isInitial || revision != lastFocusedRevision {
                lastFocusedRevision = revision
                if !isInitial {
                    window.makeKeyAndOrderFront(nil)
                }
            }
        }
    }
}
