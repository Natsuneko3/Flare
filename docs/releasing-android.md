# Android fork releases

This fork upgrades the installed `dev.dimension.flare.videofix` application in place. Its first managed release is `1.7.8-custom.1` / version code `1781`, following the installed `1.7.8-local-glass-animation` / `1780` build. Android requires the same package ID and signing certificate and a higher version code. Do not uninstall the existing app to work around a signing error: uninstalling removes its local data.

The current Agent compatibility release is configured as `1.8.0-custom.3` / version code `1803`, retaining the previous performance release. This configuration does not mean a build has been verified or published.

The authoritative version and package identity are committed in `android-release.properties`. `BUILD_NUMBER`, `BUILD_VERSION`, and upstream `fdroid.properties` do not override them. The Android release includes `arm64-v8a` native libraries; debug builds also support the x86_64 CI emulator. The tool inspects the APK and uses the actual ABI set in its filename.

## Prerequisites

- Python 3.9+, Git, Java 25, the repository's Gradle wrapper, and Android SDK build-tools containing `aapt2` and `apksigner`.
- `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or the existing ignored `local.properties` must locate the SDK.
- GitHub CLI (`gh`) authenticated to GitHub with permission to push and create releases in the public `Natsuneko3/Flare` repository. The tool only checks authentication; it never retrieves passwords, logs in for you, or embeds tokens in the APK. Run `gh auth login` yourself when needed.
- The **existing** signing keystore. The expected certificate SHA-256 is `f000d6ac91b2617d0112fa8aa112ef049c43b1367dc4f0929fa3ad3a1dc27584`. The current setup uses the existing Android debug keystore. Release signing and packaging fail if it is missing or its certificate does not match; they never generate a replacement key. Ordinary debug builds and lint do not run this release-only check. Keep a private backup of that existing keystore. Never generate a replacement for this application ID.

An ignored root `signing.properties` can later point to a stable copy of that same keystore using the already-supported `storeFile`, `storePassword`, `keyAlias`, and `keyPassword` keys. Do not commit that file, any keystore, credentials, or personal machine paths. The release script verifies the APK certificate against the configured fingerprint regardless of the signing configuration used. It does not read signing passwords. `GH_TOKEN` and related GitHub token variables are removed from the Gradle subprocess environment.

On Windows, use `python` instead of `python3` in the commands below. The release tool selects `gradlew.bat` and SDK `.exe`/`.bat` tools, including SDK paths with spaces and escaped `sdk.dir` values in `local.properties`. `JAVA_HOME` must point to the installed JDK.

Fork CI compiles debug Android variants and retains shared, desktop, lint and device checks without the private release keystore. Official Apple runner jobs, store bundles and Google Play/GitHub publishing jobs are restricted to `flaresocialapp/Flare`; this fork publishes its signed APK and `flare-update.json` through `Android APK Release` or the local release tool below.

## Automatic GitHub builds and manual app updates

Pushing application changes to `master` starts `.github/workflows/android-apk-release.yml`. A manual **Run workflow** is also available on the repository Actions page. Documentation-only pushes are ignored, and GitHub's `[skip ci]` commit directive also skips automatic runs; use the manual workflow when needed. Pull requests and other forks cannot run this signing/publishing job.

The workflow runs publisher contract tests and Android unit tests, builds the release APK with JDK 25, and reuses the existing certificate. The only signing Secret is `FLARE_ANDROID_KEYSTORE_BASE64`: the base64 encoding of the original Android debug keystore already used for this app. Its default alias and passwords are unchanged. Store this binary only in repository Actions Secrets, never in Git or an artifact. The runner restores it in its temporary directory with private permissions and removes it after the job. Both Gradle and APK inspection verify `expectedSignerSha256`.

Versions are calculated from both `android-release.properties` and the latest published manifest, so every update has a higher Android version code. CI creates a version-only child commit of the pushed source and attaches the release tag to that exact build commit. It never pushes the generated version commit back to `master`; the release tag contains the reproducible source and effective version. If a newer `master` push is already queued, the obsolete build does not replace the latest release. Concurrent release jobs are serialized.

Publication creates a draft, uploads the signed APK and `flare-update.json`, downloads and hashes both files, then publishes the release as latest. A failed build or verification leaves the previous update available. Local release behavior is unchanged; local publishers must choose a version higher than the latest CI release instead of assuming the base configuration is the latest installed version.

The package ID stays `dev.dimension.flare.videofix`, the certificate stays the same, and the version code increases. Android therefore upgrades the existing app while preserving its data. The existing **Settings → Check for updates** button lets the user explicitly check, download and start the system update installer. CI does not install anything on a device.

## Release in one command

1. Finish the source changes. For releases after `custom.1`, increment both version fields:

   ```console
   python3 -B scripts/android_release.py --bump-version
   ```

   From the current configuration, this changes `custom.3` / `1803` to `custom.4` / `1804`, and checks Android's maximum version code. When intentionally moving to a new upstream base, edit `versionName` to its `MAJOR.MINOR.PATCH-custom.1` value and continue increasing `versionCode` globally.

2. Write UTF-8 release notes, then commit all source and version changes. Keep notes outside the repository or commit them too. The tool rejects tracked changes and untracked files; ignored build outputs are allowed.

3. From the repository root, run:

   ```console
   python3 -B scripts/android_release.py --notes-file /path/to/release-notes.md
   ```

The command checks GitHub authentication, runs the release tool's tests and `:app:testDebugUnitTest`, builds `:app:assembleRelease`, and verifies the APK package ID, version, minimum SDK and signing certificate. Existing generated release APK output is moved into `build/android-release/.previous-apks/`, so an old APK cannot satisfy a failed or skipped build. Source content and the Git commit are checked before and after building.

The prepared files are stored under `build/android-release/android-v1.8.0-custom.3/` (using the configured version). The folder contains the APK, `flare-update.json`, release notes and `build-receipt.json`. The receipt binds the commit, source content and file hashes to the successful test/build run. Do not hand-edit the manifest or receipt.

Only after successful local preparation does the tool push the current branch and its annotated tag, create a **draft** GitHub Release, upload the APK and manifest, and download both assets to compare their hashes. It then publishes the release and marks it latest. The public unauthenticated manifest is checked after publishing. Existing published releases are never overwritten; a matching draft can be resumed. If an upload or verification fails, the draft stays unpublished, and the previous latest release remains usable.

The script rejects version codes that do not advance beyond the current public manifest. The initial fork release has no previous manifest and is required to exceed the installed baseline `1780`.

## Prepare, inspect, then publish

Preparing is local and does not require GitHub authentication:

```console
python3 -B scripts/android_release.py --prepare-only --notes-file /path/to/release-notes.md
```

After reviewing or installing the prepared APK, publish exactly that verified build:

```console
python3 -B scripts/android_release.py --publish-existing build/android-release/android-v1.8.0-custom.3
```

Publishing rechecks the source commit, clean tree, source fingerprint, APK hash, manifest hash, APK metadata and certificate. If the source or version changes after preparation, commit and rebuild. To deliberately rebuild the same unpublished version, move its existing prepared folder aside first. Generated backups can be reviewed and removed manually when no longer needed.

A machine-specific Gradle network initialization script can be passed without committing its path:

```console
FLARE_GRADLE_INIT_SCRIPT=/path/to/local-network.init.gradle python3 -B scripts/android_release.py --prepare-only --notes-file /path/to/release-notes.md
```

Optional safe Gradle options can be repeated, for example `--gradle-arg=--offline` or `--gradle-arg=-Dorg.gradle.workers.max=2`. The repository's KMP plugin compatibility diagnostic has a known false positive; only this exact task exclusion is supported:

```console
python3 -B scripts/android_release.py --prepare-only --notes-file /path/to/release-notes.md --gradle-arg=--exclude-task=kmpPartiallyResolvedDependenciesChecker
```

This bypasses only that diagnostic; `:app:testDebugUnitTest` and `:app:assembleRelease` still run. All other task exclusions, dry runs and debug logging are rejected. A Gradle init script is trusted local build configuration; do not use one that suppresses tests or rewrites release metadata.

## Update manifest contract

The client always checks this public URL without credentials:

`https://github.com/Natsuneko3/Flare/releases/latest/download/flare-update.json`

