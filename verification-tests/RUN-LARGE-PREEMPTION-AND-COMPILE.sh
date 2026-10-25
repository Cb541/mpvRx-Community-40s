#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
SRC='../mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview'
KOTLIN_LIB="$(dirname "$(command -v kotlinc)")/../lib"
# Stage 3.3's stubs work across both base versions. A stubbed compile is not an Android build.
kotlinc "$SRC/SeekPreviewStore.kt" "$SRC/ScrubContinuationPolicy.kt" "$SRC/ManualPreviewTuning.kt" "$SRC/AheadPacing.kt" "$SRC/ForegroundPreviewAdmission.kt" \
 android.kt graphics.kt log.kt clock.kt stubs.kt playback.kt \
 largeManualPreemptionStub.kt largeManualPreemptionHarness.kt \
 -cp "$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar" -include-runtime -d "$T/manual.jar"
java -cp "$T/manual.jar:$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar" LargeManualPreemptionHarnessKt
