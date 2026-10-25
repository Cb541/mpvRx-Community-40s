import app.gyrolet.mpvrx.domain.seekpreview.*
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
suspend fun poll(limitMs:Long=2500, condition:()->Boolean) {
 val start=SystemClock.elapsedRealtime()
 while(!condition() && SystemClock.elapsedRealtime()-start<limitMs)delay(50)
 check(condition()) { "Timed out waiting for test condition" }
}
fun main()=runBlocking {
 val testCache=File("/tmp/mpvrx-preview-harness-${System.nanoTime()}")
 val store=SeekPreviewStore(Context(testCache),"https://example.invalid/movie.mkv",5L)
 try{
   store.request(listOf(120_000L))
   poll { NetworkSeekFrameRetriever.extracted.get()>=1 }
   check(NetworkSeekHttpBridge.created.get()==1)
   // A real playback seek triggers cancellation; the next request must reopen
   // the bridge because cancelActivePreviewRequests permanently suspends it.
   PlaybackSession.lastPlaybackSeekAtElapsedMs = SystemClock.elapsedRealtime()
   poll { NetworkSeekHttpBridge.interrupted.get()>=1 }
   delay(2050L)
   store.request(listOf(480_000L))
   poll { NetworkSeekFrameRetriever.extracted.get()>=2 }
   check(NetworkSeekHttpBridge.created.get()>=2) { "Reused canceled proxy" }
   println("PASS: old proxy retired and new one generated frame")
   // A rapidly replaced target must interrupt the obsolete decode rather than
   // wait for all of its network reads to finish.
   store.request(listOf(820_000L))
   delay(35)
   store.request(listOf(900_000L))
   poll { NetworkSeekFrameRetriever.extracted.get()>=3 }
   println("PASS: latest-request-wins")
 }finally{store.close();delay(250);testCache.deleteRecursively()}
}
