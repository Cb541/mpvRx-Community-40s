package app.gyrolet.mpvrx.domain.seekpreview
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
object SeekPreviewSessionDisk {
 fun attach(context:Context,generation:Long,source:String):File = File(context.cacheDir,"jpg-$generation").also { it.mkdirs() }
 fun isActive(generation:Long): Boolean = true
}
object SeekStripLayout { fun pictureMs(time:Double,step:Long):Long=((time/step).toLong()*step) }
class NetworkSeekHttpBridge {
 val id:Int=created.incrementAndGet()
 var suspended=false
 var closed=false
 val previewUrl:String get()=if(!suspended && !closed) "http://127.0.0.1/bridge-$id" else "http://127.0.0.1/invalid-$id"
 fun setLargeForegroundFastMode(enabled:Boolean){}
 fun cancelActivePreviewRequests() { suspended=true; interrupted.incrementAndGet() }
 fun close(){ closed=true }
 companion object {
 val created=AtomicInteger(0)
 val interrupted=AtomicInteger(0)
 fun open(source:String,headers:Map<String,String>,cache:File)=NetworkSeekHttpBridge()
 }
}
class NetworkSeekFrameRetriever(
 private val source:String, private val headers:Map<String,String>, private val disabledPlatformProbe:AtomicInteger,
 private val bridgeUrl:()->String?
) {
 init { opened.incrementAndGet() }
 private val canceled=AtomicBoolean(false)
 fun persistentFrameAt(timeMs:Long, keyframesOnly:Boolean=false, dimension:Int=512):Bitmap? {
   val path=bridgeUrl()?:return null
   started.incrementAndGet()
   for(i in 0..20){if(canceled.get())return null;Thread.sleep(10)}
   if(path.contains("invalid"))return null
   extracted.incrementAndGet()
   return Bitmap()
 }
 fun nativeFrameAt(timeMs:Long,f:(String,Double,Boolean)->Bitmap?):Bitmap?=null
 fun interruptPersistent(){ canceled.set(true) }
 fun interruptForPlaybackSeek(){ canceled.set(true) }
 fun close() { canceled.set(true) }
 companion object { val extracted=AtomicInteger(0); val started=AtomicInteger(0); val opened=AtomicInteger(0) }
}
