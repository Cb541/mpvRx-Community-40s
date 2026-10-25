#!/usr/bin/env bash
# Build the modified mpvRx seek-thumbnail player as an installable Android debug APK.
# Requires JDK 17+, Android SDK platform 37, NDK 27.3.13750724, CMake 3.22.1,
# and online Gradle/Maven dependency access.
set -euo pipefail
cd "$(dirname "$0")"
bash ./gradlew :app:assembleStandardDebug -PtargetAbi=arm64-v8a --no-daemon
APK="$(find app/build/outputs/apk/standard/debug -type f -name '*arm64-v8a*.apk' -print -quit)"
if [[ -z "$APK" ]]; then
  echo 'No arm64-v8a APK found.' >&2
  exit 1
fi
echo "Built APK: $PWD/$APK"
if [[ "${1:-}" == '--install' ]]; then
  adb install -r "$APK"
fi
