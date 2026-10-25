#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
KOTLIN_FILE=../mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview/ExtractorPageRoutingPolicy.kt
KOTLIN_JAR=$(mktemp --suffix=.jar)
trap 'rm -f "$KOTLIN_JAR"' EXIT
kotlinc "$KOTLIN_FILE" youtubeRoutingHarness.kt -include-runtime -d "$KOTLIN_JAR"
java -jar "$KOTLIN_JAR"
