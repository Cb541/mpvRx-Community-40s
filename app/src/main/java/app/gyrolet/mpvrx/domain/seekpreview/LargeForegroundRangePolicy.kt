/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.seekpreview

/** Transport-independent rules for low-latency foreground reads of large media.
 * All responses must be verifiably seekable identity-encoded HTTP 206 ranges.
 */
internal object LargeForegroundRangePolicy {
  const val LARGE_THRESHOLD = 20_000_000_000L
  private const val MAX_WINDOW = 8L * 1024L * 1024L

  data class Span(val start: Long, val end: Long, val total: Long) {
    val bytes: Long get() = end - start + 1L
  }

  fun requested(start: Long, explicitEnd: Long?, total: Long): Span? {
    if (total < LARGE_THRESHOLD || start < 0L || start >= total ||
        (explicitEnd != null && explicitEnd < start)) return null
    // Preserve finite request bounds; constrain open ranges so a single preview
    // can NEVER initiate a download for the rest of a 20+ GB movie.
    val end = minOf(total - 1L, explicitEnd ?: (start + minOf(MAX_WINDOW, total - start) - 1L))
    return Span(start, end, total)
  }

  /** Any manual uncached preview, independent of movie size, can receive its
   * first origin byte without waiting for the full 1 MiB aligned-cache block.
   * The response must still pass [validated], never be an unverified HTTP 200,
   * and must not alter actual mpv playback I/O. This intentionally preserves
   * [requested]'s >=20 GB policy for previous callers/tests.
   */
  fun requestedManual(start: Long, explicitEnd: Long?, total: Long): Span? {
    if (total <= 0L || start < 0L || start >= total ||
        (explicitEnd != null && explicitEnd < start)) return null
    val end = minOf(total - 1L, explicitEnd
      ?: (start + minOf(MAX_WINDOW, total - start) - 1L))
    return Span(start, end, total)
  }

  fun validated(contentRange: String?, contentEncoding: String?,
    status: Int, wanted: Span, bodyLength: Long?): Span? {
    if (status != 206 ||
      (contentEncoding != null && !contentEncoding.equals("identity", ignoreCase = true))) return null
    val match = contentRange?.let {
      Regex("""^bytes\s+(\d+)-(\d+)/(\d+)$""", RegexOption.IGNORE_CASE).matchEntire(it)
    } ?: return null
    val first = match.groupValues[1].toLongOrNull() ?: return null
    val last = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull() ?: return null
    if (first != wanted.start || last != wanted.end || total != wanted.total) return null
    val bytes = last - first + 1L
    if (bytes <= 0L || (bodyLength != null && bodyLength >= 0L && bodyLength != bytes)) return null
    return Span(first, last, total)
  }
}
