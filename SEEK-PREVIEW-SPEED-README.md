# Faster network seek previews — mpvRx 2.7.2

Apply this patch over the **existing network-preview + NanoHTTPD compile-fixed build** (not the original, unmodified mpvRx source).

## Changes

1. HTTP/HTTPS landing frames use a **5-second cache grid**. Moving the finger by a second no longer starts another remote decode. The timestamp on screen and actual seek-on-release still use the requested time, **not the 5-second rounded time**. The shown frame is representative of a point within approximately 2.5 seconds of the target, before any keyframe-offset effects.
2. Network thumbnail extraction uses Android `OPTION_CLOSEST_SYNC` (seek to the closest indexed/keyframe) rather than decoding to the exact frame, which may be very slow over remote HTTP. This trades frame precision for faster preview generation; keyframe spacing depends on the video.
3. If Android's retriever cannot decode a particular stream, it will be skipped for subsequent frames. The existing mpv-native fallback remains available.
4. The native fallback remembers when hardware decoding fails and stops probing it over and over for the same stream.
5. Duplicate seek-strip requests no longer cancel an in-progress extraction, and expensive disk-cache directory scans are throttled to once every 30 seconds.
6. Added logcat timing instrumentation (`SeekPreviewPerf`) without logging stream URLs, request headers, cookies or tokens.

None of these changes alters the normal player decoding/quality settings, timeline seeking, touch gesture behavior or the local-video extraction path.

**Limitations:** Cold remote HTTP seeks still depend on server responsiveness, keyframe density, container indexing, and codec support. Some remuxes may still need seconds for a first frame, and a fast keyframe preview is not necessarily the exact frame at the timestamp. This patch is a speed optimization and diagnostic pass, not a replacement for mpv's native extractor or a guarantee of instant cold-cache thumbnails.

## Install on the machine where the earlier build succeeded

Download `mpvRx-Nuvio-Seek-Speed-PATCH.zip` into `~/Downloads`:

```bash
cd ~/Downloads/mpvRx-Nuvio-Build/mpvRx-2.7.2
unzip -oq "$HOME/Downloads/mpvRx-Nuvio-Seek-Speed-PATCH.zip" -d .
bash BUILD-MOBILE-APK.sh --install
```

A compile-only verification with isolated Kotlin stubs passed for the modified preview extraction classes. A complete Android Gradle build and real Stremio/Nuvio device tests were **not** possible in this environment.

## If cold-cache streams are still slow

Connect the phone with USB debugging and run while previewing a movie:

```bash
adb logcat -c
adb logcat -v time -s SeekPreviewPerf:D SeekPreviewNetwork:W '*:S'
```

Share just the `SeekPreviewPerf` timing lines or a screenshot. Do not share the original playback URL or HTTP authorization headers. The log separates time spent in Android keyframe extraction, mpv fallback, and the total request so we can identify whether the server, Android retriever, or native mpv extraction is the bottleneck.
