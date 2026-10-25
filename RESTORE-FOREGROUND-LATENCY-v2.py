#!/usr/bin/env python3
from pathlib import Path
import shutil
p = Path("app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview/SeekPreviewStore.kt")
backup = p.with_suffix(p.suffix + ".before-foreground-latency-v2")
if not backup.exists():
    raise SystemExit("Backup not found; no changes made.")
shutil.copy2(backup,p)
print("Restored SeekPreviewStore.kt from its pre-latency-v2 backup.")
