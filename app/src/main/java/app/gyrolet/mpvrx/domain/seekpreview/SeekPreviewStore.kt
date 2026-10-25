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
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Playback-first seek previews with SenPlayer-inspired cooperative browsing.
 *  - No background remote decoder while video is PLAYING. 12-second snapshots
 *    come from the main player's already-decoded video.
 *  - One latest-request-wins persistent FFmpeg decoder for uncached previews.
 *  - A small, bounded neighbor burst ONLY when explicitly paused for browsing.
 *    Any seek, buffering event, request change or resume cancels future neighbors.
 *  - Dedicated JPEG writes so foreground frames publish before disk compression.
 *  - Playback's own URL and demuxer are never redirected through the preview proxy.
 *
 * This adapts design principles, not SenPlayer proprietary code. It intentionally
 * does not run unrestricted whole-movie remote thumbnail generation.
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
  // 64 MiB keeps about twice as many generated thumbnails ready without JPEG reads.
  private val memory = object : LruCache<Long, Bitmap>(64 * 1024 * 1024) {
    override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
  }
  /** Include the real cache timestamp so the UI never labels an old frame as a new one. */
  data class TimedFrame(val timeMs: Long, val bitmap: Bitmap)
  // Sorted concurrent sets: cached seek lookups are O(log N), not an O(N)
  // scan of every JPEG in a long movie on every one-second scrub movement.
  private val diskIndex = ConcurrentSkipListSet<Long>()
  private val memoryTimes = ConcurrentSkipListSet<Long>()
  // Playback screenshots have REAL timestamps, not rounded 12 s timestamps.
  // Track captures within 1 s of a grid slot so speculative FFmpeg does not
  // redundantly decode that same moment (which slowed the accuracy build).
  // Verify the referenced bitmap still exists before skipping any slot.
  private val capturedGridSlots = ConcurrentHashMap<Long, Long>()

  private fun hasAccurateCachedSlot(slotMs: Long): Boolean {
    if (slotMs in diskIndex || memory.get(slotMs) != null) return true
    val capturedAt = capturedGridSlots[slotMs] ?: return false
    if (capturedAt in diskIndex || memory.get(capturedAt) != null) return true
    capturedGridSlots.remove(slotMs, capturedAt)
    return false
  }
  private val failureAt = ConcurrentHashMap<Long, Long>()
  private val _tick = MutableStateFlow(0L)
  val tick = _tick.asStateFlow()
  // Only new cache coverage requires recalculating the timeline. Moving an
  // existing JPEG from disk to RAM must NOT resort every cached timestamp.
  private val _coverageTick = MutableStateFlow(0L)
  val coverageTick = _coverageTick.asStateFlow()

  /** Real completion MILESTONES, not an invented FFmpeg decode percentage.
   * The precompiled native library does not report video-frame decode progress.
   * Progress is intentionally stationary while a step is in flight; elapsed
   * time in the UI provides the accurate indication of how slow that step is.
   */
  enum class PreviewStep(val percent: Int, val label: String) {
    QUEUED(0, "Queued"),
    WAITING(10, "Waiting for playback"),
    OPENING(25, "Opening stream"),
    DECODING(45, "Decoding frame"),
    COMPLETE(100, "Ready"),
    FAILED(0, "Unavailable"),
  }
  data class PreviewProgress(val targetMs: Long, val step: PreviewStep, val startedMs: Long)
  private val _previewProgress = MutableStateFlow<PreviewProgress?>(null)
  val previewProgress = _previewProgress.asStateFlow()

  /** Never publish a completed/stale job's status as the current finger target. */
  private fun progressStep(target: Long, step: PreviewStep) {
    synchronized(lock) {
      if (latestRequest != target) return
      val previous = _previewProgress.value
      val started = previous?.takeIf { it.targetMs == target }?.startedMs
        ?: SystemClock.elapsedRealtime()
      _previewProgress.value = PreviewProgress(target, step, started)
    }
  }

  // There can never be more than one speculative HTTP connection, because
  // there is no speculative HTTP work at all. Foreground is strictly user-driven.
  private val foregroundRequests = Channel<Long>(Channel.CONFLATED)
  // Independent cache-only disk reader: a JPEG already on disk must never
  // wait for FFmpeg, network admission, or the foreground decode queue.
  private val cachedDiskRequests = Channel<Long>(Channel.CONFLATED)
  // JPEG compression is serialized on its own IO coroutine. Rapid eight-frame
  // bursts must not spawn eight competing JPEG encoders while the foreground
  // disk reader is trying to show an already-generated picture.
  private val jpegSaves = Channel<Pair<Long, Bitmap>>(Channel.UNLIMITED)
  private val latestCachedDiskTarget = AtomicLong(-1L)
  private var latestRequest: Long? = null
  @Volatile private var latestRequestQueuedAtMs = 0L
  private var lastCaptureSlot = -1L
  private val disabledPlatformProbe = AtomicInteger(-1) // FFmpeg first, no multi-second Android probe.
  @Volatile private var activeDecoder: NetworkSeekFrameRetriever? = null
  @Volatile private var activeBridge: NetworkSeekHttpBridge? = null
  // Clock progress is sampled independently of the native decoder loop. A
  // blocked FFmpeg call must not keep using an out-of-date "healthy" reading.
  @Volatile private var lastPlaybackAdvanceAtMs = 0L
  private var lastSeekSignal = PlaybackSession.lastPlaybackSeekAtElapsedMs
  private val seekEpoch = AtomicLong(0L)
  // Only the foreground worker closes native contexts. Other threads set this
  // bit and interrupt their I/O; freeing a decoder on a different thread while
  // FFmpeg is inside av_read_frame is unsafe.
  private val transportInvalidated = AtomicBoolean(false)
  private val activeForegroundSlot = AtomicLong(-1L)
  @Volatile private var decodingNeighbor = false
  // Explicit upper bound on sidecar work while the user pauses and browses.
  // No remote neighbor generation runs while the video is actually playing.
  private val pausedNeighborOffsets = longArrayOf(12_000L, -12_000L, 24_000L, -24_000L, 36_000L, 48_000L)
  @Volatile private var aheadCursorMs = -1L
  @Volatile private var aheadDurationMs = -1L
  // Under 20 GB: first sweep every 24s across the entire movie, THEN return
  // to fill the missing 12s positions. Independent of the playback clock and
  // preserved across user seeks; source session owns this scheduler.
  private val aheadFullMoviePasses = AheadFullMoviePasses()
  // Windowed schedulers (medium/large files) track the active ahead band.
  private var aheadTwoPassWindowStartMs = -1L
  private var aheadTwoPassPreviousCursorMs = -1L
  @Volatile private var aheadHandle = 0L
  // Tracks decoded output across separately opened ahead sessions. Some
  // providers/native seek failures repeatedly return the SAME opening frame
  // while the Java API reports only a Bitmap (no decoded presentation time).
  // One pinned reference costs less than a megabyte and adds no network I/O.
  private var lastAheadFrame: Bitmap? = null
  private var lastAheadFrameAtMs = -1L

  private fun plausibleAheadFrame(timeMs: Long, frame: Bitmap): Boolean {
    val previous = lastAheadFrame
    if (previous != null && lastAheadFrameAtMs >= 0L &&
      abs(timeMs - lastAheadFrameAtMs) >= 12_000L &&
      previous.width == frame.width && previous.height == frame.height &&
      previous.sameAs(frame)) {
      Log.w("SeekPreviewPerf", "ahead-repeated-distant-frame-suppressed slotMs=$timeMs previousMs=$lastAheadFrameAtMs")
      return false
    }
    lastAheadFrame = frame
    lastAheadFrameAtMs = timeMs
    return true
  }
  private val aheadFailuresAtRevision = ConcurrentHashMap<Long, Long>()
  // Revision alone can stay constant for an entire video even after playback
  // and HTTP admission recover. Retry only after a bounded quiet period.
  private val aheadCacheRetryAfterMs = ConcurrentHashMap<Long, Long>()
  private val aheadRemoteRetryAfterMs = ConcurrentHashMap<Long, Long>()
  private val aheadPausedForUser = AtomicBoolean(false)
  // Large remuxes only: do not let speculative decoding immediately restart
  // while foreground FFmpeg is still opening/closing or the user is scrubbing.
  @Volatile private var manualLargeFileQuietUntilMs = 0L

  init {
    directory.listFiles()?.forEach { f ->
      if (f.isFile && f.extension == "jpg") f.nameWithoutExtension.toLongOrNull()?.let(diskIndex::add)
    }
    Log.i("SeekPreviewPerf", "preview-session actual-jpeg-files=${diskIndex.size} sourceType=${if (networkSource) "network" else "local"} captureStep=12s seekStep=1s (video-byte cache is separate)")
    Log.i("SeekPreviewPerf", "senplayer-inspired preview: playing-remote-workers=0 foreground-workers=1 pause-burst-max=6 capture-step=12s ahead-order=24s-then-12s")
    scope.launch {
      var previous: Double? = null
      while (active()) {
        delay(400L)
        val clock = PlaybackSession.propDouble["time-pos"]?.value?.takeIf { it.isFinite() }
        if (clock != null && previous != null &&
          clock - previous > 0.06 && clock - previous < 4.0 &&
          !PlaybackSession.state.value.paused &&
          (if (AheadPacing.sizeBand(SharedPlaybackTransportRegistry.mediaSizeBytes(generation, source)) == AheadPacing.SizeBand.LARGE)
            PlaybackSession.propBoolean["seeking"]?.value != true &&
              PlaybackSession.propBoolean["paused-for-cache"]?.value != true &&
              PlaybackSession.propBoolean["core-idle"]?.value != true
          else !playbackNeedsPriority())) {
          lastPlaybackAdvanceAtMs = SystemClock.elapsedRealtime()
        }
        previous = clock
      }
    }
    scope.launch { foregroundLoop() }
    scope.launch { cachedDiskLookupLoop() }
    scope.launch {
      for ((at, bitmap) in jpegSaves) {
        if (!active()) break
        persist(at, bitmap)
      }
    }
    // Native FFmpeg extraction is blocking. Monitor playback on an independent
    // coroutine so a newly stalled video can interrupt an in-flight ahead
    // decoder instead of waiting for that decode to return on its own.
    scope.launch {
      var interruptedHandle = 0L
      while (active()) {
        delay(350L)
        val handle = aheadHandle
        if (handle == 0L) {
          interruptedHandle = 0L
        } else if (!mayFetchAhead() && handle != interruptedHandle) {
          PersistentNativeSeek.interrupt(handle)
          interruptedHandle = handle
          Log.d("SeekPreviewPerf", "ahead-preempted for playback")
        }
      }
    }
    // Exactly ONE playback-fed, cache-ONLY worker. The playback proxy serves
    // its bytes entirely from existing blocks and cannot reach the origin.
    scope.launch { cacheOnlyAheadLoop() }
    // Separate lightweight playback-seek observer: interrupt the one pending
    // thumbnail HTTP request BEFORE playback needs a fresh unbuffered range.
    scope.launch {
      while (active()) {
        delay(100)
        val signal = PlaybackSession.lastPlaybackSeekAtElapsedMs
        if (signal > lastSeekSignal) {
          lastSeekSignal = signal
          invalidateTransport(playbackSeek = true)
          Log.d("SeekPreviewPerf", "playback-seek: thumbnail connection invalidated; will reopen after seek")
        }
      }
    }
  }

  private fun active(): Boolean = !closed.get() &&
    PlaybackSession.isCurrentGeneration(generation) && SeekPreviewSessionDisk.isActive(generation)

  /** Interrupt I/O immediately, but never release native handles off the
   * foreground worker. A canceled NanoHTTPD bridge is permanently suspended,
   * so the next preview MUST create a fresh instance (not reuse the old URL).
   */
  private fun invalidateTransport(playbackSeek: Boolean) {
    seekEpoch.incrementAndGet()
    transportInvalidated.set(true)
    PersistentNativeSeek.interrupt(aheadHandle)
    if (playbackSeek) {
      activeDecoder?.interruptForPlaybackSeek()
      // Shared playback socket is NEVER canceled, only the optional thumbnail side.
      SharedPlaybackTransportRegistry.suspendOnlyPreviews(generation)
    } else activeDecoder?.interruptPersistent()
    activeBridge?.cancelActivePreviewRequests()
  }

  /** Called by the existing UI; no network is used here, on purpose. */
  fun warmAround(positionSeconds: Float, durationSeconds: Float) {
    if (!active() || !positionSeconds.isFinite() || !durationSeconds.isFinite() ||
      durationSeconds <= 0f) return
    aheadCursorMs = (positionSeconds * 1000).toLong().coerceAtLeast(0)
    aheadDurationMs = (durationSeconds * 1000).toLong().coerceAtLeast(0)
  }

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
      // The coroutine may start long after it was scheduled. Timestamp the
      // screenshot with the playback clock AT capture, not with a rounded
      // 12-second bucket computed earlier on the Compose collector thread.
      val before = PlaybackSession.propDouble["time-pos"]?.value?.takeIf { it.isFinite() }
      val frame = runCatching { PlaybackSession.grabThumbnail(512) }.getOrNull()
      val after = PlaybackSession.propDouble["time-pos"]?.value?.takeIf { it.isFinite() }
      if (frame == null || !active() || before == null || after == null ||
          after < before || after - before > 1.5 ||
          PlaybackSession.propBoolean["seeking"]?.value == true ||
          PlaybackSession.state.value.paused) {
        synchronized(lock) { if (lastCaptureSlot == slot) lastCaptureSlot = -1L }
        return@launch
      }
      // 250 ms precision is ample for the seek preview and keeps the number
      // of distinct JPEG filenames bounded. Unlike the 12-second grid this
      // timestamp describes the displayed image rather than a grid marker.
      val capturedAtMs = (((before + after) * 500.0).toLong() / 250L) * 250L
      publish(capturedAtMs, frame)
      // Exact-time capture should also satisfy its 12-second grid position
      // when it is genuinely close. Never stamp a 6-second-old frame as 12 s.
      if (abs(capturedAtMs - slot) <= 1_000L) capturedGridSlots[slot] = capturedAtMs
      saveAsync(capturedAtMs, frame)
      Log.d("SeekPreviewPerf", "playback-capture slotMs=$capturedAtMs bucketMs=$slot duration=${SystemClock.elapsedRealtime()-start}ms")
    }
  }

  /**
   * Latest requested position wins for NEXT work, but never kills an already
   * decoding frame merely because the finger moved one second or lifted.
   * A valid completed frame is cached under its ORIGINAL timestamp; only a
   * real playback seek / transport invalidation is allowed to interrupt it.
   */
  fun request(timesMs: List<Long>) {
    if (!active()) return
    val next = timesMs.firstOrNull()?.takeIf { it >= 0 }
    synchronized(lock) {
      if (next == latestRequest) {
        // The UI closes the preview with request(emptyList()) even if the
        // foreground decoder was previously suspended for a cached scrub.
        if (next == null) aheadPausedForUser.set(false)
        return
      }
      latestRequest = next
      latestRequestQueuedAtMs = SystemClock.elapsedRealtime()
      // Track ONLY the latest manually selected position. An older decode
      // may still finish and enter the JPEG cache without updating this UI.
      _previewProgress.value = next?.let {
        PreviewProgress(it, PreviewStep.QUEUED, latestRequestQueuedAtMs)
      }
    }
    // Scrubbing previously called invalidateTransport() for EVERY changed
    // target, aborting already-started FFmpeg work and throwing its image away.
    // With a slow HEVC/network seek this required holding a finger perfectly
    // still for several seconds or nothing would ever enter the JPEG cache.
    // The CONFLATED channel prioritizes the latest request once the current
    // frame finishes. Earlier successfully decoded images remain useful later.
    // Never interrupt an in-flight USER-requested decode on finger movement
    // or preview dismissal. Speculative neighbor work remains preemptible;
    // the playback seek observer can still interrupt foreground I/O.
    val running = activeForegroundSlot.get()
    if (decodingNeighbor) {
      // Speculative PAUSED neighbor generation must never hold up a new
      // foreground seek. Interrupt the neighbor (not a user-requested frame).
      invalidateTransport(playbackSeek = false)
    } else if (ScrubContinuationPolicy.inFlightSuperseded(running, next)) {
      // A real requested thumbnail is already decoding. Finish it, cache it
      // under its original timestamp, and then serve latestRequest next.
      Log.d("SeekPreviewPerf", "scrub-decode-continues startedMs=$running nextMs=${next ?: -1L}")
    }
    // 20GB+ still gets stronger speculation throttling, but low-latency
    // progressive HTTP *delivery* now applies to all uncached manual seeks.
    // Reusing this request flag cannot increase ahead/background network I/O.
    // Drop the speculative HTTP budget synchronously, but NEVER cancel the
    // playback connection or the on-demand preview proxy.
    val largeFileQuietMs = AheadPacing.manualPreviewQuietMs(
      SharedPlaybackTransportRegistry.mediaSizeBytes(generation, source))
    if (largeFileQuietMs > 0L) {
      manualLargeFileQuietUntilMs = SystemClock.elapsedRealtime() + largeFileQuietMs
      if (next != null) {
        SharedPlaybackTransportRegistry.finishBudgetedAhead(generation)
        Log.d("SeekPreviewPerf", "large-manual-priority ahead-budget-revoked")
      }
    }
    // All manual remote previews can stream verified 206 bytes immediately.
    // Playback and budgeted/cache-only ahead endpoints remain unchanged.
    if (networkSource) SharedPlaybackTransportRegistry.setLargeForegroundFastMode(
      generation, source, next != null)
    aheadPausedForUser.set(next != null)
    if (next != null) PersistentNativeSeek.interrupt(aheadHandle)
    // Dismissing the seek overlay is NOT a real playback seek. Keep the
    // warmed FFmpeg demuxer/socket alive for the normal paused/playing idle
    // timeout. Closing it here forced a full remote container reopen for
    // every new drag. Playback-seek invalidation and close() still reclaim
    // transport immediately when necessary.
    if (next != null) foregroundRequests.trySend(next)
  }


  /**
   * Stage 3: a cache-ONLY ahead worker. The transport refuses missing bytes;
   * it NEVER fetches a new origin range. It therefore cannot throttle actual
   * playback through its own network requests. Missing positions are deferred
   * until playback captures more complete byte blocks.
   *
   * Separate persistent native handle from foreground; foreground never waits
   * on speculative work. One attempt per cache revision/slot, no retry storm.
   */
  private suspend fun cacheOnlyAheadLoop() {
    var healthyTicks = 0
    var previousClock: Double? = null
    var lastRevision = -1L
    var lastEpoch = -1L
    var decoderHandle = 0L
    var attemptedInRevision = 0
    var revisionBudgetStartedMs = 0L
    var nextBudgetedAttemptAtMs = 0L
    try {
      while (active() && scope.isActive) {
        delay(750L)
        val now = PlaybackSession.propDouble["time-pos"]?.value
        val clockProgressed = now != null && now.isFinite() && previousClock != null &&
          now - previousClock!! > 0.04 && now - previousClock!! < 4.0
        // The independent 400-ms playback monitor handles clocks which tick
        // more slowly than this loop; a stale clock still fails closed.
        val recentAdvance = lastPlaybackAdvanceAtMs != 0L &&
          SystemClock.elapsedRealtime() - lastPlaybackAdvanceAtMs in 0L..1_200L
        val moving = clockProgressed || recentAdvance
        previousClock = now
        // A stale core-idle property was observed in earlier builds despite
        // advancing playback; the actual clock is the definitive gate here.
        if (moving && !PlaybackSession.state.value.paused && !playbackNeedsPriority()) healthyTicks++
        else healthyTicks = 0
        if (!mayDecodeAhead(healthyTicks)) {
          if (decoderHandle != 0L) {
            PersistentNativeSeek.interrupt(decoderHandle)
            PersistentNativeSeek.release(decoderHandle)
            decoderHandle = 0L
            aheadHandle = 0L
          }
          continue
        }
        val url = SharedPlaybackTransportRegistry.cacheOnlyPreviewUrl(generation, source)
          ?: continue // Stage 2 shared transport must actually be enabled.
        val revision = SharedPlaybackTransportRegistry.cachedByteRevision(generation, source)
        if (revision < 1L) continue
        val epoch = seekEpoch.get()
        if (epoch != lastEpoch) {
          aheadFailuresAtRevision.clear()
          aheadCacheRetryAfterMs.clear()
          aheadRemoteRetryAfterMs.clear()
          lastEpoch = epoch
          if (decoderHandle != 0L) PersistentNativeSeek.release(decoderHandle)
          decoderHandle = 0L
          aheadHandle = 0L
          aheadTwoPassWindowStartMs = -1L
          aheadTwoPassPreviousCursorMs = -1L
        }
        val selectionClockMs = SystemClock.elapsedRealtime()
        if (revision != lastRevision ||
          selectionClockMs - revisionBudgetStartedMs >= 15_000L) {
          lastRevision = revision
          revisionBudgetStartedMs = selectionClockMs
          attemptedInRevision = 0
        }
        val bufferSeconds = PlaybackSession.propDouble["demuxer-cache-duration"]?.value
        val mediaBytes = SharedPlaybackTransportRegistry.mediaSizeBytes(generation, source)
        val plan = AheadPacing.plan(mediaBytes, bufferSeconds, healthyTicks)
        if (attemptedInRevision >= plan.attemptsPerCacheRevision) continue
        // Under 20 GB, traverse the ENTIRE movie in 24-second bursts before
        // attempting any intervening 12-second slot. Failed/cooldown slots are
        // deferred to a later full-length sweep, never allowed to trap the
        // first pass forever. Verified 20GB+ files may speculate ahead too,
        // but only sparsely in 40-second steps within a bounded forward band.
        val fullMovie = AheadPacing.fullMovieCoarseFirst(mediaBytes)
        val largeSparseAhead = plan.band == AheadPacing.SizeBand.LARGE && !fullMovie
        val nowForSelection = SystemClock.elapsedRealtime()
        val canAttemptSlot: (Long) -> Boolean = { candidate ->
          (aheadFailuresAtRevision[candidate] != revision ||
            (aheadCacheRetryAfterMs[candidate] ?: 0L) <= nowForSelection) &&
            (aheadRemoteRetryAfterMs[candidate] ?: 0L) <= nowForSelection
        }
        val windowEnd: Long
        val selected: AheadSelection?
        if (fullMovie) {
          // Never seek to duration itself: that timestamp can be EOF rather
          // than a decodable picture and would stall completion forever.
          windowEnd = (aheadDurationMs - 1L).coerceAtLeast(0L)
          selected = aheadFullMoviePasses.select(
            aheadDurationMs, ::hasAccurateCachedSlot, canAttemptSlot)
        } else if (largeSparseAhead) {
          val alignedCursor = (aheadCursorMs / AheadLargeGrid.STRIDE_MS) * AheadLargeGrid.STRIDE_MS
          val jumpedBackward = aheadTwoPassPreviousCursorMs >= 0L &&
            alignedCursor + AheadLargeGrid.STRIDE_MS < aheadTwoPassPreviousCursorMs
          if (aheadTwoPassWindowStartMs < 0L || jumpedBackward ||
            alignedCursor >= aheadTwoPassWindowStartMs + AheadLargeGrid.WINDOW_MS) {
            aheadTwoPassWindowStartMs = alignedCursor
          }
          aheadTwoPassPreviousCursorMs = alignedCursor
          var windowStart = aheadTwoPassWindowStartMs
          var end = minOf(aheadDurationMs,
            windowStart + AheadLargeGrid.WINDOW_MS)
          if (AheadLargeGrid.fullyFilled(windowStart, end, ::hasAccurateCachedSlot) &&
            end >= windowStart + AheadLargeGrid.STRIDE_MS && end < aheadDurationMs) {
            aheadTwoPassWindowStartMs = end
            windowStart = end
            end = minOf(aheadDurationMs,
              windowStart + AheadLargeGrid.WINDOW_MS)
          }
          windowEnd = end
          selected = AheadLargeGrid.select(windowStart, windowEnd,
            isFilled = ::hasAccurateCachedSlot, canAttempt = canAttemptSlot)
        } else {
          // Medium-file bounded 24s/12s window behavior.
          val alignedCursor = (aheadCursorMs / AheadTwoPassGrid.COARSE_MS) * AheadTwoPassGrid.COARSE_MS
          val jumpedBackward = aheadTwoPassPreviousCursorMs >= 0L &&
            alignedCursor + AheadTwoPassGrid.COARSE_MS < aheadTwoPassPreviousCursorMs
          if (aheadTwoPassWindowStartMs < 0L || jumpedBackward ||
            alignedCursor >= aheadTwoPassWindowStartMs + AheadTwoPassGrid.WINDOW_MS) {
            aheadTwoPassWindowStartMs = alignedCursor
          }
          aheadTwoPassPreviousCursorMs = alignedCursor
          var windowStart = aheadTwoPassWindowStartMs
          var end = minOf(aheadDurationMs,
            windowStart + AheadTwoPassGrid.WINDOW_MS,
            windowStart + plan.scanSlots * AheadTwoPassGrid.FINE_MS)
          if (AheadTwoPassGrid.fullyFilled(windowStart, end, ::hasAccurateCachedSlot) &&
            end >= windowStart + AheadTwoPassGrid.FINE_MS && end < aheadDurationMs) {
            aheadTwoPassWindowStartMs = end
            windowStart = end
            end = minOf(aheadDurationMs,
              windowStart + AheadTwoPassGrid.WINDOW_MS,
              windowStart + plan.scanSlots * AheadTwoPassGrid.FINE_MS)
          }
          windowEnd = end
          selected = AheadTwoPassGrid.select(windowStart, windowEnd,
            isFilled = ::hasAccurateCachedSlot, canAttempt = canAttemptSlot)
        }
        if (selected == null) continue
        val slot = selected.slotMs
        val mode = when {
          fullMovie -> "full-movie-under20GB"
          largeSparseAhead -> "large-40s-windowed"
          else -> "windowed"
        }
        Log.d("SeekPreviewPerf", "ahead-scheduler mode=$mode " +
          "phase=${selected.pass} slotMs=$slot strideMs=${selected.strideMs} throughMs=$windowEnd")
        // This is a speculative decode, never an authorized network fetch.
        // Opening FFmpeg may itself request missing metadata; the cache-only
        // proxy returns 503 quickly rather than touching the remote server.
        attemptedInRevision++
        if (decoderHandle == 0L) decoderHandle = PersistentNativeSeek.create(url) ?: 0L
        aheadHandle = decoderHandle
        if (decoderHandle == 0L) {
          Log.d("SeekPreviewPerf", "ahead-cache-only open-miss slotMs=$slot revision=$revision")
        }
        val started = SystemClock.elapsedRealtime()
        var frame = if (decoderHandle != 0L)
          PersistentNativeSeek.extract(decoderHandle, slot / 1000.0, 416) else null
        // A repeated frame must not be saved at an unrelated timestamp, but
        // it must NOT kill the entire decoder session either. Static shots,
        // title cards and fades can legitimately repeat. Continue seeking
        // later positions within the SAME bounded session.
        var startFrameAccepted = frame?.let { plausibleAheadFrame(slot, it) } ?: false
        if (!mayDecodeAhead(healthyTicks) || seekEpoch.get() != epoch) {
          // In-flight decode may have been interrupted by an actual seek.
          PersistentNativeSeek.release(decoderHandle)
          decoderHandle = 0L
          aheadHandle = 0L
          continue
        }
        // The under-20GB whole-movie pass includes medium-band media: let it
        // reuse an already-open cache-only decoder for 24-second bursts too.
        // Every burst remains capped by the band's existing plan (max 8),
        // with no extra HTTP allowance and the same playback safety checks.
        if (frame != null && (plan.band == AheadPacing.SizeBand.SMALL || fullMovie)) {
          val burstStarted = SystemClock.elapsedRealtime()
          for (offset in 1 until minOf(plan.burstFrames, 8)) {
            val neighbor = slot + offset * selected.strideMs
            if (neighbor > windowEnd ||
              !active() || seekEpoch.get() != epoch || !mayDecodeAhead(healthyTicks) ||
              SystemClock.elapsedRealtime() - burstStarted >= 1_500L) break
            if (hasAccurateCachedSlot(neighbor)) continue
            val nearby = PersistentNativeSeek.extract(decoderHandle, neighbor / 1000.0, 416)
              ?: break // Stop immediately on a cache miss; never fetch remotely.
            if (!active() || seekEpoch.get() != epoch || !mayDecodeAhead(healthyTicks)) break
            // Suppress a suspect duplicate, then continue with the next
            // position. Previous accuracy build used 'break' here, dropping
            // the whole otherwise fast burst on a single repeated picture.
            if (!plausibleAheadFrame(neighbor, nearby)) continue
            publish(neighbor, nearby)
            saveAsync(neighbor, nearby)
            aheadFailuresAtRevision.remove(neighbor)
            aheadCacheRetryAfterMs.remove(neighbor)
            aheadRemoteRetryAfterMs.remove(neighbor)
            Log.d("SeekPreviewPerf", "ahead-cached-burst slotMs=$neighbor success=true")
          }
        }
        if (frame == null) {
          PersistentNativeSeek.release(decoderHandle)
          decoderHandle = 0L
          aheadHandle = 0L
          Log.d("SeekPreviewPerf", "ahead-cache-only miss slotMs=$slot revision=$revision")
          // Stage 3 cannot fill a future slot from the past playback buffer
          // alone: MKV indexes and future keyframes may be absent. Allow a
          // strictly budgeted single-client *remote* fallback occasionally,
          // but never while playback is paused, stalling, or seeking.
          val timestamp = SystemClock.elapsedRealtime()
          var triedRemote = false
          val permitted = mayFetchAhead()
          if (timestamp >= nextBudgetedAttemptAtMs && permitted) {
            val cooldownMs = plan.cooldownMs
            nextBudgetedAttemptAtMs = timestamp + cooldownMs
            val remoteUrl = SharedPlaybackTransportRegistry.beginBudgetedAhead(generation, source, plan) {
              active() && seekEpoch.get() == epoch && mayFetchAhead()
            }
            if (remoteUrl != null) {
              triedRemote = true
              var remoteHandle = 0L
              var extraGenerated = 0
              val networkStart = SystemClock.elapsedRealtime()
              try {
                remoteHandle = PersistentNativeSeek.create(remoteUrl) ?: 0L
                aheadHandle = remoteHandle
                if (remoteHandle != 0L && mayFetchAhead() && seekEpoch.get() == epoch) {
                  frame = PersistentNativeSeek.extract(remoteHandle, slot / 1000.0, 416)
                  startFrameAccepted = frame?.let { plausibleAheadFrame(slot, it) } ?: false
                  // Reuse ONE decoder within its size-aware session-wide budget.
                  // Previously every successful slot closed FFmpeg and waited
                  // another 12 seconds before it could generate the next one.
                  if (frame != null) {
                    // Enforce the same eight-frame cap on the remote path.
                    val maximumFrames = minOf(plan.burstFrames, 8)
                    for (offset in 1 until maximumFrames) {
                      val neighbor = slot + offset * selected.strideMs
                      if (neighbor > windowEnd ||
                        !active() || seekEpoch.get() != epoch || !mayFetchAhead() ||
                        SystemClock.elapsedRealtime() - networkStart > plan.deadlineMs - 1_000L) break
                      if (hasAccurateCachedSlot(neighbor)) continue
                      val extra = PersistentNativeSeek.extract(remoteHandle, neighbor / 1000.0, 416)
                        ?: break // never spin on missing keyframe/index bytes
                      if (!active() || seekEpoch.get() != epoch || !mayFetchAhead()) break
                      if (!plausibleAheadFrame(neighbor, extra)) continue
                      publish(neighbor, extra)
                      saveAsync(neighbor, extra)
                      aheadFailuresAtRevision.remove(neighbor)
                      aheadCacheRetryAfterMs.remove(neighbor)
                      aheadRemoteRetryAfterMs.remove(neighbor)
                      extraGenerated++
                      Log.d("SeekPreviewPerf", "ahead-frame slotMs=$neighbor success=true burst=true")
                    }
                  }
                }
              } finally {
                if (remoteHandle != 0L) PersistentNativeSeek.release(remoteHandle)
                aheadHandle = 0L
                SharedPlaybackTransportRegistry.finishBudgetedAhead(generation)
              }
              Log.d("SeekPreviewPerf", "ahead-budgeted slotMs=$slot duration=${SystemClock.elapsedRealtime()-networkStart}ms success=$startFrameAccepted extras=$extraGenerated cooldownMs=$cooldownMs sizeBand=${plan.band} fileBytes=${mediaBytes ?: -1L} maxMiB=${plan.maxMissingBlocks} deadlineMs=${plan.deadlineMs}")
            }
          }
          if (frame == null) {
            if (!triedRemote) {
              Log.d("SeekPreviewPerf", "ahead-no-remote slotMs=$slot " +
                "healthAllowed=$permitted cooldownRemainingMs=${(nextBudgetedAttemptAtMs - timestamp).coerceAtLeast(0L)} " +
                "revision=$revision")
            }
            if (triedRemote) aheadRemoteRetryAfterMs[slot] =
              SystemClock.elapsedRealtime() + AheadPacing.FAILED_SLOT_RETRY_MS
            aheadFailuresAtRevision[slot] = SharedPlaybackTransportRegistry.cachedByteRevision(generation, source)
            aheadCacheRetryAfterMs[slot] = SystemClock.elapsedRealtime() + 15_000L
            continue
          }
        }
        if (!active() || seekEpoch.get() != epoch || !mayDecodeAhead(healthyTicks)) continue
        if (!startFrameAccepted) {
          // Do not claim this timeline position was decoded correctly.
          // Any later successful frames were already published above.
          aheadFailuresAtRevision[slot] = SharedPlaybackTransportRegistry.cachedByteRevision(generation, source)
          aheadCacheRetryAfterMs[slot] = SystemClock.elapsedRealtime() + 15_000L
          Log.d("SeekPreviewPerf", "ahead-suspect-start-skipped slotMs=$slot; later burst slots retained")
          continue
        }
        publish(slot, frame)
        saveAsync(slot, frame)
        aheadFailuresAtRevision.remove(slot)
        aheadCacheRetryAfterMs.remove(slot)
        aheadRemoteRetryAfterMs.remove(slot)
        Log.d("SeekPreviewPerf", "ahead-frame slotMs=$slot duration=${SystemClock.elapsedRealtime()-started}ms success=true")
      }
    } catch (_: CancellationException) {
    } finally {
      if (decoderHandle != 0L) PersistentNativeSeek.release(decoderHandle)
      aheadHandle = 0L
    }
  }

  private fun largeManualQuietActive(): Boolean {
    val until = manualLargeFileQuietUntilMs
    return until > 0L && SystemClock.elapsedRealtime() < until
  }

  private fun mayDecodeAhead(healthyTicks: Int): Boolean =
    // Huge movies prioritize on-demand accurate seeks over expensive sparse
    // speculative FFmpeg index/range scans. Playback capture (free frames)
    // remains active, and smaller films keep the proven eight-frame bursts.
    active() && networkSource &&
      AheadPacing.allowSpeculativeAhead(SharedPlaybackTransportRegistry.mediaSizeBytes(generation, source)) &&
      !largeManualQuietActive() && healthyTicks >= 2 && aheadCursorMs >= 0L &&
      aheadDurationMs > aheadCursorMs && !aheadPausedForUser.get() &&
      PlaybackSession.propBoolean["seeking"]?.value != true &&
      PlaybackSession.propBoolean["paused-for-cache"]?.value != true &&
      !PlaybackSession.state.value.paused && !playbackNeedsPriority()

  /** Network admission for the experimental ahead fallback. Clock progress
   * beats mpv's occasionally stale buffer-duration estimates. */
  private fun mayFetchAhead(): Boolean {
    if (!active() || aheadPausedForUser.get() || largeManualQuietActive() ||
      PlaybackSession.state.value.paused || playbackNeedsPriority()) return false
    val sinceProgress = SystemClock.elapsedRealtime() - lastPlaybackAdvanceAtMs
    if (lastPlaybackAdvanceAtMs == 0L || sinceProgress !in 0L..1_600L) return false
    val buffered = PlaybackSession.propDouble["demuxer-cache-duration"]?.value
    return buffered == null || !buffered.isFinite() || buffered >= 30.0
  }

  /**
   * True when the requested timestamp already has a near-exact thumbnail in
   * memory or on disk. A merely nearby 12-second grid picture is NOT treated
   * as accurate: that would recreate the old wrong-scene preview regression.
   *
   * Safe on the Compose thread: only looks up an in-memory index. Actual JPEG
   * reads and BitmapFactory decoding happen on an independent IO coroutine.
   */
  fun primeCachedPreview(timeMs: Long, maxDistanceMs: Long = 1_000L): Boolean {
    if (!active() || timeMs < 0L) return false
    // Native/foreground workers ONLY use the 1s default: a nearby sample is
    // never incorrectly marked as satisfying a precise frame request.
    // The UI can request the nearest real, timestamp-labelled *cached sample*
    // within half the 12s thumbnail grid (6s) for an instant visual response.
    val hot = peekTimed(timeMs, maxDistanceMs)
    if (hot != null) {
      // Even a RAM hit should proactively warm its two adjacent disk JPEGs.
      // Only queue when the actual cached slot changes, not on every swipe
      // event within the same second.
      if (latestCachedDiskTarget.getAndSet(hot.timeMs) != hot.timeMs)
        cachedDiskRequests.trySend(hot.timeMs)
      return true
    }
    // Queue the actual JPEG timestamp, NOT the continuously changing 1-second
    // seek cursor. All cursor positions mapping to this cached grid slot now
    // share one disk-read target and can reuse the prefetched decoded Bitmap.
    val cachedAt = nearestDiskFrameTime(timeMs, maxDistanceMs) ?: return false
    latestCachedDiskTarget.set(cachedAt)
    cachedDiskRequests.trySend(cachedAt)
    return true
  }

  /** Actual JPEG available within this window (in RAM OR indexed on disk). */
  fun hasCachedPreview(timeMs: Long, maxDistanceMs: Long = 6_000L): Boolean =
    active() && (peekTimed(timeMs, maxDistanceMs) != null ||
      nearestDiskFrameTime(timeMs, maxDistanceMs) != null)

  private fun nearestDiskFrameTime(timeMs: Long, maxDistanceMs: Long = 1_000L): Long? {
    val floor = diskIndex.floor(timeMs)?.takeIf { timeMs - it <= maxDistanceMs }
    val ceil = diskIndex.ceiling(timeMs)?.takeIf { it - timeMs <= maxDistanceMs }
    return when {
      floor == null -> ceil
      ceil == null -> floor
      timeMs - floor <= ceil - timeMs -> floor
      else -> ceil
    }
  }

  /**
   * A disk hit is rendered without involving native seeking. The 12-second
   * neighbours are warmed only after the requested image has been loaded;
   * this reduces disk latency as the user scrubs across cached slots.
   */
  private suspend fun cachedDiskLookupLoop() {
    for (requested in cachedDiskRequests) {
      if (!active()) break
      // The latest target wins what we read NEXT. An in-flight disk JPEG
      // remains a valid thumbnail for its own timestamp even if the finger
      // moved during BitmapFactory.decodeFile; don't throw it away.
      if (latestCachedDiskTarget.get() != requested) continue
      // A nearest 12s-grid sample is a truthful *approximate* visual, and
      // may load even while the exact 1s frame still needs native decoding.
      // A thumbnail may still be in RAM while its JPEG writer is finishing.
      // Warm its neighbors even if the requested bitmap itself is RAM-only.
      val matchingTime = nearestDiskFrameTime(requested, 1_000L)
        ?: peekTimed(requested, 1_000L)?.timeMs ?: continue
      loadCachedDiskFrame(matchingTime, requested, foreground = true)
      if (!active() || latestCachedDiskTarget.get() != requested) continue
      for (neighbor in longArrayOf(matchingTime + 12_000L, matchingTime - 12_000L)) {
        if (!active() || latestCachedDiskTarget.get() != requested) break
        if (neighbor < 0L) continue
        val nearest = nearestDiskFrameTime(neighbor, 1_000L) ?: continue
        loadCachedDiskFrame(nearest, requested, foreground = false)
      }
    }
  }

  private fun loadCachedDiskFrame(at: Long, requested: Long, foreground: Boolean) {
    if (memory.get(at) != null || !active()) return
    val diskReadStarted = SystemClock.elapsedRealtime()
    val bitmap = BitmapFactory.decodeFile(File(directory, "$at.jpg").path)
    val diskDecodeMs = SystemClock.elapsedRealtime() - diskReadStarted
    if (bitmap == null) {
      // A corrupted/deleted JPEG must not permanently suppress actual FFmpeg
      // extraction. Retry the CURRENT target through the original path.
      if (diskIndex.remove(at)) _coverageTick.value += 1L
      if (foreground && isStillWanted(requested)) foregroundRequests.trySend(requested)
      return
    }
    if (active()) {
      // A disk JPEG cannot become inaccurate merely because a finger moved.
      // Cache it under its REAL filename timestamp. The UI will only use it
      // for matching seek positions and will never relabel it as a new time.
      publish(at, bitmap)
      if (foreground) Log.d("SeekPreviewPerf", "cached-disk-ready requestedMs=$requested actualFrameMs=$at jpegDecodeMs=$diskDecodeMs moved=${latestCachedDiskTarget.get()!=requested}")
    }
  }

  private fun isStillWanted(slot: Long): Boolean = synchronized(lock) { latestRequest == slot }

  /** Foreground previews may use the short, playback-verified seek gate on
   * all known sizes. Speculative ahead decoding uses its ORIGINAL gate.
   */
  private fun playbackNeedsPriority(forManualPreview: Boolean = false): Boolean {
    if (!active()) return true
    if (PlaybackSession.propBoolean["seeking"]?.value == true ||
      PlaybackSession.propBoolean["paused-for-cache"]?.value == true) return true
    // A recently committed seek can still be opening its first byte range.
    val seekSignal = PlaybackSession.lastPlaybackSeekAtElapsedMs
    val seekAge = SystemClock.elapsedRealtime() - seekSignal
    if (seekAge !in 0L..2_000L) return false
    val sizeBand = AheadPacing.sizeBand(
      SharedPlaybackTransportRegistry.mediaSizeBytes(generation, source))
    val grace = ForegroundPreviewAdmission.seekGraceMs(
      fastGraceAllowed = ForegroundPreviewAdmission.allowFastGrace(sizeBand, forManualPreview),
      paused = PlaybackSession.state.value.paused,
      coreIdle = PlaybackSession.propBoolean["core-idle"]?.value,
      bufferedSeconds = PlaybackSession.propDouble["demuxer-cache-duration"]?.value,
      advancedAfterSeek = lastPlaybackAdvanceAtMs > seekSignal,
    )
    return seekAge <= grace
  }

  /** The dedicated worker never loops over an entire movie. The decoder is
   * retained up to 30s only while paused, avoiding repeated container opens;
   * during playback the old 8s cleanup rule remains in effect.
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
      var lastRequestAtMs = SystemClock.elapsedRealtime()
      while (scope.isActive && active()) {
        // Check every second: if the user resumes the video partway through
        // a 30s paused idle window, restore the short playing cleanup window
        // immediately rather than retaining a spare HTTP socket for 30s.
        // Once closed, avoid waking every second merely to poll an absent
        // decoder. A new foreground Channel request always wakes immediately.
        val checkIntervalMs = if (decoder != null || bridge != null) 1_000L else 8_000L
        val slot = withTimeoutOrNull(checkIntervalMs) { foregroundRequests.receive() }
        if (slot == null) {
          val idleMs = SystemClock.elapsedRealtime() - lastRequestAtMs
          if ((decoder != null || bridge != null) &&
              idleMs >= ManualPreviewTuning.warmIdleMs(PlaybackSession.state.value.paused)) {
            releaseSession()
            transportInvalidated.set(false)
          }
          continue
        }
        if (slot < 0) {
          // Reserved for explicit transport shutdown. The UI no longer
          // sends this marker merely because a finger was lifted.
          releaseSession()
          transportInvalidated.set(false)
          continue
        }
        lastRequestAtMs = SystemClock.elapsedRealtime()
        // cancelActivePreviewRequests() closes HTTP admission forever for its
        // bridge. Reuse would silently cause every subsequent frame to fail.
        if (transportInvalidated.getAndSet(false)) {
          releaseSession()
          Log.d("SeekPreviewPerf", "foreground transport reopened after cancellation")
        }
        if (!isStillWanted(slot)) continue
        // Already-generated JPEGs must bypass network, playback-priority
        // gates and the native extractor. primeCachedPreview arranges for
        // the disk reader to publish the exact timestamp asynchronously.
        if (primeCachedPreview(slot)) {
          progressStep(slot, PreviewStep.COMPLETE)
          continue
        }
        // Do not treat a distant 12-second-grid neighbour as this position.
        val cached = peekNearby(slot, maxDistanceMs = 1_000L)
        if (cached != null) {
          _tick.value += 1L
          progressStep(slot, PreviewStep.COMPLETE)
          continue
        }
        progressStep(slot, PreviewStep.WAITING)
        // Measure waiting separately: the previous on-demand duration started
        // AFTER this gate and hid the fixed post-seek delay from logs.
        val gateStartedAtMs = SystemClock.elapsedRealtime()
        val requestedAtMs = latestRequestQueuedAtMs
        // Playback always wins. A stalled video never has to wait behind a
        // newly started preview network range request.
        while (playbackNeedsPriority(forManualPreview = true) && active() && isStillWanted(slot)) {
          // Wait without opening a single preview connection. This remains
          // pending through long network stalls and wakes on real playback recovery.
          // Poll without a coarse quarter-second penalty AFTER the
          // playback seek has settled. We still never open HTTP while the
          // priority gate is true.
          delay(80)
        }
        if (!active() || !isStillWanted(slot)) continue
        val gateWaitMs = SystemClock.elapsedRealtime() - gateStartedAtMs
        progressStep(slot, PreviewStep.OPENING)
        if (transportInvalidated.getAndSet(false)) {
          releaseSession()
          Log.d("SeekPreviewPerf", "foreground transport reopened after playback recovery")
        }

        val start = SystemClock.elapsedRealtime()
        val fileSize = SharedPlaybackTransportRegistry.mediaSizeBytes(generation, source)
        Log.d("SeekPreviewPerf", "manual-preview-start slotMs=$slot sizeBand=${AheadPacing.sizeBand(fileSize)} fileBytes=${fileSize ?: -1L} gateWaitMs=$gateWaitMs queuedMs=${start-requestedAtMs}")
        val startedEpoch = seekEpoch.get()
        val previewDimension = ManualPreviewTuning.dimension(fileSize)
        Log.d("SeekPreviewPerf", "manual-decoder-profile width=$previewDimension idlePausedMs=${ManualPreviewTuning.warmIdleMs(PlaybackSession.state.value.paused)}")
        // The 12-second disk index can grow to thousands of JPEGs. A manual
        // exact seek must not linearly scan all of them before opening FFmpeg.
        val onDisk = nearestDiskFrameTime(slot, 750L)
        val diskFrame = onDisk?.let { BitmapFactory.decodeFile(File(directory, "$it.jpg").path) }
        if (diskFrame != null && onDisk != null) {
          publish(onDisk, diskFrame)
          progressStep(slot, PreviewStep.COMPLETE)
          // It already belongs to the timestamp embedded in its filename.
          continue
        }
        if (onDisk != null) diskIndex.remove(onDisk)

        // Playback-shared mode borrows its preview endpoint and byte cache.
        // The preview UI must not own/close the active playback transport.
        val sharedPreviewUrl = SharedPlaybackTransportRegistry.previewUrl(generation, source)
        if (networkSource && bridge == null && sharedPreviewUrl == null) {
          bridge = runCatching { NetworkSeekHttpBridge.open(source, headers, appCache) }.getOrNull()
          bridge?.setLargeForegroundFastMode(true) // On-demand only, not playback or ahead.
          activeBridge = bridge
        }
        if (!isStillWanted(slot) || playbackNeedsPriority(forManualPreview = true) ||
            seekEpoch.get() != startedEpoch || transportInvalidated.get()) continue
        // Re-open preview admission only after the playback-priority gate clears.
        if (!playbackNeedsPriority(forManualPreview = true) && sharedPreviewUrl != null) {
          SharedPlaybackTransportRegistry.resumeOnlyPreviews(generation)
        }
        val previewUrl: () -> String? = {
          if (playbackNeedsPriority(forManualPreview = true)) null else sharedPreviewUrl ?: bridge?.previewUrl
        }
        if (decoder == null && (!networkSource || bridge != null || sharedPreviewUrl != null)) {
          decoder = NetworkSeekFrameRetriever(source, headers, disabledPlatformProbe, previewUrl)
          activeDecoder = decoder
        }
        // Local files use the existing native extractor. Network video uses
        // exactly one FFmpeg session against the authenticated preview proxy.
        progressStep(slot, PreviewStep.DECODING)
        activeForegroundSlot.set(slot)
        val frame = try {
          if (networkSource) {
            decoder?.persistentFrameAt(slot, dimension = previewDimension)
              ?: if (!playbackNeedsPriority(forManualPreview = true) && isStillWanted(slot) &&
                  seekEpoch.get() == startedEpoch && !transportInvalidated.get()) decoder?.nativeFrameAt(slot) { path, second, hw ->
                PlaybackSession.grabThumbnailFast(path, second, previewDimension, hw)
              } else null
          } else {
            PlaybackSession.grabThumbnailFast(source, slot / 1000.0, 512, true)
          }
        } finally {
          activeForegroundSlot.compareAndSet(slot, -1L)
        }
        // A new finger position or lifted finger may have superseded this
        // request while FFmpeg was in av_read_frame(). That does NOT make a
        // correctly decoded frame obsolete for the session cache: preserve it
        // at `slot`, never label it with latestRequest's timestamp. Reject only
        // actual playback-seek interruptions or closed/invalid transports.
        if (!ScrubContinuationPolicy.shouldCacheCompleted(
              active(), startedEpoch, seekEpoch.get(), transportInvalidated.get())) {
          if (seekEpoch.get() != startedEpoch || transportInvalidated.getAndSet(false)) releaseSession()
          continue
        }
        val stillWanted = isStillWanted(slot)
        if (frame != null) {
          publish(slot, frame) // UI updates immediately if this slot is visible.
          saveAsync(slot, frame)
          failureAt.remove(slot)
          progressStep(slot, PreviewStep.COMPLETE)
          if (!stillWanted) Log.d("SeekPreviewPerf", "scrub-finished-cached slotMs=$slot (finger moved or released)")
        } else if (stillWanted) {
          failureAt[slot] = SystemClock.elapsedRealtime()
          progressStep(slot, PreviewStep.FAILED)
        }
        val finishedAtMs = SystemClock.elapsedRealtime()
        Log.d("SeekPreviewPerf", "on-demand-only slotMs=$slot duration=${finishedAtMs-start}ms requestToFrameMs=${finishedAtMs-requestedAtMs} gateWaitMs=$gateWaitMs sizeBand=${AheadPacing.sizeBand(fileSize)} success=${frame!=null}")
        if (frame != null && finishedAtMs - requestedAtMs > 1_000L) Log.i(
          "SeekPreviewPerf", "uncached-over-1s waitMs=${finishedAtMs-requestedAtMs} gateWaitMs=$gateWaitMs nativeAndNetworkMs=${finishedAtMs-start} slotMs=$slot")
        // Reuse the already-open FFmpeg session for a tiny neighboring preview
        // burst, but ONLY if the user explicitly paused. Never while playing.
        // Large remote media are dominated by sparse origin range seeks.
        // A six-neighbor speculative burst could occupy the SINGLE foreground
        // decoder immediately after a successful user frame; the next touch
        // then interrupted that read, invalidated the socket and caused yet
        // another remote container open. Never speculate here on LARGE files.
        // Smaller streams retain the proven limited neighbor warm path.
        if (frame != null && stillWanted && decoder != null && !playbackNeedsPriority() &&
            AheadPacing.sizeBand(fileSize) != AheadPacing.SizeBand.LARGE) {
          generatePausedNeighbors(slot, decoder!!, startedEpoch)
        }
        // Playback seeks and preemption of SPECULATIVE neighbor work can still
        // interrupt transport. Never reuse a truly interrupted native handle.
        if (playbackNeedsPriority(forManualPreview = true) || seekEpoch.get() != startedEpoch || transportInvalidated.get()) {
          releaseSession()
          transportInvalidated.set(false)
        }
      }
    } catch (_: CancellationException) {
      // This is normal when the video closes.
    } finally {
      releaseSession()
    }
  }

  /**
   * Small paused-only 12-second neighborhood, not a second background worker.
   * This is deliberately called on the SINGLE foreground IO worker after
   * publishing the requested frame. A new scrub target cancels the remaining
   * neighbors via latestRequest; a real seek interrupts the native session.
   */
  private suspend fun generatePausedNeighbors(
    requestedSlot: Long,
    decoder: NetworkSeekFrameRetriever,
    requestEpoch: Long,
  ) {
    if (!mayGeneratePausedNeighbors(requestedSlot, requestEpoch)) return
    val anchor = SeekStripLayout.pictureMs(requestedSlot.toDouble(), 12_000L)
    var generated = 0
    for (offset in pausedNeighborOffsets) {
      if (!mayGeneratePausedNeighbors(requestedSlot, requestEpoch)) break
      val neighbor = anchor + offset
      if (neighbor < 0L || neighbor in diskIndex || memory.get(neighbor) != null) continue
      val diskTime = nearestDiskFrameTime(neighbor, 1_000L)
      if (diskTime != null) continue
      val started = SystemClock.elapsedRealtime()
      // Persistent FFmpeg session is reused. Never spin up parallel network
      // workers or probe the Android decoder for speculative neighbors.
      // Suppress bursts during active scrubbing; they are worth the bandwidth
      // only when the user has stopped issuing foreground requests.
      if (SystemClock.elapsedRealtime() - latestRequestQueuedAtMs < 1_000L) break
      decodingNeighbor = true
      val picture = try {
        if (mayGeneratePausedNeighbors(requestedSlot, requestEpoch)) decoder.persistentFrameAt(neighbor, dimension = 416)
        else null
      } finally {
        decodingNeighbor = false
      }
      if (!mayGeneratePausedNeighbors(requestedSlot, requestEpoch)) break
      if (picture == null) {
        Log.d("SeekPreviewPerf", "paused-neighbor decode unsuccessful; ending burst")
        break
      }
      publish(neighbor, picture)
      saveAsync(neighbor, picture)
      generated++
      Log.d("SeekPreviewPerf", "paused-neighbor slotMs=$neighbor duration=${SystemClock.elapsedRealtime()-started}ms")
      // Give any pending foreground seek or playback state update time to win.
      delay(120)
    }
    if (generated > 0) Log.d("SeekPreviewPerf", "paused-neighbor complete frames=$generated")
  }

  private fun mayGeneratePausedNeighbors(requestedSlot: Long, requestEpoch: Long): Boolean =
    active() && seekEpoch.get() == requestEpoch && isStillWanted(requestedSlot) &&
      PlaybackSession.state.value.paused &&
      PlaybackSession.propBoolean["seeking"]?.value != true &&
      PlaybackSession.propBoolean["paused-for-cache"]?.value != true &&
      PlaybackSession.propBoolean["core-idle"]?.value != true &&
      !playbackNeedsPriority()

  /** Frame becomes available immediately; JPEG compression is disk IO only. */
  private fun saveAsync(slot: Long, frame: Bitmap) {
    if (active()) jpegSaves.trySend(slot to frame)
  }

  private fun publish(time: Long, frame: Bitmap) {
    if (!active()) return
    memory.put(time, frame)
    // A real new timestamp changes timeline coverage; a previously saved JPEG
    // being decoded back into RAM changes only the picture itself.
    val newTime = memoryTimes.add(time)
    if (newTime && time !in diskIndex) _coverageTick.value += 1L
    _tick.value += 1L
  }

  private fun persist(time: Long, frame: Bitmap) {
    if (!active()) return
    val tmp = File(directory, "$time.${System.nanoTime()}.tmp")
    val destination = File(directory, "$time.jpg")
    runCatching {
      FileOutputStream(tmp).use { require(frame.compress(Bitmap.CompressFormat.JPEG, 80, it)) }
      if (active() && tmp.renameTo(destination)) {
        if (diskIndex.add(time) && time !in memoryTimes) _coverageTick.value += 1L
      }
    }.onFailure {
      Log.w("SeekPreviewPerf", "JPEG preview write failed (${it.javaClass.simpleName})")
    }
    if (tmp.exists()) tmp.delete()
    // This directory is scoped to ONE playback generation and removed by
    // SeekPreviewSessionDisk when playback ends. Do not enumerate every JPEG
    // on each frame write (quadratic I/O), or silently evict older timestamps
    // at 96MiB while a long movie is still active. Android can still reclaim
    // its cacheDir under storage pressure; failed/missing JPEGs are handled
    // by the existing disk-index fallback on the next seek.
  }

  fun cachedFrameTimesMs(): List<Long> =
    (diskIndex.asSequence() + memoryTimes.asSequence().filter { memory.get(it) != null })
      .distinct().sorted().toList()

  fun peek(timeMs: Long): Bitmap? = memory.get(timeMs)

  /** Only return a frame with a known timestamp, not an old visual fallback
   * accidentally rebranded as the currently requested seek position. Disk
   * JPEGs are loaded on the foreground worker, never on the Compose thread.
   */
  fun peekTimed(timeMs: Long, maxDistanceMs: Long = 6_000L): TimedFrame? {
    memory.get(timeMs)?.let { return TimedFrame(timeMs, it) }
    // Only inspect a narrow, ordered window around this seek time, never the
    // entire 2-hour movie cache. Old evicted RAM entries are harmless here.
    val low = (timeMs - maxDistanceMs).coerceAtLeast(0L)
    val high = if (Long.MAX_VALUE - timeMs < maxDistanceMs) Long.MAX_VALUE
      else timeMs + maxDistanceMs
    return memoryTimes.subSet(low, true, high, true).asSequence()
      .sortedBy { abs(it - timeMs) }
      .mapNotNull { at -> memory.get(at)?.let { TimedFrame(at, it) } }
      .firstOrNull()
  }

  fun peekNearby(timeMs: Long, maxDistanceMs: Long = 6_000L): Bitmap? =
    peekTimed(timeMs, maxDistanceMs)?.bitmap

  fun unavailable(timeMs: Long): Boolean = failureAt[timeMs]?.let {
    SystemClock.elapsedRealtime() - it < 5_000L
  } ?: false

  fun close() {
    if (!closed.compareAndSet(false, true)) return
    _previewProgress.value = null
    invalidateTransport(playbackSeek = true)
    SharedPlaybackTransportRegistry.setLargeForegroundFastMode(generation, source, false)
    // Cancelling the scope wakes receive() with CancellationException, which
    // foregroundLoop already handles. Closing the Channel first instead throws
    // ClosedReceiveChannelException from this SupervisorJob worker.
    PersistentNativeSeek.interrupt(aheadHandle)
    scope.cancel()
    memory.evictAll()
    memoryTimes.clear()
    capturedGridSlots.clear()
    lastAheadFrame = null
    // SeekPreviewSessionDisk handles JPEG deletion when the session ends.
  }
}



