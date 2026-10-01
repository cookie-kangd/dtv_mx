package dtv.mobile.ui.screens.douyu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import dtv.mobile.model.Platform
import dtv.mobile.model.Streamer
import dtv.mobile.repo.DouyuCate1
import dtv.mobile.repo.DouyuCate2
import dtv.mobile.repo.DouyuCate3
import dtv.mobile.repo.PagedResult
import dtv.mobile.state.AppState
import dtv.mobile.state.CategoryMenuState
import dtv.mobile.state.SubscribedPartition
import dtv.mobile.ui.components.CategoryPill
import dtv.mobile.ui.screens.HomePillItem
import dtv.mobile.ui.screens.PlatformHomeContent
import kotlinx.coroutines.delay

private const val PAGE_SIZE = 20

@Composable
fun DouyuHomeScreen(
  appState: AppState,
  modifier: Modifier = Modifier,
) {
  var categories: List<DouyuCate1> by remember { mutableStateOf(emptyList()) }
  var selectedCate1: DouyuCate1? by remember { mutableStateOf(null) }
  var selectedCate2: DouyuCate2? by remember { mutableStateOf(null) }
  var cate3List: List<DouyuCate3> by remember { mutableStateOf(emptyList()) }
  var selectedCate3: DouyuCate3? by remember { mutableStateOf(null) }
  var pendingCate3Id by remember { mutableStateOf<String?>(null) }

  var rooms: List<Streamer> by remember { mutableStateOf(emptyList()) }
  var loading by remember { mutableStateOf(true) }
  var loadingMore by remember { mutableStateOf(false) }
  var hasMore by remember { mutableStateOf(true) }
  var page by remember { mutableStateOf(0) } // cate2 uses offset; cate3 uses page+1
  // 加载世代号：切分类 / 切换二级分类 / 下拉刷新会 +1，用来丢弃「旧分区迟到的返回」。
  // 翻页协程与切分类协程互不取消，必须靠它区分这批数据属于哪一次加载。
  var loadGeneration by remember { mutableIntStateOf(0) }

  val gridState = rememberLazyGridState()

  LaunchedEffect(Unit) {
    loading = true
    val data = appState.repo.fetchDouyuCategories()
    categories = data.cate1List

    val savedId = if (appState.rememberCategoryEnabled) {
      appState.rememberedCategoryId(Platform.Douyu)
    } else {
      appState.currentPartition
        ?.takeIf { it.platform == Platform.Douyu }
        ?.id
    }
    val savedParts = savedId?.split(':').orEmpty()
    val savedType = savedParts.getOrNull(1).orEmpty()
    val savedC2Id = savedParts.getOrNull(2).orEmpty()
    pendingCate3Id = if (savedType == "c2" && savedParts.getOrNull(3) == "c3") savedParts.getOrNull(4) else null

    val savedCate2 = if (savedType == "c2" && savedC2Id.isNotBlank()) {
      data.cate1List.asSequence().mapNotNull { c1 ->
        val c2 = c1.cate2List.firstOrNull { it.id == savedC2Id }
        c2?.let { c1 to it }
      }.firstOrNull()
    } else {
      null
    }

    selectedCate1 = savedCate2?.first ?: data.cate1List.firstOrNull()
    selectedCate2 = savedCate2?.second ?: selectedCate1?.cate2List?.firstOrNull()
    loading = false
  }

  LaunchedEffect(selectedCate2?.id) {
    val cate2Id = selectedCate2?.id ?: return@LaunchedEffect
    cate3List = appState.repo.fetchDouyuThreeCate(cate2Id)
    val restoredCate3 = pendingCate3Id
      ?.takeIf { it.isNotBlank() }
      ?.let { id -> cate3List.firstOrNull { it.id == id } }
    pendingCate3Id = null
    selectedCate3 = restoredCate3
  }

  // 顶栏「板块下拉菜单」：把一级分区列表交给 RootScaffold 的 HubTopBar 渲染，
  // 选中后切到对应板块并自动选中其第一个分类。
  DisposableEffect(categories, selectedCate1) {
    if (categories.isNotEmpty() && appState.selectedPlatform == Platform.Douyu) {
      appState.categoryMenu = CategoryMenuState(
        options = categories.map { it.name },
        selectedIndex = categories.indexOfFirst { it.id == selectedCate1?.id },
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
    if (reset) {
      rooms = emptyList()
      hasMore = true
      page = 0
      loadGeneration += 1
    }
    val generation = loadGeneration
    if (!hasMore) return

    if (reset) loading = true else loadingMore = true
    try {
      val result: PagedResult<Streamer> = if (selectedCate3 != null) {
        appState.repo.fetchDouyuLiveListByCate3(
          cate3Id = selectedCate3!!.id,
          page = (page + 1).coerceAtLeast(1),
          limit = PAGE_SIZE,
        )
      } else {
        appState.repo.fetchDouyuLiveListByCate2(
          cate2Id = cate2.id,
          offset = page * PAGE_SIZE,
          limit = PAGE_SIZE,
        )
      }

      // 世代校验：返回时若用户已切到别的分类，这批数据属于旧分区，必须丢弃。
      // 翻页协程与切分类协程互不取消，否则 reset 清空的 rooms 会被旧分区的一页拼上
      // （新分类里混进上一个分类的直播间），page 游标也跟着错位、后续分页重复漏页。
      if (generation != loadGeneration) return
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

      // 该栏目本页就已装满一页才认为后面还有更多；
      // 不足一页（如斗鱼「语音互动-唱歌」只有 5 个房间）说明已是最后一页。
      hasMore = incoming.size >= PAGE_SIZE && addedCount > 0
      page += 1
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

  LaunchedEffect(selectedCate2?.id, selectedCate3?.id) {
    if (selectedCate2 == null) return@LaunchedEffect
    val partition = when {
      selectedCate3 != null -> SubscribedPartition(
        id = "douyu:c3:${selectedCate3!!.id}",
        name = selectedCate3!!.name,
        platform = Platform.Douyu,
      )
      else -> SubscribedPartition(
        id = "douyu:c2:${selectedCate2!!.id}",
        name = selectedCate2!!.name,
        platform = Platform.Douyu,
      )
    }
    appState.currentPartition = partition
    val rememberedId = if (selectedCate3 != null) {
      "douyu:c2:${selectedCate2!!.id}:c3:${selectedCate3!!.id}"
    } else {
      "douyu:c2:${selectedCate2!!.id}"
    }
    appState.saveRememberedCategory(platform = Platform.Douyu, id = rememberedId)
    // small debounce to avoid double refresh when cate2 -> cate3 list updates
    delay(60)
    loadPage(reset = true)
    gridState.scrollToItem(0)
  }

  val cate2Pills = selectedCate1?.cate2List.orEmpty()
  val currentPartition: SubscribedPartition? = when {
    selectedCate3 != null -> SubscribedPartition(
      id = "douyu:c3:${selectedCate3!!.id}",
      name = selectedCate3!!.name,
      platform = Platform.Douyu,
    )
    selectedCate2 != null -> SubscribedPartition(
      id = "douyu:c2:${selectedCate2!!.id}",
      name = selectedCate2!!.name,
      platform = Platform.Douyu,
    )
    else -> null
  }

  PlatformHomeContent(
    appState = appState,
    currentPartition = currentPartition,
    pills = cate2Pills.map { HomePillItem(key = it.id, label = it.name) },
    selectedPillKey = selectedCate2?.id,
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
    aboveGrid = if (cate3List.isNotEmpty()) {
      {
        Cate3Chips(
          cate3List = cate3List,
          selectedId = selectedCate3?.id,
          onSelect = { selectedCate3 = it },
        )
      }
    } else {
      null
    },
  )
}

@Composable
private fun Cate3Chips(
  cate3List: List<DouyuCate3>,
  selectedId: String?,
  onSelect: (DouyuCate3?) -> Unit,
) {
  LazyRow(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    item {
      CategoryPill(
        label = "全部",
        selected = selectedId == null,
        onClick = { onSelect(null) },
      )
    }
    items(cate3List, key = { it.id }) { c3 ->
      CategoryPill(
        label = c3.name,
        selected = c3.id == selectedId,
        onClick = { onSelect(c3) },
      )
    }
  }
}
