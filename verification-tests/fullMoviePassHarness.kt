import app.gyrolet.mpvrx.domain.seekpreview.AheadFullMoviePasses
import app.gyrolet.mpvrx.domain.seekpreview.AheadPacing
import app.gyrolet.mpvrx.domain.seekpreview.AheadTwoPassGrid

fun main() {
  check(AheadPacing.fullMovieCoarseFirst(1L))
  check(AheadPacing.fullMovieCoarseFirst(9_999_999_999L))
  check(AheadPacing.fullMovieCoarseFirst(10_000_000_000L))
  check(AheadPacing.fullMovieCoarseFirst(15_000_000_000L))
  check(AheadPacing.fullMovieCoarseFirst(19_999_999_999L))
  check(!AheadPacing.fullMovieCoarseFirst(20_000_000_000L))
  check(!AheadPacing.fullMovieCoarseFirst(20_000_000_001L))
  check(!AheadPacing.fullMovieCoarseFirst(null))
  check(!AheadPacing.fullMovieCoarseFirst(0L))

  // Test a 20-minute video, including 192s section boundaries: no midpoint
  // can enter the work queue until the whole film's 24s pass has been visited.
  val completed = mutableSetOf<Long>()
  val state = AheadFullMoviePasses()
  val duration = 20 * 60_000L
  val coarse = mutableListOf<Long>()
  repeat(49) {
    val next = state.select(duration, { it in completed }) ?: error("missing coarse frame $it")
    check(next.pass == AheadTwoPassGrid.Pass.COARSE_24S) { "interleaved fine pass at $next" }
    check(next.slotMs == (it + 1L) * 24_000L) { "wrong coarse position $next" }
    coarse.add(next.slotMs)
    completed.add(next.slotMs)
  }
  check(coarse.size == 49)  // 20:00 itself is EOF, never decode at 20:00
  repeat(50) {
    val next = state.select(duration, { it in completed }) ?: error("missing fine frame $it")
    check(next.pass == AheadTwoPassGrid.Pass.FILL_12S)
    check(next.slotMs == 12_000L + it * 24_000L)
    completed.add(next.slotMs)
  }
  check(state.select(duration, { it in completed }) == null)

  // A temporarily unavailable 24s sample is traversed on pass 1 but
  // retried only on the later full-film sweep; it must not block 12s forever.
  val deferred = AheadFullMoviePasses()
  val ready = mutableSetOf<Long>()
  var sawFine = false
  var firstCoarseFinished = false
  var coarseSelections = 0
  repeat(70) {
    val next = deferred.select(5 * 60_000L,
      { it in ready },
      { it != 72_000L || firstCoarseFinished }) ?: return@repeat
    if (next.pass == AheadTwoPassGrid.Pass.FILL_12S) {
      sawFine = true
      firstCoarseFinished = true
    } else if (!sawFine) {
      check(next.slotMs != 72_000L) // deferred by retry cooldown
      coarseSelections++
    }
    ready.add(next.slotMs)
  }
  check(coarseSelections == 11) // twelve 24s slots, one unavailable
  check(sawFine)
  check(72_000L in ready) // missing coarse eventually revisited

  // A <24s file can still backfill the one 12s frame, without a coarse slot.
  val tiny = AheadFullMoviePasses()
  check(tiny.select(23_000, { false })?.slotMs == 12_000L)
  check(tiny.select(12_000, { false }) == null) // exact EOF excluded

  // Session playback seeks don't reset an in-progress sweep.
  val onSeek = AheadFullMoviePasses()
  val a = onSeek.select(3_600_000, { false })!!
  val b = onSeek.select(3_600_000, { false })!!
  check(a.slotMs == 24_000L && b.slotMs == 48_000L)
  println("PASS: <20GB whole-film coarse pass, full backfill, retry, EOF, boundaries and seek continuation")
}
