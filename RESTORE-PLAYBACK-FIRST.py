#!/usr/bin/env python3
"""Restore source that INSTALL-PLAYBACK-FIRST.py originally replaced."""
from pathlib import Path
import shutil
import sys
root = Path.cwd()
rel = Path('app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview')
original = root / rel / 'SeekPreviewStore.kt'
backup = root / rel / 'SeekPreviewStore.kt.before-playback-first'
if not backup.exists():
    sys.exit('No backup found. Nothing changed.')
shutil.copy2(backup, original)
print('Restored original SeekPreviewStore.kt from pre-upgrade backup.')
print('Run: bash BUILD-MOBILE-APK.sh --install')
