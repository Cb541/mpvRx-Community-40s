#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
S='../mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview'
KOTLIN_LIB="$(dirname "$(command -v kotlinc)")/../lib"
kotlinc -nowarn "$S/SeekPreviewStore.kt" "$S/ScrubContinuationPolicy.kt" "$S/AheadPacing.kt" "$S/ForegroundPreviewAdmission.kt" \
  android.kt graphics.kt log.kt clock.kt stubs.kt stage36PlaybackStub.kt \
  largeManualPreemptionStub.kt stage36JpegHarness.kt \
  -cp "$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar" \
  -include-runtime -d "$T/stage36.jar"
java -cp "$T/stage36.jar:$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar" Stage36JpegHarnessKt
printf '\nPASS: Stage 3.6 JPEG/screenshot maintenance verification (isolated JVM, not Android playback)\n'
