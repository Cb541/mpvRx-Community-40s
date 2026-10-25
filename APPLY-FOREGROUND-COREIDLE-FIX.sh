#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
python3 - <<'PYFIX'
from pathlib import Path
import shutil
p=Path('app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview/SeekPreviewStore.kt')
if not p.exists(): raise SystemExit('ERROR: Run this script inside your mpvRx source folder.')
s=p.read_text()
if 'foreground-decode-start slotMs=' in s:
    print('Foreground core-idle fix already applied.')
    raise SystemExit(0)
old0 = '  /** A foreground seek thumbnail must be able to recover from an mpv playback\n   * seek independently of the slow 18-second background preloading gate.\n   * Do not steal the network while mpv is actually seeking/buffering/idle.\n   * Once playback has recovered at least a small buffer, or is safely paused,\n   * a requested preview can proceed even if the background recovery flag is\n   * still awaiting a larger buffer or a moving playback clock.\n   */\n  private fun foregroundPreviewMayRun(): Boolean {\n    if (playbackSeeking.value == true || pausedForCache.value == true || coreIdle.value == true) return false\n    if (PlaybackSession.lastPlaybackSeekAtElapsedMs > observedSeekSignal) return false\n    if (!playbackSeekRecovery) return true\n    if (PlaybackSession.state.value.paused) return true\n    val bufferedSeconds = playbackCacheDuration.value\n    if (bufferedSeconds != null && bufferedSeconds.isFinite() && bufferedSeconds >= 8.0) return true\n    // Some remux streams keep demuxer-cache-duration at zero even during\n    // uninterrupted playback. A moving mpv playback clock proves recovery.\n    val at = playbackClock.value\n    val baseline = recoveryBaselinePosition\n    return at != null && at.isFinite() && baseline != null && at - baseline >= 1.5\n  }\n'
new0 = '  /** Foreground previews are independent of speculative preloading. A remote\n   * mpv source may stay core-idle=true indefinitely even when seeking=false\n   * and it reports many buffered seconds. Do NOT use core-idle or the warm\n   * recovery flag to veto a user-requested thumbnail forever.\n   *\n   * Yield while the playback engine is actively seeking/paused for cache and\n   * for a short grace interval following a playback seek. After that, allow\n   * precisely one foreground decoder; three warm workers remain completely\n   * suspended while playback recovery is incomplete.\n   */\n  private fun foregroundPreviewMayRun(): Boolean {\n    if (closed.get() || playbackSeeking.value == true || pausedForCache.value == true) return false\n    val lastSeek = PlaybackSession.lastPlaybackSeekAtElapsedMs\n    if (lastSeek > 0L) {\n      val sinceSeekMs = SystemClock.elapsedRealtime() - lastSeek\n      if (sinceSeekMs in 0L until 1_800L) return false\n    }\n    return true\n  }\n'
old1 = '    if (allowAutoWarm) scope.launch {\n      while (!closed.get() && PlaybackSession.isCurrentGeneration(generation)) {'
new1 = '    // The foreground retry loop must run even on metered connections, where\n    // 12-second speculative preloading is disabled.\n    scope.launch {\n      while (!closed.get() && PlaybackSession.isCurrentGeneration(generation)) {'
old2 = '      if (values.isNotEmpty()) lastUserSeekMs = SystemClock.elapsedRealtime()\n      workSignal.value += 1\n    }\n    // An active scrub takes priority over all warm decoders. Cancel their\n    // in-progress network reads; the unfinished 12s slots get requeued.\n    if (values.isNotEmpty()) warmDecoders.forEach { it?.interruptPersistent() }'
new2 = '      if (values.isNotEmpty()) {\n        lastUserSeekMs = SystemClock.elapsedRealtime()\n        Log.d("SeekPreviewPerf", "foreground-request slotMs=${values.first()} " +\n          "seeking=${playbackSeeking.value} coreIdle=${coreIdle.value} " +\n          "seekRecovery=$playbackSeekRecovery")\n      }\n      workSignal.value += 1\n    }\n    // An active scrub takes priority over all warm decoders. Cancel their\n    // in-progress network reads; the unfinished 12s slots get requeued.\n    if (values.isNotEmpty()) warmDecoders.forEach { it?.interruptPersistent() }\n    // The next foreground target supersedes a slow decode of the old target.\n    // Native side uses an atomic FFmpeg interrupt callback, not player seeks.\n    foregroundDecoder?.interruptPersistent()'
old3 = '      if (urgent != null) {\n        inFlight.add(urgent)\n        return@synchronized Job(urgent, false)\n      }'
new3 = '      if (urgent != null) {\n        inFlight.add(urgent)\n        Log.d("SeekPreviewPerf", "foreground-decode-start slotMs=$urgent " +\n          "coreIdle=${coreIdle.value} playbackRecovery=$playbackSeekRecovery")\n        return@synchronized Job(urgent, false)\n      }'
for i,desc in enumerate(['foreground gate','foreground wake monitor','foreground request','foreground dispatch']):
    old=globals()['old'+str(i)]
    if s.count(old)!=1:
        raise SystemExit(f'ERROR: Expected exactly one {desc} anchor, found {s.count(old)}. No files changed. Provide current SeekPreviewStore.kt.')
for i in range(4):
    s=s.replace(globals()['old'+str(i)],globals()['new'+str(i)],1)
backup=p.with_suffix(p.suffix+'.before-coreidle-fix')
if not backup.exists(): shutil.copy2(p,backup)
p.write_text(s)
print('Applied foreground gate fix, independent wake-up monitor and diagnostic logs.')
print('Existing keyframe video seek, video network safety, 12-second warm worker limits untouched.')
print('Backup:',backup)
PYFIX
