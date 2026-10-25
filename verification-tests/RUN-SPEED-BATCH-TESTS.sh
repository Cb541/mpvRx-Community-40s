#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
SRC='../mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview'
kotlinc "$SRC/SeekByteBlockCache.kt" speedBatchHarness.kt -include-runtime -d "$TMP/speed-tests.jar"
java -jar "$TMP/speed-tests.jar"
