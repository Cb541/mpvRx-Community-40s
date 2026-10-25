#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
SRC='../mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview'
kotlinc "$SRC/AheadPacing.kt" "$SRC/ForegroundPreviewAdmission.kt" foregroundAdmissionHarness.kt \
 -include-runtime -d "$TMP/foreground-admission.jar"
java -jar "$TMP/foreground-admission.jar"
