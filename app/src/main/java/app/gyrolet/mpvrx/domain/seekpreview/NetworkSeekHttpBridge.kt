/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.seekpreview

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import okhttp3.Call
import okhttp3.ResponseBody
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response as UpstreamResponse
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local-only range proxy for mpv's native thumbnail decoder. It is needed because the
 * native fast-thumbnail method accepts a media URL but not HTTP request headers.
 * Requests go only to the already-playing media URL; the server listens on loopback,
 * uses an unpredictable path, and is stopped when its seek session ends.
 */
internal class NetworkSeekHttpBridge private constructor(
  private val upstreamUrl: String,
  private val playbackHeaders: Map<String, String>,
  cacheRoot: File,
) : NanoHTTPD("127.0.0.1", 0), AutoCloseable {
  private val token = ByteArray(24).also { SecureRandom().nextBytes(it) }
    .joinToString("") { "%02x".format(it) }
  private val videoCache = SeekByteBlockCache(
    File(cacheRoot, "mpvrx-seek-$token"),
    { start, end -> fetchRangeBlock(start, end) },
    fetchAdjacent = { start, end -> fetchAdjacentBlocks(start, end) },
  )
  // Enabled ONLY while the user requests an uncached foreground thumbnail.
  // Does not change the actual playback URL, buffer, or admission gate.
  @Volatile private var largeForegroundFastMode = false
  private val foregroundCaptureLazy = lazy { PlaybackRangeCapture(videoCache, queueCapacity = 4) }
  private val foregroundCapture by foregroundCaptureLazy
  private val progressiveRangeDisabled = java.util.concurrent.atomic.AtomicBoolean(false)
  private val progressiveRequestCount = AtomicInteger(0)

  fun setLargeForegroundFastMode(enabled: Boolean) {
    largeForegroundFastMode = enabled
  }

  // The experimental shared-playback proxy feeds its already-downloaded bytes
  // into this exact same cache, avoiding duplicate thumbnail network reads.
  internal val sharedByteCache: SeekByteBlockCache get() = videoCache
  private val client = OkHttpClient.Builder()
    // Redirect explicitly so credentials/cookies never follow a cross-origin Location.
    .followRedirects(false).followSslRedirects(false)
    .connectTimeout(12, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS)
    .build()

  private val admissionLock = Any()
  private val activeCalls = HashSet<Call>()
  // Diagnoses connection pressure without exposing signed URLs or credentials.
  private val upstreamRequests = AtomicInteger(0)
  @Volatile private var suspended = false
  @Volatile private var stopped = false
  @Volatile private var ioEpoch = 0L

  private fun checkAdmission(epoch: Long = ioEpoch) {
    if (suspended || stopped || epoch != ioEpoch) throw IOException("Preview traffic suspended")
  }

  // Keep calls registered until their response BODY closes, not just until
  // execute() returns headers. Persistent FFmpeg readers keep bodies open.
  private fun executeTracked(request: Request): UpstreamResponse {
    val call = client.newCall(request)
    val epoch = synchronized(admissionLock) {
      checkAdmission()
      activeCalls.add(call)
      ioEpoch
    }
    try {
      val response = call.execute()
      synchronized(admissionLock) {
        if (suspended || stopped || epoch != ioEpoch) {
          response.close()
          throw IOException("Preview request superseded")
        }
      }
      val body = response.body
      if (body == null) {
        synchronized(admissionLock) { activeCalls.remove(call) }
        return response
      }
      val trackedBody = object : ResponseBody() {
        private val trackedSource = object : ForwardingSource(body.source()) {
          override fun close() {
            try { super.close() } finally {
              synchronized(admissionLock) { activeCalls.remove(call) }
            }
          }
        }.buffer()
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun source() = trackedSource
      }
      return response.newBuilder().body(trackedBody).build()
    } catch (error: Exception) {
      synchronized(admissionLock) { activeCalls.remove(call) }
      call.cancel()
      throw error
    }
  }

  val previewUrl: String get() = URI("http", null, "127.0.0.1", listeningPort, "/$token/media", null, null).toASCIIString()

  override fun serve(session: IHTTPSession): Response {
    if (session.uri != "/$token/media") return textResponse(Response.Status.NOT_FOUND)
    if (session.method != Method.GET && session.method != Method.HEAD) return textResponse(Response.Status.METHOD_NOT_ALLOWED)
    if (suspended || stopped) return textResponse(Response.Status.SERVICE_UNAVAILABLE)
    val initial = upstreamUrl.toHttpUrlOrNull() ?: return textResponse(Response.Status.BAD_REQUEST)
    val range = session.headers["range"]?.takeIf { it.startsWith("bytes=") }
    return try {
      // FFmpeg commonly requests `Range: bytes=N-`. Passing it through
      // unmodified broke thumbnails on providers whose servers accept finite
      // ranges but mishandle open-ended ones. The old proxy did work, but
      // could download the rest of an entire movie 1 MiB at a time.
      // Satisfy that request with a bounded 8 MiB *206* window instead:
      // still seekable to FFmpeg, with at most nine upstream block fetches.
      // Never truncate a regular no-Range GET advertised as HTTP 200.
      // ANY foreground seek now streams verified 206 bytes as they arrive,
      // without forcing the first missing 1 MiB byte-cache block to download
      // completely before FFmpeg can read the first keyframe/metadata bytes.
      // Exact stored cache hits still win with no upstream network request.
      if (session.method == Method.GET && largeForegroundFastMode && range != null) {
        serveProgressiveLargeRange(range)?.let { return it }
      }
      if (session.method == Method.GET && shouldUseBlockCache(range)) {
        serveFromCachedRanges(range)?.let { return it }
      }
      // Unknown Range formats and initial requests without Range are passed
      // through unchanged. No request is made against the *playback* socket.
      val upstream = fetch(initial, session.method == Method.HEAD, range) ?: return textResponse(Response.Status.SERVICE_UNAVAILABLE)
      if (range != null && upstream.code == 200) {
        Log.w("SeekPreviewNetwork", "thumbnail origin ignored byte range; FFmpeg seekability may be limited")
      }
      val status = object : Response.IStatus {
        override fun getRequestStatus(): Int = upstream.code
        override fun getDescription(): String = "${upstream.code} ${upstream.message.ifBlank { "Response" }}"
      }
      val mime = upstream.header("Content-Type")?.substringBefore(';') ?: "application/octet-stream"
      val length = upstream.header("Content-Length")?.toLongOrNull() ?: upstream.body?.contentLength() ?: -1L
      val response = if (session.method == Method.HEAD) {
        upstream.close()
        newFixedLengthResponse(status, mime, ByteArrayInputStream(ByteArray(0)), length.coerceAtLeast(0L))
      } else {
        val body = upstream.body
        if (body == null) {
          upstream.close()
          newFixedLengthResponse(status, mime, "")
        } else {
          val input = object : FilterInputStream(body.byteStream()) {
            override fun close() {
              try { super.close() } finally { upstream.close() }
            }
          }
          if (length >= 0) newFixedLengthResponse(status, mime, input, length)
          else newChunkedResponse(status, mime, input)
        }
      }
      upstream.header("Content-Range")?.let { response.addHeader("Content-Range", it) }
      upstream.header("Accept-Ranges")?.let { response.addHeader("Accept-Ranges", it) }
      response.addHeader("Connection", "close")
      response
    } catch (error: Exception) {
      // Don't include signed media URLs, authorization, or cookies in logs/responses.
      Log.w("SeekPreviewNetwork", "Native preview bridge request failed (${error.javaClass.simpleName})")
      textResponse(Response.Status.SERVICE_UNAVAILABLE)
    }
  }

  private data class ByteRequest(val start: Long, val endInclusive: Long?)

  private fun parseByteRequest(range: String?): ByteRequest? {
    if (range == null) return ByteRequest(0L, null)
    val match = Regex("""^bytes=(\d+)-(\d*)$""").matchEntire(range) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].takeIf { it.isNotBlank() }?.toLongOrNull()
    if (end != null && end < start) return null
    return ByteRequest(start, end)
  }

  /** The block cache's finite 206 response restores FFmpeg's remote seeking.
   * Open-ended reads are capped separately to avoid the old read-to-EOF storm.
   */
  private fun shouldUseBlockCache(range: String?): Boolean {
    if (range == null) return false // Cannot send partial data as a 200 response.
    val request = parseByteRequest(range) ?: return false
    val end = request.endInclusive
    // Finite ranges crossing a block go straight to origin as one request.
    return end == null ||
      (end >= request.start && request.start / (1024L * 1024L) == end / (1024L * 1024L))
  }

  private fun serveFromCachedRanges(range: String?): Response? {
    val epoch = ioEpoch
    checkAdmission(epoch)
    val request = parseByteRequest(range) ?: return null
    if (!videoCache.isSupported() || !videoCache.prime(request.start)) return null
    val total = videoCache.length() ?: return null
    if (request.start >= total) return null // Let upstream emit an accurate 416.
    // The FFmpeg client asks for the file from N to EOF; a complete response
    // used to cause potentially thousands of 1 MiB upstream fetches. A bounded
    // partial-content response allows FFmpeg to issue a further Range request
    // when it actually needs more data, rather than prefetching the entire file.
    val windowEnd = if (request.endInclusive == null)
      request.start + minOf(total - request.start, (8L * 1024L * 1024L)) - 1L
    else request.endInclusive
    val finalByte = minOf(windowEnd, total - 1)
    val contentLength = finalByte - request.start + 1
    val code = Response.Status.PARTIAL_CONTENT
    val cachedInput = object : FilterInputStream(videoCache.stream(request.start, finalByte)) {
      override fun read(): Int { checkAdmission(epoch); return super.read() }
      override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
        checkAdmission(epoch)
        return super.read(bytes, offset, count)
      }
    }
    val response = newFixedLengthResponse(code, videoCache.mime(), cachedInput, contentLength)
    response.addHeader("Accept-Ranges", "bytes")
    response.addHeader("Content-Range", "bytes ${request.start}-$finalByte/$total")
    response.addHeader("Connection", "close")
    return response
  }

  /** User-initiated uncached foreground read, regardless of file size.
   * A 206 is issued only after
   * confirming Content-Range and length; the body is streamed directly from
   * OkHttp to FFmpeg, so first-byte latency is not gated on a whole 1 MiB
   * download. No new concurrent worker or playback requests are created.
   * This path never handles HEAD or an un-ranged request.
   */
  private fun serveProgressiveLargeRange(range: String): Response? {
    if (progressiveRangeDisabled.get()) return null
    val request = parseByteRequest(range) ?: return null
    val total = videoCache.length() ?: return null
    val span = LargeForegroundRangePolicy.requestedManual(request.start, request.endInclusive, total)
      ?: return null
    // Fully cached spans use the original byte cache; its hits are faster
    // than any new network request. For finite cross-block ranges the old
    // passthrough already streams without blocking on complete cache blocks.
    // Prefer zero-network initial bytes, especially opening metadata. If
    // FFmpeg needs farther data, the working cached-block path handles it.
    val firstBlockEnd = minOf(span.end, (span.start / 1_048_576L + 1L) * 1_048_576L - 1L)
    if (videoCache.hasCachedSpan(span.start, firstBlockEnd)) return null
    if (request.endInclusive != null &&
        request.endInclusive / 1_048_576L != request.start / 1_048_576L) return null
    val epoch = ioEpoch
    checkAdmission(epoch)
    val target = upstreamUrl.toHttpUrlOrNull() ?: return null
    val begun = System.nanoTime()
    val upstream = fetch(target, false, "bytes=${span.start}-${span.end}") ?: return null
    try {
      if (upstream.code == 200 || upstream.header("Content-Encoding")?.let {
          !it.equals("identity", ignoreCase = true)
        } == true) {
        progressiveRangeDisabled.set(true)
      }
      val validated = LargeForegroundRangePolicy.validated(
        upstream.header("Content-Range"), upstream.header("Content-Encoding"),
        upstream.code, span, upstream.body?.contentLength()) ?: run {
          if (upstream.code == 206) progressiveRangeDisabled.set(true)
          upstream.close()
          return null // Proven incompatible, or transient origin failure.
        }
      checkAdmission(epoch)
      val body = upstream.body ?: run { upstream.close(); return null }
      val mime = upstream.header("Content-Type")?.substringBefore(';')?.trim()
        ?.ifBlank { "application/octet-stream" } ?: "application/octet-stream"
      val captureInput = foregroundCapture.wrap(body.byteStream(), validated.start,
        validated.total, mime, validated.bytes)
      val firstByte = java.util.concurrent.atomic.AtomicBoolean(false)
      val upstreamInput = object : FilterInputStream(captureInput) {
        private fun onRead(count: Int) {
          if (count > 0 && firstByte.compareAndSet(false, true)) {
            Log.d("SeekPreviewPerf", "foreground-uncached-first-byte-ms=${(System.nanoTime()-begun)/1_000_000L}")
          }
        }
        override fun read(): Int {
          checkAdmission(epoch)
          val result = super.read()
          onRead(if (result >= 0) 1 else 0)
          return result
        }
        override fun read(bytes: ByteArray, offset: Int, len: Int): Int {
          checkAdmission(epoch)
          val n = super.read(bytes, offset, len)
          onRead(n)
          return n
        }
        override fun close() {
          try { super.close() } finally { upstream.close() }
        }
      }
      val response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT,
        mime, upstreamInput, validated.bytes)
      response.addHeader("Content-Range", "bytes ${validated.start}-${validated.end}/${validated.total}")
      response.addHeader("Accept-Ranges", "bytes")
      response.addHeader("Connection", "close")
      val count = progressiveRequestCount.incrementAndGet()
      if (count <= 8 || count % 16 == 0) Log.d("SeekPreviewPerf",
        "foreground-progressive request=$count bytes=${validated.bytes} headersMs=${(System.nanoTime()-begun)/1_000_000L}")
      return response
    } catch (error: Exception) {
      upstream.close()
      throw error
    }
  }

  /** One bounded 2 MiB HTTP 206 response instead of two separate 1 MiB
   * requests. Called only after sequential reads are proven and the budget
   * grants BOTH block tokens; it never touches playback's HTTP client.
   * A one-block-only origin simply falls back to the original implementation.
   */
  private fun fetchAdjacentBlocks(start: Long, end: Long): List<SeekByteBlockCache.Block>? {
    checkAdmission()
    val url = upstreamUrl.toHttpUrlOrNull() ?: return null
    val begunNs = System.nanoTime()
    val upstream = fetch(url, false, "bytes=$start-$end") ?: return null
    return upstream.use { response ->
      if (response.code != 206) return@use null
      val header = response.header("Content-Range") ?: return@use null
      val match = Regex("""^bytes\s+(\d+)-(\d+)/(\d+)$""", RegexOption.IGNORE_CASE)
        .matchEntire(header) ?: return@use null
      val first = match.groupValues[1].toLongOrNull() ?: return@use null
      val last = match.groupValues[2].toLongOrNull() ?: return@use null
      val total = match.groupValues[3].toLongOrNull() ?: return@use null
      val block = 1024 * 1024
      if (first != start || last != end || end - start + 1L != 2L * block || total <= end)
        return@use null
      val input = response.body?.byteStream() ?: return@use null
      // Validate both whole blocks before publishing either to the shared cache.
      val bytes = ByteArray(block * 2)
      var received = 0
      input.use {
        while (received < bytes.size) {
          checkAdmission()
          val count = it.read(bytes, received, bytes.size - received)
          if (count <= 0) break
          received += count
        }
      }
      if (received != bytes.size) return@use null
      Log.d("SeekPreviewPerf", "thumbnail-adjacent-range-2MiB durationMs=${(System.nanoTime()-begunNs)/1_000_000L}")
      val mime = response.header("Content-Type")?.substringBefore(';') ?: "application/octet-stream"
      listOf(
        SeekByteBlockCache.Block(start, total, bytes.copyOfRange(0, block), mime),
        SeekByteBlockCache.Block(start + block, total, bytes.copyOfRange(block, bytes.size), mime),
      )
    }
  }

  private fun fetchRangeBlock(start: Long, end: Long): SeekByteBlockCache.Block? {
    checkAdmission()
    val url = upstreamUrl.toHttpUrlOrNull() ?: return null
    val upstream = fetch(url, false, "bytes=$start-$end") ?: return null
    return upstream.use { response ->
      if (response.code == 200) {
        // Origin ignored a byte range; use unmodified streaming instead.
        throw SeekByteBlockCache.RangeUnsupportedException()
      }
      // 416 can mean the requested offset is past EOF, not missing range support.
      if (response.code != 206) return@use null // transient 401/403/416/429/5xx
      val header = response.header("Content-Range")
        ?: throw SeekByteBlockCache.RangeUnsupportedException()
      val match = Regex("""^bytes\s+(\d+)-(\d+)/(\d+)$""", RegexOption.IGNORE_CASE).matchEntire(header)
        ?: throw SeekByteBlockCache.RangeUnsupportedException()
      val first = match.groupValues[1].toLongOrNull() ?: return@use null
      val last = match.groupValues[2].toLongOrNull() ?: return@use null
      val total = match.groupValues[3].toLongOrNull() ?: return@use null
      if (first != start || last > end || last < first || total <= last)
        throw SeekByteBlockCache.RangeUnsupportedException()
      val count = last - first + 1
      if (count > 1024L * 1024 || count != minOf(1024L * 1024, total - start))
        throw SeekByteBlockCache.RangeUnsupportedException()
      val body = response.body ?: return@use null
      val output = ByteArrayOutputStream(count.toInt())
      val buffer = ByteArray(32768)
      body.byteStream().use { input ->
        while (output.size() < count) {
          val amount = input.read(buffer, 0, minOf(buffer.size, count.toInt() - output.size()))
          if (amount <= 0) break
          output.write(buffer, 0, amount)
        }
      }
      if (output.size().toLong() != count) return@use null
      SeekByteBlockCache.Block(first, total, output.toByteArray(),
        response.header("Content-Type")?.substringBefore(';') ?: "application/octet-stream")
    }
  }

  private fun fetch(startUrl: HttpUrl, head: Boolean, range: String?): UpstreamResponse? {
    var target = startUrl
    repeat(5) {
      val sameOrigin = target.scheme == startUrl.scheme && target.host == startUrl.host && target.port == startUrl.port
      val builder = Request.Builder().url(target)
      playbackHeaders.forEach { (name, value) ->
        val lower = name.lowercase()
        if (lower in setOf("host", "connection", "range", "accept-encoding", "transfer-encoding", "content-length")) return@forEach
        if (!sameOrigin && lower !in setOf("user-agent", "accept")) return@forEach
        if (value.any { it == '\r' || it == '\n' }) return@forEach
        builder.header(name, value)
      }
      builder.header("Accept-Encoding", "identity")
      range?.let { builder.header("Range", it) }
      if (head) builder.head() else builder.get()
      val upstream = executeTracked(builder.build())
      val count = upstreamRequests.incrementAndGet()
      if (count % 32 == 0) {
        val active = synchronized(admissionLock) { activeCalls.size }
        Log.d("SeekPreviewNetwork", "thumbnail upstream requests=$count active=$active range=${if (range == null) "none" else if (range.endsWith("-")) "open" else "bounded"}")
      }
      if (upstream.code in setOf(401, 403, 416, 429, 500, 502, 503, 504)) {
        Log.w("SeekPreviewNetwork", "thumbnail origin HTTP=${upstream.code} range=${if (range == null) "none" else if (range.endsWith("-")) "open" else "bounded"}")
      }
      if (upstream.code !in setOf(301, 302, 303, 307, 308)) return upstream
      val location = upstream.header("Location")
      val next = location?.let { target.resolve(it) }
      upstream.close()
      if (next == null || next.scheme !in setOf("http", "https")) return null
      target = next
    }
    return null
  }

  private fun textResponse(status: Response.Status): Response =
    newFixedLengthResponse(status, MIME_PLAINTEXT, "Preview request failed")

  /** Close all outstanding thumbnail-only network requests when the video player
   * seeks outside its own demuxer buffer. New requests can run after recovery.
   */
  fun cancelActivePreviewRequests() {
    val calls = synchronized(admissionLock) {
      suspended = true
      ioEpoch++
      activeCalls.toList()
    }
    // Call.cancel also cancels bodies whose headers have already returned.
    calls.forEach { it.cancel() }
    client.connectionPool.evictAll()
    Log.d("SeekPreviewPerf", "thumbnail HTTP admission closed for playback seek")
  }

  fun resumePreviewRequests() = synchronized(admissionLock) {
    if (!stopped) suspended = false
  }

  override fun close() {
    synchronized(admissionLock) { stopped = true }
    cancelActivePreviewRequests()
    stop()
    if (foregroundCaptureLazy.isInitialized()) foregroundCapture.close()
    videoCache.close()
    client.connectionPool.evictAll()
  }

  companion object {
    /** May throw if a local server cannot bind; the caller keeps normal fallback behavior. */
    fun open(source: String, headers: Map<String, String>, cacheRoot: File): NetworkSeekHttpBridge =
      NetworkSeekHttpBridge(source, headers, cacheRoot).apply { start(SOCKET_READ_TIMEOUT, false) }
  }
}
