/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import kotlinx.coroutines.delay
import android.os.SystemClock

/** A stall must persist this long before the spinner appears, so ordinary seeks never flash it. */
private const val BUFFERING_SHOW_DELAY_MS = 350L

/** Once shown the spinner lingers this long, so a cache oscillating at mpv's threshold can't strobe. */
private const val BUFFERING_HIDE_DELAY_MS = 300L

/**
 * Resolved buffering state for the player overlay.
 *
 * @param visible whether the spinner should be on screen right now.
 * @param isCacheStall mpv paused playback specifically because the cache ran dry.
 * @param cachePercent mpv's fill progress toward the unpause threshold, when it reports one.
 * @param cacheSeconds how many seconds of media the demuxer currently holds.
 */
internal data class BufferingState(
  val visible: Boolean = false,
  val isCacheStall: Boolean = false,
  val isSeekStall: Boolean = false,
  val cachePercent: Int? = null,
  val cacheSeconds: Double? = null,
)

/**
 * Derives "is playback actually stalled?" from mpv's core state rather than from a single property.
 *
 * `paused-for-cache` only becomes true once mpv has already given up and stopped the clock, so it
 * misses slow opens and demuxer stalls. `core-idle` covers those: it is set whenever the core is not
 * producing output. Subtracting the cases where that is expected (user paused, end of file, nothing
 * loaded) leaves the precise condition.
 */
@Composable
internal fun rememberBufferingState(
  enabled: Boolean,
  forceVisible: Boolean = false,
): BufferingState {
  val paused by PlaybackSession.propBoolean["pause"].collectAsState()
  val coreIdle by PlaybackSession.propBoolean["core-idle"].collectAsState()
  val pausedForCache by PlaybackSession.propBoolean["paused-for-cache"].collectAsState()
  val eofReached by PlaybackSession.propBoolean["eof-reached"].collectAsState()
  val idleActive by PlaybackSession.propBoolean["idle-active"].collectAsState()
  val cacheBufferingState by PlaybackSession.propInt["cache-buffering-state"].collectAsState()
  val demuxerCacheDuration by PlaybackSession.propDouble["demuxer-cache-duration"].collectAsState()

  val isCacheStall = pausedForCache == true
  var isSeekStall by remember { mutableStateOf(false) }
  // mpv can stay in its seeking state without ever setting paused-for-cache.
  // That previously left a frozen frame with no progress indication.
  LaunchedEffect(enabled) {
    var lastSeek = PlaybackSession.lastPlaybackSeekAtElapsedMs
    var activeSeek = false
    var lastPosition: Double? = null
    var lastProgressMs = SystemClock.elapsedRealtime()
    var progressedSeconds = 0.0
    while (enabled) {
      val now = SystemClock.elapsedRealtime()
      val signal = PlaybackSession.lastPlaybackSeekAtElapsedMs
      if (signal > lastSeek) {
        lastSeek = signal
        activeSeek = true
        lastPosition = null
        lastProgressMs = now
        progressedSeconds = 0.0
      }
      val current = PlaybackSession.propDouble["time-pos"].value
      if (activeSeek && current != null && current.isFinite()) {
        val previous = lastPosition
        if (previous == null || kotlin.math.abs(current - previous) > 0.3) {
          // A many-minute seek jump is not successful playback progress.
          val delta = if (previous == null) 0.0 else current - previous
          if (delta in 0.3..2.0) progressedSeconds += delta
          lastPosition = current
          lastProgressMs = now
        }
        if (progressedSeconds >= 2.0 &&
            PlaybackSession.propBoolean["seeking"].value != true &&
            PlaybackSession.propBoolean["paused-for-cache"].value != true) {
          activeSeek = false
        }
      }
      isSeekStall = activeSeek && now - signal > 1_500L &&
        now - lastProgressMs > 1_200L && PlaybackSession.propBoolean["pause"].value != true &&
        PlaybackSession.propBoolean["eof-reached"].value != true &&
        PlaybackSession.propBoolean["idle-active"].value != true
      delay(250L)
    }
    isSeekStall = false
  }
  val stalled =
    enabled &&
      (
        forceVisible ||
          (
            paused != true &&
              eofReached != true &&
              idleActive != true &&
              (isCacheStall || coreIdle == true || isSeekStall)
          )
      )

  var visible by remember { mutableStateOf(false) }
  LaunchedEffect(stalled) {
    if (visible == stalled) return@LaunchedEffect
    delay(if (stalled) BUFFERING_SHOW_DELAY_MS else BUFFERING_HIDE_DELAY_MS)
    visible = stalled
  }

  return BufferingState(
    visible = visible,
    isCacheStall = isCacheStall,
    isSeekStall = isSeekStall,
    cachePercent = cacheBufferingState?.takeIf { it in 0..100 },
    cacheSeconds = demuxerCacheDuration?.takeIf { it.isFinite() && it > 0.0 },
  )
}
