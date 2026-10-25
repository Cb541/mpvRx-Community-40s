import app.gyrolet.mpvrx.domain.seekpreview.SeekByteBlockCache
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

private fun runCase(title: String, allowGroup: Boolean, budgeted: Boolean) {
  val root = Files.createTempDirectory("mpvrx-range-batch-").toFile()
  val blockSize = 16
  val total = 16L * 8
  val single = mutableListOf<Long>()
  val pairs = mutableListOf<Long>()
  val tokens = AtomicInteger()
  val cache = SeekByteBlockCache(
    root,
    { start, _ ->
      single += start
      SeekByteBlockCache.Block(start, total, ByteArray(blockSize) { ((start + it) % 251).toByte() }, "video/mp4")
    },
    blockSize = blockSize,
    maxDiskBytes = total,
    maxMemoryBytes = 16,
    fetchAdjacent = { start, end ->
      pairs += start
      if (!allowGroup) null
      else listOf(start, start + blockSize).map { at ->
        SeekByteBlockCache.Block(at, total, ByteArray(blockSize) { ((at + it) % 251).toByte() }, "video/mp4")
      }.also { check(end - start + 1L == 2L * blockSize) }
    }
  )
  try {
    check(cache.prime(0))
    val input = if (budgeted) cache.streamBudgeted(0, 4L * blockSize - 1L) {
      tokens.incrementAndGet(); true
    } else cache.stream(0, 4L * blockSize - 1L)
    val bytes = input.use { it.readBytes() }
    check(bytes.size == blockSize * 4)
    check(bytes.contentEquals(ByteArray(blockSize * 4) { (it % 251).toByte() }))
    check(pairs == listOf(blockSize.toLong())) { "wrong batch starts $pairs" }
    check(single == if (allowGroup) listOf(0L, 3L * blockSize) else listOf(0L, blockSize.toLong(), 2L * blockSize, 3L * blockSize)) { "wrong singles $single" }
    check(cache.stats().adjacentDisabled == !allowGroup)
    if (budgeted) check(tokens.get() == if (allowGroup) 3 else 4) { "wrong charged missing blocks ${tokens.get()}" }
    // Follow-on requests must all use the warmed cache, not repeat origin calls.
    val origSingles = single.size
    val origPairs = pairs.size
    cache.streamCachedOnly(0, 4L * blockSize - 1L).use { stream -> check(stream.readBytes().contentEquals(bytes)) }
    check(single.size == origSingles && pairs.size == origPairs)
    println("PASS $title")
  } finally { cache.close(); root.deleteRecursively() }
}

private fun testSparseDoesNotBatch() {
  val root = Files.createTempDirectory("mpvrx-sparse-").toFile()
  var pairs = 0
  val cache = SeekByteBlockCache(root,
    { start, _ -> SeekByteBlockCache.Block(start, 1024, ByteArray(16), "video/mp4") },
    blockSize = 16, maxDiskBytes = 1024, maxMemoryBytes = 16,
    fetchAdjacent = { _, _ -> pairs++; null })
  try {
    check(cache.prime(0))
    cache.stream(256, 260).use { check(it.readBytes().size == 5) }
    cache.stream(32, 36).use { check(it.readBytes().size == 5) }
    check(pairs == 0) { "sparse seeks must not download adjacent blocks" }
    println("PASS sparse keyframe seeks preserve single-range behavior")
  } finally { cache.close(); root.deleteRecursively() }
}

fun main() {
  runCase("foreground contiguous blocks group safely", true, false)
  runCase("budgeted ahead groups without exceeding tokens", true, true)
  runCase("range-rejecting origin falls back to one-block reads", false, false)
  runCase("range-rejecting origin uses existing budgeted fallback", false, true)
  testSparseDoesNotBatch()
  testHotDiskRestores()
}

private fun testHotDiskRestores() {
  val root = Files.createTempDirectory("mpvrx-disk-restore-").toFile()
  val cache = SeekByteBlockCache(root,
    { _, _ -> error("Cache-only path must not fetch") },
    blockSize = 16, maxDiskBytes = 128, maxMemoryBytes = 16)
  try {
    check(cache.offerPlaybackBlock(0, 128, ByteArray(16) { 12 }, "video/mp4"))
    check(cache.offerPlaybackBlock(16, 128, ByteArray(16) { 23 }, "video/mp4"))
    // Memory contains only block 1. Reading block 0 should promote it back to RAM.
    cache.streamCachedOnly(0, 15).use { check(it.readBytes().all { b -> b == 12.toByte() }) }
    check(root.resolve("0.blk").delete())
    // Disk is now gone; a second cache-only read must still succeed from RAM.
    cache.streamCachedOnly(0, 15).use { check(it.readBytes().all { b -> b == 12.toByte() }) }
    println("PASS cached decoder reuses disk-restored block from RAM")
  } finally { cache.close(); root.deleteRecursively() }
}
