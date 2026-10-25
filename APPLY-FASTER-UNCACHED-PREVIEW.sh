#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
python3 - <<'PYFIX'
from pathlib import Path
import shutil
p = Path('app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview/SeekPreviewStore.kt')
if not p.is_file():
    raise SystemExit('ERROR: Run the installer from your mpvRx source root.')
s = p.read_text()
if 'Prefer the already-verified persistent FFmpeg decoder for on-demand' in s:
    print('Faster uncached preview patch already applied.'); raise SystemExit(0)
old_path = '          val platformFrame = if (stillWanted(job.time)) decoder.frameAt(job.time) else null\n          if (platformFrame != null) platformFrame else {\n            val persistent = if (stillWanted(job.time)) decoder.persistentFrameAt(job.time) else null\n            persistent ?: nativeDecoderLock.withLock {\n              if (!stillWanted(job.time)) null\n              else decoder.nativeFrameAt(job.time) { path, seconds, hardware ->\n                PlaybackSession.grabThumbnailFast(path, seconds, 512, hardware)\n              }\n            }\n          }\n'
new_path = "          // Prefer the already-verified persistent FFmpeg decoder for on-demand\n          // network previews. An Android MediaMetadataRetriever probe can take\n          // seconds on DV/HEVC remuxes before FFmpeg is even tried. Never delay\n          // the user's thumbnail behind that probe when a native session exists.\n          val nativeFrame = if (persistentWorkerPool && stillWanted(job.time))\n            decoder.persistentFrameAt(job.time) else null\n          nativeFrame ?: run {\n            // Preserve both fallback paths, including the platform decoder for\n            // sources the native extractor cannot handle.\n            val platformFrame = if (stillWanted(job.time)) decoder.frameAt(job.time) else null\n            platformFrame ?: run {\n              val fallbackPersistent = if (!persistentWorkerPool && stillWanted(job.time))\n                decoder.persistentFrameAt(job.time) else null\n              fallbackPersistent ?: nativeDecoderLock.withLock {\n                if (!stillWanted(job.time)) null\n                else decoder.nativeFrameAt(job.time) { path, seconds, hardware ->\n                  PlaybackSession.grabThumbnailFast(path, seconds, 512, hardware)\n                }\n              }\n            }\n          }\n"
old_persist = '      if (bitmap != null && !closed.get()) persist(job.time, bitmap)\n    }\n    if (closed.get() || !PlaybackSession.isCurrentGeneration(generation) || !SeekPreviewSessionDisk.isActive(generation)) return\n'
new_persist = '      // Publish the decoded image before writing JPEG to disk below. Otherwise\n      // UI presentation waits for synchronous compression and file I/O.\n    }\n    if (closed.get() || !PlaybackSession.isCurrentGeneration(generation) || !SeekPreviewSessionDisk.isActive(generation)) return\n'
old_tick = '    synchronized(lock) { _tick.value += 1L }\n    if (networkSource) Log.d("SeekPreviewPerf",\n'
new_tick = '    synchronized(lock) { _tick.value += 1L }\n    // Do not block the first visible seek thumbnail on JPEG compression.\n    // Persistence stays on this worker, but Compose can render the bitmap now.\n    if (bitmap != null && !closed.get() && origin == "decoder") persist(job.time, bitmap)\n    if (networkSource) Log.d("SeekPreviewPerf",\n'
for old, label in [(old_path,'foreground decoder'),(old_persist,'save-before-display'),(old_tick,'UI notification')]:
    if s.count(old) != 1:
        raise SystemExit('ERROR: Expected exactly one ' + label + ' match; found ' + str(s.count(old)) + '. No files changed. Send your current SeekPreviewStore.kt.')
s = s.replace(old_path,new_path,1).replace(old_persist,new_persist,1).replace(old_tick,new_tick,1)
backup=p.with_suffix(p.suffix + '.before-fast-preview')
if not backup.exists(): shutil.copy2(p,backup)
p.write_text(s)
print('Installed native-first uncached preview + publish-before-disk optimization.')
print('Preserved video keyframe seeking, recovery protection, background worker pool and thumbnail quality.')
print('Source backup:',backup)
PYFIX
