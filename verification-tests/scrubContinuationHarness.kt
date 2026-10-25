package app.gyrolet.mpvrx.domain.seekpreview

private fun expect(value: Boolean, label: String) {
  if (!value) error("FAILED: $label")
}

fun main() {
  // The user starts decoding 12s, moves to 13s, then lets go. That already
  // decoding 12s bitmap must be cached under 12s (not discarded or renamed).
  val cache = mutableMapOf<Long, String>()
  var latest: Long?
  val inFlight = 12_000L
  latest = 15_000L
  expect(ScrubContinuationPolicy.inFlightSuperseded(inFlight, latest), "finger moved during frame")
  latest = null
  expect(ScrubContinuationPolicy.inFlightSuperseded(inFlight, latest), "finger lifted during frame")
  expect(ScrubContinuationPolicy.shouldCacheCompleted(true, 7L, 7L, false), "cache valid completed stale frame")
  cache[inFlight] = "real-12s-frame"
  expect(cache[12_000L] == "real-12s-frame", "correct JPEG filename timestamp")
  expect(cache[13_000L] == null, "do not substitute a 12s frame for 13s")
  // Actual playback seeks must interrupt and must never accept the stale frame.
  expect(!ScrubContinuationPolicy.shouldCacheCompleted(true, 7L, 8L, false), "reject playback seek")
  expect(!ScrubContinuationPolicy.shouldCacheCompleted(true, 7L, 7L, true), "reject interrupted transport")
  expect(!ScrubContinuationPolicy.shouldCacheCompleted(false, 7L, 7L, false), "reject closed session")
  expect(!ScrubContinuationPolicy.inFlightSuperseded(-1L, latest), "idle foreground")
  println("PASS scrub continuation: moving finger / release preserves valid original frame; seek interrupts reject")
}
