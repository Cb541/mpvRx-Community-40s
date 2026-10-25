#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY2'
from pathlib import Path
p=Path('mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview/SeekPreviewStore.kt')
s=p.read_text()
a=s.index('  private suspend fun foregroundLoop() {')
b=s.index('  private suspend fun generatePausedNeighbors(', a)
foreground=s[a:b]
background=s[:a]+s[b:]
assert foreground.count('playbackNeedsPriority(forManualPreview = true)')==6
assert 'if (frame != null && decoder != null && !playbackNeedsPriority()) {' in foreground
assert 'playbackNeedsPriority(forManualPreview = true)' not in background
assert 'private fun playbackNeedsPriority(forManualPreview: Boolean = false)' in background
print('PASS: only user-requested foreground path gets new early admission')
PY2
