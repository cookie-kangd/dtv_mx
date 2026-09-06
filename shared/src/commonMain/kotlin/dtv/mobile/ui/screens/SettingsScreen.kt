package dtv.mobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dtv.mobile.model.Platform
import dtv.mobile.state.AppState
import dtv.mobile.ui.DockContentClearance
import dtv.mobile.ui.components.BilibiliWebLoginSheet
import dtv.mobile.state.ThemeMode
import dtv.mobile.state.VideoQuality
import dtv.mobile.ui.system.PlatformBackHandler
import dtv.mobile.update.AppUpdateInfo
import dtv.mobile.update.UpdateState
import dtv.mobile.update.rememberUpdateManager
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SettingsSection { Root, Basic, Sync, Platform, PlatformLogin, About }

private val PresetAccentColors = listOf(
  "青柠" to "#A3E635",
  "红色" to "#EF4444",
  "橙色" to "#F97316",
  "黄色" to "#EAB308",
  "绿色" to "#22C55E",
  "青色" to "#14B8A6",
  "蓝色" to "#3B82F6",
  "靛蓝" to "#6366F1",
  "紫色" to "#A855F7",
  "粉色" to "#EC4899",
  "玫红" to "#F43F5E",
  "天蓝" to "#38BDF8",
  "灰色" to "#94A3B8",
)

/** 全局颜色默认色（青色）。未显式设置时即使用此色，点击「恢复默认颜色」也会回到此色。 */
private const val DEFAULT_ACCENT_HEX = "#14B8A6"

@Composable
fun SettingsScreen(
  appState: AppState,
  modifier: Modifier = Modifier,
) {
  var section by remember { mutableStateOf(SettingsSection.Root) }

  PlatformBackHandler(enabled = section != SettingsSection.Root) { section = SettingsSection.Root }

  // 返回层级唯一化：根页一个大返回（回首页），子页一个大返回（回设置根页）。
  // 顶栏不再叠加返回图标（见 RootScaffold），系统返回键行为与此一致。
  Column(modifier = modifier.fillMaxSize()) {
    if (section == SettingsSection.Root) {
      Row(modifier = Modifier.padding(start = 6.dp, end = 18.dp, top = 6.dp, bottom = 2.dp)) {
        SettingsSectionHeader(title = "设置", onBack = { appState.back() })
      }
    }
    when (section) {
      SettingsSection.Root -> SettingsRoot(
        onOpenBasic = { section = SettingsSection.Basic },
        onOpenSync = { section = SettingsSection.Sync },
        onOpenPlatform = { section = SettingsSection.Platform },
        onOpenPlatformLogin = { section = SettingsSection.PlatformLogin },
        onOpenAbout = { section = SettingsSection.About },
        modifier = Modifier.fillMaxSize(),
      )
      SettingsSection.Basic -> BasicSettingsSection(
        appState = appState,
        onBack = { section = SettingsSection.Root },
        modifier = Modifier.fillMaxSize(),
      )
      SettingsSection.Sync -> SyncSettingsSection(
        appState = appState,
        onBack = { section = SettingsSection.Root },
        modifier = Modifier.fillMaxSize(),
      )
      SettingsSection.Platform -> PlatformSettingsSection(
        appState = appState,
        onBack = { section = SettingsSection.Root },
        modifier = Modifier.fillMaxSize(),
      )
      SettingsSection.PlatformLogin -> PlatformLoginSection(
        appState = appState,
        onBack = { section = SettingsSection.Root },
        modifier = Modifier.fillMaxSize(),
      )
      SettingsSection.About -> AboutSection(
        appState = appState,
        onBack = { section = SettingsSection.Root },
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}

@Composable
private fun SettingsRoot(
  onOpenBasic: () -> Unit,
  onOpenSync: () -> Unit,
  onOpenPlatform: () -> Unit,
  onOpenPlatformLogin: () -> Unit,
  onOpenAbout: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.verticalScroll(rememberScrollState()),
  ) {
    // 入口分组卡：五个设置入口收进同一张圆角卡片，不再是散落在背景上的行
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 4.dp),
      shape = RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
    ) {
      Column(modifier = Modifier.padding(vertical = 4.dp)) {
        SettingsItemRow(
          icon = { Icon(imageVector = Icons.Default.Settings, contentDescription = null) },
          title = "基本设置",
          subtitle = "记住栏目、主题模式、全局颜色",
          onClick = onOpenBasic,
        )
        SettingsItemRow(
          icon = { Icon(imageVector = Icons.Default.LiveTv, contentDescription = null) },
          title = "平台设置",
          subtitle = "平台启用开关与切换栏排序",
          onClick = onOpenPlatform,
        )
        SettingsItemRow(
          icon = { Icon(imageVector = Icons.Default.AccountCircle, contentDescription = null) },
          title = "平台登录",
          subtitle = "B站等平台账号登录与退出",
          onClick = onOpenPlatformLogin,
        )
        SettingsItemRow(
          icon = { Icon(imageVector = Icons.Default.Devices, contentDescription = null) },
          title = "数据同步",
          subtitle = "局域网共享 / 导入关注、分区与屏蔽词",
          onClick = onOpenSync,
        )
        SettingsItemRow(
          icon = { Icon(imageVector = Icons.Default.Info, contentDescription = null) },
          title = "关于",
          subtitle = "应用介绍、检查更新与版本信息",
          onClick = onOpenAbout,
        )
      }
    }
    // 悬浮底栏（毛玻璃浮岛）叠在内容之上，滚动内容底部预留出浮岛高度
    Spacer(
      modifier = Modifier.height(
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + dtv.mobile.ui.DockContentClearance,
      ),
    )
  }
}

