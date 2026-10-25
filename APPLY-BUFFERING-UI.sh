#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
python3 - "$ROOT" <<'PYCODE'
from pathlib import Path
import sys
p = Path(sys.argv[1]) / 'app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/PlayerControls.kt'
s = p.read_text()
old = '''  val showBufferingIndicator =
    bufferingState.visible &&
      (controlsShown || playbackSessionState.phase == PlaybackPhase.LOADING) &&
      !isPlayerSeeking'''
new = '''  val showBufferingIndicator =
    bufferingState.visible &&
      (controlsShown || playbackSessionState.phase == PlaybackPhase.LOADING || bufferingState.isSeekStall) &&
      (!isPlayerSeeking || bufferingState.isSeekStall || isMpvBuffering)'''
if new in s:
    print('Buffering indicator fix already installed.')
elif old in s:
    p.write_text(s.replace(old, new, 1))
    print('Buffering indicator fix installed (all existing thumbnail positioning preserved).')
else:
    raise SystemExit('Could not find expected showBufferingIndicator expression; PlayerControls.kt was NOT modified.')
PYCODE
