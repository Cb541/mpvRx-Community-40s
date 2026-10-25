mpvRx rolling adaptive 3-worker 12-second seek thumbnail cache

Install over your CURRENT, working (12S-RECOVERY) mpvRx project.
Only one Kotlin file is changed: SeekPreviewStore.kt.
Your 28 MB custom native AAR stays as-is. No native rebuild needed.

FIXES:
- Up to three independent background native FFmpeg sessions, with a fourth reserved for seeks.
- Adaptive concurrency based on mpv demuxer-cache-duration: >=60s -> 3; 35-60 -> 2; 15-35 -> 1; <15/stalling -> 0.
- Unknown buffer -> one background worker, never blindly three. Playback takes priority.
- Background jobs pause while actively scrubbing, and their slots are requeued if interrupted.
- Rolling queue automatically refills every 500ms, independent of the playhead.
- Continues in 12-second buckets from current position to film end, then returns to fill earlier gaps.
- Periodically retries failed gaps without thrashing.
- JPEG disk cache and thumbnail geometry are untouched. Network metering protection remains.

Look for:
  warm-workers=3 foreground-workers=1
  warm-capacity=3/3 active=... bufferSeconds=... stalled=false
  12s-sweep start=... duration=... rolling=true

If warm-capacity stays at 1/3, mpv isn't reporting a healthy buffer. Do not force three without checking network and playback stalls.

Apply:
  cd ~/Downloads/mpvRx-Nuvio-Build/mpvRx-2.7.2
  unzip -oq ~/Downloads/mpvRx-Rolling-Adaptive-3x-12S-CACHE-PATCH.zip -d .
  bash BUILD-MOBILE-APK.sh --install
