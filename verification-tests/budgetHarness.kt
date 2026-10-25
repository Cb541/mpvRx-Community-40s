import app.gyrolet.mpvrx.domain.seekpreview.SeekByteBlockCache
import java.nio.file.Files
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

fun main() {
  val temp = Files.createTempDirectory("mpvrx-budgeted-ahead").toFile()
  val blockSize = 1024 * 1024
  val bytes = ByteArray(10 * blockSize) { (it * 31).toByte() }
  val remote = AtomicInteger(0)
  val cache = SeekByteBlockCache(temp, { start, last ->
    remote.incrementAndGet()
    SeekByteBlockCache.Block(start, bytes.size.toLong(),
      bytes.copyOfRange(start.toInt(), (last + 1).coerceAtMost(bytes.size.toLong()).toInt()), "video/mp4")
  })
  try {
    check(cache.offerPlaybackBlock(0L, bytes.size.toLong(), bytes.copyOfRange(0, blockSize), "video/mp4"))
    val tokens = AtomicInteger(3)
    var refused = false
    try {
      cache.streamBudgeted(0L, (8 * blockSize - 1).toLong()) { tokens.getAndDecrement() > 0 }.use {
        val buffer = ByteArray(blockSize)
        while (it.read(buffer) > -1) { }
      }
    } catch (_: IOException) { refused = true }
    check(refused) { "Budget failed to halt read" }
    check(remote.get() == 3) { "Exceeded remote block budget: ${remote.get()}" }
    check(cache.hasCachedSpan(0L, (4 * blockSize - 1).toLong()))
    // Data already fetched must be reusable without spending more tokens.
    val after = remote.get()
    cache.streamBudgeted(0L, (4 * blockSize - 1).toLong()) { false }.use {
      check(it.readBytes().contentEquals(bytes.copyOfRange(0, 4 * blockSize)))
    }
    check(remote.get() == after) { "Cached read refetched from origin" }
    // Future unavailable ranges fail closed and cannot initiate a single fetch.
    var denied = false
    try { cache.streamBudgeted((5 * blockSize).toLong(),(5 * blockSize+32).toLong()) { false }.use { it.read() } }
    catch (_: IOException) { denied = true }
    check(denied && remote.get() == 3)
    println("PASS: budget limits ahead network to 3 blocks, cached bytes reused, denied reads fail closed")
  } finally {
    cache.close()
    check(!temp.exists())
  }
}
