/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.seekpreview

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Sidecar frame decoder for HTTP(S) streams. mpv's separate thumbnail player does not
 * inherit the main playback session's User-Agent/Referer/Authorization/Cookie headers.
 * Android's retriever lets us explicitly use those same headers without touching mpv's
 * active player or writing credentials to a local file.
 *
 * The retriever stays open across requests, rather than reconnecting to a remote file
 * for every thumbnail tile. Access is limited to SeekPreviewStore's one IO worker.
 * This is for seekable, unprotected streams, not DRM or live/non-seekable media.
 */
internal class NetworkSeekFrameRetriever(
  private val source: String,
  private val headers: Map<String, String>,
  private val platformState: AtomicInteger,
  private val sharedBridgeUrl: () -> String? = { null },
) : AutoCloseable {
  private var decoder: MediaMetadataRetriever? = null
  private var size: Pair<Int, Int> = 512 to 288
  private var retryAfterMs: Long = 0
  private var loggedError = false
  private var nativeHardwareSupported: Boolean? = null
  @Volatile private var persistentHandle = 0L
  private var persistentAttempted = false
  private var lastNativeAttemptMs = 0L
  private var quickPathFailed = false
  // Reject a decoder stuck on an earlier keyframe without penalizing normal
  // seeks. Only exact repeats across widely separated foreground requests
  // trigger the existing mpv fallback.
  private var lastNativeFrame: Bitmap? = null
  private var lastNativeFrameMs = -1L
  // A particular file/container can repeatedly consume the native FFmpeg read
  // timeout before returning null, then succeed using the existing mpv path.
  // After a genuinely slow failure, avoid repeating that delay for each scrub.
  private var slowNativeRetryAfterMs = 0L
  // A canceled HTTP read can leave avformat's existing AVIO context at EOF.
  // Reopen from the worker thread on its *next* decode, never concurrently
  // free a context while another thread is extracting a frame.
  @Volatile private var reopenAfterPlaybackSeek = false

  /** Fast preview first: syncing to indexed/key frames avoids a full decode from the
   * previous GOP on every HTTP seek. The timestamp/real playback seek are still exact.
   * Retain one retriever instance per video rather than reconnecting for each frame.
   */
  fun frameAt(timeMs: Long): Bitmap? {
    // Exactly one network worker probes Android's platform decoder. Once it fails,
    // both workers go straight to mpv rather than each spending ~3 seconds on
    // an unsupported container/codec. States: 0 unknown, 1 probing, 2 works,
    // -1 unsupported for this playback generation.
    if (platformState.get() == -1) return null
    val probing = platformState.compareAndSet(0, 1)
    if (!probing && platformState.get() != 2) return null
    val started = SystemClock.elapsedRealtime()
    // Route Android's retriever through the same cancellable admission gate.
    val path = nativeFallbackUrl() ?: return null
    val retriever = open(path) ?: run {
      if (probing) platformState.compareAndSet(1, -1)
      return null
    }
    val us = timeMs.coerceIn(0L, Long.MAX_VALUE / 1000L) * 1000L
    val (width, height) = size
    return try {
      val bitmap = retriever.getScaledFrameAtTime(
        us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height
      )
      Log.d("SeekPreviewPerf", "android-keyframe duration=${SystemClock.elapsedRealtime() - started}ms success=${bitmap != null}")
      if (bitmap == null) platformState.set(-1)
      else if (probing) platformState.compareAndSet(1, 2)
      bitmap
    } catch (error: Exception) {
      Log.d("SeekPreviewPerf", "android-keyframe duration=${SystemClock.elapsedRealtime() - started}ms failed")
      logOnce("Android stream frame request failed", error)
      // The Android decoder may not support this file, while native mpv does.
      // Skip repeated platform decode failures for this playback session.
      platformState.set(-1)
      // A transport error or expired signed URL can invalidate the extractor. Reopen later.
      reset()
      retryAfterMs = SystemClock.elapsedRealtime() + 2_000L
      null
    }
  }

  /** Separate persistent FFmpeg worker; no libmpv global lock is needed here. */
  fun persistentFrameAt(
    timeMs: Long, keyframesOnly: Boolean = false, dimension: Int = 512,
  ): Bitmap? {
    if (SystemClock.elapsedRealtime() < slowNativeRetryAfterMs) return null
    val path = nativeFallbackUrl() ?: return null
    if (reopenAfterPlaybackSeek) {
      reopenAfterPlaybackSeek = false
      PersistentNativeSeek.release(persistentHandle)
      persistentHandle = 0L
      persistentAttempted = false
      lastNativeAttemptMs = 0L
      quickPathFailed = false
      Log.d("SeekPreviewPerf", "thumbnail native context reopened after playback seek")
    }
    if (!persistentAttempted && SystemClock.elapsedRealtime() >= lastNativeAttemptMs + 4_000L) {
      persistentAttempted = true
      lastNativeAttemptMs = SystemClock.elapsedRealtime()
      val openStartedAt = SystemClock.elapsedRealtime()
      persistentHandle = PersistentNativeSeek.create(path) ?: 0L
      Log.d("SeekPreviewPerf", "persistent-native-open duration=${SystemClock.elapsedRealtime()-openStartedAt}ms ready=${persistentHandle != 0L}")
      if (persistentHandle == 0L) persistentAttempted = false
    }
    if (persistentHandle == 0L) return null
    val started = SystemClock.elapsedRealtime()
    // The decoder input is unchanged; only the RGBA conversion/scale target
    // shrinks. Foreground is size-aware and paused neighbors use 416px.
    val result = PersistentNativeSeek.extract(persistentHandle, timeMs / 1000.0,
      dimension, keyframesOnly)
    val elapsed = SystemClock.elapsedRealtime() - started
    if (!keyframesOnly && result != null && lastNativeFrameMs >= 0 &&
        kotlin.math.abs(timeMs - lastNativeFrameMs) >= 24_000L &&
        lastNativeFrame?.let { it.width == result.width && it.height == result.height && it.sameAs(result) } == true) {
      Log.w("SeekPreviewPerf", "foreground-identical-native-frame targetMs=$timeMs previousMs=$lastNativeFrameMs; using mpv fallback")
      // Null means the foreground worker can use its normal independent mpv
      // extractor; never cache the suspect frame at a new timestamp.
      return null
    }
    if (!keyframesOnly && result != null) {
      lastNativeFrame = result
      lastNativeFrameMs = timeMs
    }
    Log.d("SeekPreviewPerf", "persistent-native quick=$keyframesOnly duration=${elapsed}ms success=${result != null}")
    if (result == null && !keyframesOnly && elapsed >= 3_000L) {
      slowNativeRetryAfterMs = SystemClock.elapsedRealtime() + 30_000L
      Log.d("SeekPreviewPerf", "foreground-native-slow-failure: use mpv fallback for 30s")
    }
    if (result == null && !keyframesOnly) {
      // A failed native seek is isolated to this worker; use legacy extraction.
      PersistentNativeSeek.release(persistentHandle)
      persistentHandle = 0L
      persistentAttempted = false
      lastNativeAttemptMs = SystemClock.elapsedRealtime()
    }
    return result
  }

  /** Try the low-cost keyframe path once per video. Some HEVC remuxes never
   * return an image with the old AVDISCARD_NONKEY native implementation. Repeating
   * that failure filled no cache slots and wasted 1-3 seconds per slot. Recover by
   * using the verified full decoder for this playback session instead.
   * Do not immediately fall back if playback is stalled or a user seek is active.
   */
  fun warmFrameAt(timeMs: Long, mayContinue: () -> Boolean): Bitmap? {
    if (!mayContinue()) return null
    if (!quickPathFailed && PersistentNativeSeek.hasKeyframeExtraction()) {
      val quick = persistentFrameAt(timeMs, keyframesOnly = true, dimension = 416)
      if (quick != null) return quick
      quickPathFailed = true
      Log.w("SeekPreviewPerf", "keyframe quick path failed; switching background previews to reliable full decode")
    }
    return if (mayContinue()) persistentFrameAt(timeMs, keyframesOnly = false, dimension = 416) else null
  }

  /** Nonblocking native read interruption when the main video buffer runs low. */
  fun interruptPersistent() {
    PersistentNativeSeek.interrupt(persistentHandle)
  }

  /** Separate from ordinary scrub interruption, which must not reopen the
   * native decoder for every finger movement. */
  fun interruptForPlaybackSeek() {
    reopenAfterPlaybackSeek = true
    PersistentNativeSeek.interrupt(persistentHandle)
  }

  /** The prebuilt mpvlib fallback does use shared native state; caller serializes it. */
  fun nativeFrameAt(timeMs: Long, getFrame: (String, Double, Boolean) -> Bitmap?): Bitmap? {
    val started = SystemClock.elapsedRealtime()
    val path = nativeFallbackUrl() ?: return null
    val bitmap = legacyNativeFrame(path, timeMs / 1000.0, getFrame)
    Log.d("SeekPreviewPerf", "mpv-fallback duration=${SystemClock.elapsedRealtime() - started}ms success=${bitmap != null}")
    return bitmap
  }

  private fun legacyNativeFrame(
    path: String, seconds: Double, getFrame: (String, Double, Boolean) -> Bitmap?,
  ): Bitmap? {
    if (nativeHardwareSupported == false) return getFrame(path, seconds, false)
    val hardware = getFrame(path, seconds, true)
    if (hardware != null) {
      nativeHardwareSupported = true
      return hardware
    }
    nativeHardwareSupported = false
    return getFrame(path, seconds, false)
  }

  private fun open(path: String): MediaMetadataRetriever? {
    decoder?.let { return it }
    if (SystemClock.elapsedRealtime() < retryAfterMs) return null
    val instance = MediaMetadataRetriever()
    return try {
      instance.setDataSource(path, emptyMap<String, String>())
      val videoWidth = instance.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 512
      val videoHeight = instance.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 288
      val rotation = instance.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
      val w = if (rotation % 180 != 0) videoHeight else videoWidth
      val h = if (rotation % 180 != 0) videoWidth else videoHeight
      if (w > 0 && h > 0) {
        val factor = 512.0 / max(w, h)
        size = (w * factor).roundToInt().coerceAtLeast(1) to (h * factor).roundToInt().coerceAtLeast(1)
      }
      decoder = instance
      instance
    } catch (error: Exception) {
      logOnce("Android stream decoder could not open", error)
      runCatching { instance.release() }
      retryAfterMs = SystemClock.elapsedRealtime() + 5_000L
      null
    }
  }

  /** mpv/FFmpeg fallback for formats that the Android platform retriever cannot decode. */
  fun nativeFallbackUrl(): String? = sharedBridgeUrl()
  // Never bypass a suspended/authenticated bridge with a direct source request.

  private fun logOnce(message: String, error: Exception) {
    if (!loggedError) {
      // Deliberately do not log the signed stream URL or any request headers.
      Log.w("SeekPreviewNetwork", "$message (${error.javaClass.simpleName})")
      loggedError = true
    }
  }

  private fun reset() {
    decoder?.let { runCatching { it.release() } }
    decoder = null
  }

  override fun close() {
    lastNativeFrame = null
    lastNativeFrameMs = -1L
    reset()
    PersistentNativeSeek.release(persistentHandle)
    persistentHandle = 0L
  }
}
