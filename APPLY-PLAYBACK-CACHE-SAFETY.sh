#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
FILE=app/src/main/java/app/gyrolet/mpvrx/ui/player/MPVView.kt
if [ ! -f "$FILE" ]; then
  echo "Cannot find $FILE: run this from the mpvRx project root." >&2
  exit 1
fi
python3 - "$FILE" <<'PYINNER'
from pathlib import Path
import sys
p = Path(sys.argv[1]); s=p.read_text()
old='PlaybackSession.setOptionString("cache-on-disk", "yes")'
new='PlaybackSession.setOptionString("cache-on-disk", "no")'
if old in s:
    p.write_text(s.replace(old,new,1))
    print("Disabled disk-backed mpv demuxer cache; video buffer and thumbnail caches retained")
elif new in s:
    print("Playback demuxer cache safety already applied")
else:
    raise SystemExit("Expected mpv cache option missing; no change made")
PYINNER
