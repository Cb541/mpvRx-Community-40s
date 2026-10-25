#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
python3 - <<'PYFIX'
from pathlib import Path
p=Path('app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview/SeekPreviewStore.kt')
if not p.exists(): raise SystemExit('Missing SeekPreviewStore.kt')
s=p.read_text()
old='private val warmWorkerCount = 0 // TEMP TEST: no speculative network thumbnail decoder'
new='private val warmWorkerCount = if (persistentWorkerPool) 3 else if (networkSource) 1 else 0'
if old in s:
    p.write_text(s.replace(old,new,1)); print('Restored original adaptive three-worker background cache.')
elif new in s: print('Adaptive three-worker background cache is already active.')
else: raise SystemExit('Unexpected version: no changes made.')
PYFIX