/**
 * 平台登录：集中管理需要登录的平台（当前为 B站，后续可扩展抖音等）。
 * 每个平台一张卡片：左侧平台信息（点击弹出登录弹窗，二维码 / 账号密码两种方式），
 * 右侧垂直居中一个退出登录图标（仅已登录时显示），点击即退出对应平台。
 * 登录态保存在仓库 Cookie 中，升级重装后仍保留。
 */
@Composable
private fun PlatformLoginSection(
  appState: AppState,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var loggedIn by remember { mutableStateOf(false) }
  var checking by remember { mutableStateOf(true) }
  var showLoginSheet by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  // 进页/弹窗关闭后刷新登录状态
  LaunchedEffect(showLoginSheet) {
    checking = true
    loggedIn = runCatching { appState.repo.getBilibiliCookie() }
      .getOrNull()?.contains("sessdata=", ignoreCase = true) == true
    checking = false
  }

  Column(
    modifier = modifier
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 18.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    SettingsSectionHeader(title = "平台登录", onBack = onBack)

    // B站登录卡片：卡片主体点击 → 登录；右侧退出图标（垂直居中）点击 → 退出登录
    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
    ) {
      Row(
        modifier = Modifier
          .clickable { showLoginSheet = true }
          .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Box(
          modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Default.LiveTv,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
          )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = "哔哩哔哩",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = when {
              checking -> "检查登录状态…"
              loggedIn -> "已登录 · 点击卡片可重新登录"
              else -> "未登录 · 点击扫码或密码登录"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
          )
        }

        // 右侧退出图标：与卡片内容上下居中对齐；仅已登录时展示
        if (!checking && loggedIn) {
          Box(
            modifier = Modifier
              .size(38.dp)
              .clip(CircleShape)
              .clickable {
                scope.launch {
                  runCatching { appState.repo.clearBilibiliCookie() }
                  loggedIn = false
                }
              },
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.Logout,
              contentDescription = "退出登录",
              tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
              modifier = Modifier.size(21.dp),
            )
          }
        }
      }
    }

    Text(
      text = "登录态保存在本机，升级或重装后仍会保留；点击对应平台卡片即可重新登录。",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
      modifier = Modifier.padding(horizontal = 4.dp),
    )

    // 悬浮底栏（毛玻璃浮岛）叠在内容之上，滚动内容底部预留出浮岛高度
    Spacer(
      modifier = Modifier.height(
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + DockContentClearance,
      ),
    )
  }

  if (showLoginSheet) {
    BilibiliWebLoginSheet(
      appState = appState,
      onDismissRequest = { showLoginSheet = false },
      onCookieCaptured = { cookieHeader ->
        scope.launch {
          runCatching { appState.repo.mergeBilibiliCookie(cookieHeader) }
          loggedIn = runCatching { appState.repo.getBilibiliCookie() }
            .getOrNull()?.contains("sessdata=", ignoreCase = true) == true
        }
      },
    )
  }
}

