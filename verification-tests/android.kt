package android.content
import java.io.File
open class Context(val cacheDir: File = File(System.getProperty("java.io.tmpdir"),"seek-test")) {
  val applicationContext: Context get() = this
}
