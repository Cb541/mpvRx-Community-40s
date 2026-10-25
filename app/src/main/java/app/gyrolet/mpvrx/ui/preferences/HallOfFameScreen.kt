package app.gyrolet.mpvrx.ui.preferences

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.repository.GitHubCommunityMember
import app.gyrolet.mpvrx.repository.GitHubContributor
import app.gyrolet.mpvrx.repository.GitHubContributorsRepository
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.LocalShowSettingsBackArrow
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import java.text.NumberFormat
import java.util.Locale

@Serializable
object HallOfFameScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val repository = koinInject<GitHubContributorsRepository>()
    val backstack = LocalBackStack.current
    val uriHandler = LocalUriHandler.current
    val githubRepoUrl = stringResource(R.string.github_repo_url).trimEnd('/')
    var refreshRequest by remember { mutableIntStateOf(0) }
    var contributors by remember { mutableStateOf(CreditsState<GitHubContributor>()) }
    var active by remember { mutableStateOf(CreditsState<GitHubContributor>()) }
    var community by remember { mutableStateOf(CreditsState<GitHubCommunityMember>()) }

    LaunchedEffect(refreshRequest) {
      contributors = contributors.copy(loading = true, failed = false)
      active = active.copy(loading = true, failed = false)
      community = community.copy(loading = true, failed = false)
      launch {
        repository.contributors(forceRefresh = refreshRequest > 0)
          .onSuccess { contributors = CreditsState(entries = it, loading = false) }
          .onFailure { contributors = contributors.copy(loading = false, failed = true) }
      }
      launch {
        repository.activeContributors(forceRefresh = refreshRequest > 0)
          .onSuccess { entries ->
            active = CreditsState(
              entries = entries.filterNot { it.displayName.lowercase(Locale.ROOT) in leadLogins },
              loading = false,
            )
          }
          .onFailure { active = active.copy(loading = false, failed = true) }
      }
      launch {
        repository.communityMembers(forceRefresh = refreshRequest > 0)
          .onSuccess { entries ->
            community = CreditsState(
              entries = entries.filterNot { it.login.lowercase(Locale.ROOT) in leadLogins },
              loading = false,
            )
          }
          .onFailure { community = community.copy(loading = false, failed = true) }
      }
    }

    val loading = contributors.loading || active.loading || community.loading
    val colors = MaterialTheme.colorScheme
    val remainingActive = active.copy(
      entries = active.entries.filterNot { it.displayName.lowercase(Locale.ROOT) in featuredLogins },
    )
    val topReporters = community.entries.filter { it.issuesReported > 0 }.take(3)
    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.pref_hall_of_fame_title),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = colors.primary,
            )
          },
          navigationIcon = {
            if (LocalShowSettingsBackArrow.current) {
              IconButton(onClick = { backstack.popSafely() }) {
                Icon(Icons.RoundedFilled.ArrowBack, stringResource(R.string.back))
              }
            }
          },
          actions = {
            TooltipBox(
              positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
              tooltip = { PlainTooltip { Text(stringResource(R.string.ui_refresh)) } },
              state = rememberTooltipState(),
            ) {
              IconButton(onClick = { refreshRequest++ }, enabled = !loading) {
                Icon(Icons.RoundedFilled.Refresh, stringResource(R.string.ui_refresh))
              }
            }
          },
        )
      },
    ) { paddingValues ->
      Box(
        modifier = Modifier.fillMaxSize().padding(paddingValues),
        contentAlignment = Alignment.TopCenter,
      ) {
        LazyVerticalGrid(
          columns = GridCells.Adaptive(136.dp),
          modifier = Modifier.widthIn(max = 960.dp).fillMaxSize(),
          contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          item(key = "creators", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
              HallOfFamePerson(
                name = "MarlboroAdvance",
                handle = "marlboro-advance",
                details = stringResource(R.string.hall_of_fame_original_developer),
                avatarUrl = "https://avatars.githubusercontent.com/u/227117361?s=256",
                profileUrl = "https://github.com/marlboro-advance",
                featured = true,
                accent = colors.onPrimaryContainer,
                containerColor = colors.primaryContainer,
              )
              HallOfFamePerson(
                name = "Ritesh Pandit",
                handle = "Riteshp2001",
                details = stringResource(R.string.hall_of_fame_maintainer),
                avatarUrl = "https://avatars.githubusercontent.com/u/87899750?s=256",
                profileUrl = "https://github.com/Riteshp2001",
                featured = true,
                accent = colors.onTertiaryContainer,
                containerColor = colors.tertiaryContainer,
              )
            }
          }
          item(key = "featured:header", span = { GridItemSpan(maxLineSpan) }) {
            Row(
              modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(Icons.RoundedFilled.Star, null, modifier = Modifier.size(22.dp), tint = colors.primary)
              Text(
                text = stringResource(R.string.hall_of_fame_featured_contributors),
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
              )
            }
          }
          item(key = "featured:profiles", span = { GridItemSpan(maxLineSpan) }) {
            HallOfFameTopThree(featuredContributors) { contributor, tileModifier ->
              HallOfFamePerson(
                name = contributor.name,
                details = "@${contributor.login}",
                avatarUrl = "https://avatars.githubusercontent.com/u/${contributor.avatarId}?s=256",
                profileUrl = "https://github.com/${contributor.login}",
                accent = colors.onSecondaryContainer,
                containerColor = colors.secondaryContainer,
                prominent = true,
                modifier = tileModifier,
              )
            }
          }
          creditsSection(
            sectionKey = "active",
            titleRes = R.string.hall_of_fame_active_title,
            subtitleRes = R.string.hall_of_fame_active_period,
            icon = Icons.RoundedFilled.Star,
            state = remainingActive,
            itemKey = { it.profileUrl ?: it.displayName },
            onRetry = { refreshRequest++ },
            activityUrl = "$githubRepoUrl/commits",
          ) { contributor ->
            HallOfFamePerson(
              name = contributor.displayName,
              details = pluralStringResource(
                R.plurals.hall_of_fame_commit_count, contributor.contributions, contributor.contributions,
              ),
              avatarUrl = contributor.avatarUrl,
              profileUrl = contributor.profileUrl,
              accent = colors.primary,
            )
          }
          creditsSection(
            sectionKey = "community",
            titleRes = R.string.hall_of_fame_feedback_title,
            subtitleRes = R.string.hall_of_fame_feedback_summary,
            icon = Icons.RoundedFilled.BugReport,
            state = community,
            itemKey = { it.login },
            onRetry = { refreshRequest++ },
            activityUrl = "$githubRepoUrl/issues",
            highlightedKeys = topReporters.mapTo(mutableSetOf()) { it.login },
            highlightedContent = if (topReporters.isEmpty()) null else {
              {
                HallOfFameTopThree(topReporters) { member, tileModifier ->
                  HallOfFamePerson(
                    name = member.login,
                    details = communityDetails(member),
                    avatarUrl = member.avatarUrl,
                    profileUrl = member.profileUrl,
                    accent = colors.onTertiaryContainer,
                    containerColor = colors.tertiaryContainer,
                    prominent = true,
                    modifier = tileModifier,
                  )
                }
              }
            },
          ) { member ->
            HallOfFamePerson(
              name = member.login,
              details = communityDetails(member),
              avatarUrl = member.avatarUrl,
              profileUrl = member.profileUrl,
              accent = colors.tertiary,
            )
          }
          creditsSection(
            sectionKey = "all",
            titleRes = R.string.hall_of_fame_all_title,
            subtitleRes = R.string.hall_of_fame_all_period,
            icon = Icons.RoundedFilled.Person,
            state = contributors,
            itemKey = { it.profileUrl ?: "anonymous:${it.displayName}" },
            onRetry = { refreshRequest++ },
            activityUrl = "$githubRepoUrl/graphs/contributors",
          ) { contributor ->
            HallOfFamePerson(
              name = contributor.displayName,
              details = pluralStringResource(
                R.plurals.contributors_contribution_count, contributor.contributions, contributor.contributions,
              ),
              avatarUrl = contributor.avatarUrl,
              profileUrl = contributor.profileUrl,
              accent = colors.secondary,
            )
          }
          item(key = "github", span = { GridItemSpan(maxLineSpan) }) {
            TextButton(
              onClick = { runCatching { uriHandler.openUri("$githubRepoUrl/graphs/contributors") } },
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(stringResource(R.string.hall_of_fame_view_github))
              Icon(Icons.RoundedFilled.ChevronRight, null, modifier = Modifier.size(18.dp))
            }
          }
        }
      }
    }
  }
}

