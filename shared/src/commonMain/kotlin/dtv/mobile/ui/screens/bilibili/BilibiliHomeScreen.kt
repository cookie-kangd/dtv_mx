package dtv.mobile.ui.screens.bilibili

import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dtv.mobile.model.Platform
import dtv.mobile.model.Streamer
import dtv.mobile.repo.BilibiliCate1
import dtv.mobile.repo.BilibiliCate2
import dtv.mobile.repo.PagedResult
import dtv.mobile.state.AppState
import dtv.mobile.state.CategoryMenuState
import dtv.mobile.state.SubscribedPartition
import dtv.mobile.ui.screens.HomePillItem
import dtv.mobile.ui.screens.PlatformHomeContent
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

private const val PAGE_SIZE = 20

@Composable
fun BilibiliHomeScreen(
  appState: AppState,
  modifier: Modifier = Modifier,
) {
  var categories: List<BilibiliCate1> by remember { mutableStateOf(emptyList()) }
  var selectedCate1: BilibiliCate1? by remember { mutableStateOf(null) }
  var selectedCate2: BilibiliCate2? by remember { mutableStateOf(null) }

  var rooms by remember { mutableStateOf<List<Streamer>>(emptyList()) }
  var loading by remember { mutableStateOf(true) }
  var loadingMore by remember { mutableStateOf(false) }
  var hasMore by remember { mutableStateOf(true) }
  var page by remember { mutableIntStateOf(1) }
  // 加载世代号：切分区 / 下拉刷新会 +1，用来丢弃「旧分区迟到的返回」。
  // 翻页协程与切分类协程互不取消，必须靠它区分这批数据属于哪一次加载。
  var loadGeneration by remember { mutableIntStateOf(0) }

  val gridState = rememberLazyGridState()

  // 顶栏「板块下拉菜单」：把一级分区列表交给 RootScaffold 的 HubTopBar 渲染，
  // 选中后切到对应板块并自动选中其第一个分区。
  DisposableEffect(categories, selectedCate1) {
    if (categories.isNotEmpty() && appState.selectedPlatform == Platform.Bilibili) {
      appState.categoryMenu = CategoryMenuState(
        options = categories.map { it.name },
        selectedIndex = categories.indexOfFirst { it.parentAreaId == selectedCate1?.parentAreaId },
        onSelect = { index ->
          val c1 = categories.getOrNull(index) ?: return@CategoryMenuState
          selectedCate1 = c1
          selectedCate2 = c1.cate2List.firstOrNull()
        },
      )
    }
    onDispose { }
  }

  suspend fun loadPage(reset: Boolean) {
    val cate2 = selectedCate2 ?: return
    val hadItems = rooms.isNotEmpty()
    if (reset) {
      rooms = emptyList()
      hasMore = true
      page = 1
      loadGeneration += 1
    }
    val generation = loadGeneration
    if (!hasMore) return
    if (reset) loading = true else loadingMore = true
    try {
      // 骨架屏最短展示时长改用单调时钟测量：原来直接调 JVM 专属的
      // System.currentTimeMillis()（commonMain 不该有平台依赖），而且测耗时本来
      // 就该用单调时钟，不受系统时间跳变影响。
      val skeletonMark = if (reset && !hadItems) TimeSource.Monotonic.markNow() else null

      val resp: PagedResult<Streamer> = appState.repo.fetchBilibiliLiveList(
        parentAreaId = cate2.parentAreaId,
        areaId = cate2.areaId,
        page = page,
        pageSize = PAGE_SIZE,
      )
      // 世代校验：返回时若用户已切到别的分区，这批数据属于旧分区，必须丢弃。
      // 翻页协程与切分类协程互不取消，否则 reset 清空的 rooms 会被旧分区的一页拼上
      // （新分区里混进上一个分区的直播间），page 游标也跟着错位、后续分页重复漏页。
      if (generation != loadGeneration) return
      val incoming = resp.items
      val old = rooms
      val (merged, addedCount) = if (reset) {
        incoming to incoming.size
      } else {
        val existing = old.asSequence().map { it.roomId }.toHashSet()
        val added = incoming.filter { existing.add(it.roomId) }
        (old + added) to added.size
      }
      rooms = merged
      // 该栏目本页就已装满一页才认为后面还有更多；
      // 不足一页（如斗鱼「语音互动-唱歌」只有 5 个房间）说明已是最后一页。
      hasMore = incoming.size >= PAGE_SIZE && addedCount > 0
      page += 1
      if (skeletonMark != null) {
        val remaining = 180L - skeletonMark.elapsedNow().toLong()
        if (remaining > 0) delay(remaining)
      }
    } finally {
      if (reset) {
        // 只有「当前世代」的那次 reset 才有资格清 loading：被后一次切分类抢走时不能清，
        // 否则会把新一次加载正在显示的加载态提前抹掉。
        if (generation == loadGeneration) {
          loading = false
          appState.platformSwitchLoading = false
        }
      } else {
        loadingMore = false
      }
    }
  }

  LaunchedEffect(Unit) {
    loading = true
    val data = appState.repo.fetchBilibiliCategories()
    categories = data

    val savedId = if (appState.rememberCategoryEnabled) {
      appState.rememberedCategoryId(Platform.Bilibili)
    } else {
      appState.currentPartition
        ?.takeIf { it.platform == Platform.Bilibili }
        ?.id
    }
    val savedParts = savedId?.split(':').orEmpty()
    val savedParentAreaId = savedParts.getOrNull(1)?.toIntOrNull()
    val savedAreaId = savedParts.getOrNull(2)?.toIntOrNull()

    val saved = if (savedParentAreaId != null && savedAreaId != null) {
      data.asSequence().mapNotNull { c1 ->
        val c2 = c1.cate2List.firstOrNull { it.parentAreaId == savedParentAreaId && it.areaId == savedAreaId }
        c2?.let { c1 to it }
      }.firstOrNull()
    } else {
      null
    }

    selectedCate1 = saved?.first ?: data.firstOrNull()
    selectedCate2 = saved?.second ?: selectedCate1?.cate2List?.firstOrNull()
  }

  LaunchedEffect(selectedCate2?.parentAreaId, selectedCate2?.areaId) {
    if (selectedCate2 == null) return@LaunchedEffect
    val partition = SubscribedPartition(
      id = "bilibili:${selectedCate2!!.parentAreaId}:${selectedCate2!!.areaId}",
      name = selectedCate2!!.name,
      platform = Platform.Bilibili,
    )
    appState.currentPartition = partition
    appState.saveRememberedCategory(platform = Platform.Bilibili, id = partition.id)
    loadPage(reset = true)
    gridState.scrollToItem(0)
  }

  val cate2Pills = selectedCate1?.cate2List.orEmpty()
  val currentPartition: SubscribedPartition? = selectedCate2?.let {
    SubscribedPartition(
      id = "bilibili:${it.parentAreaId}:${it.areaId}",
      name = it.name,
      platform = Platform.Bilibili,
    )
  }

  PlatformHomeContent(
    appState = appState,
    currentPartition = currentPartition,
    pills = cate2Pills.map { HomePillItem(key = "${it.parentAreaId}:${it.areaId}", label = it.name) },
    selectedPillKey = selectedCate2?.let { "${it.parentAreaId}:${it.areaId}" },
    onPillClick = { index -> selectedCate2 = cate2Pills.getOrNull(index) },
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
  )
}
