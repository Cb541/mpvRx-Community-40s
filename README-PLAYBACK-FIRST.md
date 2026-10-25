# mpvRx playback-first thumbnail engine — experimental

This is a full replacement for `SeekPreviewStore.kt` (not a patch to the existing three-worker scheduler).
It was inspired by a separation-of-concerns approach seen in video players, but **is not
copied from KSPlayer**. KSPlayer's ProgressBar Preview is a paid feature and its
implementation is not in the public repository: https://github.com/kingslay/KSPlayer

## Design

* Remote automatic 12-second thumbnail downloading **disabled**. No extra playback network
  connections are opened for background thumbnail pre-generation.
* Each 12-second frame that the **main video player has already rendered** can be
  captured directly with `PlaybackSession.grabThumbnail(512)`. No extra media HTTP range request.
* Requests for **uncached seek previews** use a single, user-driven native FFmpeg
  decoder connected to a separate authenticated thumbnail proxy. Older scrub requests
  are conflated, and the worker waits for video playback to recover before opening
  any new preview HTTP connection.
* Preview HTTP I/O is canceled on actual playback seek. An idle preview session
  is closed after ~8 seconds, releasing connections.
* 32 MB memory thumbnail cache, JPEGs in the existing session directory, disk
  cleanup on video change, ~96 MB disk cap. Existing floating preview and white
  thumbnail markers are unchanged.

## Intentional tradeoff

The previous implementation generated 12-second thumbnails **ahead of playback** at
arbitrary remote positions using independent FFmpeg network readers. That mechanism
is completely disabled because it repeatedly caused the stream to stop seeking outside
its buffered area. White preview markers now fill **as video plays** (plus manually
requested preview positions). Rapid background full-film generation is NOT retained.

A fast full-film pre-generation system without competing network traffic would need
native sharing of actual playback's fetched byte ranges or an offline/server-side
thumbnail index. We have not implemented that shared-demuxer architecture here.

## Install on Fedora

1. Download the ZIP to `~/Downloads/`.
2. In your existing *working* source project:

```bash
cd ~/Downloads/mpvRx-Nuvio-Build/mpvRx-2.7.2
unzip -oq ~/Downloads/mpvRx-Playback-First-Thumbnail-ENGINE.zip -d .
python3 INSTALL-PLAYBACK-FIRST.py
bash BUILD-MOBILE-APK.sh --install
```

The installer preserves your existing `SeekPreviewStore.kt` as
`SeekPreviewStore.kt.before-playback-first`. It refuses to install if the
other source APIs are missing. It does not modify your mpv seek command or
native AAR, HTTP proxy implementation, or Compose thumbnail UI.

## Rollback

```bash
cd ~/Downloads/mpvRx-Nuvio-Build/mpvRx-2.7.2
python3 RESTORE-PLAYBACK-FIRST.py
bash BUILD-MOBILE-APK.sh --install
```

## Diagnostics

```bash
adb logcat -c
adb logcat -v time -s SeekPreviewPerf:D SeekPreviewNetwork:W MpvPlaybackNet:D '*:S'
```

Look for `playback-first thumbnails: background-network-workers=0` on opening a video.
If playback still stalls, capture mpv network diagnostics; this eliminates a major
source of contention but does not rule out underlying media-host or mpv bugs.

## Validation limits

Compiled this replacement Kotlin source with compatible mocked Android/app interfaces.
That verifies type checking and Kotlin syntax, not a full APK build or on-device
performance. An actual Gradle build and a repeated unbuffered seek test are needed.
