package android.graphics
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
class Bitmap(val stamp: Int = counter.incrementAndGet()) {
  companion object { private val counter = AtomicInteger(0) }
  val width: Int get() = 416
  val height: Int get() = 234
  val byteCount: Int get() = 1024
  fun sameAs(other: Bitmap): Boolean = stamp == other.stamp
  enum class CompressFormat { JPEG }
  fun compress(format: CompressFormat, quality: Int, stream: FileOutputStream): Boolean {
    stream.write(byteArrayOf(1,2,3)); return true
  }
}
object BitmapFactory { fun decodeFile(path: String): Bitmap? = if(java.io.File(path).exists()) Bitmap() else null }