/**
 * 上一次发布的版本号与更新内容（「关于」页展示用）。
 * ⚠️ 每次发新版时手动同步更新：把旧值换成「这次发版前的版本」，
 * 当前版本则由 UpdateManager 动态读取，无需维护。
 */
private const val LAST_RELEASE_VERSION = "0.2.7"
private val LAST_RELEASE_NOTES = listOf(
  "搜索框输入丢字符与输入法自动收起问题修复",
  "平板未开启默认横屏时一律竖屏进入直播间",
  "开启默认横屏后退出一次即回竖屏",
).joinToString("\n") { "· $it" }

@Composable
private fun AboutSection(
  appState: AppState,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val updateManager = rememberUpdateManager()
  val currentVersion = updateManager.currentVersionName.ifBlank { "?" }

  Column(
    modifier = modifier
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 18.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    SettingsSectionHeader(title = "关于", onBack = onBack)

    // 头部：应用图标 + 名称 + 版本号（对齐热门 App「关于」页样式）
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 12.dp, bottom = 4.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Box(
        modifier = Modifier
          .size(76.dp)
          .clip(RoundedCornerShape(20.dp))
          .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Default.LiveTv,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(40.dp),
        )
      }
      Text(
        text = "dtv_mx",
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
      )
      Text(
        text = "v$currentVersion",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
      )
    }

    // 应用简介 + 功能一览
    SettingsCard {
      Text(
        "应用简介",
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
      )
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = "一款聚合多平台直播的轻量 Android 客户端，汇聚斗鱼、虎牙、抖音、Bilibili 四大平台的直播内容，配合弹幕互动，主打快速、干净、省电。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
      )
      Spacer(modifier = Modifier.height(8.dp))
      listOf(
        "多平台直播聚合：四平台一站观看，分类自动记忆",
        "弹幕互动：横屏滚动弹幕 + 竖排弹幕列表，关键词屏蔽",
        "画中画小窗追播，熄屏听播戴耳机只听不看他",
        "多画质 / 多线路切换，关注管理与数据同步",
        "主题模式与全局配色自定义",
        "应用内检查更新，多源加速下载安装",
      ).forEach { feature ->
        Row(
          modifier = Modifier.padding(vertical = 3.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(
            modifier = Modifier
              .size(5.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = feature,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
          )
        }
      }
    }

    // 检查更新（原设置根页入口移到这里）
    UpdateCheckerCard()

    // 上次更新：上一次版本号 + 更新内容
    SettingsCard {
      Text(
        "上次更新",
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = "v$LAST_RELEASE_VERSION",
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = LAST_RELEASE_NOTES,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
      )
    }

    // 底部版本脚注
    Text(
      text = "dtv_mx v$currentVersion",
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 8.dp),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
      textAlign = TextAlign.Center,
    )

    // 悬浮底栏预留
    Spacer(
      modifier = Modifier.height(
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + dtv.mobile.ui.DockContentClearance,
      ),
    )
  }
}

@Composable
private fun UpdateCheckerCard(
  modifier: Modifier = Modifier,
) {
  val updateManager = rememberUpdateManager()
  val state = updateManager.state
  var expanded by remember { mutableStateOf(false) }
  val currentVersion = updateManager.currentVersionName

  // 检查更新整体包在圆角卡片里：不再直接贴在页面背景上，
  // 「检查更新」入口与展开后的状态内容都在同一张卡片内，排版更整体。
  SettingsCard(modifier = modifier) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .clickable {
          expanded = true
          updateManager.check()
        }
        .padding(horizontal = 2.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        modifier = Modifier
          .size(42.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Default.Download,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
      }
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = "检查更新",
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
          text = if (currentVersion.isBlank()) {
            "检查 GitHub Release 是否有新版本"
          } else {
            "当前版本 v$currentVersion · 点击检查新版本"
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
      }
      if (state is UpdateState.Checking) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
      } else if (!expanded) {
        Icon(
          imageVector = Icons.Default.Refresh,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp),
        )
      }
    }

    if (expanded) {
      Spacer(modifier = Modifier.height(10.dp))
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        when (state) {
          UpdateState.Idle -> Unit
          UpdateState.Checking -> UpdateStatusText(text = "正在检查更新…")
          UpdateState.UpToDate -> UpdateStatusText(
            text = "已是最新版本（v$currentVersion）",
            positive = true,
          )
          is UpdateState.Available -> UpdateAvailableBlock(
            info = state.info,
            onDownload = { url -> updateManager.download(state.info, url) },
          )
          is UpdateState.Downloading -> {
            val percent = (state.progress * 100).toInt()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = "正在下载新版本… $percent%",
                  modifier = Modifier.weight(1f),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                // 最右侧「停止下载」：取消所有下载并删除已缓存安装包
                OutlinedButton(
                  onClick = { updateManager.cancelDownload() },
                  contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                  Icon(imageVector = Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(modifier = Modifier.width(4.dp))
                  Text("停止下载")
                }
              }
              UpdateProgressBar(progress = state.progress)
            }
          }
          is UpdateState.Downloaded -> {
            UpdateStatusText(text = "安装包已下载，点击即可安装", positive = true)
            Button(onClick = { updateManager.install(state.fileUri) }) {
              Icon(imageVector = Icons.Default.Download, contentDescription = null)
              Spacer(modifier = Modifier.width(6.dp))
              Text("安装")
            }
          }
          is UpdateState.Failed -> {
            UpdateStatusText(text = state.message, positive = false)
            OutlinedButton(onClick = { updateManager.check() }) {
              Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
              Spacer(modifier = Modifier.width(6.dp))
              Text("重试")
            }
          }
        }
      }
    }
  }
}

