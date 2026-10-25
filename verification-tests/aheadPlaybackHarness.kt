import app.gyrolet.mpvrx.domain.seekpreview.*
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

fun main()=runBlocking {
 // Verify the actual scheduling order: every 24-second position first,
 // followed by the eight intervening 12-second positions. No bitmap can
 // be marked as a different timestamp just to satisfy an empty slot.
 val base = 96_000L
 val end = base + AheadTwoPassGrid.WINDOW_MS
 val filled = mutableSetOf<Long>()
 repeat(8) { i ->
   val next = AheadTwoPassGrid.select(base, end, { it in filled })
     ?: error("Coarse pass unexpectedly complete at $i")
   check(next.pass == AheadTwoPassGrid.Pass.COARSE_24S)
   check(next.strideMs == 24_000L && next.slotMs == base + (i + 1) * 24_000L)
   filled += next.slotMs
 }
 repeat(8) { i ->
   val next = AheadTwoPassGrid.select(base, end, { it in filled })
     ?: error("12s backfill unexpectedly complete at $i")
   check(next.pass == AheadTwoPassGrid.Pass.FILL_12S)
   check(next.strideMs == 24_000L && next.slotMs == base + 12_000L + i * 24_000L)
   filled += next.slotMs
 }
 check(AheadTwoPassGrid.fullyFilled(base, end, { it in filled }))
 check(AheadTwoPassGrid.select(base, end, { it in filled }) == null)
 val temporarilyBlocked = AheadTwoPassGrid.select(base, end,
   isFilled = { it in filled && it != base + 24_000L },
   canAttempt = { it != base + 24_000L })
 check(temporarilyBlocked == null) { "Cooldown should prevent busy retries" }
 println("PASS: 24-second first pass, 12-second backfill, and retry gating")
 val temp=File("/tmp/mpvrx-stage3-2-test-${System.nanoTime()}")
 PlaybackSession.propDouble["time-pos"] = MutableStateFlow(100.0)
 PlaybackSession.propDouble["demuxer-cache-duration"] = MutableStateFlow(115.0)
 val store=SeekPreviewStore(Context(temp), "https://example.invalid/movie.mkv", 99L)
 store.warmAround(100f,800f)
 val clock=launch {
  while(true) { delay(300); PlaybackSession.propDouble["time-pos"]!!.value += 0.3 }
 }
 try {
  val begun=SystemClock.elapsedRealtime()
  while(SystemClock.elapsedRealtime()-begun<9_000) {
    if (store.cachedFrameTimesMs().size >= 4) break
    delay(150)
  }
  val slots=store.cachedFrameTimesMs()
  check(slots.size>=4) { "Burst did not publish 4 thumbnails; slots=$slots" }
  check(slots.zipWithNext().all{ (a,b)-> b-a==24_000L }) { "Expected first-pass 24s burst: $slots" }
  check(SharedPlaybackTransportRegistry.opens.get()==1) { "Expected one network session for first four frames" }
  check(PersistentNativeSeek.extracts.get()>=4)
  println("PASS: one persistent native session yielded four ahead 24-second frames")
  println("PASS: one bounded session opened; no parallel download workers")
 } finally {clock.cancel();store.close(); delay(300); temp.deleteRecursively() }
}
