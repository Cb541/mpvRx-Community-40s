import android.content.Context
import app.gyrolet.mpvrx.domain.seekpreview.SeekPreviewStore
import app.gyrolet.mpvrx.domain.seekpreview.NetworkSeekFrameRetriever
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.File

/** Regression: thousand-frame videos use fast disk indexing. An approximate
 * grid JPEG remains available for optional fallback display, but it is NOT
 * considered an accurate 1-second cache hit by the real preview UI. */
fun main() = runBlocking {
  val temp = File("/tmp/mpvrx-large-jpeg-index-${System.nanoTime()}")
  val dir = File(temp, "jpg-991").also { check(it.mkdirs()) }
  // Represent roughly ten hours of 12s JPEGs. Only one is decoded at a time.
  repeat(3_000) { File(dir, "${it * 12_000L}.jpg").writeBytes(byteArrayOf(1, 2, 3)) }
  PlaybackSession.propBoolean["seeking"] = MutableStateFlow(true)
  val store = SeekPreviewStore(Context(temp), "https://example.invalid/huge-remux.mkv", 991L)
  try {
    val started = System.nanoTime()
    repeat(500) { j ->
      val slot = ((j * 7 + 23) % 2_900) * 12_000L
      val between = slot + (j % 12) * 1_000L
      check(store.hasCachedPreview(between, 6_000L)) { "indexed JPEG unavailable at $between" }
      check(store.primeCachedPreview(between, 6_000L)) { "failed to queue cached JPEG for $between" }
      check(!store.hasCachedPreview(between, 500L) || (j % 12) == 0 ||
        (j % 12) >= 11) { "unexpected precise hit at $between" }
    }
    val elapsedMs = (System.nanoTime() - started) / 1_000_000L
    // Loose bound for shared CI: 500 floor/ceiling probes must not take seconds.
    check(elapsedMs < 2_000L) { "cached seek index lookup too slow: ${elapsedMs}ms" }
    val sought = 24_000L * 200L + 5_000L
    check(store.primeCachedPreview(sought, 6_000L))
    var found = false
    repeat(80) {
      if (store.peekTimed(sought, 6_000L) != null) found = true
      if (!found) delay(10L)
    }
    check(found) { "most recent disk-only seek did not become available" }
    check(NetworkSeekFrameRetriever.extracted.get() == 0) { "cache-only scrub opened network decoder" }
    store.request(emptyList())
    println("PASS: 3000 indexed JPEGs, 500 mixed-position seeks in ${elapsedMs}ms, disk read available without native FFmpeg")
  } finally {
    store.close(); delay(30L); temp.deleteRecursively()
  }
}
