package dtv.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/** 下拉菜单内容的接收作用域：在 [ColumnScope] 之上增加「登记条目位置」的能力。 */
class MenuScope internal constructor(
  scope: ColumnScope,
  private val itemTops: MutableMap<Int, Int>,
  private val scrollState: androidx.compose.foundation.ScrollState,
) : ColumnScope by scope {

  /**
   * 给菜单条目标记序号，登记它在内容区里的纵向位置。
   * 只在首次布局（滚动值为 0）时记录，滚动开始后不再更新，
   * 这样记录值始终是「内容区坐标」，可直接换算滚动目标。
   */
  fun Modifier.menuItemTop(index: Int): Modifier = onGloballyPositioned { coords ->
    if (scrollState.value == 0) {
      itemTops[index] = coords.positionInParent().y.roundToInt()
    }
  }
}

/**
 * 统一的圆角下拉菜单（替代 material3 默认 4dp 小圆角的 DropdownMenu）。
 *
 * 用法：放在锚点 Box 内（与触发按钮同层），面板右对齐锚点右上角、
 * 向下偏移 [offsetY] 展开为一个大圆角卡片（描边 + 阴影）。
 * 点击面板外部 / 按返回键自动收起（PopupProperties 原生行为）。
 *
 * [maxHeight] 传入时菜单限高并在内部滚动；此时可再传 [selectedIndex]，
 * 打开菜单后自动把当前选中项滚动到视口上部——选项很多的平台
 * （如分类较多的海外平台）不用每次都从头滑到底。
 *
 * [focusable] 默认 true。面板需要「抢焦点」时（如分类下拉）保持默认；
 * 面板与输入框同时存在的场景（如搜索联想面板）必须传 false——
 * 可获取焦点的 Popup 会把焦点从文本框抢走，表现为「输入第一个字符后
 * 输入法自动收起、后续字符全部丢失」。
 */
@Composable
fun RoundedDropdownMenu(
  expanded: Boolean,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  offsetY: Dp = 6.dp,
  width: Dp? = null,
  maxHeight: Dp? = null,
  selectedIndex: Int? = null,
  focusable: Boolean = true,
  content: @Composable MenuScope.() -> Unit,
) {
  if (!expanded) return
  val offsetYpx = with(LocalDensity.current) { offsetY.roundToPx() }
  val density = LocalDensity.current
  val scrollState = rememberScrollState()
  val itemTops = mutableStateMapOf<Int, Int>()

  // 打开后自动定位：等首次布局完成（条目位置与最大滚动值就绪），
  // 把选中项滚到视口上部留一点上下文，静默定位不做动画。
  LaunchedEffect(selectedIndex) {
    if (selectedIndex == null || selectedIndex < 0) return@LaunchedEffect
    snapshotFlow { scrollState.maxValue > 0 && itemTops.containsKey(selectedIndex) }
      .filter { it }
      .first()
    val top = itemTops[selectedIndex] ?: return@LaunchedEffect
    val target = (top - with(density) { 56.dp.toPx() }).toInt()
      .coerceIn(0, scrollState.maxValue)
    scrollState.scrollTo(target)
  }

  Popup(
    alignment = Alignment.TopEnd,
    offset = IntOffset(0, offsetYpx),
    onDismissRequest = onDismissRequest,
    properties = PopupProperties(
      focusable = focusable,
      dismissOnBackPress = focusable,
      dismissOnClickOutside = true,
    ),
  ) {
    Surface(
      modifier = modifier.then(
        if (width != null) Modifier.width(width) else Modifier.widthIn(min = 132.dp),
      ),
      shape = RoundedCornerShape(16.dp),
      // 毛玻璃质感：Popup 是独立窗口拿不到 haze 实时模糊，用「半透明底 +
      // 玻璃高光渐变 + 亮描边」模拟玻璃面板，与底栏浮岛观感一致。
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
      tonalElevation = 0.dp,
      shadowElevation = 8.dp,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
    ) {
      Column(
        modifier = Modifier
          .background(
            brush = Brush.verticalGradient(
              colors = listOf(
                Color.White.copy(alpha = 0.10f),
                Color.White.copy(alpha = 0.03f),
                Color.Transparent,
              ),
            ),
          )
          .padding(vertical = 6.dp)
          .then(
            if (maxHeight != null) {
              Modifier.heightIn(max = maxHeight).verticalScroll(scrollState)
            } else {
              Modifier
            },
          ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = {
          val menuScope = MenuScope(this, itemTops, scrollState)
          menuScope.content()
        },
      )
    }
  }
}
