import FlareAppleCore
import SwiftUI
import XCTest

@MainActor
final class ProfileTabSelectionTests: XCTestCase {
    func testMediaStaysSelectedWhenHighlightsIsInsertedMovedOrRemoved() {
        let cached = ["posts", "media"]
        let refreshed = ["posts", "highlights", "media"]
        let reordered = ["media", "posts", "highlights"]

        var selection = ProfileTabSelection()
        selection.update(availableIDs: cached)
        let picker = Binding(get: { selection }, set: { selection = $0 })
        picker[indexIn: cached].wrappedValue = 1

        XCTAssertEqual(selection[indexIn: refreshed], 2)
        selection.update(availableIDs: refreshed)
        XCTAssertEqual(selection[indexIn: reordered], 0)
        selection.update(availableIDs: reordered)
        XCTAssertEqual(selection[indexIn: cached], 1)
    }

    func testRemovedSelectionFallsBackToFirstTabEvenWhenItReturns() {
        let refreshed = ["posts", "highlights", "media"]
        let remaining = ["posts", "media"]

        var selection = ProfileTabSelection()
        selection[indexIn: refreshed] = 1
        XCTAssertEqual(selection[indexIn: remaining], 0)
        selection.update(availableIDs: remaining)
        XCTAssertEqual(selection[indexIn: ["media", "highlights", "posts"]], 2)
    }

    func testEmptyTabsClearSelectionAndReloadStartsAtTheFirstTab() {
        var selection = ProfileTabSelection()
        selection[indexIn: ["posts", "media"]] = 1
        selection.update(availableIDs: [])
        selection[indexIn: []] = 0

        let reloaded = ["replies", "media"]
        XCTAssertEqual(selection[indexIn: reloaded], 0)
        selection.update(availableIDs: reloaded)
        XCTAssertEqual(selection[indexIn: ["media", "replies"]], 1)
    }
}
