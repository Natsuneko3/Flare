# Performance and simplification implementation plan

> **For agentic workers:** Use superpowers:executing-plans with independent implementation tasks delegated where files do not overlap. Keep Gradle runs serialized through the local build runner.

**Goal:** Implement the source-backed performance and simplification opportunities approved in the conversation on a new branch.

**Architecture:** Remove redundant network, allocation and persistence work while preserving observable behavior. Reuse existing helpers, introduce bounded loading for thumbnails and Web timeline rows, and keep platform-specific lifecycles and error semantics intact.

**Tech Stack:** Kotlin Multiplatform, Compose, Room, Ktor, SwiftUI/Kingfisher, Svelte/TypeScript.

**Spec:** The preceding repository audit and the user's instruction to create a branch and implement that proposal. The sections below record the concrete implementation decisions.

## Global constraints

- Base: bbfcaa0c86d0cb4a00b28c69d4e1429c74f55f60; branch: perf/reduce-redundant-work.
- Preserve application ID, signer, version, proxy routing, credentials, media bytes, UI wording, message order and existing customizations.
- Keep PagerState in UI composition; preserve Fold7 rotation and timeline refresh behavior.
- Do not change model requests, tool execution, checkpoints or retry semantics while reducing AI database writes.
- No new runtime dependency is needed. No GPU encoder rewrite without measurements.
- Work in the user-requested new branch in the current clean checkout; retain the existing build caches.
- All changes remain on this branch. Publication is outside this implementation task.

## Review focus

- Cached image export preserves original bytes, MIME detection, stream ownership and cancellation.
- RSS discovery retains partial/failure behavior, cancellation and the presenter's feed cache; parallel probes are bounded.
- AI persistence retains optimistic messages, stable positions, support parts, checkpoint atomicity and recovery.
- Web variable-height rows retain reading position across append, prepend, image resize and source switching.
- Apple thumbnail reuse includes source identity and scale and cannot show an old draft after an async completion.

## Tasks

### Task 1: Network reuse and RSS simplification

Files: feature/subscription RSS service, source detection data source/presenter and tests; social/bluesky mapper and facets tests.

- [x] Add regression coverage for repeated handles, RSS single-response parsing, detection cancellation and bounded concurrency.
- [x] Reuse fetched feed metadata for icons; centralize feed/web/instance detection with injected operations and retain UI feed cache.
- [x] Bound RSS source probes to 3 and resolve repeated Bluesky handles once per parse; preserve facet order/offsets.
- [x] Run subscription and Bluesky JVM suites; review and commit.

### Task 2: Android image export, Ugoira reuse and preview helper

Files: app media viewer/download manager/Ugoira encoder/PostActionLayoutScreen and app tests.

- [x] Cover file export bytes, MIME and error behavior using existing download tests.
- [x] Stream a cached image file to the selected output; avoid a full-file ByteArray.
- [x] Reuse Ugoira output bitmap, Canvas, Paint, Rect and row pixel buffer across frames; preserve frame dimensions, alpha background, YUV strides and cleanup.
- [x] Replace Android's duplicated preview action functions with PostActionLayoutPreviewHelper.
- [x] Run affected Android tests and compilation; review and commit.

### Task 3: Incremental AI history and common callbacks

Files: feature/agent history provider/DAO and presenter helpers/tests; desktop AgentTraceLabel/StatusInsightDialog.

- [x] Add behavioral tests for unchanged history, append, changed last-message support parts, optimistic messages and checkpoints.
- [x] Compare persisted message values and write only changed/new rows; avoid re-decoding old messages where stable IDs allow it. Keep transactions and persisted model boundaries.
- [x] Extract shared history callbacks for the four Agent presenters without collapsing their contexts or auto-run policies.
- [x] Reuse the desktop AgentTrace label implementation.
- [x] Run agent JVM tests and desktop compilation; review and commit.

### Task 4: Web timeline windowing

Files: web TimelineList.svelte and a focused timeline-window helper/test.

- [x] Add tests for bounded visible ranges, variable heights, empty lists and changes in item identity.
- [x] Render a bounded window with overscan and spacer heights; share visibility observation and measure actual heights.
- [x] Preserve paging requests, refresh/prepend scroll anchoring, source reset, accessibility and existing empty/error/footer behavior.
- [x] Run Web tests, Svelte checks/build where local prerequisites permit, and inspect in-browser rendering; review and commit.

### Task 5: Apple draft thumbnails

Files: appleApp/Shared/FlareAppleCore/Sources/FlareAppleUI/DraftBoxContentView.swift and existing image-loading support/tests as needed.

- [x] Reuse the established asynchronous Kingfisher/downsampling pipeline for local files at 60 points times display scale.
- [x] Keep placeholder/accessibility behavior and key caching by file identity and requested pixels.
- [x] Compile affected Swift sources/available Apple target and run focused tests; review and commit.

### Task 6: Integration

- [x] Review the whole branch and resolve substantive findings.
- [x] Run changed module suites, app tests, Pager/refresh regression tests, Android assembly, desktop compilation and Web/Apple checks appropriate to edits.
- [x] Record exact verification and any environment limitations; confirm clean branch and final commits.

