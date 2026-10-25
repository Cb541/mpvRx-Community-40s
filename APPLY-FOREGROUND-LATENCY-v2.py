#!/usr/bin/env python3
"""Guarded foreground-only latency optimization; no playback or proxy modifications."""
from pathlib import Path
import shutil
import sys

p = Path("app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview/SeekPreviewStore.kt")
backup = p.with_suffix(p.suffix + ".before-foreground-latency-v2")
marker = "// FOREGROUND_LATENCY_V2: prefer verified native sessions and publish before JPEG I/O"
if not p.is_file():
    sys.exit("ERROR: Run from the root of the mpvRx Android source project.")
s = p.read_text()
if marker in s:
    print("Foreground latency v2 is already applied. No changes made.")
    sys.exit(0)

old_decode = '''        } else {
          val platformFrame = if (stillWanted(job.time)) decoder.frameAt(job.time) else null
          if (platformFrame != null) platformFrame else {
            val persistent = if (stillWanted(job.time)) decoder.persistentFrameAt(job.time) else null
            persistent ?: nativeDecoderLock.withLock {
              if (!stillWanted(job.time)) null
              else decoder.nativeFrameAt(job.time) { path, seconds, hardware ->
                PlaybackSession.grabThumbnailFast(path, seconds, 512, hardware)
              }
            }
          }
        }
'''
new_decode = '''        } else {
          // FOREGROUND_LATENCY_V2: prefer verified native sessions and publish before JPEG I/O
          // Only swap decoder order when the native AAR is installed and Android's
          // platform retriever has not ALREADY proven to work for this video.
          // Check the real playback-seek safety gate before every fallback;
          // otherwise an interrupted decode can start more network traffic.
          fun safeToRead(): Boolean = stillWanted(job.time) && foregroundPreviewMayRun()
          val nativeFirst = persistentWorkerPool && platformState.get() != 2
          val firstNative = if (nativeFirst && safeToRead())
            decoder.persistentFrameAt(job.time) else null
          firstNative ?: run {
            val platformFrame = if (safeToRead()) decoder.frameAt(job.time) else null
            platformFrame ?: run {
              val laterNative = if (!nativeFirst && safeToRead())
                decoder.persistentFrameAt(job.time) else null
              laterNative ?: if (!safeToRead()) null else nativeDecoderLock.withLock {
                if (!safeToRead()) null
                else decoder.nativeFrameAt(job.time) { path, seconds, hardware ->
                  PlaybackSession.grabThumbnailFast(path, seconds, 512, hardware)
                }
              }
            }
          }
        }
'''
old_persist = '''      if (bitmap != null && !closed.get()) persist(job.time, bitmap)
    }
    if (closed.get() || !PlaybackSession.isCurrentGeneration(generation) || !SeekPreviewSessionDisk.isActive(generation)) return
'''
new_persist = '''      // Background caching keeps its current synchronous persistence semantics.
      // A requested foreground frame is already in memory and can be displayed
      // without waiting for JPEG compression or flash I/O.
      if (bitmap != null && !closed.get() && job.speculative) persist(job.time, bitmap)
    }
    if (closed.get() || !PlaybackSession.isCurrentGeneration(generation) || !SeekPreviewSessionDisk.isActive(generation)) return
'''
old_publish = '''    synchronized(lock) { _tick.value += 1L }
    if (networkSource) Log.d("SeekPreviewPerf",
'''
new_publish = '''    synchronized(lock) { _tick.value += 1L }
    if (!job.speculative && bitmap != null && origin == "decoder") {
      // The foreground display should not wait for JPEG I/O. Skip superseded
      // positions instead of piling up old disk writes during rapid scrubbing.
      val frameForDisk = bitmap
      val timeForDisk = job.time
      scope.launch {
        if (!closed.get() && stillWanted(timeForDisk) &&
            PlaybackSession.isCurrentGeneration(generation) &&
            SeekPreviewSessionDisk.isActive(generation)) {
          persist(timeForDisk, frameForDisk)
        }
      }
      if (networkSource) Log.d("SeekPreviewPerf",
        "foreground-frame-ready requestToDisplayMs=${(SystemClock.elapsedRealtime() - lastUserSeekMs).coerceAtLeast(0L)}")
    }
    if (networkSource) Log.d("SeekPreviewPerf",
'''
changes = ((old_decode,new_decode,"foreground decoder order"),
           (old_persist,new_persist,"synchronous foreground JPEG save"),
           (old_publish,new_publish,"foreground UI notification"))
for old, new, label in changes:
    count=s.count(old)
    if count != 1:
        sys.exit(f"ERROR: Expected exactly one {label} anchor; found {count}. No files changed. This project differs from the supported source.\nSend your current SeekPreviewStore.kt for a compatible fix.")
for old,new,_ in changes:
    s=s.replace(old,new,1)
if not backup.exists():
    shutil.copy2(p,backup)
p.write_text(s)
print("Applied foreground latency v2. Playback URL/seek logic, network proxy and background workers unchanged.")
print(f"Backup: {backup}")
