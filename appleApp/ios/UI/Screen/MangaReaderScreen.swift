import FlareAppleCore
import FlareAppleUI
import Foundation
@preconcurrency import KotlinSharedUI
import Observation
import SwiftUI
import UIKit

nonisolated struct IOSMangaExportAlert: Identifiable, Equatable, Sendable {
    let id = UUID()
    let title: String
    let message: String
}

struct IOSMangaReaderShareButtonVisiblePreferenceKey: PreferenceKey {
    static var defaultValue: Bool { false }

    static func reduce(value: inout Bool, nextValue: () -> Bool) {
        value = value || nextValue()
    }
}

private enum IOSMangaShareError: LocalizedError {
    case missingPresenter

    var errorDescription: String? {
        String(localized: "Unable to present the share sheet")
    }
}

@MainActor
@Observable
final class IOSMangaReaderShareCoordinator {
    var isPreparingShare = false
    var exportAlert: IOSMangaExportAlert?

    func sharePage(
        displayed: any UiMedia,
        mangaId: String,
        chapterId: String,
        pageIndex: Int,
        chapterUrl: String?,
        onShareChapterURL: (String) -> Void,
        onPreparingNeeded: @escaping @Sendable () -> Void = { MediaSaver.showPreparingMedia() },
        presentFile: @escaping @MainActor (URL) throws -> Void
    ) async {
        if let localURL = URL(string: displayed.url), localURL.isFileURL {
            do {
                let exportedFile = try ImageTranslationExportFile.prepare(localURL)
                try presentFile(exportedFile)
            } catch {
                exportAlert = IOSMangaExportAlert(
                    title: FlareAppleUILocalization.string("fx_share", fallback: "Share"),
                    message: error.localizedDescription
                )
            }
            return
        }

        if displayed is UiMediaImage || displayed is UiMediaGif {
            guard !isPreparingShare else { return }
            isPreparingShare = true
            defer { isPreparingShare = false }
            do {
                let fileURL = try await OriginalImageShareFile.make(
                    media: displayed,
                    statusKey: "\(mangaId)_\(chapterId)",
                    userHandle: "p\(pageIndex + 1)",
                    onPreparingNeeded: onPreparingNeeded
                )
                guard !Task.isCancelled else { return }
                try presentFile(fileURL)
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled else { return }
                exportAlert = IOSMangaExportAlert(
                    title: FlareAppleUILocalization.string("fx_share", fallback: "Share"),
                    message: error.localizedDescription
                )
            }
            return
        }

        if let shareUrl = chapterUrl?.trimmingCharacters(in: .whitespacesAndNewlines),
           !shareUrl.isEmpty {
            onShareChapterURL(shareUrl)
        }
    }
}

struct MangaReaderScreen: View {
    @State private var presenter: KotlinPresenter<MangaReaderPresenterState>
    @State private var hudVisible = true
    @State private var shareCoordinator = IOSMangaReaderShareCoordinator()

    private let accountType: AccountType
    private let mangaKey: MicroBlogKey
    private let onNavigate: (Route) -> Void
    private let goBack: () -> Void

    init(
        accountType: AccountType,
        mangaKey: MicroBlogKey,
        chapterId: String,
        initialPage: Int?,
        onNavigate: @escaping (Route) -> Void,
        goBack: @escaping () -> Void
    ) {
        self.accountType = accountType
        self.mangaKey = mangaKey
        self.onNavigate = onNavigate
        self.goBack = goBack
        self._presenter = State(
            initialValue: KotlinPresenter(
                presenter: MangaReaderPresenter(
                    accountType: accountType,
                    mangaKey: mangaKey,
                    initialChapterId: chapterId,
                    initialPage: initialPage.map { KotlinInt(int: Int32($0)) }
                )
            )
        )
    }

