package dtv.mobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import dtv.mobile.ui.components.DtvCardDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dtv.mobile.model.Streamer
import dtv.mobile.state.AppState
import dtv.mobile.ui.components.NetworkImage
import dtv.mobile.ui.components.RoundedDropdownMenu
import dtv.mobile.ui.system.PlatformBackHandler
import dtv.mobile.util.formatViewerCountWanIfNeeded
import dtv.mobile.util.normalizeHttpUrl
import kotlinx.coroutines.delay

/**
 * 平台页顶栏的内联搜索框：直接在输入框里输入关键字，
 * 下方弹出搜索结果面板（可进直播间、可关注/取关主播），
 * 不再跳转到独立搜索页。
 */
@Composable
fun PlatformInlineSearch(
  appState: AppState,
  modifier: Modifier = Modifier,
) {
  var query by remember { mutableStateOf("") }
  var results by remember(appState.selectedPlatform) { mutableStateOf(emptyList<Streamer>()) }
  var searching by remember(appState.selectedPlatform) { mutableStateOf(false) }
  // 结果面板是否展开。展开/收起只由「有无输入内容」与「点按外部」驱动，
  // 不再与输入框焦点联动——焦点丢失时绝不清空 query、绝不收起输入法，
  // 彻底杜绝打字过程中输入被清、输入法弹窗消失的问题。
  var panelOpen by remember(appState.selectedPlatform) { mutableStateOf(false) }
  // 输入法「搜索」键的触发计数：+1 表示用户已输入完成，要求立即搜索（跳过停顿等待）
  var searchTick by remember { mutableStateOf(0) }
  var consumedTick by remember { mutableStateOf(0) }
  val platform = appState.selectedPlatform
  val focusManager = LocalFocusManager.current

  LaunchedEffect(platform, query, searchTick) {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) {
      results = emptyList()
      searching = false
      panelOpen = false
      return@LaunchedEffect
    }
    panelOpen = true
    // 去掉逐键联想：输入停顿（约 0.6s）视为「一次输入完成」后自动搜一次；
    // 按输入法搜索键则跳过等待立即搜。
    val immediate = searchTick != consumedTick
    if (immediate) consumedTick = searchTick else delay(600)
    searching = true
    results = runCatching { appState.repo.searchAnchors(platform = platform, keyword = trimmed) }
      .getOrDefault(emptyList())
    searching = false
  }

  // 收起面板。clearFocus=false 时保持输入框焦点与输入法弹窗（如点「×」清空重输）。
  fun closePanel(clearFocus: Boolean) {
    query = ""
    panelOpen = false
    if (clearFocus) focusManager.clearFocus()
  }

  // 面板不可获焦（不抢输入法），系统返回键无法自动收起它，
  // 这里显式接管：面板展开时按返回键先收面板，不直接退出当前页。
  PlatformBackHandler(enabled = query.isNotBlank()) { closePanel(clearFocus = true) }

  Box(modifier = modifier) {
    // 毛玻璃质感：半透明底 + 发丝描边。暗色下用白色高光描边模拟玻璃边缘反光。
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .height(44.dp)
        .clip(RoundedCornerShape(999.dp)),
      shape = RoundedCornerShape(999.dp),
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
      border = BorderStroke(
        1.dp,
        if (isDark) {
          Color.White.copy(alpha = 0.12f)
        } else {
          MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
        },
      ),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = Icons.Default.Search,
          contentDescription = "搜索",
          tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
          modifier = Modifier.size(18.dp),
        )
        BasicTextField(
          value = query,
          onValueChange = { query = it },
          modifier = Modifier
            .weight(1f)
            .height(44.dp),
          singleLine = true,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          keyboardActions = KeyboardActions(onSearch = { searchTick++ }),
          textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
          ),
          cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
          decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
              if (query.isEmpty()) {
                Text(
                  text = "搜索",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
              }
              inner()
            }
          },
        )
        if (query.isNotEmpty()) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "清空",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier
              .size(36.dp)
              .clip(CircleShape)
              .clickable { closePanel(clearFocus = false) }
              .padding(8.dp),
          )
        }
      }
    }

    RoundedDropdownMenu(
      expanded = panelOpen && query.trim().isNotEmpty(),
      // 点按外部（面板不可获焦，外部点击会穿透到底层 UI 同时触发这里）：
      // 只收面板，绝不清空输入、绝不动输入法
      onDismissRequest = { panelOpen = false },
      offsetY = 50.dp,
      width = 320.dp,
      maxHeight = 420.dp,
      // 关键：结果面板不能抢焦点，否则输入第一个字符后焦点被 Popup 夺走，
      // 输入法自动收起且后续字符全部丢失。
      focusable = false,
    ) {
      when {
        searching -> {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
              text = "搜索中…",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
          }
        }
        results.isEmpty() -> {
          Text(
            text = "没有找到相关直播间",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
          )
        }
        else -> {
          // 面板内最多展示 20 条，点击结果直接进直播间并收起面板
          results.take(20).forEach { streamer ->
            val followed = appState.isFollowed(streamer)
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable {
                  closePanel(clearFocus = true)
                  appState.openPlayer(streamer)
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              val cover = normalizeHttpUrl(streamer.coverUrl) ?: normalizeHttpUrl(streamer.avatarUrl)
              Box(
                modifier = Modifier
                  .size(width = 72.dp, height = 44.dp)
                  .clip(RoundedCornerShape(8.dp))
                  .background(MaterialTheme.colorScheme.secondary),
                contentAlignment = Alignment.Center,
              ) {
                if (cover != null) {
                  NetworkImage(
                    url = cover,
                    contentDescription = streamer.title,
                    modifier = Modifier.matchParentSize(),
                  )
                } else {
                  Text(
                    text = streamer.name.take(1),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondary,
                  )
                }
              }

              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = streamer.title,
                  style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  Text(
                    text = streamer.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                  )
                  val viewer = streamer.viewerText.takeIf { it.isNotBlank() }
                    ?.let(::formatViewerCountWanIfNeeded)
                  if (viewer != null) {
                    Text(
                      text = viewer,
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                      maxLines = 1,
                    )
                  }
                }
              }

              IconButton(onClick = { appState.toggleFollow(streamer) }) {
                Icon(
                  imageVector = if (followed) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                  contentDescription = if (followed) "取消关注" else "关注",
                  tint = if (followed) {
                    DtvCardDefaults.FollowActive
                  } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                  },
                )
              }
            }
          }
        }
      }
    }
  }
}
