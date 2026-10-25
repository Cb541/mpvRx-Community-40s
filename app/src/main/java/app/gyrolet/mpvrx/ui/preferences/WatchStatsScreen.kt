package app.gyrolet.mpvrx.ui.preferences

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.repository.WatchMediaStats
import app.gyrolet.mpvrx.repository.WatchStatsRepository
import app.gyrolet.mpvrx.repository.WatchStatsSnapshot
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
object WatchStatsScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backStack = LocalBackStack.current
    val repository = koinInject<WatchStatsRepository>()
    val scope = rememberCoroutineScope()
    var revision by remember { mutableStateOf(0) }
    val stats by produceState(WatchStatsSnapshot(), revision) { value = repository.snapshot() }

    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text(stringResource(R.string.watch_stats_title)) },
          navigationIcon = {
            IconButton(onClick = backStack::popSafely) {
              Icon(Icons.RoundedFilled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
          },
          actions = {
            TextButton(
              enabled = stats.totalSeconds > 0 || stats.sessions > 0 || stats.media.isNotEmpty(),
              onClick = { scope.launch { repository.clear(); revision++ } },
            ) { Text(stringResource(R.string.watch_stats_reset)) }
          },
        )
      },
    ) { padding ->
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item { WatchTimeHero(stats) }
        item { WatchSplit(stats) }
        item { WeeklyActivity(stats.days) }
        item {
          Text(
            text = stringResource(R.string.watch_stats_top_media),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp),
          )
        }
        val topMedia = stats.media.entries.sortedByDescending { it.value.seconds }.take(8)
        if (topMedia.isEmpty()) {
          item {
            Text(
              text = stringResource(R.string.watch_stats_empty),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(vertical = 24.dp),
            )
          }
        } else {
          items(topMedia, key = Map.Entry<String, WatchMediaStats>::key) { (_, media) -> WatchMediaRow(media) }
        }
      }
    }
  }
}

@Composable
private fun WatchTimeHero(stats: WatchStatsSnapshot) {
  Card(
    shape = RoundedCornerShape(8.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
  ) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(stringResource(R.string.watch_stats_total_time), style = MaterialTheme.typography.labelLarge)
      Text(
        text = formatWatchDuration(stats.totalSeconds),
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = stringResource(R.string.watch_stats_sessions, stats.sessions),
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
      )
    }
  }
}

@Composable
private fun WatchSplit(stats: WatchStatsSnapshot) {
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    StatTile(stringResource(R.string.watch_stats_video), stats.videoSeconds, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
    StatTile(stringResource(R.string.watch_stats_audio), stats.audioSeconds, MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
  }
}

@Composable
private fun StatTile(label: String, seconds: Long, accent: Color, modifier: Modifier = Modifier) {
  Card(modifier = modifier, shape = RoundedCornerShape(8.dp)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(label, style = MaterialTheme.typography.labelMedium, color = accent)
      Text(formatWatchDuration(seconds), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
  }
}

@Composable
private fun WeeklyActivity(days: Map<String, Long>) {
  val dates = remember { (6 downTo 0).map { LocalDate.now().minusDays(it.toLong()) } }
  val values = dates.map { days[it.toString()] ?: 0L }
  val max = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
  val color = MaterialTheme.colorScheme.primary
  Card(shape = RoundedCornerShape(8.dp)) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(stringResource(R.string.watch_stats_last_seven_days), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      Canvas(Modifier.fillMaxWidth().height(112.dp)) {
        val gap = 8.dp.toPx()
        val width = (size.width - gap * (values.size - 1)) / values.size
        values.forEachIndexed { index, value ->
          val barHeight = size.height * (value.toFloat() / max.toFloat())
          drawRoundRect(
            color = color,
            topLeft = Offset(index * (width + gap), size.height - barHeight),
            size = Size(width, barHeight.coerceAtLeast(3.dp.toPx())),
          )
        }
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        dates.forEach { date ->
          Text(date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelSmall)
        }
      }
    }
  }
}

@Composable
private fun WatchMediaRow(media: WatchMediaStats) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier =
        Modifier
          .size(48.dp)
          .clip(RoundedCornerShape(6.dp))
          .background(MaterialTheme.colorScheme.surfaceContainerHigh),
      contentAlignment = Alignment.Center,
    ) {
      if (!media.artworkUri.isNullOrBlank()) {
        RemoteImage(
          url = media.artworkUri,
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
      } else {
        Icon(
          imageVector = if (media.isAudio) Icons.RoundedFilled.Audiotrack else Icons.RoundedFilled.PlayArrow,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Column(Modifier.weight(1f)) {
      Text(media.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
      Text(
        stringResource(if (media.isAudio) R.string.watch_stats_audio else R.string.watch_stats_video),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Text(formatWatchDuration(media.seconds), style = MaterialTheme.typography.labelLarge)
  }
}

private fun formatWatchDuration(seconds: Long): String {
  return DateUtils.formatElapsedTime(seconds.coerceAtLeast(0))
}
