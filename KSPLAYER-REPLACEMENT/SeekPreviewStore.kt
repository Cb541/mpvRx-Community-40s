/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.domain.seekpreview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Playback-first seek previews, replacing speculative whole-movie network decoding.
 *
 *  - ZERO remote background FFmpeg workers. 12s frames come from the actual
 *    decoded video using mpv's screenshot-raw command; no extra network reads.
 *  - ONE short-lived remote decoder, created only when the user asks to see a
 *    thumbnail that isn't already available. Channel.CONFLATED drops older requests.
 *  - The playback URL and mpv's socket are never redirected through the preview proxy.
 *  - Session JPEGs are retained while the movie is active and retired on stop/change.
 *
 * KSPlayer-inspired isolation policy, NOT a port of proprietary KSPlayer preview code.
 * This deliberately does not generate remote ahead-of-playback 12s thumbnails.
 */
internal class SeekPreviewStore(
  context: Context,
  private val source: String,
  private val generation: Long,
  private val headers: Map<String, String> = emptyMap(),
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val networkSource = source.startsWith("https://", true) || source.startsWith("http://", true)
  private val closed = AtomicBoolean(false)
  private val lock = Any()
  private val directory = SeekPreviewSessionDisk.attach(context.applicationContext, generation, source)
  private val appCache = context.applicationContext.cacheDir
  private val memory = object : LruCache<Long, Bitmap>(32 * 1024 * 1024) {
    override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
  }
  private val diskIndex = ConcurrentHashMap.newKeySet<Long>()
  private val memoryTimes = ConcurrentHashMap.newKeySet<Long>()
  private val failureAt = ConcurrentHashMap<Long, Long>()
  private val _tick = MutableStateFlow(0L)
  val tick = _tick.asStateFlow()

  // There can never be more than one speculative HTTP connection, because
  // there is no speculative HTTP work at all. Foreground is strictly user-driven.
  private val foregroundRequests = Channel<Long>(Channel.CONFLATED)
  private var latestRequest: Long? = null
  private var lastCaptureSlot = -1L
  private val disabledPlatformProbe = AtomicInteger(-1) // FFmpeg first, no multi-second Android probe.
  @Volatile private var activeDecoder: NetworkSeekFrameRetriever? = null
  @Volatile private var activeBridge: NetworkSeekHttpBridge? = null
  private var lastSeekSignal = PlaybackSession.lastPlaybackSeekAtElapsedMs
  private val seekEpoch = AtomicLong(0L)

  init {
    directory.listFiles()?.forEach { f ->
      if (f.isFile && f.extension == "jpg") f.nameWithoutExtension.toLongOrNull()?.let(diskIndex::add)
    }
    Log.i("SeekPreviewPerf", "playback-first thumbnails: background-network-workers=0 foreground-workers=1 capture-step=12s")
    scope.launch { foregroundLoop() }
    // Separate lightweight playback-seek observer: interrupt the one pending
    // thumbnail HTTP request BEFORE playback needs a fresh unbuffered range.
    scope.launch {
      while (active()) {
        delay(100)
        val signal = PlaybackSession.lastPlaybackSeekAtElapsedMs
        if (signal > lastSeekSignal) {
          lastSeekSignal = signal
          seekEpoch.incrementAndGet()
          activeDecoder?.interruptPersistent()
          activeBridge?.cancelActivePreviewRequests()
          Log.d("SeekPreviewPerf", "playback-seek: thumbnail connection interrupted")
        }
      }
    }
  }

  private fun active(): Boolean = !closed.get() &&
    PlaybackSession.isCurrentGeneration(generation) && SeekPreviewSessionDisk.isActive(generation)

  /** Called by the existing UI; no network is used here, on purpose. */
  fun warmAround(positionSeconds: Float, durationSeconds: Float) = Unit

  /** Capture only the frame the video player already decoded. No remote seeks. */
  fun capturePlaybackFrame(positionSeconds: Float) {
    if (!active() || !positionSeconds.isFinite() || positionSeconds < 0f ||
      PlaybackSession.state.value.paused ||
      PlaybackSession.propBoolean["seeking"]?.value == true ||
      PlaybackSession.propBoolean["paused-for-cache"]?.value == true) return
    val actual = PlaybackSession.propDouble["time-pos"]?.value?.takeIf { it.isFinite() }
      ?: positionSeconds.toDouble()
    if (abs(actual - positionSeconds) > 12.0) return // no frames from an old seek position
    val slot = SeekStripLayout.pictureMs(actual * 1000.0, 12_000L).coerceAtLeast(0L)
    synchronized(lock) {
      if (slot == lastCaptureSlot || slot in diskIndex || memory.get(slot) != null) return
      lastCaptureSlot = slot
    }
    scope.launch {
      if (!active() || PlaybackSession.propBoolean["seeking"]?.value == true ||
        PlaybackSession.propBoolean["paused-for-cache"]?.value == true ||
        PlaybackSession.state.value.paused) return@launch
      val start = SystemClock.elapsedRealtime()
      // screenshot-raw is from current playback, never a second network decoder.
      val frame = runCatching { PlaybackSession.grabThumbnail(512) }.getOrNull()
      if (frame == null || !active()) {
        synchronized(lock) { if (lastCaptureSlot == slot) lastCaptureSlot = -1L }
        return@launch
      }
      publish(slot, frame)
      persist(slot, frame)
      Log.d("SeekPreviewPerf", "playback-capture slotMs=$slot duration=${SystemClock.elapsedRealtime()-start}ms")
    }
  }

  /** Latest user thumbnail request only; null/empty explicitly releases the decoder. */
  fun request(timesMs: List<Long>) {
    if (!active()) return
    val next = timesMs.firstOrNull()?.takeIf { it >= 0 }
    synchronized(lock) {
      if (next == latestRequest) return
      latestRequest = next
    }
    foregroundRequests.trySend(next ?: -1L)
  }

  private fun isStillWanted(slot: Long): Boolean = synchronized(lock) { latestRequest == slot }

  private fun playbackNeedsPriority(): Boolean {
    if (!active()) return true
    if (PlaybackSession.propBoolean["seeking"]?.value == true ||
      PlaybackSession.propBoolean["paused-for-cache"]?.value == true) return true
    // A recently committed seek can still be opening its first byte range.
    val seekAge = SystemClock.elapsedRealtime() - PlaybackSession.lastPlaybackSeekAtElapsedMs
    return seekAge in 0L..2_000L
  }

  /** The dedicated worker never loops over an entire movie. Its decoder and
   * proxy close after 8 seconds of no user requests, releasing HTTP sockets.
   */
  private suspend fun foregroundLoop() {
    var decoder: NetworkSeekFrameRetriever? = null
    var bridge: NetworkSeekHttpBridge? = null
    fun releaseSession() {
      runCatching { decoder?.close() }
      runCatching { bridge?.close() }
      decoder = null
      bridge = null
      activeDecoder = null
      activeBridge = null
    }
    try {
      while (scope.isActive && active()) {
        val slot = withTimeoutOrNull(8_000L) { foregroundRequests.receive() }
        if (slot == null || slot < 0) {
          releaseSession()
          continue
        }
        if (!isStillWanted(slot)) continue
        val cached = peekNearby(slot)
        if (cached != null) {
          _tick.value += 1L
          continue
        }
        // Playback always wins. A stalled video never has to wait behind a
        // newly started preview network range request.
        while (playbackNeedsPriority() && active() && isStillWanted(slot)) {
          // Wait without opening a single preview connection. This remains
          // pending through long network stalls and wakes on real playback recovery.
          delay(250)
        }
        if (!active() || !isStillWanted(slot)) continue

        val start = SystemClock.elapsedRealtime()
        val startedEpoch = seekEpoch.get()
        val onDisk = diskIndex.asSequence().filter { abs(it-slot) <= 7_500L }
          .minByOrNull { abs(it-slot) }
        val diskFrame = onDisk?.let { BitmapFactory.decodeFile(File(directory, "$it.jpg").path) }
        if (diskFrame != null) {
          publish(slot, diskFrame)
          persist(slot, diskFrame)
          continue
        }
        if (onDisk != null) diskIndex.remove(onDisk)

        if (networkSource && bridge == null) {
          bridge = runCatching { NetworkSeekHttpBridge.open(source, headers, appCache) }.getOrNull()
          activeBridge = bridge
        }
        val previewUrl: () -> String? = { if (playbackNeedsPriority()) null else bridge?.previewUrl }
        if (decoder == null && (!networkSource || bridge != null)) {
          decoder = NetworkSeekFrameRetriever(source, headers, disabledPlatformProbe, previewUrl)
          activeDecoder = decoder
        }
        // Local files use the existing native extractor. Network video uses
        // exactly one FFmpeg session against the authenticated preview proxy.
        val frame = if (networkSource) {
          decoder?.persistentFrameAt(slot)
            ?: if (!playbackNeedsPriority() && isStillWanted(slot)) decoder?.nativeFrameAt(slot) { path, second, hw ->
              PlaybackSession.grabThumbnailFast(path, second, 512, hw)
            } else null
        } else {
          PlaybackSession.grabThumbnailFast(source, slot / 1000.0, 512, true)
        }
        if (!active() || !isStillWanted(slot) || seekEpoch.get() != startedEpoch) {
          if (seekEpoch.get() != startedEpoch) releaseSession()
          continue
        }
        if (frame != null) {
          publish(slot, frame) // the UI updates BEFORE JPEG compression
          persist(slot, frame)
          failureAt.remove(slot)
        } else {
          failureAt[slot] = SystemClock.elapsedRealtime()
        }
        Log.d("SeekPreviewPerf", "on-demand-only slotMs=$slot duration=${SystemClock.elapsedRealtime()-start}ms success=${frame!=null}")
        // If a playback seek happened while decoding, retire this connection.
        if (playbackNeedsPriority()) releaseSession()
      }
    } catch (_: CancellationException) {
      // This is normal when the video closes.
    } finally {
      releaseSession()
    }
  }

  private fun publish(time: Long, frame: Bitmap) {
    if (!active()) return
    memory.put(time, frame)
    memoryTimes.add(time) // a real in-memory success; disk save follows
    _tick.value += 1L
  }

  private fun persist(time: Long, frame: Bitmap) {
    if (!active()) return
    val tmp = File(directory, "$time.${System.nanoTime()}.tmp")
    val destination = File(directory, "$time.jpg")
    runCatching {
      FileOutputStream(tmp).use { require(frame.compress(Bitmap.CompressFormat.JPEG, 80, it)) }
      if (active() && tmp.renameTo(destination)) {
        diskIndex.add(time)
      }
    }.onFailure {
      Log.w("SeekPreviewPerf", "JPEG preview write failed (${it.javaClass.simpleName})")
    }
    if (tmp.exists()) tmp.delete()
    // Keep the thumbnail spool bounded. No video-byte cache is created by the new engine.
    val files = directory.listFiles()?.filter { it.isFile && it.extension == "jpg" }.orEmpty()
    var total = files.sumOf { it.length() }
    if (total > 96L * 1024L * 1024L) for (file in files.sortedBy { it.lastModified() }) {
      if (total <= 80L * 1024L * 1024L) break
      val size = file.length()
      if (file.delete()) {
        total -= size
        file.nameWithoutExtension.toLongOrNull()?.let(diskIndex::remove)
      }
    }
  }

  fun cachedFrameTimesMs(): List<Long> =
    (diskIndex.asSequence() + memoryTimes.asSequence().filter { memory.get(it) != null })
      .distinct().sorted().toList()

  fun peek(timeMs: Long): Bitmap? = memory.get(timeMs)

  fun peekNearby(timeMs: Long, maxDistanceMs: Long = 10_000L): Bitmap? {
    memory.get(timeMs)?.let { return it }
    val closest = (diskIndex.asSequence() + memoryTimes.asSequence())
      .filter { abs(it-timeMs) <= maxDistanceMs }
      .minByOrNull { abs(it-timeMs) } ?: return null
    memory.get(closest)?.let { return it }
    // Never do JPEG disk I/O on the Compose main thread. Foreground worker
    // will resolve disk misses asynchronously instead.
    return null
  }

  fun unavailable(timeMs: Long): Boolean = failureAt[timeMs]?.let {
    SystemClock.elapsedRealtime() - it < 5_000L
  } ?: false

  fun close() {
    if (!closed.compareAndSet(false, true)) return
    activeDecoder?.interruptPersistent()
    activeBridge?.cancelActivePreviewRequests()
    foregroundRequests.close()
    scope.cancel()
    memory.evictAll()
    memoryTimes.clear()
    // SeekPreviewSessionDisk handles JPEG deletion when the session ends.
  }
}
