#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
SRC='../mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview'
kotlinc speedCompileStubs/*.kt "$SRC/SeekByteBlockCache.kt" "$SRC/PlaybackRangeCapture.kt" "$SRC/LargeForegroundRangePolicy.kt" "$SRC/NetworkSeekHttpBridge.kt" -d "$TMP/proxy.jar"
echo 'PASS native-preview HTTP bridge compiled against API stubs'
