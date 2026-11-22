import FlareAppleCore
import UIKit
import XCTest
@testable import Flare

@MainActor
final class ProfileTimelinePagerTests: XCTestCase {
    func testRefreshingTabsKeepsMediaVisibleAndSwipingSelectsItsNewNeighbor() throws {
        let pager = ProfileTimelinePagerViewController()
        let posts = page("posts")
        let media = page("media")
        let highlights = page("highlights")
        var selection = ProfileTabSelection()
        selection[indexIn: [posts.id, media.id]] = 1
        var pagePosition: CGFloat?
        pager.onPagePositionChanged = { pagePosition = $0 }

        func update(_ pages: [ProfileTimelinePageConfiguration]) {
            let ids = pages.map(\.id)
            pager.onSelectedIndexChanged = { selection[indexIn: ids] = $0 }
            pager.setPages(pages, selectedIndex: selection[indexIn: ids])
            selection.update(availableIDs: ids)
        }

        update([posts, media])
        let nativePager = try XCTUnwrap(pager.children.first as? UIPageViewController)
        let mediaPage = try XCTUnwrap(nativePager.viewControllers?.first)
        let postsPage = try XCTUnwrap(pager.pageViewController(nativePager, viewControllerBefore: mediaPage))
        pager.pageViewController(nativePager, willTransitionTo: [postsPage])

        update([posts, highlights, media])
        XCTAssertIdentical(nativePager.viewControllers?.first, mediaPage)
        XCTAssertEqual(pagePosition, 2)
        let highlightsPage = try XCTUnwrap(pager.pageViewController(nativePager, viewControllerBefore: mediaPage))
        XCTAssertFalse(highlightsPage === postsPage)
        pager.pageViewController(nativePager, willTransitionTo: [highlightsPage])
        nativePager.setViewControllers([highlightsPage], direction: .reverse, animated: false)
        pager.pageViewController(nativePager, didFinishAnimating: true, previousViewControllers: [mediaPage], transitionCompleted: true)
        XCTAssertEqual(selection[indexIn: [media.id, posts.id, highlights.id]], 2)

        update([posts, media])
        XCTAssertIdentical(nativePager.viewControllers?.first, postsPage)
        XCTAssertIdentical(pager.pageViewController(nativePager, viewControllerAfter: postsPage), mediaPage)
        update([])
        XCTAssertTrue(nativePager.viewControllers?.first?.children.isEmpty ?? true)
        update([highlights, posts, media])
        let firstPage = try XCTUnwrap(nativePager.viewControllers?.first)
        XCTAssertNil(pager.pageViewController(nativePager, viewControllerBefore: firstPage))
    }

    private func page(_ id: String) -> ProfileTimelinePageConfiguration {
        let timeline = UITimelineCollectionViewController(detailStatusKey: nil)
        return ProfileTimelinePageConfiguration(id: id) { timeline }
    }
}
