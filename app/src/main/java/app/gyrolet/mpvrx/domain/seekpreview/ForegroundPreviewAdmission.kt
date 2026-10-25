/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.seekpreview

/** Post-seek protection for *user-requested* previews. Playback still wins.
 *
 * Known-size small and medium files can now use the same short, evidence-based
 * foreground grace previously reserved for >=20 GB streams. The speculative
 * ahead worker retains its prior rules (large-only fast grace) to avoid
 * increasing network/decoder competition after playback seeks.
 * Unknown-size media deliberately retain the original 2-second grace.
 */
internal object ForegroundPreviewAdmission {
  fun allowFastGrace(band: AheadPacing.SizeBand, manualRequest: Boolean): Boolean =
    band == AheadPacing.SizeBand.LARGE ||
      (manualRequest && (band == AheadPacing.SizeBand.SMALL || band == AheadPacing.SizeBand.MEDIUM))

  fun seekGraceMs(
    fastGraceAllowed: Boolean, paused: Boolean, coreIdle: Boolean?,
    bufferedSeconds: Double?, advancedAfterSeek: Boolean,
  ): Long {
    if (!fastGraceAllowed) return 2_000L
    val buffered = bufferedSeconds?.takeIf { it.isFinite() && it >= 0.0 }
    // A confirmed paused movie has no actively advancing playback decoder;
    // the foreground UI can admit the user's thumbnail request sooner once
    // the real `seeking` and `paused-for-cache` guards have both cleared.
    // Still retain a grace period and fall back to a longer one if the mpv
    // core reports idle/no ready buffered data. Playback reads ALWAYS win.
    if (paused) return when {
      coreIdle == false && buffered != null && buffered >= 20.0 -> 350L
      coreIdle == false && buffered != null && buffered >= 8.0 -> 650L
      else -> 1_000L
    }
    // Do not relax during actual playback without verified clock progress.
    if (coreIdle == false && buffered != null && advancedAfterSeek && buffered >= 45.0) return 250L
    return 2_000L
  }
}
