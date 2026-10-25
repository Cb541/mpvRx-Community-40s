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
 p.write_text(s.replace(paused,active))
 print('12-second background extraction restored; rebuild to resume pre-generation')
elif active in s:
 print('12-second background extraction is already enabled')
else:
 raise SystemExit('Source does not match expected version. No change made.')
PY
