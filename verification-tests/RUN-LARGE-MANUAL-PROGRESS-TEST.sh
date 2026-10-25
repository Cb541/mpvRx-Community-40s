#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
SRC='../mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview'
KOTLIN_LIB="$(dirname "$(command -v kotlinc)")/../lib"
kotlinc "$SRC/SeekPreviewStore.kt" "$SRC/ScrubContinuationPolicy.kt" "$SRC/ManualPreviewTuning.kt" "$SRC/AheadPacing.kt" "$SRC/ForegroundPreviewAdmission.kt" \
  android.kt graphics.kt log.kt clock.kt stubs.kt playback.kt aheadPlaybackStub.kt previewProgressLargeHarness.kt \
  -cp "$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar" -nowarn -include-runtime -d "$TMP/progress.jar"
java -cp "$TMP/progress.jar:$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar" PreviewProgressLargeHarnessKt
UI='../mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/SeekThumbnailStrip.kt'
grep -Fq 'val pendingProgress = store?.previewProgress?.collectAsState()?.value' "$UI"
grep -Fq 'Frames ${seekStripTimestamp(targetMs)}–${seekStripTimestamp(gridSample.timeMs)}' "$UI"
grep -Fq 'Generating ${currentProgress?.step?.percent ?: 0}%' "$UI"
echo 'PASS: compact frame-time and milestone-percent pill renders beneath preview'
