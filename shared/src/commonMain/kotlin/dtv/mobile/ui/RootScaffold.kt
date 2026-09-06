package dtv.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dtv.mobile.state.CategoryMenuState
import dtv.mobile.state.AppState
import dtv.mobile.state.Screen
import dtv.mobile.ui.screens.HomeScreen
import dtv.mobile.ui.screens.PlatformInlineSearch
import dtv.mobile.ui.screens.PlatformScreen
import dtv.mobile.ui.screens.PlayerScreen
import dtv.mobile.ui.screens.SettingsScreen
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import dtv.mobile.ui.system.PlatformBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import dtv.mobile.ui.components.LocalGlassHaze
import dtv.mobile.ui.components.RoundedDropdownMenu
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScaffold(appState: AppState) {
  PlatformBackHandler(enabled = appState.currentScreen != Screen.Home) { appState.back() }

  val scope = rememberCoroutineScope()

  var homeRefreshing by remember { mutableStateOf(false) }
  // 首页刷新（图标点击 / 首次进入）与「下拉刷新」走完全相同的路径：
  // 只调用 refreshFollowedStreamerCards()，复用各平台关注列表 API 返回的权威直播状态。
  // 不再额外调用 refreshFollowedLiveStatus() —— 后者基于房间页 HTML / 单独的在线判断接口，
  // 对「刚刚下播」的主播会误判为仍在线（例如虎牙房间页残留的 stream 数据块），
  // 这正是「点击刷新图标刷出已下播却显示在线」的根因。统一后两种刷新结果完全一致。
  val onHomeRefresh: () -> Unit = l@{
    if (homeRefreshing) return@l
    scope.launch {
      homeRefreshing = true
      runCatching { appState.refreshFollowedStreamerCards() }
      homeRefreshing = false
    }
  }

  LaunchedEffect(Unit) {
    if (appState.followedStreamers.isNotEmpty()) {
      runCatching { appState.refreshFollowedStreamerCards() }
    }
  }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    contentWindowInsets = if (appState.currentScreen == Screen.Player) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
    topBar = {
      when (appState.currentScreen) {
        Screen.Player -> Unit
        Screen.Settings -> {
          // 设置页的标题与返回按钮由 SettingsScreen 自己渲染（根页大返回回首页，
          // 子页大返回回设置根页），顶栏不再叠加返回图标，避免出现两个返回。
          Unit
        }
        else -> {
          HubTopBar(
            // 平台页不再显示左侧平台名（毛玻璃底栏已标明当前平台，重复展示纯属多余），
            // 让搜索框吃到整行剩余宽度；首页保留「关注列表」标题。
            title = if (appState.currentScreen == Screen.Home) "关注列表" else null,
            appState = appState,
            categoryMenu = appState.categoryMenu,
            showSearch = appState.currentScreen != Screen.Home,
            showPlatformActions = appState.currentScreen == Screen.Platform,
            showSettings = appState.currentScreen == Screen.Home,
            showRefresh = appState.currentScreen == Screen.Home,
            refreshing = homeRefreshing,
            onRefreshClick = onHomeRefresh,
            onSettingsClick = appState::openSettings,
          )
        }
      }
    },
    bottomBar = {},
  ) { padding ->
    // 悬浮毛玻璃底栏：内容铺满全屏（不再被 bottomBar 槽位顶起），
    // 底栏浮岛 overlay 在内容之上；列表通过自身 contentPadding 预留浮岛高度，
    // 保证最后一张卡片能完整滚出浮岛区域。haze 源注册在内容层上，
    // 使浮岛能对其背后的滚动内容做实时模糊（毛玻璃）。
    val showDock = !(appState.currentScreen == Screen.Player && appState.playerFullscreen)
    val hazeState = remember { HazeState() }
    CompositionLocalProvider(LocalGlassHaze provides hazeState) {
      Box(modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
      AnimatedContent(
        targetState = appState.currentScreen,
        transitionSpec = {
          val enteringPlayer = targetState == Screen.Player && initialState != Screen.Player
          val leavingPlayer = initialState == Screen.Player && targetState != Screen.Player
          when {
            enteringPlayer -> (slideInHorizontally(animationSpec = tween(240)) { it / 6 } + fadeIn(animationSpec = tween(240)))
              .togetherWith(fadeOut(animationSpec = tween(120)))
            leavingPlayer -> fadeIn(animationSpec = tween(120))
              .togetherWith(slideOutHorizontally(animationSpec = tween(240)) { it / 6 } + fadeOut(animationSpec = tween(240)))
            else -> fadeIn(animationSpec = tween(140)).togetherWith(fadeOut(animationSpec = tween(140)))
          }
        },
        label = "screen",
        modifier = Modifier.fillMaxSize().haze(hazeState),
      ) { screen ->
        when (screen) {
          Screen.Home -> HomeScreen(
            modifier = Modifier,
            appState = appState,
          )
          Screen.Platform -> PlatformScreen(
            modifier = Modifier,
            appState = appState,
          )
          Screen.Player -> PlayerScreen(
            modifier = Modifier,
            appState = appState,
            streamer = appState.currentStreamer,
          )
          Screen.Settings -> SettingsScreen(
            modifier = Modifier,
            appState = appState,
          )
        }
      }

      if (showDock) {
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
          PlatformBottomBar(
            hazeState = hazeState,
            selectedScreen = appState.dockSelectedScreen,
            selectedPlatform = appState.selectedPlatform,
            onHomeClick = appState::openHome,
            onPlatformClick = appState::selectPlatform,
            switchingLoading = appState.platformSwitchLoading,
            platforms = appState.visiblePlatforms,
          )
        }
      }
      }
    }
  }
}

