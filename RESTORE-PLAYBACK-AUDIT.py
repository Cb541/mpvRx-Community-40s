#!/usr/bin/env python3
from pathlib import Path
import shutil
base=Path('app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview')
paths=[base/'SeekPreviewStore.kt',base/'SeekByteBlockCache.kt',base/'NetworkSeekHttpBridge.kt']
for path in paths:
    backup=Path(str(path)+'.before-playback-audit')
    if backup.exists():
        shutil.copy2(backup,path)
        backup.unlink()
        print('Restored',path)
    else: print('No backup; untouched:',path)