    var body: some View {
        MangaReaderContentView(
            state: presenter.state,
            hudVisible: $hudVisible,
            goBack: goBack,
            onSavePage: { original, displayed, pageIndex in
                savePage(original: original, displayed: displayed, pageIndex: pageIndex)
            },
            onSharePage: { _, displayed, pageIndex in
                sharePage(displayed: displayed, pageIndex: pageIndex)
            }
        )
        .toolbar(.hidden, for: .navigationBar)
        .toolbar(.hidden, for: .tabBar)
        .statusBarHidden(!hudVisible)
        .preferredColorScheme(.dark)
        .alert(item: $shareCoordinator.exportAlert) { alert in
            Alert(
                title: Text(verbatim: alert.title),
                message: Text(verbatim: alert.message),
                dismissButton: .default(
                    Text(verbatim: FlareAppleUILocalization.string("Ok", fallback: "OK"))
                )
            )
        }
    }

    private func savePage(original: any UiMedia, displayed: any UiMedia, pageIndex: Int) {
        let fileName = mangaPageFileName(original: original, displayed: displayed, pageIndex: pageIndex)
        switch onEnum(of: displayed) {
        case .ugoira(let animation):
            MediaSaver.shared.saveUgoira(animation, fileName: fileName)
        case .image(let image):
            MediaSaver.shared.saveImage(media: image)
        case .gif(let gif):
            MediaSaver.shared.saveImage(media: gif)
        case .video(let video):
            MediaSaver.shared.saveVideo(media: video)
        case .audio(let audio):
            MediaSaver.shared.saveFile(media: audio, fileName: fileName)
        }
    }

    private func sharePage(displayed: any UiMedia, pageIndex: Int) {
        let activeChapter = presenter.state.activeChapterId
        let chapterUrl = presenter.state.chapterUrl
        Task { @MainActor in
            await shareCoordinator.sharePage(
                displayed: displayed,
                mangaId: mangaKey.id,
                chapterId: activeChapter,
                pageIndex: pageIndex,
                chapterUrl: chapterUrl,
                onShareChapterURL: { shareUrl in
                    onNavigate(.statusShareSheet(accountType, mangaKey, shareUrl, nil, nil))
                },
                presentFile: { fileURL in
                    guard let host = Self.topViewController() else {
                        throw IOSMangaShareError.missingPresenter
                    }
                    presentActivitySheet(items: [fileURL], from: host)
                }
            )
        }
    }

    private func mangaPageFileName(original: any UiMedia, displayed: any UiMedia, pageIndex: Int) -> String {
        MediaFileNamePolicy.shared.mangaPageFileName(
            mangaId: mangaKey.id,
            chapterId: presenter.state.activeChapterId,
            pageIndex: Int32(pageIndex),
            original: original,
            displayed: displayed
        )
    }

    private func presentActivitySheet(items: [Any], from presenter: UIViewController) {
        let controller = UIActivityViewController(activityItems: items, applicationActivities: nil)
        controller.popoverPresentationController?.sourceView = presenter.view
        controller.popoverPresentationController?.sourceRect = CGRect(
            x: presenter.view.bounds.midX,
            y: 44,
            width: 1,
            height: 1
        )
        presenter.present(controller, animated: true)
    }

    private static func topViewController() -> UIViewController? {
        let rootViewController = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }?
            .windows
            .first { $0.isKeyWindow }?
            .rootViewController
        return topViewController(from: rootViewController)
    }

    private static func topViewController(from viewController: UIViewController?) -> UIViewController? {
        if let nav = viewController as? UINavigationController {
            return topViewController(from: nav.visibleViewController)
        }
        if let tab = viewController as? UITabBarController {
            return topViewController(from: tab.selectedViewController)
        }
        if let presented = viewController?.presentedViewController {
            return topViewController(from: presented)
        }
        return viewController
    }
}

struct MangaReaderContentView: View {
    @Environment(\.openURL) private var openURL
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    let state: any MangaReaderPresenterState
    @Binding var hudVisible: Bool
    let goBack: () -> Void
    let controller: MangaReaderInteractionController?
    let onSavePage: ((any UiMedia, any UiMedia, Int) -> Void)?
    let onSharePage: ((any UiMedia, any UiMedia, Int) -> Void)?
    let onOpenExternalURL: ((String) -> Void)?

    @State private var showSettings = false

