/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.seekpreview

/**
 * Validate a playback HTTP response before allowing its bytes into a shared
 * preview cache. This is metadata validation only; playback remains free to
 * consume any response, even one this policy declines to cache.
 */
internal object PlaybackRangeResponsePolicy {
  data class Span(val start: Long, val total: Long, val mime: String, val expectedBytes: Long)
  private val contentRange = Regex("""^bytes\s+(\d+)-(\d+)/(\d+)$""", RegexOption.IGNORE_CASE)

  fun eligible(
    status: Int,
    contentRangeHeader: String?,
    contentLengthHeader: String?,
    contentEncodingHeader: String?,
    contentTypeHeader: String?,
    requestStart: Long? = null,
  ): Span? {
    if (contentEncodingHeader != null && !contentEncodingHeader.equals("identity", ignoreCase = true)) return null
    val mime = contentTypeHeader?.substringBefore(';')?.trim().orEmpty().ifBlank { "application/octet-stream" }
    val length = contentLengthHeader?.trim()?.toLongOrNull()
    if (length != null && length <= 0) return null
    if (status == 200) {
      // If the origin ignored a range, playback might still be able to stream it,
      // but cache metadata must not pretend the response started at that offset.
      if (requestStart != null && requestStart != 0L) return null
      if (!contentRangeHeader.isNullOrBlank()) return null
      val total = length ?: return null
      return Span(0L, total, mime, total)
    }
    if (status != 206) return null
    val match = contentRangeHeader?.trim()?.let { contentRange.matchEntire(it) } ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull() ?: return null
    if (start < 0 || end < start || total <= end) return null
    val count = end - start + 1
    if (count <= 0 || (length != null && length != count)) return null
    if (requestStart != null && requestStart != start) return null
    return Span(start, total, mime, count)
  }
}
