#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY'
from pathlib import Path
import hashlib, json
r=Path('.'); c=json.loads((r/'PATCH-REVISIONS.json').read_text());
s=(r/c['rel']).read_bytes();h=hashlib.sha256(s).hexdigest()
assert h==c['target'], f'Expected Stage 3.8 source, saw {h}'
t=s.decode()
assert 'private fun clearCaptureReservation(' not in t, 'Stage 3.6 screenshot behavior still present'
assert 'jpegWriteMutex' not in t, 'Stage 3.6 serial JPEG writer still present'
assert 'requestToFrameMs=' in t, 'Stage 3.5 timing instrumentation lost'
assert 'ForegroundPreviewAdmission.seekGraceMs(' in t, 'Faster manual admission lost'
a=(r/c['rel']).with_name('ForegroundPreviewAdmission.kt').read_text()
assert 'seekGraceMs(' in a
print('PASS: Stage 3.6 storage/capture changes reverted; Stage 3.7 manual timing/priority retained.')
PY
