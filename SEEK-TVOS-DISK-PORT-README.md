# Nuvio Native tvOS disk-seek design adapted for mpvRx (Android)

Source studied: user-supplied Nuvio-Native-tvOS-unsigned(6).ipa. Its Mach-O binary
contains identifiable `NuvioRangeCache`, `NuvioThumbnailDecoder`, seek-disk
writer/reader and `NuvioMediaReadAhead` symbols, plus mpv options
`cache-on-disk`, `demuxer-cache-dir`, `demuxer-max-bytes=256MiB`,
`demuxer-max-back-bytes=128MiB`, `demuxer-cache-unlink-files=immediate`.
This is an architectural adaptation, NOT a binary or source port of the
Apple TV's custom FFmpeg range decoder.

## Implemented

1. MPVView enables mpv's built-in disk-backed demuxer cache under Android's
   private app cache directory. mpv unlinks media-byte cache files immediately;
   they disappear automatically when a video or mpv process ends.
2. A separate preview JPEG spool is stored under
   `<internal cache>/seek-session-v2/<random process epoch>-g<generation>-<source hash>/`.
   It remains available across Activity/Compose recreation, but is retired on
   source replacement, playback stop, natural EOF, error, and core destroy.
   Orphaned files from a crash are removed on next opening.
3. During playback on unmetered networks the app captures a live frame every
   12 seconds where possible (without re-opening the network stream), then
   progressively decodes near-future positions and a coarse overview. A single
   background native worker is maintained; urgent finger scrubs win priority.
4. Background work re-centers after a seek. JPEG disk reads can reuse nearby
   12-second slots for the UI's 5-second landing previews. Playback seeks remain exact.
5. The preview spool evicts least-recently-used JPEGs when it exceeds 192 MiB,
   trimming to 160 MiB. The decoded bitmap cache uses 48 MiB of RAM.

## What this does NOT do

- Disk storage itself does not speed up an uncached native network frame request.
  The previous log measured 2-3 seconds for `grabThumbnailFast` because its
  underlying native implementation opens an FFmpeg input separately on each
  request. A native Android FFmpeg `AVFormatContext`/`AVCodecContext` persistent
  seek worker with reusable range cache would be needed to match the Apple
  TV's cold-seek performance.
- mpv's main playback demuxer cache and its thumbnail sidecar are independent.
  `cache-on-disk` improves byte read-ahead/seek-back for *playback*, not arbitrary
  frame extraction in the thumbnail worker. The disk JPEG spool gives fast
  rendering only once images have been pre-generated or captured.
- mpv's disk-backed demuxer files are append-only. The 256 MiB option limits
  metadata, NOT the total bytes stored on disk. High bitrate streams can use
  significant storage until closed; disable `cache-on-disk` if space is low.
- Prefetch only runs on unmetered connections by default, to avoid large
  mobile-data charges and battery usage.

## Install

From your existing modified source tree:

    unzip -oq ~/Downloads/mpvRx-Nuvio-tvOS-Disk-Seek-PATCH.zip -d .
    bash BUILD-MOBILE-APK.sh --install

## Verify

    adb logcat -v time -s SeekPreviewPerf:D SeekPreviewNetwork:W '*:S'

Play a Stremio or Nuvio HTTP stream on Wi-Fi for 60-120 seconds, scrub within
that interval, and then skip to an unwatched section. Cached positions should
be much faster; uncached positions may still be slow until generated.

## Validation

The modified SeekPreviewStore, SeekPreviewSessionDisk and SeekStripLayout compiled
against Kotlin coroutine and Android API stubs. Full Gradle/Android compilation
and runtime validation require the user's Fedora Android toolchain.
