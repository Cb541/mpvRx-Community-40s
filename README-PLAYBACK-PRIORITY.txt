# mpvRx: playback-priority keyframe seek caching (experimental)

This patch REPLACES the problematic 3-warm-worker scheduler. It does not touch seek-preview UI, dimensions, progress-bar notches, or seek JPEG cache lifetime.

- One 12-second background worker plus a dedicated on-demand worker
- Native keyframe-only extraction for speculative thumbnails; on-demand path unchanged
- Never invoke costly legacy native/platform fallback for speculative thumbnails
- Background pacing reacts to mpv paused-for-cache/core-idle/demuxer-cache-duration
- Abort native background network reads when playback buffer stalls
- Native byte-range cache and session JPEG cache are retained
- If native keyframe API absent, background FFmpeg pre-generation is disabled for safety; playback screenshots still populate watched slots

Install the NATIVE extension and rebuild the arm64 AAR BEFORE applying this Android patch.

You must have applied the earlier mpvlib persistent FFmpeg native source.

Native install:
  mkdir -p ~/Downloads/mpvlib-keyframe-upgrade
  unzip -oq ~/Downloads/mpvlib-KEYFRAME-AND-PLAYBACK-PROTECTION-SOURCE.zip -d ~/Downloads/mpvlib-keyframe-upgrade
  bash ~/Downloads/mpvlib-keyframe-upgrade/APPLY_NATIVE_KEYFRAME.sh ~/Projects/mpvlibAndroid-seek
  cd ~/Projects/mpvlibAndroid-seek/buildscripts
  bash ./buildall.sh --arch arm64 --no-deps mpv-android

Then Android:
  cd ~/Downloads/mpvRx-Nuvio-Build/mpvRx-2.7.2
  unzip -oq ~/Downloads/mpvRx-Keyframe-Playback-Priority-PATCH.zip -d .
  mkdir -p app/libs
  cp ~/Projects/mpvlibAndroid-seek/app/build/outputs/aar/app-release.aar app/libs/mpvlib-seek-native.aar
  bash BUILD-MOBILE-APK.sh --install

Runtime:
  adb logcat -s SeekPreviewPerf:D SeekPreviewNetwork:W '*:S'
  Look for warm-workers=1, playback-priority=true, keyframe-warm=true,
  and persistent-native quick=true success=true.

Thresholds for *active playback* (approximate cached seconds reported by mpv):
  stalled/core-idle: pause native warm and interrupt a running warm network read
  under 12s: no native warm
  12–30s: at most one attempt every 14s
  30–60s: one per 7s
  60–120s: one per 3.5s
  120s+: one per 1s
  unknown cache seconds: one per 12s

Stated cadence is measured from completion of the previous speculative job; decoding time is additional. Only one background FFmpeg decoder can be active. This is intended to prevent starvation, not to promise continuous high-speed pre-generation when the network has no spare bandwidth.

Validation: isolated Kotlin compile and simulated scheduler policy passed; native apply script idempotence passed. Full Android/NDK build and on-device testing NOT performed in this environment.
