import app.gyrolet.mpvrx.domain.seekpreview.SeekPreviewStore
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

fun main() = runBlocking {
  val root = File(System.getProperty("java.io.tmpdir"), "mpvrx-stage36-${System.nanoTime()}")
  root.mkdirs()
  val store = SeekPreviewStore(Context(root), "file:///tmp/preview.mkv", 883L)
  val spool = File(root, "jpg-883")
  try {
    // Stale screenshot must never be published under the wrong 12-s slot.
    PlaybackSession.propDouble["time-pos"] = MutableStateFlow(0.0)
    PlaybackSession.grabDelayMs = 150L
    store.capturePlaybackFrame(0f)
    delay(40L)
    PlaybackSession.propDouble["time-pos"]!!.value = 36.0
    delay(300L)
    check(store.peek(0L) == null) { "Stale screenshot was mislabelled as slot 0" }
    val reservation = SeekPreviewStore::class.java.getDeclaredField("lastCaptureSlot")
      .apply { isAccessible = true }
    check(reservation.getLong(store) == -1L) { "Discarded screenshot left a stuck reservation" }
    println("PASS: wrong-position screenshot discarded and capture reservation released")

    // Once time comes back, the same slot must be eligible for fresh capture.
    PlaybackSession.grabDelayMs = 0L
    PlaybackSession.propDouble["time-pos"]!!.value = 0.0
    store.capturePlaybackFrame(0f)
    delay(150L)
    check(store.peek(0L) != null) { "Capture did not recover after discarded screenshot" }
    println("PASS: subsequent capture of same slot succeeds")

    // Test that eight successively saved JPEGs trigger one prune cycle.
    val persist = SeekPreviewStore::class.java.getDeclaredMethod("persist", Long::class.javaPrimitiveType, Bitmap::class.java)
      .apply { isAccessible = true }
    val count = SeekPreviewStore::class.java.getDeclaredField("jpegWritesSincePrune")
      .apply { isAccessible = true }
    val start = count.getInt(store)
    repeat(8) { i -> persist.invoke(store, 12000L * (i+1), Bitmap()) }
    check(count.getInt(store) == start) { "JPEG prune cadence did not wrap after 8 saves" }
    check(spool.listFiles()!!.count { it.extension == "jpg" } >= 9)
    println("PASS: serialized JPEG writer stores frames and amortizes directory scans")
  } finally {
    store.close()
    delay(100L)
    root.deleteRecursively()
  }
}
