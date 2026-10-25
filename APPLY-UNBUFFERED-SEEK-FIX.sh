#!/usr/bin/env bash
set -euo pipefail
# Execute from the source tree root, even if the script is copied elsewhere.
python3 - <<'PYTHON_PATCH'
from pathlib import Path
import shutil
base=Path('app/src/main/java/app/gyrolet/mpvrx')

def rewrite(path,old,new,marker):
    text=path.read_text()
    if marker in text:
        print(f'{path.name}: already applied')
        return
    if text.count(old)!=1:
        raise SystemExit(f'{path}: expected one anchor, got {text.count(old)}. Not changing this file.')
    backup=path.with_suffix(path.suffix+'.before-unbuffered-seek')
    if not backup.exists(): shutil.copy2(path,backup)
    path.write_text(text.replace(old,new,1))
    print(f'{path.name}: updated')

view=base/'ui/player/PlayerViewModel.kt'
old='      seekTo(target.toInt(), exact = true, resumeAfterSeek = !stripWasPaused)'
new="""      // For a remote seek past the filled mpv video buffer, prefer a fast
      // keyframe landing. A precise seek on an uncached HEVC GOP may require
      // a lot more decoding before the playback clock can restart.
      val isNetwork = PlaybackSession.getPropertyBoolean("network") == true
      val bufferedEnd = PlaybackSession.getPropertyDouble("demuxer-cache-time")
      val nowPosition = PlaybackSession.getPropertyDouble("time-pos") ?: target.toDouble()
      val outsideVideoBuffer = bufferedEnd?.takeIf { it.isFinite() }
        ?.let { target.toDouble() > it + 1.0 }
        ?: (target.toDouble() - nowPosition > 20.0)
      val keyframeLanding = isNetwork && outsideVideoBuffer
      android.util.Log.d("MpvPlaybackNet", "seek-commit keyframe=$keyframeLanding outsideBuffer=$outsideVideoBuffer")
      seekTo(target.toInt(), fast = keyframeLanding, exact = !keyframeLanding, resumeAfterSeek = !stripWasPaused)"""
rewrite(view,old,new,'seek-commit keyframe=')

mpvview=base/'ui/player/MPVView.kt'
old='PlaybackSession.setOptionString("msg-level", "all=$logLevel")'
new='PlaybackSession.setOptionString("msg-level", "all=$logLevel,ffmpeg=info,stream=info,demux=info")'
rewrite(mpvview,old,new,'all=$logLevel,ffmpeg=info,stream=info,demux=info')

store=base/'domain/seekpreview/SeekPreviewStore.kt'
text=store.read_text()
if 'mpv-issue category=' in text:
    print('SeekPreviewStore.kt: already applied')
else:
    if text.count('import app.gyrolet.mpvrx.ui.player.PlaybackSession')!=1 or text.count('    restoreDiskIndex()')!=1:
        raise SystemExit('SeekPreviewStore.kt: expected anchors not found')
    backup=store.with_suffix(store.suffix+'.before-unbuffered-seek')
    if not backup.exists(): shutil.copy2(store,backup)
    text=text.replace('import app.gyrolet.mpvrx.ui.player.PlaybackSession','import app.gyrolet.mpvrx.ui.player.PlaybackSession\nimport `is`.xyz.mpv.MPVLib',1)
    snippet='''    // Publish issue classifications only, never signed video URLs or headers.
    // FFmpeg stream/demux errors explain why a seek cannot fetch fresh bytes.
    if (networkSource) scope.launch {
      MPVLib.logFlow.collect { (component, level, message) ->
        val detail = message.lowercase()
        val kind = when {
          "403" in detail || "401" in detail || "forbidden" in detail || "unauthorized" in detail -> "authorization"
          "416" in detail || "range not satisfiable" in detail -> "range-rejected"
          "429" in detail || "too many requests" in detail -> "rate-limited"
          "404" in detail || "not found" in detail -> "missing-media"
          "500" in detail || "502" in detail || "503" in detail || "504" in detail -> "server-error"
          "timed out" in detail || "timeout" in detail -> "timeout"
          "connection reset" in detail || "connection refused" in detail || "network is unreachable" in detail -> "connection"
          "seek" in detail && ("fail" in detail || "error" in detail) -> "seek-failed"
          "read" in detail && ("fail" in detail || "error" in detail) -> "read-failed"
          "i/o error" in detail || "input/output error" in detail -> "io-error"
          else -> null
        }
        if (kind != null) {
          val code = Regex("""(?<![0-9])(?:401|403|404|416|429|500|502|503|504)(?![0-9])""")
            .find(detail)?.value ?: "none"
          val safeComponent = component.take(32).replace(Regex("[^a-zA-Z0-9_-]"), "_")
          Log.w("MpvPlaybackNet", "mpv-issue category=$kind code=$code component=$safeComponent level=$level")
        }
      }
    }
'''
    store.write_text(text.replace('    restoreDiskIndex()', '    restoreDiskIndex()\n'+snippet,1))
    print('SeekPreviewStore.kt: added privacy-safe mpv stream diagnostics')
PYTHON_PATCH
