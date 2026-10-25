package app.gyrolet.mpvrx.domain.seekpreview
fun main() {
  val a = ForegroundPreviewAdmission
  val bands = AheadPacing.SizeBand.entries
  for (band in bands) {
    val manual = a.allowFastGrace(band, manualRequest = true)
    val ahead = a.allowFastGrace(band, manualRequest = false)
    if (band == AheadPacing.SizeBand.UNKNOWN) {
      check(!manual && !ahead) { "Unknown size must retain conservative gate" }
    } else {
      check(manual) { "Known-size manual preview must qualify: $band" }
      check(ahead == (band == AheadPacing.SizeBand.LARGE)) { "Ahead policy changed for $band" }
    }
    fun grace(allow: Boolean, paused: Boolean = false, coreIdle: Boolean? = false,
       buffer: Double? = 90.0, advanced: Boolean = true) =
       a.seekGraceMs(allow, paused, coreIdle, buffer, advanced)
    check(grace(manual) == if (manual) 250L else 2_000L)
    check(grace(manual, paused = true) == if (manual) 350L else 2_000L)
    check(grace(ahead) == if (ahead) 250L else 2_000L)
    // Nothing gets a shortened grace without proven playback safety.
    check(grace(manual, coreIdle = true) == 2_000L)
    check(grace(manual, coreIdle = null) == 2_000L)
    check(grace(manual, buffer = 12.0) == 2_000L)
    check(grace(manual, buffer = Double.NaN) == 2_000L)
    check(grace(manual, buffer = null) == 2_000L)
    check(grace(manual, advanced = false) == 2_000L)
    check(grace(manual, paused = true, buffer = 19.0) == if (manual) 650L else 2_000L)
    check(grace(manual, paused = true, buffer = 2.0) == if (manual) 1_000L else 2_000L)
    check(grace(manual, paused = true, coreIdle = true) == if (manual) 1_000L else 2_000L)
    check(grace(manual, buffer = 44.99) == 2_000L)
    println("PASS ${band}: manual=$manual ahead=$ahead healthyManualGrace=${grace(manual)}ms")
  }
  // File size classification and 20GB threshold remain unchanged.
  check(AheadPacing.sizeBand(1_000_000_000) == AheadPacing.SizeBand.SMALL)
  check(AheadPacing.sizeBand(10_000_000_000) == AheadPacing.SizeBand.MEDIUM)
  check(AheadPacing.sizeBand(20_000_000_000) == AheadPacing.SizeBand.LARGE)
  check(AheadPacing.sizeBand(null) == AheadPacing.SizeBand.UNKNOWN)
  println("PASS: fast manual preview extended to all KNOWN file sizes; ahead and safety unchanged")
}
