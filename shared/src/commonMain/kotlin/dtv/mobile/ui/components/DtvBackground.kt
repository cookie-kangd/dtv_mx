package dtv.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

@Composable
fun DtvBackground(content: @Composable () -> Unit) {
  val bg = MaterialTheme.colorScheme.background
  val surface = MaterialTheme.colorScheme.surface
  val accent = MaterialTheme.colorScheme.primary
  val isDark = bg.luminance() < 0.35f

  Box(
    modifier = Modifier
      .fillMaxSize()
      // drawWithCache：Brush 在尺寸/主题变化时才重建，绘制帧之间零分配
      // （此前每次 draw 都重新分配 1 个 linearGradient + 3 个 radialGradient）。
      .drawWithCache {
        val w = size.width
        val h = size.height
        val diagonal = Brush.linearGradient(
          colors = listOf(
            bg,
            accent.copy(alpha = if (isDark) 0.05f else 0.04f),
            bg,
          ),
          start = Offset(0f, 0f),
          end = Offset(w, h),
        )
        val a1 = if (isDark) accent.copy(alpha = 0.10f) else accent.copy(alpha = 0.06f)
        val a2 = if (isDark) surface.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.05f)
        val a3 = if (isDark) accent.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.03f)
        val blob1 = Brush.radialGradient(
          colors = listOf(a1, Color.Transparent),
          center = Offset(w * 0.12f, h * 0.10f),
          radius = w * 0.62f,
        )
        val blob2 = Brush.radialGradient(
          colors = listOf(a3, Color.Transparent),
          center = Offset(w * 0.86f, h * 0.18f),
          radius = w * 0.46f,
        )
        val blob3 = Brush.radialGradient(
          colors = listOf(a2, Color.Transparent),
          center = Offset(w * 0.92f, h * 0.92f),
          radius = w * 0.66f,
        )
        onDrawBehind {
          drawRect(diagonal)
          drawCircle(brush = blob1, radius = w * 0.62f, center = Offset(w * 0.12f, h * 0.10f))
          drawCircle(brush = blob2, radius = w * 0.46f, center = Offset(w * 0.86f, h * 0.18f))
          drawCircle(brush = blob3, radius = w * 0.66f, center = Offset(w * 0.92f, h * 0.92f))
        }
      },
  ) {
    content()
  }
}
