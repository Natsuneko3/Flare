import FlareAppleCore
import FlareAppleUI
import Foundation
@preconcurrency import KotlinSharedUI
import SwiftUI

@MainActor
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
    let isRefreshing: Bool
    let onRefresh: () -> Void
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

    private var activeTab: MacMangaDetailTab {
        availableTabs.contains(selectedTab) ? selectedTab : .chapters
    }

    private func hasSynopsisOrTags(_ detail: UiMangaDetail) -> Bool {
        detail.content?.innerText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false ||
            detail.tagGroups.contains(where: { !$0.tags.isEmpty })
    }

    var body: some View {
        StateView(state: detail) { loaded in
            GeometryReader { proxy in
                if proxy.size.width >= 560 {
                    splitBody(detail: loaded, totalWidth: proxy.size.width)
                } else {
                    compactBody(detail: loaded, totalWidth: proxy.size.width)
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
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                if let translation {
                    let translateLabel = FlareAppleUILocalization.string(
                        translation.showTranslation ? "Show original" : "Translate",
                        fallback: translation.showTranslation ? "Show original" : "Translate"
                    )
                    Button {
                        translation.toggleTranslation()
                    } label: {
                        if translation.isTranslating {
                            ProgressView().controlSize(.small)
                        } else {
                            Image(fontAwesome: .language)
                        }
                    }
                    .accessibilityLabel(Text(verbatim: translateLabel))
                    .accessibilityIdentifier("manga_translation_toggle")
                    .help(translateLabel)
                }

                let refreshLabel = FlareAppleUILocalization.string("Refresh", fallback: "Refresh")
                Button {
                    onRefresh()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .disabled(isRefreshing)
                .accessibilityIdentifier("manga_refresh_button")
                .accessibilityLabel(Text(verbatim: refreshLabel))
                .help(refreshLabel)

                let openBrowserLabel = FlareAppleUILocalization.string(
                    "manga_open_in_browser",
                    fallback: "Open in browser"
                )
                Button {
                    if let shareURL {
                        openExternal(shareURL)
                    }
                } label: {
                    Image(systemName: "safari")
                }
                .disabled(shareURL == nil)
                .accessibilityLabel(Text(verbatim: openBrowserLabel))
                .help(openBrowserLabel)

                if let loadedDetail, let shareURL {
                    let shareLabel = FlareAppleUILocalization.string("fx_share", fallback: "Share")
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
                    .accessibilityLabel(Text(verbatim: shareLabel))
                    .help(shareLabel)
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
        let metaWidth = min(max(totalWidth * 0.35, 280), 360)
        let rightPaneWidth = max(totalWidth - metaWidth - 1, 0)
        return HStack(spacing: 0) {
            ScrollView {
                MacMangaInspectorPane(
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

                switch activeTab {
                case .chapters:
                    ScrollView {
                        MacMangaChaptersSection(
                            chapterGroups: detail.chapterGroups,
                            selectedGroupId: selectedGroupId,
                            chapterSortDescending: chapterSortDescending,
                            chapters: chapters,
                            detailURL: detail.url,
                            containerWidth: max(rightPaneWidth - 32, 0),
                            onSelectGroup: onSelectGroup,
                            onToggleSort: onToggleSort,
                            onChapterTap: { chapter in
                                onNavigate(.mangaReader(detail.accountType, detail.mangaKey, chapter.id, nil))
                            },
                            onLockedChapterTap: { chapter in
                                lockedPromptChapter = chapter
                            },
                            onSetChapterRead: onSetChapterRead,
                            onOpenExternal: openExternal
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

    private func compactBody(detail: UiMangaDetail, totalWidth: CGFloat) -> some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12, pinnedViews: [.sectionHeaders]) {
                heroCard(detail: detail)

                if hasSynopsisOrTags(detail) {
                    MangaSynopsisAndTagsCardView(detail: detail)
                }

                if availableTabs.count > 1 {
                    Section {
                        compactTabContent(detail: detail, tab: activeTab, containerWidth: max(totalWidth - 32, 0))
                    } header: {
                        Picker("", selection: $selectedTab) {
                            ForEach(availableTabs) { tab in
                                Text(verbatim: tab.localizedTitle).tag(tab)
                            }
                        }
                        .pickerStyle(.segmented)
                        .labelsHidden()
                        .padding(.vertical, 8)
                        .frame(maxWidth: .infinity)
                        .background(Color.flareSystemGroupedBackground)
                    }
                } else {
                    Section {
                        compactTabContent(detail: detail, tab: .chapters, containerWidth: max(totalWidth - 32, 0))
                    }
                }
            }
            .padding(16)
        }
        .detectScrolling()
    }

    @ViewBuilder
    private func compactTabContent(
        detail: UiMangaDetail,
        tab: MacMangaDetailTab,
        containerWidth: CGFloat
    ) -> some View {
        switch tab {
        case .chapters:
            MacMangaChaptersSection(
                chapterGroups: detail.chapterGroups,
                selectedGroupId: selectedGroupId,
                chapterSortDescending: chapterSortDescending,
                chapters: chapters,
                detailURL: detail.url,
                containerWidth: containerWidth,
                onSelectGroup: onSelectGroup,
                onToggleSort: onToggleSort,
                onChapterTap: { chapter in
                    onNavigate(.mangaReader(detail.accountType, detail.mangaKey, chapter.id, nil))
                },
                onLockedChapterTap: { chapter in
                    lockedPromptChapter = chapter
                },
                onSetChapterRead: onSetChapterRead,
                onOpenExternal: openExternal
            )

        case .comments:
            if let comments {
                LazyVStack(spacing: 2) {
                    TimelinePagingContent(
                        data: comments,
                        detailStatusKey: nil
                    )
                }
            }

        case .recommendations:
            if let recommendations {
                MacMangaRecommendationsSection(
                    recommendations: recommendations,
                    showTitle: false,
                    onOpenMedia: { post, media in
                        openPostMedia(post: post, media: media)
                    }
                )
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

@MainActor
private struct MacMangaInspectorPane: View {
    @Environment(\.openURL) private var openURL
    let detail: UiMangaDetail
    let translation: (any MangaTranslatePresenterState)?
    let primaryReadTarget: PrimaryReadTarget?
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

    private var hasSynopsis: Bool {
        detail.content?.innerText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
    }

    private var hasTags: Bool {
        detail.tagGroups.contains(where: { !$0.tags.isEmpty })
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top, spacing: 14) {
                    if let cover = detail.cover {
                        Button {
                            onOpenCover(cover)
                        } label: {
                            NetworkImage(data: cover, placeholder: cover.preview, contentMode: .fill)
                                .frame(width: 132, height: 198)
                                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                .shadow(color: .black.opacity(0.24), radius: 12, x: 0, y: 6)
                                .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(Text(verbatim: detail.title))
                    }

                    VStack(alignment: .leading, spacing: 6) {
                        Text(verbatim: detail.title)
                            .flarePrimaryText()
                            .font(.title2.weight(.bold))
                            .textSelection(.enabled)
                            .fixedSize(horizontal: false, vertical: true)

                        if let altTitle = detail.altTitles
                            .map({ $0.trimmingCharacters(in: .whitespacesAndNewlines) })
                            .first(where: { !$0.isEmpty }) {
                            Text(verbatim: altTitle)
                                .font(.subheadline)
                                .flareSecondaryText()
                                .textSelection(.enabled)
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
                            .controlSize(.large)
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
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.large)
                            .disabled(isBookmarkDisabled)
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
            .padding(16)
            .background {
                MangaBlurredCoverBackdropView(
                    cover: detail.cover,
                    targetBackgroundColor: .flareSystemGroupedBackground
                )
            }
            .background {
                GeometryReader { proxy in
                    Color.clear.preference(
                        key: MangaDetailPaneFramesPreferenceKey.self,
                        value: MangaDetailPaneFrames(heroCard: proxy.frame(in: .global))
                    )
                }
            }

            if hasSynopsis || hasTags {
                Divider()
                    .padding(.horizontal, 16)

                VStack(alignment: .leading, spacing: 12) {
                    if let content = detail.content, hasSynopsis {
                        MangaSynopsisContentView(content: content)
                    }

                    MangaTagGroupsView(tagGroups: detail.tagGroups)
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
}

@MainActor
private struct MacMangaChaptersSection: View {
    let chapterGroups: [UiMangaChapterGroup]
    let selectedGroupId: String?
    let chapterSortDescending: Bool
    let chapters: [UiMangaChapterItem]
    let detailURL: String?
    let containerWidth: CGFloat
    let onSelectGroup: (String) -> Void
    let onToggleSort: () -> Void
    let onChapterTap: (UiMangaChapter) -> Void
    let onLockedChapterTap: (UiMangaChapter) -> Void
    let onSetChapterRead: (String, Bool) -> Void
    let onOpenExternal: (String) -> Void

    @State private var measuredWidth: CGFloat = 0

    private var effectiveWidth: CGFloat {
        measuredWidth > 0 ? measuredWidth : containerWidth
    }

    private var usesMultiColumnGrid: Bool {
        effectiveWidth >= 520
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            MangaChaptersHeaderView(
                chapterGroups: chapterGroups,
                selectedGroupId: selectedGroupId,
                chapterSortDescending: chapterSortDescending,
                chapterCount: chapters.count,
                onSelectGroup: onSelectGroup,
                onToggleSort: onToggleSort
            )

            if chapters.isEmpty {
                Text(
                    verbatim: FlareAppleUILocalization.string(
                        "manga_detail_empty_chapters",
                        fallback: "No chapters available"
                    )
                )
                .font(.subheadline)
                .flareSecondaryText()
                .padding(.vertical, 16)
            } else if usesMultiColumnGrid {
                LazyVGrid(
                    columns: [GridItem(.adaptive(minimum: 220, maximum: 340), spacing: 8)],
                    alignment: .leading,
                    spacing: 6
                ) {
                    ForEach(chapters, id: \.chapter.id) { item in
                        MacMangaChapterCell(
                            item: item,
                            detailURL: detailURL,
                            onClick: {
                                if item.chapter.locked {
                                    onLockedChapterTap(item.chapter)
                                } else {
                                    onChapterTap(item.chapter)
                                }
                            },
                            onSetRead: { isRead in
                                onSetChapterRead(item.chapter.id, isRead)
                            },
                            onOpenExternal: onOpenExternal
                        )
                    }
                }
            } else {
                LazyVStack(alignment: .leading, spacing: 2) {
                    ForEach(chapters, id: \.chapter.id) { item in
                        MacMangaChapterCell(
                            item: item,
                            detailURL: detailURL,
                            onClick: {
                                if item.chapter.locked {
                                    onLockedChapterTap(item.chapter)
                                } else {
                                    onChapterTap(item.chapter)
                                }
                            },
                            onSetRead: { isRead in
                                onSetChapterRead(item.chapter.id, isRead)
                            },
                            onOpenExternal: onOpenExternal
                        )
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onGeometryChange(for: CGFloat.self) { proxy in
            proxy.size.width
        } action: { width in
            measuredWidth = width
        }
        .background {
            GeometryReader { proxy in
                Color.clear.preference(
                    key: MangaDetailPaneFramesPreferenceKey.self,
                    value: MangaDetailPaneFrames(chaptersSection: proxy.frame(in: .global))
                )
            }
        }
    }
}

@MainActor
private struct MacMangaChapterCell: View {
    let item: UiMangaChapterItem
    let detailURL: String?
    let onClick: () -> Void
    let onSetRead: (Bool) -> Void
    let onOpenExternal: (String) -> Void

    @State private var isHovered = false

    private var emphasis: MangaChapterTitleEmphasis {
        MangaDetailFormatting.chapterTitleEmphasis(for: item)
    }

    private var titleColor: Color {
        switch emphasis {
        case .current:
            return .accentColor
        case .muted:
            return .flareSecondaryLabel
        case .unread:
            return .flareLabel
        }
    }

    private var markToggleLabel: String {
        if item.isRead {
            return FlareAppleUILocalization.string("manga_detail_mark_unread", fallback: "Mark as unread")
        }
        return FlareAppleUILocalization.string("manga_detail_mark_read", fallback: "Mark as read")
    }

    private var resolvedOpenURL: String? {
        MangaDetailFormatting.resolveLockedChapterURL(chapter: item.chapter, detailURL: detailURL)
    }

    private var cellOverlayFill: Color {
        if item.isCurrent {
            return Color.accentColor.opacity(isHovered ? 0.18 : 0.12)
        }
        return isHovered ? Color.primary.opacity(0.06) : .clear
    }

    private var cleanedSubtitle: String? {
        guard let raw = item.chapter.subtitle?.trimmingCharacters(in: .whitespacesAndNewlines),
              !raw.isEmpty else {
            return nil
        }
        return raw
    }

    private var hasMetadataSubtitle: Bool {
        cleanedSubtitle != nil ||
            (!item.isRead && item.lastPageIndex > 0 && item.pageCount > 0) ||
            item.chapter.uploadedAt != nil
    }

    var body: some View {
        HStack(spacing: 6) {
            Button(action: onClick) {
                HStack(spacing: 10) {
                    if emphasis == .current {
                        Capsule()
                            .fill(Color.accentColor)
                            .frame(width: 3, height: 22)
                            .accessibilityHidden(true)
                    }

                    if item.chapter.locked {
                        Image(fontAwesome: .lock)
                            .font(.caption)
                            .foregroundStyle(Color.flareSecondaryLabel)
                            .accessibilityIdentifier("manga_chapter_locked_icon_\(item.chapter.id)")
                            .accessibilityLabel(
                                Text(
                                    verbatim: FlareAppleUILocalization.string(
                                        "manga_locked_chapter_title",
                                        fallback: "Locked chapter"
                                    )
                                )
                            )
                    } else if item.isRead {
                        Image(fontAwesome: .check)
                            .font(.caption)
                            .foregroundStyle(Color.flareSecondaryLabel)
                            .accessibilityIdentifier("manga_chapter_read_icon_\(item.chapter.id)")
                    }

                    VStack(alignment: .leading, spacing: 3) {
                        Text(verbatim: item.chapter.title)
                            .font(.body.weight(emphasis == .current ? .semibold : .regular))
                            .foregroundStyle(titleColor)
                            .lineLimit(2)
                            .multilineTextAlignment(.leading)

                        if hasMetadataSubtitle {
                            HStack(spacing: 8) {
                                if let subtitle = cleanedSubtitle {
                                    Text(verbatim: subtitle)
                                        .font(.caption)
                                        .flareSecondaryText()
                                        .lineLimit(1)
                                }
                                if !item.isRead, item.lastPageIndex > 0, item.pageCount > 0 {
                                    Text(
                                        verbatim: FlareAppleUILocalization.string(
                                            "manga_detail_page_progress",
                                            fallback: "Page %1$lld / %2$lld",
                                            arguments: [Int64(item.lastPageIndex + 1), Int64(item.pageCount)]
                                        )
                                    )
                                    .font(.caption)
                                    .foregroundStyle(Color.accentColor)
                                }
                                if let uploadedAt = item.chapter.uploadedAt {
                                    DateTimeText(data: uploadedAt)
                                        .font(.caption)
                                        .flareSecondaryText()
                                }
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.leading, 12)
                .padding(.vertical, 10)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Group {
                if isHovered {
                    Menu {
                        cellMenuActions
                    } label: {
                        Image(systemName: "ellipsis")
                            .font(.subheadline)
                            .foregroundStyle(Color.flareSecondaryLabel)
                            .frame(width: 28, height: 28)
                            .contentShape(Rectangle())
                    }
                    .menuStyle(.borderlessButton)
                    .fixedSize()
                    .accessibilityIdentifier("manga_chapter_menu_\(item.chapter.id)")
                    .accessibilityLabel(Text(verbatim: FlareAppleUILocalization.string("more", fallback: "More")))
                } else {
                    Color.clear
                        .frame(width: 28, height: 28)
                }
            }
            .padding(.trailing, 6)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .flareTextBackground(.flareSecondarySystemGroupedBackground)
        .background {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(Color.flareSecondarySystemGroupedBackground)
                .overlay {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(cellOverlayFill)
                }
        }
        .onHover { hovering in
            isHovered = hovering
        }
        .contextMenu {
            cellMenuActions
        }
        .accessibilityAction(named: Text(verbatim: markToggleLabel)) {
            onSetRead(!item.isRead)
        }
        .background {
            GeometryReader { proxy in
                Color.clear.preference(
                    key: MangaChapterFramesPreferenceKey.self,
                    value: [item.chapter.id: proxy.frame(in: .global)]
                )
            }
        }
        .accessibilityIdentifier("manga_chapter_row_\(item.chapter.id)")
    }

    @ViewBuilder
    private var cellMenuActions: some View {
        Button {
            onSetRead(!item.isRead)
        } label: {
            Label(
                markToggleLabel,
                systemImage: item.isRead ? "circle" : "checkmark.circle"
            )
        }

        if let openUrl = resolvedOpenURL {
            Button {
                onOpenExternal(openUrl)
            } label: {
                Label(
                    FlareAppleUILocalization.string("manga_open_in_browser", fallback: "Open in browser"),
                    systemImage: "safari"
                )
            }
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
