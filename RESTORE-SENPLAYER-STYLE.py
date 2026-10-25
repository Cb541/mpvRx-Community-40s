#!/usr/bin/env python3
"""Guarded rollback to the previous playback-first SeekPreviewStore."""
from pathlib import Path
import hashlib, sys
ROOT = Path.cwd()
DEST = ROOT / "app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview/SeekPreviewStore.kt"
BACKUP = DEST.with_name(DEST.name + ".before-senplayer-style")
OLD = "58e9037e5b28ee1e0c0dfb325bf8de8fdcdefab23368b9defb3ed3b02281302e"
NEW = "6d155e08f906b9292b3b023d1c0fe46a34e2f8ee24f861f5f041bec4873dc9b6"
def digest(p): return hashlib.sha256(p.read_bytes()).hexdigest()
if not DEST.is_file() or not BACKUP.is_file(): sys.exit("Missing source or backup; not modifying files.")
if digest(BACKUP) != OLD: sys.exit("Unexpected backup hash; not modifying files.")
if digest(DEST) == OLD:
    print("Already rolled back.")
    sys.exit(0)
if digest(DEST) != NEW: sys.exit("Current source was edited after install; refusing to overwrite it.")
TMP = DEST.with_name(DEST.name + ".restore-tmp")
TMP.write_bytes(BACKUP.read_bytes())
TMP.replace(DEST)
print("Restored previous playback-first thumbnail engine. Rebuild mpvRx to apply.")
