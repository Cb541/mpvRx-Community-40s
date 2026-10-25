#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
python3 - <<'PYFIX'
from pathlib import Path
import shutil
base = Path('app/src/main/java/app/gyrolet/mpvrx')
view = base / 'ui/player/PlayerViewModel.kt'
store = base / 'domain/seekpreview/SeekPreviewStore.kt'
if not view.exists() or not store.exists():
    raise SystemExit('Run from your modified mpvRx source directory (missing expected Kotlin files).')

old_seek = '''      val isNetwork = PlaybackSession.getPropertyBoolean("network") == true
      val bufferedEnd = PlaybackSession.getPropertyDouble("demuxer-cache-time")
      val nowPosition = PlaybackSession.getPropertyDouble("time-pos") ?: target.toDouble()
      val outsideVideoBuffer = bufferedEnd?.takeIf { it.isFinite() }
        ?.let { target.toDouble() > it + 1.0 }
        ?: (target.toDouble() - nowPosition > 20.0)
      val keyframeLanding = isNetwork && outsideVideoBuffer
      android.util.Log.d("MpvPlaybackNet", "seek-commit keyframe=$keyframeLanding outsideBuffer=$outsideVideoBuffer")
      seekTo(target.toInt(), fast = keyframeLanding, exact = !keyframeLanding, resumeAfterSeek = !stripWasPaused)'''
new_seek = '''      // 'network' is not an mpv playback property. A distant stream seek
      // should not try to decode a long precise GOP at a cold network position.
      val viaNetwork = PlaybackSession.getPropertyBoolean("demuxer-via-network") == true ||
        seekThumbnailSource()?.let {
          it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
        } == true
      val nowPosition = PlaybackSession.getPropertyDouble("time-pos") ?: precisePosition.value.toDouble()
      val distantJump = kotlin.math.abs(target.toDouble() - nowPosition) >= 15.0
      // demuxer-cache-time is only an estimate, not a reliable cache range;
      // use it only as an additional reason to avoid an exact cold seek.
      val estimatedEnd = PlaybackSession.getPropertyDouble("demuxer-cache-time")
        ?.takeIf { it.isFinite() }
      val pastEstimatedEnd = estimatedEnd != null && target.toDouble() > estimatedEnd + 1.0
      val keyframeLanding = viaNetwork && (distantJump || pastEstimatedEnd)
      android.util.Log.d("MpvPlaybackNet", "seek-commit keyframe=$keyframeLanding distant=$distantJump network=$viaNetwork")
      seekTo(target.toInt(), fast = keyframeLanding, exact = !keyframeLanding, resumeAfterSeek = !stripWasPaused)'''
old_dispatch = '    viewModelScope.launch(if (exact) playbackStateDispatcher else Dispatchers.IO) {'
new_dispatch = '''    // A fast/keyframe seek must remain ordered with the asynchronous pause
    // triggered when the user begins scrubbing. Otherwise 'unpause' can race
    // ahead of the seek and leave the playback core in a stuck state.
    viewModelScope.launch(if (exact || resumeAfterSeek) playbackStateDispatcher else Dispatchers.IO) {'''

source=view.read_text()
if 'seek-commit keyframe=$keyframeLanding distant=$distantJump' in source:
    print('PlayerViewModel.kt: network-aware seek correction already present.')
elif source.count(old_seek)==1 and source.count(old_dispatch)==1:
    corrected=source.replace(old_seek, new_seek, 1).replace(old_dispatch,new_dispatch,1)
    backup=view.with_suffix('.kt.before-playback-first-seek')
    if not backup.exists(): shutil.copy2(view,backup)
    view.write_text(corrected)
    print('PlayerViewModel.kt: corrected remote seek detection and serialized seek/resume.')
else:
    raise SystemExit('PlayerViewModel.kt differs from expected patched source. No seek changes made; send this file for review.')

# Undo ONLY the last native-first thumbnail speed experiment, keeping the
# foreground wake-up and coreIdle recovery fixes that made previews work.
s=store.read_text()
marker='Prefer the already-verified persistent FFmpeg decoder for on-demand'
if marker in s:
    bak=store.with_suffix('.kt.before-fast-preview')
    if bak.exists() and 'foreground-decode-start slotMs=' in bak.read_text():
        shutil.copy2(bak, store)
        print('SeekPreviewStore.kt: restored pre-speed-tweak foreground request order.')
    else:
        raise SystemExit('Fast-preview rollback backup is missing/old. Seek file left unchanged; do NOT replace it with an older source.')
else:
    print('SeekPreviewStore.kt: foreground fast-path experiment is not present; nothing to roll back.')
print('Done. Native AAR, existing JPEGs, background cache scheduler and preview UI unchanged.')
PYFIX
