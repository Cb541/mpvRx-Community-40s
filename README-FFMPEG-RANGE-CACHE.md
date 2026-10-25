# mpvRx range-byte seek cache + optional persistent native decoder

This patch applies on top of your latest `mpvRx-Faster-Cold-Seek` build. It
modifies only the seek-preview backend and the optional local AAR selection in
`app/build.gradle.kts`. It does **not** replace your floating preview UI,
thumbnail position, dimensions, rounded corners or cache indicator notches.

## Apply and build (works with the ordinary AAR)

```bash
cd ~/Downloads/mpvRx-Nuvio-Build/mpvRx-2.7.2
unzip -oq "$HOME/Downloads/mpvRx-FFmpeg-RangeCache-ANDROID-PATCH.zip" -d .
bash BUILD-MOBILE-APK.sh --install
```

This enables the authenticated shared 1 MiB-block HTTP range cache even before
you install a custom native AAR. The range store is capped at 192 MiB of disk and
12 MiB of RAM per playback bridge. It serves genuine 206 partial-content ranges
and gracefully falls back to direct streaming if the server doesn't support
byte ranges. Data is deleted when playback ends. The older cached JPEG previews
and exact playback seek behavior are preserved.

## Enable real persistent native FFmpeg sessions

Download `mpvlib-Persistent-FFmpeg-SOURCE.zip`, extract its top-level files to
`~/Downloads/mpvlib-persistent-seek`, follow `README-NATIVE-BUILD.md` in that
archive, and rebuild mpvlibAndroid. The Android patch automatically uses the
persistent decoder when you install that custom AAR.

## Diagnostics

```bash
adb logcat -c
adb logcat -v time -s SeekPreviewPerf:D SeekPreviewNetwork:W '*:S'
```

Look for `persistent-native session=ready` to verify you're actually running
the custom native AAR. Without it, the ordinary AAR is still extracting one
frame per native fallback call (but HTTP video bytes are now cached).

## Honest test status

The core byte-block store passed a JVM byte-integrity, repeated-read, fallback
and cleanup test. Seek-store, native reflection adapter and HTTP bridge passed
isolated Kotlin compile checks against representative Android/OkHttp/NanoHTTPD
interfaces. Native C++ passed a representative-header syntax check. A full
Android Gradle build, mpvlib native build and real-device benchmark are **not**
possible in this environment and remain to be verified on your device.
