#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
SRC='../mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview'
kotlinc "$SRC/SeekByteBlockCache.kt" "$SRC/PlaybackRangeCapture.kt" sharedCaptureHarness.kt -include-runtime -d "$TMP/shared-cache.jar"
java -jar "$TMP/shared-cache.jar"
echo 'PASS JVM shared-capture tests. This is not an Android playback test.'
kotlinc "$SRC/PlaybackRangeResponsePolicy.kt" responsePolicyHarness.kt -include-runtime -d "$TMP/response-validation.jar"
java -jar "$TMP/response-validation.jar"
kotlinc "$SRC/SeekByteBlockCache.kt" "$SRC/PlaybackRangeCapture.kt" sharedStressHarness.kt -include-runtime -d "$TMP/stress.jar"
java -jar "$TMP/stress.jar"