internal data class AheadSelection(val slotMs: Long, val strideMs: Long, val pass: String)

/**
 * Apple-TV-style ahead coverage order, without modifying thumbnail timestamps:
 * 1) fill 24-second positions across a bounded window;
 * 2) go back and fill their missing 12-second midpoints.
 *
 * Cached images are ALWAYS stored under their real grid timestamps; we never
 * relabel a 24s bitmap as the missing 12s sample. Deferred network failures
 * do not block the second pass forever. Each pass still respects the 8-frame
 * maximum of AheadPacing.Plan and the existing playback-priority gates.
 */
internal object AheadTwoPassGrid {
  const val FINE_MS = 12_000L
  const val COARSE_MS = 24_000L
  const val WINDOW_MS = COARSE_MS * 8

  enum class Pass { COARSE_24S, FILL_12S }

  fun select(
    startMs: Long,
    endMs: Long,
    isFilled: (Long) -> Boolean,
    canAttempt: (Long) -> Boolean = { true },
  ): AheadSelection? {
    if (endMs <= startMs) return null
    var coarse = startMs + COARSE_MS
    while (coarse <= endMs) {
      if (!isFilled(coarse) && canAttempt(coarse))
        return AheadSelection(coarse, COARSE_MS, Pass.COARSE_24S.name)
      coarse += COARSE_MS
    }
    var midpoint = startMs + FINE_MS
    while (midpoint <= endMs) {
      if (!isFilled(midpoint) && canAttempt(midpoint))
        return AheadSelection(midpoint, COARSE_MS, Pass.FILL_12S.name)
      midpoint += COARSE_MS
    }
    return null
  }

