import CoreGraphics
import SwiftUI
import XCTest
#if canImport(FlareAppleUI)
import FlareAppleUI
#endif

final class ProfileLayoutPolicyTests: XCTestCase {
    func testCardModeStartsWhenTheTimelineHasTwoColumns() {
        XCTAssertFalse(ProfileLayoutPolicy(width: 1_087).usesCard)
        XCTAssertTrue(ProfileLayoutPolicy(width: 1_088).usesCard)
    }

    func testUnfoldedLayoutPreservesTheTimelineFrame() {
        let layout = ProfileLayoutPolicy(width: 1_000)

        XCTAssertFalse(layout.usesCard)
        XCTAssertEqual(layout.profileWidth, 400)
        XCTAssertEqual(layout.columnSpacing, 8)
        XCTAssertEqual(layout.mode, .horizontalSplit(primaryWidth: 400, spacing: 8, isFoldDriven: false))
        XCTAssertEqual(1_000 - layout.profileWidth - layout.columnSpacing, 592)
    }

    func testBookFoldUsesBothEdgesOfTheReservedRegion() {
        let layout = ProfileLayoutPolicy(
            width: 944,
            division: CGRect(x: 456, y: 0, width: 32, height: 600)
        )

        XCTAssertFalse(layout.usesCard)
        XCTAssertEqual(layout.profileWidth, 456)
        XCTAssertEqual(layout.columnSpacing, 32)
        XCTAssertEqual(layout.mode, .horizontalSplit(primaryWidth: 456, spacing: 32, isFoldDriven: true))
        XCTAssertEqual(944 - layout.profileWidth - layout.columnSpacing, 456)
    }

    func testTrailingSystemBarDoesNotMoveTheSplitAwayFromTheFold() {
        let layout = ProfileLayoutPolicy(
            width: 880,
            division: CGRect(x: 456, y: 0, width: 32, height: 600)
        )

        XCTAssertEqual(layout.profileWidth, 456)
        XCTAssertEqual(layout.profileWidth + layout.columnSpacing, 488)
    }

    func testRightToLeftFoldAlignsWithAsymmetricReservedRegion() {
        let layout = ProfileLayoutPolicy(
            width: 880,
            division: CGRect(x: 456, y: 0, width: 32, height: 600),
            layoutDirection: .rightToLeft
        )

        XCTAssertEqual(layout.profileWidth, 392)
        XCTAssertEqual(layout.columnSpacing, 32)
        XCTAssertEqual(880 - layout.profileWidth - layout.columnSpacing, 456)
        XCTAssertEqual(880 - layout.profileWidth, 488)
    }

    func testMultipleTimelineColumnsKeepTheCardWhenVerticallyFolded() {
        let layout = ProfileLayoutPolicy(
            width: 1_440,
            division: CGRect(x: 704, y: 0, width: 32, height: 900)
        )

        XCTAssertTrue(layout.usesCard)
        XCTAssertEqual(layout.profileWidth, 400)
        XCTAssertEqual(layout.columnSpacing, 8)
        XCTAssertEqual(layout.mode, .horizontalSplit(primaryWidth: 400, spacing: 8, isFoldDriven: false))
    }

    func testHorizontalTabletopFoldSplitsTopAndBottomOnCompactAndWideScreens() {
        let compactTabletop = ProfileLayoutPolicy(
            size: CGSize(width: 390, height: 844),
            isRegularWidth: false,
            division: CGRect(x: 0, y: 406, width: 390, height: 32)
        )
        XCTAssertFalse(compactTabletop.usesCard)
        XCTAssertEqual(compactTabletop.mode, .verticalSplit(primaryHeight: 406, spacing: 32))

        let wideTabletop = ProfileLayoutPolicy(
            size: CGSize(width: 1_440, height: 900),
            isRegularWidth: true,
            division: CGRect(x: 0, y: 434, width: 1_440, height: 32)
        )
        XCTAssertFalse(wideTabletop.usesCard)
        XCTAssertEqual(wideTabletop.mode, .verticalSplit(primaryHeight: 434, spacing: 32))
    }

    func testZeroOutsideAndSliverDivisionsKeepTheUnfoldedLayout() {
        let unfolded = ProfileLayoutPolicy(size: CGSize(width: 944, height: 600), isRegularWidth: true)
        for division in [
            CGRect(x: 472, y: 0, width: 0, height: 600),
            CGRect(x: 0, y: 284, width: 944, height: 0),
            CGRect(x: -32, y: 0, width: 32, height: 600),
            CGRect(x: 944, y: 0, width: 32, height: 600),
            CGRect(x: 0, y: 100, width: 944, height: 32),
        ] {
            XCTAssertEqual(
                ProfileLayoutPolicy(size: CGSize(width: 944, height: 600), isRegularWidth: true, division: division),
                unfolded
            )
        }
    }

    func testTabPlacementAvoidsMissingOrDuplicatedTabsAcrossSplitModes() {
        let compactUnfolded = ProfileLayoutPolicy(size: CGSize(width: 390, height: 844), isRegularWidth: false)
        XCTAssertEqual(compactUnfolded.mode, .compact)
        XCTAssertFalse(compactUnfolded.showsInlinePaneTabs)
        XCTAssertFalse(compactUnfolded.showsToolbarInlineTabs)

        let regularUnfolded = ProfileLayoutPolicy(size: CGSize(width: 1_000, height: 800), isRegularWidth: true)
        XCTAssertFalse(regularUnfolded.showsInlinePaneTabs)
        XCTAssertTrue(regularUnfolded.showsToolbarInlineTabs)

        let compactBookFold = ProfileLayoutPolicy(
            size: CGSize(width: 720, height: 844),
            isRegularWidth: false,
            division: CGRect(x: 344, y: 0, width: 32, height: 844)
        )
        XCTAssertTrue(compactBookFold.showsInlinePaneTabs)
        XCTAssertFalse(compactBookFold.showsToolbarInlineTabs)

        let regularTabletopFold = ProfileLayoutPolicy(
            size: CGSize(width: 1_024, height: 768),
            isRegularWidth: true,
            division: CGRect(x: 0, y: 368, width: 1_024, height: 32)
        )
        XCTAssertTrue(regularTabletopFold.showsInlinePaneTabs)
        XCTAssertFalse(regularTabletopFold.showsToolbarInlineTabs)
    }
}
