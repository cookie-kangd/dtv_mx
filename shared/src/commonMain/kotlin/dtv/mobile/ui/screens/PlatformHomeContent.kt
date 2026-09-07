package dtv.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dtv.mobile.model.Streamer
import dtv.mobile.state.AppState
import dtv.mobile.state.SubscribedPartition
import dtv.mobile.ui.DockContentClearance
import dtv.mobile.ui.components.CategoryPill
import dtv.mobile.ui.components.LazyGridLoadMoreEffect
import dtv.mobile.ui.components.LocalCardMetrics
import dtv.mobile.ui.components.PullToRefreshBox
import dtv.mobile.ui.components.StreamerCard
import dtv.mobile.ui.components.StreamerCardSkeleton
import kotlinx.coroutines.launch

/** 平台首页分类胶囊条的单个条目；key 需在当前列表内唯一且稳定（用于 LazyRow 复用）。 */
data class HomePillItem(
  val key: String,
  val label: String,
)

/**
 * 四个平台（斗鱼/虎牙/抖音/B站）首页共用的 UI 骨架：
 * 分类胶囊条 + 可选附加行（如斗鱼三级分类）+ 直播间网格 + 底部加载提示 + 下拉刷新。
 *
 * 各平台的差异全部在数据层（分区模型 / 拉取接口 / 翻页方式），由各自的
 * XxxHomeScreen 处理；界面样式调整只改本组件即可全局生效。
 */
@Composable
fun PlatformHomeContent(
  appState: AppState,
  currentPartition: SubscribedPartition?,
  pills: List<HomePillItem>,
  selectedPillKey: String?,
  onPillClick: (Int) -> Unit,
  rooms: List<Streamer>,
  loading: Boolean,
  loadingMore: Boolean,
  hasMore: Boolean,
  gridState: LazyGridState,
  onRefresh: suspend () -> Unit,
  onLoadMore: suspend () -> Unit,
  modifier: Modifier = Modifier,
  aboveGrid: (@Composable ColumnScope.() -> Unit)? = null,
) {
  val cardMetrics = LocalCardMetrics.current
  val scope = rememberCoroutineScope()
  var refreshing by remember { mutableStateOf(false) }

  // 胶囊条滚动位置：进直播间时整页会被销毁，LazyListState 随之丢失，
  // 因此把位置按「平台」粒度存进 AppState，返回后原样还原。
  // v0.2.11 修复：key 绝不能包含分区 id —— 点胶囊本身就会切换分区
  // （如 douyu:c2:<id>），若 key 随分区变化，remember 会重建 LazyListState，
  // 滚动位置直接跳回第一个胶囊（v0.2.10「越改越糟」的根因）。
  // 按平台粒度共享一份位置：点胶囊切换分类时 state 保持不动，滚动在哪就在哪。
  val pillPlatformKey = appState.selectedPlatform.name
  val savedPillScroll = remember(pillPlatformKey) { appState.pillScrollPosition(pillPlatformKey) }
  // state 只随平台切换重建，绝不随分区/分类变化重建。
  // 构造时不在初始化参数里给位置（各版本工厂签名有差异），统一由下方恢复 effect 定位。
  val pillListState = remember(pillPlatformKey) { LazyListState() }
  // 返回页面时分类列表是异步填充的，列表为空的瞬间 LazyRow 会把 index 钳到 0，
  // 若此时就回写，会把真实位置覆盖成 (0,0)。
  // 因此：恢复动作等列表非空后再执行一次；列表非空前绝不回写。
  var pillRestored by remember(pillPlatformKey) { mutableStateOf(false) }
  LaunchedEffect(pillListState, pillPlatformKey, pills.size) {
    if (pills.isEmpty()) return@LaunchedEffect
    if (!pillRestored) {
      val saved = savedPillScroll
      if (saved != null && saved.first > 0 && saved.first < pills.size) {
        runCatching { pillListState.scrollToItem(saved.first, saved.second) }
      }
      pillRestored = true
    }
    snapshotFlow {
      pillListState.firstVisibleItemIndex to pillListState.firstVisibleItemScrollOffset
    }.collect { (index, offset) ->
      appState.savePillScrollPosition(pillPlatformKey, index, offset)
    }
  }

  LazyGridLoadMoreEffect(
    gridState = gridState,
    enabled = !loading && !loadingMore && hasMore,
    itemCount = rooms.size,
  ) {
    onLoadMore()
  }

  PullToRefreshBox(
    refreshing = refreshing,
    onRefresh = {
      if (refreshing) return@PullToRefreshBox
      scope.launch {
        refreshing = true
        runCatching { onRefresh() }
        refreshing = false
      }
    },
    modifier = modifier.fillMaxSize(),
  ) {
    Column(modifier = Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 6.dp)) {
      // 具体分类胶囊条：一级分区（板块）已迁移到顶栏右侧下拉菜单，
      // 这里平铺展示当前板块下的具体分类，选中项以全局主题色高亮。
      // 分类很多的平台（如 Twitch）改为把全部分类收进顶栏下拉菜单，
      // pills 传空列表即整体隐藏这一行，省去左右滑动找分类的麻烦。
      if (pills.isNotEmpty()) {
        LazyRow(
          state = pillListState,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          items(pills.size, key = { pills[it].key }) { index ->
            CategoryPill(
              label = pills[index].label,
              selected = pills[index].key == selectedPillKey,
              onClick = { onPillClick(index) },
            )
          }
        }
        Spacer(modifier = Modifier.height(4.dp))
      }
      aboveGrid?.invoke(this)
      Spacer(modifier = Modifier.height(8.dp))

      LazyVerticalGrid(
        modifier = Modifier.fillMaxSize(),
        state = gridState,
        columns = GridCells.Fixed(cardMetrics.columns),
        contentPadding = PaddingValues(
          bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
            DockContentClearance,
        ),
        verticalArrangement = Arrangement.spacedBy(cardMetrics.gridSpacing),
        horizontalArrangement = Arrangement.spacedBy(cardMetrics.gridSpacing),
      ) {
        if (loading || appState.platformSwitchLoading) {
          items(6, span = { GridItemSpan(1) }, contentType = { "skeleton" }) {
            StreamerCardSkeleton()
          }
        } else {
          items(rooms.size, key = { rooms[it].roomId }, span = { GridItemSpan(1) }, contentType = { "streamer" }) { index ->
            val streamer = rooms[index]
            StreamerCard(
              streamer = streamer,
              followed = appState.isFollowed(streamer),
              onClick = { appState.openPlayer(streamer, partition = currentPartition) },
              onToggleFollow = { appState.toggleFollow(streamer) },
            )
          }

          item(span = { GridItemSpan(cardMetrics.columns) }) {
            // 底部提示单独占满一整行并居中；没有更多数据时不再显示任何提示
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
              contentAlignment = Alignment.Center,
            ) {
              when {
                loadingMore -> Text("加载更多…", style = MaterialTheme.typography.bodyMedium)
                hasMore -> Text(
                  "继续滑动加载更多",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
                else -> Text(
                  "无更多直播间",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
              }
            }
          }
        }
      }
    }
  }
}