private data class CreditsState<T>(
  val entries: List<T> = emptyList(),
  val loading: Boolean = true,
  val failed: Boolean = false,
)

private val leadLogins = setOf("marlboro-advance", "riteshp2001")

private data class FeaturedContributor(val name: String, val login: String, val avatarId: Long)

private val featuredContributors = listOf(
  FeaturedContributor("Arnab Sadhukhan", "Arnab11", 25551878L),
  FeaturedContributor("Utsav", "Utsavrajputt", 296386188L),
  FeaturedContributor("SunnyVishnu3", "SunnyVishnu3", 196376335L),
)

private val featuredLogins = featuredContributors.mapTo(mutableSetOf()) { it.login.lowercase(Locale.ROOT) }

private fun <T> LazyGridScope.creditsSection(
  sectionKey: String,
  @StringRes titleRes: Int,
  @StringRes subtitleRes: Int,
  icon: AppIcon,
  state: CreditsState<T>,
  itemKey: (T) -> String,
  onRetry: () -> Unit,
  activityUrl: String,
  highlightedKeys: Set<String> = emptySet(),
  highlightedContent: (@Composable () -> Unit)? = null,
  content: @Composable (T) -> Unit,
) {
  item(key = "$sectionKey:header", span = { GridItemSpan(maxLineSpan) }) {
    Column(modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Icon(icon, null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
          text = stringResource(titleRes),
          modifier = Modifier.weight(1f).semantics { heading() },
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
        )
        if (!state.loading && !state.failed) {
          Text(
            text = NumberFormat.getIntegerInstance().format(state.entries.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Text(
        text = stringResource(subtitleRes),
        modifier = Modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
  if (highlightedContent != null) {
    item(key = "$sectionKey:highlights", span = { GridItemSpan(maxLineSpan) }) {
      highlightedContent()
    }
  }
  if (state.loading || state.failed || state.entries.isEmpty()) {
    item(key = "$sectionKey:status", span = { GridItemSpan(maxLineSpan) }) {
      val uriHandler = LocalUriHandler.current
      Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          if (state.loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
          Text(
            text = stringResource(
              when {
                state.loading -> R.string.contributors_loading
                state.failed -> R.string.contributors_load_error
                else -> R.string.contributors_empty
              },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (state.failed) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.contributors_retry)) }
            TextButton(onClick = { runCatching { uriHandler.openUri(activityUrl) } }) {
              Text(stringResource(R.string.hall_of_fame_view_github))
            }
          }
        }
      }
    }
  }
  items(
    state.entries.filterNot { itemKey(it) in highlightedKeys },
    key = { "$sectionKey:${itemKey(it)}" },
    contentType = { "person" },
  ) { entry ->
    content(entry)
  }
}

@Composable
private fun <T> HallOfFameTopThree(
  entries: List<T>,
  content: @Composable (T, Modifier) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    entries.take(3).forEach { entry ->
      content(entry, Modifier.weight(1f).fillMaxHeight())
    }
    repeat((3 - entries.size).coerceAtLeast(0)) {
      Spacer(Modifier.weight(1f))
    }
  }
}

@Composable
private fun communityDetails(member: GitHubCommunityMember): String = buildList {
  if (member.issuesReported > 0) {
    add(pluralStringResource(R.plurals.hall_of_fame_issue_count, member.issuesReported, member.issuesReported))
  }
  if (member.feedbackComments > 0) {
    add(pluralStringResource(R.plurals.hall_of_fame_feedback_count, member.feedbackComments, member.feedbackComments))
  }
}.joinToString("\n")

@Composable
private fun HallOfFamePerson(
  name: String,
  details: String,
  avatarUrl: String?,
  profileUrl: String?,
  accent: Color,
  handle: String? = null,
  featured: Boolean = false,
  prominent: Boolean = false,
  containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
  modifier: Modifier = Modifier,
) {
  val uriHandler = LocalUriHandler.current
  val shape = RoundedCornerShape(8.dp)
  val profileLabel = stringResource(R.string.hall_of_fame_view_profile, name)
  Surface(
    modifier = modifier.fillMaxWidth().clip(shape).clickable(
      enabled = profileUrl != null,
      role = Role.Button,
      onClickLabel = profileLabel,
      onClick = { profileUrl?.let { runCatching { uriHandler.openUri(it) } } },
    ),
    shape = shape,
    color = containerColor,
    border = if (prominent) BorderStroke(1.dp, accent.copy(alpha = 0.2f)) else null,
  ) {
    if (featured) {
      Row(
        modifier = Modifier.padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        HallOfFameAvatar(avatarUrl = avatarUrl, accent = accent, size = 64.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(text = details, style = MaterialTheme.typography.labelMedium, color = accent)
          Text(
            text = name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = accent,
          )
          Text(
            text = "@${handle.orEmpty()}",
            style = MaterialTheme.typography.bodySmall,
            color = accent,
          )
        }
        if (profileUrl != null) {
          Icon(Icons.RoundedFilled.ChevronRight, null, modifier = Modifier.size(18.dp), tint = accent)
        }
      }
    } else {
      Column(
        modifier = Modifier
          .heightIn(min = if (prominent) 208.dp else 160.dp)
          .padding(horizontal = if (prominent) 8.dp else 10.dp, vertical = if (prominent) 20.dp else 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        HallOfFameAvatar(avatarUrl = avatarUrl, accent = accent, size = if (prominent) 64.dp else 48.dp)
        Text(
          text = name,
          modifier = Modifier.fillMaxWidth(),
          style = if (prominent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          color = if (prominent) accent else MaterialTheme.colorScheme.onSurface,
          textAlign = TextAlign.Center,
          minLines = 2,
        )
        Text(
          text = details,
          modifier = Modifier.fillMaxWidth(),
          style = MaterialTheme.typography.bodySmall,
          color = if (prominent) accent else MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}

@Composable
private fun HallOfFameAvatar(avatarUrl: String?, accent: Color, size: Dp) {
  Box(
    modifier = Modifier.size(size).clip(CircleShape).background(accent.copy(alpha = 0.12f)),
    contentAlignment = Alignment.Center,
  ) {
    Icon(Icons.RoundedFilled.Person, null, modifier = Modifier.size(24.dp), tint = accent)
    avatarUrl?.let { url ->
      RemoteImage(url, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
  }
}
