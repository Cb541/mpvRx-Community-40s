/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.seekpreview

/**
 * Nuvio Enhanced-informed *safe* preview tuning.
 *
 * Hardware decoding and approximate media timestamps are deliberately NOT enabled:
 * they would create competing remote I/O or mislabel frames on existing mpvRx builds.
 * The native FFmpeg decoder remains the only foreground network extractor.
 * Smaller conversion targets reduce RGBA/scale allocations, while paused-only
 * context reuse avoids repeated remote container/header probes.
 */
internal object ManualPreviewTuning {
  /** Max output edge in pixels, NOT video-decoder input resolution. */
  fun dimension(bytes: Long?): Int = when (AheadPacing.sizeBand(bytes)) {
    AheadPacing.SizeBand.LARGE -> 416 // >=20GB: -34.0% output pixels vs 512
    AheadPacing.SizeBand.MEDIUM -> 448 // 5..20GB: -23.4% output pixels
    AheadPacing.SizeBand.SMALL, AheadPacing.SizeBand.UNKNOWN -> 512
  }

  /**
   * Nuvio retains its frame grabber for ~30 s. Keep this longer warm period
   * ONLY if mpv is actually paused. When playing, use the original 8 s timeout
   * so the extra native context and HTTP socket don't linger.
   */
  fun warmIdleMs(paused: Boolean): Long = if (paused) 30_000L else 8_000L
}
