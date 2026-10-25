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
 fun grabThumbnail(dim:Int):Bitmap?=Bitmap()
 fun grabThumbnailFast(src:String,seconds:Double,dim:Int,hw:Boolean):Bitmap?=null
}
