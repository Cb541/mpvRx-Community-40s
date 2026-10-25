#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
KOTLIN_LIB="$(dirname "$(command -v kotlinc)")/../lib"
SOURCE="../mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
kotlinc "$SOURCE/SeekPreviewStore.kt" "$SOURCE/ScrubContinuationPolicy.kt" "$SOURCE/AheadPacing.kt" \
  android.kt graphics.kt log.kt clock.kt stubs.kt playback.kt sharedRegistryStub.kt persistentStub.kt harness.kt \
  -cp "$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar" \
  -include-runtime -d "$TMP/foreground.jar"
java -cp "$TMP/foreground.jar:$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar" HarnessKt
kotlinc "$SOURCE/SeekByteBlockCache.kt" rangeHarness.kt -include-runtime -d "$TMP/byte-cache.jar"
java -jar "$TMP/byte-cache.jar"
jar tf ../mpvRx-2.7.2/app/libs/mpvlib-seek-native.aar >/dev/null || true
printf '\nAll isolated JVM verification tests passed. This is NOT an Android playback test.\n'