  fun fullyFilled(startMs: Long, endMs: Long, isFilled: (Long) -> Boolean): Boolean {
    if (endMs <= startMs) return false
    var slot = startMs + FINE_MS
    while (slot <= endMs) {
      if (!isFilled(slot)) return false
      slot += FINE_MS
    }
    return true
  }
}


/**
 * Sparse large-file ahead coverage. Verified >=20GB media are allowed to build
 * a light cache ahead of playback, but only at 40-second spacing inside a
 * bounded forward window. This intentionally does NOT introduce 12-second
 * backfill for huge files.
 */
internal object AheadLargeGrid {
  const val STRIDE_MS = 40_000L
  const val WINDOW_MS = STRIDE_MS * 5

  fun select(
    startMs: Long,
    endMs: Long,
    isFilled: (Long) -> Boolean,
    canAttempt: (Long) -> Boolean = { true },
  ): AheadSelection? {
    if (endMs <= startMs) return null
    var slot = startMs + STRIDE_MS
    while (slot <= endMs) {
      if (!isFilled(slot) && canAttempt(slot))
        return AheadSelection(slot, STRIDE_MS, "SPARSE_40S")
      slot += STRIDE_MS
    }
    return null
  }

  fun fullyFilled(startMs: Long, endMs: Long, isFilled: (Long) -> Boolean): Boolean {
    if (endMs <= startMs) return false
    var slot = startMs + STRIDE_MS
    while (slot <= endMs) {
      if (!isFilled(slot)) return false
      slot += STRIDE_MS
    }
    return true
  }
}

