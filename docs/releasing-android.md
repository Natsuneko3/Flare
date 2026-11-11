# Android fork releases

This fork upgrades the installed `dev.dimension.flare.videofix` application in place. Its first managed release is `1.7.8-custom.1` / version code `1781`, following the installed `1.7.8-local-glass-animation` / `1780` build. Android requires the same package ID and signing certificate and a higher version code. Do not uninstall the existing app to work around a signing error: uninstalling removes its local data.

The version baseline is `1.8.0` / version code `1808`, following the published `1.8.0-custom.8` release. Future automatic releases use plain three-part versions such as `1.8.1`, then `1.8.2`, with tags such as `android-v1.8.1`. Historical `custom` tags remain readable and are never renamed. A source configuration does not mean a build has been verified or published.

The authoritative version and package identity are committed in `android-release.properties`. `BUILD_NUMBER`, `BUILD_VERSION`, and upstream `fdroid.properties` do not override them. The Android release includes `arm64-v8a` native libraries; debug builds also support the x86_64 CI emulator. The tool inspects the APK and uses the actual ABI set in its filename.

## Prerequisites

- Python 3.9+, Git, Java 25, the repository's Gradle wrapper, and Android SDK build-tools containing `aapt2` and `apksigner`.
- `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or the existing ignored `local.properties` must locate the SDK.
- GitHub CLI (`gh`) authenticated to GitHub with permission to push and create releases in the public `Natsuneko3/Flare` repository. The tool only checks authentication; it never retrieves passwords, logs in for you, or embeds tokens in the APK. Run `gh auth login` yourself when needed.
- The **existing** signing keystore. The expected certificate SHA-256 is `f000d6ac91b2617d0112fa8aa112ef049c43b1367dc4f0929fa3ad3a1dc27584`. The current setup uses the existing Android debug keystore. Release signing and packaging fail if it is missing or its certificate does not match; they never generate a replacement key. Ordinary debug builds and lint do not run this release-only check. Keep a private backup of that existing keystore. Never generate a replacement for this application ID.

An ignored root `signing.properties` can later point to a stable copy of that same keystore using the already-supported `storeFile`, `storePassword`, `keyAlias`, and `keyPassword` keys. Do not commit that file, any keystore, credentials, or personal machine paths. The release script verifies the APK certificate against the configured fingerprint regardless of the signing configuration used. It does not read signing passwords. `GH_TOKEN` and related GitHub token variables are removed from the Gradle subprocess environment.

On Windows, use `python` instead of `python3` in the commands below. The release tool selects `gradlew.bat` and SDK `.exe`/`.bat` tools, including SDK paths with spaces and escaped `sdk.dir` values in `local.properties`. `JAVA_HOME` must point to the installed JDK.

Fork CI compiles debug Android variants and retains shared, desktop, lint and device checks without the private release keystore. Official Apple runner jobs, store bundles and Google Play/GitHub publishing jobs are restricted to `flaresocialapp/Flare`; this fork publishes its signed APK and `flare-update.json` through `Mobile Release` or the local release tool below.

## Automatic GitHub builds and manual app updates

Pushing application changes to `master` starts `.github/workflows/android-apk-release.yml`. A manual **Run workflow** is also available on the repository Actions page. Documentation-only pushes are ignored, and GitHub's `[skip ci]` commit directive also skips automatic runs; use the manual workflow when needed. Pull requests and other forks cannot run this signing/publishing job.

The workflow runs publisher contract tests and Android unit tests, builds the release APK with JDK 25, and reuses the existing certificate. The only signing Secret is `FLARE_ANDROID_KEYSTORE_BASE64`: the base64 encoding of the original Android debug keystore already used for this app. Its default alias and passwords are unchanged. Store this binary only in repository Actions Secrets, never in Git or an artifact. The runner restores it in its temporary directory with private permissions and removes it after the job. Both Gradle and APK inspection verify `expectedSignerSha256`.

Versions are calculated from `android-release.properties`, the latest published manifest, and all reserved Android release tags. Each new release increments PATCH in the current major/minor line. Moving to a new major/minor line uses its configured initial version. The Android version code and iOS build number exceed every published or reserved build code, including historical `custom` tags; codes are read from each tag's committed properties rather than inferred from its name. A planning job reserves a unique tag and creates a version-only child commit of the pushed source; both platform jobs build that exact commit. Tags left by failed builds are skipped by later plans and do not become public Releases. It never pushes the generated version commit back to `master`; the release tag contains the reproducible source and effective version. The publisher checks current `master` before upload and again immediately before exposing the release; a newer application commit keeps the obsolete build unpublished. Descendants that change only trigger-excluded documentation do not discard the running application build. Concurrent release jobs are serialized.

Publication waits for both platform jobs, creates a draft, uploads the signed APK, signed IPA and their metadata, downloads and hashes every asset, then publishes the release as latest. A failed build or verification leaves the previous update available. Local release behavior is unchanged; local publishers must choose a version higher than the latest CI release instead of assuming the base configuration is the latest installed version.

The package ID stays `dev.dimension.flare.videofix`, the certificate stays the same, and the version code increases. Android therefore upgrades the existing app while preserving its data. The existing **Settings → Check for updates** button lets the user explicitly check, download and start the system update installer. CI does not install anything on a device.

## Signed iPhone package in the same release

The same workflow builds the iOS target in parallel on GitHub's `xcode-27` macOS runner and includes `Flare-<version>-ios.ipa` plus `flare-ios-build.json` to that release. Both platform builds must succeed before a new public release is created.

The shared Kotlin libraries compile first with one worker and a 4 GiB heap. That JVM exits before a fresh process links the Release framework with a 6 GiB heap, keeping dependency compiler state out of the optimizer. A 5 GiB isolated link still ran out of memory; the 6 GiB isolated link passed local verification. An 8 GiB heap budget exceeds the hosted ARM runner's 7 GiB RAM and can page heavily. Progress streams directly to the job log. Xcode then reuses the framework and builds with one job to limit concurrent memory use.

`ios-release.properties` records the existing iPhone identity: Bundle ID `dev.dimension.flare.xiamao`, its Open in Flare extension, development team, certificate fingerprint and installed build baseline. CI overrides only the generated Xcode project, preserving upstream project files. The iOS marketing version matches the plain release version (for example `1.8.1`); its build number matches the increasing Android release code. Both app and extension must match the existing identity and use physical iPhone builds.

The encrypted repository Secrets are `FLARE_IOS_CERTIFICATE_P12_BASE64`, `FLARE_IOS_CERTIFICATE_PASSWORD`, `FLARE_IOS_APP_PROFILE_BASE64` and `FLARE_IOS_EXTENSION_PROFILE_BASE64`. They contain only the existing selected signing identity and its matching profiles. A temporary runner keychain holds the certificate; signing files and keychain are removed after the job. The private certificate is never uploaded as a release artifact.

The current personal development profiles expire on 2026-11-14 and cover the existing iPhone. This is a development-signed update, not App Store distribution. Refresh profiles with the same Apple account before expiry and update the two profile Secrets; if Apple also rotates the certificate, update the existing identity configuration and encrypted certificate together. Expired profiles or a different Bundle ID/team/certificate stop the iOS job. Manual IPA installation must use the same identity and an authorized device to preserve the existing app data.

## Release in one command

1. Finish the source changes, then increment the patch version and build code:

   ```console
   python3 -B scripts/android_release.py --bump-version
   ```

   From the current baseline, this changes `1.8.0` / `1808` to `1.8.1` / `1809`, and checks Android's maximum version code. When intentionally moving to a new major/minor line, configure its initial plain version (for example `1.9.0`) and continue increasing `versionCode` globally. Check the latest CI release before a local release: the committed baseline may be older than published builds.

2. Write UTF-8 release notes, then commit all source and version changes. Keep notes outside the repository or commit them too. The tool rejects tracked changes and untracked files; ignored build outputs are allowed.

3. From the repository root, run:

   ```console
   python3 -B scripts/android_release.py --notes-file /path/to/release-notes.md
   ```

The command checks GitHub authentication, runs the release tool's tests and `:app:testDebugUnitTest`, builds `:app:assembleRelease`, and verifies the APK package ID, version, minimum SDK and signing certificate. Existing generated release APK output is moved into `build/android-release/.previous-apks/`, so an old APK cannot satisfy a failed or skipped build. Source content and the Git commit are checked before and after building.

The prepared files are stored under `build/android-release/android-v1.8.1/` (using the configured version). The folder contains the APK, `flare-update.json`, release notes and `build-receipt.json`. The receipt binds the commit, source content and file hashes to the successful test/build run. Do not hand-edit the manifest or receipt.

Only after successful local preparation does the tool push the current branch and its annotated tag, create a **draft** GitHub Release, upload the APK and manifest, and download both assets to compare their hashes. It then publishes the release and marks it latest. The public unauthenticated manifest is checked after publishing. Existing published releases are never overwritten; a matching draft can be resumed. If an upload or verification fails, the draft stays unpublished, and the previous latest release remains usable.

The script rejects version codes that do not advance beyond the current public manifest. The initial fork release has no previous manifest and is required to exceed the installed baseline `1780`.

## Prepare, inspect, then publish

Preparing is local and does not require GitHub authentication:

```console
python3 -B scripts/android_release.py --prepare-only --notes-file /path/to/release-notes.md
```

After reviewing or installing the prepared APK, publish exactly that verified build:

```console
python3 -B scripts/android_release.py --publish-existing build/android-release/android-v1.8.1
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
  "versionCode": 1809,
  "versionName": "1.8.1",
  "minSdk": 26,
  "apkFileName": "Flare-1.8.1-arm64-v8a.apk",
  "sha256": "<64 lowercase hexadecimal characters>",
  "sizeBytes": 12345678,
  "releaseTag": "android-v1.8.1",
  "releaseNotes": "Release notes shown in the app"
}
```

The APK URL is derived from the fixed repository, `releaseTag`, and `apkFileName`: `https://github.com/Natsuneko3/Flare/releases/download/<releaseTag>/<apkFileName>`. A multi-ABI or ABI-independent APK uses `universal` in its filename. Neither the manifest nor the APK needs a GitHub token to download.

The publisher applies the same limits as the Android client: the complete UTF-8 JSON document, including formatting and release notes, must be at most 64 KiB; the APK must contain at least one byte and be at most 256 MiB; `releaseTag` and `apkFileName` must each be safe ASCII path segments of at most 128 characters. `versionName` is limited to 256 UTF-16 code units. A violation stops preparation or publication. Existing public and prepared manifests are also read with the 64 KiB limit.

Run the standalone release-tool checks without invoking Gradle or contacting GitHub:

```console
python3 -B scripts/test_android_release.py
```
