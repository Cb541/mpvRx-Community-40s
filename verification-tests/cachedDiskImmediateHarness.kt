import app.gyrolet.mpvrx.domain.seekpreview.*
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

fun main() = runBlocking {
  val temp = File("/tmp/mpvrx-instant-cache-${System.nanoTime()}")
  val session = File(temp, "jpg-991").also { check(it.mkdirs()) }
  // Emulate two previously-generated JPEGs that are not in the RAM LRU.
  File(session, "24000.jpg").writeBytes(byteArrayOf(1, 2, 3))
  File(session, "36000.jpg").writeBytes(byteArrayOf(1, 2, 3))
  File(session, "48000.jpg").writeBytes(byteArrayOf(1, 2, 3))
  PlaybackSession.propBoolean["seeking"] = MutableStateFlow(true)
  val store = SeekPreviewStore(Context(temp), "https://example.invalid/remote.mkv", 991L)
  try {
    check(store.peekTimed(24_000L, 1_000L) == null) { "test requires an initial RAM miss" }
    check(store.primeCachedPreview(24_000L)) { "exact on-disk hit not recognized" }
    store.request(listOf(24_000L))
    var elapsed = 0L
    while (store.peekTimed(24_000L, 1_000L) == null && elapsed < 900L) {
      delay(10L); elapsed += 10L
    }
    check(store.peekTimed(24_000L, 1_000L)?.timeMs == 24_000L) {
      "disk hit was delayed behind playback seek admission"
    }
    var warmed = false
    repeat(70) {
      if (store.peekTimed(36_000L, 1_000L)?.timeMs == 36_000L) warmed = true
      if (!warmed) delay(10L)
    }
    check(warmed) { "next 12s on-disk preview was not prewarmed" }
    check(!store.primeCachedPreview(29_000L)) {
      "a 5-second-distant thumbnail was incorrectly counted as an accurate cached hit"
    }
    // A non-exact 12s grid sample is still immediately useful if the REAL
    // cached timestamp is disclosed. This must remain distinct from accuracy.
    check(store.primeCachedPreview(29_000L, 6_000L)) {
      "half-grid cached sample wasn't available to the UI"
    }
    check(store.peekTimed(29_000L, 6_000L)?.timeMs == 24_000L) {
      "nearest cached sample was not selected with its true timestamp"
    }
    // Exercise a disk-only JPEG 5 seconds away: it must load without the
    // foreground FFmpeg decoder despite playback's seeking gate being set.
    check(store.peekTimed(53_000L, 6_000L) == null)
    check(store.primeCachedPreview(53_000L, 6_000L))
    var loadedApproximate = false
    repeat(70) {
      if (store.peekTimed(53_000L, 6_000L)?.timeMs == 48_000L) loadedApproximate = true
      if (!loadedApproximate) delay(10L)
    }
    check(loadedApproximate) { "approximate cached disk sample did not arrive promptly" }
    check(!store.primeCachedPreview(53_000L)) {
      "approximate cached sample incorrectly treated as exact by FFmpeg"
    }
    println("PASS: exact disk hits; independent prewarming; on-disk half-grid sample displayed without decoding; precise miss remains a miss")
  } finally {
    store.close(); delay(80L); temp.deleteRecursively()
  }
}
