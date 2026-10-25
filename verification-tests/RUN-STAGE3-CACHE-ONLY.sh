#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT
SRC='../mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview'
kotlinc "$SRC/SeekByteBlockCache.kt" cacheOnlyHarness.kt -include-runtime -d "$TEMP/cache-only.jar"
java -jar "$TEMP/cache-only.jar"
