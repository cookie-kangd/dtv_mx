package dtv.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dtv.mobile.model.Streamer
import dtv.mobile.util.formatViewerCountWanIfNeeded
import dtv.mobile.util.normalizeHttpUrl

@Composable
fun HomeStreamerCard(
  streamer: Streamer,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  followed: Boolean = false,
  onToggleFollow: (() -> Unit)? = null,
) {
  val metrics = LocalCardMetrics.current
  val compact = metrics.compact
  val isDark = DtvCardDefaults.isDarkTheme()
  // 与 Colors.kt 的冷色深色盘对齐（NightBgSecondary = 0xFF121720），避免卡片发灰而背景发蓝
  val bg = DtvCardDefaults.homeCardColor(isDark)
  val border = DtvCardDefaults.cardBorderColor(isDark)
  val accent = MaterialTheme.colorScheme.primary
  // URL 归一化按输入记忆：卡片随列表滚动频繁重组时避免重复正则解析
  val cover = remember(streamer.coverUrl, streamer.avatarUrl) {
    normalizeHttpUrl(streamer.coverUrl) ?: normalizeHttpUrl(streamer.avatarUrl)
  }
  val avatar = remember(streamer.avatarUrl) { normalizeHttpUrl(streamer.avatarUrl) }
  val offline = !streamer.isLive
  val offlineOverlay = DtvCardDefaults.offlineOverlayColor

  Column(
    modifier = modifier
      .fillMaxWidth()
      .clickable(onClick = onClick),
    verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
  ) {
    Box(modifier = Modifier.fillMaxWidth()) {
      val mediaShape = RoundedCornerShape(if (compact) 20.dp else 32.dp)
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .aspectRatio(metrics.coverRatio)
          .clip(mediaShape),
        shape = mediaShape,
        color = bg,
        tonalElevation = 0.dp,
        shadowElevation = if (isDark) 0.dp else 2.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
      ) {
        Box(modifier = Modifier.fillMaxWidth()) {
          if (cover != null) {
            NetworkImage(url = cover, contentDescription = streamer.title, modifier = Modifier.fillMaxWidth().aspectRatio(metrics.coverRatio))
            if (offline) {
              Box(modifier = Modifier.matchParentSize().background(offlineOverlay))
            }
          } else {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(metrics.coverRatio), contentAlignment = Alignment.Center) {
              Text(text = streamer.name.take(1), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            }
          }
          Box(
            modifier = Modifier
              .matchParentSize()
              .background(
                Brush.verticalGradient(
                  colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)),
                ),
              ),
          )
        }
      }

      // 右上角人气徽标与左上角关注徽标：同高、同内边距、同圆角，视觉上下对齐
      val badgeH = if (compact) 22.dp else 26.dp
      if (streamer.viewerText.isNotBlank()) {
        Surface(
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(end = if (compact) 7.dp else 12.dp, top = if (compact) 7.dp else 12.dp)
            .height(badgeH),
          shape = RoundedCornerShape(metrics.badgeCorner),
          color = Color.Black.copy(alpha = 0.40f),
          border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
          tonalElevation = 0.dp,
          shadowElevation = 0.dp,
        ) {
          Box(
            modifier = Modifier
              .fillMaxHeight()
              .padding(horizontal = if (compact) 6.dp else 9.dp),
            contentAlignment = Alignment.Center,
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

      // 封面左上角关注徽标：首页卡片虽都是已关注主播，但可从卡片直接取消关注
      if (onToggleFollow != null) {
        Surface(
          onClick = onToggleFollow,
          modifier = Modifier
            .align(Alignment.TopStart)
            .padding(start = if (compact) 7.dp else 12.dp, top = if (compact) 7.dp else 12.dp)
            .size(badgeH),
          shape = RoundedCornerShape(metrics.badgeCorner),
          color = Color.Black.copy(alpha = 0.40f),
          border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
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

    Box(modifier = Modifier.fillMaxWidth()) {
      Box(
        modifier = Modifier
          .padding(start = if (compact) 10.dp else 14.dp)
          .offset(y = if (compact) (-14).dp else (-20).dp)
          .size(if (compact) 32.dp else 44.dp),
      ) {
        Surface(
          modifier = Modifier
            .matchParentSize()
            .clip(RoundedCornerShape(if (compact) 13.dp else 18.dp)),
          shape = RoundedCornerShape(if (compact) 13.dp else 18.dp),
          color = MaterialTheme.colorScheme.background,
          tonalElevation = 0.dp,
          shadowElevation = 0.dp,
          border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = if (isDark) 0.10f else 0.05f)),
        ) {
          if (avatar != null) {
            NetworkImage(url = avatar, contentDescription = streamer.name, modifier = Modifier.matchParentSize())
            if (offline) {
              Box(modifier = Modifier.matchParentSize().background(offlineOverlay))
            }
          } else {
            Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
              Text(text = streamer.name.take(1), style = MaterialTheme.typography.titleSmall)
            }
          }
        }

        Box(
          modifier = Modifier
            .align(Alignment.BottomEnd)
            .offset(x = 1.dp, y = 1.dp)
            .size(if (compact) 10.dp else 12.dp)
            .clip(CircleShape)
            .background(if (streamer.isLive) accent else Color(0xFF9CA3AF))
            .border(width = 2.dp, color = MaterialTheme.colorScheme.background, shape = CircleShape),
        )
      }

      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(start = if (compact) 48.dp else 72.dp, top = 0.dp)
          .offset(y = (-2).dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "@${streamer.name}",
            style = MaterialTheme.typography.bodySmall.copy(
              fontWeight = FontWeight.Black,
              fontStyle = FontStyle.Italic,
            ),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Text(
          text = streamer.title,
          style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
          color = DtvCardDefaults.secondaryTextColor(isDark),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}
