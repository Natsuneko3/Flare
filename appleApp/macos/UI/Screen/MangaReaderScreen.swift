import AppKit
import FlareAppleCore
import FlareAppleUI
import Foundation
@preconcurrency import KotlinSharedUI
import SwiftUI

@MainActor
final class MacMangaShareAnchorBox {
    weak var view: NSView?
}

@MainActor
struct MangaReaderScreen: View {
    @State private var presenter: KotlinPresenter<MangaReaderPresenterState>
    @State private var hudVisible = true
    @State private var isSavingPage = false
    @State private var isSharingPage = false
    @State private var exportAlert: MacMangaExportAlert?
    @State private var shareAnchor = MacMangaShareAnchorBox()

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
            },
            onOpenExternalURL: { rawURL in
                onNavigate(.externalLink(rawURL))
            },
            shareAnchor: shareAnchor
        )
        .navigationBarBackButtonHidden(true)
        .navigationTitle("")
        .preferredColorScheme(.dark)
        .alert(item: $exportAlert) { alert in
            Alert(
                title: Text(verbatim: alert.title),
                message: Text(verbatim: alert.message),
                dismissButton: .default(Text(verbatim: FlareAppleUILocalization.string("Ok", fallback: "OK")))
            )
        }
    }

    private func savePage(original: any UiMedia, displayed: any UiMedia, pageIndex: Int) {
        #if !FLARE_MAC_UNIT_TESTS
        guard !isSavingPage else { return }
        let localURL = URL(string: displayed.url).flatMap { $0.isFileURL ? $0 : nil }
        let preparedFile: URL?
        do {
            preparedFile = try localURL.map { try ImageTranslationExportFile.prepare($0) }
        } catch {
            exportAlert = MacMangaExportAlert(
                title: FlareAppleUILocalization.string("save_error", fallback: "Save Failed"),
                message: error.localizedDescription
            )
            return
        }

        let shareContext = MacMediaShareContext(
            statusKey: "\(mangaKey.id)_\(presenter.state.activeChapterId)",
            userHandle: "p\(pageIndex + 1)"
        )
        guard let source = MacMediaExportSource(media: displayed, shareContext: shareContext) else {
            return
        }

        isSavingPage = true
        Task { @MainActor in
            defer { isSavingPage = false }
            do {
                _ = try await MacMediaFileExporter.save(
                    source: source,
                    existingFileURL: preparedFile
                )
                if let preparedFile {
                    try? FileManager.default.removeItem(at: preparedFile.deletingLastPathComponent())
                }
            } catch {
                exportAlert = MacMangaExportAlert(
                    title: FlareAppleUILocalization.string("save_error", fallback: "Save Failed"),
                    message: error.localizedDescription
                )
            }
        }
        #else
        _ = (original, displayed, pageIndex)
        #endif
    }

    private func sharePage(displayed: any UiMedia, pageIndex: Int) {
        #if !FLARE_MAC_UNIT_TESTS
        guard !isSharingPage else { return }
        let localURL = URL(string: displayed.url).flatMap { $0.isFileURL ? $0 : nil }
        let preparedFile: URL?
        do {
            preparedFile = try localURL.map { try ImageTranslationExportFile.prepare($0) }
        } catch {
            exportAlert = MacMangaExportAlert(
                title: FlareAppleUILocalization.string("fx_share", fallback: "Share"),
                message: error.localizedDescription
            )
            return
        }

        let shareContext = MacMediaShareContext(
            statusKey: "\(mangaKey.id)_\(presenter.state.activeChapterId)",
            userHandle: "p\(pageIndex + 1)"
        )
        guard let source = MacMediaExportSource(media: displayed, shareContext: shareContext),
              source.supportsSharing else {
            return
        }

        isSharingPage = true
        Task { @MainActor in
            defer { isSharingPage = false }
            do {
                let fileURL: URL
                if let preparedFile {
                    fileURL = preparedFile
                } else {
                    fileURL = try await MacMediaFileExporter.makeShareFile(source: source)
                }
                guard !Task.isCancelled else { return }
                try MacMediaFileExporter.presentSharePicker(
                    fileURL: fileURL,
                    relativeTo: shareAnchor.view ?? NSApp.keyWindow?.contentView
                )
            } catch {
                exportAlert = MacMangaExportAlert(
                    title: FlareAppleUILocalization.string("fx_share", fallback: "Share"),
                    message: error.localizedDescription
                )
            }
        }
        #else
        _ = (displayed, pageIndex)
        #endif
    }
}