@Composable
private fun HubTopBar(
  title: String?,
  appState: AppState,
  categoryMenu: CategoryMenuState?,
  showSearch: Boolean,
  showPlatformActions: Boolean,
  showSettings: Boolean,
  showRefresh: Boolean,
  refreshing: Boolean,
  onRefreshClick: () -> Unit,
  onSettingsClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var categoryMenuExpanded by remember { mutableStateOf(false) }
  Surface(
    modifier = modifier.statusBarsPadding(),
    color = Color.Transparent,
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 5.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (title != null) {
        Text(
          text = title,
          color = MaterialTheme.colorScheme.primary,
          style = MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic,
          ),
          modifier = Modifier.padding(top = 4.dp),
        )
      }

      if (showSearch) {
        // 内联搜索框：就地输入、下拉展示搜索结果，可进直播间/关注主播，不再跳搜索页
        PlatformInlineSearch(
          appState = appState,
          modifier = Modifier.weight(1f),
        )
      } else {
        Spacer(modifier = Modifier.weight(1f))
      }

      // 数据同步与主题模式入口已迁移到「设置」页；
      // 顶栏保留平台页专属操作：板块下拉菜单（原第二列一级分区）。
      // B站登录入口已迁移到「设置 → 平台登录」，四平台顶栏因此完全一致。
      if (showPlatformActions) {
        if (categoryMenu != null) {
          Box {
            // 与搜索框同规格（高 44dp 胶囊），背景始终为全局高亮色。
            // 宽度按「4 个汉字 + 图标」定死（90dp：文字区 58dp > 4×14sp=56dp），
            // 板块名最长 4 个字必然完整显示，不会出现省略号；同时保证 4 个平台
            // 顶栏的搜索框长度完全一致。去掉平台名后搜索框吃到整行剩余宽度。
            val menu = categoryMenu
            val currentSection = menu.options.getOrNull(menu.selectedIndex).takeIf { menu.selectedIndex >= 0 }
            Surface(
              onClick = { categoryMenuExpanded = !categoryMenuExpanded },
              modifier = Modifier.height(44.dp).width(90.dp),
              shape = RoundedCornerShape(999.dp),
              color = MaterialTheme.colorScheme.primary,
              tonalElevation = 0.dp,
              shadowElevation = 0.dp,
            ) {
              Row(
                modifier = Modifier.padding(start = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
              ) {
                Text(
                  text = currentSection ?: "板块",
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.onPrimary,
                  fontWeight = FontWeight.Bold,
                  maxLines = 1,
                  softWrap = false,
                  modifier = Modifier.weight(1f),
                )
                Icon(
                  imageVector = Icons.Default.ArrowDropDown,
                  contentDescription = "选择板块",
                  tint = MaterialTheme.colorScheme.onPrimary,
                  modifier = Modifier.size(18.dp),
                )
              }
            }
            RoundedDropdownMenu(
              expanded = categoryMenuExpanded,
              onDismissRequest = { categoryMenuExpanded = false },
              offsetY = 50.dp,
              width = 180.dp,
            ) {
              menu?.options?.forEachIndexed { index, option ->
                val selected = index == menu.selectedIndex
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                      categoryMenuExpanded = false
                      menu.onSelect(index)
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text = option,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Black else FontWeight.Normal,
                    color = if (selected) {
                      MaterialTheme.colorScheme.primary
                    } else {
                      MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                  )
                }
              }
            }
          }
        }
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        if (showRefresh) {
          // 用 Box + clickable 取代默认 48dp 触摸尺寸的 IconButton，
          // 让刷新/设置两个图标在顶栏真正贴近（视觉间隙从约 28dp 收到约 8dp）。
          Box(
            modifier = Modifier
              .size(36.dp)
              .clickable(enabled = !refreshing, onClick = onRefreshClick, role = Role.Button),
            contentAlignment = Alignment.Center,
          ) {
            if (refreshing) {
              CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
              )
            } else {
              Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "刷新",
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
              )
            }
          }
        }

        if (showSettings) {
          Box(
            modifier = Modifier
              .size(36.dp)
              .clickable(onClick = onSettingsClick, role = Role.Button),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.Default.Settings,
              contentDescription = "设置",
              modifier = Modifier.size(22.dp),
              tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
          }
        }
      }
    }
  }
}
