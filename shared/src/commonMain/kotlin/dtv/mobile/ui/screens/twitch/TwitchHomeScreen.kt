package dtv.mobile.ui.screens.twitch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.Modifier
import dtv.mobile.model.Platform
import dtv.mobile.model.Streamer
import dtv.mobile.repo.TwitchCate
import dtv.mobile.repo.TwitchPage
import dtv.mobile.state.AppState
import dtv.mobile.state.SubscribedPartition
import dtv.mobile.ui.screens.HomePillItem
import dtv.mobile.ui.screens.PlatformHomeContent

private const val PAGE_SIZE = 30

@Composable
fun TwitchHomeScreen(
  appState: AppState,
  modifier: Modifier = Modifier,
) {
  var games: List<TwitchCate> by remember { mutableStateOf(emptyList()) }
  var selectedSlug: String? by remember { mutableStateOf<String?>(null) }

  var rooms: List<Streamer> by remember { mutableStateOf(emptyList()) }
  var loading by remember { mutableStateOf(true) }
  var loadingMore by remember { mutableStateOf(false) }
  var hasMore by remember { mutableStateOf(true) }
  var cursor: String? by remember { mutableStateOf(null) }

  val gridState = rememberLazyGridState()

  LaunchedEffect(Unit) {
    loading = true
    games = appState.repo.fetchTwitchCategories()

    val savedId = if (appState.rememberCategoryEnabled) {
      appState.rememberedCategoryId(Platform.Twitch)
    } else {
      appState.currentPartition
        ?.takeIf { it.platform == Platform.Twitch }
        ?.id
    }
    // 分区 id 形态：twitch:all / twitch:g:<slug>
    selectedSlug = savedId
      ?.takeIf { it.startsWith("twitch:g:") }
      ?.substringAfter("twitch:g:")
      ?.takeIf { it.isNotBlank() }
    loading = false
  }

  suspend fun loadPage(reset: Boolean) {
    if (reset) {
      rooms = emptyList()
      hasMore = true
      cursor = null
    }
    if (!hasMore) return

    if (reset) loading = true else loadingMore = true
    try {
      val result: TwitchPage = appState.repo.fetchTwitchLiveList(
        gameSlug = selectedSlug,
        cursor = cursor,
        limit = PAGE_SIZE,
      )
      val incoming = result.items
      val old = rooms
      val (merged, addedCount) = if (reset) {
        incoming to incoming.size
      } else {
        val existing = old.asSequence().map { it.roomId }.toHashSet()
        val added = incoming.filter { existing.add(it.roomId) }
        (old + added) to added.size
      }
      rooms = merged
      cursor = result.cursor ?: cursor
      hasMore = result.hasMore && addedCount > 0
    } finally {
      if (reset) {
        loading = false
        appState.platformSwitchLoading = false
      } else {
        loadingMore = false
      }
    }
  }

  LaunchedEffect(selectedSlug) {
    val partition = if (selectedSlug.isNullOrBlank()) {
      SubscribedPartition(id = "twitch:all", name = "全部", platform = Platform.Twitch)
    } else {
      val name = games.firstOrNull { it.id == selectedSlug }?.name ?: selectedSlug!!
      SubscribedPartition(id = "twitch:g:$selectedSlug", name = name, platform = Platform.Twitch)
    }
    appState.currentPartition = partition
    appState.saveRememberedCategory(
      platform = Platform.Twitch,
      id = partition.id,
    )
    loadPage(reset = true)
    gridState.scrollToItem(0)
  }

  val pills = buildList {
    add(HomePillItem(key = "all", label = "全部"))
    games.forEach { add(HomePillItem(key = it.id, label = it.name)) }
  }
  val selectedPillKey = selectedSlug ?: "all"

  PlatformHomeContent(
    appState = appState,
    currentPartition = appState.currentPartition?.takeIf { it.platform == Platform.Twitch },
    pills = pills,
    selectedPillKey = selectedPillKey,
    onPillClick = { index ->
      val pill = pills.getOrNull(index) ?: return@PlatformHomeContent
      selectedSlug = pill.key.takeIf { it != "all" }
    },
    rooms = rooms,
    loading = loading,
    loadingMore = loadingMore,
    hasMore = hasMore,
    gridState = gridState,
    onRefresh = {
      if (!loading) {
        loadPage(reset = true)
        gridState.scrollToItem(0)
      }
    },
    onLoadMore = { loadPage(reset = false) },
    modifier = modifier,
    aboveGrid = null,
  )
}
