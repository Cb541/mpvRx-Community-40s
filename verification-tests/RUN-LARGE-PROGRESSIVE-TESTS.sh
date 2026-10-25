#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
SRC='../mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview'
kotlinc "$SRC/LargeForegroundRangePolicy.kt" largeProgressiveHarness.kt -include-runtime -d "$T/range.jar"
java -jar "$T/range.jar"
bash RUN-SPEED-NETWORK-COMPILE.sh 2>&1 | grep -E '^PASS'
