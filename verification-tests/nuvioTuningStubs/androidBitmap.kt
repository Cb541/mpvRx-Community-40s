package android.graphics
class Bitmap(val width: Int = 416, val height: Int = 234) {
  fun sameAs(other: Bitmap): Boolean = width == other.width && height == other.height
}
