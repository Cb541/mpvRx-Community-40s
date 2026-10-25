package app.gyrolet.mpvrx.domain.seekpreview
import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicInteger
internal object SharedPlaybackTransportRegistry {
 val opens=AtomicInteger(0)
 val closes=AtomicInteger(0)
 fun setLargeForegroundFastMode(generation:Long,source:String,enabled:Boolean){}
 fun cacheOnlyPreviewUrl(generation:Long,source:String):String?="http://127.0.0.1/cached"
 fun cachedByteRevision(generation:Long,source:String):Long=1
 fun previewUrl(generation:Long,source:String):String?=null
 @Volatile var stubMediaBytes = 12_000_000_000L
 fun mediaSizeBytes(generation:Long,source:String):Long?=stubMediaBytes
 fun beginBudgetedAhead(generation:Long,source:String,plan:AheadPacing.Plan,canFetch:()->Boolean):String?=if(canFetch()){ opens.incrementAndGet(); "http://127.0.0.1/ahead" }else null
 fun finishBudgetedAhead(generation:Long){closes.incrementAndGet()}
 fun suspendOnlyPreviews(generation:Long){}
 fun resumeOnlyPreviews(generation:Long){}
}
internal object PersistentNativeSeek {
 val extracts=AtomicInteger(0)
 @Volatile var repeatedTargetSecond = -1L
 @Volatile var originalTargetSecond = -1L
 val interruptions=AtomicInteger(0)
 fun create(url:String):Long?=if(url.contains("/ahead"))99L else null
 fun extract(handle:Long,second:Double,size:Int):Bitmap? {
   extracts.incrementAndGet()
   val t = second.toLong()
   return if (t == repeatedTargetSecond || t == originalTargetSecond) Bitmap(999999)
     else Bitmap(t.toInt())
 }
 fun release(handle:Long){}
 fun interrupt(handle:Long){interruptions.incrementAndGet()}
}
