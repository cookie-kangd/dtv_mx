package dtv.mobile.ui.screens.douyin

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
import dtv.mobile.repo.DouyinCate1
import dtv.mobile.repo.DouyinCate2
import dtv.mobile.repo.PagedResult
import dtv.mobile.state.AppState
import dtv.mobile.state.CategoryMenuState
import dtv.mobile.state.SubscribedPartition
import dtv.mobile.ui.screens.HomePillItem
import dtv.mobile.ui.screens.PlatformHomeContent
import kotlin.random.Random

private const val PAGE_SIZE = 15

private fun generateMsToken(length: Int = 107): String {
  val charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
  return buildString(length) {
    repeat(length) { append(charset[Random.nextInt(charset.length)]) }
  }
}

@Composable
fun DouyinHomeScreen(
  appState: AppState,
  modifier: Modifier = Modifier,
) {
  var categories: List<DouyinCate1> by remember { mutableStateOf(emptyList()) }
  var selectedCate1: DouyinCate1? by remember { mutableStateOf(null) }
  var selectedCate2: DouyinCate2? by remember { mutableStateOf(null) }

  var rooms by remember { mutableStateOf<List<Streamer>>(emptyList()) }
  var loading by remember { mutableStateOf(true) }
  var loadingMore by remember { mutableStateOf(false) }
  var hasMore by remember { mutableStateOf(true) }
  var offset by remember { mutableIntStateOf(0) }
  var msToken by remember { mutableStateOf(generateMsToken()) }
  // 加载世代号：切分类 / 下拉刷新会 +1，用来丢弃「旧分区迟到的返回」。
  // 翻页协程与切分类协程是两条互不取消的链，必须靠它区分数据属于哪一次加载。
  var loadGeneration by remember { mutableIntStateOf(0) }

  val gridState = rememberLazyGridState()

  // 顶栏「板块下拉菜单」：把一级分区列表交给 RootScaffold 的 HubTopBar 渲染，
  // 选中后切到对应板块并自动选中其第一个分类。
  DisposableEffect(categories, selectedCate1) {
    if (categories.isNotEmpty() && appState.selectedPlatform == Platform.Douyin) {
      appState.categoryMenu = CategoryMenuState(
        options = categories.map { it.name },
        selectedIndex = categories.indexOfFirst { it.name == selectedCate1?.name },
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
      offset = 0
      msToken = generateMsToken()
      loadGeneration += 1
    }
    val generation = loadGeneration
    if (!hasMore) return

    if (reset) loading = true else loadingMore = true
    try {
      val resp: PagedResult<Streamer> = appState.repo.fetchDouyinPartitionLiveList(
        partition = cate2.partition,
        partitionType = cate2.partitionType,
        offset = offset,
        limit = PAGE_SIZE,
        msToken = msToken,
      )
      // 世代校验：返回时若用户已切到别的分类（或又下拉刷新过一次），这批数据属于旧分区，
      // 必须丢弃。翻页协程与切分类协程互不取消，否则 reset 清空的 rooms 会被旧分区的
      // 一页拼上（新分类里混进上一个分类的直播间），offset 也跟着错位、后续分页重复漏页。
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
      offset += incoming.size
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
    try {
      val data = appState.repo.fetchDouyinCategories()
      categories = data

      val savedId = if (appState.rememberCategoryEnabled) {
        appState.rememberedCategoryId(Platform.Douyin)
      } else {
        appState.currentPartition
          ?.takeIf { it.platform == Platform.Douyin }
          ?.id
      }
      val savedParts = savedId?.split(':').orEmpty()
      val savedPartitionType = savedParts.getOrNull(1).orEmpty()
      val savedPartition = savedParts.getOrNull(2).orEmpty()

      val saved = if (savedPartitionType.isNotBlank() && savedPartition.isNotBlank()) {
        data.asSequence().mapNotNull { c1 ->
          val c2 = c1.cate2List.firstOrNull { it.partitionType == savedPartitionType && it.partition == savedPartition }
          c2?.let { c1 to it }
        }.firstOrNull()
      } else {
        null
      }

      selectedCate1 = saved?.first ?: data.firstOrNull()
      selectedCate2 = saved?.second ?: selectedCate1?.cate2List?.firstOrNull()
    } finally {
      loading = false
    }
  }

  LaunchedEffect(selectedCate2?.partition, selectedCate2?.partitionType) {
    if (selectedCate2 == null) return@LaunchedEffect
    val partition = SubscribedPartition(
      id = "douyin:${selectedCate2!!.partitionType}:${selectedCate2!!.partition}",
      name = selectedCate2!!.name,
      platform = Platform.Douyin,
    )
    appState.currentPartition = partition
    appState.saveRememberedCategory(platform = Platform.Douyin, id = partition.id)
    loadPage(reset = true)
    gridState.scrollToItem(0)
  }

  val cate2Pills = selectedCate1?.cate2List.orEmpty()
  val currentPartition: SubscribedPartition? = selectedCate2?.let {
    SubscribedPartition(
      id = "douyin:${it.partitionType}:${it.partition}",
      name = it.name,
      platform = Platform.Douyin,
    )
  }

  PlatformHomeContent(
    appState = appState,
    currentPartition = currentPartition,
    pills = cate2Pills.map { HomePillItem(key = "${it.partitionType}:${it.partition}", label = it.name) },
    selectedPillKey = selectedCate2?.let { "${it.partitionType}:${it.partition}" },
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
