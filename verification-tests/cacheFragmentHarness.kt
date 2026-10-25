import app.gyrolet.mpvrx.domain.seekpreview.SeekByteBlockCache
import app.gyrolet.mpvrx.domain.seekpreview.PlaybackRangeCapture
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.random.Random

fun main() {
  val dir = Files.createTempDirectory("mpvrx-fragment-verify").toFile()
  val blockSize = 4096
  val bytes = Random(42).nextBytes(12 * blockSize)
  val cache = SeekByteBlockCache(dir, { _, _ -> error("cache attempted unexpected network read") },
    blockSize, 24L * blockSize, 1L * blockSize)
  val capture = PlaybackRangeCapture(cache, blockSize, queueCapacity = 16)
  try {
    // Two valid playback 206 responses, neither a complete block on its own.
    val first = capture.wrap(ByteArrayInputStream(bytes.copyOfRange(0, 1111)),
      0, bytes.size.toLong(), "video/mp4", 1111)
    first.readBytes(); first.close()
    val second = capture.wrap(ByteArrayInputStream(bytes.copyOfRange(1111, blockSize)),
      1111, bytes.size.toLong(), "video/mp4", (blockSize - 1111).toLong())
    second.readBytes(); second.close()
    repeat(100) { if (cache.hasCachedSpan(0, (blockSize-1).toLong())) return@repeat; Thread.sleep(20) }
    check(cache.hasCachedSpan(0, (blockSize-1).toLong())) { "partial fragments did not assemble" }
    check(cache.streamCachedOnly(0, (blockSize-1).toLong()).readBytes().contentEquals(bytes.copyOfRange(0,blockSize)))
    println("PASS: aligned cache block assembled exactly from two partial 206 responses")
    // Force the first block out of the one-block RAM cache, then restore from disk.
    for (i in 1 until 10) {
      val start = i * blockSize
      check(cache.offerPlaybackBlock(start.toLong(), bytes.size.toLong(),
        bytes.copyOfRange(start, start + blockSize), "video/mp4"))
    }
    val revision = cache.revision()
    check(cache.stream(0, (blockSize-1).toLong()).readBytes().contentEquals(bytes.copyOfRange(0,blockSize)))
    check(cache.revision() == revision) { "a disk restore incorrectly incremented the revision" }
    println("PASS: disk restoration does not masquerade as newly cached data")
  } finally { capture.close(); cache.close(); dir.deleteRecursively() }
}
