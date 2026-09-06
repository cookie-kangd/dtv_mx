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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * 统一的圆角下拉菜单（替代 material3 默认 4dp 小圆角的 DropdownMenu）。
 *
 * 用法：放在锚点 Box 内（与触发按钮同层），面板右对齐锚点右上角、
 * 向下偏移 [offsetY] 展开为一个大圆角卡片（描边 + 阴影）。
 * 点击面板外部 / 按返回键自动收起（PopupProperties 原生行为）。
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
  focusable: Boolean = true,
  content: @Composable ColumnScope.() -> Unit,
) {
  if (!expanded) return
  val offsetYpx = with(LocalDensity.current) { offsetY.roundToPx() }
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
              Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())
            } else {
              Modifier
            },
          ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
      )
    }
  }
}
