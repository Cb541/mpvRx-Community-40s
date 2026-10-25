/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.seekpreview

/**
 * Keep successfully decoded frames during finger movement, but never after a
 * real playback-seek interruption, closed session or invalidated transport.
 * This only governs whether a *finished* bitmap is accepted into cache; the
 * UI still checks the bitmap's actual timestamp before displaying it.
 */
internal object ScrubContinuationPolicy {
  fun shouldCacheCompleted(
    sessionActive: Boolean,
    requestEpoch: Long,
    currentEpoch: Long,
    transportInvalidated: Boolean,
  ): Boolean = sessionActive && requestEpoch == currentEpoch && !transportInvalidated

  /** Request supersession may alter NEXT work, never interrupt CURRENT work. */
  fun inFlightSuperseded(activeFrameMs: Long, latestFrameMs: Long?): Boolean =
    activeFrameMs >= 0L && activeFrameMs != latestFrameMs
}
