mpvRx Foreground Uncached Thumbnail Latency v2

Purpose: Reduce the wait for a thumbnail at a previously uncached position,
without touching the working video playback seek, HTTP proxy, native library,
white cache marks, or the 12-second background scheduling.

Changes in SeekPreviewStore.kt ONLY:
- If persistent FFmpeg is available and Android's retriever has not already
  succeeded, try FFmpeg first instead of probing the Android retriever.
- Keep the Android and legacy FFmpeg fallback on actual failure.
- Recheck playback seek protection before any fallback network request.
- Publish the foreground frame immediately after decoding, then write the JPEG
  from an independent IO coroutine (background frames are unchanged).
- Log foreground-frame-ready requestToDisplayMs for actual measurements.

Apply from the ROOT OF THE SOURCE PROJECT YOU REALLY BUILD:
  python3 APPLY-FOREGROUND-LATENCY-v2.py
  bash BUILD-MOBILE-APK.sh --install

If the script says source mismatch, don't overwrite the source with an older
full-file patch. Send the current SeekPreviewStore.kt for a tailored patch.

Rollback, from the same directory:
  python3 RESTORE-FOREGROUND-LATENCY-v2.py
  bash BUILD-MOBILE-APK.sh --install

Logs:
  adb logcat -c
  adb logcat -v time -s SeekPreviewPerf:D SeekPreviewNetwork:W '*:S'

Compare foreground-frame-ready requestToDisplayMs for several uncached targets.
Remote network and decoding time can still dominate first-time extraction. This
patch isn't a guaranteed 1–2 second decode and hasn't been benchmarked on device.
