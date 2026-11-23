import SwiftUI
#if canImport(FlareAppleUI)
import FlareAppleUI
#endif

nonisolated enum TimelineColumnPolicy {
    case adaptive
    case single

    func columnCount(for width: CGFloat) -> Int {
        guard case .adaptive = self, width.isFinite else { return 1 }
        let available = max(width - 32, 0)
        return max(Int((available + 8) / (320 + 8)), 1)
    }
}

nonisolated struct ProfileLayoutPolicy: Equatable {
    let mode: AdaptiveSplitMode
    let usesCard: Bool
    let profileWidth: CGFloat
    let columnSpacing: CGFloat

    init(
        size: CGSize,
        isRegularWidth: Bool = true,
        division: CGRect? = nil,
        layoutDirection: LayoutDirection = .leftToRight
    ) {
        // Classify before fold displacement so resizing a pane cannot switch modes.
        let multiColumnUnfolded = TimelineColumnPolicy.adaptive.columnCount(for: size.width - 400 - 8) > 1
        let policy = AdaptiveSplitLayoutPolicy(
            size: size,
            division: division,
            isUnfoldedSplitEligible: isRegularWidth,
            primaryWidthStrategy: .profileDefault,
            unfoldedSpacing: 8,
            layoutDirection: layoutDirection,
            skipVerticalFoldForUnfoldedRemainingWidth: { remainingWidth in
                TimelineColumnPolicy.adaptive.columnCount(for: remainingWidth) > 1
            }
        )
        self.mode = policy.mode
        switch policy.mode {
        case let .horizontalSplit(primaryWidth, spacing, _):
            self.usesCard = multiColumnUnfolded
            self.profileWidth = primaryWidth
            self.columnSpacing = spacing
        case .verticalSplit, .compact:
            self.usesCard = false
            self.profileWidth = 400
            self.columnSpacing = 8
        }
    }

    init(width: CGFloat, division: CGRect? = nil, layoutDirection: LayoutDirection = .leftToRight) {
        self.init(
            size: CGSize(width: width, height: 800),
            isRegularWidth: true,
            division: division,
            layoutDirection: layoutDirection
        )
    }
}
