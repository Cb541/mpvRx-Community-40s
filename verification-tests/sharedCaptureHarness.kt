import app.gyrolet.mpvrx.domain.seekpreview.SeekByteBlockCache
import app.gyrolet.mpvrx.domain.seekpreview.PlaybackRangeCapture
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import kotlin.random.Random

private fun waitFor(check: () -> Boolean): Boolean {
  repeat(100) { if (check()) return true; Thread.sleep(20) }
  return check()
}
fun main() {
  val base = Files.createTempDirectory("shared-media-test").toFile()
  val blockSize = 4096
  val bytes = Random(91).nextBytes(blockSize * 11 + 91)
  var remoteFetches = 0
  val cache = SeekByteBlockCache(base, { start, end ->
    remoteFetches++
    val actual = (end + 1).coerceAtMost(bytes.size.toLong()).toInt()
    SeekByteBlockCache.Block(start, bytes.size.toLong(), bytes.copyOfRange(start.toInt(), actual), "video/mp4")
  }, blockSize = blockSize, maxDiskBytes = 30L * blockSize, maxMemoryBytes = 2L * blockSize)
  val capture = PlaybackRangeCapture(cache, blockSize = blockSize, queueCapacity = 20)
  try {
    val playback = capture.wrap(ByteArrayInputStream(bytes), 0L, bytes.size.toLong(), "video/mp4")
    // Read playback with irregular network-sized chunks, including single bytes.
    val obtained = ArrayList<Byte>()
    val part = ByteArray(1701)
    repeat(13) { val b = playback.read(); check(b >= 0); obtained.add(b.toByte()) }
    while (true) {
      val n = playback.read(part)
      if (n < 0) break
      obtained.addAll(part.take(n))
    }
    playback.close()
    check(obtained.toByteArray().contentEquals(bytes)) { "playback read was altered" }
    check(waitFor { capture.stats().storedBlocks == 12L }) { "didn't store 12 blocks: ${capture.stats()}" }
    check(cache.hasCachedSpan(0L, bytes.size.toLong()-1))
    val output = cache.stream(2222L, (bytes.size-1).toLong()).readBytes()
    check(output.contentEquals(bytes.copyOfRange(2222, bytes.size)))
    check(remoteFetches == 0) { "preview should reuse playback data, fetched $remoteFetches remote blocks" }
    println("PASS playback-to-preview reuse: 12 stored blocks / 0 thumbnail origin requests")

    // An interrupted seek starting halfway into a block MUST NOT persist the partial block.
    val second = Files.createTempDirectory("shared-media-interrupted-test").toFile()
    val cache2 = SeekByteBlockCache(second, { _, _ -> null }, blockSize = blockSize,
      maxDiskBytes = 16L * blockSize, maxMemoryBytes = 2L * blockSize)
    val capture2 = PlaybackRangeCapture(cache2, blockSize, queueCapacity = 12)
    try {
      val first = blockSize / 2
      val src = capture2.wrap(ByteArrayInputStream(bytes.copyOfRange(first, first+blockSize)), first.toLong(), bytes.size.toLong(), "video/mp4")
      val consumed = ByteArray(blockSize/4)
      check(src.read(consumed) == consumed.size)
      src.close() // canceled before aligned block completed
      Thread.sleep(120)
      check(!cache2.hasCachedSpan(0L, (blockSize-1).toLong()))
      check(!cache2.hasCachedSpan(blockSize.toLong(), (2*blockSize-1).toLong()))
      println("PASS canceled partial responses never poison byte cache")
    } finally { capture2.close(); cache2.close(); second.deleteRecursively() }
    println("PASS playback bytes and cache reads remain identical")
  } finally { capture.close(); cache.close(); base.deleteRecursively() }
}
