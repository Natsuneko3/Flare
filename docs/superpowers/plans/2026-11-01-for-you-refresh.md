# For You Recommendation Refresh Implementation Plan

> **For agentic workers:** Use parallel bounded agents for network transport, batch selection and UI; root owns shared presenter/transaction integration and all Gradle/ADB commands. Follow test-first steps within each task.

**Goal:** Only standalone X For You pulls replace the list with fresh recommendations and bounded viewed-post deduplication.

**Architecture:** Opt-in source capability plus per-page session/cache wrapper; explicit pull action bypasses retained-history merging through a transactional mediator method. Existing refresh entry points stay unchanged.

**Tech Stack:** Kotlin Multiplatform, Compose, Room, Paging, coroutines, existing X GraphQL client.

**Spec:** `docs/superpowers/specs/2026-11-01-for-you-refresh-design.md` (user selected option 2 in chat).

## Global Constraints

- No shared default behavior changes; enable only a single-source XQT_FEATURED page.
- At most 200 viewed IDs, at most 3 page requests per manual pull; preserve X ordering and whole-page cursor consistency.
- No writes/clear until new nonempty accepted results; cancellation/failure/no-new keeps old content.
- Never mark prefetched, hidden-tab or background content as viewed.
- Preserve installed package/signature/data; use the established release workflow after verification.

## Review Focus

- Stale append, cancellation or another cache writer must not corrupt a committed batch.
- A filtered/all-duplicate result must never blank the page or loop requests.
- Reading-position restoration must not pull the newly replaced list back to an old anchor.
- A mixed feed containing the same X account must keep its cache/cursor and refresh policy.
- Fold/recreation and inactive tab precomposition must not mark unseen items.

## Task 1: X transport

- [x] Write failing request/response tests, add minimal `RecommendationRefreshSource.loadRecommendations(pageSize, cursor, seenPostIds)` stub; root runs `:social:xqt:jvmTest`.
- [x] Implement only the Featured loader capability; normal `load()` and HomeTimelineRequest defaults remain unchanged. Run request and configuration tests.

## Task 2: Bounded batch selection

- [x] Write failing `RecommendationBatchLoaderTest` cases for bounded viewed history, ordering, repeats, cursors, filters, cancellation and normal-load delegation; root runs `:shared:jvmTest --tests '*RecommendationBatchLoaderTest'`.
- [x] Implement `markViewed(items)` and `loadFresh(pageSize, accept): PagingResult?`, with separate page cache key. Verify tests.

## Task 3: Atomic replacement and presenter routing

- [x] Write failing Room mediator tests proving replacement despite retainHistory, old data retained on no-new/error, own-key isolation, and serialized append/cancellation behavior.
- [x] Implement `TimelineRemoteMediator.refreshRecommendations(...)`, explicit For You factory opt-in, presenter loading/outcome/viewed APIs; leave refresh/refreshAsync defaults untouched.
- [x] Run targeted tests plus existing shared timeline cache, gap, startup and position suites.

## Task 4: UI pull and actual viewing

- [x] Write tests for active viewport/header/key filtering and the pull routing contract, then run them red.
- [x] Wire only the pull gesture to `refreshForPull`, mark visible foreground posts after dwell, reset top after success and localize no-new/error feedback.
- [x] Verify Compose tests, app unit tests, Android compilation and focused device behavior.

## Task 5: Delivery

- [ ] Review final diff and behavior isolation. Increment custom release version, build signed APK, push source and publish verified assets.
- [ ] Install on Fold7 preserving data; verify For You pulls and ordinary timeline behavior. Record exactly which live and automated checks passed.

## Verified before release

- JVM/Android test suites: shared 759, X transport 72, Compose 125, app 85; zero failures.
- Actual Pager regressions cover completed/failed append, saved reading position, and UI cancellation after the replacement transaction commits.
- Debug APK assembled and installed over the existing package on Fold7 without clearing data. Two controlled For You pulls showed different first-screen posts at index zero; the ordinary X page also refreshed successfully.
- Release signing, asset read-back and final-device package verification follow the established `scripts/android_release.py` workflow.
