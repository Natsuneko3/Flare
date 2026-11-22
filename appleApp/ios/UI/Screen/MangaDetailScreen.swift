import FlareAppleCore
import FlareAppleUI
import Foundation
@preconcurrency import KotlinSharedUI
import SwiftUI

struct MangaDetailScreen: View {
    @State private var presenter: KotlinPresenter<MangaDetailPresenterState>
    @State private var refreshGeneration = 0

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
                refreshGeneration += 1
                presenter = KotlinPresenter(
                    presenter: MangaDetailPresenter(accountType: accountType, mangaKey: mangaKey)
                )
            }
        )
        .id(refreshGeneration)
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
    let onSelectGroup: (String) -> Void
    let onToggleSort: () -> Void
    let onSetChapterRead: (String, Bool) -> Void
    let onNavigate: (Route) -> Void
    let onOpenExternalURL: ((String) -> Void)?
    let onRetry: () -> Void

    @State private var selectedTab: IOSMangaDetailTab = .chapters
    @State private var lockedPromptChapter: UiMangaChapter?

    init(
        state: any MangaDetailPresenterState,
        onNavigate: @escaping (Route) -> Void,
        onOpenExternalURL: ((String) -> Void)? = nil,
        onRetry: @escaping () -> Void = {}
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
        self.onSelectGroup = { state.selectGroup(groupId: $0) }
        self.onToggleSort = { state.toggleChapterSort() }
        self.onSetChapterRead = { state.setChapterRead(chapterId: $0, isRead: $1) }
        self.onNavigate = onNavigate
        self.onOpenExternalURL = onOpenExternalURL
        self.onRetry = onRetry
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
        .navigationTitle(loadedDetail?.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
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
                    if let shareURL {
                        openExternal(shareURL)
                    }
                } label: {
                    Image(systemName: "safari")
                }
                .disabled(shareURL == nil)
                .accessibilityLabel(
                    Text(
                        verbatim: FlareAppleUILocalization.string(
                            "manga_open_in_browser",
                            fallback: "Open in browser"
                        )
                    )
                )

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
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                heroCard(detail: detail)

                if detail.content != nil || detail.tagGroups.contains(where: { !$0.tags.isEmpty }) {
                    MangaSynopsisAndTagsCardView(detail: detail)
                }

                MangaChapterListSection(
                    chapterGroups: detail.chapterGroups,
                    selectedGroupId: selectedGroupId,
                    chapterSortDescending: chapterSortDescending,
                    chapters: chapters,
                    onSelectGroup: onSelectGroup,
                    onToggleSort: onToggleSort,
                    onChapterTap: { chapter in
                        onNavigate(.mangaReader(detail.accountType, detail.mangaKey, chapter.id, nil))
                    },
                    onLockedChapterTap: { chapter in
                        lockedPromptChapter = chapter
                    },
                    onSetChapterRead: onSetChapterRead
                )

                if supportsComments, let comments {
                    IOSMangaCommentsPreview(
                        comments: comments,
                        onViewMore: {
                            onNavigate(.mangaComments(detail.accountType, detail.mangaKey))
                        }
                    )
                    .padding(.top, 4)
                }

                if supportsRecommendations, let recommendations {
                    IOSMangaRecommendationsSection(
                        recommendations: recommendations,
                        onOpenMedia: { post, media in
                            let medias = post.images.filter { $0 is UiMediaImage || $0 is UiMediaUgoira }
                            let index = medias.firstIndex { $0.url == media.url } ?? 0
                            onNavigate(.mediaRaw(medias, index, media.mediaPreviewURL))
                        }
                    )
                    .padding(.top, 4)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 24)
        }
        .timelineMediaViewport()
    }

    private func regularSplitBody(detail: UiMangaDetail) -> some View {
        GeometryReader { proxy in
            let metaWidth = min(max(proxy.size.width * 0.36, 320), 400)
            HStack(spacing: 0) {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 12) {
                        heroCard(detail: detail)
                        if detail.content != nil || detail.tagGroups.contains(where: { !$0.tags.isEmpty }) {
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
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                    }

                    let activeTab = availableTabs.contains(selectedTab) ? selectedTab : .chapters
                    switch activeTab {
                    case .chapters:
                        ScrollView {
                            MangaChapterListSection(
                                chapterGroups: detail.chapterGroups,
                                selectedGroupId: selectedGroupId,
                                chapterSortDescending: chapterSortDescending,
                                chapters: chapters,
                                onSelectGroup: onSelectGroup,
                                onToggleSort: onToggleSort,
                                onChapterTap: { chapter in
                                    onNavigate(.mangaReader(detail.accountType, detail.mangaKey, chapter.id, nil))
                                },
                                onLockedChapterTap: { chapter in
                                    lockedPromptChapter = chapter
                                },
                                onSetChapterRead: onSetChapterRead
                            )
                            .padding(16)
                        }

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

    private func heroCard(detail: UiMangaDetail) -> some View {
        MangaHeroCardView(
            detail: detail,
            translation: translation,
            primaryReadTarget: primaryReadTarget,
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

private struct IOSMangaCommentsPreview: View {
    let comments: PagingState<UiTimelineV2>
    let onViewMore: () -> Void

    private var sectionTitle: String {
        FlareAppleUILocalization.string("manga_detail_tab_comments", fallback: "Comments")
    }

    var body: some View {
        switch onEnum(of: comments) {
        case .empty:
            EmptyView()
        case .error(let error):
            ListErrorView(error: error.error) {
                error.onRetry()
            }
        case .loading:
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: sectionTitle)
                    .flarePrimaryText()
                    .font(.headline)
                    .fontWeight(.semibold)
                ForEach(0..<3, id: \.self) { index in
                    ListCardView(index: index, totalCount: 3) {
                        TimelinePlaceholderView()
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)
                    }
                }
            }
        case .success(let success):
            let total = Int(success.itemCount)
            let visible = min(total, 3)
            if visible > 0 {
                VStack(alignment: .leading, spacing: 4) {
                    Text(verbatim: sectionTitle)
                        .flarePrimaryText()
                        .font(.headline)
                        .fontWeight(.semibold)

                    ForEach(0..<visible, id: \.self) { index in
                        if let item = success.peek(index: Int32(index)) {
                            ListCardView(index: index, totalCount: visible) {
                                TimelineView(data: item, detailStatusKey: nil)
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 8)
                            }
                            .onAppear {
                                _ = success.get(index: Int32(index))
                            }
                        }
                    }

                    let hasMore: Bool = {
                        if total > 3 { return true }
                        if case .notLoading(let notLoading) = onEnum(of: success.appendState) {
                            return !notLoading.endOfPaginationReached
                        }
                        return false
                    }()
                    if hasMore {
                        Button(action: onViewMore) {
                            Text(
                                verbatim: FlareAppleUILocalization.string(
                                    "manga_detail_view_more",
                                    fallback: "View more"
                                )
                            )
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .padding(.top, 6)
                    }
                }
            }
        }
    }
}

private struct IOSMangaRecommendationsSection: View {
    let recommendations: PagingState<UiTimelineV2>
    let onOpenMedia: (UiTimelineV2.Post, UiMedia) -> Void
    private let columnCount = 2

    private var sectionTitle: String {
        FlareAppleUILocalization.string("manga_detail_tab_recommendations", fallback: "Recommendations")
    }

    var body: some View {
        switch onEnum(of: recommendations) {
        case .empty:
            VStack(alignment: .leading, spacing: 8) {
                Text(verbatim: sectionTitle)
                    .flarePrimaryText()
                    .font(.headline)
                    .fontWeight(.semibold)
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
        case .error(let error):
            VStack(alignment: .leading, spacing: 8) {
                Text(verbatim: sectionTitle)
                    .flarePrimaryText()
                    .font(.headline)
                    .fontWeight(.semibold)
                ListErrorView(error: error.error) {
                    error.onRetry()
                }
            }
        case .loading:
            VStack(alignment: .leading, spacing: 8) {
                Text(verbatim: sectionTitle)
                    .flarePrimaryText()
                    .font(.headline)
                    .fontWeight(.semibold)
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
                    Text(verbatim: sectionTitle)
                        .flarePrimaryText()
                        .font(.headline)
                        .fontWeight(.semibold)
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
