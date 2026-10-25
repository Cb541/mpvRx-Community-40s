import app.gyrolet.mpvrx.domain.seekpreview.*
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File

private suspend fun waitUntil(maxMs: Int = 4_000, predicate: () -> Boolean) {
  repeat(maxMs / 10) {
    if (predicate()) return
    delay(10)
  }
  check(predicate()) { "Timed out waiting for foreground progress" }
}

fun main() = runBlocking {
  check(!AheadPacing.allowSpeculativeAhead(34_000_000_000L))
  check(AheadPacing.allowSpeculativeAhead(5_000_000_000L))
  check(!AheadPacing.allowSpeculativeAhead(null))
  val stages = SeekPreviewStore.PreviewStep.entries
  check(stages.first().percent == 0)
  check(stages.first { it.name == "DECODING" }.percent < 100)
  check(stages.first { it.name == "COMPLETE" }.percent == 100)

  val temp = File("/tmp/mpvrx-progress-${System.nanoTime()}").also { check(it.mkdirs()) }
  val store = SeekPreviewStore(Context(temp), "https://example.invalid/huge.mkv", 7757L)
  try {
    store.request(listOf(12_000L))
    check(store.previewProgress.value?.targetMs == 12_000L)
    waitUntil { NetworkSeekFrameRetriever.started.get() > 0 }
    val active = store.previewProgress.value
    check(active?.step == SeekPreviewStore.PreviewStep.DECODING) {
      "Expected real decoder milestone; got $active"
    }
    val oldStart = requireNotNull(active).startedMs
    store.request(listOf(24_000L)) // old job must NOT mark current as complete
    check(store.previewProgress.value?.targetMs == 24_000L)
    waitUntil { store.peekTimed(12_000L, 0L)?.timeMs == 12_000L }
    check(store.previewProgress.value?.targetMs == 24_000L)
    waitUntil { store.peekTimed(24_000L, 0L)?.timeMs == 24_000L }
    check(store.previewProgress.value?.step == SeekPreviewStore.PreviewStep.COMPLETE)
    check(store.previewProgress.value?.startedMs != oldStart)
    store.request(emptyList())
    check(store.previewProgress.value == null)
    println("PASS: genuine progress milestones, stale-frame protection, 20GB+ ahead disabled")
  } finally {
    store.close(); delay(25); temp.deleteRecursively()
  }
}
