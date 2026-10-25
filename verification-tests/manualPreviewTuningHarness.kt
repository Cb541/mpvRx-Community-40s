package app.gyrolet.mpvrx.domain.seekpreview

fun main() {
  fun checkSize(bytes: Long?, wanted: Int) {
    val actual = ManualPreviewTuning.dimension(bytes)
    check(actual == wanted) { "bytes=$bytes wanted=$wanted got=$actual" }
  }
  checkSize(null, 512)
  checkSize(0, 512)
  checkSize(500_000_000L, 512)
  checkSize(5_000_000_000L, 512)
  checkSize(5_000_000_001L, 448)
  checkSize(19_999_999_999L, 448)
  checkSize(20_000_000_000L, 416)
  checkSize(40_000_000_000L, 416)
  check(ManualPreviewTuning.warmIdleMs(true) == 30_000L)
  check(ManualPreviewTuning.warmIdleMs(false) == 8_000L)
  // Large file conversion buffer is smaller but still plenty for mobile
  // preview labels. No change to decoded input or exact timestamp.
  val saved = 1.0 - (416.0 * 416.0) / (512.0 * 512.0)
  check(saved > 0.33)
  println("PASS: size-aware pixel output, boundary sizes, paused-only 30s decoder reuse")
}
