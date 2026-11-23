import FlareAppleCore
import FlareAppleUI
import Foundation
@preconcurrency import KotlinSharedUI
import Observation
import SwiftUI
import SwiftUIBackports
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
        .persistentSystemOverlays(hudVisible ? .automatic : .hidden)
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
    @State private var showChapters = false

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

    struct VisiblePageTarget {
        let pageIndex: Int
        let ready: PageMediaStatusReady
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

    static func resolveVisibleReadyPageTargets(state: any MangaReaderPresenterState) -> [VisiblePageTarget] {
        if state.direction != .webtoon {
            let slotIdx = Int(state.currentSlotIndex)
            if state.slots.indices.contains(slotIdx),
               let doubleSlot = state.slots[slotIdx] as? ReaderSlotDouble {
                let orderedIndices = [Int(doubleSlot.leftPageIndex), Int(doubleSlot.rightPageIndex)]
                return orderedIndices.compactMap { index in
                    guard state.pages.indices.contains(index),
                          let ready = state.pages[index].status as? PageMediaStatusReady else {
                        return nil
                    }
                    return VisiblePageTarget(pageIndex: index, ready: ready)
                }
            }
        }
        let idx = Int(state.currentPageIndex)
        guard state.pages.indices.contains(idx),
              let ready = state.pages[idx].status as? PageMediaStatusReady else {
            return []
        }
        return [VisiblePageTarget(pageIndex: idx, ready: ready)]
    }

    private var visibleReadyPageTargets: [VisiblePageTarget] {
        Self.resolveVisibleReadyPageTargets(state: state)
    }

    private var canShareAnyVisiblePage: Bool {
        guard onSharePage != nil else { return false }
        return visibleReadyPageTargets.contains { target in
            target.ready.displayed is UiMediaImage || target.ready.displayed is UiMediaGif
        }
    }

    private var hasMoreMenuActions: Bool {
        let canSaveAny = onSavePage != nil && visibleReadyPageTargets.contains { !($0.ready.original is UiMediaUgoira) }
        let canRegenerate = currentReadyPage.map { state.imageTranslation.canRegenerate(media: $0.original) } ?? false
        let hasChapterURL = state.chapterUrl?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
        return canSaveAny || canShareAnyVisiblePage || canRegenerate || hasChapterURL
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
                        withAnimation(.smooth(duration: 0.2)) {
                            hudVisible.toggle()
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
                        .backport
                        .glassEffect(.regular, in: .capsule, fallbackBackground: .regularMaterial)
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
            .sheet(isPresented: Binding(
                get: { showChapters && horizontalSizeClass != .regular },
                set: { if !$0 { showChapters = false } }
            )) {
                MangaReaderChapterListView(
                    state: state,
                    onDismiss: { showChapters = false },
                    onOpenExternalURL: onOpenExternalURL
                )
                .presentationDetents([.medium, .large])
                .preferredColorScheme(.dark)
            }
        }
        .persistentSystemOverlays(hudVisible ? .automatic : .hidden)
        .imageTranslationErrorAlert(
            state: state.imageTranslation,
            media: activeTranslationErrorMedia
        )
    }

    private func topHudBar(isWideViewport: Bool) -> some View {
        let hasChapterDirectory = !state.chapterItems.isEmpty
        return HStack(spacing: 8) {
            Button(action: goBack) {
                Image(systemName: "chevron.backward")
                    .font(.body.weight(.semibold))
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityIdentifier("manga_reader_back")
            .backport
            .glassEffect(.regularInteractive, in: .capsule, fallbackBackground: .regularMaterial)

            Button {
                if hasChapterDirectory {
                    showChapters = true
                }
            } label: {
                HStack(spacing: 6) {
                    VStack(alignment: .leading, spacing: 1) {
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
                .padding(.horizontal, 14)
                .padding(.vertical, 6)
                .frame(minHeight: 44)
                .contentShape(Capsule())
            }
            .disabled(!hasChapterDirectory)
            .frame(maxWidth: .infinity, alignment: .leading)
            .backport
            .glassEffect(.regularInteractive, in: .capsule, fallbackBackground: .regularMaterial)
            .accessibilityIdentifier("manga_reader_chapters_button")
            .accessibilityLabel(
                Text(
                    verbatim: FlareAppleUILocalization.string(
                        "manga_reader_chapters",
                        fallback: "Chapters"
                    )
                )
            )
            .popover(isPresented: Binding(
                get: { showChapters && horizontalSizeClass == .regular },
                set: { if !$0 { showChapters = false } }
            )) {
                MangaReaderChapterListView(
                    state: state,
                    onDismiss: { showChapters = false },
                    onOpenExternalURL: onOpenExternalURL
                )
                .frame(minWidth: 340, idealWidth: 380, minHeight: 420, maxHeight: 560)
                .preferredColorScheme(.dark)
            }

            HStack(spacing: 0) {
                if let ready = currentReadyPage {
                    let item = state.imageTranslation.item(media: ready.original)
                    if state.imageTranslation.canToggle(media: ready.original) {
                        Button {
                            state.imageTranslation.toggle(media: ready.original)
                        } label: {
                            Image(systemName: "translate")
                                .font(.body)
                                .imageScale(.large)
                                .frame(width: 44, height: 44)
                                .contentShape(Rectangle())
                        }
                        .disabled(item.loading)
                    }
                }

                Button {
                    showSettings = true
                } label: {
                    Image(systemName: "gearshape")
                        .font(.body)
                        .imageScale(.large)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
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

                if hasMoreMenuActions {
                    Menu {
                        moreMenuContent
                    } label: {
                        Image(systemName: "ellipsis.circle")
                            .font(.body)
                            .imageScale(.large)
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }
                    .accessibilityIdentifier("manga_reader_more_menu")
                    .accessibilityLabel(
                        Text(verbatim: FlareAppleUILocalization.string("more", fallback: "More"))
                    )
                }
            }
            .padding(.horizontal, 4)
            .backport
            .glassEffect(.regularInteractive, in: .capsule, fallbackBackground: .regularMaterial)
        }
        .buttonStyle(.plain)
        .foregroundStyle(.white)
        .safeAreaPadding([.top, .horizontal])
        .preference(key: IOSMangaReaderShareButtonVisiblePreferenceKey.self, value: canShareAnyVisiblePage)
    }

    @ViewBuilder
    private var moreMenuContent: some View {
        let targets = visibleReadyPageTargets
        let isSpread = targets.count > 1
        let saveBase = FlareAppleUILocalization.string("media_menu_download", fallback: "Save")
        let shareBase = FlareAppleUILocalization.string("media_menu_share_image", fallback: "Share image")

        if let onSavePage {
            ForEach(targets, id: \.pageIndex) { target in
                if !(target.ready.original is UiMediaUgoira) {
                    let title = isSpread ? "\(saveBase) (P\(target.pageIndex + 1))" : saveBase
                    Button {
                        onSavePage(target.ready.original, target.ready.displayed, target.pageIndex)
                    } label: {
                        Label(title, systemImage: "square.and.arrow.down")
                    }
                }
            }
        }

        if let onSharePage {
            ForEach(targets, id: \.pageIndex) { target in
                if target.ready.displayed is UiMediaImage || target.ready.displayed is UiMediaGif {
                    let title = isSpread ? "\(shareBase) (P\(target.pageIndex + 1))" : shareBase
                    Button {
                        onSharePage(target.ready.original, target.ready.displayed, target.pageIndex)
                    } label: {
                        Label(title, systemImage: "square.and.arrow.up")
                    }
                }
            }
        }

        if let ready = currentReadyPage {
            let item = state.imageTranslation.item(media: ready.original)
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
        }

        if let chapterUrl = state.chapterUrl?.trimmingCharacters(in: .whitespacesAndNewlines),
           !chapterUrl.isEmpty {
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

    @ViewBuilder
    private func pageContextMenuContent(page: ReaderPageState, ready: PageMediaStatusReady) -> some View {
        let pageIdx = Int(page.index)
        if let onSavePage, !(ready.original is UiMediaUgoira) {
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
    }

    @ViewBuilder
    private var bottomHudBar: some View {
        if #available(iOS 26.0, *) {
            bottomHudBarContent
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .frame(maxWidth: 640)
                .backport
                .glassEffect(
                    .tinted(.init(.systemGroupedBackground).opacity(0.5)),
                    in: .rect(corners: .concentric, isUniform: true),
                    fallbackBackground: .regularMaterial
                )
                .padding()
        } else {
            bottomHudBarContent
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .frame(maxWidth: 640)
                .backport
                .glassEffect(
                    .regular,
                    in: .rect(cornerRadius: 24, style: .continuous),
                    fallbackBackground: .regularMaterial
                )
                .padding(.horizontal, 16)
                .padding(.bottom, 12)
        }
    }

    private var bottomHudBarContent: some View {
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