@Composable
private fun VideoQualitySelector(
  selected: VideoQuality,
  onSelect: (VideoQuality) -> Unit,
  modifier: Modifier = Modifier,
) {
  val options = VideoQuality.entries
  Row(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
      .padding(4.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    options.forEach { quality ->
      val isSelected = quality == selected
      Box(
        modifier = Modifier
          .weight(1f)
          .clip(RoundedCornerShape(9.dp))
          .background(
            if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
          )
          .clickable { onSelect(quality) }
          .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = quality.label,
          style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
          color = if (isSelected) Color.Black else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
          maxLines = 1,
        )
      }
    }
  }
}

@Composable
private fun UpdateStatusText(
  text: String,
  positive: Boolean? = null,
) {
  val color = when (positive) {
    true -> Color(0xFF16A34A)
    false -> Color(0xFFDC2626)
    null -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
  }
  Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = color,
  )
}

@Composable
private fun UpdateProgressBar(progress: Float) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(6.dp)
      .clip(RoundedCornerShape(3.dp))
      .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth(progress.coerceIn(0f, 1f))
        .height(6.dp)
        .background(MaterialTheme.colorScheme.primary),
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdateAvailableBlock(
  info: AppUpdateInfo,
  onDownload: (String) -> Unit,
) {
  // commonMain 不可使用 String.format，这里手动保留一位小数
  val sizeText = if (info.sizeBytes > 0) {
    val mb = (info.sizeBytes / 1048576.0 * 10.0).toLong() / 10.0
    "$mb MB"
  } else {
    ""
  }

  Text(
    text = "发现新版本 v${info.versionName}",
    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
    color = MaterialTheme.colorScheme.primary,
  )
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    if (sizeText.isNotBlank()) {
      Text(
        text = sizeText,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
      )
    }
    if (info.publishedAt.isNotBlank()) {
      Text(
        text = info.publishedAt.replace("T", " ").removeSuffix("Z"),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
      )
    }
  }
  if (info.notes.isNotBlank()) {
    Text(
      text = info.notes.take(300),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
      maxLines = 6,
    )
  }
  // 下载来源按钮：github 原始地址 + gh-proxy.org 系列加速镜像，全部等宽等色、
  // 并排流式排列（一行放不下自动换行）。逐源下载，走同一套下载/安装流程，
  // 任意一个失败可换另一个。
  val sources = info.downloadSources.ifEmpty {
    listOf(dtv.mobile.update.DownloadSource("github", info.downloadUrl))
  }
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    sources.forEach { source ->
      OutlinedButton(
        onClick = { onDownload(source.url) },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
      ) {
        Text(source.label + if (source.recommended) "(推荐)" else "", maxLines = 1)
      }
    }
  }
}

@Composable
private fun SettingsItemRow(
  icon: @Composable () -> Unit,
  title: String,
  subtitle: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(14.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 6.dp, vertical = 4.dp),
    color = Color.Transparent,
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Box(
        modifier = Modifier
          .size(42.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
      ) {
        CompositionLocalProvider(
          LocalContentColor provides MaterialTheme.colorScheme.primary,
        ) {
          icon()
        }
      }
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
      }
    }
  }
}

