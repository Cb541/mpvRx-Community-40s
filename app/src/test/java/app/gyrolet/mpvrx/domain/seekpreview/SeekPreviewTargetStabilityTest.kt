// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.domain.seekpreview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SeekPreviewTargetStabilityTest {
  @Test fun movingSlightlyDuringDecodeDoesNotRequeue() {
    assertTrue(SeekPreviewTargetStability.shouldHoldActiveTarget(600_000, 609_000, 12_000_000, true))
    assertTrue(SeekPreviewTargetStability.shouldHoldActiveTarget(600_000, 605_000, 12_000_000, true))
  }
  @Test fun deliberateLargeSeekRequeuesImmediately() {
    assertFalse(SeekPreviewTargetStability.shouldHoldActiveTarget(600_000, 640_000, 12_000_000, true))
  }
  @Test fun afterDecodeCompletesReconcileToPreciseSelectedTarget() {
    assertFalse(SeekPreviewTargetStability.shouldHoldActiveTarget(600_000, 605_000, 12_000_000, false))
  }
  @Test fun toleranceScalesWithDurationButIsBounded() {
    assertEquals(4_000L, SeekPreviewTargetStability.toleranceMs(1_200_000))
    assertEquals(12_000L, SeekPreviewTargetStability.toleranceMs(10_800_000))
    assertEquals(18_000L, SeekPreviewTargetStability.toleranceMs(43_200_000))
  }
}
