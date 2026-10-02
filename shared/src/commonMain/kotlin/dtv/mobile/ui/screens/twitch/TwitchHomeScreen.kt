package dtv.mobile.ui.screens.twitch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
  // 分类恢复是否已完成。进直播间时平台页会被整页销毁（AnimatedContent 按 screen 切换），
  // 返回后重建；恢复是异步的（要拉分类），而下面写回分区的 effect 是同步触发的，
  // 若不加这道闸门，它会用「还没恢复的 null」立刻把已保存的分类覆盖成「推荐」。
  var restored by remember { mutableStateOf(false) }

  var rooms: List<Streamer> by remember { mutableStateOf(emptyList()) }
  // 请求世代：切分类时旧协程会被取消，旧协程的 finally 若无条件 loading=false，
  // 会把新一次加载正在显示的骨架屏提前抹掉 —— 用户看到的是空白网格且不知在加载。
  // 其余四个平台（斗鱼/虎牙/抖音/B站）都已有这个校验，这里补齐。
  var loadGeneration by remember { mutableIntStateOf(0) }
  var loading by remember { mutableStateOf(true) }

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
    restored = true
    // 这里刻意不复位 loading：分类拉完立刻会由下面写回分区的 effect 触发 loadPage()，
    // 中间复位会让界面闪一下「空列表」，直接让骨架屏一路铺到数据回来。
  }

  suspend fun loadPage() {
    loadGeneration += 1
    val generation = loadGeneration
    loading = true
    try {
      val result: TwitchPage = appState.repo.fetchTwitchLiveList(
        gameSlug = selectedSlug,
        limit = PAGE_SIZE,
        chineseOnly = appState.twitchChineseOnly,
      )
      if (generation != loadGeneration) return
      rooms = result.items
    } finally {
      // 只有「当前世代」那次请求才有资格清 loading
      if (generation == loadGeneration) {
        loading = false
        appState.platformSwitchLoading = false
      }
    }
  }

  // 顶栏「板块下拉菜单」：Twitch 分类数量多（几十个），第二行横向平铺
  // 左右滑动找分类非常不便，因此本平台特殊处理——隐藏胶囊行，把
  // 「推荐 + 全部分类」整体收进顶栏右上角的下拉菜单（RootScaffold 渲染，
  // 超高自动滚动）。「推荐」= 中文（ZH）热门 + 中文谈天说地 + 中文IRL 的聚合流
  // （设置里可切回全语言人气总榜），点具体分类则只看该分类（不限语言）。
  // 分类下拉项：只随分类列表重建，不随 selectedSlug 变化重建
  // （100+ 条分类每次选中都 buildList 一遍纯属浪费）。
  val menuOptions = remember(games) {
    buildList {
      add("推荐")
      games.forEach { add(it.name) }
    }
  }
  DisposableEffect(menuOptions, selectedSlug) {
    if (menuOptions.isNotEmpty() && appState.selectedPlatform == Platform.Twitch) {
      appState.categoryMenu = CategoryMenuState(
        options = menuOptions,
        // slug 在分类列表中找不到（分类下架/id 变更）时回落高亮「推荐」，
        // 不能 coerceAtLeast(0) 后 +1 错误高亮第一个分类（误导用户当前选择）。
        selectedIndex = when {
          selectedSlug == null -> 0
          else -> games.indexOfFirst { it.id == selectedSlug }.takeIf { it >= 0 }?.plus(1) ?: 0
        },
        onSelect = { index ->
          selectedSlug = if (index <= 0) null else games.getOrNull(index - 1)?.id
        },
      )
    }
    onDispose { }
  }

  // 「推荐」分区显示名（历史 id "twitch:all" 保留以兼容已记住的分区）。
  // 必须等恢复完成（restored）后才写回分区并落库：恢复期间 selectedSlug 还是 null，
  // 直接落库会把用户上次选的分类清成「推荐」，导致看完直播回来分类被重置。
  // twitchChineseOnly 作为 key：在设置里改了「推荐只看中文」后回到本页会自动重新拉取。
  LaunchedEffect(selectedSlug, restored, appState.twitchChineseOnly) {
    if (!restored) return@LaunchedEffect
    val partition = if (selectedSlug.isNullOrBlank()) {
      SubscribedPartition(id = "twitch:all", name = "推荐", platform = Platform.Twitch)
    } else {
      // 不用 selectedSlug!!：onSelect 里 games.getOrNull(...) 可能拿到 null，
      // 重组时序下这个 !! 依赖的可能是上一帧的快照。兜底成空串更安全。
      val name = games.firstOrNull { it.id == selectedSlug }?.name ?: selectedSlug.orEmpty()
      SubscribedPartition(id = "twitch:g:$selectedSlug", name = name, platform = Platform.Twitch)
    }
    appState.currentPartition = partition
    appState.saveRememberedCategory(
      platform = Platform.Twitch,
      id = partition.id,
    )
    loadPage()
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
    // Twitch 匿名接口单页最多 30 条且游标翻页被服务端 integrity check 拒绝，
    // 因此固定单页：底部提示「无更多直播间」，不再显示会落空的「继续滑动加载更多」。
    loadingMore = false,
    hasMore = false,
    gridState = gridState,
    onRefresh = {
      if (!loading) {
        loadPage()
        gridState.scrollToItem(0)
      }
    },
    onLoadMore = { },
    modifier = modifier,
    aboveGrid = null,
  )
}
