#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
SRC='../mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview'
kotlinc httpCompileStubs/*.kt "$SRC/SeekByteBlockCache.kt" "$SRC/PlaybackRangeCapture.kt" \
  "$SRC/PlaybackRangeResponsePolicy.kt" "$SRC/AheadPacing.kt" "$SRC/SharedPlaybackHttpBridge.kt" \
  "$SRC/SharedPlaybackTransportRegistry.kt" "$SRC/ExtractorPageRoutingPolicy.kt" -d "$TMP/proxy.jar"
echo 'PASS isolated shared playback HTTP proxy Kotlin compilation (API stubs, not Android compilation)'
