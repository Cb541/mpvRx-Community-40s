/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.seekpreview

import android.content.Context
import android.util.Log
import app.gyrolet.mpvrx.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

/**
 * Opt-in integration for playback through the playback-fed shared media cache.
 * This owns the proxy for the entire player generation, NOT the preview UI.
 * No active transport is replaced until its playback is being replaced/stopped.
 */
internal object SharedPlaybackTransportRegistry {
  // Only the side-by-side debug test app can route media over this proxy.
  // Release/preview flavors retain the original playback URL, regardless of configuration.
  private val experimentalEnabled get() = BuildConfig.DEBUG && BuildConfig.SHARED_PLAYBACK_EXPERIMENT
  private data class Entry(val generation: Long, val source: String,
    val proxy: SharedPlaybackHttpBridge)
  private var current: Entry? = null
  private var mismatchLoggedForGeneration = -1L

  fun playbackUrlOrOriginal(context: Context?, generation: Long, source: String,
    headers: Map<String, String>): String = synchronized(this) {
    current?.proxy?.close()
    current = null
    val url = source.toHttpUrlOrNull()
    val host = url?.host?.lowercase().orEmpty()
    val localOrPrivate = host == "localhost" || host == "::1" || host == "0.0.0.0" ||
      host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") ||
      host.startsWith("169.254.") || host.endsWith(".local") ||
      host.startsWith("172.") && host.substringAfter("172.").substringBefore('.').toIntOrNull()?.let { it in 16..31 } == true
    // Website/watch URLs require yt-dlp, which MUST see the real site address.
    // Never replace such a URL with 127.0.0.1 before libmpv's extractor runs.
    // Explicit video files, manifests, and signed CDN streams retain the
    // established shared-media transport and its playback cache.
    val needsWebExtractor = ExtractorPageRoutingPolicy.requiresOriginalUrl(
      host, url?.encodedPath.orEmpty())
    // Exclude preexisting loopback file/HLS gateways and non-http sources.
    if (!experimentalEnabled || context == null || url == null || localOrPrivate || needsWebExtractor ||
      source.endsWith(".m3u8", true) || source.contains(".m3u8?", true)) {
      if (experimentalEnabled && url != null && (localOrPrivate || needsWebExtractor)) {
        Log.d("SharedMediaTransport", if (needsWebExtractor)
          "passing web extractor URL directly to libmpv yt-dlp hook" else
          "not intercepting local media proxy")
      }
      return@synchronized source
    }
    val candidate = runCatching {
      SharedPlaybackHttpBridge.open(source, headers,
        File(context.cacheDir, "mpvrx-shared-transport"))
    }.onFailure { Log.w("SharedMediaTransport", "Cannot open shared transport (${it.javaClass.simpleName})") }
      .getOrNull() ?: return@synchronized source
    mismatchLoggedForGeneration = -1L
    current = Entry(generation, source, candidate)
    Log.d("SharedMediaTransport", "experimental shared playback route active")
    candidate.playbackUrl
  }

  fun previewUrl(generation: Long, source: String): String? = synchronized(this) {
    val entry = current?.takeIf { it.generation == generation }
    if (entry != null && entry.source != source && mismatchLoggedForGeneration != generation) {
      mismatchLoggedForGeneration = generation
      Log.w("SharedMediaTransport", "preview source differs from playback URL; shared-cache preview unavailable")
    }
    entry?.takeIf { it.source == source }?.proxy?.previewUrl
  }

  fun setLargeForegroundFastMode(generation: Long, source: String, enabled: Boolean) = synchronized(this) {
    current?.takeIf { it.generation == generation && it.source == source }
      ?.proxy?.setLargeForegroundFastMode(enabled)
  }

  /** The only URL allowed for playback-time speculation; it has zero upstream I/O. */
  fun cacheOnlyPreviewUrl(generation: Long, source: String): String? = synchronized(this) {
    current?.takeIf { it.generation == generation && it.source == source }
      ?.proxy?.cacheOnlyPreviewUrl
  }

  /** A monotonically increasing count of cached blocks, not a buffer-duration estimate. */
  fun cachedByteRevision(generation: Long, source: String): Long = synchronized(this) {
    current?.takeIf { it.generation == generation && it.source == source }
      ?.proxy?.byteCache?.revision() ?: -1L
  }

  /** Full remote object size, only after a verified Content-Range was seen. */
  fun mediaSizeBytes(generation: Long, source: String): Long? = synchronized(this) {
    current?.takeIf { it.generation == generation && it.source == source }
      ?.proxy?.byteCache?.length()
  }

  fun beginBudgetedAhead(generation: Long, source: String, plan: AheadPacing.Plan,
    canFetch: () -> Boolean): String? = synchronized(this) {
    current?.takeIf { it.generation == generation && it.source == source }
      ?.proxy?.beginBudgetedAhead(plan, canFetch)
  }

  fun finishBudgetedAhead(generation: Long) = synchronized(this) {
    current?.takeIf { it.generation == generation }?.proxy?.finishBudgetedAhead()
  }

  fun suspendOnlyPreviews(generation: Long) = synchronized(this) {
    current?.takeIf { it.generation == generation }?.proxy?.finishBudgetedAhead()
    current?.takeIf { it.generation == generation }?.proxy?.cancelOnlyPreviewReads()
  }

  fun resumeOnlyPreviews(generation: Long) = synchronized(this) {
    current?.takeIf { it.generation == generation }?.proxy?.resumeOnlyPreviewReads()
  }

  fun retireAll() = synchronized(this) {
    current?.proxy?.close()
    current = null
  }
}