## Verification and completion — 2026-11-06

Implementation is committed on `perf/reduce-redundant-work`. No application version, application ID, signing configuration, model policy, dependency version, or publication target was changed.

### Results

| Check | Result |
| --- | --- |
| Subscription JVM suite | 69 passed, including one-response XML/HTML discovery, bounded probes, shared icons, cancellation and complete presenter cache |
| Bluesky JVM suite | 127 passed, including repeated handles and Unicode byte ranges |
| Agent JVM suite | 169 passed, including SQLite write counts, optimistic reconciliation, support cards, checkpoints and recovery |
| Android unit suite | 96 passed; PNG/GIF checks use Robolectric native graphics to exercise actual MIME decoding |
| Pager / refresh regression suites | 2 rotation tests and 4 refresh tests passed |
| Web presenter processor tests | 14 passed; generated manifest byte-identical before/after prerequisite repair |
| Apple image processor tests | 4 passed against actual source and Kingfisher 8.15.0: animated first frame, static fill, EXIF rotation/display scale, and default animation preservation |
| Web tests | 39/40 passed; all 17 focused window/component/history tests passed; one pre-existing locale-catalog failure remains below |
| Svelte check | 0 errors, 0 warnings |
| Android build | `:app:assembleDebug` and `:app:compileDebugAndroidTestKotlin` passed |
| Desktop build | `:desktopApp:compileKotlin` passed |
| Apple UI build | Xcode `FlareAppleUI`, macOS arm64 Debug, signing disabled: BUILD SUCCEEDED |
| Web builds | Both development and production Kotlin/WASM distributions passed; production Vite bundle written to `web/build` |
| Browser | Actual TimelineList fixture with 10,000 rows passed 12 behaviors for both window and nested scrolling; 10 and 9 rows rendered respectively. Actual static production Home and Settings pages also loaded with no console errors |

The JVM/Android/processor/rotation/refresh checks above total **481 passing tests**. The four Apple image tests ran in an isolated SwiftPM harness using the real processor and test sources; they are also registered durably in `MacTimelineTests` through `project.yml`.

### Review and implementation adjustments

- Independent review covered agent persistence and callbacks, RSS cache publication/order/cancellation, Android export ownership/MIME and frame reset, Apple image loading, Web source identity/scroll anchoring, and the Web generator repair.
- Draft GIF thumbnails use an explicit first-frame downsampling mode. Existing animated-image callers retain their default behavior and cache identifiers.
- Web row identity follows actual generated nested-post/feed shapes. History source identity follows the submitted query, so editing the search field does not reset existing results.
- Shared RSS icon jobs are scoped to their discovery callers; failed discovery cancels outstanding icon work before instance fallback.
- Baseline Web compilation was blocked by decoding reference-only output structures and losing immutable-list input types. The generator now follows only real input constructors, preserves reference handles, and converts immutable-list inputs with `toPersistentList()`. Generated nullable guards receive a targeted `UNNECESSARY_SAFE_CALL` suppression; the project still treats other compiler warnings as errors.
- Android test setup was corrected to use actual graphics decoding and actually revoke previously granted directory permission. The support-card persistence fixture now cites the card, matching existing message rendering rules.

### Reproduction and evidence

- Gradle used JDK 25 and Gradle 9.8.2 with the existing local network init script. Main-checkout Gradle jobs were serialized. The existing `kmpPartiallyResolvedDependenciesChecker` diagnostic was excluded, matching prior repository builds.
- An isolated generator-only Gradle project allowed its focused RED/GREEN cycle while the main checkout built Apple; it referenced the same source and versions and kept project outputs separate.
- Local logs and per-task reports are in ignored `build/performance-audit/`. Final logs: `rss-bluesky-final.log`, `android-final.log`, `agent-desktop-green.log`, `integration.log`, `apple-image-green.log`, `apple-build.log`, `web-wasm-final.log`, `web-wasm-production.log`, `web-check-final.log`, `web-tests-final.log`, and `web-production-build.log`.
- Web production validation served the actual `web/build` static output with SPA fallback and the existing COOP/COEP headers. `vite preview`'s server rendering path failed to find `server/chunks/flare.wasm`; that preview-mode issue is not a failure of the tested static deployment output and remains outside this optimization scope.

### Remaining limits

- The baseline `en-US` catalog has four keys absent from `en`: `composeVisibilityFriends`, `composeVisibilityFriendsDescription`, `composeVisibilitySelf`, and `composeVisibilitySelfDescription`. This exact mismatch was verified in base commit `bbfcaa0c8`; locale wording was left unchanged.
- Ugoira instrumentation compiled, but codec/color/timestamp behavior was not run on a device. An emulator in use by another session was left untouched.
- No frame-rate or end-to-end latency percentage is claimed. Verified reductions are bounded row rendering, reduced SQLite writes, deduplicated requests and reused/bounded image buffers. Web identity scanning and height-offset rebuilding remain O(n).
- No release was published and no application was installed as part of this branch implementation.
