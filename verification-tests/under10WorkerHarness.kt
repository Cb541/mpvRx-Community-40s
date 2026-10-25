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
  SharedPlaybackTransportRegistry.stubMediaBytes = 15_000_000_000L
  val temp = File("/tmp/mpvrx-under20-fullpass-${System.nanoTime()}")
  PlaybackSession.propDouble["time-pos"] = MutableStateFlow(100.0)
  PlaybackSession.propDouble["demuxer-cache-duration"] = MutableStateFlow(115.0)
  val store = SeekPreviewStore(Context(temp), "https://example.invalid/fifteen-gb.mkv", 239L)
  store.warmAround(100f, 300f)  // playback begins at 1:40, but pre-gen starts at 0
  val clock = launch {
    while(true) { delay(300); PlaybackSession.propDouble["time-pos"]!!.value += 0.3 }
  }
  try {
    val requiredCoarse = (1..12).map { it * 24_000L }.toSet()
    var sawFine = false
    val started = SystemClock.elapsedRealtime()
    while (SystemClock.elapsedRealtime() - started < 23_000L) {
      delay(125)
      val frames = store.cachedFrameTimesMs().toSet()
      val anyFine = frames.any { it % 24_000L == 12_000L }
      if (anyFine) {
        val decisions = android.util.Log.schedulerEvents.toList()
        val firstFineIndex = decisions.indexOfFirst { "phase=FILL_12S" in it }
        check(firstFineIndex > 0) { "Missing fine-phase scheduling decision: $decisions" }
        val coarseVisited = decisions.take(firstFineIndex)
          .filter { "phase=COARSE_24S" in it }
          .mapNotNull { Regex("slotMs=(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
          .toSet()
        check(requiredCoarse.all { it in coarseVisited || it in frames }) {
          "12s scheduled before visiting/decoding every coarse position: visited=$coarseVisited cached=$frames missing=${requiredCoarse - coarseVisited - frames}"
        }
        sawFine = true
        break
      }
    }
    val result = store.cachedFrameTimesMs().toSet()
    check(sawFine) { "No 12s backfill within harness interval: $result" }
    check(24_000L in result && 288_000L in result && result.any { it % 24_000L == 12_000L })
    println("PASS: actual worker visited all movie-wide 24s slots before first 12s backfill; started playback at 1:40")
  } finally {
    clock.cancel()
    store.close()
    delay(300)
    temp.deleteRecursively()
  }
}
