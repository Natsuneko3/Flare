import SwiftUI
@preconcurrency import KotlinSharedUI
import FlareAppleCore

#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

public struct PluginWidgetStackView: View {
    private let cards: [UiPluginWidgetCard]
    private let postIdentity: String?

    public init(cards: [UiPluginWidgetCard], postIdentity: String? = nil) {
        self.cards = cards
        self.postIdentity = postIdentity
    }

    public var body: some View {
        if cards.isEmpty {
            EmptyView()
        } else if cards.count == 1 {
            PluginWidgetCardSurface(card: cards[0])
                .frame(maxWidth: .infinity, alignment: .leading)
                .environment(\.isPostContent, true)
        } else {
            PluginWidgetMultiCardPager(cards: cards)
                .id(postIdentity ?? "__unscoped_widget_stack__")
                .frame(maxWidth: .infinity, alignment: .leading)
                .environment(\.isPostContent, true)
        }
    }
}

private struct PluginWidgetHiddenSizingPassKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    fileprivate var isPluginWidgetHiddenSizingPass: Bool {
        get { self[PluginWidgetHiddenSizingPassKey.self] }
        set { self[PluginWidgetHiddenSizingPassKey.self] = newValue }
    }
}

private struct PluginWidgetMultiCardPager: View {
    let cards: [UiPluginWidgetCard]
    @State private var selectedCardID: String?

    private var cardIDs: [String] {
        Self.uniqueCardIdentities(for: cards)
    }

    private var currentPage: Int {
        guard let selectedCardID, let index = cardIDs.firstIndex(of: selectedCardID) else {
            return 0
        }
        return index
    }

    static func uniqueCardIdentities(for cards: [UiPluginWidgetCard]) -> [String] {
        var counts: [String: Int] = [:]
        counts.reserveCapacity(cards.count)
        return cards.map { card in
            let base = "\(card.pluginId):\(card.id)"
            let seen = counts[base, default: 0]
            counts[base] = seen + 1
            return seen == 0 ? base : "\(base)#\(seen)"
        }
    }

    var body: some View {
        let ids = cardIDs
        PluginWidgetEqualHeightPagerLayout(
            cardCount: cards.count,
            spacing: 8
        ) {
            ForEach(Array(cards.enumerated()), id: \.offset) { _, card in
                PluginWidgetCardSurface(card: card)
                    .environment(\.isPluginWidgetHiddenSizingPass, true)
                    .accessibilityHidden(true)
                    .allowsHitTesting(false)
                    .hidden()
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 8) {
                    ForEach(Array(zip(ids, cards)), id: \.0) { cardID, card in
                        PluginWidgetCardSurface(card: card)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                            .containerRelativeFrame(.horizontal, alignment: .topLeading)
                    }
                }
                .scrollTargetLayout()
            }
            .scrollTargetBehavior(.viewAligned)
            .scrollPosition(id: $selectedCardID)

            HStack(spacing: 6) {
                ForEach(0..<cards.count, id: \.self) { index in
                    Circle()
                        .fill(index == currentPage ? Color.accentColor : Color.flareSecondaryLabel.opacity(0.35))
                        .frame(width: 6, height: 6)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(verbatim: "Page \(currentPage + 1) of \(cards.count)"))
        }
        .onAppear {
            if selectedCardID == nil || !ids.contains(selectedCardID ?? "") {
                selectedCardID = ids.first
            }
        }
        .onChange(of: ids) { _, newIDs in
            guard let current = selectedCardID, newIDs.contains(current) else {
                selectedCardID = newIDs.first
                return
            }
        }
    }
}

private struct PluginWidgetEqualHeightPagerLayout: Layout {
    let cardCount: Int
    let spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard subviews.count >= cardCount + 2, cardCount > 0 else { return .zero }
        let targetWidth = proposal.width ?? subviews[0].sizeThatFits(.unspecified).width
        let cardHeight = (0..<cardCount).reduce(CGFloat(0)) { current, index in
            let size = subviews[index].sizeThatFits(ProposedViewSize(width: targetWidth, height: nil))
            return max(current, size.height.isFinite ? size.height : 0)
        }
        let dotsSize = subviews[cardCount + 1].sizeThatFits(ProposedViewSize(width: targetWidth, height: nil))
        return CGSize(
            width: targetWidth,
            height: ceil(cardHeight) + spacing + ceil(dotsSize.height)
        )
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard subviews.count >= cardCount + 2, cardCount > 0 else { return }
        let width = bounds.width
        let cardHeight = ceil(
            (0..<cardCount).reduce(CGFloat(0)) { current, index in
                let size = subviews[index].sizeThatFits(ProposedViewSize(width: width, height: nil))
                return max(current, size.height.isFinite ? size.height : 0)
            }
        )
        let cardProposal = ProposedViewSize(width: width, height: cardHeight)

        for index in 0..<cardCount {
            subviews[index].place(
                at: bounds.origin,
                anchor: .topLeading,
                proposal: ProposedViewSize(width: width, height: nil)
            )
        }

        subviews[cardCount].place(
            at: bounds.origin,
            anchor: .topLeading,
            proposal: cardProposal
        )

        let dotsSubview = subviews[cardCount + 1]
        let dotsSize = dotsSubview.sizeThatFits(ProposedViewSize(width: width, height: nil))
        dotsSubview.place(
            at: CGPoint(x: bounds.midX, y: bounds.minY + cardHeight + spacing),
            anchor: .top,
            proposal: ProposedViewSize(dotsSize)
        )
    }
}

private struct PluginWidgetCardSurface: View {
    let card: UiPluginWidgetCard
    @Environment(\.openURL) private var openURL
    @Environment(\.colorScheme) private var colorScheme

