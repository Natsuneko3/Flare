import FlareAppleCore
import FlareAppleUI
import Foundation
@preconcurrency import KotlinSharedUI
import SwiftUI

@MainActor
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

@MainActor
struct MangaCommentsScreen: View {
    let accountType: AccountType
    let mangaKey: MicroBlogKey
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
        ScrollView {
            LazyVStack(spacing: 2) {
                TimelinePagingContent(
                    data: presenter.state.comments,
                    detailStatusKey: nil
                )
            }
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .padding(.horizontal, 16)
        }
        .detectScrolling()
        .background(Color.flareSystemGroupedBackground)
        .navigationTitle(
            FlareAppleUILocalization.string("manga_detail_tab_comments", fallback: "Comments")
        )
    }
}

@MainActor
struct MangaDetailContentView: View {
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

    @State private var selectedTab: MacMangaDetailTab = .chapters
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

    private var availableTabs: [MacMangaDetailTab] {
        var tabs: [MacMangaDetailTab] = [.chapters]
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
            GeometryReader { proxy in
                if proxy.size.width >= 540 {
                    splitBody(detail: loaded, totalWidth: proxy.size.width)
                } else {
                    compactBody(detail: loaded)
                }
            }
        } errorContent: { error in
            ListErrorView(error: error, onRetry: onRetry)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } loadingContent: {
            MacMangaDetailLoadingView()
        }
        .flareTextBackground(.flareSystemGroupedBackground)
        .background(Color.flareSystemGroupedBackground)
        .navigationTitle(loadedDetail?.title ?? "")
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

                if let loadedDetail, let shareURL {
                    MacStatusShareMenu(
                        data: MacStatusShareData(
                            statusKey: loadedDetail.mangaKey,
                            accountType: loadedDetail.accountType,
                            shareUrl: shareURL
                        ),
                        onShareScreenshot: {
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
                    ) {
                        Image(fontAwesome: .shareNodes)
                    }
                    .accessibilityLabel(
                        Text(verbatim: FlareAppleUILocalization.string("fx_share", fallback: "Share"))
                    )
                }
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

    private func splitBody(detail: UiMangaDetail, totalWidth: CGFloat) -> some View {
        let metaWidth = min(max(totalWidth * 0.36, 300), 400)
        return HStack(spacing: 0) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    heroCard(detail: detail)
                    if detail.content != nil || detail.tagGroups.contains(where: { !$0.tags.isEmpty }) {
                        MangaSynopsisAndTagsCardView(detail: detail)
                    }
                }
                .padding(16)
            }
            .detectScrolling()
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
                    .detectScrolling()

                case .comments:
                    if let comments {
                        ScrollView {
                            LazyVStack(spacing: 2) {
                                TimelinePagingContent(
                                    data: comments,
                                    detailStatusKey: nil
                                )
                            }
                            .padding(16)
                        }
                        .detectScrolling()
                    } else {
                        Spacer()
                    }

                case .recommendations:
                    if let recommendations {
                        ScrollView {
                            MacMangaRecommendationsSection(
                                recommendations: recommendations,
                                showTitle: false,
                                onOpenMedia: { post, media in
                                    openPostMedia(post: post, media: media)
                                }
                            )
                            .padding(16)
                        }
                        .detectScrolling()
                    } else {
                        Spacer()
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
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
                    MacMangaCommentsPreview(
                        comments: comments,
                        onViewMore: {
                            onNavigate(.mangaComments(detail.accountType, detail.mangaKey))
                        }
                    )
                    .padding(.top, 4)
                }

                if supportsRecommendations, let recommendations {
                    MacMangaRecommendationsSection(
                        recommendations: recommendations,
                        showTitle: true,
                        onOpenMedia: { post, media in
                            openPostMedia(post: post, media: media)
                        }
                    )
                    .padding(.top, 4)
                }
            }
            .padding(16)
        }
        .detectScrolling()
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

    private func openPostMedia(post: UiTimelineV2.Post, media: UiMedia) {
        let medias = post.images.filter { $0 is UiMediaImage || $0 is UiMediaUgoira }
        let index = medias.firstIndex { $0.url == media.url } ?? 0
        onNavigate(.mediaRaw(medias, index, media.mediaPreviewURL))
    }

    private func openExternal(_ rawURL: String) {
        if let onOpenExternalURL {
            onOpenExternalURL(rawURL)
        } else {
            onNavigate(.externalLink(rawURL))
        }
    }
}

private nonisolated enum MacMangaDetailTab: String, Identifiable, Sendable {
    case chapters
    case comments
    case recommendations

    var id: String { rawValue }

    @MainActor
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

@MainActor
private struct MacMangaCommentsPreview: View {
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
                    .font(.headline)
                    .fontWeight(.semibold)
                ForEach(0..<3, id: \.self) { index in
                    if index > 0 { Divider() }
                    TimelinePlaceholderView()
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                }
            }
        case .success(let success):
            let total = Int(success.itemCount)
            let visible = min(total, 3)
            if visible > 0 {
                VStack(alignment: .leading, spacing: 4) {
                    Text(verbatim: sectionTitle)
                        .font(.headline)
                        .fontWeight(.semibold)
                    ForEach(0..<visible, id: \.self) { index in
                        if let item = success.peek(index: Int32(index)) {
                            if index > 0 { Divider() }
                            TimelineView(data: item, detailStatusKey: nil)
                                .padding(.horizontal, 16)
                                .padding(.vertical, 8)
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
                        .padding(.top, 8)
                    }
                }
            }
        }
    }
}

@MainActor
private struct MacMangaRecommendationsSection: View {
    let recommendations: PagingState<UiTimelineV2>
    let showTitle: Bool
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
                        .font(.headline)
                        .fontWeight(.semibold)
                }
                HStack(alignment: .top, spacing: 8) {
                    ForEach(0..<columnCount, id: \.self) { column in
                        LazyVStack(spacing: 8) {
                            ForEach(0..<3, id: \.self) { index in
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

@MainActor
private struct MacMangaDetailLoadingView: View {
    var body: some View {
        HStack(spacing: 16) {
            VStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 14)
                    .fill(.placeholder)
                    .frame(height: 220)
                RoundedRectangle(cornerRadius: 14)
                    .fill(.placeholder)
                    .frame(height: 160)
                Spacer()
            }
            .frame(width: 340)

            VStack(spacing: 10) {
                ForEach(0..<5, id: \.self) { _ in
                    RoundedRectangle(cornerRadius: 12)
                        .fill(.placeholder)
                        .frame(height: 54)
                }
                Spacer()
            }
            .frame(maxWidth: .infinity)
        }
        .padding(16)
        .redacted(reason: .placeholder)
    }
}
