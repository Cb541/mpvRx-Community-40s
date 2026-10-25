# mpvRx 2.7.2 — priority seek preview scheduler (speed v2)

Apply this update to the previous **Nuvio Seek Speed** network preview source, NOT stock mpvRx.

## What changed

- Two independent HTTP preview workers instead of one global serial extraction loop. One stays available while the other is auto-warming cache tiles. The native mpv fallback remains serialized for safety, and speculative jobs never occupy the native fallback.
- The currently requested landing frame takes precedence over unrequested strip images; fast dragging replaces the *queued* work without discarding completed images.
- A bounded on-device cache warm-up of at most 24 frames per playback session runs while watching, only on an Android network reported as unmetered. Work is speculative and never changes video playback position. Mobile data still supports on-demand previews; speculative preloading is disabled.
- Short-distance in-memory nearest-frame reuse (up to 10 seconds) provides an immediate approximate image while an exact target preview loads. The seek timestamp and eventual seek target remain exact, but the image may be approximate due to keyframe seeking.
- Memory cache increased from 24 MiB to 32 MiB. Disk cache remains 128 MiB maximum. Stream URLs, cookies, and authentication headers are never written to plaintext cache metadata.
- Playback ticks are collected directly as a Flow and reduced to 30-second buckets to avoid adding high-frequency Compose recomposition.
- Added the normal ACCESS_NETWORK_STATE permission to respect metered connections.

## Known limits

- First-time random seeking can still take seconds over a slow server, particularly for 4K Dolby Vision remuxes that Android's MediaMetadataRetriever cannot decode. Those formats use the existing mpv fallback, which creates a separate decoder per requested frame. This update cannot guarantee an instant cold-cache preview or eliminate server latency.
- Auto-warming may consume extra network bandwidth and battery while watching on an unmetered connection. It is capped at 24 frame attempts per playback session, uses at most one speculative worker, and does not use the native decoder if platform extraction fails.
- Signed URL expiry, unsupported streaming formats, HLS, live/unseekable, and DRM streams may remain unavailable. Do not share stream URLs or access tokens in bug reports.
- Requires testing on the target Android device. Full Gradle build and performance benchmarks were not possible in the code-patching environment.

## Build on Fedora (same tree that built successfully before)

```bash
cd ~/Downloads/mpvRx-Nuvio-Build/mpvRx-2.7.2
unzip -oq "$HOME/Downloads/mpvRx-Nuvio-Seek-ULTRAFAST-PATCH.zip" -d .
bash BUILD-MOBILE-APK.sh --install
```

## Diagnostics

```bash
adb logcat -c
adb logcat -v time -s SeekPreviewPerf:D SeekPreviewNetwork:W '*:S'
```

Test two situations: a second preview of a location you've already visited; and a completely fresh location in a large network remux. The first should be cached; the second may still depend on server range-request and decoder latency. The log distinguishes Android keyframe, mpv fallback, disk and total latency.

## Verification

- Isolated Kotlin/JVM compilation succeeded for SeekPreviewStore, SeekStripLayout, and Android API test stubs.
- Simulated parallel scheduling test passed: an on-demand frame completed while a separate background frame was decoding; near-cache lookup passed. This is not an Android/device performance measurement.
- ZIP structure/integrity checked at packaging time.
