/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.seekpreview

import android.graphics.Bitmap
import android.util.Log
import `is`.xyz.mpv.MPVLib
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional ABI bridge: the ordinary released mpvlib AAR has no persistent decoder,
 * so reflection preserves compatibility. A custom mpvlib AAR containing the JNI
 * additions from this patch provides seekPreviewOpen/Frame/Close at runtime.
 */
internal object PersistentNativeSeek {
  private val resolved = AtomicBoolean(false)
  private var open: Method? = null
  private var frame: Method? = null
  private var keyframe: Method? = null
  private var interrupt: Method? = null
  private var close: Method? = null
  private val once = AtomicBoolean(false)

  @Synchronized private fun resolve() {
    if (resolved.get()) return
    val cls = MPVLib::class.java
    runCatching {
      open = cls.getMethod("seekPreviewOpen", String::class.java)
      frame = cls.getMethod("seekPreviewFrame", java.lang.Long.TYPE, java.lang.Double.TYPE, Integer.TYPE)
      close = cls.getMethod("seekPreviewClose", java.lang.Long.TYPE)
    }.onFailure {
      if (once.compareAndSet(false, true))
        Log.i("SeekPreviewPerf", "Persistent native decoder not in installed mpvlib; using legacy extraction")
      open = null; frame = null; close = null
    }
    // Optional keyframe-only API introduced by the second native patch.
    // Keep foreground previews working if the user hasn't rebuilt the AAR.
    if (open != null) keyframe = runCatching {
      cls.getMethod("seekPreviewFrameKeyframe", java.lang.Long.TYPE, java.lang.Double.TYPE, Integer.TYPE)
    }.getOrNull()
    if (open != null) interrupt = runCatching {
      cls.getMethod("seekPreviewInterrupt", java.lang.Long.TYPE)
    }.getOrNull()
    resolved.set(true)
  }

  /** True only when the custom AAR exposes independent persistent native sessions. */
  fun isAvailable(): Boolean {
    resolve()
    return open != null && frame != null && close != null
  }

  fun hasKeyframeExtraction(): Boolean { resolve(); return keyframe != null }

  fun create(url: String): Long? {
    resolve()
    val method = open ?: return null
    return runCatching { (method.invoke(MPVLib, url) as Number).toLong().takeIf { it != 0L } }
      .onFailure { Log.w("SeekPreviewPerf", "Native preview session open failed (${it.javaClass.simpleName})") }
      .getOrNull()
  }

  fun extract(handle: Long, second: Double, dimension: Int, keyframesOnly: Boolean = false): Bitmap? {
    if (handle == 0L) return null
    val method = (if (keyframesOnly) keyframe else frame) ?: return null
    return runCatching { method.invoke(MPVLib, handle, second, dimension) as? Bitmap }
      .getOrNull()
  }

  fun interrupt(handle: Long) {
    if (handle != 0L) interrupt?.let { method ->
      runCatching { method.invoke(MPVLib, handle) }
    }
  }

  fun release(handle: Long) {
    if (handle == 0L) return
    close?.let { method -> runCatching { method.invoke(MPVLib, handle) } }
  }
}