    init(
        state: any MangaReaderPresenterState,
        hudVisible: Binding<Bool>,
        goBack: @escaping () -> Void,
        controller: MangaReaderInteractionController? = nil,
        onSavePage: ((any UiMedia, any UiMedia, Int) -> Void)? = nil,
        onSharePage: ((any UiMedia, any UiMedia, Int) -> Void)? = nil,
        onOpenExternalURL: ((String) -> Void)? = nil
    ) {
        self.state = state
        self._hudVisible = hudVisible
        self.goBack = goBack
        self.controller = controller
        self.onSavePage = onSavePage
        self.onSharePage = onSharePage
        self.onOpenExternalURL = onOpenExternalURL
    }

    private var currentReadyPage: PageMediaStatusReady? {
        let idx = Int(state.currentPageIndex)
        guard state.pages.indices.contains(idx) else { return nil }
        return state.pages[idx].status as? PageMediaStatusReady
    }

    var body: some View {
        GeometryReader { proxy in
            let isWideViewport = proxy.size.width >= 720

            ZStack {
                Color.black.ignoresSafeArea()

                MangaReaderCanvas(
                    state: state,
                    hudVisible: hudVisible,
                    onToggleHud: {
                        withAnimation(.easeInOut(duration: 0.18)) {
                            hudVisible.toggle()
                        }
                    },
                    onBack: goBack,
                    onOpenExternalURL: onOpenExternalURL,
                    controller: controller
                )
                .ignoresSafeArea()

                VStack(spacing: 0) {
                    if hudVisible {
                        topHudBar(isWideViewport: isWideViewport)
                            .transition(.move(edge: .top).combined(with: .opacity))
                    }

                    Spacer(minLength: 0)

                    if hudVisible && !state.pages.isEmpty {
                        bottomHudBar
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }

                if !hudVisible && !state.pages.isEmpty {
                    Text(verbatim: formatMangaReaderPageBadge(state: state))
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.white)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(.black.opacity(0.68), in: Capsule())
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                        .padding(.trailing, 16)
                        .padding(.bottom, 12)
                        .accessibilityIdentifier("manga_reader_collapsed_page_badge")
                        .allowsHitTesting(false)
                }
            }
            .sheet(isPresented: Binding(
                get: { showSettings && horizontalSizeClass != .regular },
                set: { if !$0 { showSettings = false } }
            )) {
                MangaReaderSettingsView(state: state, isWideViewport: isWideViewport)
                    .presentationDetents([.medium])
                    .preferredColorScheme(.dark)
            }
        }
        .imageTranslationErrorAlert(
            state: state.imageTranslation,
            media: currentReadyPage?.original
        )
    }

