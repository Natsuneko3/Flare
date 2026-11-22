import FlareAppleCore
import FlareAppleUI
import Foundation
@preconcurrency import KotlinSharedUI
import SwiftUI

struct MangaDetailScreen: View {
    @State private var presenter: KotlinPresenter<MangaDetailPresenterState>

    private let accountType: AccountType
    private let mangaKey: MicroBlogKey
    private let onNavigate: (Route) -> Void

    init(
        accountType: AccountType,
        mangaKey: MicroBlogKey,
        onNavigate: @escaping (Route) -> Void
    ) {
        self.accountType = accountType
        self.mangaKey = mangaKey
        self.onNavigate = onNavigate
        self._presenter = State(
            initialValue: KotlinPresenter(
                presenter: MangaDetailPresenter(accountType: accountType, mangaKey: mangaKey)
            )
        )
    }

    var body: some View {
        MangaDetailContentView(
            state: presenter.state,
            onNavigate: onNavigate,
            onRetry: {
                presenter.state.refresh()
            }
        )
    }
}

struct MangaCommentsScreen: View {
    let accountType: AccountType
    let mangaKey: MicroBlogKey
    @Environment(\.timelineAppearance) private var timelineAppearance
    @State private var presenter: KotlinPresenter<MangaDetailPresenterState>

    init(accountType: AccountType, mangaKey: MicroBlogKey) {
        self.accountType = accountType
        self.mangaKey = mangaKey
        self._presenter = State(
            initialValue: KotlinPresenter(
                presenter: MangaDetailPresenter(accountType: accountType, mangaKey: mangaKey)
            )
        )
    }

