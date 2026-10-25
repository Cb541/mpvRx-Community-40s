#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
TEMP="$(mktemp -d)";trap 'rm -rf "$TEMP"' EXIT
SRC='../mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview'
KOTLIN_LIB="$(dirname "$(command -v kotlinc)")/../lib"
kotlinc "$SRC/SeekPreviewStore.kt" "$SRC/ScrubContinuationPolicy.kt" "$SRC/ManualPreviewTuning.kt" "$SRC/AheadPacing.kt" "$SRC/ForegroundPreviewAdmission.kt" \
  android.kt graphics.kt log.kt clock.kt stubs.kt playback.kt aheadPlaybackStub.kt aheadPlaybackHarness.kt \
  -cp "$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar" -include-runtime -d "$TEMP/ahead-test.jar"
java -cp "$TEMP/ahead-test.jar:$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar" AheadPlaybackHarnessKt
