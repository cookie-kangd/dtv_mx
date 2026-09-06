package dtv.mobile.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeChild
import dtv.mobile.model.Platform
import dtv.mobile.state.Screen

/** 底栏浮岛在屏幕上占据的净高度（不含导航条），列表 contentPadding 用它预留空间。 */
val DockContentClearance = 96.dp

@Composable
fun PlatformBottomBar(
  hazeState: HazeState,
  selectedScreen: Screen,
  selectedPlatform: Platform,
  onHomeClick: () -> Unit,
  onPlatformClick: (Platform) -> Unit,
  switchingLoading: Boolean = false,
  // 由「设置-平台设置」决定：只包含已启用的平台，且按用户拖拽后的顺序排列
  platforms: List<Platform> = Platform.entries.filter { it != Platform.Custom },
) {
  val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
  // 毛玻璃 tint：浅色模式用偏白的半透明、深色模式用偏黑的半透明，
  // blur 由 haze 完成后叠加这层色彩，让浮岛在两种模式下都保持可读性。
  val glassTint = if (isDark) {
    Color(0xFF1C1C22).copy(alpha = 0.58f)
  } else {
    Color(0xFFF6F7FB).copy(alpha = 0.60f)
  }
  val glassStyle = HazeStyle(tint = glassTint, blurRadius = 24.dp, noiseFactor = 0.06f)
  val dockShape = RoundedCornerShape(percent = 50)
  // 描边要能明确看出是「一块玻璃」：浅色模式用高亮白边（靠阴影与背景拉开层次），
  // 深色模式用偏白的亮边勾出玻璃轮廓，比原来的暗色描边明显得多。
  val borderColor = if (isDark) Color.White.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.90f)
  // 玻璃高光：自上而下的白色渐变，是玻璃质感的关键（配合描边一起才像毛玻璃）
  val glassHighlight = Brush.verticalGradient(
    colors = if (isDark) {
      listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.02f), Color.Transparent)
    } else {
      listOf(Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0.10f), Color.Transparent)
    },
  )
  val activeBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
  // 深色底用亮灰、浅色底用深灰，保证两种模式下的对比度（此前两个值写反了）
  val inactiveIcon = if (isDark) Color(0xFF9CA3AF) else Color(0xFF6B7280)

    Box(
    modifier = Modifier
      .fillMaxWidth()
      .navigationBarsPadding()
      .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .shadow(elevation = if (isDark) 10.dp else 16.dp, shape = dockShape, clip = false)
        .hazeChild(hazeState, shape = dockShape, style = glassStyle)
        .background(brush = glassHighlight, shape = dockShape)
        .border(BorderStroke(1.6.dp, borderColor), dockShape)
        .padding(vertical = 4.dp),
      horizontalArrangement = Arrangement.SpaceAround,
    ) {
      DockItem(
        selected = selectedScreen == Screen.Home,
        label = "首页",
        activeBackground = activeBg,
        onClick = onHomeClick.takeUnless { switchingLoading } ?: {},
      ) {
        Icon(
          imageVector = Icons.Default.Home,
          contentDescription = "首页",
          tint = if (selectedScreen == Screen.Home) MaterialTheme.colorScheme.primary else inactiveIcon,
        )
      }

      platforms.forEach { platform ->
        val isSelected = selectedScreen == Screen.Platform && platform == selectedPlatform
        DockItem(
          selected = isSelected,
          label = platform.title,
          activeBackground = activeBg,
          onClick = { if (!switchingLoading) onPlatformClick(platform) },
        ) {
          val icon = when (platform) {
            Platform.Douyu -> Icons.Default.WaterDrop
            Platform.Huya -> Icons.Default.Pets
            Platform.Douyin -> Icons.Default.MusicNote
            Platform.Bilibili -> Icons.Default.LiveTv
            Platform.Twitch -> Icons.Default.SportsEsports
            Platform.Custom -> Icons.Default.Home
          }
          Icon(
            imageVector = icon,
            contentDescription = platform.title,
            tint = if (isSelected) MaterialTheme.colorScheme.primary else inactiveIcon,
          )
        }
      }
    }
  }
}

@Composable
private fun RowScope.DockItem(
  selected: Boolean,
  label: String,
  activeBackground: Color,
  onClick: () -> Unit,
  icon: @Composable () -> Unit,
) {
  val scale = animateFloatAsState(targetValue = if (selected) 1.06f else 1.0f, label = "dockScale").value
  val bgAlpha = animateFloatAsState(targetValue = if (selected) 1.0f else 0.0f, label = "dockBgAlpha").value
  // 去掉默认的矩形水波纹：此前点击/切换栏目时整个格子会闪出一块方形灰底，
  // 在胶囊浮岛里非常突兀。切换反馈改由选中态的胶囊高亮 + 图标/文字变色表达。
  val interactionSource = remember { MutableInteractionSource() }

  Column(
    modifier = Modifier
      .weight(1f)
      .clickable(interactionSource = interactionSource, indication = null) { onClick() }
      .defaultMinSize(minHeight = 48.dp)
      .padding(vertical = 1.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    androidx.compose.material3.Surface(
      shape = RoundedCornerShape(percent = 50),
      color = activeBackground.copy(alpha = activeBackground.alpha * bgAlpha),
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
    ) {
      Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)) {
        Box(modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale)) {
          icon()
        }
      }
    }

    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 10.sp),
      color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
