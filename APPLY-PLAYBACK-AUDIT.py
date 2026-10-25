#!/usr/bin/env python3
"""Conservative mpvRx playback reliability audit, checks all anchors before modifying anything."""
from pathlib import Path
import sys, shutil, os, tempfile
root=Path.cwd()
base=Path('app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview')
paths=[base/'SeekPreviewStore.kt',base/'SeekByteBlockCache.kt',base/'NetworkSeekHttpBridge.kt']


def replace_once(s, old, new, label):
    n=s.count(old)
    if n!=1: raise ValueError(f'{label}: expected one source anchor, found {n}. No files modified.')
    return s.replace(old,new,1)

try:
    src={p:(root/p).read_text() for p in paths}
except Exception as exc:
    raise SystemExit(f'Cannot read expected source: {exc}')
try:
    store=src[paths[0]]
    store=replace_once(store,
'''  private var lastStallMs = 0L
''',
'''  private var lastStallMs = 0L
  // A cache-duration estimate is NOT proof the stream is playable. Record an
  // actual advancing video clock before allowing speculative network reads.
  private var lastPlaybackClockSample: Double? = null
  @Volatile private var lastPlaybackClockAdvanceMs = 0L

  private fun samplePlaybackClock(now: Long) {
    val clock = playbackClock.value?.takeIf { it.isFinite() }
    val previous = lastPlaybackClockSample
    if (clock == null) {
      lastPlaybackClockSample = null
      return
    }
    // Ignore seek discontinuities. Only ordinary clock movement proves
    // playback has resumed at its current destination.
    if (previous != null && clock - previous in 0.04..1.5) {
      lastPlaybackClockAdvanceMs = now
    }
    lastPlaybackClockSample = clock
  }

  private fun videoClockIsAdvancing(now: Long): Boolean =
    lastPlaybackClockAdvanceMs > 0L && now - lastPlaybackClockAdvanceMs in 0L..2_500L
''','progress sample insertion')
    store=replace_once(store,
'''        updatePlaybackSeekRecovery()
        val permitted = permittedWarmWorkers()
''',
'''        samplePlaybackClock(SystemClock.elapsedRealtime())
        updatePlaybackSeekRecovery()
        val permitted = permittedWarmWorkers()
''','update loop heartbeat')
    store=replace_once(store,
'''      recoveryBaselinePosition = null
      recoveryStableSinceMs = 0L
      Log.d("SeekPreviewPerf", "playback-seek entered; cancelling all thumbnail HTTP reads until playback recovers")
''',
'''      recoveryBaselinePosition = null
      recoveryStableSinceMs = 0L
      lastPlaybackClockSample = null
      lastPlaybackClockAdvanceMs = 0L
      Log.d("SeekPreviewPerf", "playback-seek entered; cancelling all thumbnail HTTP reads until playback recovers")
''','seek invalidates heartbeat')
    store=replace_once(store,
'''    if (playbackSeeking.value == true || pausedForCache.value == true || coreIdle.value == true) {
      // A new read, decoder start, or network stall has not recovered yet.
''',
'''    if (playbackSeeking.value == true || pausedForCache.value == true) {
      // Real seeking/buffering remains authoritative. core-idle can be stale,
      // so clock progress rather than that flag determines recovery.
''','recovery coreIdle deadlock')
    store=replace_once(store,
'''    val clockHasAdvanced = at - baseline >= 1.25
''',
'''    val clockHasAdvanced = at - baseline >= 1.25 && videoClockIsAdvancing(now)
''','recovery requires ongoing progress')
    store=replace_once(store,
'''    if (pausedForCache.value == true || coreIdle.value == true) {
      lastStallMs = now
      return 0
    }
    if (lastStallMs != 0L && now - lastStallMs < 3_000L) return 0
    if (PlaybackSession.state.value.paused) return 1
    val seconds = playbackCacheDuration.value
    if (seconds == null || !seconds.isFinite()) return 1 // never assume unknown = healthy
    return when {
      seconds < 15.0 -> 0
      seconds < 35.0 -> 1
      seconds < 60.0 -> 2
      else -> 3
    }
''',
'''    if (pausedForCache.value == true || !videoClockIsAdvancing(now)) {
      // A reported video buffer may belong to the PREVIOUS seek destination.
      // Do not open a single new background network read until time advances.
      lastStallMs = now
      return 0
    }
    // Some mpv cores leave core-idle=true after a seek, even while the clock
    // advances. Trust actual clock progress over that stale flag.
    if (lastStallMs != 0L && now - lastStallMs < 2_000L) return 0
    if (PlaybackSession.state.value.paused) return 0
    val seconds = playbackCacheDuration.value
    if (seconds == null || !seconds.isFinite()) return 0
    return when {
      seconds < 25.0 -> 0
      seconds < 45.0 -> 1
      seconds < 75.0 -> 2
      else -> 3
    }
''','background worker health gate')
    store=replace_once(store,
'''      recoveryBaselinePosition = null
      stopThumbnailIoForPlayback()
''',
'''      recoveryBaselinePosition = null
      lastPlaybackClockSample = null
      lastPlaybackClockAdvanceMs = 0L
      stopThumbnailIoForPlayback()
''','jump heartbeat reset')

    cache=src[paths[1]]
    cache=replace_once(cache,
'''  data class Block(val start: Long, val total: Long, val bytes: ByteArray, val mime: String)
''',
'''  data class Block(val start: Long, val total: Long, val bytes: ByteArray, val mime: String)
  // Only a demonstrably non-range HTTP origin should disable block caching.
  // Cancellation, timeout, and transient 5xx errors must be retryable.
  class RangeUnsupportedException : IOException("Upstream does not support consistent HTTP byte ranges")
''','block cache error type')
    cache=replace_once(cache,
'''    } catch (_: Exception) {
      future.complete(null)
      return null
    } finally {
''',
'''    } catch (_: RangeUnsupportedException) {
      synchronized(lock) { unsupported = true }
      future.complete(null)
      return null
    } catch (_: Exception) {
      // A canceled range read is NOT evidence that ranges are unsupported.
      future.complete(null)
      return null
    } finally {
''','transient range failures')
    cache=replace_once(cache,
'''    val block = fetch(start, last) ?: run {
      synchronized(lock) { unsupported = true }
      return null
    }
''',
'''    // A null result can be a canceled OkHttp call while actual playback seeks.
    // Do not permanently disable the session cache on this transient failure.
    val block = fetch(start, last) ?: return null
''','do not poison byte cache on cancellation')

    bridge=src[paths[2]]
    bridge=replace_once(bridge,
'''      if (response.code != 206) return@use null
      val header = response.header("Content-Range") ?: return@use null
''',
'''      if (response.code == 200) {
        // Origin ignored a byte range; use unmodified streaming instead.
        throw SeekByteBlockCache.RangeUnsupportedException()
      }
      // 416 can mean the requested offset is past EOF, not missing range support.
      if (response.code != 206) return@use null // transient 401/403/416/429/5xx
      val header = response.header("Content-Range")
        ?: throw SeekByteBlockCache.RangeUnsupportedException()
''','non-range origin detection')
    bridge=replace_once(bridge,
'''      val match = Regex("""^bytes\\s+(\\d+)-(\\d+)/(\\d+)$""", RegexOption.IGNORE_CASE).matchEntire(header) ?: return@use null
''',
'''      val match = Regex("""^bytes\\s+(\\d+)-(\\d+)/(\\d+)$""", RegexOption.IGNORE_CASE).matchEntire(header)
        ?: throw SeekByteBlockCache.RangeUnsupportedException()
''','malformed range response')
    bridge=replace_once(bridge,
'''      if (first != start || last > end || last < first || total <= last) return@use null
''',
'''      if (first != start || last > end || last < first || total <= last)
        throw SeekByteBlockCache.RangeUnsupportedException()
''','range mismatch')
    bridge=replace_once(bridge,
'''      if (count > 1024L * 1024 || count != minOf(1024L * 1024, total - start)) return@use null
''',
'''      if (count > 1024L * 1024 || count != minOf(1024L * 1024, total - start))
        throw SeekByteBlockCache.RangeUnsupportedException()
''','range length mismatch')
    edited={paths[0]:store,paths[1]:cache,paths[2]:bridge}
    # Check all changes before touching source. Do not silently overwrite updates.
    for p,updated in edited.items():
        if updated==src[p]: raise ValueError(f'No change produced for {p}')
        backup=root/(str(p)+'.before-playback-audit')
        if backup.exists(): raise ValueError(f'Backup {backup} already exists; use restore before reapplying.')
except ValueError as error:
    raise SystemExit(f'AUDIT PATCH ABORTED SAFELY: {error}')

for p,updated in edited.items():
    target=root/p
    backup=root/(str(p)+'.before-playback-audit')
    shutil.copy2(target, backup)
    fd,tmp=tempfile.mkstemp(dir=target.parent, prefix='.audit-', suffix='.kt')
    try:
        with os.fdopen(fd,'w') as stream: stream.write(updated)
        os.replace(tmp,target)
    finally:
        if os.path.exists(tmp): os.unlink(tmp)
    print('Updated',p)
print('Playback audit patch applied. Existing PlaybackSession, PlayerViewModel, MPVView, AAR, thumbnails, and UI NOT modified.')
print('Use python3 RESTORE-PLAYBACK-AUDIT.py from source root to roll back.')
