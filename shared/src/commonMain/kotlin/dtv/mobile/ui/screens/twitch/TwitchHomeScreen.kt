package dtv.mobile.ui.screens.twitch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import dtv.mobile.state.CategoryMenuState
import dtv.mobile.state.SubscribedPartition
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

  // 顶栏「板块下拉菜单」：Twitch 分类数量多（几十个），第二行横向平铺
  // 左右滑动找分类非常不便，因此本平台特殊处理——隐藏胶囊行，把
  // 「推荐 + 全部分类」整体收进顶栏右上角的下拉菜单（RootScaffold 渲染，
  // 超高自动滚动）。推荐即官方「浏览」页的默认热门流（未选分类）。
  DisposableEffect(games, selectedSlug) {
    if (games.isNotEmpty() && appState.selectedPlatform == Platform.Twitch) {
      val options = buildList {
        add("推荐")
        games.forEach { add(it.name) }
      }
      appState.categoryMenu = CategoryMenuState(
        options = options,
        selectedIndex = if (selectedSlug == null) {
          0
        } else {
          1 + games.indexOfFirst { it.id == selectedSlug }.coerceAtLeast(0)
        },
        onSelect = { index ->
          selectedSlug = if (index <= 0) null else games.getOrNull(index - 1)?.id
        },
      )
    }
    onDispose { }
  }

  // 「推荐」分区显示名（历史 id "twitch:all" 保留以兼容已记住的分区）
  LaunchedEffect(selectedSlug) {
    val partition = if (selectedSlug.isNullOrBlank()) {
      SubscribedPartition(id = "twitch:all", name = "推荐", platform = Platform.Twitch)
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

  PlatformHomeContent(
    appState = appState,
    currentPartition = appState.currentPartition?.takeIf { it.platform == Platform.Twitch },
    pills = emptyList(),
    selectedPillKey = null,
    onPillClick = { },
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
