#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
SRC='../mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview'
kotlinc "$SRC/AheadPacing.kt" "$SRC/ManualPreviewTuning.kt" manualPreviewTuningHarness.kt \
  -include-runtime -nowarn -d "$TMP/tuning.jar"
java -jar "$TMP/tuning.jar"
# Check the foreground and paused-neighbor decoder calls are wired to policy.
grep -q 'decoder?.persistentFrameAt(slot, dimension = previewDimension)' "$SRC/SeekPreviewStore.kt" "$SRC/ScrubContinuationPolicy.kt"
grep -q 'decoder.persistentFrameAt(neighbor, dimension = 416)' "$SRC/SeekPreviewStore.kt" "$SRC/ScrubContinuationPolicy.kt"
grep -q 'warmIdleMs(PlaybackSession.state.value.paused)' "$SRC/SeekPreviewStore.kt" "$SRC/ScrubContinuationPolicy.kt"
grep -q 'dimension, keyframesOnly)' "$SRC/NetworkSeekFrameRetriever.kt"
echo "PASS: decoding call sites wired to the tuning policy"