@Composable
private fun SettingsSectionHeader(
  title: String,
  onBack: () -> Unit,
) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    IconButton(onClick = onBack) {
      Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
    }
    Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black))
  }
}

@Composable
private fun SettingsCard(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), content = content)
  }
}

@Composable
private fun PlatformSettingsSection(
  appState: AppState,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var notice by remember { mutableStateOf<String?>(null) }

  // 操作提示展示数秒后自动消失
  LaunchedEffect(notice) {
    val shown = notice ?: return@LaunchedEffect
    delay(2800)
    if (notice == shown) notice = null
  }

  Column(
    modifier = modifier
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 18.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    SettingsSectionHeader(title = "平台设置", onBack = onBack)

    // 提示条
    if (notice != null) {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
      ) {
        Text(
          text = notice.orEmpty(),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
      }
    }

    // 平台启用（默认全部开启）
    SettingsCard {
      Text("平台启用", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = "Twitch 默认关闭（需在下方手动开启），其余平台默认开启。关闭后该平台会立即从切换栏移除，重新开启后自动加回。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
      )
      Spacer(modifier = Modifier.height(6.dp))
      appState.platformOrder.forEach { platform ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = platform.title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
          )
          Switch(
            checked = platform !in appState.platformDisabled,
            onCheckedChange = { enabled ->
              appState.updatePlatformEnabled(platform, enabled)
              notice = if (enabled) {
                "已开启「${platform.title}」，已加回下方切换栏（无需重启）"
              } else {
                "已关闭「${platform.title}」，已从下方切换栏移除（无需重启）"
              }
            },
          )
        }
      }
    }

    // 平台排序（拖拽）
    SettingsCard {
      Text("平台排序", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = "长按右侧手柄上下拖动即可调整顺序，下方切换栏会立即按新顺序排列。已关闭的平台也会显示在此处（置灰），重新开启后按此顺序加回。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
      )
      Spacer(modifier = Modifier.height(10.dp))
      PlatformReorderList(
        platforms = appState.platformOrder,
        disabled = appState.platformDisabled,
        onReorder = { notice = "顺序已更新：${appState.platformOrder.joinToString(" · ") { it.title }}（无需重启）" },
        onMove = { from, to -> appState.movePlatform(from, to) },
      )
    }

    // 悬浮毛玻璃底栏叠在内容之上，滚动内容底部预留浮岛高度，避免最后一项（如平台排序末位）被遮挡
    Spacer(
      modifier = Modifier.height(
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + DockContentClearance,
      ),
    )
  }
}

/** 可长按拖拽排序的纵向列表（仅用于平台排序，条目数很少，无需 LazyColumn）。 */
@Composable
private fun PlatformReorderList(
  platforms: List<Platform>,
  disabled: Set<Platform> = emptySet(),
  onMove: (fromIndex: Int, toIndex: Int) -> Unit,
  onReorder: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val itemHeight = 52.dp
  val gap = 8.dp
  val density = LocalDensity.current
  val itemHeightPx = with(density) { itemHeight.toPx() }
  val gapPx = with(density) { gap.toPx() }
  val stride = itemHeightPx + gapPx

  // 拖拽中记录的是"平台"本身而非索引：拖拽过程中列表会重排，
  // 用索引会被中途的位置变化打乱，用身份则每次都能算出正确的目标位置。
  var draggingPlatform by remember { mutableStateOf<Platform?>(null) }
  var dragOffset by remember { mutableStateOf(0f) }
  // pointerInput 的 key 固定为 Unit，避免列表变化时手势被重启而中断拖拽；
  // 列表内容通过 rememberUpdatedState 读取，保证回调里拿到的是最新顺序。
  val latestList by rememberUpdatedState(platforms)

  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
    platforms.forEach { platform ->
      val isDragging = draggingPlatform == platform
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .height(itemHeight)
          .zIndex(if (isDragging) 1f else 0f)
          .offset { IntOffset(0, (if (isDragging) dragOffset else 0f).roundToInt()) }
          .pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
              onDragStart = {
                draggingPlatform = platform
                dragOffset = 0f
              },
              onDragEnd = {
                if (draggingPlatform != null) {
                  draggingPlatform = null
                  dragOffset = 0f
                  onReorder()
                }
              },
              onDragCancel = {
                draggingPlatform = null
                dragOffset = 0f
              },
              onDrag = { change, dragAmount ->
                change.consume()
                val list = latestList
                val from = list.indexOf(platform)
                if (from < 0) return@detectDragGesturesAfterLongPress

                dragOffset += dragAmount.y
                // 每拖过约一行的距离就与相邻项交换一次
                val step = (dragOffset / stride).roundToInt()
                if (step != 0) {
                  val target = (from + step).coerceIn(0, list.lastIndex)
                  if (target != from) {
                    onMove(from, target)
                    dragOffset -= (target - from) * stride
                  }
                }
              },
            )
          },
        shape = RoundedCornerShape(12.dp),
        color = if (isDragging) {
          MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
          MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
        },
        border = BorderStroke(
          1.dp,
          if (isDragging) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
          } else {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
          },
        ),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
      ) {
        Row(
          modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text(
            text = if (platform in disabled) "${platform.title}（已关闭）" else platform.title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (platform in disabled) {
              MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            } else {
              Color.Unspecified
            },
            modifier = Modifier.weight(1f),
          )
          DragHandleIcon(
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDragging) 0.85f else 0.45f),
          )
        }
      }
    }
  }
}

