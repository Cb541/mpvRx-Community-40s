#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT="$ROOT/app/src/main/java/app/gyrolet/mpvrx/preferences/CommunityPlaybackCacheDefaults.kt"
PREF="$ROOT/app/src/main/java/app/gyrolet/mpvrx/preferences/AdvancedPreferences.kt"
python3 - "$DEFAULT" "$PREF" <<'PYTEST'
import pathlib, re, sys
source = pathlib.Path(sys.argv[1]).read_text()
pref = pathlib.Path(sys.argv[2]).read_text()
assert 'getString("mpv.conf", CommunityPlaybackCacheDefaults.mpvConf)' in pref
match = re.search(r'val mpvConf: String = """\n(.*?)\n"""\.trimIndent\(\) \+ "\\n"', source, re.S)
assert match, 'Default config block not found'
actual = match.group(1).splitlines()
expected={
 'cache':'yes','cache-on-disk':'yes','demuxer-seekable-cache':'yes','demuxer-thread':'yes',
 'demuxer-max-bytes':'4096MiB','demuxer-max-back-bytes':'2048MiB',
 'demuxer-readahead-secs':'3600','cache-secs':'3600','cache-pause':'no',
 'demuxer-hysteresis-secs':'0','stream-buffer-size':'32MiB',
 'demuxer-donate-buffer':'yes','demuxer-cache-unlink-files':'immediate',
}
options=dict(line.split('=',1) for line in actual if line and not line.startswith('#'))
assert options==expected,(options,expected)
assert len(actual) >= len(expected)
print('PASS: all 13 user-requested mpv options included in the fresh-install default')
print('PASS: pre-existing preference values remain authoritative')
PYTEST
