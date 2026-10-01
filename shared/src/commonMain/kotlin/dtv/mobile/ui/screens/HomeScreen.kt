package dtv.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.zIndex
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dtv.mobile.state.AppState
import dtv.mobile.ui.DockContentClearance
import dtv.mobile.ui.components.LocalCardMetrics
import dtv.mobile.ui.components.HomeStreamerCard
import dtv.mobile.ui.components.PullToRefreshBox
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
  appState: AppState,
  modifier: Modifier = Modifier,
) {
  val items = appState.followedStreamers
  // 注意不能 remember(items)：followedStreamers 是 mutableStateListOf，
  // 增删/更新都是原地变更、引用永不变，remember 的 key 永远相同会把
  // 「在播优先」排序永久缓存成旧值（首页不再跟随列表变化刷新）。
  // 这里 n 就是关注数量级（几十），每次重组重算一遍 toList+filter 开销可忽略。
  val displayItems = run {
    val snapshot = items.toList()
    val live = snapshot.filter { it.isLive }
    val offline = snapshot.filterNot { it.isLive }
    live + offline
  }
  val gridItems = displayItems
  var refreshing by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val gridState = rememberLazyGridState()
  val cardMetrics = LocalCardMetrics.current

  var draggingKey by remember { mutableStateOf<String?>(null) }
  var dragOffset by remember { mutableStateOf(Offset.Zero) }
  // 拖拽手势的 pointerInput key 只能认 itemKey：绝不能再把「每交换一次位置就自增的计数」
  // 塞进 key —— key 一变手势协程就被取消重建，onDragCancel 立刻清空拖拽状态，
  // 长按拖动只要成功交换过一次位置就会「掉手」，表现为一次长按只能挪一格。
  // 手势闭包捕获的是创建时的列表快照，所以最新数据改用 rememberUpdatedState 读取。
  val gridItemsRef = rememberUpdatedState(gridItems)
  val itemsRef = rememberUpdatedState(items)

  if (items.isEmpty()) {
    // 空状态：图标徽章 + 主文案 + 引导副文案，对齐主流 App 的空态样式
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 32.dp),
      ) {
        Box(
          modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Default.FavoriteBorder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(34.dp),
          )
        }
        Text(
          text = "还没有关注的主播",
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = "去底部切换到斗鱼 / 虎牙 / 抖音 / B站，搜索主播并点爱心关注，开播就会出现在这里",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
          textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
      }
    }
    return
  }

  PullToRefreshBox(
    refreshing = refreshing,
    onRefresh = {
      if (refreshing) return@PullToRefreshBox
      scope.launch {
        refreshing = true
        runCatching { appState.refreshFollowedStreamerCards() }
        refreshing = false
      }
    },
    modifier = modifier.fillMaxSize(),
  ) {
    LazyVerticalGrid(
      modifier = Modifier.fillMaxSize(),
      state = gridState,
      columns = GridCells.Fixed(cardMetrics.columns),
      contentPadding = PaddingValues(
        start = 18.dp,
        end = 18.dp,
        top = 4.dp,
        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + DockContentClearance,
      ),
      horizontalArrangement = Arrangement.spacedBy(if (cardMetrics.compact) 10.dp else 16.dp),
      verticalArrangement = Arrangement.spacedBy(if (cardMetrics.compact) 10.dp else 14.dp),
    ) {
      items(gridItems, key = { "${it.platform}-${it.roomId}" }, contentType = { "streamer" }) { streamer ->
        val itemKey = "${streamer.platform}-${streamer.roomId}"
        val isDragging = draggingKey == itemKey
        HomeStreamerCard(
          streamer = streamer,
          followed = true,
          onClick = { if (draggingKey == null) appState.openPlayer(streamer) },
          onToggleFollow = { appState.toggleFollow(streamer) },
          modifier = Modifier
            .then(
              if (isDragging) {
                Modifier
                  .zIndex(2f)
                  .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
              } else {
                Modifier
              },
            )
            .pointerInput(itemKey) {
              detectDragGesturesAfterLongPress(
                onDragStart = {
                  draggingKey = itemKey
                  dragOffset = Offset.Zero
                },
                onDragCancel = {
                  draggingKey = null
                  dragOffset = Offset.Zero
                },
                onDragEnd = {
                  draggingKey = null
                  dragOffset = Offset.Zero
                },
                onDrag = { change, dragAmount ->
                  change.consume()
                  if (draggingKey != itemKey) return@detectDragGesturesAfterLongPress

                  dragOffset += dragAmount

                  val fromIndex = gridItemsRef.value.indexOfFirst { "${it.platform}-${it.roomId}" == itemKey }
                  if (fromIndex < 0) return@detectDragGesturesAfterLongPress

                  val draggingInfo = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == fromIndex }
                    ?: return@detectDragGesturesAfterLongPress
                  val draggingStreamer = gridItemsRef.value.getOrNull(fromIndex) ?: return@detectDragGesturesAfterLongPress
                  val draggingIsLive = draggingStreamer.isLive

                  val draggingCenter = Offset(
                    x = draggingInfo.offset.x + dragOffset.x + draggingInfo.size.width / 2f,
                    y = draggingInfo.offset.y + dragOffset.y + draggingInfo.size.height / 2f,
                  )

                  val targetInfo = gridState.layoutInfo.visibleItemsInfo
                    .asSequence()
                    .filter { it.index in 0..gridItemsRef.value.lastIndex }
                    .firstOrNull { info ->
                      if (info.index == draggingInfo.index) return@firstOrNull false
                      val idx = info.index
                      val s = gridItemsRef.value.getOrNull(idx) ?: return@firstOrNull false
                      if (s.isLive != draggingIsLive) return@firstOrNull false
                      val left = info.offset.x.toFloat()
                      val top = info.offset.y.toFloat()
                      val right = left + info.size.width
                      val bottom = top + info.size.height
                      draggingCenter.x in left..right && draggingCenter.y in top..bottom
                    }

                  val toIndex = targetInfo?.index ?: return@detectDragGesturesAfterLongPress
                  if (toIndex == fromIndex) return@detectDragGesturesAfterLongPress
                  val targetStreamer = gridItemsRef.value.getOrNull(toIndex) ?: return@detectDragGesturesAfterLongPress

                  val diff = Offset(
                    x = (draggingInfo.offset.x - targetInfo.offset.x).toFloat(),
                    y = (draggingInfo.offset.y - targetInfo.offset.y).toFloat(),
                  )

                  val fromBase = itemsRef.value.indexOfFirst { "${it.platform}-${it.roomId}" == itemKey }
                  val toBase = itemsRef.value.indexOfFirst { "${it.platform}-${it.roomId}" == "${targetStreamer.platform}-${targetStreamer.roomId}" }
                  if (fromBase < 0 || toBase < 0) return@detectDragGesturesAfterLongPress
                  appState.moveFollowedStreamer(fromIndex = fromBase, toIndex = toBase)
                  dragOffset += diff
                },
              )
            },
        )
      }

      item(span = { GridItemSpan(cardMetrics.columns) }) {
        Spacer(modifier = Modifier.height(8.dp))
      }
    }
  }
}

// (removed) end-of-feed footer
