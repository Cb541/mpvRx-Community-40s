# mpvRx remote-seek protection / thumbnail request amplification

This patch targets the NetworkSeekHttpBridge code in the user's latest uploaded
mpvRx 2.7.2 SEEK-BUFFERING-FIX source. It is NOT an alteration to mpv's player
URL, actual mpv demuxer, native FFmpeg ABI, or the seekbar UI.

## Problem found in source

Previously, `serveFromCachedRanges` cached these HTTP requests:

* `GET /media` with no Range header
* `Range: bytes=0-` and other open-ended ranges
* `Range: bytes=start-end` spanning arbitrary numbers of 1 MiB blocks

The cache responded by streaming the requested bytes through its own block
reader; that reader called a new upstream HTTP request every 1 MiB. For large
files, three ongoing independent persistent FFmpeg sessions can therefore cause
many more HTTP requests than FFmpeg requested. This is a plausible explanation
for freezing outside the **video playback buffer** after many 12-second previews,
but only actual playback and upstream HTTP logs can confirm the cause.

## Fix

* Only explicitly bounded ranges entirely within a single 1 MiB block use the
  byte-block cache. That can be satisfied by at most one upstream range fetch.
* Open-ended and multi-block reads pass through as a single upstream HTTP stream.
  The existing `executeTracked` wrapper still ensures cancellation on playback
  seeks, and the origin's HTTP status/Content-Range/Content-Length are forwarded.
* Logs cumulative upstream request counts and active call counts at 32-request
  intervals, without logging signed media URLs, headers, cookies, or tokens.
* Logs only safe HTTP error status (401, 403, 416, 429, or server 5xx).
* Preserves all three background workers, manual preview quality, rounded
  floating thumbnail, 12-second JPEG cache, and video playback configuration.

## Apply

Extract at the root of the current custom project and build:

```bash
cd ~/Downloads/mpvRx-Nuvio-Build/mpvRx-2.7.2
unzip -oq "$HOME/Downloads/mpvRx-Playback-Buffer-Protection-FIX.zip" -d .
bash BUILD-MOBILE-APK.sh --install
```

This ZIP overwrites **only NetworkSeekHttpBridge.kt**; other included files are
helper scripts and this README.

## A/B diagnosis if seek stalls persist

Temporarily disable only speculative 12-second network extraction:

```bash
bash TEST-STOP-12S-BACKGROUND.sh
bash BUILD-MOBILE-APK.sh --install
```

Manual thumbnails on demand and playback screenshots stay operational. Play a
fresh remote file for a few minutes and jump beyond the actual filled **video**
buffer. If the freeze disappears, it strongly implicates speculative network
thumbnail traffic (as distinct from stored JPEG count). Restore with:

```bash
bash RESTORE-12S-BACKGROUND.sh
bash BUILD-MOBILE-APK.sh --install
```

Observe:

```bash
adb logcat -c
adb logcat -v time -s SeekPreviewNetwork:D SeekPreviewPerf:D MpvPlaybackNet:D '*:S'
```

A `thumbnail origin HTTP=429` or `503` indicates the preview origin rejected a
request; **it does not by itself prove** the video playback socket suffered the
same error. A persistent mpv stall while background extraction is disabled
suggests playback origin/range support or main demuxer issues instead.

No Android Gradle build or device test has been completed in the current
container; rebuild and verify with the existing Fedora SDK and native AAR.
