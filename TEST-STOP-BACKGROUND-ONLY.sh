#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
python3 - <<'PYFIX'
from pathlib import Path
import shutil
p=Path('app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview/SeekPreviewStore.kt')
if not p.exists(): raise SystemExit('Missing SeekPreviewStore.kt. Run in source root.')
s=p.read_text()
old='private val warmWorkerCount = if (persistentWorkerPool) 3 else if (networkSource) 1 else 0'
new='private val warmWorkerCount = 0 // TEMP TEST: no speculative network thumbnail decoder'
if new in s:
    print('Already in temporary video-playback-only test mode.')
elif s.count(old)==1:
    p.write_text(s.replace(old,new,1))
    print('Temporary test enabled: 12-second BACKGROUND network generation off; foreground seek thumbnails remain.')
else: raise SystemExit('Unexpected SeekPreviewStore.kt version. Nothing changed.')
PYFIX
