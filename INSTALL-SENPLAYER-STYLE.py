#!/usr/bin/env python3
"""Guarded, atomic upgrade from playback-first to cooperative pause-burst previews."""
from pathlib import Path
import hashlib, shutil, sys

ROOT = Path.cwd()
REL = Path("app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview/SeekPreviewStore.kt")
DEST = ROOT / REL
REPLACEMENT = ROOT / "SENPLAYER-ADAPTER" / "SeekPreviewStore.kt"
BACKUP = ROOT / "app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview/SeekPreviewStore.kt.before-senplayer-style"
OLD = "58e9037e5b28ee1e0c0dfb325bf8de8fdcdefab23368b9defb3ed3b02281302e"
NEW = "6d155e08f906b9292b3b023d1c0fe46a34e2f8ee24f861f5f041bec4873dc9b6"

def digest(p): return hashlib.sha256(p.read_bytes()).hexdigest()
if not DEST.is_file() or not REPLACEMENT.is_file():
    sys.exit("Missing mpvRx source or SENPLAYER-ADAPTER/SeekPreviewStore.kt. Extract ZIP at the project root.")
if digest(REPLACEMENT) != NEW:
    sys.exit("Unexpected replacement file contents; aborting without changes.")
actual = digest(DEST)
if actual == NEW:
    print("Already installed. No files changed.")
    sys.exit(0)
if actual != OLD:
    sys.exit("Your SeekPreviewStore.kt is not the exact playback-first version this patch targets. "
             "Refusing to overwrite it. Please use the playback-first patch first, or provide your current file for a merge.")
if BACKUP.exists():
    if digest(BACKUP) != OLD: sys.exit("Existing backup is unexpected. Refusing to overwrite.")
else:
    shutil.copy2(DEST, BACKUP)
TMP = DEST.with_name(DEST.name + ".sen-style-tmp")
TMP.write_bytes(REPLACEMENT.read_bytes())
TMP.replace(DEST)
print("Installed SenPlayer-inspired cooperative preview scheduler.")
print("No changes to PlaybackSession, playback URL/proxy, native AAR, or thumbnail UI.")
print("Build: bash BUILD-MOBILE-APK.sh --install")
