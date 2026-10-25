import app.gyrolet.mpvrx.domain.seekpreview.*
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import java.io.File

private suspend fun until(timeoutMs: Long, condition: () -> Boolean) {
  var waited = 0L
  while (!condition() && waited < timeoutMs) { delay(10); waited += 10 }
  check(condition()) { "Timed out waiting for frame worker after ${waited}ms" }
}

fun main() = runBlocking {
  val folder = File("/tmp/mpvrx-scrub-inflight-${System.nanoTime()}").also { check(it.mkdirs()) }
  val store = SeekPreviewStore(Context(folder), "https://example.invalid/movie.mkv", 194L)
  try {
    // Trigger actual 210ms native-decoder stub, move finger mid-extraction.
    store.request(listOf(12_000L))
    until(3_000) { NetworkSeekFrameRetriever.started.get() >= 1 }
    store.request(listOf(15_000L))
    until(3_000) { store.peekTimed(12_000L, 0L)?.timeMs == 12_000L }
    until(3_000) { store.peekTimed(15_000L, 0L)?.timeMs == 15_000L }
    check(NetworkSeekHttpBridge.interrupted.get() == 0) { "Moving finger canceled active decoder" }
    // Lift finger during the next native extraction, after it has started.
    store.request(listOf(24_000L))
    until(3_000) { NetworkSeekFrameRetriever.started.get() >= 3 }
    store.request(emptyList())
    until(3_000) { store.peekTimed(24_000L, 0L)?.timeMs == 24_000L }
    check(store.peekTimed(25_000L, 0L) == null) { "Wrong image was labeled as 25s" }
    check(NetworkSeekHttpBridge.interrupted.get() == 0) { "Finger lift canceled active decoder" }
    // Regression: previously request(emptyList()) immediately closed the
    // session; the next user scrub had to re-open a remote FFmpeg container.
    val warmOpenCount = NetworkSeekFrameRetriever.opened.get()
    val warmBridgeCount = NetworkSeekHttpBridge.created.get()
    store.request(listOf(36_000L))
    until(3_000) { store.peekTimed(36_000L, 0L)?.timeMs == 36_000L }
    check(NetworkSeekFrameRetriever.opened.get() == warmOpenCount) {
      "New scrub reopened native decoder immediately after finger lift"
    }
    check(NetworkSeekHttpBridge.created.get() == warmBridgeCount) {
      "New scrub reopened HTTP bridge immediately after finger lift"
    }
    println("PASS actual worker: moved/lifted completed frames cached; subsequent scrub reused warm decoder and HTTP bridge")
  } finally {
    store.close(); delay(60); folder.deleteRecursively()
  }
}
