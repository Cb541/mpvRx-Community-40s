/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.seekpreview

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response as UpstreamResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/**
 * EXPERIMENTAL shared playback/preview byte transport.
 *
 * Playback receives an ordinary, streamed loopback HTTP response. Nothing
 * waits for disk writes. Complete blocks from those bytes are opportunistically
 * copied into the SAME SeekByteBlockCache used by the existing preview proxy.
 *
 * Important: playback uses its OWN HTTP client and is never affected by
 * preview cancellation/suspension. This is specifically designed to avoid the
 * earlier regression where canceling thumbnail reads also stopped playback.
 */
internal class SharedPlaybackHttpBridge private constructor(
  private val source: String,
  private val headers: Map<String, String>,
  private val preview: NetworkSeekHttpBridge,
) : NanoHTTPD("127.0.0.1", 0), AutoCloseable {
  private val secret = ByteArray(24).also { SecureRandom().nextBytes(it) }
    .joinToString("") { "%02x".format(it) }
  private val client = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false)
    .connectTimeout(12, TimeUnit.SECONDS)
    .readTimeout(35, TimeUnit.SECONDS)
    .build()
  private val capture = PlaybackRangeCapture(preview.sharedByteCache)
  private val closed = AtomicBoolean(false)
  private val playbackRequests = AtomicLong(0)
  private val lastAheadRejectionLogAtMs = AtomicLong(0L)

  private fun aheadRejected(reason: String): Response {
    // Log no URL, request headers, cookies, or tokens.
    val now = System.nanoTime() / 1_000_000L
    val previous = lastAheadRejectionLogAtMs.get()
    if (now - previous >= 1_000L && lastAheadRejectionLogAtMs.compareAndSet(previous, now))
      Log.d("SeekPreviewPerf", "ahead-http-rejected reason=$reason")
    return response(Response.Status.SERVICE_UNAVAILABLE)
  }

  val playbackUrl: String get() = URI("http", null, "127.0.0.1", listeningPort,
    "/$secret/play", null, null).toASCIIString()
  val previewUrl: String get() = preview.previewUrl
  /** Zero-upstream-read endpoint exclusively for speculative ahead thumbnails. */
  val cacheOnlyPreviewUrl: String get() = URI("http", null, "127.0.0.1", listeningPort,
    "/$secret/cached", null, null).toASCIIString()
  val byteCache: SeekByteBlockCache get() = preview.sharedByteCache
  fun setLargeForegroundFastMode(enabled: Boolean) = preview.setLargeForegroundFastMode(enabled)

  // At most one opt-in, time-limited speculative session. Unlike Stage 3
  // cache-only reads, it may fetch a few missing blocks, never an open-ended
  // movie-sized download. The foreground decoder uses a different route.
  private class AheadBudget(val plan: AheadPacing.Plan, val canFetch: () -> Boolean) {
    val active = AtomicBoolean(true)
    val remaining = AtomicInteger(plan.maxMissingBlocks)
    val startedAtNs = System.nanoTime()
    fun allow(): Boolean {
      if (!active.get() || System.nanoTime() - startedAtNs > TimeUnit.MILLISECONDS.toNanos(plan.deadlineMs) || !canFetch()) return false
      while (true) {
        val prev = remaining.get()
        if (prev <= 0) return false
        if (remaining.compareAndSet(prev, prev - 1)) return true
      }
    }
  }
  @Volatile private var aheadBudget: AheadBudget? = null

  fun beginBudgetedAhead(plan: AheadPacing.Plan, canFetch: () -> Boolean): String? = synchronized(this) {
    if (closed.get() || aheadBudget != null) return null
    preview.resumePreviewRequests() // only after the playback health gate permits this job
    aheadBudget = AheadBudget(plan, canFetch)
    URI("http", null, "127.0.0.1", listeningPort, "/$secret/ahead", null, null).toASCIIString()
  }
  fun finishBudgetedAhead() = synchronized(this) {
    aheadBudget?.active?.set(false)
    aheadBudget = null
  }


  override fun serve(session: IHTTPSession): Response {
    if (closed.get()) return response(Response.Status.SERVICE_UNAVAILABLE)
    if (session.uri == "/$secret/cached") return serveCachedOnly(session)
    if (session.uri == "/$secret/ahead") return serveBudgetedAhead(session)
    if (session.uri != "/$secret/play") return response(Response.Status.NOT_FOUND)
    if (session.method != Method.GET && session.method != Method.HEAD) return response(Response.Status.METHOD_NOT_ALLOWED)
    val initial = source.toHttpUrlOrNull() ?: return response(Response.Status.BAD_REQUEST)
    val requestedRange = session.headers["range"]
    val requestId = playbackRequests.incrementAndGet()
    val startedAt = System.nanoTime()
    val rangeType = if (requestedRange == null) "none" else if (requestedRange.endsWith("-")) "open" else "bounded"
    Log.d("SharedMediaTransport", "playback-http id=$requestId method=${session.method} range=$rangeType")
    if (requestedRange != null && !Regex("""^bytes=\d+-\d*$""").matches(requestedRange)) {
      return response(Response.Status.BAD_REQUEST)
    }
    return try {
      val upstream = fetch(initial, session.method == Method.HEAD, requestedRange)
        ?: return response(Response.Status.SERVICE_UNAVAILABLE)
      Log.d("SharedMediaTransport", "playback-origin id=$requestId status=${upstream.code} elapsedMs=${(System.nanoTime()-startedAt)/1_000_000L} range=$rangeType")
      val code = object : Response.IStatus {
        override fun getRequestStatus(): Int = upstream.code
        override fun getDescription(): String = "${upstream.code} ${upstream.message.ifBlank { "Response" }}"
      }
      val mime = upstream.header("Content-Type")?.substringBefore(';') ?: "application/octet-stream"
      val bodyLength = upstream.header("Content-Length")?.toLongOrNull()
        ?: upstream.body?.contentLength()?.takeIf { it >= 0L } ?: -1L
      val result = if (session.method == Method.HEAD) {
        upstream.close()
        newFixedLengthResponse(code, mime, ByteArrayInputStream(byteArrayOf()), bodyLength.coerceAtLeast(0))
      } else {
        val upstreamBody = upstream.body
        if (upstreamBody == null) {
          upstream.close()
          newFixedLengthResponse(code, mime, "")
        } else {
          val input = object : FilterInputStream(upstreamBody.byteStream()) {
            override fun close() {
              try { super.close() } finally { upstream.close() }
            }
          }
          val seekStart = requestedRange?.substringAfter("bytes=")?.substringBefore('-')?.toLongOrNull()
          val metadata = PlaybackRangeResponsePolicy.eligible(
            upstream.code, upstream.header("Content-Range"),
            upstream.header("Content-Length"), upstream.header("Content-Encoding"),
            upstream.header("Content-Type"), seekStart)
          val played = if (metadata != null) {
            capture.wrap(input, metadata.start, metadata.total, metadata.mime, metadata.expectedBytes)
          } else input
          if (bodyLength >= 0) newFixedLengthResponse(code, mime, played, bodyLength)
          else newChunkedResponse(code, mime, played)
        }
      }
      upstream.header("Content-Range")?.let { result.addHeader("Content-Range", it) }
      upstream.header("Accept-Ranges")?.let { result.addHeader("Accept-Ranges", it) }
      result.addHeader("Connection", "close")
      result
    } catch (error: Exception) {
      Log.w("SharedMediaTransport", "playback-origin-failure id=$requestId elapsedMs=${(System.nanoTime()-startedAt)/1_000_000L} reason=${error.javaClass.simpleName}")
      response(Response.Status.SERVICE_UNAVAILABLE)
    }
  }


  /**
   * This endpoint must never call fetch(), preview.stream(), or any other
   * upstream downloader. A missing byte range is a 503, not an HTTP fetch.
   * Unlike a 200 response it cannot advertise truncated movie contents.
   */
  private fun serveCachedOnly(session: IHTTPSession): Response {
    if (session.method != Method.HEAD && session.method != Method.GET) {
      return response(Response.Status.METHOD_NOT_ALLOWED)
    }
    val total = byteCache.length() ?: return response(Response.Status.SERVICE_UNAVAILABLE)
    if (total <= 0L) return response(Response.Status.SERVICE_UNAVAILABLE)
    if (session.method == Method.HEAD) {
      val result = newFixedLengthResponse(Response.Status.OK, byteCache.mime(),
        ByteArrayInputStream(ByteArray(0)), total)
      result.addHeader("Accept-Ranges", "bytes")
      return result
    }
    val requestRange = session.headers["range"]
    val match = requestRange?.let { Regex("""^bytes=(\d+)-(\d*)$""").matchEntire(it) }
    if (requestRange != null && match == null) return response(Response.Status.BAD_REQUEST)
    val start = match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    if (start >= total || start < 0L) return response(Response.Status.BAD_REQUEST)
    val requestedEnd = match?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toLongOrNull()
    if (requestedEnd != null && requestedEnd < start) return response(Response.Status.BAD_REQUEST)
    val end = if (requestRange == null) total - 1L
    else minOf(total - 1L, requestedEnd ?: total - 1L, start + (4L * 1024L * 1024L) - 1L)
    // An initial GET without Range must be complete to be a valid HTTP 200.
    val availableEnd = byteCache.cachedContiguousEnd(start, end)
      ?: return response(Response.Status.SERVICE_UNAVAILABLE)
    val result = newFixedLengthResponse(
      if (requestRange == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT,
      byteCache.mime(),
      // An un-ranged GET declares the full length, but its cache-only stream
      // fails closed if FFmpeg actually reads past available data. No download.
      byteCache.streamCachedOnly(start, if (requestRange == null) total - 1L else availableEnd),
      if (requestRange == null) total else availableEnd - start + 1L,
    )
    result.addHeader("Accept-Ranges", "bytes")
    if (requestRange != null) result.addHeader("Content-Range", "bytes $start-$availableEnd/$total")
    result.addHeader("Connection", "close")
    return result
  }

  /** Bounded experimental remote fallback for ahead thumbs only. Every missing
   * byte-cache block spends one session token; reads abort when video stalls.
   * Never substitute this route for actual mpv playback or manual previews.
   */
  private fun serveBudgetedAhead(session: IHTTPSession): Response {
    val budget = aheadBudget ?: return aheadRejected("no-budget")
    if (!budget.active.get()) return aheadRejected("expired-budget")
    if (!budget.canFetch()) return aheadRejected("playback-admission")
    if (session.method != Method.GET && session.method != Method.HEAD) return response(Response.Status.METHOD_NOT_ALLOWED)
    val total = byteCache.length() ?: return aheadRejected("unknown-length")
    if (total <= 0L) return aheadRejected("invalid-length")
    if (session.method == Method.HEAD) {
      val head = newFixedLengthResponse(Response.Status.OK, byteCache.mime(),
        ByteArrayInputStream(ByteArray(0)), total)
      head.addHeader("Accept-Ranges", "bytes")
      return head
    }
    val range = session.headers["range"]
    val match = range?.let { Regex("""^bytes=(\d+)-(\d*)$""").matchEntire(it) }
    if (range != null && match == null) return response(Response.Status.BAD_REQUEST)
    val start = match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    if (start < 0L || start >= total) return response(Response.Status.BAD_REQUEST)
    val upper = match?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toLongOrNull()
    if (upper != null && upper < start) return response(Response.Status.BAD_REQUEST)
    // FFmpeg may request open-ended ranges. Each response is capped; the
    // session-wide token and deadline caps apply across ALL range requests.
    // Return a 206 even for the initial non-Range GET so a truncated 200 can
    // never falsely claim to contain the whole movie.
    val end = minOf(total - 1L, upper ?: total - 1L, start + budget.plan.maxRangeWindowBytes - 1L)
    val result = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, byteCache.mime(),
      byteCache.streamBudgeted(start, end) { aheadBudget === budget && budget.allow() },
      end - start + 1L)
    result.addHeader("Content-Range", "bytes $start-$end/$total")
    result.addHeader("Accept-Ranges", "bytes")
    result.addHeader("Connection", "close")
    return result
  }

  // Redirect credentials only when the redirect remains on the original origin.
  // Never print the signed URL or any authentication header.
  private fun fetch(start: HttpUrl, head: Boolean, range: String?): UpstreamResponse? {
    var destination = start
    repeat(6) {
      if (closed.get()) return null
      val sameOrigin = destination.scheme == start.scheme && destination.host == start.host &&
        destination.port == start.port
      val request = Request.Builder().url(destination)
      headers.forEach { (key, value) ->
        val lower = key.lowercase()
        if (lower in setOf("host", "range", "connection", "accept-encoding", "content-length", "transfer-encoding")) return@forEach
        if (!sameOrigin && lower !in setOf("user-agent", "accept")) return@forEach
        if ('\r' in value || '\n' in value) return@forEach
        request.header(key, value)
      }
      request.header("Accept-Encoding", "identity")
      range?.let { request.header("Range", it) }
      if (head) request.head() else request.get()
      val response = client.newCall(request.build()).execute()
      if (response.code !in setOf(301,302,303,307,308)) return response
      val next = response.header("Location")?.let { destination.resolve(it) }
      response.close()
      if (next == null || next.scheme !in setOf("http", "https")) return null
      destination = next
    }
    return null
  }

  private fun response(status: Response.Status) =
    newFixedLengthResponse(status, MIME_PLAINTEXT, "Shared playback unavailable")

  fun cancelOnlyPreviewReads() { preview.cancelActivePreviewRequests() }
  fun resumeOnlyPreviewReads() { preview.resumePreviewRequests() }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    finishBudgetedAhead()
    stop()
    capture.close()
    val stats = capture.stats()
    val bytes = byteCache.stats()
    Log.d("SeekPreviewPerf", "byte-cache stats revision=${bytes.revision} diskMiB=${bytes.diskBytes / 1_048_576L} " +
      "partial=${bytes.partialBlocks} evictions=${bytes.evicted} restores=${bytes.restored} fetches=${bytes.originFetches} " +
      "adjacentGets=${bytes.adjacentFetches} savedRequests=${bytes.adjacentSavedRequests} adjacentDisabled=${bytes.adjacentDisabled}")
    Log.d("SharedMediaTransport", "playback-proxy closed requests=${playbackRequests.get()}")
    Log.d("SharedMediaTransport", "playback-cache accepted=${stats.acceptedBlocks} dropped=${stats.droppedBlocks} stored=${stats.storedBlocks}")
    preview.close()
    client.dispatcher.cancelAll()
    client.connectionPool.evictAll()
  }

  companion object {
    fun open(source: String, headers: Map<String, String>, cacheRoot: File): SharedPlaybackHttpBridge {
      val preview = NetworkSeekHttpBridge.open(source, headers, cacheRoot)
      return try {
        SharedPlaybackHttpBridge(source, headers, preview).apply { start(SOCKET_READ_TIMEOUT, false) }
      } catch (error: Exception) {
        preview.close()
        throw error
      }
    }
  }
}
