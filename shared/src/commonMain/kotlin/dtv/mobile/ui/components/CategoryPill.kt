package dtv.mobile.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun CategoryPill(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
  val selectedBg = MaterialTheme.colorScheme.primary
  val selectedFg = Color.Black
  // 与 Colors.kt 的冷色深色盘对齐（NightBgTertiary = 0xFF1A2230）；
  // 半透明以适配悬浮毛玻璃底栏的玻璃质感
  val idleBg = if (isDark) Color(0xFF1A2230).copy(alpha = 0.72f) else Color(0xFFE5E7EB).copy(alpha = 0.72f)
  val idleFg = if (isDark) Color(0xFF9CA3AF) else Color(0xFF6B7280)

  // 按压缩放反馈：与直播间卡片手感一致（仅在按压瞬间有动画，空闲零开销）
  val interactionSource = remember { MutableInteractionSource() }
  val pressed by interactionSource.collectIsPressedAsState()
  val pressScale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "pillPressScale")

  Surface(
    modifier = modifier
      .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
      .clip(CircleShape)
      .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    shape = CircleShape,
    color = if (selected) selectedBg else idleBg,
    tonalElevation = 0.dp,
    shadowElevation = if (selected) 6.dp else 0.dp,
  ) {
    Box(
      modifier = Modifier
        .background(Color.Transparent)
        .padding(horizontal = 18.dp, vertical = 10.dp),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black),
        color = if (selected) selectedFg else idleFg,
        maxLines = 1,
      )
    }
  }
}

