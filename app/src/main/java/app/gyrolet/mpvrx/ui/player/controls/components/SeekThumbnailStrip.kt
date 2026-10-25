/* SPDX-License-Identifier: AGPL-3.0-or-later
 * Seeking preview UI adapted toward a single floating preview window inspired by
 * Nuvio Native tvOS while retaining the Android seek-preview backend.
 */
package app.gyrolet.mpvrx.ui.player.controls.components

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import app.gyrolet.mpvrx.domain.seekpreview.SeekPreviewTargetStability
import app.gyrolet.mpvrx.domain.seekpreview.SeekPreviewStore
import app.gyrolet.mpvrx.domain.seekpreview.SeekStripLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
internal fun SeekThumbnailStrip(
  source: String?,
  headers: Map<String, String>,
  generation: Long,
  targetSeconds: Float?,
  durationSeconds: Float,
  playbackPosition: StateFlow<Float>,
  compact: Boolean,
  modifier: Modifier = Modifier,
  onCachedFramesChanged: (List<Long>) -> Unit = {},
) {
  val context = LocalContext.current
  val density = LocalDensity.current
  val store = remember(source, generation, headers) {
    source?.takeIf { it.isNotBlank() }?.let {
      SeekPreviewStore(context.applicationContext, it, generation, headers)
    }
  }
  DisposableEffect(store) { onDispose { store?.close() } }
  LaunchedEffect(targetSeconds == null) { if (targetSeconds == null) store?.request(emptyList()) }

  val cacheTick = store?.tick?.collectAsState()?.value ?: 0L
  val pendingProgress = store?.previewProgress?.collectAsState()?.value
  val coverageTick = store?.coverageTick?.collectAsState()?.value ?: 0L
  LaunchedEffect(store, coverageTick) {
    // Coalesce bursts; sorting the full list of cached slots is done on an
    // IO/background dispatcher, not for every disk-to-RAM image transition
    // during an active UI scrub.
    delay(120L)
    val times = withContext(Dispatchers.Default) { store?.cachedFrameTimesMs().orEmpty() }
    onCachedFramesChanged(times)
  }
  LaunchedEffect(store, playbackPosition, durationSeconds) {
    if (store != null) {
      playbackPosition
        .map { (it / 12f).toLong() }
        .distinctUntilChanged()
        .collect { bucket ->
          store.warmAround(bucket * 12f, durationSeconds)
          store.capturePlaybackFrame(bucket * 12f)
        }
    }
  }
  if (targetSeconds == null || !targetSeconds.isFinite() || durationSeconds <= 0f || !durationSeconds.isFinite()) return

  val targetMs = (targetSeconds * 1000.0).roundToLong()
  val durationMs = (durationSeconds * 1000.0).roundToLong()
  val networkSource = source?.startsWith("https://", true) == true ||
    source?.startsWith("http://", true) == true
  // Five-second quantization alone can visibly mismatch the progress bar
  // even for a perfectly accurate decoder. Keep the existing 120ms debounce
  // to coalesce scrubbing, but request at one-second precision instead.
  val previewMs = if (networkSource) {
    SeekStripLayout.pictureMs(targetMs.toDouble(), 1_000L).coerceIn(0L, durationMs)
  } else {
    targetMs.coerceIn(0L, durationMs)
  }

  // The finger's precise seek position is NEVER changed. Only the preview
  // worker's requested position is held steady while a small finger movement
  // would otherwise reset an actively decoding frame back to 0% / Queued.
  // Once the in-flight frame finishes, request the exact latest finger target.
  var requestedPreviewMs by remember(store) { mutableStateOf(previewMs) }
  val activeTargetIsProcessing = pendingProgress?.let { progress ->
    progress.targetMs == requestedPreviewMs && progress.step in setOf(
      SeekPreviewStore.PreviewStep.QUEUED,
      SeekPreviewStore.PreviewStep.WAITING,
      SeekPreviewStore.PreviewStep.OPENING,
      SeekPreviewStore.PreviewStep.DECODING,
    )
  } == true
  LaunchedEffect(store, previewMs, pendingProgress?.targetMs, pendingProgress?.step) {
    if (previewMs != requestedPreviewMs && !SeekPreviewTargetStability.shouldHoldActiveTarget(
        currentTargetMs = requestedPreviewMs,
        selectedTargetMs = previewMs,
        durationMs = durationMs,
        isProcessing = activeTargetIsProcessing,
      )) {
      requestedPreviewMs = previewMs
    }
  }

  // Two genuinely different things: exact frames (within 1 second) and
  // the nearest PRE-COMPUTED 12-second sample. Many seek cursor positions
  // are between these grid positions; previously the UI didn't show a valid
  // grid JPEG and just sat on "updating preview" until FFmpeg finished.
  // A grid sample can be shown immediately ONLY with its real frame time.
  // It never satisfies/cancels the precise FFmpeg request for previewMs.
  val exactHit = remember(previewMs, cacheTick, store) {
    store?.peekTimed(previewMs, 1_000L)
  }
  val gridSample = remember(previewMs, cacheTick, store) {
    store?.peekTimed(previewMs, 6_000L)
  }
  // Keep the last decoded picture rather than a black screen while loading,
  // but NEVER present it as a picture of the new seek time.
  var lastSuccessfulFrame by remember(store) {
    mutableStateOf<SeekPreviewStore.TimedFrame?>(null)
  }
  LaunchedEffect(exactHit, gridSample) {
    (exactHit ?: gridSample)?.let { lastSuccessfulFrame = it }
  }
  val displayed = exactHit ?: gridSample ?: lastSuccessfulFrame
  val pictureIsCurrent = exactHit != null && kotlin.math.abs(exactHit.timeMs - previewMs) <= 1_000L
  val isCachedGridSample = !pictureIsCurrent && gridSample != null
  // Percentages denote real worker milestones, not estimated per-frame decoding.
  val currentProgress = pendingProgress?.takeIf {
    it.targetMs == requestedPreviewMs && !pictureIsCurrent &&
      it.step != SeekPreviewStore.PreviewStep.COMPLETE
  }
  LaunchedEffect(store, previewMs) {
    // Cached 12-second JPEG lookup always follows the finger immediately.
    // This disk/RAM path does not queue native FFmpeg extraction.
    store?.primeCachedPreview(previewMs, 6_000L)
  }
  LaunchedEffect(store, requestedPreviewMs) {
    // Only intentional changes (or the completion of a held preview) retarget
    // the foreground native worker. Tiny moves don't reset the status box.
    store?.request(listOf(requestedPreviewMs))
  }

  val glide by animateFloatAsState(
    targetValue = targetMs.toFloat(),
    animationSpec = tween(durationMillis = 45, easing = LinearEasing),
    label = "seek-preview-glide",
  )

  // Keep the existing picture size. The status lives in ONE tiny, content-
  // sized black pill immediately underneath; it never covers the preview.
  val windowWidth = if (compact) 275.1f.dp else 370.65f.dp
  val windowHeight = if (compact) 151.2f.dp else 194.25f.dp
  val corner = if (compact) 12.dp else 14.dp
  // Reserve a little extra vertical room so the tiny rounded pill never
  // gets clipped at the bottom, even when it wraps to two lines.
  val infoPanelHeight = if (compact) 26.dp else 28.dp
  val infoPanelGap = 3.dp
  val infoPanelReserve = 4.dp

  BoxWithConstraints(
    modifier =
      modifier
        .fillMaxWidth()
        .height(windowHeight + infoPanelHeight + infoPanelGap + infoPanelReserve)
        .clipToBounds(),
  ) {
    val widthPx = constraints.maxWidth.toFloat()
    val previewWidthPx = with(density) { windowWidth.toPx() }
    val usableWidth = (widthPx - previewWidthPx).coerceAtLeast(0f)
    val fraction = (glide / durationMs.toFloat()).coerceIn(0f, 1f)
    val xOffset = (usableWidth * fraction).roundToInt()

    Box(
      modifier =
        Modifier
          .align(Alignment.TopStart)
          .offset { IntOffset(xOffset, 0) }
          .width(windowWidth)
          .height(windowHeight + infoPanelHeight + infoPanelGap + infoPanelReserve)
          .zIndex(1f),
    ) {
      Box(
        modifier =
          Modifier
            .align(Alignment.TopCenter)
            .width(windowWidth)
            .height(windowHeight)
            .clip(RoundedCornerShape(corner))
            .background(Color.Black.copy(alpha = 0.94f)),
      ) {
        PreviewPicture(
          bitmap = displayed?.bitmap,
          corner = corner,
          modifier = Modifier.fillMaxSize(),
        )
      }

      // Minimal status: show exactly what the current preview represents.
      // "Frames" identifies the selected timestamp and the actual cached
      // JPEG timestamp. It is NEVER used for a distant last-good fallback.
      // A cached grid frame can be displayed while the exact seek decodes,
      // with its percent shown beneath it as a second line.
      if (!pictureIsCurrent) {
        val frameLine = if (isCachedGridSample && gridSample != null) {
          "Frames ${seekStripTimestamp(targetMs)}–${seekStripTimestamp(gridSample.timeMs)}"
        } else null
        val generationLine = if (currentProgress?.step == SeekPreviewStore.PreviewStep.FAILED) {
          "Unavailable"
        } else {
          "Generating ${currentProgress?.step?.percent ?: 0}%"
        }
        Column(
          modifier = Modifier
            .align(Alignment.TopCenter)
            .offset(y = windowHeight + infoPanelGap)
            .widthIn(max = windowWidth - 18.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black)
            .padding(horizontal = 7.dp, vertical = 3.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
          if (frameLine != null) {
            Text(
              text = frameLine,
              color = Color.White,
              fontSize = if (compact) 7.5.sp else 8.25.sp,
              lineHeight = if (compact) 8.5.sp else 9.25.sp,
              fontWeight = FontWeight.Medium,
              maxLines = 1,
            )
          }
          Text(
            text = generationLine,
            color = Color.White,
            fontSize = if (compact) 7.5.sp else 8.25.sp,
            lineHeight = if (compact) 8.5.sp else 9.25.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
          )
        }
      }
    }
  }
}

@Composable
private fun PreviewPicture(
  bitmap: Bitmap?,
  corner: androidx.compose.ui.unit.Dp,
  modifier: Modifier,
) {
  Box(
    modifier =
      modifier
        .clip(RoundedCornerShape(corner))
        .background(Color.Black.copy(alpha = 0.9f)),
  ) {
    if (bitmap != null) {
      Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}

internal fun seekStripTimestamp(timeMs: Long): String {
  val total = (timeMs / 1000L).coerceAtLeast(0L)
  val seconds = (total % 60).toString().padStart(2, '0')
  val minutes = (total / 60 % 60).toString().padStart(2, '0')
  return if (total >= 3600) "${total / 3600}:$minutes:$seconds" else "$minutes:$seconds"
}
