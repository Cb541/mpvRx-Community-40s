import app.gyrolet.mpvrx.domain.seekpreview.SeekByteBlockCache
import app.gyrolet.mpvrx.domain.seekpreview.PlaybackRangeCapture
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

fun main() {
  val root = Files.createTempDirectory("shared-range-stress").toFile()
  val size = 4096
  val bytes = Random(82).nextBytes(512*size+123)
  val fetches = AtomicInteger(0)
  val cache = SeekByteBlockCache(root, { first, last ->
    fetches.incrementAndGet()
    val end = minOf(bytes.size.toLong(), last+1).toInt()
    SeekByteBlockCache.Block(first, bytes.size.toLong(), bytes.copyOfRange(first.toInt(), end), "video/mp4")
  }, size, maxDiskBytes=64L*size, maxMemoryBytes=6L*size)
  val capture = PlaybackRangeCapture(cache, size, queueCapacity=8)
  val workers = Executors.newFixedThreadPool(5)
  try {
    val playback = workers.submit {
      val input = capture.wrap(ByteArrayInputStream(bytes),0,bytes.size.toLong(),"video/mp4")
      val chunk=ByteArray(1331)
      var count=0
      while (true) {
        val n=input.read(chunk)
        if (n<0) break
        count+=n
        if (count % (64*size) < 1331) Thread.sleep(3)
      }
      input.close()
      check(count==bytes.size)
    }
    val readers=(0 until 4).map { index -> workers.submit {
      val random=Random(index+1)
      repeat(400) {
        val start=random.nextInt(0,bytes.size-5000)
        val end=start+random.nextInt(1,4096)
        val got=cache.stream(start.toLong(),end.toLong()).readBytes()
        check(got.contentEquals(bytes.copyOfRange(start,end+1))) { "range mismatch: $start-$end" }
      }
    } }
    playback.get(20,TimeUnit.SECONDS)
    readers.forEach { it.get(20,TimeUnit.SECONDS) }
    println("PASS concurrent playback + 1600 random preview ranges, cache eviction, zero mismatched bytes")
    println("Observed ${fetches.get()} safe backing-range fetches during overlapping playback and previews")
  } finally { workers.shutdownNow();capture.close();cache.close();root.deleteRecursively() }
}
