#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
python3 - <<'PYFIX'
from pathlib import Path
import shutil
p = Path('app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview/SeekPreviewStore.kt')
if not p.is_file():
    raise SystemExit('ERROR: Run in the mpvRx-2.7.2 source folder (no changes made).')
s = p.read_text()
marker = 'private fun foregroundPreviewMayRun(): Boolean'
if marker in s:
    print('Foreground uncached seek fix already applied; nothing to do.')
    raise SystemExit(0)

old_tick = '''          if (!closed.get() && warming.isNotEmpty() && requested.isEmpty()) workSignal.value += 1L'''
new_tick = '''          // A new preview may have been requested during a playback seek and
          // rejected by claimNext(). StateFlow does not replay the same request
          // when playback recovers. Re-awaken the dedicated foreground worker
          // whenever the video is healthy enough to fetch a preview.
          if (!closed.get()) {
            if (requested.isNotEmpty()) {
              if (foregroundPreviewMayRun()) workSignal.value += 1L
            } else if (warming.isNotEmpty()) {
              workSignal.value += 1L
            }
          }'''
old_seek_signal = '  private var observedSeekSignal = 0L'
new_seek_signal = '  @Volatile private var observedSeekSignal = 0L'
old_clock_baseline = '  private var recoveryBaselinePosition: Double? = null'
new_clock_baseline = '  @Volatile private var recoveryBaselinePosition: Double? = null'
old_gate = '''      if (playbackSeekRecovery || playbackSeeking.value == true ||
          pausedForCache.value == true || coreIdle.value == true) return@synchronized null'''
new_gate = '''      if (!foregroundPreviewMayRun()) return@synchronized null'''
old_load = '''    if (!job.speculative && (!stillWanted(job.time) || playbackSeekRecovery ||
        playbackSeeking.value == true || pausedForCache.value == true || coreIdle.value == true)) return'''
new_load = '''    if (!job.speculative && (!stillWanted(job.time) || !foregroundPreviewMayRun())) return'''
anchor = '''  private fun claimNext(workerIndex: Int): Job? = synchronized(lock) {'''
addition = '''  /** A foreground seek thumbnail must be able to recover from an mpv playback
   * seek independently of the slow 18-second background preloading gate.
   * Do not steal the network while mpv is actually seeking/buffering/idle.
   * Once playback has recovered at least a small buffer, or is safely paused,
   * a requested preview can proceed even if the background recovery flag is
   * still awaiting a larger buffer or a moving playback clock.
   */
  private fun foregroundPreviewMayRun(): Boolean {
    if (playbackSeeking.value == true || pausedForCache.value == true || coreIdle.value == true) return false
    if (PlaybackSession.lastPlaybackSeekAtElapsedMs > observedSeekSignal) return false
    if (!playbackSeekRecovery) return true
    if (PlaybackSession.state.value.paused) return true
    val bufferedSeconds = playbackCacheDuration.value
    if (bufferedSeconds != null && bufferedSeconds.isFinite() && bufferedSeconds >= 8.0) return true
    // Some remux streams keep demuxer-cache-duration at zero even during
    // uninterrupted playback. A moving mpv playback clock proves recovery.
    val at = playbackClock.value
    val baseline = recoveryBaselinePosition
    return at != null && at.isFinite() && baseline != null && at - baseline >= 1.5
  }

'''
for name, old in [('scheduler wake-up',old_tick),('foreground dispatch gate',old_gate),('foreground decode gate',old_load),('helper insertion point',anchor),('seek signal visibility',old_seek_signal),('playback clock visibility',old_clock_baseline)]:
    if s.count(old) != 1:
        raise SystemExit(f'ERROR: Expected one {name}, found {s.count(old)}. No changes made. Send the current SeekPreviewStore.kt for a compatible patch.')
original = s
s = s.replace(old_tick,new_tick,1).replace(old_gate,new_gate,1).replace(old_load,new_load,1).replace(anchor,addition+anchor,1)
s = s.replace(old_seek_signal,new_seek_signal,1).replace(old_clock_baseline,new_clock_baseline,1)
assert s.count(marker) == 1
backup=p.with_suffix(p.suffix + '.before-foreground-preview-fix')
if not backup.exists():
    shutil.copy2(p,backup)
p.write_text(s)
print('Restored foreground uncached thumbnail wake-ups without changing native decoding or video seeking.')
print('Backup:',backup)
PYFIX
