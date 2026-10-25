/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.seekpreview

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.BitSet
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * A bounded, temporary, per-playback spool for the *video bytes* consumed by the
 * FFmpeg thumbnail extractor. This is distinct from the existing JPEG filmstrip.
 * Byte ranges are aligned so repeated opens/probes/nearby seeks reuse exactly
 * the same data. No URL, token, cookie or request header is written to disk.
 *
 * It is exclusively used for finite, seekable HTTP sources with proper 206
 * Content-Range support. Otherwise the caller falls back to ordinary streaming.
 */
internal class SeekByteBlockCache(
  private val directory: File,
  private val fetch: (Long, Long) -> Block?,
  private val blockSize: Int = 1024 * 1024,
  private val maxDiskBytes: Long = 384L * 1024 * 1024,
  private val maxMemoryBytes: Long = 16L * 1024 * 1024,
  // Optional two-block range read, used only after proven sequential access.
  // Null preserves the original single-block behavior for all other callers.
  private val fetchAdjacent: ((Long, Long) -> List<Block>?)? = null,
) : AutoCloseable {
  data class Block(val start: Long, val total: Long, val bytes: ByteArray, val mime: String)
  // Only a demonstrably non-range HTTP origin should disable block caching.
  // Cancellation, timeout, and transient 5xx errors must be retryable.
  class RangeUnsupportedException : IOException("Upstream does not support consistent HTTP byte ranges")
  private val closed = AtomicBoolean(false)
  private val cachedRevision = AtomicLong(0L)
  private val pending = ConcurrentHashMap<Long, CompletableFuture<Block?>>()
  private val lock = Any()
  private val inMemory = LinkedHashMap<Long, Block>(16, 0.75f, true)
  private var memoryBytes = 0L
  private var diskBytes = 0L
  private var expectedTotal: Long? = null
  private var mime = "application/octet-stream"
  private var unsupported = false
  // Playback requests can start/end at arbitrary byte offsets. Hold only a
  // handful of incomplete blocks and publish them once every byte is known.
  // Never expose a partial block to FFmpeg, and never stall playback to merge.
  private data class Fragment(val data: ByteArray, val received: BitSet, var count: Int = 0)
  private val fragments = LinkedHashMap<Long, Fragment>(16, 0.75f, true)
  private val maxFragments = 12
  private val diskEvictions = AtomicLong(0L)
  private val restoredBlocks = AtomicLong(0L)
  private val originFetches = AtomicLong(0L)
  private val adjacentFetches = AtomicLong(0L)
  private val adjacentSavedRequests = AtomicLong(0L)
  private val adjacentDisabled = AtomicBoolean(false)

  init {
    require(blockSize > 0 && blockSize <= 4 * 1024 * 1024)
    require(maxDiskBytes >= blockSize)
    require(directory.mkdirs() || directory.isDirectory) { "Cannot create seek cache" }
  }

  /** Returns null if the server ignores ranges, reports inconsistent sizes, or closes. */
  fun prime(offset: Long): Boolean = load(offset / blockSize) != null

  fun length(): Long? = synchronized(lock) { expectedTotal }
  /** New complete blocks seen by playback; never triggers a network fetch. */
  fun revision(): Long = cachedRevision.get()
  fun mime(): String = synchronized(lock) { mime }
  data class Stats(val revision: Long, val diskBytes: Long, val partialBlocks: Int,
    val evicted: Long, val restored: Long, val originFetches: Long,
    val adjacentFetches: Long, val adjacentSavedRequests: Long, val adjacentDisabled: Boolean)
  fun stats(): Stats = synchronized(lock) {
    Stats(cachedRevision.get(), diskBytes, fragments.size,
      diskEvictions.get(), restoredBlocks.get(), originFetches.get(),
      adjacentFetches.get(), adjacentSavedRequests.get(), adjacentDisabled.get())
  }
  fun isSupported(): Boolean = synchronized(lock) { !unsupported && !closed.get() }

  /**
   * Populate this cache with bytes already received by the PLAYBACK reader.
   * Never opens an upstream connection. Only complete aligned blocks are accepted,
   * so a truncated/cancelled playback request can never create corrupt cache data.
   * Returns false on a conflicting length or after the session is retired.
   */
  fun offerPlaybackBlock(start: Long, total: Long, bytes: ByteArray, contentType: String): Boolean {
    if (closed.get() || start < 0 || total <= 0 || start >= total ||
      start % blockSize != 0L ||
      bytes.size.toLong() != min(blockSize.toLong(), total - start)) return false
    val copy = bytes.copyOf()
    val block = Block(start, total, copy, contentType)
    synchronized(lock) {
      if (closed.get() || unsupported) return false
      if (expectedTotal != null && expectedTotal != total) return false
      expectedTotal = total
      mime = contentType
      fragments.remove(start / blockSize)
      keep(start / blockSize, block)
    }
    return true
  }

  /** Merge a byte-exact fragment from a playback response. Only complete
   * blocks become visible to thumbnail readers. Invoked by the background
   * capture writer (never from the video player's network read callback).
   */
  fun offerPlaybackFragment(start: Long, offsetWithinBlock: Int, total: Long,
    bytes: ByteArray, contentType: String): Boolean {
    if (closed.get() || start < 0L || total <= start || start % blockSize != 0L ||
      bytes.isEmpty() || offsetWithinBlock < 0) return false
    val required = min(blockSize.toLong(), total - start).toInt()
    if (offsetWithinBlock >= required || bytes.size > required - offsetWithinBlock) return false
    synchronized(lock) {
      if (closed.get() || unsupported || (expectedTotal != null && expectedTotal != total)) return false
      expectedTotal = total
      mime = contentType
      val index = start / blockSize
      if (inMemory.containsKey(index) || File(directory, "$index.blk").let {
          it.isFile && it.length() == required.toLong()
        }) return true
      val fragment = fragments.getOrPut(index) {
        Fragment(ByteArray(required), BitSet(required))
      }
      if (fragment.data.size != required) { fragments.remove(index); return false }
      // Only copy bytes not previously provided. The coverage map guarantees
      // cancelled/interrupted reads never masquerade as a complete block.
      bytes.copyInto(fragment.data, offsetWithinBlock)
      val unseen = bytes.size - fragment.received.get(
        offsetWithinBlock, offsetWithinBlock + bytes.size).cardinality()
      fragment.received.set(offsetWithinBlock, offsetWithinBlock + bytes.size)
      fragment.count += unseen
      if (fragment.count == required) {
        fragments.remove(index)
        keep(index, Block(start, total, fragment.data, contentType))
      }
      while (fragments.size > maxFragments) {
        val oldest = fragments.entries.iterator()
        oldest.next(); oldest.remove()
      }
      return true
    }
  }

  /** True only for stored blocks; unlike [stream] this never triggers a fetch. */
  fun hasCachedSpan(start: Long, endInclusive: Long): Boolean {
    if (start < 0 || endInclusive < start || closed.get()) return false
    val first = start / blockSize
    val last = endInclusive / blockSize
    return synchronized(lock) {
      if (expectedTotal == null || endInclusive >= expectedTotal!! || unsupported) return@synchronized false
      (first..last).all { index ->
        inMemory.containsKey(index) || File(directory, "$index.blk").let {
          it.isFile && it.length() == min(blockSize.toLong(), expectedTotal!! - index * blockSize)
        }
      }
    }
  }


  /**
   * Cache-ONLY byte range for low-priority ahead thumbnails. NEVER uses [fetch]
   * even when a file disappears during disk-cache eviction. Callers may offer
   * a partial 206 response up to this end; a miss yields null instead of HTTP I/O.
   */
  fun cachedContiguousEnd(start: Long, requestedEnd: Long): Long? {
    if (closed.get() || start < 0L || requestedEnd < start) return null
    return synchronized(lock) {
      val total = expectedTotal ?: return@synchronized null
      if (unsupported || start >= total) return@synchronized null
      val end = min(requestedEnd, total - 1L)
      var index = start / blockSize
      val last = end / blockSize
      while (index <= last) {
        val size = min(blockSize.toLong(), total - index * blockSize)
        val memoryHit = inMemory[index]?.bytes?.size?.toLong() == size
        val diskHit = if (memoryHit) false else File(directory, "$index.blk").let {
          it.isFile && it.length() == size
        }
        if (!memoryHit && !diskHit) break
        index++
      }
      if (index == start / blockSize) null else min(end, index * blockSize - 1L)
    }
  }

  /** Return disk-restored blocks to the memory LRU instead of doing a 1 MiB
   * disk read every time FFmpeg seeks past the same metadata/keyframe. Still
   * NEVER calls fetch(): cache-only readers cannot reach the origin.
   */
  private fun cached(index: Long): Block? {
    synchronized(lock) { inMemory[index]?.let { return it } }
    val restored = restore(index) ?: return null
    restoredBlocks.incrementAndGet()
    keep(index, restored)
    return synchronized(lock) { inMemory[index] } ?: restored
  }

  /** The first missing 1 MiB block is always fetched normally. Only after a
   * reader has *actually crossed* into the adjacent block do we attempt one
   * two-block GET. This avoids downloading unnecessary neighbors for the very
   * common sparse/keyframe seeks, while saving a network RTT for contiguous
   * data on high-latency origins.
   *
   * The caller has already charged/approved the first block. If the second
   * admission token cannot be granted, we do the original single-block fetch.
   * A server rejecting a larger range disables this optional optimization,
   * not the existing working one-block transport.
   */
  private fun loadAdjacent(index: Long, lastInclusive: Long, allowNext: () -> Boolean): Block? {
    val group = fetchAdjacent ?: return null
    if (adjacentDisabled.get() || index < 0 || closed.get()) return null
    val total = synchronized(lock) { expectedTotal } ?: return null
    val start = index * blockSize
    val nextStart = start + blockSize
    if (lastInclusive < nextStart || nextStart > total - blockSize ||
        cached(index) != null || cached(index + 1) != null) return null
    if (!allowNext()) return null
    originFetches.incrementAndGet()
    adjacentFetches.incrementAndGet()
    val blocks = try { group(start, nextStart + blockSize - 1L) } catch (_: Exception) { null }
    if (blocks == null || blocks.size != 2 ||
      blocks[0].start != start || blocks[1].start != nextStart ||
      blocks.any { it.total != total || it.bytes.size != blockSize }) {
      // Some servers only support 1 MiB ranges; one failed probe per video.
      adjacentDisabled.set(true)
      return null
    }
    blocks.forEach { keep(it.start / blockSize, it) }
    adjacentSavedRequests.incrementAndGet()
    return cached(index)
  }

  /** Missing cached data throws, never downloads. Preview-only; NOT for playback. */
  fun streamCachedOnly(start: Long, lastInclusive: Long): InputStream = object : InputStream() {
    private var cursor = start
    private var stopped = false
    override fun read(): Int {
      val one = ByteArray(1)
      return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }
    override fun read(bytes: ByteArray, off: Int, len: Int): Int {
      if (off < 0 || len < 0 || off > bytes.size - len) throw IndexOutOfBoundsException()
      if (len == 0) return 0
      if (stopped || cursor > lastInclusive) return -1
      val index = cursor / blockSize
      // Deliberately bypass load() and its upstream fetch callback.
      val part = cached(index) ?: throw IOException("Uncached preview read")
      val within = (cursor - part.start).toInt()
      if (within < 0 || within >= part.bytes.size) throw IOException("Invalid cached preview block")
      val count = min(len.toLong(), min((part.bytes.size - within).toLong(), lastInclusive - cursor + 1)).toInt()
      part.bytes.copyInto(bytes, off, within, within + count)
      cursor += count
      return count
    }
    override fun close() { stopped = true }
  }

  /**
   * Low-priority preview reader: cache hits cost nothing; every *missing*
   * 1 MiB block requires an explicit admission token from the caller.
   * Playback and user-requested previews do not use this method. No downloader
   * is opened after cancellation or after the token budget is exhausted.
   */
  fun streamBudgeted(start: Long, lastInclusive: Long, allowMissingBlock: () -> Boolean): InputStream = object : InputStream() {
    private var cursor = start
    private var stopped = false
    private var lastServedIndex = -1L
    override fun read(): Int {
      val one = ByteArray(1)
      return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }
    override fun read(bytes: ByteArray, off: Int, len: Int): Int {
      if (off < 0 || len < 0 || off > bytes.size - len) throw IndexOutOfBoundsException()
      if (len == 0) return 0
      if (stopped || cursor > lastInclusive) return -1
      val index = cursor / blockSize
      val alreadyPresent = cached(index)
      val part = alreadyPresent ?: run {
        if (!allowMissingBlock()) throw IOException("Ahead cache budget exhausted")
        // Only batch after consecutive blocks were consumed by THIS reader.
        // Two missing blocks spend two admission tokens, not one.
        val adjacent = if (lastServedIndex >= 0 && lastServedIndex == index - 1L)
          loadAdjacent(index, lastInclusive, allowMissingBlock) else null
        adjacent ?: load(index) ?: throw IOException("Ahead cache range unavailable")
      }
      val within = (cursor - part.start).toInt()
      if (within < 0 || within >= part.bytes.size) throw IOException("Invalid ahead cache block")
      val count = min(len.toLong(), min((part.bytes.size - within).toLong(), lastInclusive - cursor + 1)).toInt()
      part.bytes.copyInto(bytes, off, within, within + count)
      cursor += count
      lastServedIndex = index
      return count
    }
    override fun close() { stopped = true }
  }

  /** Clients read any span of the file; new blocks are fetched only on demand. */
  fun stream(start: Long, lastInclusive: Long): InputStream = object : InputStream() {
    private var cursor = start
    private var stopped = false
    private var lastServedIndex = -1L
    override fun read(): Int {
      val one = ByteArray(1)
      return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
      if (length == 0) return 0
      if (stopped || cursor > lastInclusive) return -1
      val blockIndex = cursor / blockSize
      val part = cached(blockIndex) ?: run {
        val adjacent = if (lastServedIndex >= 0 && lastServedIndex == blockIndex - 1L)
          loadAdjacent(blockIndex, lastInclusive) { true } else null
        adjacent ?: load(blockIndex) ?: throw IOException("Video range no longer available")
      }
      val within = (cursor - part.start).toInt()
      if (within < 0 || within >= part.bytes.size) throw IOException("Short HTTP range response")
      val count = min(length.toLong(), min((part.bytes.size - within).toLong(), lastInclusive - cursor + 1)).toInt()
      part.bytes.copyInto(buffer, offset, within, within + count)
      cursor += count
      lastServedIndex = blockIndex
      return count
    }
    override fun close() { stopped = true }
  }

  private fun load(index: Long): Block? {
    if (closed.get() || index < 0) return null
    synchronized(lock) {
      if (unsupported) return null
      inMemory[index]?.let { return it }
    }
    val future = CompletableFuture<Block?>()
    val existing = pending.putIfAbsent(index, future)
    if (existing != null) return try { existing.get() } catch (_: InterruptedException) {
      Thread.currentThread().interrupt(); null
    } catch (_: ExecutionException) { null }
    try {
      val restored = restore(index)
      if (restored != null) restoredBlocks.incrementAndGet()
      val result = restored ?: fetchBlock(index)
      if (result != null && !closed.get()) keep(index, result)
      future.complete(result)
      return result
    } catch (_: RangeUnsupportedException) {
      synchronized(lock) { unsupported = true }
      future.complete(null)
      return null
    } catch (_: Exception) {
      // A canceled range read is NOT evidence that ranges are unsupported.
      future.complete(null)
      return null
    } finally {
      pending.remove(index, future)
    }
  }

  private fun restore(index: Long): Block? {
    val total: Long
    val savedMime: String
    synchronized(lock) {
      total = expectedTotal ?: return null
      savedMime = mime
    }
    val file = File(directory, "$index.blk")
    if (!file.isFile || file.length() > blockSize || file.length() == 0L) return null
    return try {
      val data = file.readBytes()
      val start = index * blockSize
      val expected = min(blockSize.toLong(), total - start)
      if (data.size.toLong() != expected) null else Block(start, total, data, savedMime)
    } catch (_: IOException) { null }
  }

  private fun fetchBlock(index: Long): Block? {
    val start = index * blockSize
    val last = start + blockSize - 1L
    // A null result can be a canceled OkHttp call while actual playback seeks.
    // Do not permanently disable the session cache on this transient failure.
    originFetches.incrementAndGet()
    val block = fetch(start, last) ?: return null
    val expectedSize = min(blockSize.toLong(), block.total - start)
    if (block.start != start || block.total <= 0 || expectedSize <= 0 ||
      block.bytes.size.toLong() != expectedSize) {
      synchronized(lock) { unsupported = true }
      return null
    }
    synchronized(lock) {
      if (expectedTotal != null && expectedTotal != block.total) {
        unsupported = true
        return null
      }
      expectedTotal = block.total
      mime = block.mime
    }
    return block
  }

  private fun keep(index: Long, block: Block) {
    synchronized(lock) {
      if (inMemory.containsKey(index)) return
      val output = File(directory, "$index.blk")
      val previouslyStored = output.isFile && output.length() == block.bytes.size.toLong()
      inMemory[index] = block
      // A disk restore is NOT new data. Only real additions advance revision;
      // otherwise a burst can keep retrying the same unavailable bytes.
      if (!previouslyStored) cachedRevision.incrementAndGet()
      memoryBytes += block.bytes.size
      while (memoryBytes > maxMemoryBytes && inMemory.isNotEmpty()) {
        val iterator = inMemory.entries.iterator()
        val oldest = iterator.next()
        memoryBytes -= oldest.value.bytes.size
        iterator.remove()
      }
      if (!previouslyStored) {
        // If a truncated/stale file exists, replace it safely. Never consider
        // its length a valid cached block.
        if (output.isFile) {
          diskBytes = (diskBytes - output.length()).coerceAtLeast(0L)
          output.delete()
        }
        // A whole block is persisted atomically; interrupted downloads never poison the cache.
        val temp = File(directory, "$index.part")
        try {
          temp.writeBytes(block.bytes)
          if (temp.renameTo(output)) diskBytes += output.length()
        } catch (_: IOException) { /* Memory cache remains useful */
        } finally { temp.delete() }
      }
      if (diskBytes > maxDiskBytes) {
        // Preserve the first and last eight MiB: MKV/MP4 metadata, seek
        // indexes and tail moov/cues commonly live here. Evict other blocks
        // first, rather than making a native open fail after playback advances.
        val lastIndex = (block.total - 1L) / blockSize
        val files = directory.listFiles()?.asSequence()
          ?.filter { it.extension == "blk" }
          ?.filter { file ->
            val number = file.nameWithoutExtension.toLongOrNull()
            number != null && number >= 8L && number < lastIndex - 7L
          }?.sortedBy { it.lastModified() }?.toList().orEmpty()
        for (file in files) {
          if (diskBytes <= maxDiskBytes * 3 / 4) break
          val size = file.length()
          if (file.delete()) {
            diskBytes -= size
            diskEvictions.incrementAndGet()
          }
        }
      }
    }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    synchronized(lock) { inMemory.clear(); fragments.clear(); memoryBytes = 0L }
    // Any blocked active streams are completed/closed by the HTTP bridge first.
    directory.deleteRecursively()
  }
}
