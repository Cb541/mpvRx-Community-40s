import app.gyrolet.mpvrx.domain.seekpreview.*
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

fun main() = runBlocking {
 val temp = File("/tmp/mpvrx-ahead-repeat-${System.nanoTime()}")
 PlaybackSession.propDouble["time-pos"] = MutableStateFlow(100.0)
 PlaybackSession.propDouble["demuxer-cache-duration"] = MutableStateFlow(115.0)
 // The second attempt is an identical image even though the target moved
 // forward by 12 s: it MUST NOT be published or stop later fast frames.
 PersistentNativeSeek.originalTargetSecond = 120L
 PersistentNativeSeek.repeatedTargetSecond = 144L
 val store = SeekPreviewStore(Context(temp), "https://example.invalid/movie.mkv", 99L)
 store.warmAround(100f, 800f)
 val clock = launch {
  while (true) { delay(300); PlaybackSession.propDouble["time-pos"]!!.value += 0.3 }
 }
 try {
  val begin = SystemClock.elapsedRealtime()
  var slots: List<Long> = emptyList()
  while (SystemClock.elapsedRealtime() - begin < 11_000L) {
   slots = store.cachedFrameTimesMs()
   if (168_000L in slots && 192_000L in slots) break
   delay(125)
  }
  check(120_000L in slots) { "Valid first frame was not stored: $slots" }
  check(144_000L !in slots) { "Repeated incorrect frame was incorrectly saved: $slots" }
  check(168_000L in slots && 192_000L in slots) { "Duplicate killed the fast burst: $slots" }
  check(SharedPlaybackTransportRegistry.opens.get() == 1) { "Additional HTTP sessions opened" }
  println("PASS: suspect duplicate suppressed without aborting remaining burst slots: $slots")
 } finally { clock.cancel(); store.close(); delay(300); temp.deleteRecursively() }
}
