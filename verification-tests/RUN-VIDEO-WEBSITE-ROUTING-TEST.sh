#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
JAR="$(mktemp --suffix=.jar)"
trap 'rm -f "$JAR"' EXIT
kotlinc \
  ../mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview/ExtractorPageRoutingPolicy.kt \
  videoWebsiteRoutingHarness.kt -include-runtime -d "$JAR"
java -jar "$JAR"