Example (hash and size come from the actual APK):

```json
{
  "schemaVersion": 1,
  "applicationId": "dev.dimension.flare.videofix",
  "versionCode": 1801,
  "versionName": "1.8.0-custom.1",
  "minSdk": 26,
  "apkFileName": "Flare-1.8.0-custom.1-arm64-v8a.apk",
  "sha256": "<64 lowercase hexadecimal characters>",
  "sizeBytes": 12345678,
  "releaseTag": "android-v1.8.0-custom.1",
  "releaseNotes": "Release notes shown in the app"
}
```

The APK URL is derived from the fixed repository, `releaseTag`, and `apkFileName`: `https://github.com/Natsuneko3/Flare/releases/download/<releaseTag>/<apkFileName>`. A multi-ABI or ABI-independent APK uses `universal` in its filename. Neither the manifest nor the APK needs a GitHub token to download.

The publisher applies the same limits as the Android client: the complete UTF-8 JSON document, including formatting and release notes, must be at most 64 KiB; the APK must contain at least one byte and be at most 256 MiB; `releaseTag` and `apkFileName` must each be safe ASCII path segments of at most 128 characters. `versionName` is limited to 256 UTF-16 code units. A violation stops preparation or publication. Existing public and prepared manifests are also read with the 64 KiB limit.

Run the standalone release-tool checks without invoking Gradle or contacting GitHub:

```console
python3 -B scripts/test_android_release.py
```