    private func handleAction(_ action: any UiWidgetAction) {
        executePluginWidgetAction(
            action,
            openURL: { url in openURL(url) },
            onRefresh: card.onRefresh
        )
    }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 12, style: .continuous)
        let borderColor: Color = if card.accentTone == .neutral {
            .flareSeparator
        } else {
            pluginWidgetToneColor(card.accentTone, colorScheme: colorScheme).opacity(0.42)
        }

        VStack(alignment: .leading, spacing: 10) {
            PluginWidgetCardHeader(card: card)

            VStack(alignment: .leading, spacing: 8) {
                ForEach(Array(card.body.enumerated()), id: \.offset) { _, node in
                    PluginWidgetNodeView(
                        node: node,
                        fillWidth: true,
                        onAction: handleAction
                    )
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if !card.actions.isEmpty {
                PluginWidgetFlowLayout(itemSpacing: 8, rowSpacing: 6) {
                    ForEach(Array(card.actions.enumerated()), id: \.offset) { _, button in
                        PluginWidgetActionButton(button: button) {
                            handleAction(button.action)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            shape.fill(Color.flareSecondarySystemBackground.opacity(0.55))
                .overlay {
                    shape.fill(Color.primary.opacity(0.025))
                }
        }
        .clipShape(shape)
        .overlay {
            shape.strokeBorder(borderColor, lineWidth: 1)
                .allowsHitTesting(false)
        }
        .contentShape(shape)
        .if(card.cardAction != nil) { view in
            view.onTapGesture {
                if let action = card.cardAction {
                    handleAction(action)
                }
            }
        }
    }
}

private struct PluginWidgetCardHeader: View {
    let card: UiPluginWidgetCard

    private var headerTitle: String {
        let trimmed = card.title.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? card.pluginName : card.title
    }

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            HStack(alignment: .center, spacing: 6) {
                if let rawPath = card.pluginIconPath?.trimmingCharacters(in: .whitespacesAndNewlines),
                   !rawPath.isEmpty {
                    PluginWidgetLocalIconView(path: rawPath, accessibilityLabel: card.pluginName)
                }
                Text(verbatim: headerTitle)
                    .font(.body.weight(.semibold))
                    .flarePrimaryText()
                    .lineLimit(1)
                    .truncationMode(.tail)
                if let badge = card.badge {
                    PluginWidgetBadgePill(badge: badge)
                        .fixedSize(horizontal: true, vertical: false)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if card.refreshable || card.isRefreshing {
                if card.isRefreshing {
                    ProgressView()
                        .controlSize(.mini)
                        .frame(width: 22, height: 22)
                        .accessibilityLabel(Text("Refresh"))
                } else {
                    Button {
                        card.onRefresh?()
                    } label: {
                        Image(fontAwesome: .arrowsRotate)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 12, height: 12)
                            .flareSecondaryText()
                            .frame(width: 22, height: 22)
                            .contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .disabled(card.onRefresh == nil)
                    .accessibilityLabel(Text("Refresh"))
                }
            }
        }
    }
}

private struct PluginWidgetLocalIconView: View {
    let path: String
    let accessibilityLabel: String

    private var resolvedFilePath: String {
        if path.hasPrefix("file://"), let url = URL(string: path) {
            return url.path
        }
        return path
    }

    var body: some View {
        #if os(iOS)
        if let image = UIImage(contentsOfFile: resolvedFilePath) {
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .frame(width: 16, height: 16)
                .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
                .accessibilityLabel(Text(verbatim: accessibilityLabel))
        }
        #elseif os(macOS)
        if let image = NSImage(contentsOfFile: resolvedFilePath) {
            Image(nsImage: image)
                .resizable()
                .scaledToFit()
                .frame(width: 16, height: 16)
                .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
                .accessibilityLabel(Text(verbatim: accessibilityLabel))
        }
        #endif
    }
}

private struct PluginWidgetNodeView: View {
    let node: UiWidgetNode
    let fillWidth: Bool
    let onAction: (any UiWidgetAction) -> Void

    var body: some View {
        switch onEnum(of: node) {
        case .text(let textNode):
            PluginWidgetTextNodeView(
                node: textNode,
                fillWidth: fillWidth,
                onCopy: { text in onAction(UiWidgetActionCopy(text: text, label: nil)) }
            )

        case .row(let rowNode):
            WidgetWeightedRowLayout(
                gap: rowNode.gap.points,
                align: rowNode.align,
                fillWidth: fillWidth
            ) {
                ForEach(Array(rowNode.children.enumerated()), id: \.offset) { _, child in
                    let rawWeight = child.weight?.floatValue
                    let validWeight: CGFloat? = if let w = rawWeight, w.isFinite, w > 0 {
                        CGFloat(w)
                    } else {
                        nil
                    }
                    PluginWidgetNodeView(
                        node: child,
                        fillWidth: validWeight != nil,
                        onAction: onAction
                    )
                    .layoutValue(key: WidgetRowWeightKey.self, value: validWeight)
                }
            }

        case .column(let columnNode):
            let alignment: HorizontalAlignment = switch columnNode.align {
            case .start, .spaceBetween: .leading
            case .center: .center
            case .end: .trailing
            }
            let frameAlignment: Alignment = switch columnNode.align {
            case .start, .spaceBetween: .leading
            case .center: .center
            case .end: .trailing
            }
            VStack(alignment: alignment, spacing: columnNode.gap.points) {
                ForEach(Array(columnNode.children.enumerated()), id: \.offset) { _, child in
                    PluginWidgetNodeView(
                        node: child,
                        fillWidth: fillWidth,
                        onAction: onAction
                    )
                }
            }
            .frame(maxWidth: fillWidth ? .infinity : nil, alignment: frameAlignment)

        case .stat(let statNode):
            PluginWidgetStatNodeView(node: statNode, fillWidth: fillWidth)

        case .matchup(let matchupNode):
            PluginWidgetMatchupNodeView(node: matchupNode, fillWidth: fillWidth)

        case .sparkline(let sparklineNode):
            PluginWidgetSparklineNodeView(node: sparklineNode, fillWidth: fillWidth)

        case .segmentedBar(let segmentedBarNode):
            PluginWidgetSegmentedBarNodeView(node: segmentedBarNode, fillWidth: fillWidth)

        case .keyValue(let keyValueNode):
            PluginWidgetKeyValueNodeView(
                node: keyValueNode,
                fillWidth: fillWidth,
                onAction: onAction
            )

        case .badgeRow(let badgeRowNode):
            PluginWidgetFlowLayout(itemSpacing: 6, rowSpacing: 6) {
                ForEach(Array(badgeRowNode.badges.enumerated()), id: \.offset) { _, badge in
                    PluginWidgetBadgePill(badge: badge)
                }
            }
            .frame(maxWidth: fillWidth ? .infinity : nil, alignment: .leading)

        case .image(let imageNode):
            PluginWidgetImageNodeView(node: imageNode, fillWidth: fillWidth)

        case .collapsible(let collapsibleNode):
            PluginWidgetCollapsibleNodeView(
                node: collapsibleNode,
                fillWidth: fillWidth,
                onAction: onAction
            )

        case .divider:
            if fillWidth {
                Rectangle()
                    .fill(Color.flareSeparator)
                    .frame(maxWidth: .infinity, minHeight: 1, maxHeight: 1)
            } else {
                Rectangle()
                    .fill(Color.flareSeparator)
                    .frame(width: 1, height: 16)
            }
        }
    }
}

nonisolated struct WidgetRowWeightKey: LayoutValueKey {
    static let defaultValue: CGFloat? = nil
}

private struct PluginWidgetFlowLayout: Layout {
    let itemSpacing: CGFloat
    let rowSpacing: CGFloat

    private func arrange(maxWidth: CGFloat?, subviews: Subviews) -> (frames: [CGRect], size: CGSize) {
        guard !subviews.isEmpty else { return ([], .zero) }
        let limit = maxWidth.flatMap { $0.isFinite && $0 > 0 ? $0 : nil }
        var frames: [CGRect] = []
        frames.reserveCapacity(subviews.count)
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var maxRowWidth: CGFloat = 0

        for subview in subviews {
            let measured = subview.sizeThatFits(ProposedViewSize(width: limit, height: nil))
            let width = max(0, measured.width.isFinite ? measured.width : 0)
            let height = max(0, measured.height.isFinite ? measured.height : 0)

            if let limit, x > 0, x + width > limit {
                maxRowWidth = max(maxRowWidth, x - itemSpacing)
                x = 0
                y += rowHeight + rowSpacing
                rowHeight = 0
            }
            frames.append(CGRect(x: x, y: y, width: width, height: height))
            x += width + itemSpacing
            rowHeight = max(rowHeight, height)
        }
        if x > 0 {
            maxRowWidth = max(maxRowWidth, x - itemSpacing)
        }
        return (frames, CGSize(width: maxRowWidth, height: y + rowHeight))
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        arrange(maxWidth: proposal.width, subviews: subviews).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let (frames, _) = arrange(maxWidth: bounds.width, subviews: subviews)
        for (index, subview) in subviews.enumerated() where index < frames.count {
            let frame = frames[index]
            subview.place(
                at: CGPoint(x: bounds.minX + frame.minX, y: bounds.minY + frame.minY),
                anchor: .topLeading,
                proposal: ProposedViewSize(frame.size)
            )
        }
    }
}

struct WidgetWeightedRowLayout: Layout {
    let gap: CGFloat
    let align: UiWidgetAlign
    let fillWidth: Bool

    private func computeSizes(
        proposal: ProposedViewSize,
        subviews: Subviews
    ) -> (sizes: [CGSize], totalWidth: CGFloat, maxHeight: CGFloat) {
        let count = subviews.count
        guard count > 0 else { return ([], 0, 0) }
        let totalGap = gap * CGFloat(max(0, count - 1))
        let boundedWidth = proposal.width.flatMap { $0.isFinite ? max($0, 0) : nil }
        var sizes = Array(repeating: CGSize.zero, count: count)

        var totalWeight: CGFloat = 0
        for subview in subviews {
            if let w = subview[WidgetRowWeightKey.self], w.isFinite, w > 0 {
                totalWeight += w
            }
        }

        var usedUnweightedWidth: CGFloat = 0
        for (index, subview) in subviews.enumerated() {
            if let w = subview[WidgetRowWeightKey.self], w.isFinite, w > 0 {
                continue
            }
            let maxAllowed = boundedWidth.map { max(0, $0 - totalGap - usedUnweightedWidth) }
            let ideal = subview.sizeThatFits(ProposedViewSize(width: nil, height: proposal.height))
            let targetW = maxAllowed.map { min(ideal.width, $0) } ?? ideal.width
            let fitted = targetW < ideal.width - 0.5
                ? subview.sizeThatFits(ProposedViewSize(width: targetW, height: proposal.height))
                : ideal
            sizes[index] = CGSize(
                width: max(0, fitted.width.isFinite ? fitted.width : 0),
                height: max(0, fitted.height.isFinite ? fitted.height : 0)
            )
            usedUnweightedWidth += sizes[index].width
        }

        if totalWeight > 0 {
            let remaining = max(0, (boundedWidth ?? (usedUnweightedWidth + 160 * CGFloat(count))) - totalGap - usedUnweightedWidth)
            for (index, subview) in subviews.enumerated() {
                guard let w = subview[WidgetRowWeightKey.self], w.isFinite, w > 0 else { continue }
                let childWidth = remaining * (w / totalWeight)
                let fitted = subview.sizeThatFits(ProposedViewSize(width: childWidth, height: proposal.height))
                sizes[index] = CGSize(
                    width: childWidth,
                    height: max(0, fitted.height.isFinite ? fitted.height : 0)
                )
            }
        }

        let contentWidth = sizes.reduce(CGFloat(0)) { $0 + $1.width } + totalGap
        let maxHeight = sizes.reduce(CGFloat(0)) { max($0, $1.height) }
        let finalWidth: CGFloat = if fillWidth, let boundedWidth {
            boundedWidth
        } else if let boundedWidth {
            min(contentWidth, boundedWidth)
        } else {
            contentWidth
        }
        return (sizes, finalWidth, maxHeight)
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let (_, totalWidth, maxHeight) = computeSizes(proposal: proposal, subviews: subviews)
        return CGSize(width: totalWidth, height: maxHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let count = subviews.count
        guard count > 0 else { return }
        let (sizes, _, _) = computeSizes(
            proposal: ProposedViewSize(width: bounds.width, height: proposal.height),
            subviews: subviews
        )
        let sumWidths = sizes.reduce(CGFloat(0)) { $0 + $1.width }
        let totalGap = gap * CGFloat(max(0, count - 1))
        let hasWeighted = subviews.contains { ($0[WidgetRowWeightKey.self] ?? 0) > 0 }

        var x: CGFloat
        var effectiveGap = gap

        switch align {
        case .start:
            x = bounds.minX
        case .center:
            x = bounds.minX + max(0, (bounds.width - (sumWidths + totalGap)) / 2)
        case .end:
            x = bounds.maxX - (sumWidths + totalGap)
        case .spaceBetween:
            x = bounds.minX
            if count > 1, !hasWeighted {
                effectiveGap = max(gap, (bounds.width - sumWidths) / CGFloat(count - 1))
            }
        }

        for (index, subview) in subviews.enumerated() {
            let size = sizes[index]
            let y = bounds.minY + (bounds.height - size.height) / 2
            subview.place(
                at: CGPoint(x: x, y: y),
                anchor: .topLeading,
                proposal: ProposedViewSize(size)
            )
            x += size.width + effectiveGap
        }
    }
}

private struct PluginWidgetTextNodeView: View {
    let node: UiWidgetNode.Text
    let fillWidth: Bool
    let onCopy: (String) -> Void
    @Environment(\.colorScheme) private var colorScheme

    private var resolvedLineLimit: Int? {
        node.maxLines.map { max(Int($0.intValue), 1) }
    }

    @ViewBuilder
    private func styledText(_ font: Font) -> some View {
        let base = Text(verbatim: node.text)
            .font(font)
        if node.tone != .neutral {
            base
                .foregroundStyle(pluginWidgetToneColor(node.tone, colorScheme: colorScheme))
                .lineLimit(resolvedLineLimit)
                .truncationMode(.tail)
                .fixedSize(horizontal: false, vertical: resolvedLineLimit == nil)
        } else if node.style == .caption {
            base
                .flareSecondaryText()
                .lineLimit(resolvedLineLimit)
                .truncationMode(.tail)
                .fixedSize(horizontal: false, vertical: resolvedLineLimit == nil)
        } else {
            base
                .flarePrimaryText()
                .lineLimit(resolvedLineLimit)
                .truncationMode(.tail)
                .fixedSize(horizontal: false, vertical: resolvedLineLimit == nil)
        }
    }

    var body: some View {
        if node.style == .code {
            let codeShape = RoundedRectangle(cornerRadius: 8, style: .continuous)
            let codeBody = HStack(alignment: .top, spacing: 8) {
                styledText(.caption.monospaced())
                    .frame(maxWidth: fillWidth ? .infinity : nil, alignment: .leading)
                if node.copyable {
                    Image(systemName: "doc.on.doc")
                        .font(.system(size: 11))
                        .flareSecondaryText()
                        .padding(.top, 2)
                        .accessibilityHidden(true)
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .background(codeShape.fill(Color.primary.opacity(0.06)))
            .overlay {
                codeShape.strokeBorder(Color.flareSeparator, lineWidth: 1)
                    .allowsHitTesting(false)
            }

            if node.copyable {
                Button {
                    onCopy(node.text)
                } label: {
                    codeBody.contentShape(codeShape)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(verbatim: "Copy \(node.text)"))
            } else {
                codeBody
            }
        } else {
            let font: Font = switch node.style {
            case .title: .headline.weight(.bold)
            case .subtitle: .body.weight(.semibold)
            case .body: .body
            case .caption, .code: .caption
            }

            if node.copyable {
                Button {
                    onCopy(node.text)
                } label: {
                    HStack(alignment: .center, spacing: 4) {
                        styledText(font)
                        Image(systemName: "doc.on.doc")
                            .font(.system(size: 11))
                            .flareSecondaryText()
                            .accessibilityHidden(true)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .frame(maxWidth: fillWidth ? .infinity : nil, alignment: .leading)
            } else {
                styledText(font)
                    .frame(maxWidth: fillWidth ? .infinity : nil, alignment: .leading)
            }
        }
    }
}

private struct PluginWidgetStatNodeView: View {
    let node: UiWidgetNode.Stat
    let fillWidth: Bool
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: node.label)
                .font(.caption)
                .flareSecondaryText()
                .lineLimit(1)
                .truncationMode(.tail)

            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(verbatim: node.value)
                    .font(.title3.weight(.bold))
                    .flarePrimaryText()
                    .lineLimit(1)
                    .truncationMode(.tail)

                if let delta = node.delta?.trimmingCharacters(in: .whitespacesAndNewlines), !delta.isEmpty {
                    if node.deltaTone == .neutral {
                        Text(verbatim: delta)
                            .font(.caption.weight(.semibold))
                            .flareSecondaryText()
                            .lineLimit(1)
                            .fixedSize(horizontal: true, vertical: false)
                    } else {
                        Text(verbatim: delta)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(pluginWidgetToneColor(node.deltaTone, colorScheme: colorScheme))
                            .lineLimit(1)
                            .fixedSize(horizontal: true, vertical: false)
                    }
                }
            }

            if let subtext = node.subtext?.trimmingCharacters(in: .whitespacesAndNewlines), !subtext.isEmpty {
                Text(verbatim: subtext)
                    .font(.caption)
                    .flareSecondaryText()
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
        }
        .frame(maxWidth: fillWidth ? .infinity : nil, alignment: .leading)
    }
}

private struct PluginWidgetMatchupNodeView: View {
    let node: UiWidgetNode.Matchup
    let fillWidth: Bool
    @Environment(\.colorScheme) private var colorScheme

    private var statusText: String {
        if let raw = node.status?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty {
            return raw
        }
        return "vs"
    }

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            PluginWidgetMatchupSideView(side: node.left, alignEnd: false)
                .frame(maxWidth: fillWidth ? .infinity : nil, alignment: .leading)

            Group {
                if node.status != nil, node.statusTone != .neutral {
                    Text(verbatim: statusText)
                        .foregroundStyle(pluginWidgetToneColor(node.statusTone, colorScheme: colorScheme))
                } else {
                    Text(verbatim: statusText)
                        .flareSecondaryText()
                }
            }
            .font(.caption.weight(.semibold))
            .lineLimit(1)
            .fixedSize(horizontal: true, vertical: false)

            PluginWidgetMatchupSideView(side: node.right, alignEnd: true)
                .frame(maxWidth: fillWidth ? .infinity : nil, alignment: .trailing)
        }
        .frame(maxWidth: fillWidth ? .infinity : nil, alignment: .leading)
    }
}

private struct PluginWidgetMatchupSideView: View {
    let side: UiWidgetNode.MatchupSide
    let alignEnd: Bool

    private var cleanIconUrl: String? {
        let trimmed = side.iconUrl?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? nil : trimmed
    }

    private var cleanScore: String? {
        let trimmed = side.score?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? nil : trimmed
    }

    private var cleanSubtitle: String? {
        let trimmed = side.subtitle?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? nil : trimmed
    }

    var body: some View {
        HStack(alignment: .center, spacing: 6) {
            if alignEnd, let score = cleanScore {
                Text(verbatim: score)
                    .font(.headline.weight(.bold))
                    .flarePrimaryText()
                    .lineLimit(1)
                    .fixedSize(horizontal: true, vertical: false)
            }

            if !alignEnd, let iconUrl = cleanIconUrl {
                NetworkImage(data: UiMediaImage(url: iconUrl, proxy: ProxyRef.Global.shared), contentMode: .fit)
                    .frame(width: 20, height: 20)
                    .clipShape(Circle())
            }

            VStack(alignment: alignEnd ? .trailing : .leading, spacing: 1) {
                Text(verbatim: side.name)
                    .font(.body.weight(.semibold))
                    .flarePrimaryText()
                    .lineLimit(1)
                    .truncationMode(.tail)
                if let subtitle = cleanSubtitle {
                    Text(verbatim: subtitle)
                        .font(.caption)
                        .flareSecondaryText()
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
            }

            if alignEnd, let iconUrl = cleanIconUrl {
                NetworkImage(data: UiMediaImage(url: iconUrl, proxy: ProxyRef.Global.shared), contentMode: .fit)
                    .frame(width: 20, height: 20)
                    .clipShape(Circle())
            }

            if !alignEnd, let score = cleanScore {
                Text(verbatim: score)
                    .font(.headline.weight(.bold))
                    .flarePrimaryText()
                    .lineLimit(1)
                    .fixedSize(horizontal: true, vertical: false)
            }
        }
    }
}

private struct PluginWidgetSparklineNodeView: View {
    let node: UiWidgetNode.Sparkline
    let fillWidth: Bool
    @Environment(\.colorScheme) private var colorScheme

    private var chartHeight: CGFloat {
        switch node.height {
        case .sm: 32
        case .md: 52
        }
    }

    private var lineColor: Color {
        node.tone == .neutral ? .accentColor : pluginWidgetToneColor(node.tone, colorScheme: colorScheme)
    }

    private var hasLabels: Bool {
        let minNonEmpty = !(node.minLabel?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
        let maxNonEmpty = !(node.maxLabel?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
        return minNonEmpty || maxNonEmpty
    }

    var body: some View {
        let normalized = node.normalizedPoints.map { value -> CGFloat in
            let raw = CGFloat(value.floatValue)
            return raw.isFinite ? min(max(raw, 0), 1) : 0.5
        }

        VStack(alignment: .leading, spacing: 2) {
            GeometryReader { proxy in
                let size = proxy.size
                let strokeWidth: CGFloat = 2
                let inset = strokeWidth
                let usableHeight = max(size.height - inset * 2, 0)

                let yFor: (CGFloat) -> CGFloat = { norm in
                    inset + (1 - norm) * usableHeight
                }

                if !normalized.isEmpty, size.width > 0, size.height > 0 {
                    if normalized.count == 1 {
                        Path { path in
                            let y = yFor(normalized[0])
                            path.move(to: CGPoint(x: 0, y: y))
                            path.addLine(to: CGPoint(x: size.width, y: y))
                        }
                        .stroke(lineColor, style: StrokeStyle(lineWidth: strokeWidth, lineCap: .round))
                    } else {
                        let stepX = size.width / CGFloat(normalized.count - 1)
                        ZStack {
                            Path { path in
                                for (index, value) in normalized.enumerated() {
                                    let x = CGFloat(index) * stepX
                                    let y = yFor(value)
                                    if index == 0 {
                                        path.move(to: CGPoint(x: x, y: size.height))
                                        path.addLine(to: CGPoint(x: x, y: y))
                                    } else {
                                        path.addLine(to: CGPoint(x: x, y: y))
                                    }
                                }
                                path.addLine(to: CGPoint(x: size.width, y: size.height))
                                path.closeSubpath()
                            }
                            .fill(
                                LinearGradient(
                                    colors: [lineColor.opacity(0.16), .clear],
                                    startPoint: .top,
                                    endPoint: .bottom
                                )
                            )

                            Path { path in
                                for (index, value) in normalized.enumerated() {
                                    let x = CGFloat(index) * stepX
                                    let y = yFor(value)
                                    if index == 0 {
                                        path.move(to: CGPoint(x: x, y: y))
                                    } else {
                                        path.addLine(to: CGPoint(x: x, y: y))
                                    }
                                }
                            }
                            .stroke(
                                lineColor,
                                style: StrokeStyle(lineWidth: strokeWidth, lineCap: .round, lineJoin: .round)
                            )
                        }
                    }
                }
            }
            .frame(height: chartHeight)
            .frame(maxWidth: .infinity)

            if hasLabels {
                HStack {
                    Text(verbatim: node.minLabel ?? "")
                        .font(.caption)
                        .flareSecondaryText()
                        .lineLimit(1)
                    Spacer(minLength: 4)
                    Text(verbatim: node.maxLabel ?? "")
                        .font(.caption)
                        .flareSecondaryText()
                        .lineLimit(1)
                }
            }
        }
        .frame(width: fillWidth ? nil : 88)
        .frame(maxWidth: fillWidth ? .infinity : nil, alignment: .leading)
    }
}

private struct PluginWidgetSegmentedBarNodeView: View {
    let node: UiWidgetNode.SegmentedBar
    let fillWidth: Bool
    @Environment(\.colorScheme) private var colorScheme

    private func segmentColor(at index: Int, segment: UiWidgetNode.SegmentedBarSegment) -> Color {
        if segment.tone != .neutral {
            return pluginWidgetToneColor(segment.tone, colorScheme: colorScheme)
        }
        return index == 0 ? .accentColor : Color.flareSecondaryLabel.opacity(0.55)
    }

    var body: some View {
        let fractions = node.normalizedFractions.map { value -> CGFloat in
            let raw = CGFloat(value.floatValue)
            return raw.isFinite ? min(max(raw, 0), 1) : 0
        }

        VStack(alignment: .leading, spacing: 6) {
            GeometryReader { proxy in
                let size = proxy.size
                if !fractions.isEmpty, size.width > 0, size.height > 0 {
                    let gap: CGFloat = fractions.count > 1 ? 2 : 0
                    let totalGap = gap * CGFloat(fractions.count - 1)
                    let availableWidth = max(size.width - totalGap, 0)

                    ZStack(alignment: .leading) {
                        ForEach(Array(fractions.enumerated()), id: \.offset) { index, fraction in
                            let precedingFraction = fractions.prefix(index).reduce(CGFloat(0), +)
                            let x = availableWidth * precedingFraction + gap * CGFloat(index)
                            let segWidth = availableWidth * fraction
                            if segWidth > 0, index < node.segments.count {
                                Path(
                                    roundedRect: CGRect(x: x, y: 0, width: segWidth, height: size.height),
                                    cornerRadius: 2
                                )
                                .fill(segmentColor(at: index, segment: node.segments[index]))
                            }
                        }
                    }
                }
            }
            .frame(height: 8)
            .frame(maxWidth: .infinity)
            .clipShape(Capsule())

            PluginWidgetFlowLayout(itemSpacing: 12, rowSpacing: 4) {
                ForEach(Array(node.segments.enumerated()), id: \.offset) { index, segment in
                    HStack(spacing: 4) {
                        Circle()
                            .fill(segmentColor(at: index, segment: segment))
                            .frame(width: 6, height: 6)
                        let valuePart = segment.valueText?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                        let labelText = valuePart.isEmpty ? segment.label : "\(segment.label) · \(valuePart)"
                        Text(verbatim: labelText)
                            .font(.caption)
                            .flarePrimaryText()
                            .lineLimit(1)
                            .truncationMode(.tail)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(width: fillWidth ? nil : 140)
        .frame(maxWidth: fillWidth ? .infinity : nil, alignment: .leading)
    }
}

private struct PluginWidgetMaxWidthCapLayout: Layout {
    let maxWidth: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let subview = subviews.first else { return .zero }
        let allowed = proposal.width.map { min($0, maxWidth) } ?? maxWidth
        let ideal = subview.sizeThatFits(ProposedViewSize(width: nil, height: proposal.height))
        if ideal.width <= allowed {
            return ideal
        }
        return subview.sizeThatFits(ProposedViewSize(width: allowed, height: proposal.height))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let subview = subviews.first else { return }
        subview.place(at: bounds.origin, anchor: .topLeading, proposal: ProposedViewSize(bounds.size))
    }
}

private struct PluginWidgetKeyValueNodeView: View {
    let node: UiWidgetNode.KeyValue
    let fillWidth: Bool
    let onAction: (any UiWidgetAction) -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(Array(node.items.enumerated()), id: \.offset) { _, item in
                let isOpenUrl: Bool = if let action = item.action, case .openUrl = onEnum(of: action) {
                    true
                } else {
                    false
                }
                let isCopy: Bool = if let action = item.action, case .copy = onEnum(of: action) {
                    true
                } else {
                    false
                }

                let rowContent = HStack(alignment: .center, spacing: 8) {
                    PluginWidgetMaxWidthCapLayout(maxWidth: fillWidth ? 140 : 110) {
                        Text(verbatim: item.label)
                            .font(.caption)
                            .flareSecondaryText()
                            .lineLimit(1)
                            .truncationMode(.tail)
                    }
                    .layoutPriority(1)

                    if fillWidth {
                        Spacer(minLength: 0)
                    }

                    HStack(alignment: .center, spacing: 4) {
                        Group {
                            if item.tone != .neutral {
                                Text(verbatim: item.value)
                                    .foregroundStyle(pluginWidgetToneColor(item.tone, colorScheme: colorScheme))
                            } else if isOpenUrl {
                                Text(verbatim: item.value)
                                    .foregroundStyle(Color.accentColor)
                                    .underline()
                            } else {
                                Text(verbatim: item.value)
                                    .flarePrimaryText()
                            }
                        }
                        .font(.body.weight(.medium))
                        .lineLimit(1)
                        .truncationMode(.tail)

                        if isCopy {
                            Image(systemName: "doc.on.doc")
                                .font(.system(size: 10))
                                .flareSecondaryText()
                                .fixedSize()
                                .accessibilityHidden(true)
                        }
                    }
                }
                .frame(maxWidth: fillWidth ? .infinity : nil, alignment: .leading)

                if let action = item.action {
                    Button {
                        onAction(action)
                    } label: {
                        rowContent
                            .padding(.vertical, 2)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                } else {
                    rowContent
                }
            }
        }
        .frame(maxWidth: fillWidth ? .infinity : nil, alignment: .leading)
    }
}

private struct PluginWidgetImageNodeView: View {
    let node: UiWidgetNode.Image
    let fillWidth: Bool
    @Environment(\.isPluginWidgetHiddenSizingPass) private var isHiddenSizingPass

    private var safeAspectRatio: CGFloat {
        if let raw = node.aspectRatio?.floatValue, raw.isFinite, raw > 0 {
            return CGFloat(raw)
        }
        return node.cornerStyle == .circle ? 1.0 : 16.0 / 9.0
    }

    var body: some View {
        let altText = node.alt?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

        Group {
            if fillWidth {
                PluginWidgetAspectCappedLayout(aspectRatio: safeAspectRatio, maxHeight: 200) {
                    if isHiddenSizingPass {
                        Color.clear
                    } else {
                        NetworkImage(
                            data: UiMediaImage(url: node.url, proxy: ProxyRef.Global.shared),
                            contentMode: .fill
                        )
                    }
                }
            } else {
                let compactHeight: CGFloat = node.cornerStyle == .circle ? 40 : 56
                Group {
                    if isHiddenSizingPass {
                        Color.clear
                    } else {
                        NetworkImage(
                            data: UiMediaImage(url: node.url, proxy: ProxyRef.Global.shared),
                            contentMode: .fill
                        )
                    }
                }
                .frame(width: compactHeight * safeAspectRatio, height: compactHeight)
            }
        }
        .clipShape(
            node.cornerStyle == .circle
                ? AnyShape(Circle())
                : AnyShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        )
        .if(!altText.isEmpty) { view in
            view.accessibilityLabel(Text(verbatim: altText))
        }
    }
}

private struct PluginWidgetAspectCappedLayout: Layout {
    let aspectRatio: CGFloat
    let maxHeight: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = max(proposal.width ?? 200, 1)
        let idealHeight = width / max(aspectRatio, 0.05)
        let height = min(idealHeight, maxHeight)
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let subview = subviews.first else { return }
        subview.place(at: bounds.origin, anchor: .topLeading, proposal: ProposedViewSize(bounds.size))
    }
}

private struct PluginWidgetCollapsibleNodeView: View {
    let node: UiWidgetNode.Collapsible
    let fillWidth: Bool
    let onAction: (any UiWidgetAction) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button {
                node.onToggle?()
            } label: {
                HStack(alignment: .center, spacing: 6) {
                    Image(fontAwesome: .chevronDown)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 10, height: 10)
                        .rotationEffect(.degrees(node.expanded ? 0 : -90))
                        .flareSecondaryText()
                    Text(verbatim: node.summary)
                        .font(.body.weight(.medium))
                        .flarePrimaryText()
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .frame(maxWidth: fillWidth ? .infinity : nil, alignment: .leading)
                }
                .padding(.vertical, 2)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if node.expanded {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(Array(node.children.enumerated()), id: \.offset) { _, child in
                        PluginWidgetNodeView(
                            node: child,
                            fillWidth: fillWidth,
                            onAction: onAction
                        )
                    }
                }
                .padding(.leading, 8)
                .frame(maxWidth: fillWidth ? .infinity : nil, alignment: .leading)
            }
        }
        .frame(maxWidth: fillWidth ? .infinity : nil, alignment: .leading)
    }
}

private struct PluginWidgetBadgePill: View {
    let badge: UiWidgetBadge
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let fg: Color = badge.tone == .neutral
            ? .flareSecondaryLabel
            : pluginWidgetToneColor(badge.tone, colorScheme: colorScheme)
        Text(verbatim: badge.text)
            .font(.caption.weight(.semibold))
            .foregroundStyle(fg)
            .lineLimit(1)
            .truncationMode(.tail)
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(Capsule().fill(fg.opacity(0.14)))
    }
}

private struct PluginWidgetActionButton: View {
    let button: UiWidgetButton
    let onTap: () -> Void
    @Environment(\.self) private var environment

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 8, style: .continuous)
        let isPrimary = button.style == .primary
        let resolvedColors = pluginWidgetButtonColors(
            style: button.style,
            tone: button.tone,
            in: environment
        )
        let borderStroke: Color? = if isPrimary {
            nil
        } else if button.tone == .neutral {
            .flareSeparator
        } else {
            pluginWidgetToneColor(button.tone, colorScheme: environment.colorScheme).opacity(0.32)
        }

        Button(action: onTap) {
            Text(verbatim: button.label)
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color(resolvedColors.foreground))
                .lineLimit(1)
                .truncationMode(.tail)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(shape.fill(Color(resolvedColors.background)))
                .overlay {
                    if let borderStroke {
                        shape.strokeBorder(borderStroke, lineWidth: 1)
                            .allowsHitTesting(false)
                    }
                }
                .contentShape(shape)
        }
        .buttonStyle(.plain)
    }
}

func executePluginWidgetAction(
    _ action: any UiWidgetAction,
    openURL: (URL) -> Void,
    onRefresh: (() -> Void)?
) {
    switch onEnum(of: action) {
    case .openUrl(let open):
        let trimmed = open.url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.lowercased().hasPrefix("https://") else {
            return
        }
        let authority = trimmed.dropFirst(8)
            .prefix { $0 != "/" && $0 != "?" && $0 != "#" }
        guard !authority.isEmpty,
              !authority.contains("@"),
              let url = URL(string: trimmed),
              url.scheme?.lowercased() == "https",
              url.host?.isEmpty == false,
              url.user == nil,
              url.password == nil else {
            return
        }
        openURL(url)

    case .copy(let copy):
        #if os(iOS)
        UIPasteboard.general.string = copy.text
        #elseif os(macOS)
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(copy.text, forType: .string)
        #endif

    case .refresh:
        onRefresh?()
    }
}

func pluginWidgetToneColor(_ tone: UiWidgetTone, colorScheme: ColorScheme) -> Color {
    switch tone {
    case .neutral:
        return .flareLabel
    case .accent:
        return .accentColor
    case .positive:
        return colorScheme == .dark
            ? Color(red: 0x3F / 255.0, green: 0xB9 / 255.0, blue: 0x50 / 255.0)
            : Color(red: 0x1B / 255.0, green: 0x7F / 255.0, blue: 0x3B / 255.0)
    case .negative:
        return colorScheme == .dark
            ? Color(red: 0xFF / 255.0, green: 0x6B / 255.0, blue: 0x6B / 255.0)
            : Color(red: 0xCF / 255.0, green: 0x22 / 255.0, blue: 0x2E / 255.0)
    case .warning:
        return colorScheme == .dark
            ? Color(red: 0xD2 / 255.0, green: 0x99 / 255.0, blue: 0x22 / 255.0)
            : Color(red: 0x9A / 255.0, green: 0x67 / 255.0, blue: 0x00 / 255.0)
    }
}

func pluginWidgetRelativeLuminance(_ resolved: Color.Resolved) -> Double {
    let r = Double(max(0, resolved.linearRed))
    let g = Double(max(0, resolved.linearGreen))
    let b = Double(max(0, resolved.linearBlue))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

func pluginWidgetWCAGContrastRatio(_ first: Color.Resolved, _ second: Color.Resolved) -> Double {
    let l1 = pluginWidgetRelativeLuminance(first)
    let l2 = pluginWidgetRelativeLuminance(second)
    let lighter = max(l1, l2)
    let darker = min(l1, l2)
    return (lighter + 0.05) / (darker + 0.05)
}

func pluginWidgetButtonColors(
    style: UiWidgetButtonStyle,
    tone: UiWidgetTone,
    in environment: EnvironmentValues
) -> (background: Color.Resolved, foreground: Color.Resolved) {
    let isPrimary = style == .primary
    let baseToneColor: Color = if tone == .neutral {
        isPrimary ? .accentColor : .flareLabel
    } else {
        pluginWidgetToneColor(tone, colorScheme: environment.colorScheme)
    }

    if isPrimary {
        var bgResolved = baseToneColor.resolve(in: environment)
        bgResolved.opacity = 1
        let white = Color.white.resolve(in: environment)
        let nearBlack = Color(red: 17.0 / 255.0, green: 17.0 / 255.0, blue: 17.0 / 255.0).resolve(in: environment)
        let pureBlack = Color.black.resolve(in: environment)

        let whiteContrast = pluginWidgetWCAGContrastRatio(bgResolved, white)
        let nearBlackContrast = pluginWidgetWCAGContrastRatio(bgResolved, nearBlack)
        if nearBlackContrast >= whiteContrast {
            let fg = nearBlackContrast >= 4.5 ? nearBlack : pureBlack
            return (bgResolved, fg)
        } else {
            return (bgResolved, white)
        }
    } else {
        let fgResolved = baseToneColor.resolve(in: environment)
        let alpha: Float = tone == .neutral ? 0.06 : 0.14
        var bgResolved = fgResolved
        bgResolved.opacity = alpha
        return (bgResolved, fgResolved)
    }
}

private extension UiWidgetGap {
    var points: CGFloat {
        switch self {
        case .xs: 4
        case .sm: 8
        case .md: 12
        }
    }
}