    var body: some View {
        UITimelinePagingView(
            data: presenter.state.comments,
            detailStatusKey: nil,
            key: "manga_comments_\(mangaKey.description())",
            suppressInitialRefreshIndicator: true
        )
        .environment(\.timelineAppearance, timelineAppearance.mangaCardTimeline())
        .flareTextBackground(.flareSystemGroupedBackground)
        .background(Color(.systemGroupedBackground))
        .navigationTitle(
            FlareAppleUILocalization.string("manga_detail_tab_comments", fallback: "Comments")
        )
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct MangaDetailContentView: View {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.timelineAppearance) private var timelineAppearance
    @Environment(\.openURL) private var openURL

    let detail: UiState<UiMangaDetail>
    let translation: (any MangaTranslatePresenterState)?
    let selectedGroupId: String?
    let chapterSortDescending: Bool
    let chapters: [UiMangaChapterItem]
    let primaryReadTarget: PrimaryReadTarget?
    let supportsComments: Bool
    let supportsRecommendations: Bool
    let comments: PagingState<UiTimelineV2>?
    let recommendations: PagingState<UiTimelineV2>?
    let isRefreshing: Bool
    let onRefresh: () -> Void
    let onSelectGroup: (String) -> Void
    let onToggleSort: () -> Void
    let onSetChapterRead: (String, Bool) -> Void
    let onNavigate: (Route) -> Void
    let onOpenExternalURL: ((String) -> Void)?
    let onRetry: () -> Void

    @State private var selectedTab: IOSMangaDetailTab = .chapters
    @State private var lockedPromptChapter: UiMangaChapter?
    @State private var isHeroVisible = true
    @State private var compactHeroMetrics = CompactHeroMetrics()
    @State private var compactSafeTopMinY: CGFloat = 0

    init(
        state: any MangaDetailPresenterState,
        onNavigate: @escaping (Route) -> Void,
        onOpenExternalURL: ((String) -> Void)? = nil,
        onRetry: (() -> Void)? = nil
    ) {
        self.detail = state.detail
        self.translation = state.translation
        self.selectedGroupId = state.selectedGroupId
        self.chapterSortDescending = state.chapterSortDescending
        self.chapters = state.chapters
        self.primaryReadTarget = state.primaryReadTarget
        self.supportsComments = state.supportsComments
        self.supportsRecommendations = state.supportsRecommendations
        self.comments = state.comments
        self.recommendations = state.recommendations
        self.isRefreshing = state.isRefreshing
        self.onRefresh = { state.refresh() }
        self.onSelectGroup = { state.selectGroup(groupId: $0) }
        self.onToggleSort = { state.toggleChapterSort() }
        self.onSetChapterRead = { state.setChapterRead(chapterId: $0, isRead: $1) }
        self.onNavigate = onNavigate
        self.onOpenExternalURL = onOpenExternalURL
        self.onRetry = onRetry ?? { state.refresh() }
    }

    init(
        detail: UiState<UiMangaDetail>,
        translation: (any MangaTranslatePresenterState)? = nil,
        selectedGroupId: String? = nil,
        chapterSortDescending: Bool = false,
        chapters: [UiMangaChapterItem] = [],
        primaryReadTarget: PrimaryReadTarget? = nil,
        supportsComments: Bool = false,
        supportsRecommendations: Bool = false,
        comments: PagingState<UiTimelineV2>? = nil,
        recommendations: PagingState<UiTimelineV2>? = nil,
        isRefreshing: Bool = false,
        onRefresh: @escaping () -> Void = {},
        onSelectGroup: @escaping (String) -> Void = { _ in },
        onToggleSort: @escaping () -> Void = {},
        onSetChapterRead: @escaping (String, Bool) -> Void = { _, _ in },
        onNavigate: @escaping (Route) -> Void = { _ in },
        onOpenExternalURL: ((String) -> Void)? = nil,
        onRetry: @escaping () -> Void = {}
    ) {
        self.detail = detail
        self.translation = translation
        self.selectedGroupId = selectedGroupId
        self.chapterSortDescending = chapterSortDescending
        self.chapters = chapters
        self.primaryReadTarget = primaryReadTarget
        self.supportsComments = supportsComments
        self.supportsRecommendations = supportsRecommendations
        self.comments = comments
        self.recommendations = recommendations
        self.isRefreshing = isRefreshing
        self.onRefresh = onRefresh
        self.onSelectGroup = onSelectGroup
        self.onToggleSort = onToggleSort
        self.onSetChapterRead = onSetChapterRead
        self.onNavigate = onNavigate
        self.onOpenExternalURL = onOpenExternalURL
        self.onRetry = onRetry
    }

    private var isRegularWidth: Bool {
        horizontalSizeClass == .regular
    }

    private var loadedDetail: UiMangaDetail? {
        if case .success(let success) = onEnum(of: detail) {
            return success.data
        }
        return nil
    }

    private var shareURL: String? {
        guard let raw = loadedDetail?.url?.trimmingCharacters(in: .whitespacesAndNewlines),
              !raw.isEmpty else {
            return nil
        }
        return raw
    }

    private var availableTabs: [IOSMangaDetailTab] {
        var tabs: [IOSMangaDetailTab] = [.chapters]
        if supportsComments {
            tabs.append(.comments)
        }
        if supportsRecommendations {
            tabs.append(.recommendations)
        }
        return tabs
    }

    private var activeTab: IOSMangaDetailTab {
        availableTabs.contains(selectedTab) ? selectedTab : .chapters
    }

    private func hasSynopsisOrTags(_ detail: UiMangaDetail) -> Bool {
        detail.content?.innerText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false ||
            detail.tagGroups.contains(where: { !$0.tags.isEmpty })
    }

    var body: some View {
        StateView(state: detail) { loaded in
            if isRegularWidth {
                regularSplitBody(detail: loaded)
            } else {
                compactBody(detail: loaded)
            }
        } errorContent: { error in
            ListErrorView(error: error, onRetry: onRetry)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } loadingContent: {
            IOSMangaDetailLoadingView()
        }
        .environment(\.timelineAppearance, timelineAppearance.mangaCardTimeline())
        .flareTextBackground(.flareSystemGroupedBackground)
        .background(Color(.systemGroupedBackground))
        .toolbarBackground(
            !isRegularWidth && isHeroVisible && loadedDetail != nil ? Visibility.hidden : Visibility.automatic,
            for: .navigationBar
        )
        .navigationTitle(isRegularWidth ? (loadedDetail?.title ?? "") : "")
        .navigationBarTitleDisplayMode(.inline)
        .preference(key: IOSMangaCompactHeroVisiblePreferenceKey.self, value: isHeroVisible)
        .onAppear {
            if !availableTabs.contains(selectedTab) {
                selectedTab = .chapters
            }
        }
        .onChange(of: availableTabs) { _, newTabs in
            if !newTabs.contains(selectedTab) {
                selectedTab = .chapters
            }
        }
        .onChange(of: loadedDetail == nil) { _, isNil in
            if isNil {
                isHeroVisible = true
                compactHeroMetrics = CompactHeroMetrics()
            }
        }
        .onChange(of: isRegularWidth) { _, _ in
            isHeroVisible = true
        }
        .toolbar {
            if !isRegularWidth, let title = loadedDetail?.title, !title.isEmpty {
                if #available(iOS 27.1, *) {
                    MangaCompactNavigationTitle(title: title, isVisible: !isHeroVisible)
                } else {
                    ToolbarItem(placement: .principal) {
                        Text(verbatim: title)
                            .font(.headline)
                            .lineLimit(1)
                            .opacity(isHeroVisible ? 0 : 1)
                            .accessibilityHidden(isHeroVisible)
                            .accessibilityIdentifier("manga_compact_nav_title")
                    }
                }
            }

            ToolbarItemGroup(placement: .primaryAction) {
                if let translation {
                    Button {
                        translation.toggleTranslation()
                    } label: {
                        if translation.isTranslating {
                            ProgressView().controlSize(.small)
                        } else {
                            Image(fontAwesome: .language)
                        }
                    }
                    .accessibilityLabel(
                        Text(
                            verbatim: FlareAppleUILocalization.string(
                                translation.showTranslation ? "Show original" : "Translate",
                                fallback: translation.showTranslation ? "Show original" : "Translate"
                            )
                        )
                    )
                    .accessibilityIdentifier("manga_translation_toggle")
                }

                Button {
                    if let loadedDetail, let shareURL {
                        onNavigate(
                            .statusShareSheet(
                                loadedDetail.accountType,
                                loadedDetail.mangaKey,
                                shareURL,
                                nil,
                                nil
                            )
                        )
                    }
                } label: {
                    Image(fontAwesome: .shareNodes)
                }
                .disabled(loadedDetail == nil || shareURL == nil)
                .accessibilityLabel(
                    Text(
                        verbatim: FlareAppleUILocalization.string("fx_share", fallback: "Share")
                    )
                )

                Menu {
                    Button {
                        if let shareURL {
                            openExternal(shareURL)
                        }
                    } label: {
                        Label(
                            FlareAppleUILocalization.string(
                                "manga_open_in_browser",
                                fallback: "Open in browser"
                            ),
                            systemImage: "safari"
                        )
                    }
                    .disabled(shareURL == nil)

                    Button {
                        onRefresh()
                    } label: {
                        Label(
                            FlareAppleUILocalization.string("Refresh", fallback: "Refresh"),
                            systemImage: "arrow.clockwise"
                        )
                    }
                    .disabled(isRefreshing)
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityIdentifier("manga_more_menu")
                .accessibilityLabel(
                    Text(verbatim: FlareAppleUILocalization.string("more", fallback: "More"))
                )
            }
        }
        .alert(
            FlareAppleUILocalization.string("manga_locked_chapter_title", fallback: "Locked chapter"),
            isPresented: Binding(
                get: { lockedPromptChapter != nil },
                set: { if !$0 { lockedPromptChapter = nil } }
            ),
            presenting: lockedPromptChapter
        ) { chapter in
            if let openUrl = MangaDetailFormatting.resolveLockedChapterURL(
                chapter: chapter,
                detailURL: loadedDetail?.url
            ) {
                Button(
                    FlareAppleUILocalization.string("manga_open_in_browser", fallback: "Open in browser")
                ) {
                    lockedPromptChapter = nil
                    openExternal(openUrl)
                }
            }
            Button(FlareAppleUILocalization.string("Cancel", fallback: "Cancel"), role: .cancel) {
                lockedPromptChapter = nil
            }
        } message: { chapter in
            Text(
                verbatim: FlareAppleUILocalization.string(
                    "manga_locked_chapter_message",
                    fallback: "%@ is locked. Open in browser to view on the original site?",
                    arguments: [chapter.title]
                )
            )
        }
    }

    private func compactBody(detail: UiMangaDetail) -> some View {
        ZStack(alignment: .top) {
            Color.clear
                .frame(height: 0)
                .onGeometryChange(for: CGFloat.self) { proxy in
                    proxy.frame(in: .global).minY
                } action: { minY in
                    compactSafeTopMinY = minY
                }
                .allowsHitTesting(false)
                .accessibilityHidden(true)

            GeometryReader { backdropProxy in
                let backdropMinY = backdropProxy.frame(in: .global).minY
                let topInset = max(compactSafeTopMinY - backdropMinY, 0)
                let visibleBottom = compactHeroMetrics.height > 0
                    ? max(compactHeroMetrics.maxY - backdropMinY, 0)
                    : 0
                if visibleBottom > 0 {
                    let baseHeight = topInset + compactHeroMetrics.height
                    let renderHeight = max(baseHeight, visibleBottom)
                    let offsetY = min(visibleBottom - baseHeight, 0)
                    MangaBlurredCoverBackdropView(
                        cover: detail.cover,
                        targetBackgroundColor: .flareSystemGroupedBackground
                    )
                    .frame(width: backdropProxy.size.width, height: renderHeight)
                    .background {
                        GeometryReader { innerProxy in
                            Color.clear.preference(
                                key: IOSMangaCompactBackdropFramePreferenceKey.self,
                                value: innerProxy.frame(in: .global)
                            )
                        }
                    }
                    .offset(y: offsetY)
                }
            }
            .clipped()
            .ignoresSafeArea(edges: .top)
            .allowsHitTesting(false)
            .accessibilityHidden(true)

            List {
                heroHeader(detail: detail, usesCardSurface: false)
                    .onDisappear {
                        isHeroVisible = false
                        compactHeroMetrics = CompactHeroMetrics()
                    }
                    .onGeometryChange(for: CompactHeroMetrics.self) { proxy in
                        let frame = proxy.frame(in: .global)
                        return CompactHeroMetrics(
                            minY: frame.minY,
                            maxY: frame.maxY,
                            height: frame.height
                        )
                    } action: { metrics in
                        compactHeroMetrics = metrics
                        guard metrics.height > 0 else { return }
                        let collapseThreshold = max(compactSafeTopMinY + 8, 44)
                        let expandThreshold = collapseThreshold + 24
                        if isHeroVisible && metrics.maxY < collapseThreshold {
                            isHeroVisible = false
                        } else if !isHeroVisible && metrics.maxY > expandThreshold {
                            isHeroVisible = true
                        }
                    }
                    .listRowSeparator(.hidden)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)

                if hasSynopsisOrTags(detail) {
                    MangaSynopsisAndTagsCardView(detail: detail)
                        .listRowSeparator(.hidden)
                        .listRowInsets(
                            EdgeInsets(
                                top: 4,
                                leading: TimelineHorizontalInsets.card,
                                bottom: 6,
                                trailing: TimelineHorizontalInsets.card
                            )
                        )
                        .listRowBackground(Color.clear)
                }

                if availableTabs.count > 1 {
                    Section {
                        compactTabRows(detail: detail, tab: activeTab)
                    } header: {
                        stickyTabPickerHeader
                    }
                    .id("manga_compact_tabs_multi")
                } else {
                    Section {
                        compactTabRows(detail: detail, tab: .chapters)
                    }
                    .id("manga_compact_tabs_single")
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .listRowSpacing(2)
            .refreshable {
                onRefresh()
            }
            .detectScrolling()
        }
    }

    private var stickyTabPickerHeader: some View {
        Picker("", selection: $selectedTab) {
            ForEach(availableTabs) { tab in
                Text(verbatim: tab.localizedTitle).tag(tab)
            }
        }
        .pickerStyle(.segmented)
        .labelsHidden()
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity)
        .background(.bar)
        .textCase(nil)
        .listRowInsets(EdgeInsets())
    }

    @ViewBuilder
    private func compactTabRows(detail: UiMangaDetail, tab: IOSMangaDetailTab) -> some View {
        switch tab {
        case .chapters:
            chaptersListRows(detail: detail)

        case .comments:
            if let comments {
                TimelinePagingListContent(data: comments)
            }

        case .recommendations:
            if let recommendations {
                IOSMangaRecommendationsSection(
                    recommendations: recommendations,
                    showTitle: false,
                    onOpenMedia: { post, media in
                        openPostMedia(post: post, media: media)
                    }
                )
                .padding(.horizontal, TimelineHorizontalInsets.card)
                .padding(.vertical, 6)
                .listRowSeparator(.hidden)
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            }
        }
    }

    @ViewBuilder
    private func chaptersListRows(detail: UiMangaDetail) -> some View {
        MangaChaptersHeaderView(
            chapterGroups: detail.chapterGroups,
            selectedGroupId: selectedGroupId,
            chapterSortDescending: chapterSortDescending,
            chapterCount: chapters.count,
            onSelectGroup: onSelectGroup,
            onToggleSort: onToggleSort
        )
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 6)
        .background {
            GeometryReader { proxy in
                Color.clear.preference(
                    key: MangaDetailPaneFramesPreferenceKey.self,
                    value: MangaDetailPaneFrames(chaptersSection: proxy.frame(in: .global))
                )
            }
        }
        .listRowSeparator(.hidden)
        .listRowInsets(EdgeInsets())
        .listRowBackground(Color.clear)

        if chapters.isEmpty {
            Text(
                verbatim: FlareAppleUILocalization.string(
                    "manga_detail_empty_chapters",
                    fallback: "No chapters available"
                )
            )
            .font(.subheadline)
            .flareSecondaryText()
            .padding(.horizontal, 16)
            .padding(.vertical, 16)
            .listRowSeparator(.hidden)
            .listRowInsets(EdgeInsets())
            .listRowBackground(Color.clear)
        } else {
            ForEach(Array(chapters.enumerated()), id: \.element.chapter.id) { index, item in
                let markToggleLabel = item.isRead
                    ? FlareAppleUILocalization.string("manga_detail_mark_unread", fallback: "Mark as unread")
                    : FlareAppleUILocalization.string("manga_detail_mark_read", fallback: "Mark as read")

                ListCardView(index: index, totalCount: chapters.count) {
                    MangaChapterRowView(
                        item: item,
                        showsTrailingMenu: false,
                        detailURL: detail.url,
                        style: .listPlain,
                        onClick: {
                            if item.chapter.locked {
                                lockedPromptChapter = item.chapter
                            } else {
                                onNavigate(.mangaReader(detail.accountType, detail.mangaKey, item.chapter.id, nil))
                            }
                        },
                        onSetRead: { isRead in
                            onSetChapterRead(item.chapter.id, isRead)
                        }
                    )
                }
                .listRowSeparator(.hidden)
                .listRowInsets(
                    EdgeInsets(
                        top: 0,
                        leading: TimelineHorizontalInsets.card,
                        bottom: 0,
                        trailing: TimelineHorizontalInsets.card
                    )
                )
                .listRowBackground(Color.clear)
                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                    Button {
                        onSetChapterRead(item.chapter.id, !item.isRead)
                    } label: {
                        Label(
                            markToggleLabel,
                            systemImage: item.isRead ? "circle" : "checkmark.circle.fill"
                        )
                    }
                    .tint(item.isRead ? .secondary : .accentColor)
                }
                .contextMenu {
                    Button {
                        onSetChapterRead(item.chapter.id, !item.isRead)
                    } label: {
                        Label(
                            markToggleLabel,
                            systemImage: item.isRead ? "circle" : "checkmark.circle"
                        )
                    }

                    if let openUrl = MangaDetailFormatting.resolveLockedChapterURL(
                        chapter: item.chapter,
                        detailURL: detail.url
                    ) {
                        Button {
                            openExternal(openUrl)
                        } label: {
                            Label(
                                FlareAppleUILocalization.string(
                                    "manga_open_in_browser",
                                    fallback: "Open in browser"
                                ),
                                systemImage: "safari"
                            )
                        }
                    }
                }
                .accessibilityAction(named: Text(verbatim: markToggleLabel)) {
                    onSetChapterRead(item.chapter.id, !item.isRead)
                }
            }
        }
    }

    private func regularSplitBody(detail: UiMangaDetail) -> some View {
        GeometryReader { proxy in
            let metaWidth = min(max(proxy.size.width * 0.36, 320), 400)
            HStack(spacing: 0) {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 12) {
                        heroHeader(detail: detail, usesCardSurface: true)
                        if hasSynopsisOrTags(detail) {
                            MangaSynopsisAndTagsCardView(detail: detail)
                        }
                    }
                    .padding(16)
                }
                .frame(width: metaWidth)
                .frame(maxHeight: .infinity)

                Divider()

                VStack(spacing: 0) {
                    if availableTabs.count > 1 {
                        Picker("", selection: $selectedTab) {
                            ForEach(availableTabs) { tab in
                                Text(verbatim: tab.localizedTitle).tag(tab)
                            }
                        }
                        .pickerStyle(.segmented)
                        .labelsHidden()
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                    }

                    switch activeTab {
                    case .chapters:
                        List {
                            chaptersListRows(detail: detail)
                        }
                        .listStyle(.plain)
                        .scrollContentBackground(.hidden)
                        .listRowSpacing(2)
                        .refreshable {
                            onRefresh()
                        }
                        .detectScrolling()

                    case .comments:
                        if let comments {
                            UITimelinePagingView(
                                data: comments,
                                detailStatusKey: nil,
                                key: "manga_comments_tab_\(detail.mangaKey.description())",
                                suppressInitialRefreshIndicator: true
                            )
                        } else {
                            Spacer()
                        }

                    case .recommendations:
                        if let recommendations {
                            UIGalleryTimelinePagingView(
                                data: recommendations,
                                suppressInitialRefreshIndicator: true
                            )
                        } else {
                            Spacer()
                        }
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }

    private func heroHeader(detail: UiMangaDetail, usesCardSurface: Bool) -> some View {
        IOSMangaHeroHeaderView(
            detail: detail,
            translation: translation,
            primaryReadTarget: primaryReadTarget,
            usesCardSurface: usesCardSurface,
            onOpenCover: { cover in
                onNavigate(.mediaRaw([cover], 0, cover.previewUrl))
            },
            onOpenProfile: { accountType, userKey in
                onNavigate(.profileUser(accountType, userKey))
            },
            onOpenReader: { chapterId, initialPage in
                onNavigate(.mangaReader(detail.accountType, detail.mangaKey, chapterId, initialPage))
            }
        )
    }

    private func openPostMedia(post: UiTimelineV2.Post, media: UiMedia) {
        let medias = post.images.filter { $0 is UiMediaImage || $0 is UiMediaUgoira }
        let index = medias.firstIndex { $0.url == media.url } ?? 0
        onNavigate(.mediaRaw(medias, index, media.mediaPreviewURL))
    }

    private func openExternal(_ rawURL: String) {
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

private struct IOSMangaHeroHeaderView: View {
    @Environment(\.openURL) private var openURL
    let detail: UiMangaDetail
    let translation: (any MangaTranslatePresenterState)?
    let primaryReadTarget: PrimaryReadTarget?
    let usesCardSurface: Bool
    let onOpenCover: (UiMediaImage) -> Void
    let onOpenProfile: (AccountType, MicroBlogKey) -> Void
    let onOpenReader: (String, Int) -> Void

    private var combinedCreatorNames: String {
        var seen = Set<String>()
        var names: [String] = []
        for raw in detail.authors + detail.artists {
            let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty, seen.insert(trimmed).inserted {
                names.append(trimmed)
            }
        }
        return names.joined(separator: " · ")
    }

    private var showsBookmarkFallback: Bool {
        detail.actions.isEmpty && (!(detail.bookmarkAction is ClickEventNoop) || detail.isBookmarked)
    }

    private var targetBackdropColor: Color {
        usesCardSurface ? .flareSecondarySystemGroupedBackground : .flareSystemGroupedBackground
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 16) {
                if let cover = detail.cover {
                    Button {
                        onOpenCover(cover)
                    } label: {
                        NetworkImage(data: cover, placeholder: cover.preview, contentMode: .fill)
                            .frame(width: 114, height: 171)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .shadow(color: .black.opacity(0.22), radius: 10, x: 0, y: 5)
                            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("manga_cover_button")
                    .accessibilityLabel(Text(verbatim: detail.title))
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text(verbatim: detail.title)
                        .flarePrimaryText()
                        .font(.title3.bold())
                        .fixedSize(horizontal: false, vertical: true)

                    if let altTitle = detail.altTitles
                        .map({ $0.trimmingCharacters(in: .whitespacesAndNewlines) })
                        .first(where: { !$0.isEmpty }) {
                        Text(verbatim: altTitle)
                            .font(.subheadline)
                            .flareSecondaryText()
                            .lineLimit(2)
                    }

                    if let author = detail.author {
                        Button {
                            onOpenProfile(detail.accountType, author.key)
                        } label: {
                            HStack(spacing: 8) {
                                AvatarView(data: author.avatar)
                                    .frame(width: 24, height: 24)
                                RichText(text: author.name)
                                    .font(.caption)
                                    .flareSecondaryText()
                                    .lineLimit(1)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("manga_author_button")
                    } else if !combinedCreatorNames.isEmpty {
                        Text(verbatim: combinedCreatorNames)
                            .font(.caption)
                            .flareSecondaryText()
                            .lineLimit(2)
                    }

                    HStack(spacing: 8) {
                        if let status = detail.status {
                            MangaStatusBadgeView(status: status)
                        }
                        if let updatedAt = detail.updatedAt {
                            DateTimeText(data: updatedAt)
                                .font(.caption2)
                                .flareSecondaryText()
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            if let translation {
                if translation.isTranslating {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                }
                if translation.error != nil {
                    Button {
                        translation.retry()
                    } label: {
                        Text(
                            verbatim: FlareAppleUILocalization.string(
                                "Retry translation",
                                fallback: "Retry translation"
                            )
                        )
                    }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("manga_translation_retry")
                }
            }

            if primaryReadTarget != nil || showsBookmarkFallback {
                HStack(spacing: 10) {
                    if let target = primaryReadTarget {
                        Button {
                            onOpenReader(target.chapterId, Int(target.initialPage))
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "book.fill")
                                Text(verbatim: MangaDetailFormatting.primaryReadButtonTitle(for: target))
                                    .lineLimit(1)
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .accessibilityIdentifier("manga_primary_read_cta")
                    } else {
                        Spacer(minLength: 0)
                    }

                    if showsBookmarkFallback {
                        let isBookmarkDisabled = detail.bookmarkAction is ClickEventNoop
                        Button {
                            detail.onBookmark(ClickContext(launcher: AppleUriLauncher(openUrl: openURL)))
                        } label: {
                            Image(fontAwesome: detail.isBookmarked ? .heartFill : .heart)
                                .frame(width: 34, height: 34)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .disabled(isBookmarkDisabled)
                        .opacity(isBookmarkDisabled ? 0.45 : 1.0)
                        .foregroundStyle(detail.isBookmarked ? Color.red : Color.secondary)
                        .accessibilityIdentifier("manga_cta_bookmark_fallback")
                        .accessibilityLabel(
                            Text(
                                verbatim: detail.isBookmarked
                                    ? FlareAppleUILocalization.string("bookmark_remove", fallback: "Remove bookmark")
                                    : FlareAppleUILocalization.string("bookmark_add", fallback: "Add bookmark")
                            )
                        )
                    }
                }
            }

            if !detail.actions.isEmpty {
                StatusActionsView(
                    data: detail.actions,
                    useText: false,
                    isDetail: true,
                    postKey: detail.mangaKey.description()
                )
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 14)
        .padding(.bottom, 16)
        .flareTextBackground(usesCardSurface ? .flareSecondarySystemGroupedBackground : .flareSystemGroupedBackground)
        .background {
            if usesCardSurface {
                MangaBlurredCoverBackdropView(
                    cover: detail.cover,
                    targetBackgroundColor: targetBackdropColor
                )
            }
        }
        .clipShape(
            RoundedRectangle(
                cornerRadius: usesCardSurface ? 28 : 0,
                style: .continuous
            )
        )
        .background {
            GeometryReader { proxy in
                Color.clear.preference(
                    key: MangaDetailPaneFramesPreferenceKey.self,
                    value: MangaDetailPaneFrames(heroCard: proxy.frame(in: .global))
                )
            }
        }
    }
}

@available(iOS 27.1, *)
private struct MangaCompactNavigationTitle: ToolbarContent {
    @Environment(\.toolbarVerticalEdge) private var toolbarVerticalEdge
    let title: String
    let isVisible: Bool

    var body: some ToolbarContent {
        if toolbarVerticalEdge == nil {
            ToolbarItem(placement: .principal) {
                Text(verbatim: title)
                    .font(.headline)
                    .lineLimit(1)
                    .opacity(isVisible ? 1 : 0)
                    .accessibilityHidden(!isVisible)
                    .accessibilityIdentifier("manga_compact_nav_title")
            }
        }
    }
}

private nonisolated struct CompactHeroMetrics: Equatable, Sendable {
    var minY: CGFloat = 0
    var maxY: CGFloat = 0
    var height: CGFloat = 0
}

struct IOSMangaCompactBackdropFramePreferenceKey: PreferenceKey {
    static var defaultValue: CGRect? { nil }

    static func reduce(value: inout CGRect?, nextValue: () -> CGRect?) {
        if let next = nextValue() {
            value = next
        }
    }
}

struct IOSMangaCompactHeroVisiblePreferenceKey: PreferenceKey {
    static var defaultValue: Bool? { nil }

    static func reduce(value: inout Bool?, nextValue: () -> Bool?) {
        if let next = nextValue() {
            value = next
        }
    }
}

private enum IOSMangaDetailTab: String, Identifiable {
    case chapters
    case comments
    case recommendations

    var id: String { rawValue }

    var localizedTitle: String {
        switch self {
        case .chapters:
            FlareAppleUILocalization.string("manga_detail_chapters_title", fallback: "Chapters")
        case .comments:
            FlareAppleUILocalization.string("manga_detail_tab_comments", fallback: "Comments")
        case .recommendations:
            FlareAppleUILocalization.string("manga_detail_tab_recommendations", fallback: "Recommendations")
        }
    }
}

private struct IOSMangaRecommendationsSection: View {
    let recommendations: PagingState<UiTimelineV2>
    var showTitle: Bool = true
    let onOpenMedia: (UiTimelineV2.Post, UiMedia) -> Void
    private let columnCount = 2

    private var sectionTitle: String {
        FlareAppleUILocalization.string("manga_detail_tab_recommendations", fallback: "Recommendations")
    }

    var body: some View {
        switch onEnum(of: recommendations) {
        case .empty:
            VStack(alignment: .leading, spacing: 8) {
                if showTitle {
                    Text(verbatim: sectionTitle)
                        .flarePrimaryText()
                        .font(.headline)
                        .fontWeight(.semibold)
                }
                Text(
                    verbatim: FlareAppleUILocalization.string(
                        "manga_detail_empty_recommendations",
                        fallback: "No recommended works yet"
                    )
                )
                .font(.subheadline)
                .flareSecondaryText()
                .padding(.vertical, 16)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        case .error(let error):
            VStack(alignment: .leading, spacing: 8) {
                if showTitle {
                    Text(verbatim: sectionTitle)
                        .flarePrimaryText()
                        .font(.headline)
                        .fontWeight(.semibold)
                }
                ListErrorView(error: error.error) {
                    error.onRetry()
                }
            }
        case .loading:
            VStack(alignment: .leading, spacing: 8) {
                if showTitle {
                    Text(verbatim: sectionTitle)
                        .flarePrimaryText()
                        .font(.headline)
                        .fontWeight(.semibold)
                }
                HStack(alignment: .top, spacing: 8) {
                    ForEach(0..<columnCount, id: \.self) { column in
                        LazyVStack(spacing: 8) {
                            ForEach(0..<2, id: \.self) { index in
                                TimelineGalleryItemView(item: nil, onOpenMedia: onOpenMedia)
                                    .aspectRatio((index + column).isMultiple(of: 2) ? 0.75 : 1.25, contentMode: .fit)
                            }
                        }
                        .frame(maxWidth: .infinity)
                    }
                }
            }
        case .success(let success):
            let itemCount = Int(success.itemCount)
            if itemCount > 0 {
                VStack(alignment: .leading, spacing: 8) {
                    if showTitle {
                        Text(verbatim: sectionTitle)
                            .flarePrimaryText()
                            .font(.headline)
                            .fontWeight(.semibold)
                    }
                    HStack(alignment: .top, spacing: 8) {
                        ForEach(0..<columnCount, id: \.self) { column in
                            LazyVStack(spacing: 8) {
                                ForEach(Array(stride(from: column, to: itemCount, by: columnCount)), id: \.self) { index in
                                    TimelineGalleryItemView(
                                        item: success.peek(index: Int32(index)),
                                        onOpenMedia: onOpenMedia
                                    )
                                    .onAppear {
                                        _ = success.get(index: Int32(index))
                                    }
                                }
                            }
                            .frame(maxWidth: .infinity)
                        }
                    }
                }
            }
        }
    }
}

private struct IOSMangaDetailLoadingView: View {
    var body: some View {
        VStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 14)
                .fill(.placeholder)
                .frame(height: 210)
            RoundedRectangle(cornerRadius: 14)
                .fill(.placeholder)
                .frame(height: 120)
            RoundedRectangle(cornerRadius: 14)
                .fill(.placeholder)
                .frame(height: 180)
            Spacer()
        }
        .padding(16)
        .redacted(reason: .placeholder)
    }
}

private extension TimelineAppearance {
    func mangaCardTimeline() -> TimelineAppearance {
        doCopy(
            avatarShape: avatarShape,
            showMedia: showMedia,
            showSensitiveContent: showSensitiveContent,
            expandContentWarning: expandContentWarning,
            expandMediaSize: expandMediaSize,
            limitSingleMediaHeight: limitSingleMediaHeight,
            mediaLayout: mediaLayout,
            limitMediaGridToNine: limitMediaGridToNine,
            videoAutoplay: videoAutoplay,
            showLinkPreview: showLinkPreview,
            compatLinkPreview: compatLinkPreview,
            showEmojiReactions: showEmojiReactions,
            showNumbers: showNumbers,
            postActionStyle: postActionStyle,
            postActionLayout: postActionLayout,
            postActionFixedWidth: postActionFixedWidth,
            fullWidthPost: fullWidthPost,
            absoluteTimestamp: absoluteTimestamp,
            showPlatformLogo: showPlatformLogo,
            timelineDisplayMode: .card,
            aiConfig: aiConfig,
            lineLimit: lineLimit,
            showTranslateButton: showTranslateButton
        )
    }
}
