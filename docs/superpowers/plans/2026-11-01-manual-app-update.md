# Manual Android Update Implementation Plan

> **For agentic workers:** Use subagent-driven-development for the independent update transport, installer validation, and local release tooling; coordinate all Gradle builds through the main agent.

**Goal:** Add a Settings-only update check, validated APK download and user-confirmed installation, backed by locally built GitHub Release assets.

**Architecture:** No startup hook or scheduler. The Settings control owns the update flow; a repository reads the public release manifest and streams a verified APK to private cache. An Android installer validates package identity, version and signing certificates before granting temporary read access to the system installer.

**Tech Stack:** Kotlin, Compose, coroutines, Kotlin serialization, Android PackageManager/FileProvider, Python release tooling and GitHub Releases.

**Spec:** The user-approved flow in this chat: local build/upload, Settings button to check, explicit download, system installation confirmation. Never check automatically at startup or on a timer.

## Global constraints

- Repository: `Natsuneko3/Flare`; manifest: `releases/latest/download/flare-update.json`.
- Preserve `dev.dimension.flare.videofix`, the existing signing certificate and installed data.
- Initial published version: `1.7.8-custom.1`, versionCode `1781` (installed baseline `1780`).
- Manifest schema 1: applicationId, versionCode, versionName, minSdk, apkFileName, sha256, sizeBytes, releaseTag, releaseNotes.
- No GitHub token or signing secret in the APK or repository.
- Publish complete APK and manifest assets together using a draft release.

## Review focus

- Opening Settings must not perform a request; only the explicit check action may do so.
- Cancellation and failed downloads must not expose partial APKs as installable.
- Older/equal versions, wrong package, mismatched size/hash/signature and unsupported SDK must not install.
- Returning from install-source permission settings must recheck permission and validate the file again.
- Release metadata must describe the actual APK and tested source; never publish a stale build.

## Tasks

- [x] Repository: write failing tests for version selection, malformed metadata, HTTPS redirects, streaming bounds, cancellation, size and digest validation; implement `AppUpdateSource.checkForUpdate(...)` and `download(...)`.
- [x] Installer: write failing policy tests; implement archive metadata and signer validation plus system installer intents. Add the install-package permission without enabling it silently.
- [x] Settings: add localized check/update states and download progress; test no request on composition, explicit actions, latest/error paths and cancellation.
- [x] Release tooling: persist package/version config, validate APK metadata/signature, generate manifest, build locally and publish complete Release assets; test generation/version/old-build guards.
- [ ] Run application unit tests and focused device tests; build signed Release APK.
- [ ] Push tested source, publish the APK and manifest, and verify public asset hashes.
- [ ] Install on Fold7 and exercise the real Settings check/download/install flow without clearing account data.
