# mpvRx — Restore previews after the playback-buffer protection patch

This patch restores remote thumbnails (foreground and 12-second background)
while retaining the previous patch’s playback-friendly network limits.

## Root cause in the modified source

The previous network-protection patch forwarded every open-ended FFmpeg HTTP
Range request to the origin. Some video origins handle finite `bytes=N-M`
requests reliably but do not properly serve `bytes=N-` requests; the prior
byte-block cache hid this incompatibility. Another possible failure mode was
reuse of an FFmpeg AVIO context after its thumbnail HTTP reads were canceled.

## Changes

- Restore the already-proven byte-block cache for open-ended FFmpeg ranges.
- Cap each open-ended response at 8 MiB, with an honest HTTP 206 and correct
  `Content-Range: bytes N-M/TOTAL`. This avoids downloading from N all the
  way to EOF via hundreds of 1 MiB requests.
- Bounded single-block requests are still served by the reusable byte cache.
- Non-Range GETs and multi-block finite requests pass through unchanged.
- Native FFmpeg contexts are reopened on the next request after an actual
  playback seek cancels their network I/O; ordinary thumbnail scrubbing does
  not trigger this expensive reopen.
- No changes to main mpv playback URL, video buffer, manual seeking, user UI,
  adaptive worker count or persistent AAR ABI.

## Apply

From the current working project directory:

```bash
cd ~/Downloads/mpvRx-Nuvio-Build/mpvRx-2.7.2
unzip -oq ~/Downloads/mpvRx-RESTORE-THUMBNAILS-PLAYBACK-SAFE-PATCH.zip -d .
bash BUILD-MOBILE-APK.sh --install
```

## Verify

```bash
adb logcat -c
adb logcat -v time -s SeekPreviewPerf:D SeekPreviewNetwork:D MpvPlaybackNet:D '*:S'
```

Look for `persistent-native ... success=true`, and new white cached-image
marks. A new log `thumbnail native context reopened after playback seek`
confirms recovery of a cancelled native context. A 4xx/5xx origin status in
`SeekPreviewNetwork` may indicate a provider-specific response failure.

**Caveat:** I couldn't run the complete Android build or test remote media
on the user's S26 Ultra. The range semantics and source integrity were
checked offline; performance needs device testing.
