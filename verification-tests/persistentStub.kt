package app.gyrolet.mpvrx.domain.seekpreview
import android.graphics.Bitmap
object PersistentNativeSeek {
 fun create(url:String):Long?=1L
 fun extract(handle:Long,second:Double,size:Int):Bitmap?=null
 fun release(handle:Long){}
 fun interrupt(handle:Long){}
}
