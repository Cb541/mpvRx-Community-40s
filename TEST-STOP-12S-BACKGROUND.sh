#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
python3 - <<'PY'
from pathlib import Path
p=Path('app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview/SeekPreviewStore.kt')
s=p.read_text()
active='private val allowAutoWarm = networkSource && runCatching {'
paused='private val allowAutoWarm = false && networkSource && runCatching {'
if paused in s:
 print('12-second background extraction is already disabled (manual previews still work)')
elif s.count(active)==1:
 p.write_text(s.replace(active,paused))
 print('TEST MODE: background 12-second extraction disabled; rebuild and compare unbuffered video seeking')
else:
 raise SystemExit('Source does not match expected version. No change made.')
PY
