package android.media
import android.graphics.Bitmap
class MediaMetadataRetriever {
  fun setDataSource(path: String, headers: Map<String,String>) {}
  fun extractMetadata(key: Int): String? = null
  fun getScaledFrameAtTime(us: Long, option: Int, width: Int, height: Int): Bitmap? = Bitmap(width,height)
  fun release() {}
  companion object {
    const val OPTION_CLOSEST_SYNC = 2
    const val METADATA_KEY_VIDEO_WIDTH = 18
    const val METADATA_KEY_VIDEO_HEIGHT = 19
    const val METADATA_KEY_VIDEO_ROTATION = 24
  }
}
