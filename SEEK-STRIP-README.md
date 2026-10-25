# mpvRx 2.7.2 — Nuvio-style seek strip adaptation

## Implemented

- Sliding scene tiles behind a larger, fixed, outlined landing preview and timestamp.
- Adapted Nuvio spacing / anchor algorithm to keep the strip steady as seeking accelerates.
- TV remote hold left/right from hidden controls or the focused seekbar; 5/10/30-second
  steps with a maximum of ten preview updates per second.
- Phone touch seekbar dragging AND full-screen horizontal swipe seeking use the same preview and commit-on-release system, without the former swipe-seek feedback chip.
- Actual video stays at its original position while previewing; release issues one exact
  seek to the displayed whole-second timestamp. Playback resumes only if it was playing.
- Back, focus loss, backgrounding and media changes cancel previews.
- Local native mpv frame extraction, landing-first requests, stale request / media guards,
  serialized decoder use, hardware then software fallback for null results.
- 24 MiB memory cache and a bounded 128 MiB local JPEG cache (trimmed to 112 MiB).
- Missing previews remain empty / labeled unavailable; unrelated frames are never substituted.

## Network seek previews (Stremio / Nuvio external-player links)

- The original seek-strip worker opened the stream a second time without any playback HTTP
  headers. The video could play normally in mpv while the strip said "Preview unavailable".
- The player now passes the *effective playback headers* (including URL-specific Referer,
  User-Agent and externally supplied headers) to the thumbnail worker.
- HTTP(S) previews first try Android's header-aware `MediaMetadataRetriever`, which stays
  open across nearby requests. If Android cannot extract that codec/container, it falls
  back to the native mpv/FFmpeg thumbnail path through a short-lived loopback HTTP range
  gateway that forwards the playback headers to the already-playing source URL.
- The gateway binds to `127.0.0.1` only, accepts only GET/HEAD on a random capability
  path and closes with the seek session. Cross-origin redirects do not receive cookies,
  Authorization, or other custom playback headers. No remote thumbnail service is used.
- Locally stored videos still use their original working native mpv extraction code.
- Signed streams, network interruptions, servers without byte-range seeking, live/HLS
  streams, DRM, and Android/native decoder differences may still prevent previews. A
  landing timestamp remains the requested destination; a displayed frame may be a
  nearby keyframe if an exact frame is unavailable.

### Apply the network-only patch to an existing successful build

```bash
cd ~/Downloads/mpvRx-Nuvio-Build/mpvRx-2.7.2
unzip -oq ~/Downloads/mpvRx-Nuvio-Network-Preview-PATCH.zip -d .
bash BUILD-MOBILE-APK.sh --install
```

The patch ZIP paths are relative to the project root. For a clean new build, use the
full source ZIP instead. Debug APKs normally install beside the released app.

## Scope and limitations

This is a source implementation, NOT a verified APK release. No APK in the supplied
uploads was modified. This is an adaptation of Nuvio's presentation and layout, not a
port of its ExoPlayer-specific demuxer or full-title pre-generation engine.

The native mpv extractor chooses the decoded frame. The landing timestamp is the exact
seek target, but preview frame accuracy / keyframe rounding, HDR-to-SDR colour and Dolby
Vision support must be checked on the real box. AVI and 4K DV remuxes have not been tested.
Protected streams, streams requiring headers unavailable to the independent decoder,
non-seekable streams and some codecs may produce no thumbnail. A cold cache can take time;
this version does not generate coverage for the entire film ahead of time.
Double-tap skip actions retain their normal behavior; full-screen horizontal swipe
now previews without seeking playback until the finger lifts.

## Validation performed

- The provided adaptation reported nine passing layout tests and an isolated store
  compilation. These were not a full Android application build.
- In this revision, geometry spot-checks passed using the installed Kotlin compiler.
- An APK build was attempted but the Gradle 9.7.1 distribution could not be resolved
  because this environment has no DNS/network access. Android dependencies/SDK are also
  unavailable here. No APK was generated or tested on a device.
- Full app compilation, device seeking precision, native decoder performance and color
  handling (particularly HDR/Dolby Vision) still need on-device verification.

## Build on your Android development machine

Prerequisites are unchanged: JDK 17, Android SDK platform 37, NDK 27.3.13750724,
CMake 3.22.1, and access to the project's Gradle / Maven / GitHub dependencies.

```bash
cd ~/Downloads/mpvRx-2.7.2
bash ./gradlew :app:assembleStandardDebug -PtargetAbi=arm64-v8a --no-daemon
```

The debug APK is generated under `app/build/outputs/apk/standard/debug/`.
It has the existing `.debug` application ID suffix and installs alongside the released
app. Use the generated APK there, not either original uploaded APK.

## Device acceptance checks

1. Play a local AVI; hold right, change direction, release, and verify the timestamp landed.
2. Repeat while paused; it must remain paused after release.
3. Use Back during a held seek; original playback position must be retained.
4. Repeat on 4K HDR / Dolby Vision MKV, then on an authenticated network source.
5. Rapidly switch titles while thumbnails are decoding; no previous-title frames may appear.
6. Drag the seekbar and swipe horizontally on the video on the phone, in portrait and landscape.
7. During a swipe, confirm the underlying video frame does not jump until release.
8. Background while scrubbing; return and ensure there is no stuck preview or paused state.
9. Revisit cached scenes and confirm the strip loads promptly with no playback stutter.
