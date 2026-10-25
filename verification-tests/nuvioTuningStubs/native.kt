package app.gyrolet.mpvrx.domain.seekpreview
import android.graphics.Bitmap
object PersistentNativeSeek {
  var lastDimension = -1
  fun create(path:String):Long?=55L
  fun extract(handle:Long, second:Double, dimension:Int, keyframesOnly:Boolean=false):Bitmap? {
    lastDimension=dimension
    return Bitmap(dimension,dimension*9/16)
  }
  fun hasKeyframeExtraction():Boolean=true
  fun release(handle:Long){}
  fun interrupt(handle:Long){}
}
