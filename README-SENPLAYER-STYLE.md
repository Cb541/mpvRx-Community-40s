# mpvRx — cooperative SenPlayer-inspired thumbnail previews

This is an original adaptation informed by the user's uploaded Nuvio tvOS build (which contains `SeekFrameService`, `aheadDecoder`, `NuvioRangeCache`) and SenPlayer's public release notes. It is **not** the SenPlayer source or an identical implementation. SenPlayer's private thumbnail pipeline has not been inspected.

## This experimental release deliberately prioritizes playback

- While the video **plays**, automatic 12-second thumbnails come only from frames the main playback decoder has already rendered. There are **no speculative remote FFmpeg workers** during playback.
- A **single** persistent FFmpeg session loads a genuinely uncached thumbnail when the user scrubs. Latest-request-wins behavior is preserved.
- When the user **explicitly pauses**, the existing session can populate a **bounded neighborhood** of six 12-second slots (±12s, ±24s, +36s, +48s). This makes nearby repeated scrubs quicker without the old runaway full-film background downloads.
- Neighbor generation cancels on a new seekbar target, playback resuming, real playback seeking, low-buffer stalls, or source changes. A new requested thumbnail can interrupt any in-flight neighbor read.
- The requested image reaches the UI immediately; JPEG encoding/persistence is dispatched separately to IO workers.
- Up to 96 MiB of session JPEGs remain on disk; existing cleanup handles the media session end.

## Explicit limitations

This does **not** implement SenPlayer's private playback engine or full-coverage ahead-of-playback generation. The Apple TV player has a shared FFmpeg video-byte-range architecture; mpvRx's playback demuxer and its thumbnail HTTP proxy still have separate upstream reads. Trying to generate hundreds of remote positions in parallel was repeatedly shown to starve video playback. Without a safely shared playback-owned byte-range cache (or a server-side thumbnail index), rapid whole-film pre-generation remains incompatible with the playback-first guarantee.

To keep that guarantee, **this patch only preloads extra network frames while explicitly paused**. The video may still need time to fetch an entirely new uncached preview. It does not change Dolby Vision rendering or the playback player's network buffer policies.

## Install

Prerequisite: the `mpvRx-Playback-First-Thumbnail-ENGINE.zip` from the previous step must already be installed. This installer checks the exact source hash and **refuses mismatched sources** rather than overwriting other changes.

```bash
cd ~/Downloads/mpvRx-Nuvio-Build/mpvRx-2.7.2
unzip -oq "$HOME/Downloads/mpvRx-SenPlayer-Cooperative-Previews-PATCH.zip" -d .
python3 INSTALL-SENPLAYER-STYLE.py
bash BUILD-MOBILE-APK.sh --install
```

If you build from `~/Downloads/mpvRx-Rebuild/mpvRx-2.7.2` instead, use that project root for the `cd` command.

### Rollback

```bash
python3 RESTORE-SENPLAYER-STYLE.py
bash BUILD-MOBILE-APK.sh --install
```

### Diagnostics

```bash
adb logcat -c
adb logcat -v time -s SeekPreviewPerf:D SeekPreviewNetwork:W MpvPlaybackNet:D '*:S'
```

Look for `senplayer-inspired preview: playing-remote-workers=0`, `on-demand-only ... success=true` and, **when paused**, `paused-neighbor ...`.

## Verification status

- Kotlin scheduler compiled with Android/app interface stubs.
- Simulation confirmed one remote request while playing, paused-only neighbor generation, and priority of newer seekbar requests.
- Guarded installation and rollback are tested.
- Full Gradle build and real-device playback are **not verified** in this environment; don't claim an APK has been built.
