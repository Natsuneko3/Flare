import FlareAppleCore
import Foundation
@preconcurrency import KotlinSharedUI
import SwiftUI

public nonisolated enum MangaChapterTitleEmphasis: Equatable, Sendable {
    case current
    case muted
    case unread
}

public nonisolated enum MangaChapterRowStyle: Equatable, Sendable {
    case groupedCard(index: Int, totalCount: Int)
    case listPlain
}

public struct MangaBlurredCoverBackdropView: View {
    public let cover: UiMediaImage?
    public let targetBackgroundColor: Color

    public init(
        cover: UiMediaImage?,
        targetBackgroundColor: Color = .flareSystemGroupedBackground
    ) {
        self.cover = cover
        self.targetBackgroundColor = targetBackgroundColor
    }

    public var body: some View {
        GeometryReader { proxy in
            ZStack {
                targetBackgroundColor

                if let cover {
                    NetworkImage(data: cover, placeholder: cover.preview, contentMode: .fill)
                        .frame(width: max(proxy.size.width, 1), height: max(proxy.size.height, 1))
                        .scaleEffect(1.18)
                        .blur(radius: 28)
                        .overlay {
                            targetBackgroundColor.opacity(0.42)
                        }
                } else {
                    LinearGradient(
                        colors: [
                            Color.accentColor.opacity(0.18),
                            Color.accentColor.opacity(0.06),
                            targetBackgroundColor,
                        ],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                }

                LinearGradient(
                    stops: [
                        .init(color: targetBackgroundColor.opacity(0.08), location: 0.0),
                        .init(color: targetBackgroundColor.opacity(0.56), location: 0.62),
                        .init(color: targetBackgroundColor, location: 1.0),
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
            }
            .frame(width: proxy.size.width, height: proxy.size.height)
            .clipped()
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

public nonisolated struct MangaDetailPaneFrames: Equatable, Sendable {
    public var heroCard: CGRect?
    public var chaptersSection: CGRect?

    public init(heroCard: CGRect? = nil, chaptersSection: CGRect? = nil) {
        self.heroCard = heroCard
        self.chaptersSection = chaptersSection
    }
}

public struct MangaDetailPaneFramesPreferenceKey: PreferenceKey {
    public static var defaultValue: MangaDetailPaneFrames { MangaDetailPaneFrames() }

    public static func reduce(value: inout MangaDetailPaneFrames, nextValue: () -> MangaDetailPaneFrames) {
        let next = nextValue()
        if let heroCard = next.heroCard {
            value.heroCard = heroCard
        }
        if let chaptersSection = next.chaptersSection {
            value.chaptersSection = chaptersSection
        }
    }
}

public struct MangaChapterFramesPreferenceKey: PreferenceKey {
    public static var defaultValue: [String: CGRect] { [:] }

    public static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { _, latest in latest })
    }
}

public enum MangaDetailFormatting {
    public static func chapterTitleEmphasis(for item: UiMangaChapterItem) -> MangaChapterTitleEmphasis {
        if item.isCurrent {
            return .current
        }
        if item.isRead || item.chapter.locked {
            return .muted
        }
        return .unread
    }

    public static func resolveLockedChapterURL(chapter: UiMangaChapter, detailURL: String?) -> String? {
        chapter.openUrl(mangaUrl: detailURL)
    }

    public static func primaryReadButtonTitle(for target: PrimaryReadTarget) -> String {
        if target.isContinue {
            return FlareAppleUILocalization.string(
                "manga_detail_continue_chapter",
                fallback: "Continue: %@",
                arguments: [target.chapterTitle]
            )
        }
        if target.isSingleChapterWork {
            return FlareAppleUILocalization.string(
                "manga_detail_read",
                fallback: "Read"
            )
        }
        return FlareAppleUILocalization.string(
            "manga_detail_start_chapter",
            fallback: "Read: %@",
            arguments: [target.chapterTitle]
        )
    }
}

public struct MangaFlowLayout: Layout {
    public var horizontalSpacing: CGFloat
    public var verticalSpacing: CGFloat

    public init(horizontalSpacing: CGFloat = 6, verticalSpacing: CGFloat = 6) {
        self.horizontalSpacing = horizontalSpacing
        self.verticalSpacing = verticalSpacing
    }

    public func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        let rows = computeRows(maxWidth: maxWidth, subviews: subviews)
        let width: CGFloat
        if let proposedWidth = proposal.width, proposedWidth.isFinite {
            width = proposedWidth
        } else {
            width = rows.map(\.width).max() ?? 0
        }
        let height = rows.reduce(CGFloat.zero) { partial, row in
            partial + row.height
        } + CGFloat(max(rows.count - 1, 0)) * verticalSpacing
        return CGSize(width: width, height: height)
    }

    public func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let rows = computeRows(maxWidth: bounds.width, subviews: subviews)
        var currentY = bounds.minY
        for row in rows {
            var currentX = bounds.minX
            for item in row.items {
                let itemProposal = ProposedViewSize(
                    width: min(item.size.width, bounds.width),
                    height: item.size.height
                )
                item.subview.place(
                    at: CGPoint(x: currentX, y: currentY + (row.height - item.size.height) / 2),
                    anchor: .topLeading,
                    proposal: itemProposal
                )
                currentX += min(item.size.width, bounds.width) + horizontalSpacing
            }
            currentY += row.height + verticalSpacing
        }
    }

    private struct RowItem {
        let subview: LayoutSubview
        let size: CGSize
    }

    private struct Row {
        var items: [RowItem] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func computeRows(maxWidth: CGFloat, subviews: Subviews) -> [Row] {
        guard !subviews.isEmpty else { return [] }
        var rows: [Row] = []
        var currentRow = Row()

        for subview in subviews {
            let fitting = subview.sizeThatFits(
                ProposedViewSize(width: maxWidth.isFinite ? maxWidth : nil, height: nil)
            )
            let itemWidth = maxWidth.isFinite ? min(fitting.width, maxWidth) : fitting.width
            let nextWidth = currentRow.items.isEmpty ? itemWidth : currentRow.width + horizontalSpacing + itemWidth
            if !currentRow.items.isEmpty, maxWidth.isFinite, nextWidth > maxWidth + 0.5 {
                rows.append(currentRow)
                currentRow = Row(
                    items: [RowItem(subview: subview, size: fitting)],
                    width: itemWidth,
                    height: fitting.height
                )
            } else {
                currentRow.items.append(RowItem(subview: subview, size: fitting))
                currentRow.width = nextWidth
                currentRow.height = max(currentRow.height, fitting.height)
            }
        }
        if !currentRow.items.isEmpty {
            rows.append(currentRow)
        }
        return rows
    }
}

public struct MangaHeroCardView: View {
    @Environment(\.openURL) private var openURL
    public let detail: UiMangaDetail
    public let translation: (any MangaTranslatePresenterState)?
    public let primaryReadTarget: PrimaryReadTarget?
    public let onOpenCover: (UiMediaImage) -> Void
    public let onOpenProfile: (AccountType, MicroBlogKey) -> Void
    public let onOpenReader: (String, Int) -> Void

    public init(
        detail: UiMangaDetail,
        translation: (any MangaTranslatePresenterState)?,
        primaryReadTarget: PrimaryReadTarget?,
        onOpenCover: @escaping (UiMediaImage) -> Void,
        onOpenProfile: @escaping (AccountType, MicroBlogKey) -> Void,
        onOpenReader: @escaping (String, Int) -> Void
    ) {
        self.detail = detail
        self.translation = translation
        self.primaryReadTarget = primaryReadTarget
        self.onOpenCover = onOpenCover
        self.onOpenProfile = onOpenProfile
        self.onOpenReader = onOpenReader
    }

    private var combinedCreatorNames: String {
        var seen = Set<String>()
        var names: [String] = []
        for raw in detail.authors + detail.artists {
            if let cleaned = raw.trimmedNonBlank, seen.insert(cleaned).inserted {
                names.append(cleaned)
            }
        }
        return names.joined(separator: " · ")
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 14) {
                if let cover = detail.cover {
                    Button {
                        onOpenCover(cover)
                    } label: {
                        NetworkImage(data: cover, placeholder: cover.preview, contentMode: .fill)
                            .frame(width: 108, height: 162)
                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                            .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text(verbatim: detail.title))
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text(verbatim: detail.title)
                        .flarePrimaryText()
                        .font(.headline)
                        .fontWeight(.semibold)
                        .fixedSize(horizontal: false, vertical: true)

                    if let altTitle = detail.altTitles.compactMap(\.trimmedNonBlank).first {
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

                if detail.actions.isEmpty && (!(detail.bookmarkAction is ClickEventNoop) || detail.isBookmarked) {
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

            if !detail.actions.isEmpty {
                StatusActionsView(
                    data: detail.actions,
                    useText: false,
                    isDetail: true,
                    postKey: detail.mangaKey.description()
                )
            }
        }
        .padding(14)
        .mangaDetailCardSurface()
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

public struct MangaStatusBadgeView: View {
    public let status: UiMangaStatus

    public init(status: UiMangaStatus) {
        self.status = status
    }

    public var body: some View {
        Text(verbatim: status.localizedLabel)
            .font(.caption2.weight(.medium))
            .flarePrimaryText()
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Color.accentColor.opacity(0.15), in: Capsule())
    }
}

public struct MangaSynopsisContentView: View {
    public let content: UiRichText

    @State private var isSynopsisExpanded = false
    @State private var measuredSynopsisOverflow: Bool?

    public init(content: UiRichText) {
        self.content = content
    }

    public var body: some View {
        let plainText = content.innerText.trimmingCharacters(in: .whitespacesAndNewlines)
        if !plainText.isEmpty {
            let heuristicOverflow =
                content.renderRuns.count > 4 ||
                plainText.filter({ $0 == "\n" }).count >= 4 ||
                plainText.count > 220
            let canToggle = (measuredSynopsisOverflow ?? heuristicOverflow) || isSynopsisExpanded

            VStack(alignment: .leading, spacing: 6) {
                CollapsibleRichText(
                    text: content,
                    lineLimit: 4,
                    collapseThreshold: 4,
                    isExpanded: isSynopsisExpanded,
                    isTextSelectionEnabled: true
                ) { overflows in
                    if !isSynopsisExpanded {
                        measuredSynopsisOverflow = overflows
                    }
                }
                .lineSpacing(1.5)
                .frame(maxWidth: .infinity, alignment: .leading)

                if canToggle {
                    Button {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            isSynopsisExpanded.toggle()
                        }
                    } label: {
                        HStack(spacing: 4) {
                            Text(
                                verbatim: isSynopsisExpanded
                                    ? FlareAppleUILocalization.string("mastodon_item_show_less", fallback: "Show less")
                                    : FlareAppleUILocalization.string("mastodon_item_show_more", fallback: "Show more")
                            )
                            Image(systemName: isSynopsisExpanded ? "chevron.up" : "chevron.down")
                                .font(.caption2.weight(.semibold))
                        }
                        .font(.caption.weight(.medium))
                        .foregroundStyle(Color.accentColor)
                        .padding(.vertical, 2)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("manga_synopsis_toggle")
                }
            }
            .onChange(of: content.raw) { _, _ in
                isSynopsisExpanded = false
                measuredSynopsisOverflow = nil
            }
        }
    }
}

public struct MangaSynopsisAndTagsCardView: View {
    public let detail: UiMangaDetail
    public let onTagClick: ((UiMangaTag) -> Void)?

    public init(
        detail: UiMangaDetail,
        onTagClick: ((UiMangaTag) -> Void)? = nil
    ) {
        self.detail = detail
        self.onTagClick = onTagClick
    }

    private var hasSynopsis: Bool {
        detail.content?.innerText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
    }

    private var hasTags: Bool {
        detail.tagGroups.contains(where: { !$0.tags.isEmpty })
    }

    public var body: some View {
        if hasSynopsis || hasTags {
            ListCardView {
                VStack(alignment: .leading, spacing: 12) {
                    if let content = detail.content, hasSynopsis {
                        MangaSynopsisContentView(content: content)
                    }

                    MangaTagGroupsView(
                        tagGroups: detail.tagGroups,
                        onTagClick: onTagClick
                    )
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
}

public struct MangaTagGroupsView: View {
    @Environment(\.openURL) private var openURL
    public let tagGroups: [UiMangaTagGroup]
    public let onTagClick: ((UiMangaTag) -> Void)?

    public init(
        tagGroups: [UiMangaTagGroup],
        onTagClick: ((UiMangaTag) -> Void)? = nil
    ) {
        self.tagGroups = tagGroups
        self.onTagClick = onTagClick
    }

    private var nonEmptyGroups: [UiMangaTagGroup] {
        tagGroups.filter { !$0.tags.isEmpty }
    }

    public var body: some View {
        if !nonEmptyGroups.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(nonEmptyGroups, id: \.id) { group in
                    if let groupTitle = group.title?.text.trimmedNonBlank {
                        HStack(alignment: .top, spacing: 8) {
                            Text(verbatim: groupTitle)
                                .font(.caption.weight(.semibold))
                                .flareSecondaryText()
                                .frame(minWidth: 56, alignment: .leading)
                                .padding(.top, 5)

                            tagChips(for: group)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    } else {
                        tagChips(for: group)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    @ViewBuilder
    private func tagChips(for group: UiMangaTagGroup) -> some View {
        MangaFlowLayout(horizontalSpacing: 6, verticalSpacing: 6) {
            ForEach(Array(group.tags.enumerated()), id: \.offset) { _, tag in
                let isClickable = onTagClick != nil || !(tag.clickEvent is ClickEventNoop)
                if isClickable {
                    Button {
                        if let onTagClick {
                            onTagClick(tag)
                        } else {
                            tag.onClicked(ClickContext(launcher: AppleUriLauncher(openUrl: openURL)))
                        }
                    } label: {
                        tagChipLabel(tag.name)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("manga_tag_\(group.id)_\(tag.name)")
                } else {
                    tagChipLabel(tag.name)
                        .accessibilityIdentifier("manga_tag_\(group.id)_\(tag.name)")
                }
            }
        }
    }

    private func tagChipLabel(_ name: String) -> some View {
        Text(verbatim: name)
            .font(.caption)
            .foregroundStyle(Color.accentColor)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(Color.accentColor.opacity(0.14), in: Capsule())
    }
}

public struct MangaChaptersHeaderView: View {
    public let chapterGroups: [UiMangaChapterGroup]
    public let selectedGroupId: String?
    public let chapterSortDescending: Bool
    public let chapterCount: Int
    public let onSelectGroup: (String) -> Void
    public let onToggleSort: () -> Void

    public init(
        chapterGroups: [UiMangaChapterGroup],
        selectedGroupId: String?,
        chapterSortDescending: Bool,
        chapterCount: Int,
        onSelectGroup: @escaping (String) -> Void,
        onToggleSort: @escaping () -> Void
    ) {
        self.chapterGroups = chapterGroups
        self.selectedGroupId = selectedGroupId
        self.chapterSortDescending = chapterSortDescending
        self.chapterCount = chapterCount
        self.onSelectGroup = onSelectGroup
        self.onToggleSort = onToggleSort
    }

    private var showsGroupPills: Bool {
        chapterGroups.count > 1 || chapterGroups.contains { $0.title != nil }
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .center, spacing: 8) {
                let chaptersTitle = FlareAppleUILocalization.string(
                    "manga_detail_chapters_title",
                    fallback: "Chapters"
                )
                Text(verbatim: "\(chaptersTitle) (\(chapterCount))")
                    .flarePrimaryText()
                    .font(.headline)
                    .fontWeight(.semibold)

                Spacer(minLength: 8)

                Button(action: onToggleSort) {
                    HStack(spacing: 4) {
                        Image(systemName: chapterSortDescending ? "arrow.down" : "arrow.up")
                            .font(.caption.weight(.semibold))
                        Text(
                            verbatim: chapterSortDescending
                                ? FlareAppleUILocalization.string("manga_detail_sort_descending", fallback: "Descending")
                                : FlareAppleUILocalization.string("manga_detail_sort_ascending", fallback: "Ascending")
                        )
                        .font(.subheadline)
                    }
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.accentColor)
                .accessibilityIdentifier("manga_chapter_sort_toggle")
            }

            if showsGroupPills {
                MangaFlowLayout(horizontalSpacing: 8, verticalSpacing: 6) {
                    ForEach(chapterGroups, id: \.id) { group in
                        let label = group.title?.text.trimmedNonBlank ?? group.id
                        let isSelected = group.id == selectedGroupId
                        Button {
                            onSelectGroup(group.id)
                        } label: {
                            Text(verbatim: label)
                                .font(.caption.weight(isSelected ? .semibold : .regular))
                                .foregroundStyle(isSelected ? Color.white : Color.flareLabel)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(
                                    isSelected ? Color.accentColor : Color.flareSecondarySystemGroupedBackground,
                                    in: Capsule()
                                )
                                .overlay {
                                    Capsule()
                                        .strokeBorder(
                                            isSelected ? Color.clear : Color.flareSeparator.opacity(0.45),
                                            lineWidth: 1
                                        )
                                }
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("manga_chapter_group_\(group.id)")
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

public struct MangaChapterRowView: View {
    @Environment(\.openURL) private var openURL
    public let item: UiMangaChapterItem
    public let showsTrailingMenu: Bool
    public let detailURL: String?
    public let style: MangaChapterRowStyle
    public let onClick: () -> Void
    public let onSetRead: (Bool) -> Void

    public init(
        item: UiMangaChapterItem,
        showsTrailingMenu: Bool = true,
        detailURL: String? = nil,
        style: MangaChapterRowStyle = .groupedCard(index: 0, totalCount: 1),
        onClick: @escaping () -> Void,
        onSetRead: @escaping (Bool) -> Void
    ) {
        self.item = item
        self.showsTrailingMenu = showsTrailingMenu
        self.detailURL = detailURL
        self.style = style
        self.onClick = onClick
        self.onSetRead = onSetRead
    }

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

    public var body: some View {
        Group {
            switch style {
            case .groupedCard(let index, let totalCount):
                ListCardView(index: index, totalCount: totalCount) {
                    rowContent
                }
                .contextMenu {
                    menuActions
                }
                .accessibilityAction(named: Text(verbatim: markToggleLabel)) {
                    onSetRead(!item.isRead)
                }

            case .listPlain:
                rowContent
            }
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

    private var rowContent: some View {
        HStack(spacing: 8) {
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
                                if let subtitle = item.chapter.subtitle?.trimmedNonBlank {
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
                .padding(.leading, 16)
                .padding(.trailing, showsTrailingMenu ? 0 : 16)
                .padding(.vertical, 12)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if showsTrailingMenu {
                Menu {
                    menuActions
                } label: {
                    Image(fontAwesome: .ellipsisVertical)
                        .font(.subheadline)
                        .foregroundStyle(Color.flareSecondaryLabel)
                        .frame(width: 32, height: 32)
                        .contentShape(Rectangle())
                }
                .menuStyle(.borderlessButton)
                .fixedSize()
                .padding(.trailing, 8)
                .accessibilityIdentifier("manga_chapter_menu_\(item.chapter.id)")
                .accessibilityLabel(Text(verbatim: FlareAppleUILocalization.string("more", fallback: "More")))
            }
        }
    }

    @ViewBuilder
    private var menuActions: some View {
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
                let uri = DeeplinkRoute.OpenLinkDirectly(url: openUrl).toUri()
                if let url = URL(string: uri) {
                    openURL(url)
                } else if let direct = URL(string: openUrl) {
                    openURL(direct)
                }
            } label: {
                Label(
                    FlareAppleUILocalization.string("manga_open_in_browser", fallback: "Open in browser"),
                    systemImage: "safari"
                )
            }
        }
    }

    private var hasMetadataSubtitle: Bool {
        item.chapter.subtitle?.trimmedNonBlank != nil ||
            (!item.isRead && item.lastPageIndex > 0 && item.pageCount > 0) ||
            item.chapter.uploadedAt != nil
    }
}

public struct MangaChapterListSection: View {
    public let chapterGroups: [UiMangaChapterGroup]
    public let selectedGroupId: String?
    public let chapterSortDescending: Bool
    public let chapters: [UiMangaChapterItem]
    public let detailURL: String?
    public let onSelectGroup: (String) -> Void
    public let onToggleSort: () -> Void
    public let onChapterTap: (UiMangaChapter) -> Void
    public let onLockedChapterTap: (UiMangaChapter) -> Void
    public let onSetChapterRead: (String, Bool) -> Void

    public init(
        chapterGroups: [UiMangaChapterGroup],
        selectedGroupId: String?,
        chapterSortDescending: Bool,
        chapters: [UiMangaChapterItem],
        detailURL: String? = nil,
        onSelectGroup: @escaping (String) -> Void,
        onToggleSort: @escaping () -> Void,
        onChapterTap: @escaping (UiMangaChapter) -> Void,
        onLockedChapterTap: @escaping (UiMangaChapter) -> Void,
        onSetChapterRead: @escaping (String, Bool) -> Void
    ) {
        self.chapterGroups = chapterGroups
        self.selectedGroupId = selectedGroupId
        self.chapterSortDescending = chapterSortDescending
        self.chapters = chapters
        self.detailURL = detailURL
        self.onSelectGroup = onSelectGroup
        self.onToggleSort = onToggleSort
        self.onChapterTap = onChapterTap
        self.onLockedChapterTap = onLockedChapterTap
        self.onSetChapterRead = onSetChapterRead
    }

    public var body: some View {
        Group {
            if chapters.count > 80 {
                LazyVStack(alignment: .leading, spacing: 2) {
                    sectionContent
                }
            } else {
                VStack(alignment: .leading, spacing: 2) {
                    sectionContent
                }
            }
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

    @ViewBuilder
    private var sectionContent: some View {
        MangaChaptersHeaderView(
            chapterGroups: chapterGroups,
            selectedGroupId: selectedGroupId,
            chapterSortDescending: chapterSortDescending,
            chapterCount: chapters.count,
            onSelectGroup: onSelectGroup,
            onToggleSort: onToggleSort
        )
        .padding(.bottom, 6)

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
        } else {
            ForEach(Array(chapters.enumerated()), id: \.element.chapter.id) { index, item in
                MangaChapterRowView(
                    item: item,
                    detailURL: detailURL,
                    style: .groupedCard(index: index, totalCount: chapters.count),
                    onClick: {
                        if item.chapter.locked {
                            onLockedChapterTap(item.chapter)
                        } else {
                            onChapterTap(item.chapter)
                        }
                    },
                    onSetRead: { isRead in
                        onSetChapterRead(item.chapter.id, isRead)
                    }
                )
            }
        }
    }
}

public struct MangaBoundaryCardView: View {
    public let boundary: any ChapterBoundary
    public let isPrevious: Bool
    public let isStandaloneLocked: Bool
    public let onOpenUnlocked: (ChapterBoundaryUnlocked) -> Void
    public let onOpenLockedUrl: (String) -> Void
    public let actionIdentifier: String

    public init(
        boundary: any ChapterBoundary,
        isPrevious: Bool,
        isStandaloneLocked: Bool,
        onOpenUnlocked: @escaping (ChapterBoundaryUnlocked) -> Void,
        onOpenLockedUrl: @escaping (String) -> Void,
        actionIdentifier: String
    ) {
        self.boundary = boundary
        self.isPrevious = isPrevious
        self.isStandaloneLocked = isStandaloneLocked
        self.onOpenUnlocked = onOpenUnlocked
        self.onOpenLockedUrl = onOpenLockedUrl
        self.actionIdentifier = actionIdentifier
    }

    public var body: some View {
        if let unlocked = boundary as? ChapterBoundaryUnlocked {
            VStack(spacing: 12) {
                Image(systemName: "book.fill")
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(Color.accentColor)

                Text(
                    verbatim: FlareAppleUILocalization.string(
                        isPrevious ? "manga_boundary_previous_chapter" : "manga_boundary_next_chapter",
                        fallback: isPrevious ? "Previous chapter: %@" : "Next chapter: %@",
                        arguments: [unlocked.chapter.title]
                    )
                )
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

                Text(verbatim: unlocked.chapter.title)
                    .font(.headline)
                    .fontWeight(.semibold)
                    .foregroundStyle(.primary)
                    .multilineTextAlignment(.center)

                Button {
                    onOpenUnlocked(unlocked)
                } label: {
                    Text(
                        verbatim: FlareAppleUILocalization.string(
                            isPrevious ? "manga_boundary_read_previous" : "manga_boundary_read_next",
                            fallback: isPrevious ? "Read previous chapter" : "Read next chapter"
                        )
                    )
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier(actionIdentifier)
            }
            .padding(20)
            .frame(maxWidth: 360)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .padding(.horizontal, 24)
        } else if let locked = boundary as? ChapterBoundaryLocked {
            VStack(spacing: 12) {
                Image(fontAwesome: .lock)
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundStyle(.secondary)

                Text(
                    verbatim: isStandaloneLocked
                        ? FlareAppleUILocalization.string("manga_locked_chapter_title", fallback: "Locked chapter")
                        : FlareAppleUILocalization.string(
                            isPrevious ? "manga_boundary_previous_chapter" : "manga_boundary_next_chapter",
                            fallback: isPrevious ? "Previous chapter: %@" : "Next chapter: %@",
                            arguments: [locked.chapter.title]
                        )
                )
                .font(.headline)
                .fontWeight(.semibold)
                .foregroundStyle(.primary)
                .multilineTextAlignment(.center)

                Text(
                    verbatim: FlareAppleUILocalization.string(
                        "manga_locked_chapter_message",
                        fallback: "%@ is locked. Open in browser to view on the original site?",
                        arguments: [locked.chapter.title]
                    )
                )
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

                if let openUrl = locked.openUrl.trimmedNonBlank {
                    Button {
                        onOpenLockedUrl(openUrl)
                    } label: {
                        Text(
                            verbatim: FlareAppleUILocalization.string(
                                "manga_open_in_browser",
                                fallback: "Open in browser"
                            )
                        )
                    }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier(actionIdentifier)
                }
            }
            .padding(20)
            .frame(maxWidth: 360)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .padding(.horizontal, 24)
        }
    }
}

public nonisolated struct MangaReaderScrubberFrames: Equatable, Sendable {
    public var currentLabel: CGRect?
    public var totalLabel: CGRect?

    public init(currentLabel: CGRect? = nil, totalLabel: CGRect? = nil) {
        self.currentLabel = currentLabel
        self.totalLabel = totalLabel
    }
}

public struct MangaReaderScrubberFramesPreferenceKey: PreferenceKey {
    public static var defaultValue: MangaReaderScrubberFrames { MangaReaderScrubberFrames() }

    public static func reduce(value: inout MangaReaderScrubberFrames, nextValue: () -> MangaReaderScrubberFrames) {
        let next = nextValue()
        if let currentLabel = next.currentLabel {
            value.currentLabel = currentLabel
        }
        if let totalLabel = next.totalLabel {
            value.totalLabel = totalLabel
        }
    }
}

public struct MangaReaderScrubberView: View {
    public let pageCount: Int
    public let currentPageIndex: Int
    public let isRtl: Bool
    public let onPageSelected: (Int) -> Void

    @State private var dragValue: Double = 0
    @State private var isDragging = false

    public init(
        pageCount: Int,
        currentPageIndex: Int,
        isRtl: Bool,
        onPageSelected: @escaping (Int) -> Void
    ) {
        self.pageCount = pageCount
        self.currentPageIndex = currentPageIndex
        self.isRtl = isRtl
        self.onPageSelected = onPageSelected
    }

    private var maxPage: Int {
        max(pageCount - 1, 0)
    }

    private var displayedPage: Int {
        let raw = isDragging ? Int(dragValue.rounded()) : currentPageIndex
        return min(max(raw, 0), maxPage)
    }

    public var body: some View {
        HStack(spacing: 10) {
            Text(verbatim: "\(displayedPage + 1)")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.white)
                .background {
                    GeometryReader { proxy in
                        Color.clear.preference(
                            key: MangaReaderScrubberFramesPreferenceKey.self,
                            value: MangaReaderScrubberFrames(currentLabel: proxy.frame(in: .global))
                        )
                    }
                    .id(isRtl)
                }

            if maxPage > 0 {
                Slider(
                    value: Binding(
                        get: {
                            isDragging ? dragValue : Double(min(max(currentPageIndex, 0), maxPage))
                        },
                        set: { newValue in
                            let clampedPage = min(max(Int(newValue.rounded()), 0), maxPage)
                            dragValue = Double(clampedPage)
                            if clampedPage != currentPageIndex {
                                onPageSelected(clampedPage)
                            }
                        }
                    ),
                    in: 0...Double(max(maxPage, 1)),
                    step: 1,
                    onEditingChanged: { editing in
                        if editing {
                            dragValue = Double(min(max(currentPageIndex, 0), maxPage))
                            isDragging = true
                        } else {
                            let clampedPage = min(max(Int(dragValue.rounded()), 0), maxPage)
                            isDragging = false
                            if clampedPage != currentPageIndex {
                                onPageSelected(clampedPage)
                            }
                        }
                    }
                )
                .accessibilityIdentifier("manga_reader_page_slider")
                .accessibilityLabel(
                    Text(
                        verbatim: FlareAppleUILocalization.string(
                            "manga_reader_page_position",
                            fallback: "Page position"
                        )
                    )
                )
                .accessibilityValue(
                    Text(
                        verbatim: FlareAppleUILocalization.string(
                            "manga_reader_page_of",
                            fallback: "%1$lld of %2$lld",
                            arguments: [Int64(displayedPage + 1), Int64(pageCount)]
                        )
                    )
                )
            } else {
                Spacer(minLength: 0)
                    .accessibilityIdentifier("manga_reader_page_slider")
            }

            Text(verbatim: "\(pageCount)")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.white)
                .background {
                    GeometryReader { proxy in
                        Color.clear.preference(
                            key: MangaReaderScrubberFramesPreferenceKey.self,
                            value: MangaReaderScrubberFrames(totalLabel: proxy.frame(in: .global))
                        )
                    }
                    .id(isRtl)
                }
        }
        .environment(\.layoutDirection, isRtl ? .rightToLeft : .leftToRight)
        .onChange(of: currentPageIndex) { _, newIndex in
            if !isDragging {
                let target = Double(min(max(newIndex, 0), maxPage))
                if abs(dragValue - target) > 0.001 {
                    dragValue = target
                }
            }
        }
    }
}

public struct MangaReaderSettingsView: View {
    public let state: any MangaReaderPresenterState
    public let isWideViewport: Bool

    public init(state: any MangaReaderPresenterState, isWideViewport: Bool) {
        self.state = state
        self.isWideViewport = isWideViewport
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(
                verbatim: FlareAppleUILocalization.string(
                    "manga_reader_settings",
                    fallback: "Reader settings"
                )
            )
            .font(.headline)
            .fontWeight(.semibold)

            VStack(alignment: .leading, spacing: 8) {
                Text(
                    verbatim: FlareAppleUILocalization.string(
                        "manga_reader_direction",
                        fallback: "Reading direction"
                    )
                )
                .font(.subheadline)
                .foregroundStyle(.secondary)

                MangaFlowLayout(horizontalSpacing: 8, verticalSpacing: 6) {
                    let directions: [MangaReadingDirection] = [.rtl, .ltr, .webtoon]
                    ForEach(directions, id: \.name) { direction in
                        let isSelected = state.direction == direction
                        Button {
                            state.setDirection(direction: direction)
                        } label: {
                            Text(verbatim: direction.localizedLabel)
                                .font(.caption.weight(isSelected ? .semibold : .regular))
                                .foregroundStyle(isSelected ? Color.white : Color.primary)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(
                                    isSelected ? Color.accentColor : Color.secondary.opacity(0.16),
                                    in: Capsule()
                                )
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("manga_reader_direction_\(direction.name.lowercased())")
                    }
                }
            }

            if isWideViewport && state.direction != .webtoon {
                Toggle(
                    isOn: Binding(
                        get: { state.doublePageSpread },
                        set: { state.setDoublePageSpread(enabled: $0) }
                    )
                ) {
                    Text(
                        verbatim: FlareAppleUILocalization.string(
                            "manga_reader_double_page_spread",
                            fallback: "Double-page spread"
                        )
                    )
                }
                .accessibilityIdentifier("manga_reader_double_spread_toggle")

                if state.doublePageSpread {
                    Toggle(
                        isOn: Binding(
                            get: { state.spreadCoverSinglePage },
                            set: { state.setSpreadCoverSinglePage(enabled: $0) }
                        )
                    ) {
                        Text(
                            verbatim: FlareAppleUILocalization.string(
                                "manga_reader_spread_cover_single",
                                fallback: "Single cover page"
                            )
                        )
                    }
                    .accessibilityIdentifier("manga_reader_single_cover_toggle")
                }
            }
        }
        .padding(20)
        .frame(minWidth: 280, alignment: .leading)
    }
}

public func formatMangaReaderPageBadge(state: any MangaReaderPresenterState) -> String {
    let total = state.pages.count
    guard total > 0 else { return "" }
    if state.direction != .webtoon {
        let slotIdx = Int(state.currentSlotIndex)
        if state.slots.indices.contains(slotIdx),
           let doubleSlot = state.slots[slotIdx] as? ReaderSlotDouble {
            let first = Int(min(doubleSlot.leftPageIndex, doubleSlot.rightPageIndex)) + 1
            let second = Int(max(doubleSlot.leftPageIndex, doubleSlot.rightPageIndex)) + 1
            return "\(first)-\(second) / \(total)"
        }
    }
    return "\(Int(state.currentPageIndex) + 1) / \(total)"
}

private extension View {
    @ViewBuilder
    func mangaDetailCardSurface(cornerRadius: CGFloat = 14) -> some View {
        flareTextBackground(.flareSecondarySystemGroupedBackground)
            .background(
                Color.flareSecondarySystemGroupedBackground,
                in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            )
            #if os(macOS)
            .overlay {
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .stroke(Color.flareSeparator.opacity(0.45), lineWidth: 1)
            }
            #endif
    }
}

private extension String {
    var trimmedNonBlank: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
