package app.gyrolet.mpvrx.domain.seekpreview
fun main() {
  check(AheadPacing.cooldownMs(null, 10) == 12_000L) { "unknown cache must remain conservative" }
  check(AheadPacing.cooldownMs(Double.NaN, 10) == 12_000L)
  check(AheadPacing.cooldownMs(200.0, 1) == 12_000L) { "short-term good cache does not override warmup" }
  check(AheadPacing.cooldownMs(89.0, 10) == 7_000L)
  check(AheadPacing.cooldownMs(120.0, 2) == 7_000L)
  check(AheadPacing.cooldownMs(90.0, 5) == 4_000L)
  check(AheadPacing.cooldownMs(55.0, 5) == 12_000L)
  check(AheadPacing.framesPerSuccessfulSession(null, 10) == 2)
  check(AheadPacing.framesPerSuccessfulSession(70.0, 5) == 3)
  check(AheadPacing.framesPerSuccessfulSession(95.0, 5) == 4)
  check(AheadPacing.FAILED_SLOT_RETRY_MS >= 20_000L)
  println("PASS: adaptive cooldown and bounded burst policy")
}