    private func topHudBar(isWideViewport: Bool) -> some View {
        HStack(spacing: 8) {
            Button(action: goBack) {
                Image(systemName: "chevron.backward")
                    .font(.body.weight(.semibold))
                    .frame(width: 36, height: 36)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(.white)
            .accessibilityIdentifier("manga_reader_back")

            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: state.chapterTitle)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                if !state.mangaTitle.isEmpty {
                    Text(verbatim: state.mangaTitle)
                        .font(.caption2)
                        .foregroundStyle(.white.opacity(0.72))
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if let ready = currentReadyPage {
                let item = state.imageTranslation.item(media: ready.original)
                if state.imageTranslation.canToggle(media: ready.original) {
                    Button {
                        state.imageTranslation.toggle(media: ready.original)
                    } label: {
                        Image(fontAwesome: .language)
                            .frame(width: 34, height: 34)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.white)
                    .disabled(item.loading)
                }

                if state.imageTranslation.canRegenerate(media: ready.original) {
                    Button {
                        state.imageTranslation.regenerate(media: ready.original)
                    } label: {
                        Image(systemName: "arrow.clockwise")
                            .frame(width: 34, height: 34)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.white)
                    .disabled(item.loading)
                }

                if let onSavePage, !(ready.original is UiMediaUgoira) {
                    Button {
                        onSavePage(ready.original, ready.displayed, Int(state.currentPageIndex))
                    } label: {
                        Image(fontAwesome: .download)
                            .frame(width: 34, height: 34)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.white)
                    .accessibilityLabel(
                        Text(verbatim: FlareAppleUILocalization.string("media_menu_download", fallback: "Save"))
                    )
                }

                if let onSharePage, ready.displayed is UiMediaImage || ready.displayed is UiMediaGif {
                    Button {
                        onSharePage(ready.original, ready.displayed, Int(state.currentPageIndex))
                    } label: {
                        Image(fontAwesome: .shareNodes)
                            .frame(width: 34, height: 34)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.white)
                    .accessibilityIdentifier("manga_reader_share_button")
                    .preference(key: IOSMangaReaderShareButtonVisiblePreferenceKey.self, value: true)
                    .accessibilityLabel(
                        Text(verbatim: FlareAppleUILocalization.string("media_menu_share_image", fallback: "Share image"))
                    )
                }
            }

            if let chapterUrl = state.chapterUrl?.trimmingCharacters(in: .whitespacesAndNewlines),
               !chapterUrl.isEmpty {
                Button {
                    openChapterURL(chapterUrl)
                } label: {
                    Image(systemName: "safari")
                        .frame(width: 34, height: 34)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .foregroundStyle(.white)
                .accessibilityLabel(
                    Text(
                        verbatim: FlareAppleUILocalization.string(
                            "manga_open_in_browser",
                            fallback: "Open in browser"
                        )
                    )
                )
            }

            Button {
                showSettings = true
            } label: {
                Image(fontAwesome: .gear)
                    .frame(width: 34, height: 34)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(.white)
            .accessibilityIdentifier("manga_reader_settings_button")
            .accessibilityLabel(
                Text(
                    verbatim: FlareAppleUILocalization.string(
                        "manga_reader_settings",
                        fallback: "Reader settings"
                    )
                )
            )
            .popover(isPresented: Binding(
                get: { showSettings && horizontalSizeClass == .regular },
                set: { if !$0 { showSettings = false } }
            )) {
                MangaReaderSettingsView(state: state, isWideViewport: isWideViewport)
                    .preferredColorScheme(.dark)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial)
    }

    private var bottomHudBar: some View {
        let isRtl = state.direction == .rtl
        let prevUnlocked = state.previousBoundary as? ChapterBoundaryUnlocked
        let nextUnlocked = state.nextBoundary as? ChapterBoundaryUnlocked
        let leftTarget = isRtl ? nextUnlocked : prevUnlocked
        let rightTarget = isRtl ? prevUnlocked : nextUnlocked

        return HStack(spacing: 10) {
            Button {
                if let leftTarget {
                    state.openAdjacentChapter(boundary: leftTarget)
                }
            } label: {
                Image(systemName: "backward.end.fill")
                    .font(.subheadline)
                    .frame(width: 32, height: 32)
            }
            .buttonStyle(.plain)
            .foregroundStyle(leftTarget == nil ? .white.opacity(0.35) : .white)
            .disabled(leftTarget == nil)

            MangaReaderScrubberView(
                pageCount: state.pages.count,
                currentPageIndex: Int(state.currentPageIndex),
                isRtl: isRtl,
                onPageSelected: { page in
                    state.goToPage(pageIndex: Int32(page))
                }
            )

            Button {
                if let rightTarget {
                    state.openAdjacentChapter(boundary: rightTarget)
                }
            } label: {
                Image(systemName: "forward.end.fill")
                    .font(.subheadline)
                    .frame(width: 32, height: 32)
            }
            .buttonStyle(.plain)
            .foregroundStyle(rightTarget == nil ? .white.opacity(0.35) : .white)
            .disabled(rightTarget == nil)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .frame(maxWidth: 640)
        .background(.ultraThinMaterial, in: Capsule())
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
    }

    private func openChapterURL(_ rawURL: String) {
        if let onOpenExternalURL {
            onOpenExternalURL(rawURL)
            return
        }
        let uri = DeeplinkRoute.OpenLinkDirectly(url: rawURL).toUri()
        if let url = URL(string: uri) {
            openURL(url)
        } else if let direct = URL(string: rawURL) {
            openURL(direct)
        }
    }
}
