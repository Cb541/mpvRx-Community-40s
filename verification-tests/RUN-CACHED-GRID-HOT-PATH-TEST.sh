#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
SRC='../mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview'
KOTLIN_LIB="$(dirname "$(command -v kotlinc)")/../lib"
kotlinc "$SRC/SeekPreviewStore.kt" "$SRC/ScrubContinuationPolicy.kt" "$SRC/ManualPreviewTuning.kt" "$SRC/AheadPacing.kt" "$SRC/ForegroundPreviewAdmission.kt" \
 android.kt graphics.kt log.kt clock.kt stubs.kt playback.kt aheadPlaybackStub.kt cachedGridHotPathHarness.kt \
 -cp "$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar" -nowarn -include-runtime -d "$TMP/cached-grid-test.jar"
java -cp "$TMP/cached-grid-test.jar:$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar" CachedGridHotPathHarnessKt
# Cached grid JPEGs appear with their own real timestamps, while an
# approximate sample must NOT suppress accurate FFmpeg extraction.
UI='../mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/SeekThumbnailStrip.kt'
grep -Fq 'store?.primeCachedPreview(previewMs, 6_000L)' "$UI"
grep -Fq 'store?.request(listOf(requestedPreviewMs))' "$UI"
grep -Fq 'Frames ${seekStripTimestamp(targetMs)}–${seekStripTimestamp(gridSample.timeMs)}' "$UI"
! grep -Fq 'useCachedPreviewOnly()' "$UI"
echo 'PASS: UI shows real-timestamp grid samples and still requests precise frames'
