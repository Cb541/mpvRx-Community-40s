package app.gyrolet.mpvrx.domain.seekpreview
import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicInteger
internal object SharedPlaybackTransportRegistry {
 @Volatile var bytes:Long?=40_000_000_000L
 val finishCalls=AtomicInteger(0)
 val opens=AtomicInteger(0)
 fun cacheOnlyPreviewUrl(generation:Long,source:String):String?="http://127.0.0.1/cached"
 fun cachedByteRevision(generation:Long,source:String):Long=1L
 fun previewUrl(generation:Long,source:String):String?=null
 fun mediaSizeBytes(generation:Long,source:String):Long?=bytes
 fun beginBudgetedAhead(generation:Long,source:String,plan:AheadPacing.Plan,canFetch:()->Boolean):String? {
   if(!canFetch()) return null
   opens.incrementAndGet()
   return "http://127.0.0.1/ahead"
 }
 fun finishBudgetedAhead(generation:Long) { finishCalls.incrementAndGet() }
 fun setLargeForegroundFastMode(generation:Long,source:String,enabled:Boolean){}
 fun suspendOnlyPreviews(generation:Long){}
 fun resumeOnlyPreviews(generation:Long){}
}
internal object PersistentNativeSeek {
 val interrupts=AtomicInteger(0)
 fun create(url:String):Long?=if(url.endsWith("/ahead")) 9L else null
 fun extract(handle:Long,second:Double,size:Int):Bitmap?=Bitmap()
 fun release(handle:Long){}
 fun interrupt(handle:Long){ interrupts.incrementAndGet() }
}
