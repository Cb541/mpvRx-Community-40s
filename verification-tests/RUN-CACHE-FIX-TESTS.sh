#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
SRC='../mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview'
kotlinc "$SRC/SeekByteBlockCache.kt" "$SRC/PlaybackRangeCapture.kt" cacheFragmentHarness.kt -include-runtime -d "$TMP/cache-fix-tests.jar"
java -jar "$TMP/cache-fix-tests.jar"
