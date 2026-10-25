mpvRx / Nuvio fast cold-seek patch - 2026-10-16

APPLY on top of the working latest mpvRx build with the raised preview and white cache marks.
The archive contains only:
  app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview/SeekPreviewStore.kt
  app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/SeekThumbnailStrip.kt

Changes:
- 120 ms network scrub request coalescing; cached frames are shown without delay.
- Native decode jobs discarded if no longer requested BEFORE acquiring/after waiting for native lock.
- 5-second cooldown of background native prefetch after interactive scrubbing.
- Bypass known-incompatible Android keyframe probe on URLs clearly ending in .mkv/.avi/.m2ts.
- Existing disk cache, thumbnail resolution, floating preview sizing/position, and progress notches retained.

Build/install:
  cd ~/Downloads/mpvRx-Nuvio-Build/mpvRx-2.7.2
  unzip -oq ~/Downloads/mpvRx-Faster-Cold-Seek-PATCH.zip -d .
  bash BUILD-MOBILE-APK.sh --install

The backend still opens media for each native uncached frame. The fix removes wasted requests
and delays, but cannot make a completely remote uncached frame instantaneous. A native
persistent media decoder is needed to remove that last major bottleneck.

Validation: affected cache/scheduler code compiled with isolated Kotlin stubs; a complete
Android build was not run in this environment.
