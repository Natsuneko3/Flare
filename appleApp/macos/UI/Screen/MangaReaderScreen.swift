import AppKit
import FlareAppleCore
import FlareAppleUI
import Foundation
import Kingfisher
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
    @State private var isCopyingPage = false
    @State private var exportAlert: MacMangaExportAlert?
    @State private var shareAnchor = MacMangaShareAnchorBox()
    @State private var lastAppliedRevision: Int

    private let accountType: AccountType
    private let mangaKey: MicroBlogKey
    private let requestedChapterId: String
    private let requestedInitialPage: Int?
    private let requestRevision: Int
    private let usesWindowToolbar: Bool
    private let onNavigate: (Route) -> Void
    private let goBack: () -> Void

    init(
        accountType: AccountType,
        mangaKey: MicroBlogKey,
        chapterId: String,
        initialPage: Int?,
        requestRevision: Int = 0,
        usesWindowToolbar: Bool = false,
        onNavigate: @escaping (Route) -> Void,
        goBack: @escaping () -> Void
    ) {
        self.accountType = accountType
        self.mangaKey = mangaKey
        self.requestedChapterId = chapterId
        self.requestedInitialPage = initialPage
        self.requestRevision = requestRevision
        self.usesWindowToolbar = usesWindowToolbar
        self.onNavigate = onNavigate
        self.goBack = goBack
        self._lastAppliedRevision = State(initialValue: requestRevision)
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

    private var windowNavigationTitle: String {
        let manga = presenter.state.mangaTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        let chapter = presenter.state.chapterTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        if !manga.isEmpty && !chapter.isEmpty {
            return "\(manga) — \(chapter)"
        }
        if !chapter.isEmpty {
            return chapter
        }
        return manga
    }

    var body: some View {
        MangaReaderContentView(
            state: presenter.state,
            hudVisible: $hudVisible,
            goBack: goBack,
            usesWindowToolbar: usesWindowToolbar,
            onSavePage: { original, displayed, pageIndex in
                savePage(original: original, displayed: displayed, pageIndex: pageIndex)
            },
            onSharePage: { _, displayed, pageIndex in
                sharePage(displayed: displayed, pageIndex: pageIndex)
            },
            onCopyPage: { displayed in
                copyPage(displayed: displayed)
            },
            onOpenExternalURL: { rawURL in
                onNavigate(.externalLink(rawURL))
            },
            shareAnchor: shareAnchor
        )
        .navigationBarBackButtonHidden(true)
        .navigationTitle(usesWindowToolbar ? windowNavigationTitle : "")
        .preferredColorScheme(.dark)
        .onChange(of: requestRevision) { _, newRevision in
            guard newRevision != lastAppliedRevision else { return }
            lastAppliedRevision = newRevision
            presenter.state.openChapter(
                chapterId: requestedChapterId,
                initialPage: requestedInitialPage.map { KotlinInt(int: Int32($0)) }
            )
        }
        .alert(item: $exportAlert) { alert in
            Alert(
                title: Text(verbatim: alert.title),
                message: Text(verbatim: alert.message),
                dismissButton: .default(Text(verbatim: FlareAppleUILocalization.string("Ok", fallback: "OK")))
            )
        }
    }

    private func copyPage(displayed: any UiMedia) {
        guard displayed is UiMediaImage || displayed is UiMediaGif else { return }
        if !displayed.isRemote || URL(string: displayed.url)?.isFileURL == true {
            guard let localURL = URL(string: displayed.url),
                  localURL.isFileURL,
                  ImageTranslationExportFile.isOwned(localURL),
                  let image = NSImage(contentsOf: localURL) else {
                exportAlert = MacMangaExportAlert(
                    title: FlareAppleUILocalization.string("manga_reader_copy_image", fallback: "Copy image"),
                    message: URLError(.unsupportedURL).localizedDescription
                )
                return
            }
            NSPasteboard.general.clearContents()
            NSPasteboard.general.writeObjects([image])
            return
        }
        guard !isCopyingPage else { return }
        isCopyingPage = true
        Task { @MainActor in
            defer { isCopyingPage = false }
            do {
                let result = try await ProxyMediaImages.shared.retrieve(displayed)
                guard !Task.isCancelled else { return }
                NSPasteboard.general.clearContents()
                NSPasteboard.general.writeObjects([result.image])
            } catch {
                guard !Task.isCancelled else { return }
                exportAlert = MacMangaExportAlert(
                    title: FlareAppleUILocalization.string("manga_reader_copy_image", fallback: "Copy image"),
                    message: error.localizedDescription
                )
            }
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
    let usesWindowToolbar: Bool
    let controller: MangaReaderInteractionController?
    let onSavePage: ((any UiMedia, any UiMedia, Int) -> Void)?
    let onSharePage: ((any UiMedia, any UiMedia, Int) -> Void)?
    let onCopyPage: ((any UiMedia) -> Void)?
    let onOpenExternalURL: ((String) -> Void)?
    let shareAnchor: MacMangaShareAnchorBox?

    @State private var showSettings = false
    @State private var showChapters = false
    @State private var isScrubberIdleHidden = false
    @State private var isHoveringScrubber = false
    @State private var lastHoverLocation: CGPoint?
    @State private var pointerIdleTask: Task<Void, Never>?

    init(
        state: any MangaReaderPresenterState,
        hudVisible: Binding<Bool>,
        goBack: @escaping () -> Void,
        usesWindowToolbar: Bool = false,
        controller: MangaReaderInteractionController? = nil,
        onSavePage: ((any UiMedia, any UiMedia, Int) -> Void)? = nil,
        onSharePage: ((any UiMedia, any UiMedia, Int) -> Void)? = nil,
        onCopyPage: ((any UiMedia) -> Void)? = nil,
        onOpenExternalURL: ((String) -> Void)? = nil,
        shareAnchor: MacMangaShareAnchorBox? = nil
    ) {
        self.state = state
        self._hudVisible = hudVisible
        self.goBack = goBack
        self.usesWindowToolbar = usesWindowToolbar
        self.controller = controller
        self.onSavePage = onSavePage
        self.onSharePage = onSharePage
        self.onCopyPage = onCopyPage
        self.onOpenExternalURL = onOpenExternalURL
        self.shareAnchor = shareAnchor
    }

    private var currentReadyPage: PageMediaStatusReady? {
        let idx = Int(state.currentPageIndex)
        guard state.pages.indices.contains(idx) else { return nil }
        return state.pages[idx].status as? PageMediaStatusReady
    }

    static func resolveActiveTranslationErrorMedia(state: any MangaReaderPresenterState) -> (any UiMedia)? {
        for page in state.pages {
            guard let ready = page.status as? PageMediaStatusReady else { continue }
            let item = state.imageTranslation.item(media: ready.original)
            if item.error != nil || item.errorDetailsVisible {
                return ready.original
            }
        }
        let idx = Int(state.currentPageIndex)
        guard state.pages.indices.contains(idx),
              let ready = state.pages[idx].status as? PageMediaStatusReady else {
            return nil
        }
        return ready.original
    }

    private var activeTranslationErrorMedia: (any UiMedia)? {
        Self.resolveActiveTranslationErrorMedia(state: state)
    }

    private var isBottomScrubberVisible: Bool {
        guard !state.pages.isEmpty else { return false }
        if usesWindowToolbar {
            return hudVisible && !isScrubberIdleHidden
        }
        return hudVisible
    }

    var body: some View {
        GeometryReader { proxy in
            let isWideViewport = proxy.size.width >= 720

            ZStack {
                Color.black
                    .ignoresSafeArea()

                MangaReaderCanvas(
                    state: state,
                    hudVisible: hudVisible,
                    onToggleHud: {
                        let didShowHud: Bool
                        if hudVisible && !isScrubberIdleHidden {
                            didShowHud = false
                            withAnimation(.easeInOut(duration: 0.18)) {
                                hudVisible = false
                            }
                        } else {
                            didShowHud = true
                            withAnimation(.easeInOut(duration: 0.18)) {
                                isScrubberIdleHidden = false
                                hudVisible = true
                            }
                        }
                        if didShowHud {
                            scheduleScrubberIdleHideIfNeeded()
                        } else {
                            pointerIdleTask?.cancel()
                        }
                    },
                    onBack: goBack,
                    onOpenExternalURL: onOpenExternalURL,
                    isOverlayPresented: showChapters || showSettings,
                    onDismissOverlay: {
                        guard showChapters || showSettings else { return false }
                        showChapters = false
                        showSettings = false
                        return true
                    },
                    controller: controller,
                    pageContextMenu: { page, ready in
                        AnyView(pageContextMenuContent(page: page, ready: ready))
                    }
                )
                .ignoresSafeArea(usesWindowToolbar ? .all : [])

                VStack(spacing: 0) {
                    if !usesWindowToolbar && hudVisible {
                        topHudBar(isWideViewport: isWideViewport)
                            .transition(.move(edge: .top).combined(with: .opacity))
                    }

                    Spacer(minLength: 0)

                    if isBottomScrubberVisible {
                        bottomHudBar
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                            .onContinuousHover { phase in
                                switch phase {
                                case .active:
                                    isHoveringScrubber = true
                                    pointerIdleTask?.cancel()
                                case .ended:
                                    isHoveringScrubber = false
                                    scheduleScrubberIdleHideIfNeeded()
                                }
                            }
                    }
                }

                if !isBottomScrubberVisible && !state.pages.isEmpty {
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
            .onContinuousHover { phase in
                guard usesWindowToolbar else { return }
                switch phase {
                case .active(let location):
                    if let previous = lastHoverLocation,
                       hypot(location.x - previous.x, location.y - previous.y) > 0.5 {
                        if isScrubberIdleHidden || !hudVisible {
                            withAnimation(.easeInOut(duration: 0.18)) {
                                isScrubberIdleHidden = false
                                hudVisible = true
                            }
                        }
                    }
                    lastHoverLocation = location
                    scheduleScrubberIdleHideIfNeeded()
                case .ended:
                    lastHoverLocation = nil
                    scheduleScrubberIdleHideIfNeeded()
                }
            }
            .onChange(of: showChapters) { _, isShowing in
                if isShowing {
                    pointerIdleTask?.cancel()
                } else {
                    scheduleScrubberIdleHideIfNeeded()
                }
            }
            .onChange(of: showSettings) { _, isShowing in
                if isShowing {
                    pointerIdleTask?.cancel()
                } else {
                    scheduleScrubberIdleHideIfNeeded()
                }
            }
            .modifier(
                MacReaderWindowToolbarModifier(
                    isEnabled: usesWindowToolbar,
                    state: state,
                    isWideViewport: isWideViewport,
                    currentReadyPage: currentReadyPage,
                    showChapters: $showChapters,
                    showSettings: $showSettings,
                    shareAnchor: shareAnchor,
                    onSavePage: onSavePage,
                    onSharePage: onSharePage,
                    onOpenChapterURL: openChapterURL,
                    onOpenExternalURL: onOpenExternalURL
                )
            )
        }
        .onAppear {
            scheduleScrubberIdleHideIfNeeded()
        }
        .onDisappear {
            pointerIdleTask?.cancel()
            pointerIdleTask = nil
        }
        .imageTranslationErrorAlert(
            state: state.imageTranslation,
            media: activeTranslationErrorMedia
        )
    }

    private func scheduleScrubberIdleHideIfNeeded() {
        guard usesWindowToolbar else { return }
        pointerIdleTask?.cancel()
        guard !isHoveringScrubber, !showSettings, !showChapters else { return }
        pointerIdleTask = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(2500))
            guard !Task.isCancelled, !isHoveringScrubber, !showSettings, !showChapters else { return }
            withAnimation(.easeInOut(duration: 0.22)) {
                isScrubberIdleHidden = true
            }
        }
    }

    @ViewBuilder
    private func pageContextMenuContent(page: ReaderPageState, ready: PageMediaStatusReady) -> some View {
        let pageIdx = Int(page.index)
        if ready.displayed is UiMediaImage || ready.displayed is UiMediaGif {
            Button {
                if let onCopyPage {
                    onCopyPage(ready.displayed)
                } else {
                    copyImageFallback(ready.displayed)
                }
            } label: {
                Label(
                    FlareAppleUILocalization.string("manga_reader_copy_image", fallback: "Copy image"),
                    systemImage: "doc.on.doc"
                )
            }
        }

        if let onSavePage {
            Button {
                onSavePage(ready.original, ready.displayed, pageIdx)
            } label: {
                Label(
                    FlareAppleUILocalization.string("media_menu_download", fallback: "Save"),
                    systemImage: "square.and.arrow.down"
                )
            }
        }

        if let onSharePage, ready.displayed is UiMediaImage || ready.displayed is UiMediaGif {
            Button {
                onSharePage(ready.original, ready.displayed, pageIdx)
            } label: {
                Label(
                    FlareAppleUILocalization.string("media_menu_share_image", fallback: "Share image"),
                    systemImage: "square.and.arrow.up"
                )
            }
        }

        let item = state.imageTranslation.item(media: ready.original)
        if state.imageTranslation.canToggle(media: ready.original) {
            let toggleLabel = item.showingTranslated
                ? FlareAppleUILocalization.string("Show original", fallback: "Show original")
                : (item.translated != nil
                    ? FlareAppleUILocalization.string("Show translation", fallback: "Show translation")
                    : FlareAppleUILocalization.string("Translate image", fallback: "Translate image"))
            Button {
                state.imageTranslation.toggle(media: ready.original)
            } label: {
                Label(toggleLabel, systemImage: "translate")
            }
            .disabled(item.loading)
        }

        if state.imageTranslation.canRegenerate(media: ready.original) {
            Button {
                state.imageTranslation.regenerate(media: ready.original)
            } label: {
                Label(
                    FlareAppleUILocalization.string("Translate again", fallback: "Translate again"),
                    systemImage: "arrow.clockwise"
                )
            }
            .disabled(item.loading)
        }

        if let chapterUrl = state.chapterUrl?.trimmingCharacters(in: .whitespacesAndNewlines),
           !chapterUrl.isEmpty {
            Divider()
            Button {
                openChapterURL(chapterUrl)
            } label: {
                Label(
                    FlareAppleUILocalization.string("manga_open_in_browser", fallback: "Open in browser"),
                    systemImage: "safari"
                )
            }
        }
    }

    private func copyImageFallback(_ displayed: any UiMedia) {
        if !displayed.isRemote || URL(string: displayed.url)?.isFileURL == true {
            guard let localURL = URL(string: displayed.url),
                  localURL.isFileURL,
                  ImageTranslationExportFile.isOwned(localURL),
                  let image = NSImage(contentsOf: localURL) else {
                return
            }
            NSPasteboard.general.clearContents()
            NSPasteboard.general.writeObjects([image])
            return
        }
        Task { @MainActor in
            if let result = try? await ProxyMediaImages.shared.retrieve(displayed) {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.writeObjects([result.image])
            }
        }
    }

    private func topHudBar(isWideViewport: Bool) -> some View {
        let hasChapterDirectory = !state.chapterItems.isEmpty
        return HStack(spacing: 8) {
            Button(action: goBack) {
                Image(systemName: "chevron.backward")
                    .font(.body.weight(.semibold))
                    .frame(width: 32, height: 32)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(.white)
            .accessibilityIdentifier("manga_reader_back")

            Button {
                if hasChapterDirectory {
                    showChapters.toggle()
                }
            } label: {
                HStack(spacing: 4) {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 4) {
                            Text(verbatim: state.chapterTitle)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(.white)
                                .lineLimit(1)
                            if hasChapterDirectory {
                                Image(systemName: "chevron.down")
                                    .font(.caption2.weight(.semibold))
                                    .foregroundStyle(.white.opacity(0.75))
                            }
                        }
                        if !state.mangaTitle.isEmpty {
                            Text(verbatim: state.mangaTitle)
                                .font(.caption2)
                                .foregroundStyle(.white.opacity(0.72))
                                .lineLimit(1)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!hasChapterDirectory)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier("manga_reader_chapters_button")
            .popover(isPresented: $showChapters, arrowEdge: .bottom) {
                MangaReaderChapterListView(
                    state: state,
                    onDismiss: { showChapters = false },
                    onOpenExternalURL: onOpenExternalURL
                )
                .frame(width: 360, height: 460)
                .preferredColorScheme(.dark)
            }

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

@MainActor
private struct MacReaderWindowToolbarModifier: ViewModifier {
    let isEnabled: Bool
    let state: any MangaReaderPresenterState
    let isWideViewport: Bool
    let currentReadyPage: PageMediaStatusReady?
    @Binding var showChapters: Bool
    @Binding var showSettings: Bool
    let shareAnchor: MacMangaShareAnchorBox?
    let onSavePage: ((any UiMedia, any UiMedia, Int) -> Void)?
    let onSharePage: ((any UiMedia, any UiMedia, Int) -> Void)?
    let onOpenChapterURL: (String) -> Void
    let onOpenExternalURL: ((String) -> Void)?

    @ViewBuilder
    func body(content: Content) -> some View {
        if isEnabled {
            let prevUnlocked = state.previousBoundary as? ChapterBoundaryUnlocked
            let nextUnlocked = state.nextBoundary as? ChapterBoundaryUnlocked
            let hasChapterDirectory = !state.chapterItems.isEmpty
            let chaptersHelp = FlareAppleUILocalization.string("manga_reader_chapters", fallback: "Chapters")
            let prevHelp = FlareAppleUILocalization.string("manga_reader_previous_chapter", fallback: "Previous chapter")
            let nextHelp = FlareAppleUILocalization.string("manga_reader_next_chapter", fallback: "Next chapter")
            let saveHelp = FlareAppleUILocalization.string("media_menu_download", fallback: "Save")
            let shareHelp = FlareAppleUILocalization.string("media_menu_share_image", fallback: "Share image")
            let browserHelp = FlareAppleUILocalization.string("manga_open_in_browser", fallback: "Open in browser")
            let settingsHelp = FlareAppleUILocalization.string("manga_reader_settings", fallback: "Reader settings")

            content.toolbar {
                ToolbarItemGroup(placement: .navigation) {
                    Button {
                        if let prevUnlocked {
                            state.openAdjacentChapter(boundary: prevUnlocked)
                        }
                    } label: {
                        Image(systemName: "backward.end.fill")
                    }
                    .disabled(prevUnlocked == nil)
                    .accessibilityLabel(Text(verbatim: prevHelp))
                    .help(Text(verbatim: prevHelp))

                    Button {
                        if let nextUnlocked {
                            state.openAdjacentChapter(boundary: nextUnlocked)
                        }
                    } label: {
                        Image(systemName: "forward.end.fill")
                    }
                    .disabled(nextUnlocked == nil)
                    .accessibilityLabel(Text(verbatim: nextHelp))
                    .help(Text(verbatim: nextHelp))
                }

                ToolbarItem(placement: .principal) {
                    Button {
                        if hasChapterDirectory {
                            showChapters.toggle()
                        }
                    } label: {
                        HStack(spacing: 5) {
                            VStack(alignment: .center, spacing: 1) {
                                Text(verbatim: state.chapterTitle)
                                    .font(.subheadline.weight(.semibold))
                                    .lineLimit(1)
                                if !state.mangaTitle.isEmpty {
                                    Text(verbatim: state.mangaTitle)
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(1)
                                }
                            }
                            if hasChapterDirectory {
                                Image(systemName: "chevron.down")
                                    .font(.caption2.weight(.semibold))
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .padding(.horizontal, 8)
                    }
                    .buttonStyle(.plain)
                    .disabled(!hasChapterDirectory)
                    .accessibilityIdentifier("manga_reader_chapters_button")
                    .accessibilityLabel(Text(verbatim: chaptersHelp))
                    .help(Text(verbatim: chaptersHelp))
                    .popover(isPresented: $showChapters, arrowEdge: .bottom) {
                        MangaReaderChapterListView(
                            state: state,
                            onDismiss: { showChapters = false },
                            onOpenExternalURL: onOpenExternalURL
                        )
                        .frame(width: 360, height: 460)
                        .preferredColorScheme(.dark)
                    }
                }

                ToolbarItemGroup(placement: .primaryAction) {
                    if let ready = currentReadyPage {
                        let item = state.imageTranslation.item(media: ready.original)
                        if state.imageTranslation.canToggle(media: ready.original) {
                            let toggleHelp = item.showingTranslated
                                ? FlareAppleUILocalization.string("Show original", fallback: "Show original")
                                : (item.translated != nil
                                    ? FlareAppleUILocalization.string("Show translation", fallback: "Show translation")
                                    : FlareAppleUILocalization.string("Translate image", fallback: "Translate image"))
                            Button {
                                state.imageTranslation.toggle(media: ready.original)
                            } label: {
                                Image(systemName: "translate")
                            }
                            .disabled(item.loading)
                            .accessibilityLabel(Text(verbatim: toggleHelp))
                            .help(Text(verbatim: toggleHelp))
                        }

                        if state.imageTranslation.canRegenerate(media: ready.original) {
                            let regenerateHelp = FlareAppleUILocalization.string("Translate again", fallback: "Translate again")
                            Button {
                                state.imageTranslation.regenerate(media: ready.original)
                            } label: {
                                Image(systemName: "arrow.clockwise")
                            }
                            .disabled(item.loading)
                            .accessibilityLabel(Text(verbatim: regenerateHelp))
                            .help(Text(verbatim: regenerateHelp))
                        }

                        if let onSavePage {
                            Button {
                                onSavePage(ready.original, ready.displayed, Int(state.currentPageIndex))
                            } label: {
                                Image(systemName: "square.and.arrow.down")
                            }
                            .accessibilityLabel(Text(verbatim: saveHelp))
                            .help(Text(verbatim: saveHelp))
                        }

                        if let onSharePage, ready.displayed is UiMediaImage || ready.displayed is UiMediaGif {
                            Button {
                                onSharePage(ready.original, ready.displayed, Int(state.currentPageIndex))
                            } label: {
                                Image(systemName: "square.and.arrow.up")
                            }
                            .background {
                                if let shareAnchor {
                                    MacMangaShareAnchorView(anchor: shareAnchor)
                                }
                            }
                            .accessibilityLabel(Text(verbatim: shareHelp))
                            .help(Text(verbatim: shareHelp))
                        }
                    }

                    if let chapterUrl = state.chapterUrl?.trimmingCharacters(in: .whitespacesAndNewlines),
                       !chapterUrl.isEmpty {
                        Button {
                            onOpenChapterURL(chapterUrl)
                        } label: {
                            Image(systemName: "safari")
                        }
                        .accessibilityLabel(Text(verbatim: browserHelp))
                        .help(Text(verbatim: browserHelp))
                    }

                    Button {
                        showSettings.toggle()
                    } label: {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityIdentifier("manga_reader_settings_button")
                    .accessibilityLabel(Text(verbatim: settingsHelp))
                    .help(Text(verbatim: settingsHelp))
                    .popover(isPresented: $showSettings, arrowEdge: .bottom) {
                        MangaReaderSettingsView(state: state, isWideViewport: isWideViewport)
                            .preferredColorScheme(.dark)
                    }
                }
            }
        } else {
            content
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
