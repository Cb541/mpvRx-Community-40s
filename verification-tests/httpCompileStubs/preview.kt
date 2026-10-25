package app.gyrolet.mpvrx.domain.seekpreview
import java.io.File
internal class NetworkSeekHttpBridge:AutoCloseable {
  val previewUrl:String="http://127.0.0.1/test"
  val sharedByteCache:SeekByteBlockCache get()=error("fake")
  fun setLargeForegroundFastMode(enabled:Boolean){}
  fun cancelActivePreviewRequests(){}
  fun resumePreviewRequests(){}
  override fun close(){}
  companion object {fun open(url:String,h:Map<String,String>,file:File)=NetworkSeekHttpBridge()}
}
