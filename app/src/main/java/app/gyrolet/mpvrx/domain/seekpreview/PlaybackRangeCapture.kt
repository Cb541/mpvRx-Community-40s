/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.seekpreview

import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The ingestion half of a shared playback/preview media-byte cache.
 *
 * This observes bytes ALREADY being read by playback. Its caller must only
 * attach it to an identity-encoded response with a validated Content-Range
 * (or a complete 200 response with a trustworthy Content-Length). No media
 * URL, headers, credentials, or network connection is owned by this class.
 *
 * Non-blocking policy: a slow cache disk must NEVER slow the video. Full
 * aligned blocks go to a small fixed queue; on saturation we drop caching
 * opportunities rather than blocking the playback reader.
 */
internal class PlaybackRangeCapture(
  private val cache: SeekByteBlockCache,
  private val blockSize: Int = 1024 * 1024,
  queueCapacity: Int = 6,
) : AutoCloseable {
  private data class Write(val offset: Long, val total: Long, val mime: String,
    val data: ByteArray, val startInBlock: Int)
  private val alive = AtomicBoolean(true)
  private val queued = ArrayBlockingQueue<Write>(queueCapacity)
  private val accepted = AtomicLong()
  private val dropped = AtomicLong()
  private val stored = AtomicLong()
  private val writer = Thread({
    while (alive.get() || queued.isNotEmpty()) {
      val item = try { queued.poll(200, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { break }
      if (item != null) {
        val ok = if (item.startInBlock == 0 && item.data.size.toLong() ==
            minOf(blockSize.toLong(), item.total - item.offset))
          cache.offerPlaybackBlock(item.offset, item.total, item.data, item.mime)
        else cache.offerPlaybackFragment(item.offset, item.startInBlock,
          item.total, item.data, item.mime)
        if (ok) stored.incrementAndGet()
      }
    }
  }, "mpvrx-playback-range-capture").apply { isDaemon = true; start() }

  data class Stats(val acceptedBlocks: Long, val droppedBlocks: Long, val storedBlocks: Long)
  fun stats(): Stats = Stats(accepted.get(), dropped.get(), stored.get())

  fun wrap(source: InputStream, start: Long, total: Long, mime: String,
    responseByteCount: Long = total - start): InputStream {
    require(start >= 0L && total > 0L && start < total && responseByteCount > 0 &&
      responseByteCount <= total - start)
    // Respect the validated 206 response boundary. A non-conforming origin
    // must never convince us that bytes beyond this response were received.
    val endExclusive = start + responseByteCount
    val safeMime = mime.substringBefore(';').trim().ifBlank { "application/octet-stream" }
    return object : FilterInputStream(source) {
      private var position = start
      private var blockOffset = start - start % blockSize
      private var startInBlock = (start - blockOffset).toInt()
      private var bytesHeld = 0
      private var current = ByteArray(blockSize)
      private var stopped = false

      // An HTTP response may start or stop halfway through a 1 MiB block.
      // Send the exact fragment to the bounded writer for byte-accurate merge.
      private fun flushBlock() {
        if (bytesHeld > 0 && alive.get()) {
          val required = minOf(blockSize.toLong(), total - blockOffset).toInt()
          val payload = if (startInBlock == 0 && bytesHeld == required &&
              required == blockSize) current
            else current.copyOfRange(startInBlock, startInBlock + bytesHeld)
          if (queued.offer(Write(blockOffset, total, safeMime, payload, startInBlock)))
            accepted.incrementAndGet()
          else dropped.incrementAndGet()
        }
        bytesHeld = 0
      }

      private fun record(bytes: ByteArray, off: Int, count: Int) {
        if (stopped || !alive.get() || count <= 0) return
        var i = off
        var left = count
        while (left > 0 && position < endExclusive) {
          val required = minOf(blockSize.toLong(), total - blockOffset).toInt()
          val amount = minOf(left.toLong(),
            (required - startInBlock - bytesHeld).toLong(), endExclusive - position).toInt()
          if (amount <= 0) break
          bytes.copyInto(current, startInBlock + bytesHeld, i, i + amount)
          bytesHeld += amount
          position += amount
          i += amount
          left -= amount
          if (startInBlock + bytesHeld == required) {
            flushBlock()
            blockOffset = position
            startInBlock = 0
            current = ByteArray(blockSize)
          }
        }
      }

      override fun read(): Int {
        val value = super.read()
        if (value >= 0) record(byteArrayOf(value.toByte()), 0, 1)
        else flushBlock()
        return value
      }
      override fun read(bytes: ByteArray, off: Int, len: Int): Int {
        val n = super.read(bytes, off, len)
        if (n > 0) record(bytes, off, n)
        if (n < 0) flushBlock()
        return n
      }
      override fun close() {
        flushBlock() // Safe: fragments remain invisible until 100% complete.
        stopped = true
        super.close()
      }
    }
  }

  override fun close() {
    if (alive.compareAndSet(true, false)) {
      writer.interrupt()
      queued.clear()
    }
  }
}
