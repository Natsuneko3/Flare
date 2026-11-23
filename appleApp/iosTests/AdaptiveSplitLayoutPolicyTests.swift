import CoreGraphics
import SwiftUI
import XCTest
#if canImport(FlareAppleUI)
import FlareAppleUI
#endif

final class AdaptiveSplitLayoutPolicyTests: XCTestCase {
    func testZeroNegativeAndNonFiniteSizesReturnCompact() {
        for size in [
            CGSize.zero,
            CGSize(width: -100, height: 800),
            CGSize(width: 900, height: 0),
            CGSize(width: CGFloat.nan, height: 800),
            CGSize(width: CGFloat.infinity, height: 800),
            CGSize(width: 900, height: CGFloat.nan),
        ] {
            let policy = AdaptiveSplitLayoutPolicy(
                size: size,
                division: CGRect(x: 400, y: 0, width: 32, height: 800),
                isUnfoldedSplitEligible: true,
                primaryWidthStrategy: .postDetailDefault
            )
            XCTAssertEqual(policy.mode, .compact)
            XCTAssertFalse(policy.mode.isSplit)
        }
    }

    func testPostDetailProportionalWidthClampsBetween420And580() {
        let at840 = AdaptiveSplitLayoutPolicy(
            size: CGSize(width: 840, height: 800),
            isUnfoldedSplitEligible: true,
            primaryWidthStrategy: .postDetailDefault,
            unfoldedSpacing: 8
        )
        XCTAssertEqual(at840.mode, .horizontalSplit(primaryWidth: 420, spacing: 8, isFoldDriven: false))
        XCTAssertTrue(at840.mode.isSplit)

        let at1000 = AdaptiveSplitLayoutPolicy(
            size: CGSize(width: 1_000, height: 800),
            isUnfoldedSplitEligible: true,
            primaryWidthStrategy: .postDetailDefault,
            unfoldedSpacing: 8
        )
        XCTAssertEqual(at1000.mode, .horizontalSplit(primaryWidth: 460, spacing: 8, isFoldDriven: false))

        let at1400 = AdaptiveSplitLayoutPolicy(
            size: CGSize(width: 1_400, height: 800),
            isUnfoldedSplitEligible: true,
            primaryWidthStrategy: .postDetailDefault,
            unfoldedSpacing: 8
        )
        XCTAssertEqual(at1400.mode, .horizontalSplit(primaryWidth: 580, spacing: 8, isFoldDriven: false))

        let tooNarrowForTwoPanes = AdaptiveSplitLayoutPolicy(
            size: CGSize(width: 660, height: 800),
            isUnfoldedSplitEligible: true,
            primaryWidthStrategy: .postDetailDefault,
            unfoldedSpacing: 8
        )
        XCTAssertEqual(tooNarrowForTwoPanes.mode, .compact)
    }

    func testVerticalBookFoldAlignsInLeftToRightAndRightToLeft() {
        let ltr = AdaptiveSplitLayoutPolicy(
            size: CGSize(width: 944, height: 600),
            division: CGRect(x: 456, y: 0, width: 32, height: 600),
            isUnfoldedSplitEligible: false,
            primaryWidthStrategy: .postDetailDefault,
            layoutDirection: .leftToRight
        )
        XCTAssertEqual(ltr.mode, .horizontalSplit(primaryWidth: 456, spacing: 32, isFoldDriven: true))

        let rtl = AdaptiveSplitLayoutPolicy(
            size: CGSize(width: 880, height: 600),
            division: CGRect(x: 456, y: 0, width: 32, height: 600),
            isUnfoldedSplitEligible: false,
            primaryWidthStrategy: .postDetailDefault,
            layoutDirection: .rightToLeft
        )
        XCTAssertEqual(rtl.mode, .horizontalSplit(primaryWidth: 392, spacing: 32, isFoldDriven: true))
    }

    func testHorizontalTabletopFoldSplitsVerticallyEvenOnCompactOrWideScreen() {
        let compactTabletop = AdaptiveSplitLayoutPolicy(
            size: CGSize(width: 390, height: 844),
            division: CGRect(x: 0, y: 406, width: 390, height: 32),
            isUnfoldedSplitEligible: false,
            primaryWidthStrategy: .postDetailDefault
        )
        XCTAssertEqual(compactTabletop.mode, .verticalSplit(primaryHeight: 406, spacing: 32))

        let wideTabletop = AdaptiveSplitLayoutPolicy(
            size: CGSize(width: 1_200, height: 800),
            division: CGRect(x: 0, y: 384, width: 1_200, height: 32),
            isUnfoldedSplitEligible: true,
            primaryWidthStrategy: .postDetailDefault
        )
        XCTAssertEqual(wideTabletop.mode, .verticalSplit(primaryHeight: 384, spacing: 32))
    }

    func testEdgeSliverDivisionsFallBackToUnfoldedMode() {
        let verticalNearEdge = AdaptiveSplitLayoutPolicy(
            size: CGSize(width: 900, height: 700),
            division: CGRect(x: 180, y: 0, width: 32, height: 700),
            isUnfoldedSplitEligible: true,
            primaryWidthStrategy: .postDetailDefault,
            unfoldedSpacing: 8
        )
        XCTAssertEqual(verticalNearEdge.mode, .horizontalSplit(primaryWidth: 420, spacing: 8, isFoldDriven: false))

        let horizontalNearEdge = AdaptiveSplitLayoutPolicy(
            size: CGSize(width: 900, height: 700),
            division: CGRect(x: 0, y: 120, width: 900, height: 32),
            isUnfoldedSplitEligible: false,
            primaryWidthStrategy: .postDetailDefault
        )
        XCTAssertEqual(horizontalNearEdge.mode, .compact)
    }

    func testStatusDetailPanePolicyAvoidsDuplicateHeaderAndMirroredErrors() {
        let initialError = StatusDetailPanePolicy(
            hasHeader: false,
            isListEmpty: false,
            tabsEmpty: false,
            isFilteredEmpty: false,
            isCurrentError: true
        )
        XCTAssertFalse(initialError.compactUsesHeaderState)
        XCTAssertFalse(initialError.hidesCompactList)
        XCTAssertTrue(initialError.hidesSplitSecondaryList)

        let initialLoadingWithCatalogError = StatusDetailPanePolicy(
            hasHeader: false,
            isListEmpty: false,
            tabsEmpty: true,
            isFilteredEmpty: false,
            isCurrentError: false
        )
        XCTAssertFalse(initialLoadingWithCatalogError.compactUsesHeaderState)
        XCTAssertFalse(initialLoadingWithCatalogError.hidesSplitSecondaryList)

        let untabbedPostWithoutReplies = StatusDetailPanePolicy(
            hasHeader: true,
            isListEmpty: true,
            tabsEmpty: true,
            isFilteredEmpty: false,
            isCurrentError: false
        )
        XCTAssertTrue(untabbedPostWithoutReplies.hidesCompactList)
        XCTAssertTrue(untabbedPostWithoutReplies.hidesSplitSecondaryList)
    }
}
