# mpvRx faster 12-second thumbnail generation

Applies on top of `mpvRx-FFmpeg-RangeCache-ANDROID-PATCH.zip` with the custom
`mpvlib-seek-native.aar` installed (the AAR that produces
`persistent-native session=ready` in logcat).

## Install

```bash
cd ~/Downloads/mpvRx-Nuvio-Build/mpvRx-2.7.2
unzip -oq "$HOME/Downloads/mpvRx-Faster-12-Second-Background-CACHE-PATCH.zip" -d .
bash BUILD-MOBILE-APK.sh --install
```

## What changed

- Three background persistent FFmpeg decoder sessions (previously one).
- Fourth worker stays available for a seek request.
- Fall back to one background worker with the standard native library.
- Fill a contiguous, 12-second-spaced region ahead of playback (roughly 15
  minutes), *then* generate distant 2-minute overview frames.
- Resume preloading 1.25 seconds after scrubbing ends instead of potentially
  waiting for another playback tick following a five-second cooldown.
- Keep the same thumbnail resolution, JPG disk-cache size, video-byte range
  cache size, UI placement, and timeline white indicators.
- Retain unmetered-only preloading; no new background video downloads on
  metered cellular connections.

## Check logs

```bash
adb logcat -c
adb logcat -v time -s SeekPreviewPerf:D '*:S'
```

Expect: `warm-workers=3 foreground-workers=1 persistent=true`.

`preview source=decoder speculative=true duration=...` is each thumbnail's
individual decode latency, which may still be 1-2 seconds. Parallel workers
should improve *frames generated per second*, not necessarily individual
frame latency. On-demand latency is preserved by the reserved foreground worker.

## Rollback

Restore `SeekPreviewStore.kt` and `PersistentNativeSeek.kt` from the preceding
`mpvRx-FFmpeg-RangeCache-ANDROID-PATCH.zip`, and rebuild the APK.

## Testing

- Isolated Kotlin compilation against representative interfaces passed.
- JVM concurrency test passed: three overlapping warm jobs and a separate
  foreground seek running concurrently.
- Full Gradle/APK build and device performance measurement not run here.

More concurrent 4K/HDR extraction can increase battery consumption, network
traffic, and heat. If normal video playback stutters on a particular remux,
reduce `warmWorkerCount` from 3 to 2 in `SeekPreviewStore.kt` and rebuild.
