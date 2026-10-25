// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.domain.seekpreview

import kotlin.math.abs

/**
 * Foreground preview job stability while a thumb moves slightly on the seek bar.
 * Playback's true seek position is unaffected; only uncached preview requests
 * are held until their active decoder finishes, or the movement is meaningful.
 */
internal object SeekPreviewTargetStability {
  /** Roughly one screen pixel of a long movie without allowing huge drift. */
  fun toleranceMs(durationMs: Long): Long =
    (durationMs / 900L).coerceIn(4_000L, 18_000L)

  fun shouldHoldActiveTarget(
    currentTargetMs: Long,
    selectedTargetMs: Long,
    durationMs: Long,
    isProcessing: Boolean,
  ): Boolean = isProcessing &&
    abs(selectedTargetMs - currentTargetMs) <= toleranceMs(durationMs)
}
