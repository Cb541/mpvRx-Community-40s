#!/usr/bin/env python3
"""Switch current mpvRx source to playback-first, network-isolated previews."""
from pathlib import Path
import hashlib
import shutil
import sys

root = Path.cwd()
rel = Path('app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview')
dest = root / rel / 'SeekPreviewStore.kt'
replacement = root / 'KSPLAYER-REPLACEMENT' / 'SeekPreviewStore.kt'
backup = root / rel / 'SeekPreviewStore.kt.before-playback-first'
ui = root / 'app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/SeekThumbnailStrip.kt'
worker = root / rel / 'NetworkSeekFrameRetriever.kt'
bridge = root / rel / 'NetworkSeekHttpBridge.kt'

missing = [str(p.relative_to(root)) for p in [dest, replacement, ui, worker, bridge] if not p.is_file()]
if missing:
    sys.exit('Missing required project file(s): ' + ', '.join(missing))

checks = [(ui, 'SeekPreviewStore('), (worker, 'persistentFrameAt('), (bridge, 'fun open('),
          (dest, 'class SeekPreviewStore(')]
for file, snippet in checks:
    if snippet not in file.read_text():
        sys.exit(f'Incompatible source: {file.relative_to(root)} lacks {snippet!r}. No changes made.')

new = replacement.read_bytes()
old = dest.read_bytes()
if new == old:
    print('Already using playback-first thumbnail engine; nothing to change.')
    sys.exit(0)
if not backup.exists():
    shutil.copy2(dest, backup)
    print('Saved original source:', backup.relative_to(root))
else:
    print('Preserving previous backup:', backup.relative_to(root))

temp = dest.with_name(dest.name + '.new')
temp.write_bytes(new)
temp.replace(dest)
print('Installed network-isolated on-demand thumbnail engine.')
print('Source SHA256:', hashlib.sha256(new).hexdigest())
print('Run: bash BUILD-MOBILE-APK.sh --install')
