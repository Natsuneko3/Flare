# X Manual Category Tabs Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for implementation. The user's instruction to skip compilation and testing takes precedence over its execution gates.

> **Validation follow-up (2026-11-06):** The user subsequently authorized compilation, testing, fixes, commit and push for the upstream integration, AI defaults and X category tabs together. The original no-build/no-test constraints below describe the implementation phase only. Application packaging remains excluded.

**Goal:** Let users add X category timelines such as J-Pop, Business and Technology through the existing account tab picker, with visible loading and recoverable failures.

**Architecture:** Reuse the existing X category catalog, `xqt.tag` timeline spec, account-scoped identifiers and tab persistence. Complete the catalog and timeline error handling and the existing Android/desktop picker states; do not add remote pinned-tab synchronization.

**Tech Stack:** Kotlin Multiplatform, kotlinx.serialization, Ktorfit, AndroidX Paging, Compose.

**Spec:** The user selected option 1 (manual addition using the existing X account's Discover section) in this chat and requested code implementation without compilation or testing.

## Global Constraints

- Do not compile, run tests, launch the app or perform live endpoint tests in this task.
- Preserve existing user code and existing tests; create no temporary test sources.
- Keep the service-provided category identifiers and localized labels; do not hardcode this account or the three categories.
- Reuse the current tab/group persistence and keep changes in the current workspace, uncommitted.

## Review Focus

- HTTP failures or missing catalog data must reach the picker error/retry state.
- Valid empty catalogs must remain empty; GraphQL errors must not look like success.
- Blank identifiers/names and duplicate category identifiers must not become unusable/duplicate choices.
- Prefer a nonblank `tab_label` (Business) while accepting a valid full name (Business & Finance).
- Category timeline failures must not appear as an empty end of feed; cursor-bearing pages must retain pagination.

## Task 1: Complete the existing category data path

**Files:** `PinnedTimelinesManagementSheetResponse.kt`, `XQTPinnableTimelinePagingSource.kt`, `TagTimelineRemoteMediator.kt` under `social/xqt/src/commonMain/kotlin/dev/dimension/flare/data/`.

- [x] Retain GraphQL errors in the catalog response model; filter invalid category entries and deduplicate by tag without changing source order.
- [x] Check catalog HTTP success, body, errors and required catalog container before returning candidates.
- [x] Apply the same HTTP/body/error checks to category content loading and determine continuation from the cursor.

## Task 2: Expose picker loading, empty and retry states

**Files:** `desktopApp/src/main/kotlin/dev/dimension/flare/ui/screen/home/AddTabDialog.kt`, `app/src/main/java/dev/dimension/flare/ui/screen/settings/TabAddBottomSheet.kt`.

- [x] Supply loading/error/empty content to the existing paging list and connect retry to the selected section.
- [x] Reuse existing progress, error and empty-state components; retain current manual add/remove behavior.

## Delivery

- [x] Review the diff and call sites without compiling or running tests.
- [x] Report that runtime behavior and endpoint compatibility remain unverified by request.

Ruling: The manual category registration, picker catalog wiring and persistence already exist in the current checkout. Complete the observed failure/recovery gaps instead of adding a duplicate implementation. Actual service compatibility remains to be checked in a later authorized validation run.

Static checks: the scoped `git diff --check` reported no whitespace errors. A separate read-only review confirmed ordinary tabs and manual groups preserve category account/tag identifiers and titles. No compilation, tests, app launches or endpoint probes were performed; no temporary test files were created.

Final review: no blocking static findings across the five source files. The reviewer noted that a missing inner catalog array still looked empty; preserve its absence as null and reject it in the loader, while accepting an explicit empty array. These final edits were reviewed as a diff only, with runtime validation still deferred.

## Authorized validation follow-up

The subsequent validation completed successfully on Windows on 2026-11-06:

- Full JVM tests, Android Debug unit tests and desktop tests passed. The optional real GitHub lifecycle test remained skipped because its dedicated remote fixtures were not configured.
- Android and desktop compilation, app/desktop ktlint, Android Debug lint, and Android device-test source compilation passed. No application package was produced and no device tests were installed or executed.
- The release tool's 29 tests and the CI workflow contract checks passed; AI and X network behavior was tested with mocked responses, without an API key or live account requests.
- Validation fixed omission of the default null cursor in the actual X request serializer, a fast-refresh wait race, Windows test-fixture compatibility and Android test application isolation. Existing feature assertions were retained.
- Temporary diagnostic code, downloaded validation tools and confirmed abandoned fixtures were removed. Existing regression tests and standard dependency caches were retained.

Final Gradle command: `jvmTest :app:testDebugUnitTest :desktopApp:test :app:ktlintCheck :desktopApp:ktlintCheck :app:lintDebug :app:compileDebugAndroidTestKotlin :compose-ui:compileAndroidDeviceTest :shared:compileAndroidDeviceTest :feature:plugin:compileAndroidDeviceTest`.

The validation commit uses `[skip ci]` to honor the user's instruction not to trigger application packaging on push.
