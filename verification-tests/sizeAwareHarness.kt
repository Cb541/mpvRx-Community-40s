package app.gyrolet.mpvrx.domain.seekpreview

fun main() {
  val policy = AheadPacing
  val small = policy.plan(1_500_000_000L, 120.0, 6)
  val medium = policy.plan(10_000_000_000L, 120.0, 6)
  val boundaryBefore = policy.plan(19_999_999_999L, 120.0, 6)
  val large = policy.plan(20_000_000_000L, 120.0, 6)
  val huge = policy.plan(55_000_000_000L, 120.0, 6)
  val unknown = policy.plan(null, 120.0, 6)
  check(small.band == AheadPacing.SizeBand.SMALL)
  check(medium.band == AheadPacing.SizeBand.MEDIUM)
  check(boundaryBefore.band == AheadPacing.SizeBand.MEDIUM)
  check(large.band == AheadPacing.SizeBand.LARGE)
  check(huge.band == AheadPacing.SizeBand.LARGE)
  check(unknown.band == AheadPacing.SizeBand.UNKNOWN)
  check(small.cooldownMs <= 1_000 && small.maxMissingBlocks == 32 && small.burstFrames >= 8)
  check(medium.cooldownMs > small.cooldownMs && medium.maxMissingBlocks < small.maxMissingBlocks)
  check(large.cooldownMs >= 9_000 && large.maxMissingBlocks <= 6 && large.burstFrames <= 3)
  check(large.scanSlots < small.scanSlots)
  check(unknown.cooldownMs >= 12_000 && unknown.maxMissingBlocks <= 6)
  check(small.maxRangeWindowBytes <= small.maxMissingBlocks * 1024L * 1024L)
  check(large.maxRangeWindowBytes <= large.maxMissingBlocks * 1024L * 1024L)
  // Never increase background network pressure with an unknown or low buffer.
  val unwarmed = policy.plan(1_500_000_000L, null, 10)
  val low = policy.plan(1_500_000_000L, 25.0, 10)
  val stalled = policy.plan(1_500_000_000L, 120.0, 0)
  // Current Stage 3.8 small-file profile intentionally allows 2.5 s
  // cooldown while enforcing the same <=6 MiB / <=5 s hard caps.
  check(unwarmed.cooldownMs >= 2_500 && unwarmed.maxMissingBlocks <= 6 && unwarmed.deadlineMs <= 5_000)
  check(low.cooldownMs >= 750 && low.maxMissingBlocks <= 16 && low.deadlineMs <= 9_000)
  check(stalled.cooldownMs >= 2_500 && stalled.maxMissingBlocks <= 6)
  check(!policy.allowSpeculativeAhead(40_000_000_000L))
  check(!policy.allowSpeculativeAhead(null))
  check(policy.allowSpeculativeAhead(1_500_000_000L))
  val largeLow = policy.plan(30_000_000_000L, 35.0, 6)
  check(largeLow.cooldownMs >= 25_000 && largeLow.maxMissingBlocks <= 2)
  check(policy.plan(0L, 120.0, 10).band == AheadPacing.SizeBand.UNKNOWN)
  println("PASS: 20GB threshold, small/medium/large/unknown size-aware policies")
  println("PASS: missing/stale buffering never unlocks aggressive downloads")
  println("PASS: large-file caps and single-session block/response limits")
  println("small: $small")
  println("large: $large")
}
