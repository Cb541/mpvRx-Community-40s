package app.gyrolet.mpvrx.ui.player
import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
object PlaybackSession {
 class State(val paused:Boolean=false)
 val state=MutableStateFlow(State())
 @Volatile var lastPlaybackSeekAtElapsedMs:Long=-100000L
 val propBoolean=mutableMapOf<String,MutableStateFlow<Boolean>>()
 val propDouble=mutableMapOf<String,MutableStateFlow<Double>>()
 fun isCurrentGeneration(generation:Long):Boolean=true
 @Volatile var grabDelayMs:Long=0L
 fun grabThumbnail(dim:Int):Bitmap? { Thread.sleep(grabDelayMs);return Bitmap() }
 fun grabThumbnailFast(src:String,seconds:Double,dim:Int,hw:Boolean):Bitmap?=null
}
