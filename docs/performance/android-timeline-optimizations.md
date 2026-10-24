# Android timeline performance verification

Verified on 2026-10-24 with the repository's Kotlin/Gradle versions and a Galaxy Z Fold7 (SM-F9660, Android 16).

## Changes and evidence

| Path | Previous work | Current work |
| --- | --- | --- |
| Unread counter while scrolling a 10,000-item timeline at index 5,000 | 5,001 post-key reads per viewport update | One verified index lookup; zero reads when there are no unread posts |
| Unread counter during a stale layout snapshot with three unread posts | 5,011 post-key reads in the regression fixture | Four reads: one index hint and at most three unread-prefix entries |
| Cache membership checks | Full database mapping, including serialization and reference traversal, followed by complete timeline-row reads | Identity-only mapping and a two-column primary-key query, deduplicated and batched at 500 keys |
| Notification cache lookup regression | One message BLOB read | Zero BLOB reads; notification identity, target status, account and timeline isolation are retained |
| Non-matching image streams reaching APNG/WebP factories | Materialize the source file and invoke the animation parser | Reject using a non-consuming 8-byte PNG or 12-byte RIFF/WEBP signature probe |

The cache batching test checks 1,200 inputs with 600 unique identities: two queries return the expected 550 cached entries without reading BLOBs. Image factories still delegate animation detection and decoding to the original parsers after a signature match; MIME type is not trusted to reject a valid format.

## Regression checks

All 144 targeted tests passed:

- Shared JVM: 76 startup, notification, gap and append tests.
- Compose JVM: 47 unread-count and timeline-position tests, including 4,725 equivalence cases inside the counter test.
- Android application JVM: six decoder-factory tests.
- Fold7 instrumentation: all 15 Compose UI device tests, including video geometry, countdowns and timeline item behavior.

The new cost assertions were run against the original implementations first and failed for the expected excess key reads, BLOB read and temporary-file creation. They pass after the optimizations. Decoder header fixtures verify factory selection and source preservation; they are not animation-playback benchmarks. Operation-count reductions do not imply a measured frame-rate increase.

Reproduce the focused checks with:

```sh
./gradlew \
  :shared:jvmTest \
  --tests '*TimelineStartupTest' \
  --tests '*TimelineGapTest' \
  --tests '*NotificationTimelineTest' \
  --tests '*TimelineRemoteMediatorAppendTest' \
  :compose-ui:jvmTest \
  --tests '*TimelineNewPostsCountTest' \
  --tests '*TimelineWithLazyListStateTest' \
  --tests '*TimelineNewPostsScrollTest' \
  :app:testDebugUnitTest \
  --tests 'dev.dimension.flare.common.AnimatedDecoderFactoryTest' \
  :compose-ui:connectedAndroidDeviceTest \
  -x kmpPartiallyResolvedDependenciesChecker
```

The device checks need an unlocked Android device. This focused Android/JVM run skips the optional cross-platform partial-resolution checker; the listed compile and test tasks all run. Kotlin formatting and `git diff --check` were also verified.
