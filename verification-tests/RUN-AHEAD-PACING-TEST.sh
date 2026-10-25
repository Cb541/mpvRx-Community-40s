#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT
SRC='../mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview'
kotlinc "$SRC/AheadPacing.kt" aheadPacingHarness.kt -include-runtime -d "$TEMP/test.jar"
java -jar "$TEMP/test.jar"