@MainActor
struct MangaReaderContentView: View {
    @Environment(\.openURL) private var openURL

    let state: any MangaReaderPresenterState
    @Binding var hudVisible: Bool
    let goBack: () -> Void
    let controller: MangaReaderInteractionController?
    let onSavePage: ((any UiMedia, any UiMedia, Int) -> Void)?
    let onSharePage: ((any UiMedia, any UiMedia, Int) -> Void)?
    let onOpenExternalURL: ((String) -> Void)?
    let shareAnchor: MacMangaShareAnchorBox?

    @State private var showSettings = false

    init(
        state: any MangaReaderPresenterState,
        hudVisible: Binding<Bool>,
        goBack: @escaping () -> Void,
        controller: MangaReaderInteractionController? = nil,
        onSavePage: ((any UiMedia, any UiMedia, Int) -> Void)? = nil,
        onSharePage: ((any UiMedia, any UiMedia, Int) -> Void)? = nil,
        onOpenExternalURL: ((String) -> Void)? = nil,
        shareAnchor: MacMangaShareAnchorBox? = nil
    ) {
        self.state = state
        self._hudVisible = hudVisible
        self.goBack = goBack
        self.controller = controller
        self.onSavePage = onSavePage
        self.onSharePage = onSharePage
        self.onOpenExternalURL = onOpenExternalURL
        self.shareAnchor = shareAnchor
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
                Color.black

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
                    .frame(width: 32, height: 32)
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
                            .frame(width: 32, height: 32)
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
                            .frame(width: 32, height: 32)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.white)
                    .disabled(item.loading)
                }

                if let onSavePage {
                    Button {
                        onSavePage(ready.original, ready.displayed, Int(state.currentPageIndex))
                    } label: {
                        Image(fontAwesome: .download)
                            .frame(width: 32, height: 32)
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
                            .frame(width: 32, height: 32)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.white)
                    .background {
                        if let shareAnchor {
                            MacMangaShareAnchorView(anchor: shareAnchor)
                        }
                    }
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
                        .frame(width: 32, height: 32)
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
                showSettings.toggle()
            } label: {
                Image(fontAwesome: .gear)
                    .frame(width: 32, height: 32)
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
            .popover(isPresented: $showSettings) {
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

private nonisolated struct MacMangaExportAlert: Identifiable, Sendable {
    let id = UUID()
    let title: String
    let message: String
}

@MainActor
private struct MacMangaShareAnchorView: NSViewRepresentable {
    let anchor: MacMangaShareAnchorBox

    func makeCoordinator() -> MacMangaShareAnchorCoordinator {
        MacMangaShareAnchorCoordinator(anchor: anchor)
    }

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        context.coordinator.update(view: view)
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        context.coordinator.update(view: nsView)
    }

    static func dismantleNSView(_ nsView: NSView, coordinator: MacMangaShareAnchorCoordinator) {
        coordinator.clear(view: nsView)
    }
}

@MainActor
private final class MacMangaShareAnchorCoordinator {
    private weak var anchor: MacMangaShareAnchorBox?

    init(anchor: MacMangaShareAnchorBox) {
        self.anchor = anchor
    }

    func update(view: NSView) {
        anchor?.view = view
    }

    func clear(view: NSView) {
        if anchor?.view === view {
            anchor?.view = nil
        }
    }
}
