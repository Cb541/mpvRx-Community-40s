import app.gyrolet.mpvrx.domain.seekpreview.*
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
fun main() = runBlocking {
 val root=File("/tmp/mpvrx-stage3-4-large-priority-${System.nanoTime()}")
 PlaybackSession.propDouble["time-pos"]=MutableStateFlow(105.0)
 PlaybackSession.propDouble["demuxer-cache-duration"]=MutableStateFlow(135.0)
 val large=SeekPreviewStore(Context(root),"https://example.invalid/large.mkv",901L)
 val ticking=launch { while(true) { delay(350); PlaybackSession.propDouble["time-pos"]!!.value += 0.35 } }
 try {
   large.warmAround(105f,1200f)
   // A manual uncached preview must synchronously close any speculative budget.
   large.request(listOf(510_000L))
   check(SharedPlaybackTransportRegistry.finishCalls.get()==1) { "large preview didn't revoke speculative network" }
   large.request(emptyList())
   delay(2_100L)
   check(SharedPlaybackTransportRegistry.opens.get()==0) { "ahead job restarted during large manual quiet window" }
   println("PASS large manual preview revokes ahead HTTP budget immediately")
   println("PASS ahead remote jobs stay suspended after manual preview closes")
 } finally {ticking.cancel();large.close()}
 SharedPlaybackTransportRegistry.bytes=2_000_000_000L
 val small=SeekPreviewStore(Context(root),"https://example.invalid/small.mkv",902L)
 try {
   val before=SharedPlaybackTransportRegistry.finishCalls.get()
   small.request(listOf(240_000L))
   small.request(emptyList())
   check(SharedPlaybackTransportRegistry.finishCalls.get()==before) { "small file unexpectedly preempted" }
   println("PASS smaller media does not enable large-file preemption")
 } finally { small.close();root.deleteRecursively() }
}