/** 简单的六点拖拽手柄（用 Canvas 画，不依赖扩展图标库）。 */
@Composable
private fun DragHandleIcon(
  tint: Color,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier = modifier.size(width = 18.dp, height = 22.dp)) {
    val radius = 1.7.dp.toPx()
    val xs = listOf(size.width * 0.3f, size.width * 0.7f)
    val ys = listOf(size.height * 0.2f, size.height * 0.5f, size.height * 0.8f)
    for (y in ys) {
      for (x in xs) {
        drawCircle(color = tint, radius = radius, center = Offset(x, y))
      }
    }
  }
}

@Composable
private fun BasicSettingsSection(
  appState: AppState,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 18.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    SettingsSectionHeader(title = "基本设置", onBack = onBack)

    // 记住栏目（默认开启）
    SettingsCard {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text("记住栏目", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
          Text(
            text = "在斗鱼、虎牙、抖音、B站切换栏目（如网游竞技、单机热游）时自动记住，再次打开该平台时恢复上次选择的栏目。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
          )
        }
        Switch(
          checked = appState.rememberCategoryEnabled,
          onCheckedChange = appState::updateRememberCategoryEnabled,
        )
      }
    }

    // 小卡片模式（默认开启）
    SettingsCard {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text("小卡片模式", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
          Text(
            text = "开启后直播间卡片变小、首页与平台页切换到 3 列，一屏可展示更多直播间；关闭则恢复 2 列大卡片。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
          )
        }
        Switch(
          checked = appState.compactCardEnabled,
          onCheckedChange = appState::updateCompactCardEnabled,
        )
      }
    }

    // 默认横屏（默认关闭）
    SettingsCard {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text("默认横屏", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
          Text(
            text = "开启后点进任意直播间默认以横屏全屏观看，更贴合大屏看直播的体验；关闭则保持竖屏进入（平板等系统本身就是横屏的设备，也会以竖屏进入直播间）。该设置会持久保存，重启 App 后仍保持当前开关状态。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
          )
        }
        Switch(
          checked = appState.landscapeEnabled,
          onCheckedChange = appState::updateLandscapeEnabled,
        )
      }
    }

    // 退出时清理缓存（默认开启）
    SettingsCard {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text("退出时清理缓存", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
          Text(
            text = "开启后每次退出 App 自动清理直播缓存、临时文件等垃圾数据；登录状态（如 B站）与设置、关注列表均保留。默认开启。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
          )
        }
        Switch(
          checked = appState.exitCleanupEnabled,
          onCheckedChange = appState::updateExitCleanupEnabled,
        )
      }
    }

    // 屏幕刷新率（默认开启 = 最高刷新率）
    SettingsCard {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text("屏幕高刷", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
          Text(
            text = "开启后使用系统最高刷新率（高刷屏 90/120/144Hz 生效，滑动与动画更流畅）；关闭则锁定 60Hz 标准刷新率，更省电。切换瞬间可能有一次轻微闪烁，属正常现象。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
          )
        }
        Switch(
          checked = appState.highRefreshEnabled,
          onCheckedChange = appState::updateHighRefreshEnabled,
        )
      }
    }

    // 播放画质（默认最高）
    SettingsCard {
      Text("播放画质", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = "默认「最高」：不限制分辨率上限，优先取流中的最高码率，保证画面最清晰。网络较差时可下调档位换取流畅。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
      )
      Spacer(modifier = Modifier.height(10.dp))
      VideoQualitySelector(
        selected = appState.videoQuality,
        onSelect = appState::updateVideoQuality,
      )
    }

    // 主题模式（亮色 / 暗色 / 跟随系统）
    SettingsCard {
      Text("主题模式", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = "选择浅色、深色或跟随系统。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
      )
      Spacer(modifier = Modifier.height(10.dp))
      ThemeModeSelector(
        selected = appState.themeMode,
        onSelect = appState::updateThemeMode,
      )
    }

    // 全局颜色
    SettingsCard {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
          imageVector = Icons.Default.Palette,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
        Text("全局颜色", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
      }
      Text(
        text = "自定义主题强调色，会应用到选中栏目高亮、弹幕昵称颜色等位置。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
      )
      Spacer(modifier = Modifier.height(10.dp))
      AccentColorPalette(
        selectedHex = appState.accentColorHex,
        onSelect = { hex -> appState.setAccentColor(hex) },
      )
      Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { appState.setAccentColor(DEFAULT_ACCENT_HEX) }) {
          Text("恢复默认颜色")
        }
      }
    }

    // 悬浮毛玻璃底栏叠在内容之上，滚动内容底部预留浮岛高度，避免末项被遮挡
    Spacer(
      modifier = Modifier.height(
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + DockContentClearance,
      ),
    )
  }
}

