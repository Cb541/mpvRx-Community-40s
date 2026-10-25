# mpvRx Stage 3.8 — Complete Known-Good Source

This is the reconstructed, **complete Android source project** for the shared-cache test app that was developed through Stage 3.8. It contains the custom 28.6 MB `mpvlib-seek-native.aar`, including the persistent native FFmpeg seek library, so no separate native build is needed to try building the Android app.

## Included modifications

- Stage 2: shared playback transport; separate test app package `app.gyrolet.mpvrx.sharedtest`.
- Stage 3: cache-fed automatic 12-second ahead thumbnail generation.
- Stage 3.1: bounded remote fallback for cache misses.
- Stage 3.2: persistent ahead-frame bursts.
- Stage 3.3: size-aware caching budgets.
- Stage 3.5: foreground manual-preview timing and admission, with the Kotlin syntax fix near `NetworkSeekFrameRetriever.kt:102`.
- Stage 3.6: maintenance changes originally applied, then REVERTED by Stage 3.8 due to observed thumbnail slowdown.
- Stage 3.7: fast manual-preview admission for known-size small, medium, and large videos.
- Stage 3.8: restores the pre-maintenance screenshot/JPEG throughput while keeping Stage 3.7 foreground improvements.

Stage 3.4 is superseded by 3.5 and is not required as a separate install.

## Build on Fedora KDE

Requirements: Android SDK installed, Java 17+, internet access for Gradle dependencies on first build, and (optionally) Android Debug Bridge (`adb`) to install.

```bash
cd ~/Downloads
unzip -oq mpvRx-Stage3.8-Final-Source.zip
cd mpvRx-Stage3.8-Final-Source
bash BUILD-ON-FEDORA.sh
```

The script builds `:app:assembleStandardDebug` for ARM64 using the included Gradle wrapper. The APK is copied to:

```
~/Downloads/mpvRx-Shared-Playback-Test-arm64-debug.apk
```

For installation with authorized `adb` device:

```bash
bash BUILD-ON-FEDORA.sh --install
```

The package is `app.gyrolet.mpvrx.sharedtest`, separate from the original app.

To repeat the focused source checks:

```bash
bash verification-tests/RUN-FOREGROUND-ADMISSION-TEST.sh
bash verification-tests/RUN-STAGE3-8-SCOPE-CHECK.sh
bash verification-tests/RUN-AHEAD-BURST-TEST.sh
bash verification-tests/RUN-HTTP-COMPILE-CHECK.sh
```

These isolated Kotlin/JVM checks are not substitutes for an Android build and real video tests.

## Build limitations of this exported artifact

This archive contains the complete source and native AAR, **not a compiled APK**. The assembly environment lacked an Android SDK, a Gradle distribution and network access to Gradle, so the Android build could not run there. The user-reported Stage 3.8 Android test version was already successfully installed and tested on the user's own system.
