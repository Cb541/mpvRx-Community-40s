#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
TASKDIR="$(mktemp -d)"
trap 'rm -rf "$TASKDIR"' EXIT
SRC="../mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview"
kotlinc "$SRC/SeekByteBlockCache.kt" budgetHarness.kt -include-runtime -d "$TASKDIR/budget.jar"
java -jar "$TASKDIR/budget.jar"