@Composable
private fun ThemeModeSelector(
  selected: ThemeMode,
  onSelect: (ThemeMode) -> Unit,
  modifier: Modifier = Modifier,
) {
  val options = listOf(
    ThemeMode.System to "跟随系统",
    ThemeMode.Light to "亮色",
    ThemeMode.Dark to "暗色",
  )
  Row(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
      .padding(4.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    options.forEach { (mode, label) ->
      val isSelected = mode == selected
      Box(
        modifier = Modifier
          .weight(1f)
          .clip(RoundedCornerShape(9.dp))
          .background(
            if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
          )
          .clickable { onSelect(mode) }
          .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = label,
          style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
          color = if (isSelected) Color.Black else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
          maxLines = 1,
        )
      }
    }
  }
}

@Composable
private fun SyncSettingsSection(
  appState: AppState,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 18.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    SettingsSectionHeader(title = "数据同步", onBack = onBack)
    // 功能与原「首页 → 数据同步」完全一致，仅入口位置调整到设置内
    SyncScreen(
      appState = appState,
      modifier = Modifier.fillMaxSize(),
    )

    // 悬浮毛玻璃底栏叠在内容之上，滚动内容底部预留浮岛高度，避免末项被遮挡
    Spacer(
      modifier = Modifier.height(
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + DockContentClearance,
      ),
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentColorPalette(
  selectedHex: String,
  onSelect: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val selectedNormalized = selectedHex.trim().removePrefix("#").lowercase()
  val defaultNormalized = DEFAULT_ACCENT_HEX.trim().removePrefix("#").lowercase()
  // 未显式设置（空值）时，等价于默认青色
  val effectiveSelected = if (selectedNormalized.isEmpty()) defaultNormalized else selectedNormalized
  FlowRow(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    PresetAccentColors.forEach { (label, hex) ->
      val normalized = hex.trim().removePrefix("#").lowercase()
      val active = normalized == effectiveSelected
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Box(
          modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
              if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent,
            )
            .padding(4.dp),
          contentAlignment = Alignment.Center,
        ) {
          Surface(
            modifier = Modifier
              .size(34.dp)
              .clip(CircleShape)
              .clickable { onSelect(hex) },
            shape = CircleShape,
            color = Color(0xFF000000L or (normalized.toLongOrNull(16) ?: 0L)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
          ) {
            Box(contentAlignment = Alignment.Center) {
              if (active) {
                Icon(
                  imageVector = Icons.Default.Check,
                  contentDescription = null,
                  tint = Color.Black,
                  modifier = Modifier.size(18.dp),
                )
              }
            }
          }
        }
        Text(
          text = label,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (active) 0.9f else 0.55f),
          fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
        )
      }
    }
  }
}