/**
 * Whole-movie 24-second coverage followed by whole-movie 12-second backfill.
 *
 * A pass only changes after its cursor crosses the END of the movie, never
 * after a 192s section. Slots temporarily unavailable from remote caching are
 * deferred rather than blocking backfill forever. A later full-length sweep
 * retries gaps. Completed JPEGs/playback captures are skipped by isFilled.
 *
 * Only owned by the ahead coroutine, so no locking / shared mutable reads.
 */
internal class AheadFullMoviePasses {
  private var pass = AheadTwoPassGrid.Pass.COARSE_24S
  private var nextMs = AheadTwoPassGrid.COARSE_MS

  fun select(
    durationMs: Long,
    isFilled: (Long) -> Boolean,
    canAttempt: (Long) -> Boolean = { true },
  ): AheadSelection? {
    val lastFrameMs = durationMs - 1L
    if (lastFrameMs < AheadTwoPassGrid.FINE_MS) return null
    // Visit the remainder of the active pass, then the opposite full pass.
    // Never begin fine slots until EVERY coarse position has been traversed.
    repeat(2) {
      while (nextMs <= lastFrameMs) {
        val current = nextMs
        nextMs += AheadTwoPassGrid.COARSE_MS
        if (!isFilled(current) && canAttempt(current)) {
          return AheadSelection(current,
            AheadTwoPassGrid.COARSE_MS, pass.name)
        }
      }
      if (pass == AheadTwoPassGrid.Pass.COARSE_24S) {
        pass = AheadTwoPassGrid.Pass.FILL_12S
        nextMs = AheadTwoPassGrid.FINE_MS
      } else {
        pass = AheadTwoPassGrid.Pass.COARSE_24S
        nextMs = AheadTwoPassGrid.COARSE_MS
      }
    }
    return null
  }
}
