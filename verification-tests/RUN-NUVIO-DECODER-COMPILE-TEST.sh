#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
TEMP="$(mktemp -d)";trap 'rm -rf "$TEMP"' EXIT
SRC='../mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview'
kotlinc nuvioTuningStubs/*.kt "$SRC/NetworkSeekFrameRetriever.kt" \
  -nowarn -include-runtime -d "$TEMP/native-tune.jar"
java -jar "$TEMP/native-tune.jar"
