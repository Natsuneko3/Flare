import CoreGraphics
import SwiftUI

nonisolated public enum AdaptivePrimaryWidthStrategy: Equatable, Sendable {
    case fixed(CGFloat)
    case proportional(fraction: CGFloat, min: CGFloat, max: CGFloat)

    public static let postDetailDefault: Self = .proportional(fraction: 0.46, min: 420, max: 580)
    public static let profileDefault: Self = .fixed(400)

    public func width(for containerWidth: CGFloat) -> CGFloat {
        guard containerWidth.isFinite, containerWidth > 0 else { return 0 }
        switch self {
        case let .fixed(width):
            return max(min(width, containerWidth), 0)
        case let .proportional(fraction, minWidth, maxWidth):
            let raw = containerWidth * fraction
            let clamped = min(max(raw, minWidth), maxWidth)
            return max(min(clamped, containerWidth), 0)
        }
    }
}

nonisolated public enum AdaptiveSplitMode: Equatable, Sendable {
    case compact
    case horizontalSplit(primaryWidth: CGFloat, spacing: CGFloat, isFoldDriven: Bool)
    case verticalSplit(primaryHeight: CGFloat, spacing: CGFloat)

    public var isSplit: Bool {
        if case .compact = self { return false }
        return true
    }
}

nonisolated public struct AdaptiveSplitLayoutPolicy: Equatable, Sendable {
    public let mode: AdaptiveSplitMode

    public init(
        size: CGSize,
        division: CGRect? = nil,
        isUnfoldedSplitEligible: Bool,
        primaryWidthStrategy: AdaptivePrimaryWidthStrategy,
        unfoldedSpacing: CGFloat = 8,
        layoutDirection: LayoutDirection = .leftToRight,
        skipVerticalFoldForUnfoldedRemainingWidth: ((CGFloat) -> Bool)? = nil,
        minPaneWidth: CGFloat = 240,
        minPaneHeight: CGFloat = 160
    ) {
        guard size.width.isFinite, size.height.isFinite, size.width > 0, size.height > 0 else {
            self.mode = .compact
            return
        }

        if let division,
           division.minX.isFinite, division.minY.isFinite,
           division.width.isFinite, division.height.isFinite,
           division.height >= 0, division.width > division.height {
            let topHeight = division.minY
            let bottomHeight = size.height - division.maxY
            if topHeight >= minPaneHeight, bottomHeight >= minPaneHeight {
                self.mode = .verticalSplit(primaryHeight: topHeight, spacing: division.height)
                return
            }
        }

        let unfoldedPrimary = primaryWidthStrategy.width(for: size.width)
        let safeUnfoldedSpacing = max(unfoldedSpacing, 0)
        let skipVerticalFold = skipVerticalFoldForUnfoldedRemainingWidth?(
            size.width - unfoldedPrimary - safeUnfoldedSpacing
        ) ?? false

        if !skipVerticalFold,
           let division,
           division.minX.isFinite, division.minY.isFinite,
           division.width.isFinite, division.height.isFinite,
           division.width >= 0, division.height > division.width {
            let leftWidth = division.minX
            let rightWidth = size.width - division.maxX
            if leftWidth >= minPaneWidth, rightWidth >= minPaneWidth {
                let primaryWidth = layoutDirection == .rightToLeft ? rightWidth : leftWidth
                self.mode = .horizontalSplit(
                    primaryWidth: primaryWidth,
                    spacing: division.width,
                    isFoldDriven: true
                )
                return
            }
        }

        if isUnfoldedSplitEligible,
           unfoldedPrimary >= minPaneWidth,
           size.width - unfoldedPrimary - safeUnfoldedSpacing >= minPaneWidth {
            self.mode = .horizontalSplit(
                primaryWidth: unfoldedPrimary,
                spacing: safeUnfoldedSpacing,
                isFoldDriven: false
            )
            return
        }

        self.mode = .compact
    }
}

public extension GeometryProxy {
    var activeDivisionFrame: CGRect? {
        #if os(iOS)
        if #available(iOS 27.1, *) {
            // The split policy takes physical coordinates and resolves the leading pane.
            return reservedRegions(kind: .division, layoutDirectionBehavior: .fixed).first?.frame
        }
        #endif
        return nil
    }
}

public struct AdaptiveSplitContainer<Primary: View, Secondary: View, Separator: View>: View {
    @Environment(\.layoutDirection) private var layoutDirection
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private let isUnfoldedSplitEligible: (CGSize) -> Bool
    private let primaryWidthStrategy: AdaptivePrimaryWidthStrategy
    private let unfoldedSpacing: CGFloat
    private let separator: (AdaptiveSplitMode) -> Separator
    private let primaryPane: (AdaptiveSplitMode) -> Primary
    private let secondaryPane: (AdaptiveSplitMode) -> Secondary

    public init(
        isUnfoldedSplitEligible: @escaping (CGSize) -> Bool,
        primaryWidthStrategy: AdaptivePrimaryWidthStrategy,
        unfoldedSpacing: CGFloat = 8,
        @ViewBuilder separator: @escaping (AdaptiveSplitMode) -> Separator,
        @ViewBuilder primaryPane: @escaping (AdaptiveSplitMode) -> Primary,
        @ViewBuilder secondaryPane: @escaping (AdaptiveSplitMode) -> Secondary
    ) {
        self.isUnfoldedSplitEligible = isUnfoldedSplitEligible
        self.primaryWidthStrategy = primaryWidthStrategy
        self.unfoldedSpacing = unfoldedSpacing
        self.separator = separator
        self.primaryPane = primaryPane
        self.secondaryPane = secondaryPane
    }

    public var body: some View {
        GeometryReader { proxy in
            let division = proxy.activeDivisionFrame
            let policy = AdaptiveSplitLayoutPolicy(
                size: proxy.size,
                division: division,
                isUnfoldedSplitEligible: isUnfoldedSplitEligible(proxy.size),
                primaryWidthStrategy: primaryWidthStrategy,
                unfoldedSpacing: unfoldedSpacing,
                layoutDirection: layoutDirection
            )
            let mode = policy.mode
            let (primaryWidth, primaryHeight, separatorWidth, separatorHeight): (CGFloat?, CGFloat?, CGFloat?, CGFloat?) = switch mode {
            case .compact:
                (nil, nil, nil, nil)
            case let .horizontalSplit(width, spacing, _):
                (width, nil, spacing, nil)
            case let .verticalSplit(height, spacing):
                (nil, height, nil, spacing)
            }
            let layout = primaryHeight == nil ? AnyLayout(HStackLayout(spacing: 0)) : AnyLayout(VStackLayout(spacing: 0))
            layout {
                if mode.isSplit {
                    primaryPane(mode)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .frame(width: primaryWidth, height: primaryHeight)
                    separator(mode)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .frame(width: separatorWidth, height: separatorHeight)
                }
                // Keep the scrolling view's identity as its pane changes.
                secondaryPane(mode)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: division)
        }
    }
}
