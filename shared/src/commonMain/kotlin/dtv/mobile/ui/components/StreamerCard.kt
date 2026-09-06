package dtv.mobile.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dtv.mobile.model.Streamer
import dtv.mobile.util.formatViewerCountWanIfNeeded
import dtv.mobile.util.normalizeHttpUrl

/** 平台页网格直播间卡片（小卡片/大卡片模式共用一套规格，见 CardMetrics）。 */
@Composable
fun StreamerCard(
  streamer: Streamer,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  followed: Boolean = false,
  onToggleFollow: (() -> Unit)? = null,
) {
  val metrics = LocalCardMetrics.current
  val shape = RoundedCornerShape(metrics.cornerRadius)
  val coverRatio = metrics.coverRatio
  // URL 归一化按输入记忆：网格滚动/切换画质等重组时不再重复做正则解析（与首页卡片一致）
  val cover = remember(streamer.coverUrl, streamer.avatarUrl) {
    normalizeHttpUrl(streamer.coverUrl) ?: normalizeHttpUrl(streamer.avatarUrl)
  }
  val offline = !streamer.isLive
  val isDark = DtvCardDefaults.isDarkTheme()
  val cardColor = DtvCardDefaults.gridCardColor(isDark)
  val borderColor = DtvCardDefaults.cardBorderColor(isDark)

  // 按压缩放反馈：按下时轻微缩小，松开回弹（仅在按压瞬间有动画，空闲零开销）
  val interactionSource = remember { MutableInteractionSource() }
  val pressed by interactionSource.collectIsPressedAsState()
  val pressScale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "cardPressScale")

  Surface(
    modifier = modifier
      .fillMaxWidth()
      .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
      .clip(shape)
      .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    shape = shape,
    color = cardColor,
    tonalElevation = 0.dp,
    shadowElevation = if (isDark) 0.dp else 2.dp,
    border = BorderStroke(1.dp, borderColor),
  ) {
    Column {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .aspectRatio(coverRatio)
          .background(MaterialTheme.colorScheme.secondary),
      ) {
        if (cover != null) {
          NetworkImage(
            url = cover,
            contentDescription = streamer.title,
            modifier = Modifier.fillMaxWidth().aspectRatio(coverRatio),
          )
          if (offline) {
            Box(modifier = Modifier.matchParentSize().background(DtvCardDefaults.offlineOverlayColor))
          }
        } else {
          Text(
            text = streamer.name.take(1),
            modifier = Modifier.align(Alignment.Center),
            color = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.85f),
            style = MaterialTheme.typography.titleLarge,
          )
        }

        Box(
          modifier = Modifier
            .matchParentSize()
            .background(
              androidx.compose.ui.graphics.Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.60f)),
              ),
            ),
        )

        // 右上角人气徽标与左上角关注徽标：同高(24dp)、同内边距、同圆角，视觉上下对齐
        val badgeHeight = 24.dp
        if (streamer.viewerText.isNotBlank()) {
          Surface(
            modifier = Modifier
              .align(Alignment.TopEnd)
              .padding(metrics.badgeInsetPadding)
              .height(badgeHeight),
            shape = RoundedCornerShape(metrics.badgeCorner),
            color = Color.Black.copy(alpha = 0.40f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = formatViewerCountWanIfNeeded(streamer.viewerText),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black, fontSize = metrics.viewerSize),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }

        // 封面左上角关注徽标：点击直接关注/取关，无需进入直播间
        if (onToggleFollow != null) {
          Surface(
            onClick = onToggleFollow,
            modifier = Modifier
              .align(Alignment.TopStart)
              .padding(metrics.badgeInsetPadding)
              .size(badgeHeight),
            shape = RoundedCornerShape(metrics.badgeCorner),
            color = Color.Black.copy(alpha = 0.40f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = if (followed) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (followed) "取消关注" else "关注",
                tint = if (followed) DtvCardDefaults.FollowActive else Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(14.dp),
              )
            }
          }
        }
      }

      Column(
        modifier = Modifier.padding(horizontal = metrics.contentPaddingH, vertical = metrics.contentPaddingV),
        verticalArrangement = Arrangement.spacedBy(metrics.contentSpacing),
      ) {
        Text(
          text = streamer.title,
          style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Black, fontSize = metrics.titleSize),
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(metrics.infoSpacing)) {
          val avatar = remember(streamer.avatarUrl) { normalizeHttpUrl(streamer.avatarUrl) }
          Box(
            modifier = Modifier
              .size(metrics.avatarSize)
              .clip(CircleShape)
              .background(if (isDark) Color.White.copy(alpha = 0.10f) else MaterialTheme.colorScheme.secondary),
            contentAlignment = Alignment.Center,
          ) {
            if (avatar != null) {
              NetworkImage(url = avatar, contentDescription = streamer.name, modifier = Modifier.matchParentSize())
            } else {
              Text(streamer.name.take(1), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelSmall)
            }
            if (offline) {
              Box(modifier = Modifier.matchParentSize().background(DtvCardDefaults.offlineOverlayColor))
            }
          }

          Text(
            text = streamer.name,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = metrics.nameSize),
            color = DtvCardDefaults.secondaryTextColor(isDark),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
          )
        }
      }

      Spacer(modifier = Modifier.size(2.dp))
    }
  }
}
