/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.seekpreview

/**
 * Stage 3.2: playback-first, single-decoder ahead pacing.
 * A shorter network cooldown is permitted ONLY with sustained playback progress
 * and a *known* healthy buffered duration. Unknown buffer => Stage 3.1's 12s.
 * Each session still retains Stage 3.1's shared 6 MiB/7 s hard byte/time caps.
 */
internal object AheadPacing {
  fun cooldownMs(bufferedSeconds: Double?, healthyTicks: Int): Long {
    if (healthyTicks < 2 || bufferedSeconds == null || !bufferedSeconds.isFinite()) return 12_000L
    return when {
      bufferedSeconds >= 90.0 && healthyTicks >= 5 -> 4_000L
      bufferedSeconds >= 90.0 -> 7_000L // warm-up: no immediate 4s remote cadence
      bufferedSeconds >= 60.0 && healthyTicks >= 5 -> 7_000L
      else -> 12_000L
    }
  }

  fun framesPerSuccessfulSession(bufferedSeconds: Double?, healthyTicks: Int): Int = when {
    healthyTicks >= 2 && bufferedSeconds != null && bufferedSeconds.isFinite() && bufferedSeconds >= 90.0 -> 4
    healthyTicks >= 2 && bufferedSeconds != null && bufferedSeconds.isFinite() && bufferedSeconds >= 45.0 -> 3
    else -> 2
  }

  /** Media size comes from the HTTP Content-Range total in the playback-fed
   * cache, never from the name of the file or an estimated bitrate. Decimal
   * GB matches the user's 20+ GB threshold. Unknown size stays conservative.
   */
  enum class SizeBand { SMALL, MEDIUM, LARGE, UNKNOWN }
  data class Plan(
    val band: SizeBand,
    val cooldownMs: Long,
    val maxMissingBlocks: Int,
    val deadlineMs: Long,
    val burstFrames: Int,
    val scanSlots: Int,
    val maxRangeWindowBytes: Long,
    val attemptsPerCacheRevision: Int,
  )

  fun sizeBand(bytes: Long?): SizeBand = when {
    bytes == null || bytes <= 0L -> SizeBand.UNKNOWN
    bytes >= 20_000_000_000L -> SizeBand.LARGE
    bytes <= 5_000_000_000L -> SizeBand.SMALL
    else -> SizeBand.MEDIUM
  }

  /** One network worker regardless of band. The fast profile requires both
   * known size and verified recent playback progress plus a substantial
   * buffer. Null / stale buffer never unlocks the fast profile.
   *
   * maxMissingBlocks bounds newly downloaded 1 MiB blocks for the WHOLE
   * persistent FFmpeg session, not per request or per thumbnail.
   */
  fun plan(bytes: Long?, bufferedSeconds: Double?, healthyTicks: Int): Plan {
    val band = sizeBand(bytes)
    val buffer = bufferedSeconds?.takeIf { it.isFinite() && it >= 0.0 }
    // Small files ramp up early. Other sizes retain their original health gates.
    val small = band == SizeBand.SMALL
    val strong = healthyTicks >= (if (small) 3 else 4) &&
      buffer != null && buffer >= (if (small) 50.0 else 90.0)
    val warm = healthyTicks >= 2 &&
      buffer != null && buffer >= (if (small) 20.0 else 45.0)
    val mib = 1024L * 1024L
    return when (band) {
      SizeBand.SMALL -> when {
        // Fast single-decoder burst, bounded by 1 MiB-block allowance and
        // deadline. Playback/seek safety gates still apply to every frame.
        strong -> Plan(band, 250, 32, 12_000, 8, 900, 8*mib, 20)
        warm -> Plan(band, 750, 16, 9_000, 8, 900, 4*mib, 12)
        else -> Plan(band, 2_500, 6, 5_000, 4, 900, 4*mib, 4)
      }
      SizeBand.MEDIUM -> when {
        strong -> Plan(band, 2_000, 16, 9_000, 7, 360, 4*mib, 10)
        warm -> Plan(band, 5_000, 8, 7_000, 4, 360, 4*mib, 6)
        else -> Plan(band, 12_000, 4, 5_000, 2, 360, 4*mib, 3)
      }
      SizeBand.LARGE -> when {
        strong -> Plan(band, 18_000, 4, 6_000, 2, 60, 4*mib, 2)
        warm -> Plan(band, 30_000, 2, 4_000, 1, 60, 2*mib, 1)
        else -> Plan(band, 45_000, 1, 3_000, 1, 60, 1*mib, 1)
      }
      SizeBand.UNKNOWN -> Plan(band, 12_000, 6, 7_000, 2, 24, 4*mib, 3)
    }
  }

  /** Decimal-GB threshold. Verified files STRICTLY below 20 GB get
   * movie-wide 24s coverage before any 12s backfill. Size-unknown and
   * 20GB+ media retain their previous conservative scheduling and safety gates.
   * This changes ordering only, not download or decoder admission limits.
   */
  fun fullMovieCoarseFirst(bytes: Long?): Boolean =
    bytes != null && bytes > 0L && bytes < 20_000_000_000L

  /** Keep ahead decoding off CPU and network immediately after the user finishes
   * scrubbing a >=20GB remote file. For <20GB or unknown size, return zero:
   * Stage 3.3 behaviour is unchanged for those media.
   */
  /** Huge verified files may still generate sparse ahead frames, but at a much
   * lighter cadence than small/medium media. Unknown size stays conservative.
   * Playback's already-decoded frame capture is unaffected.
   */
  fun allowSpeculativeAhead(bytes: Long?): Boolean = when (sizeBand(bytes)) {
    SizeBand.SMALL, SizeBand.MEDIUM, SizeBand.LARGE -> true
    SizeBand.UNKNOWN -> false // Unknown size may still be huge.
  }

  fun manualPreviewQuietMs(bytes: Long?): Long =
    if (sizeBand(bytes) == SizeBand.LARGE) 6_000L else 0L

  /** Rotate past difficult slots rather than attempting the same MKV index on every new block. */
  const val FAILED_SLOT_RETRY_MS: Long = 30_000L
}
