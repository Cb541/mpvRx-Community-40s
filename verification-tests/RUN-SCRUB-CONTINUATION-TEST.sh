#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
SRC="$ROOT/mpvRx-2.7.2/app/src/main/java/app/gyrolet/mpvrx"
# Compile the very same policy referenced by production SeekPreviewStore.
kotlinc "$SRC/domain/seekpreview/ScrubContinuationPolicy.kt" \
  "$ROOT/verification-tests/scrubContinuationHarness.kt" -include-runtime -d "$TMP/scrub.jar"
java -jar "$TMP/scrub.jar"
# Check that UI emits immediately, without a cancellable 75ms delay, and the
# native scrub interrupt has really been removed from the production path.
python3 - "$SRC" <<'PYTEST'
from pathlib import Path
import sys
p=Path(sys.argv[1])
ui=(p/'ui/player/controls/components/SeekThumbnailStrip.kt').read_text()
s=(p/'domain/seekpreview/SeekPreviewStore.kt').read_text()
assert 'delay(75L)' not in ui
assert 'store?.request(listOf(requestedPreviewMs))' in ui
assert 'ScrubContinuationPolicy.inFlightSuperseded(running, next)' in s
assert 'ScrubContinuationPolicy.shouldCacheCompleted(' in s
start=s.index('fun request(timesMs: List<Long>)')
end=s.index('fun warmAround',start) if 'fun warmAround' in s[start:] else s.index('private suspend fun cacheOnlyAheadLoop',start)
assert 'if (decodingNeighbor) {' in s[start:end]
assert 'invalidateTransport(playbackSeek = false)' in s[start:end]
assert 'else if (ScrubContinuationPolicy.inFlightSuperseded(running, next))' in s[start:end]
assert 'if (ScrubContinuationPolicy.inFlightSuperseded(running, next)) {\n      invalidateTransport' not in s[start:end]
assert 'val stillWanted = isStillWanted(slot)' in s
assert 'publish(slot, frame)' in s
assert 'if (frame != null && stillWanted && decoder != null' in s
print('PASS source wiring: no per-move abort, immediate queue, save stale success only at original slot')
PYTEST
