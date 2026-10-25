package app.gyrolet.mpvrx.domain.seekpreview
import java.util.concurrent.atomic.AtomicInteger
fun main() {
  val retriever = NetworkSeekFrameRetriever(
    "https://example.com/video.mkv", emptyMap(), AtomicInteger(-1)) {
      "http://127.0.0.1:9999/local-preview"
    }
  check(retriever.persistentFrameAt(12_000L, dimension = 416) != null)
  check(PersistentNativeSeek.lastDimension == 416)
  check(retriever.persistentFrameAt(24_000L, dimension = 448) != null)
  check(PersistentNativeSeek.lastDimension == 448)
  check(retriever.persistentFrameAt(36_000L, dimension = 512) != null)
  check(PersistentNativeSeek.lastDimension == 512)
  check(retriever.warmFrameAt(48_000L, {true}) != null)
  check(PersistentNativeSeek.lastDimension == 416)
  retriever.close()
  println("PASS: persistent FFmpeg context reused with 416, 448, 512 output dimensions; ahead remains 416")
}
