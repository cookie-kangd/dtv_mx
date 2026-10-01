package dtv.mobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.darkColorScheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dtv.mobile.model.Platform
import dtv.mobile.model.Streamer
import dtv.mobile.repo.DanmakuMessage
import dtv.mobile.repo.DouyuPlayInfo
import dtv.mobile.repo.DouyuPlayVariant
import dtv.mobile.repo.DtvRepository
import dtv.mobile.repo.TwitchPlayInfo
import dtv.mobile.state.AppState
import dtv.mobile.state.VideoQuality
import dtv.mobile.theme.DtvColors
import dtv.mobile.ui.DockContentClearance
import dtv.mobile.ui.components.DtvCardDefaults
import dtv.mobile.ui.components.NetworkImage
import dtv.mobile.ui.player.PictureInPicture
import dtv.mobile.ui.player.StreamPlayer
import dtv.mobile.ui.system.FullscreenEffect
import dtv.mobile.ui.system.PlatformBackHandler
import dtv.mobile.ui.system.rememberNotificationPermissionRequester
import dtv.mobile.util.normalizeHttpUrl
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlin.math.ceil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
  appState: AppState,
  streamer: Streamer?,
  modifier: Modifier = Modifier,
) {
  var url by remember(streamer?.roomId) { mutableStateOf<String?>(null) }
  // 「本房间是否已经起播过」：切画质/线路时 reloadUrl() 会先把 url 置 null 再赋新值，
  // 若弹幕 effect 直接以 url 为 key，切一次画质就会把弹幕连接整条拆掉重连
  // （清空已有弹幕、B站还要重走一次鉴权）。改成这个只在起播前 false、
  // 起播后恒为 true 的标志，切画质时弹幕连接保持不动。
  var error by remember(streamer?.roomId) { mutableStateOf<String?>(null) }
  var loading by remember(streamer?.roomId) { mutableStateOf(false) }
  var playInfo by remember(streamer?.roomId) { mutableStateOf<DouyuPlayInfo?>(null) }
  // 画质/线路选择只对「当前直播间」生效：状态按 roomId 记忆且不做持久化，
  // 进入任意直播间时都会重置，然后重新读取「设置-基本设置-画质」的全局档位。
  var selectedDouyuRate by remember(streamer?.roomId) { mutableStateOf<String?>(null) }
  var selectedDouyuCdn by remember(streamer?.roomId) { mutableStateOf<String?>(null) }
  var selectedDouyinQuality by remember(streamer?.roomId) { mutableStateOf<String?>(null) }
  var selectedBilibiliQn by remember(streamer?.roomId) { mutableStateOf<Int?>(null) }
  var selectedTwitchQuality by remember(streamer?.roomId) { mutableStateOf<String?>(null) }
  var twitchInfo by remember(streamer?.roomId) { mutableStateOf<TwitchPlayInfo?>(null) }
  var showSettings by remember(streamer?.roomId) { mutableStateOf(false) }

  var danmakuEnabled by remember(streamer?.roomId) { mutableStateOf(true) }
  var danmakuMessages by remember(streamer?.roomId) { mutableStateOf<List<DanmakuMessage>>(emptyList()) }
  var danmakuMax by remember { mutableIntStateOf(200) }
  // 弹幕投放序号 / 本批新增条数：滚动弹幕靠 revision 判定「来了新弹幕」。
  // 不能用 messages.size 当判据——缓冲区满后 takeLast 让 size 恒为 danmakuMax，
  // size 不再增长会导致横屏滚动弹幕永久停止上新（热门房一两分钟即触发）。
  var danmakuRevision by remember(streamer?.roomId) { mutableIntStateOf(0) }
  var danmakuNewCount by remember(streamer?.roomId) { mutableIntStateOf(0) }
  val mergeDanmakuBatch: (List<DanmakuMessage>) -> Unit = { batch ->
    if (batch.isNotEmpty()) {
      danmakuMessages = (danmakuMessages + batch).takeLast(danmakuMax)
      danmakuNewCount = batch.size.coerceAtMost(danmakuMax)
      danmakuRevision += 1
    }
  }
  // 「熄屏听播」（播放器右侧耳机按钮）：仅作用于当前直播间，退出即自动关闭，不做持久化
  var listenOnly by remember(streamer?.roomId) { mutableStateOf(false) }
  // Android 13+ 需要授权才能在通知栏看到听播常驻通知；未授权不影响播放
  val requestNotificationPermission = rememberNotificationPermissionRequester()
  var videoAspectRatio by remember(streamer?.roomId) { mutableStateOf<Float?>(null) }
  var videoReady by remember(streamer?.roomId) { mutableStateOf(false) }
  var playbackStarted by remember(streamer?.roomId) { mutableStateOf(false) }
  // 「默认横屏」开启时，进入直播间即按全屏横屏（Manual）方式观看，离开时恢复竖屏
  var fullscreen by remember(streamer?.roomId) { mutableStateOf(appState.landscapeEnabled) }
  var fullscreenEntry by remember(streamer?.roomId) {
    mutableStateOf(if (appState.landscapeEnabled) FullscreenEntry.Manual else FullscreenEntry.None)
  }
  // 关闭直播间标记：一旦置位，FullscreenEffect 不再允许重新锁定横屏，并强制回落竖屏，
  // 彻底消除「退出瞬间先横屏闪一下再回竖屏」的 BUG。
  // 根因：关闭过程中 streamer 会短暂变化，触发 remember(streamer?.roomId) 重新初始化，
  // 把 fullscreen/fullscreenEntry 复位成「默认横屏」状态，画面在 dispose 前闪现横屏。
  var isClosing by remember { mutableStateOf(false) }
  // 进入直播间那一帧的设备方向（true=设备本身是横屏，如平板）。
  // 用于区分「平板这类进房时就是横屏的设备」与「手机播放中主动转屏」：
  // 前者在未开启「默认横屏」时必须锁竖屏，后者仍保留转屏自动全屏的原有行为。
  // 只在首帧锁存一次，避免锁竖屏后方向变化导致锁定条件来回翻转（抖动）。
  var entryLandscape by remember(streamer?.roomId) { mutableStateOf<Boolean?>(null) }

  val scope = rememberCoroutineScope()
  // 当前直播间 roomId 的实时快照。异步解析播放地址返回后要拿它判定结果是否仍属于当前房间：
  // 切房时旧请求不会被自动取消（reloadUrl 用的是 composable 的 scope 而非 LaunchedEffect），
  // 若不校验就会把上一个房间的地址写进新房间，表现为「点进去播的是上一个直播间」。
  val currentRoomId = remember { mutableStateOf<String?>(null) }
  // 组合阶段直接写 state 属于副作用：组合被丢弃/重试时会写入「不该生效」的值。
  // 改到 SideEffect 里，在组合成功提交后再同步，异步解析返回时读到的仍是当前房间。
  SideEffect { currentRoomId.value = streamer?.roomId }
  // 画质/线路切换触发的地址解析任务：再次切换或切房时先取消上一个，避免旧结果覆盖新结果
  var resolveJob by remember { mutableStateOf<Job?>(null) }

  // 画中画：仅在支持的设备上显示按钮；进入后隐藏所有叠加层，只保留视频画面。
  val pipSupported = remember { PictureInPicture.isSupported() }
  val isInPip by PictureInPicture.isInPictureInPictureMode

  DisposableEffect(Unit) {
    onDispose { appState.playerFullscreen = false }
  }
  LaunchedEffect(fullscreen) {
    appState.playerFullscreen = fullscreen
  }

  // 横屏全屏时左上角的「X」：仅退出全屏回到竖屏，不关闭播放器。
  // 行为等同于手动全屏下系统返回键的第一次返回（离开全屏、转回竖屏、仍停留在播放器内）。
  val exitFullscreen: () -> Unit = {
    if (fullscreen && !isClosing) {
      fullscreen = false
      fullscreenEntry = if (fullscreenEntry == FullscreenEntry.Manual) {
        FullscreenEntry.ManualOff
      } else {
        FullscreenEntry.None
      }
    }
  }

  // 画中画：进入前隐藏底部切换栏（playerFullscreen=true），使 PiP 画面只含视频，
  // 与横屏全屏下的画中画一致；退出 PiP 时再恢复原值。
  // 注意：enter() 的进入结果由系统异步回调 onPictureInPictureModeChanged 上报，
  // 因此仅在 enter() 返回 true（已提交进入）时才翻转 playerFullscreen，
  // 避免「按下就先隐藏底栏、但进入失败」造成的状态残留；退出 PiP 时统一在
  // LaunchedEffect 里把 playerFullscreen 恢复原值并清空标记。
  val pipPriorFullscreen = remember { mutableStateOf<Boolean?>(null) }
  val enterPip: () -> Unit = {
    val ok = PictureInPicture.enter(videoAspectRatio?.takeIf { it > 0f } ?: (16f / 9f))
    if (ok) {
      pipPriorFullscreen.value = appState.playerFullscreen
      appState.playerFullscreen = true
    }
  }
  LaunchedEffect(isInPip) {
    if (!isInPip) {
      // 退出画中画：无论之前是否成功进入，都复位 playerFullscreen 并清空残留标记，
      // 防止再次点击全屏时叠加上一次 PiP 的旧状态而触发异常。
      pipPriorFullscreen.value?.let { appState.playerFullscreen = it }
      pipPriorFullscreen.value = null
    }
  }

  // 关闭直播间（X 按钮 / 直接退出）时，若正处于手动横屏（含「默认横屏」进入），
  // 先主动切回竖屏再退出，避免退出瞬间因为全屏横屏状态尚未解除、切屏淡出动画里还停留在
  // 横屏，结束 dispose 时才回落竖屏——表现为「先横屏闪一下再恢复竖屏」的 BUG。
  // 系统返回键走 PlatformBackHandler 已做过同样的切换，这里让 X 按钮也走同一条路径。
  val requestClose: () -> Unit = {
    isClosing = true
    if (fullscreen) {
      fullscreen = false
      fullscreenEntry = if (fullscreenEntry == FullscreenEntry.Manual) {
        FullscreenEntry.ManualOff
      } else {
        FullscreenEntry.None
      }
    }
    appState.back()
  }

  LaunchedEffect(streamer?.roomId) {
    val s = streamer ?: return@LaunchedEffect
    videoAspectRatio = null
    videoReady = false
    if (!s.isLive) {
      loading = false
      error = null
      url = null
      playInfo = null
      return@LaunchedEffect
    }
    loading = true
    error = null
    url = null
    playInfo = null
    selectedDouyuRate = null
    selectedDouyuCdn = null
    selectedDouyinQuality = null
    selectedBilibiliQn = null
    selectedTwitchQuality = null
    twitchInfo = null
    when (s.platform) {
      Platform.Douyu -> {
        // 初始画质跟随「设置-基本设置-画质」：映射为斗鱼解析器能识别的语义档位名
        // （原画/蓝光/高清/标清），由解析器内部挑选正确 rate。
        // 关键：只「解析一次」并赋值 url——先播默认流再切档位正是造成"闪一下"的元凶。
        val initialQualityName = pickDouyuQualityName(appState.videoQuality)
        runCatching {
          appState.repo.resolveDouyuStreamUrl(
            roomId = s.roomId,
            quality = initialQualityName,
            cdn = selectedDouyuCdn,
          )
        }.onSuccess { resolvedUrl ->
          url = resolvedUrl
          // 异步拉取可选清晰度/线路，仅用于设置面板展示与档位高亮，不参与起播，避免阻塞/闪烁
          scope.launch {
            runCatching { appState.repo.fetchDouyuPlayInfo(roomId = s.roomId) }
              .onSuccess { info ->
                // 异步返回时可能已切到别的直播间：清晰度列表属于上一个房间，必须丢弃，
                // 否则会把上一房间的档位高亮/线路写到新房间的设置面板上。
                if (currentRoomId.value != s.roomId) return@onSuccess
                playInfo = info
                if (selectedDouyuRate == null) {
                  selectedDouyuRate = matchDouyuRate(info.variants, initialQualityName)
                }
              }
              .onFailure {
                // 播放地址已就绪，仅清晰度列表获取失败，可忽略
              }
          }
        }.onFailure { error = it.message ?: "获取播放地址失败" }
      }
      Platform.Huya -> {
        runCatching { appState.repo.resolveHuyaStreamUrl(roomId = s.roomId) }
          .onSuccess { url = it }
          .onFailure { error = it.message ?: "获取虎牙播放地址失败" }
      }
      Platform.Douyin -> {
        // 去掉"自动"后：默认档位跟随「设置-基本设置-画质」
        if (selectedDouyinQuality == null) {
          selectedDouyinQuality = pickDouyinQuality(appState.videoQuality)
        }
        runCatching {
          resolveDouyinWithFallback(
            repo = appState.repo,
            webRid = s.roomId,
            preferred = selectedDouyinQuality ?: "ORIGIN",
          )
        }.onSuccess { (resolvedUrl, actualQuality) ->
          selectedDouyinQuality = actualQuality
          url = resolvedUrl
        }.onFailure { error = it.message ?: "获取抖音播放地址失败" }
      }
      Platform.Bilibili -> {
        // 去掉"自动"后：默认档位跟随「设置-基本设置-画质」
        if (selectedBilibiliQn == null) {
          selectedBilibiliQn = pickBilibiliQn(appState.videoQuality)
        }
        runCatching {
          resolveBilibiliWithFallback(
            repo = appState.repo,
            roomId = s.roomId,
            preferred = selectedBilibiliQn ?: 10000,
          )
        }.onSuccess { (resolvedUrl, actualQn) ->
          selectedBilibiliQn = actualQn
          url = resolvedUrl
        }.onFailure { error = it.message ?: "获取B站播放地址失败" }
      }
      Platform.Twitch -> {
        // 默认 quality=null -> 返回 master m3u8，交给 ExoPlayer 自动码率适配（ABR），
        // 起播最快且不会因「设置里挑的档位该频道没有」而回退错档。
        runCatching { appState.repo.resolveTwitchStreamUrl(login = s.roomId, quality = null) }
          .onSuccess { resolvedUrl ->
            url = resolvedUrl
            // 异步拉画质列表，仅用于设置面板展示
            scope.launch {
              runCatching { appState.repo.fetchTwitchPlayInfo(login = s.roomId) }
                .onSuccess { info ->
                  if (currentRoomId.value != s.roomId) return@onSuccess
                  twitchInfo = info
                }
            }
          }
          .onFailure { error = it.message ?: "获取Twitch播放地址失败" }
      }
      else -> {
        error = "暂不支持的平台：${s.platform.title}"
      }
    }
    loading = false
  }

  val blockKeywordsLower by remember {
    derivedStateOf { appState.danmuBlockKeywords.map { it.lowercase() }.filter { it.isNotBlank() } }
  }

  LaunchedEffect(url) {
    if (url != null) playbackStarted = true
  }

  LaunchedEffect(streamer?.roomId, streamer?.platform, danmakuEnabled, listenOnly, playbackStarted, blockKeywordsLower) {
    val s = streamer ?: return@LaunchedEffect
    // 熄屏听播开启时彻底断开弹幕连接：省掉 WebSocket 收包、列表重组与网络开销，
    // 让后台占用真正只剩一路音频解码。
    if (!danmakuEnabled || listenOnly) {
      danmakuMessages = emptyList()
      return@LaunchedEffect
    }
    // 地址还没解析出来先不连弹幕；注意这里看的是 playbackStarted 而不是 url，
    // 这样切画质过程中 url 短暂为 null 也不会把连接拆掉。
    if (!playbackStarted) {
      danmakuMessages = emptyList()
      return@LaunchedEffect
    }

    val flow = when (s.platform) {
      Platform.Douyu -> appState.repo.observeDouyuDanmaku(s.roomId)
      Platform.Huya -> appState.repo.observeHuyaDanmaku(s.roomId)
      Platform.Douyin -> appState.repo.observeDouyinDanmaku(s.roomId)
      Platform.Bilibili -> appState.repo.observeBilibiliDanmaku(s.roomId)
      Platform.Twitch -> appState.repo.observeTwitchDanmaku(s.roomId)
      else -> null
    } ?: return@LaunchedEffect

    danmakuMessages = emptyList()
    try {
      // 批量合流：热门房间每秒可达数十条弹幕。逐条改 state 会造成
      // 「每条两次 O(n) 整表复制 + 整块弹幕面板重组」。先攒批再按节奏一次性并入，
      // 复制与重组次数大幅下降；单条弹幕最多延迟 flushIntervalMs 上屏。
      val batchSize = 24
      val flushIntervalMs = 120L
      val pending = ArrayList<DanmakuMessage>(batchSize)
      coroutineScope {
        // 定时排水：保证最后一批（之后没有新弹幕时）也能上屏，不会滞留缓冲区
        val drainJob = launch {
          while (isActive) {
            delay(flushIntervalMs)
            if (pending.isNotEmpty()) {
              mergeDanmakuBatch(pending)
              pending.clear()
            }
          }
        }
        try {
          flow.collect { msg ->
            if (blockKeywordsLower.isNotEmpty()) {
              val contentLower = msg.content.lowercase()
              if (blockKeywordsLower.any { contentLower.contains(it) }) return@collect
            }
            pending.add(msg)
            // 突发弹幕攒够一批立即合并，避免缓冲区无限增长
            if (pending.size >= batchSize) {
              mergeDanmakuBatch(pending)
              pending.clear()
            }
          }
        } finally {
          drainJob.cancel()
          mergeDanmakuBatch(pending)
          pending.clear()
        }
      }
    } catch (t: Throwable) {
      if (t is CancellationException) throw t
      // Swallow network/parse errors to avoid crashing the player UI.
    }
  }

  fun reloadUrl() {
    val s = streamer ?: return
    val requestedRoomId = s.roomId
    // 连点切换画质/线路时，取消上一个仍在解析的任务，避免慢的旧结果后到覆盖新的
    resolveJob?.cancel()
    resolveJob = scope.launch {
      loading = true
      error = null
      url = null
      videoAspectRatio = null
      videoReady = false
      val result = when (s.platform) {
        Platform.Douyu -> runCatching {
          appState.repo.resolveDouyuStreamUrl(
            roomId = s.roomId,
            quality = selectedDouyuRate,
            cdn = selectedDouyuCdn,
          )
        }
        Platform.Huya -> runCatching { appState.repo.resolveHuyaStreamUrl(roomId = s.roomId) }
        Platform.Douyin -> runCatching { appState.repo.resolveDouyinStreamUrl(webRid = s.roomId, desiredQuality = selectedDouyinQuality) }
        Platform.Bilibili -> runCatching { appState.repo.resolveBilibiliStreamUrl(roomId = s.roomId, qn = selectedBilibiliQn) }
        Platform.Twitch -> runCatching { appState.repo.resolveTwitchStreamUrl(login = s.roomId, quality = selectedTwitchQuality) }
        else -> Result.failure(IllegalStateException("暂不支持的平台：${s.platform.title}"))
      }
      // 解析期间若已切到别的直播间，这份结果作废，交给新房间的解析流程赋值
      if (currentRoomId.value != requestedRoomId) return@launch
      result
        .onSuccess { url = it }
        .onFailure { error = it.message ?: "获取播放地址失败" }
      loading = false
    }
  }
  BoxWithConstraints(
    modifier = Modifier
      .fillMaxSize()
      .then(modifier),
  ) {
    val isPortraitLayout = maxHeight >= maxWidth
    val isLandscapeLayout = !isPortraitLayout

    // 未开启「默认横屏」时，若进房瞬间设备就是横屏（平板），强制锁定竖屏：
    // 进直播间不再跟随系统方向，退出一次即可回到竖屏（无需退出两次）。
    // 用户主动点全屏按钮 / 开启「默认横屏」时该锁定自动让位给横屏。
    val forcePortrait = entryLandscape == true &&
      !appState.landscapeEnabled &&
      !fullscreen &&
      fullscreenEntry == FullscreenEntry.None &&
      !isInPip &&
      !isClosing

    FullscreenEffect(
      enabled = fullscreen && !isClosing && !isInPip,
      lockLandscape = fullscreenEntry == FullscreenEntry.Manual && !isClosing,
      exitToPortrait = fullscreenEntry == FullscreenEntry.ManualOff || isClosing,
      forcePortrait = forcePortrait,
    )
    // 手势返回（含安卓系统手势返回 / 三键返回）统一在此拦截：
    // 默认横屏进入直播间后第一次返回 -> 退出全屏、转回竖屏、仍停留在播放器内；
    // 已竖屏后再返回（或自动全屏返回）-> 关闭播放器，关闭前先置 isClosing。
    // 关键点：enabled 必须为 true（常驻拦截）。若只在 fullscreen 时拦截，
    // 第一次返回后 fullscreen 已变 false，第二次手势返回会绕过本处理器、直接走上层
    // appState.back() 关闭播放器且未置 isClosing，关闭过程中 streamer 变化会触发
    // fullscreen/fullscreenEntry 重新初始化成横屏，导致 FullscreenEffect 再次锁横屏而「闪一下」。
    PlatformBackHandler(enabled = true) {
      if (fullscreenEntry == FullscreenEntry.Auto) {
        // 横屏旋转自动进入的全屏：返回直接退出播放器，并置位关闭标记避免闪横屏。
        isClosing = true
        appState.back()
      } else if (fullscreen) {
        // 手动横屏（含「默认横屏」进入直播间）第一次返回：离开全屏、转回竖屏，仍停留在播放器内。
        fullscreen = false
        fullscreenEntry = if (fullscreenEntry == FullscreenEntry.Manual) {
          FullscreenEntry.ManualOff
        } else {
          FullscreenEntry.None
        }
      } else {
        // 已处于竖屏（ManualOff / None）：退出播放器，isClosing 守卫确保全程保持竖屏。
        isClosing = true
        appState.back()
      }
    }

    val settingsStreamer = streamer
    val showSettingsDrawer = showSettings && settingsStreamer != null && fullscreen && isLandscapeLayout
    val showSettingsSheet = showSettings && settingsStreamer != null && !showSettingsDrawer

    val settingsContent: @Composable () -> Unit = settingsContent@{
      val s = settingsStreamer ?: return@settingsContent
      Column(
        modifier = Modifier
          .padding(horizontal = 16.dp, vertical = 10.dp)
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text("播放设置", style = MaterialTheme.typography.titleMedium)

        if (s.platform == Platform.Douyu || s.platform == Platform.Douyin || s.platform == Platform.Bilibili || s.platform == Platform.Twitch) {
          Text("画质", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
          when (s.platform) {
            Platform.Douyu -> {
              RowWrap(
                items = playInfo?.variants.orEmpty().map { it.name to it.rate.toString() },
                selected = selectedDouyuRate,
                onSelect = {
                  selectedDouyuRate = it
                  reloadUrl()
                },
              )
            }
            Platform.Douyin -> {
              RowWrap(
                items = listOf(
                  "原画" to "ORIGIN",
                  "超清" to "FULL_HD1",
                  "高清" to "HD1",
                  "标清" to "SD1",
                ),
                selected = selectedDouyinQuality,
                onSelect = {
                  selectedDouyinQuality = it
                  reloadUrl()
                },
              )
            }
            Platform.Bilibili -> {
              RowWrapInt(
                items = listOf(
                  "原画" to 10000,
                  "蓝光" to 400,
                  "超清" to 250,
                  "高清" to 150,
                  "流畅" to 80,
                ),
                selected = selectedBilibiliQn,
                onSelect = {
                  selectedBilibiliQn = it
                  reloadUrl()
                },
              )
            }
            Platform.Twitch -> {
              // 「自动」= master m3u8 交给播放器 ABR；其余为 usher 返回的固定档位
              RowWrap(
                // 左侧显示中文档位名（原画 / 720P60 / 仅音频），右侧 value 仍是 usher 的
                // 原始代号（chunked / 720p60 / audio_only）——匹配档位要用代号。
                items = listOf("自动" to null) + twitchInfo?.variants.orEmpty().map { it.display to it.name },
                selected = selectedTwitchQuality,
                onSelect = {
                  selectedTwitchQuality = it
                  reloadUrl()
                },
              )
            }
            else -> Unit
          }
        }

        if (s.platform == Platform.Douyu) {
          Text("线路", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
          RowWrap(
            items = listOf("自动" to null) + (playInfo?.cdns.orEmpty().map { it to it }),
            selected = selectedDouyuCdn,
            onSelect = {
              selectedDouyuCdn = it
              reloadUrl()
            },
          )
        }

        Text("弹幕", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
        RowWrap(
          items = listOf("开" to "on", "关" to "off"),
          selected = if (danmakuEnabled) "on" else "off",
          onSelect = { danmakuEnabled = it == "on" },
        )

        Text("弹幕字体大小", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
        RowWrapFloat(
          items = listOf(
            "小" to 0.90f,
            "中" to 1.00f,
            "大" to 1.15f,
          ),
          selected = appState.danmakuFontScale,
          onSelect = { appState.updateDanmakuFontScale(it) },
        )

        Text("弹幕透明度", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
        RowWrapFloat(
          items = listOf(
            "100%" to 1.00f,
            "80%" to 0.85f,
            "60%" to 0.70f,
            "40%" to 0.55f,
          ),
          selected = appState.danmakuOpacity,
          onSelect = { appState.updateDanmakuOpacity(it) },
        )

        Text("弹幕显示区域", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
        RowWrapFloat(
          items = listOf(
            "上1/4" to 0.25f,
            "上1/2" to 0.5f,
            "上3/4" to 0.75f,
            "全屏" to 1.0f,
          ),
          selected = appState.danmakuAreaFraction,
          onSelect = { appState.updateDanmakuAreaFraction(it) },
        )

        if (isLandscapeLayout) {
          Text("横屏弹幕字体", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
          RowWrapFloat(
            items = listOf(
              "小" to 1.0f,
              "中" to 1.15f,
              "大" to 1.30f,
              "特大" to 1.45f,
            ),
            selected = appState.landscapeDanmakuFontScale,
            onSelect = { appState.updateLandscapeDanmakuFontScale(it) },
          )
        }

        SpacerLine()
      }
    }

    PlatformBackHandler(enabled = showSettingsDrawer || showSettingsSheet) { showSettings = false }

    val effectiveAspect = videoAspectRatio?.takeIf { it > 0f }
    val isVideoAspectKnown = effectiveAspect != null
    val layoutAspect = effectiveAspect ?: (16f / 9f)
    val isVerticalVideo = isVideoAspectKnown && (effectiveAspect!! < 1f)
    val isHorizontalVideo = isVideoAspectKnown && !isVerticalVideo
    val verticalFullBleed = !fullscreen && isVerticalVideo
    val showFullPageLoading = !fullscreen &&
      !verticalFullBleed &&
      !isInPip &&
      streamer?.isLive == true &&
      url == null &&
      error == null &&
      loading

    LaunchedEffect(isLandscapeLayout) {
      if (isClosing || isInPip) return@LaunchedEffect
      // 锁存进房时的设备方向；进房即横屏且未开「默认横屏」（平板）时不走自动全屏，
      // 由 forcePortrait 锁竖屏，避免「平板进直播间默认横屏」。
      if (entryLandscape == null) {
        entryLandscape = isLandscapeLayout
        if (isLandscapeLayout && !appState.landscapeEnabled) return@LaunchedEffect
      }
      // Rotating to landscape should behave like fullscreen (hide bottom bar, system bars).
      if (isLandscapeLayout && !fullscreen && fullscreenEntry != FullscreenEntry.ManualOff) {
        fullscreen = true
        fullscreenEntry = FullscreenEntry.Auto
      } else if (!isLandscapeLayout && fullscreenEntry == FullscreenEntry.Auto) {
        fullscreen = false
        fullscreenEntry = FullscreenEntry.None
      } else if (!isLandscapeLayout && fullscreenEntry == FullscreenEntry.ManualOff) {
        fullscreenEntry = FullscreenEntry.None
      }
    }

    val content: @Composable () -> Unit = {
      // 横屏全屏或画中画时，视频都铺满容器并以黑色打底，保证 PiP 画面与横屏一致（不带入底部栏等杂色）。
      val isFullBleedVideo = fullscreen || isInPip
      Column(
        modifier = Modifier
          .fillMaxSize()
          .then(if (isFullBleedVideo) Modifier.background(Color.Black) else Modifier),
      ) {
            if (!fullscreen && !verticalFullBleed && !isInPip) {
          PlayerHeader(
            streamer = streamer,
            onBack = requestClose,
            followed = streamer?.let(appState::isFollowed) == true,
            onToggleFollow = { s -> appState.toggleFollow(s) },
            modifier = Modifier.fillMaxWidth(),
          )
          Spacer(modifier = Modifier.height(10.dp))
        }

        if (showFullPageLoading) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f),
            contentAlignment = Alignment.Center,
          ) {
            CenteredLoadingIndicator()
          }
          return@Column
        }

        val videoSurfaceShape = RoundedCornerShape(0.dp)
        val videoSurfaceColor = Color.Black
        val videoSurfaceModifier = if (isFullBleedVideo || verticalFullBleed) {
          Modifier.fillMaxSize()
        } else {
          Modifier.fillMaxWidth().aspectRatio(layoutAspect)
        }

        val canShowDanmaku = danmakuEnabled &&
          danmakuMessages.isNotEmpty() &&
          isVideoAspectKnown &&
          videoReady &&
          url != null &&
          !loading &&
          error == null

        Surface(
          shape = videoSurfaceShape,
          color = videoSurfaceColor,
          modifier = videoSurfaceModifier,
        ) {
          Box(modifier = Modifier.fillMaxSize()) {
            if (url != null) {
              StreamPlayer(
                url = url!!,
                fullscreen = fullscreen,
                liveMode = true,
                zoomToFill = verticalFullBleed,
                backgroundAudio = listenOnly,
                onVideoAspectRatioChanged = {
                  videoAspectRatio = it
                  if (it != null && it > 0f) videoReady = true
                },
                onError = {
                  if (it.startsWith("__retry_http__:")) {
                    error = null
                    url = it.removePrefix("__retry_http__:")
                  } else {
                    error = it
                    url = null
                  }
                },
                modifier = Modifier.fillMaxSize(),
              )
            } else {
              Box(
                modifier = Modifier
                  .fillMaxSize()
                  .padding(16.dp),
                contentAlignment = Alignment.Center,
              ) {
                when {
                  streamer == null -> {
                    Text(
                      text = "未选择直播间",
                      style = MaterialTheme.typography.titleMedium,
                      color = if (fullscreen) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.85f),
                    )
                  }
                streamer.isLive == false -> {
                  Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = if (fullscreen) Color.Black.copy(alpha = 0.30f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                  ) {
                    Column(
                      modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                      verticalArrangement = Arrangement.spacedBy(6.dp),
                      horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                      Text(
                        text = "主播未开播",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                        color = if (fullscreen) Color.White.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                      )
                      Text(
                        text = "当前直播间没有在直播",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (fullscreen) Color.White.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                      )
                    }
                  }
                }
                loading -> {
                  CircularProgressIndicator(
                    color = if (fullscreen) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                  )
                }
                error != null -> {
                  Text(
                    text = error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (fullscreen) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.85f),
                  )
                }
              }
            }
          }

            // 横屏全屏时左上角「X」：点击退出全屏回到竖屏（不关闭播放器）。
            if (fullscreen && !isInPip) {
              Surface(
                modifier = Modifier
                  .align(Alignment.TopStart)
                  .statusBarsPadding()
                  .padding(start = 12.dp, top = 6.dp)
                  .size(40.dp)
                  .clip(CircleShape)
                  .clickable(onClick = exitFullscreen),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "退出全屏",
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(22.dp),
                  )
                }
              }
            }

            if (verticalFullBleed && !isInPip) {
              PlayerHeader(
                streamer = streamer,
                onBack = requestClose,
                followed = streamer?.let(appState::isFollowed) == true,
                onToggleFollow = { s -> appState.toggleFollow(s) },
                modifier = Modifier
                  .align(Alignment.TopStart)
                  .fillMaxWidth(),
                overlay = true,
              )
            }

            val overlayDanmaku = canShowDanmaku && (fullscreen || isVerticalVideo)
            if (overlayDanmaku && !isInPip) {
              if (fullscreen && isHorizontalVideo) {
                ScrollingDanmakuOverlay(
                  resetKey = streamer?.roomId,
                  messages = danmakuMessages,
                  revision = danmakuRevision,
                  newCount = danmakuNewCount,
                  showUser = false,
                  areaFraction = appState.danmakuAreaFraction,
                  textScale = appState.landscapeDanmakuFontScale * appState.danmakuFontScale,
                  opacity = appState.danmakuOpacity,
                  modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                )
              } else {
                DanmakuOverlay(
                  messages = danmakuMessages,
                  showUser = true,
                  areaFraction = appState.danmakuAreaFraction,
                  transparentBackground = false,
                  textScale = appState.danmakuFontScale,
                  opacity = appState.danmakuOpacity,
                  modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                )
              }
            }

            if (!isInPip) {
              PlayerSideControlsOverlay(
                fullscreen = fullscreen,
                showFullscreen = isVideoAspectKnown && !isVerticalVideo,
                onToggleFullscreen = {
                  if (!isClosing) {
                    // 清空画中画残留标记，避免与本次全屏切换叠加导致状态异常
                    pipPriorFullscreen.value = null
                    if (fullscreen) {
                      fullscreen = false
                      fullscreenEntry = if (isLandscapeLayout) FullscreenEntry.ManualOff else FullscreenEntry.None
                    } else {
                      fullscreen = true
                      fullscreenEntry = FullscreenEntry.Manual
                    }
                  }
                },
                onOpenSettings = { showSettings = true },
                onReload = { reloadUrl() },
                listenOnly = listenOnly,
                onToggleListenOnly = {
                  val next = !listenOnly
                  // 开启时顺带申请通知权限，让常驻通知可见（未授权也不影响听播）
                  if (next) requestNotificationPermission()
                  listenOnly = next
                },
                pipSupported = pipSupported,
                onPip = enterPip,
                modifier = Modifier
                  .align(Alignment.CenterEnd)
                  .padding(end = 16.dp),
              )
            }
          }
        }

        if (!fullscreen && !verticalFullBleed && !isInPip) {
          if (canShowDanmaku && isHorizontalVideo) {
            HubDanmakuPanel(
              messages = danmakuMessages,
              revision = danmakuRevision,
              enhancedPortrait = isPortraitLayout,
              textScale = appState.danmakuFontScale,
              modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 12.dp)
                // 竖屏时毛玻璃浮岛底栏悬浮在内容之上：弹幕列表底部预留出
                // 导航条 + 浮岛的高度，避免最新弹幕被底栏遮住看不清。
                .padding(
                  bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                    DockContentClearance,
                ),
            )
          }
        }
      }
    }

    PlayerBackground(fullscreen = fullscreen, content = content)

    PlayerSettingsDrawer(
      visible = showSettingsDrawer,
      onDismissRequest = { showSettings = false },
    ) {
      Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
      ) {
        settingsContent()
      }
    }

    if (showSettingsSheet) {
      ModalBottomSheet(onDismissRequest = { showSettings = false }) {
        settingsContent()
      }
    }
  }
}

private enum class FullscreenEntry {
  None,
  Auto,
  Manual,
  ManualOff,
}

@Composable
private fun CenteredLoadingIndicator(
  modifier: Modifier = Modifier,
) {
  val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
  val border = if (isDark) Color.White.copy(alpha = 0.10f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
  val bg = if (isDark) Color.White.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
  val fg = if (isDark) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)

  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(18.dp),
    color = bg,
    border = BorderStroke(1.dp, border),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      CircularProgressIndicator(
        strokeWidth = 3.dp,
        modifier = Modifier.size(22.dp),
        color = MaterialTheme.colorScheme.primary,
      )

      val t = rememberInfiniteTransition(label = "loadingDots")
      val phase = t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 900, easing = LinearEasing)),
        label = "loadingDotsPhase",
      ).value
      val dots = when (((phase * 3f).toInt()) % 4) {
        0 -> ""
        1 -> "."
        2 -> ".."
        else -> "..."
      }

      Text(
        text = "加载中$dots",
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        color = fg,
      )
    }
  }
}

@Composable
private fun PlayerBackground(
  fullscreen: Boolean,
  content: @Composable () -> Unit,
) {
  val bg = MaterialTheme.colorScheme.background
  val accent = MaterialTheme.colorScheme.primary
  val isDark = bg.luminance() < 0.35f

  Box(
    modifier = Modifier
      .fillMaxSize()
      .then(if (fullscreen) Modifier.background(Color.Black) else Modifier),
  ) {
    if (!fullscreen) {
      // 静态渐变背景：Brush 在 drawWithCache 里按尺寸/配色缓存，
      // 之前 Canvas 的 draw lambda 每次执行都新建两个 Brush + 两个 alpha 拷贝
      // （播放页每 120ms 弹幕批就会让整页失效重画一次）。
      Box(
        modifier = Modifier
          .fillMaxSize()
          .drawWithCache {
            val linear = Brush.linearGradient(
              colors = listOf(
                bg,
                accent.copy(alpha = if (isDark) 0.05f else 0.10f),
                bg,
              ),
              start = Offset(0f, 0f),
              end = Offset(size.width, size.height),
            )
            val circleCenter = Offset(size.width * 0.80f, size.height * 0.18f)
            val circleRadius = size.width * 0.70f
            val radial = Brush.radialGradient(
              colors = listOf(accent.copy(alpha = if (isDark) 0.08f else 0.12f), Color.Transparent),
              center = circleCenter,
              radius = circleRadius,
            )
            onDrawBehind {
              drawRect(brush = linear)
              drawCircle(brush = radial, radius = circleRadius, center = circleCenter)
            }
          },
      )
    }
    content()
  }
}

@Composable
private fun PlayerHeader(
  streamer: Streamer?,
  onBack: () -> Unit,
  followed: Boolean,
  onToggleFollow: (Streamer) -> Unit,
  modifier: Modifier = Modifier,
  overlay: Boolean = false,
  ) {
    val liveDot = if (streamer?.isLive == true) MaterialTheme.colorScheme.primary else Color(0xFF9CA3AF)
    val glassBg = Brush.linearGradient(
      colors = listOf(
        Color.Black.copy(alpha = 0.42f),
        Color.Black.copy(alpha = 0.22f),
      ),
    )
    val glassBorder = Color.White.copy(alpha = 0.16f)

  Column(
    modifier = modifier
      .statusBarsPadding()
      .padding(vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(0.dp),
  ) {
    val closeSize = 40.dp
    val infoShape = RoundedCornerShape(closeSize / 2)
    val infoPrimary = Color.White.copy(alpha = 0.92f)
    val infoSecondary = Color.White.copy(alpha = 0.72f)
    val closeFg = Color.White.copy(alpha = 0.92f)

    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
      val available = (maxWidth - closeSize - 10.dp).coerceAtLeast(0.dp)
      val infoWidth = (maxWidth * 0.66f)
        .coerceAtMost(available)
        .coerceAtLeast(200.dp.coerceAtMost(available))
      val avatarSize = closeSize - 8.dp

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(
          modifier = Modifier
            .width(infoWidth)
            .height(closeSize)
            .clip(infoShape),
          shape = infoShape,
          color = Color.Transparent,
          border = BorderStroke(1.dp, glassBorder),
          tonalElevation = 0.dp,
          shadowElevation = 0.dp,
        ) {
          Box(modifier = Modifier.background(glassBg)) {
            Row(
              modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
          val avatar = normalizeHttpUrl(streamer?.avatarUrl)
          Box(modifier = Modifier.size(avatarSize)) {
            Box(
              modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f)),
              contentAlignment = Alignment.Center,
            ) {
              if (avatar != null) {
                NetworkImage(url = avatar, contentDescription = streamer?.name, modifier = Modifier.matchParentSize())
              } else {
                Text(
                  text = streamer?.name?.take(1).orEmpty(),
                  color = infoPrimary,
                  style = MaterialTheme.typography.titleSmall,
                  textAlign = TextAlign.Center,
                )
              }
            }

            if (streamer != null) {
              Box(
                modifier = Modifier
                  .align(Alignment.BottomEnd)
                  .offset(x = 1.dp, y = 1.dp)
                  .size(10.dp)
                  .clip(CircleShape)
                  .background(liveDot)
                  .border(width = 2.dp, color = Color.Transparent, shape = CircleShape),
              )
            }
          }

          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(0.dp),
          ) {
            Text(
              text = streamer?.name.orEmpty(),
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black),
              color = infoPrimary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = streamer?.title?.trim().orEmpty(),
              style = MaterialTheme.typography.labelSmall,
              color = infoSecondary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }

          if (streamer != null) {
            val iconTint = if (followed) DtvCardDefaults.FollowActive else infoSecondary
            Box(
              modifier = Modifier
                .size(avatarSize)
                .clip(CircleShape)
                .clickable { onToggleFollow(streamer) },
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                imageVector = if (followed) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (followed) "已收藏" else "收藏",
                tint = iconTint,
              )
            }
          }
            }
          }
        }

        Spacer(modifier = Modifier.weight(1f))

        Surface(
          modifier = Modifier
            .size(closeSize)
            .clip(CircleShape)
            .clickable(onClick = onBack),
          shape = CircleShape,
          color = Color.Transparent,
          border = BorderStroke(1.dp, glassBorder),
          tonalElevation = 0.dp,
          shadowElevation = 0.dp,
        ) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(glassBg),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "关闭",
              tint = closeFg,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun PlayerSideControlsOverlay(
  fullscreen: Boolean,
  showFullscreen: Boolean,
  onToggleFullscreen: () -> Unit,
  onOpenSettings: () -> Unit,
  onReload: () -> Unit,
  listenOnly: Boolean,
  onToggleListenOnly: () -> Unit,
  pipSupported: Boolean,
  onPip: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // 根据视频区可用高度自适应缩放整列按钮：竖屏视频区矮时整体缩小，保证 5 个按钮全部可见；
  // 横屏视频区高时保持原尺寸。相比「按横竖屏写死两套尺寸」更鲁棒，对小屏 / 异形比例同样适用。
  BoxWithConstraints(
    modifier = modifier,
    contentAlignment = Alignment.CenterEnd,
  ) {
    val gap = 14.dp
    val baseSize = 40.dp
    val baseIcon = 24.dp
    val count = 3 +
      (if (showFullscreen) 1 else 0) +
      (if (pipSupported) 1 else 0)
    val needed = baseSize * count + gap * (count - 1)
    val scale = if (maxHeight < needed) (maxHeight / needed).coerceIn(0.5f, 1f) else 1f
    val size = baseSize * scale
    val iconSize = baseIcon * scale
    val spacing = gap * scale
    Column(
      verticalArrangement = Arrangement.spacedBy(spacing),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      ControlFab(icon = Icons.Default.Settings, onClick = onOpenSettings, size = size, iconSize = iconSize)
      ControlFab(icon = Icons.Default.Refresh, onClick = onReload, size = size, iconSize = iconSize)
      if (showFullscreen) {
        ControlFab(icon = Icons.Default.FullscreenExit.takeIf { fullscreen } ?: Icons.Default.Fullscreen, onClick = onToggleFullscreen, size = size, iconSize = iconSize)
      }
      // 画中画：仅在倒数第二个位置（耳机按钮之上）显示，点击进入系统画中画。
      if (pipSupported) {
        ControlFab(icon = pipIcon(), onClick = onPip, size = size, iconSize = iconSize)
      }
      // 熄屏听播：未开启时显示耳机，开启后显示"关闭耳机"图标，再次点击即关闭
      val listenOnlyIcon = remember(listenOnly) { listenOnlyIcon(off = listenOnly) }
      ControlFab(
        icon = listenOnlyIcon,
        onClick = onToggleListenOnly,
        active = listenOnly,
        size = size,
        iconSize = iconSize,
      )
    }
  }
}

/**
 * 自绘「耳机 / 耳机（关闭）」图标。
 *
 * 不直接使用图标集里的 Headphones / HeadsetOff：这两个名字在当前依赖的
 * material-icons 版本里并不存在（会直接编译失败）。自绘可完全摆脱图标集版本差异。
 *
 * 绘制内容：一条头梁（描边）+ 左右耳罩（填充）；off = true 时再叠一道斜杠表示「关闭」。
 */
private fun listenOnlyIcon(off: Boolean): ImageVector = ImageVector.Builder(
  name = if (off) "ListenOnlyOff" else "ListenOnly",
  defaultWidth = 24.dp,
  defaultHeight = 24.dp,
  viewportWidth = 24f,
  viewportHeight = 24f,
).apply {
  val ink = SolidColor(Color.White)
  // 头梁：用折线近似半圆，配合圆角连接看起来就是一条圆润的拱形
  addPath(
    pathData = listOf(
      PathNode.MoveTo(3.2f, 13.6f),
      PathNode.LineTo(3.7f, 9.4f),
      PathNode.LineTo(6.6f, 5.9f),
      PathNode.LineTo(12f, 4f),
      PathNode.LineTo(17.4f, 5.9f),
      PathNode.LineTo(20.3f, 9.4f),
      PathNode.LineTo(20.8f, 13.6f),
    ),
    fill = null,
    stroke = ink,
    strokeLineWidth = 2f,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
  )
  // 左右耳罩
  addPath(
    pathData = listOf(
      PathNode.MoveTo(2f, 13f),
      PathNode.LineTo(6.6f, 13f),
      PathNode.LineTo(6.6f, 19f),
      PathNode.LineTo(2f, 19f),
      PathNode.Close,
      PathNode.MoveTo(17.4f, 13f),
      PathNode.LineTo(22f, 13f),
      PathNode.LineTo(22f, 19f),
      PathNode.LineTo(17.4f, 19f),
      PathNode.Close,
    ),
    fill = ink,
  )
  if (off) {
    // 斜杠：表示「关闭 / 停止听播」
    addPath(
      pathData = listOf(
        PathNode.MoveTo(3.5f, 20.5f),
        PathNode.LineTo(20.5f, 3.5f),
      ),
      fill = null,
      stroke = ink,
      strokeLineWidth = 2f,
      strokeLineCap = StrokeCap.Round,
    )
  }
}.build()

@Composable
private fun ControlFab(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  active: Boolean = false,
  size: Dp = 40.dp,
  iconSize: Dp = 24.dp,
) {
  val activeTint = MaterialTheme.colorScheme.primary
  Surface(
    modifier = modifier.size(size).clip(CircleShape).clickable(onClick = onClick),
    shape = CircleShape,
    color = if (active) activeTint.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.10f),
    border = BorderStroke(
      1.dp,
      if (active) activeTint.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.12f),
    ),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        modifier = Modifier.size(iconSize),
        imageVector = icon,
        contentDescription = null,
        tint = if (active) activeTint else Color.White.copy(alpha = 0.92f),
      )
    }
  }
}

/**
 * 自绘「画中画」图标：外框矩形 + 右上角小窗口矩形，语义清晰且不依赖图标集版本。
 */
private fun pipIcon(): ImageVector = ImageVector.Builder(
  name = "PictureInPicture",
  defaultWidth = 24.dp,
  defaultHeight = 24.dp,
  viewportWidth = 24f,
  viewportHeight = 24f,
).apply {
  val ink = SolidColor(Color.White)
  // 外框（屏幕）
  addPath(
    pathData = listOf(
      PathNode.MoveTo(3f, 5f),
      PathNode.LineTo(21f, 5f),
      PathNode.LineTo(21f, 19f),
      PathNode.LineTo(3f, 19f),
      PathNode.Close,
    ),
    fill = null,
    stroke = ink,
    strokeLineWidth = 2f,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
  )
  // 右上角小窗口（画中画窗口）
  addPath(
    pathData = listOf(
      PathNode.MoveTo(13f, 11f),
      PathNode.LineTo(19f, 11f),
      PathNode.LineTo(19f, 17f),
      PathNode.LineTo(13f, 17f),
      PathNode.Close,
    ),
    fill = ink,
  )
}.build()

@Composable
private fun PlayerSettingsDrawer(
  visible: Boolean,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val nightScheme = remember {
    darkColorScheme(
      primary = DtvColors.NightAccent,
      onPrimary = DtvColors.NightTextPrimary,
      secondary = DtvColors.NightBgTertiary,
      onSecondary = DtvColors.NightTextPrimary,
      background = DtvColors.NightBgPrimary,
      onBackground = DtvColors.NightTextPrimary,
      surface = DtvColors.NightBgSecondary,
      onSurface = DtvColors.NightTextPrimary,
      outline = DtvColors.NightBorder,
    )
  }

  Box(modifier = modifier.fillMaxSize()) {
    AnimatedVisibility(
      visible = visible,
      enter = fadeIn(animationSpec = tween(durationMillis = 140)),
      exit = fadeOut(animationSpec = tween(durationMillis = 140)),
      label = "settings_scrim",
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.Black.copy(alpha = 0.45f))
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onDismissRequest,
          ),
      )
    }

    AnimatedVisibility(
      visible = visible,
      enter = slideInHorizontally(animationSpec = tween(durationMillis = 220)) { -it } + fadeIn(animationSpec = tween(durationMillis = 140)),
      exit = slideOutHorizontally(animationSpec = tween(durationMillis = 220)) { -it } + fadeOut(animationSpec = tween(durationMillis = 140)),
      label = "settings_drawer",
    ) {
      // 横屏设置抽屉：v0.2.6 按用户要求取消毛玻璃——播放画面透出玻璃后
      // 选项文字对比度始终不足（v0.2.5 调深 tint 仍看不清），改回不透明
      // 深色面板，可读性第一。固定不透明度让所有设备/画面下表现一致。
      val drawerShape = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp)
      val drawerModifier = Modifier
        .fillMaxHeight()
        .fillMaxWidth(0.78f)
        .widthIn(max = 320.dp)
      Surface(
        modifier = drawerModifier,
        shape = drawerShape,
        color = Color(0xFF14161C),
        contentColor = DtvColors.NightTextPrimary,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
      ) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            // 极淡的纵向明暗渐变：给实心面板一点层次，底色仍接近不透明深色，
            // 任何画面下选项文字都保持高对比可读。
            .background(
              brush = Brush.verticalGradient(
                colors = listOf(
                  Color(0xFF191C25),
                  Color(0xFF12141B),
                ),
              ),
            ),
        ) {
          MaterialTheme(colorScheme = nightScheme) {
            content()
          }
        }
      }
    }
  }
}

@Composable
private fun DanmakuOverlay(
  messages: List<DanmakuMessage>,
  showUser: Boolean,
  areaFraction: Float,
  transparentBackground: Boolean,
  textScale: Float,
  opacity: Float,
  modifier: Modifier = Modifier,
) {
  BoxWithConstraints(
    modifier = modifier
      .fillMaxHeight(areaFraction)
      .clipToBounds(),
    contentAlignment = Alignment.BottomStart,
  ) {
    val maxBubbleWidth = maxWidth * 0.92f
    Column(verticalArrangement = Arrangement.Bottom) {
      messages.takeLast(10).forEach { msg ->
        DanmakuBubble(
          user = msg.user,
          content = msg.content,
          showUser = showUser,
          transparentBackground = transparentBackground,
          modifier = Modifier.widthIn(max = maxBubbleWidth),
          maxLines = 1,
          compact = true,
          textScale = textScale,
          opacity = opacity,
        )
        SpacerLine(4.dp)
      }
    }
  }
}

@Composable
private fun DanmakuBubble(
  user: String,
  content: String,
  showUser: Boolean = true,
  transparentBackground: Boolean = false,
  modifier: Modifier = Modifier,
  maxLines: Int = 1,
  compact: Boolean = false,
  textScale: Float = 1f,
  opacity: Float = 1f,
) {
  val displayUser = user.trim().ifBlank { "匿名" }
  val displayContent = content.trim()
  val effectiveOpacity = opacity.coerceIn(0.35f, 1.0f)

  val text = buildAnnotatedString {
    if (showUser) {
      withStyle(
        SpanStyle(
          color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f * effectiveOpacity),
          fontWeight = FontWeight.SemiBold,
        ),
      ) {
        append(displayUser)
      }
      append("  ")
    }
    append(displayContent)
  }

  val hPad = if (compact) 8.dp else 12.dp
  val vPad = if (compact) 4.dp else 8.dp
  val bubbleShape = if (compact) RoundedCornerShape(16.dp) else RoundedCornerShape(14.dp)

  Surface(
    modifier = modifier,
    shape = bubbleShape,
    color = if (transparentBackground) Color.Transparent else Color.Black.copy(alpha = 0.26f * effectiveOpacity),
    border = null,
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    val base = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
    val style =
      if (textScale == 1f) base
      else base.copy(
        fontSize = if (base.fontSize == TextUnit.Unspecified) base.fontSize else base.fontSize * textScale,
        lineHeight = if (base.lineHeight == TextUnit.Unspecified) base.lineHeight else base.lineHeight * textScale,
      )
    Text(
      text = text,
      style = style,
      color = Color.White.copy(alpha = 0.92f * effectiveOpacity),
      modifier = Modifier.padding(horizontal = hPad, vertical = vPad),
      maxLines = maxLines,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/** 弹幕消息的「身份 key」：equals 按引用（===），hashCode 委托消息自身——同一对象必同桶。 */
private class MsgRef(val msg: DanmakuMessage) {
  override fun equals(other: Any?): Boolean = other is MsgRef && other.msg === msg
  override fun hashCode(): Int = msg.hashCode()
}

@Composable
private fun HubDanmakuPanel(
  messages: List<DanmakuMessage>,
  // 新弹幕投放序号：不能用 keyedDisplay.size 当「来了新弹幕」的判据 ——
  // 缓冲区满（takeLast(danmakuMax)）后 size 恒为 200，size 不再变化会让
  // 竖屏弹幕面板永久停止滚动到最新一条（与横屏滚动弹幕同一个坑）。
  revision: Int = 0,
  enhancedPortrait: Boolean = false,
  textScale: Float = 1f,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  val itemSpacing = if (enhancedPortrait) 8.dp else 6.dp

  // ── 自动跟随状态机（对齐斗鱼 / 虎牙网页版的弹幕语义）──────────────────────
  //   刚进直播间                -> autoFollow = true，面板一直贴在最新一条
  //   手动往上翻看历史          -> 停止跟随（只看有没有真的翻走，跟手指有没有按住无关）
  //   自己滑回视觉最底部        -> 恢复跟随
  //
  // ⚠️ 这里绝不能拿 listState.firstVisibleItemIndex 当判据 —— v0.2.15 就是这么写的：
  // 新弹幕插在 index 0，LazyColumn 会按 key 把「原来的首项」重定位到新下标，
  // 于是 index 每来一批就从 0 变成 1、2、3……「== 0」永远为假，自动跟随彻底失效。
  // 现在一律比对「最新一条的 key 还在不在视野里」，既不依赖下标，也不依赖
  // reverseLayout 的方向语义。
  var autoFollow by remember { mutableStateOf(true) }
  // 用户是否正在手势拖拽 / 惯性滑动中。拖拽期间**无论 autoFollow 是什么**都不跟随 ——
  // 否则用户往上翻时每来一批新弹幕都会被 scrollToItem(0) 拽回底部，历史根本读不了。
  // 这里用 interactionSource 的 DragInteraction 而不是 listState.isScrollInProgress：
  // 后者会把程序自己的 scrollToItem 也算成「正在滚动」，容易在贴正的瞬间把自己判成
  // 用户在操作；DragInteraction 只有在用户真的 (a) 按下并拖过 touch slop 或
  // (b) 松手后的惯性滑动期间才存在，而且 (b) 结束后才 Stop，正好覆盖整个 user-driven 区间。
  var userDragging by remember { mutableStateOf(false) }
  // 上一次「确实贴住最新一条」时那条弹幕的 key。之后进来的 key 都比它大，用来算未读条数。
  var lastSeenKey by remember { mutableStateOf(-1L) }

  val display = remember(messages) { messages.asReversed() }
  // 为每条弹幕分配「按对象身份(===)稳定且唯一」的 key：
  // - 稳定：同一条弹幕在缓冲区滚动/裁剪时引用不变，key 不变 → 不会整列重组；
  // - 唯一：即便两条弹幕内容完全相同（如重复的"666"），因对象引用不同也拿到不同 key，
  //   避免 LazyLayout 因重复 key 直接崩溃（之前用 msg 本身当 key，data class 的 equals
  //   会让相同内容的弹幕撞 key，热门房几秒就触发崩溃）。
  // 实现：HashMap<MsgRef, Long>，MsgRef 等价于「引用相等 + 消息自身 hashCode」——
  // 同一对象必同桶，不同对象即便内容全同也是不同条目。旧实现每批做
  // keyMap.firstOrNull { === } × display.none { === } 是 O(n²)（满缓冲 200×200，
  // 热门房约 8 批/秒，全程主线程），换 HashMap 后整批 O(n)。
  val keyMap = remember { HashMap<MsgRef, Long>() }
  val counter = remember { longArrayOf(0L) }
  val keyedDisplay = remember(display) {
    val present = HashSet<MsgRef>()
    val out = ArrayList<Pair<Long, DanmakuMessage>>(display.size)
    for (msg in display) {
      val ref = MsgRef(msg)
      present.add(ref)
      val k = keyMap[ref] ?: run {
        val nk = counter[0] + 1L
        counter[0] = nk
        keyMap[ref] = nk
        nk
      }
      out.add(k to msg)
    }
    // 仅保留仍在列表中的对象，防止 key 随消息滚动无限增长
    val kit = keyMap.keys.iterator()
    while (kit.hasNext()) {
      if (kit.next() !in present) kit.remove()
    }
    out
  }
  // 以上四个用 Ref 读取：所有长期存活的协程里一律读最新值，
  // 既不因为闭包捕获到旧值出错，也不会因为把它们写进 LaunchedEffect 的 key 而反复重启协程。
  val latestKeyRef = rememberUpdatedState(keyedDisplay.firstOrNull()?.first)
  val autoFollowRef = rememberUpdatedState(autoFollow)
  val userDraggingRef = rememberUpdatedState(userDragging)
  val hasItemsRef = rememberUpdatedState(keyedDisplay.isNotEmpty())

  // 「最新一条还在不在视野里」——全部 ethical 判据的唯一来源。
  // 比对 key 而不是下标：同一条弹幕生命周期内 key 不变，而 index 会被 LazyLayout 的
  // key 重定位改写（见上）。列表为空时当作「在最底部」，让首次进入也能正常贴住。
  fun newestVisible(): Boolean {
    val k = latestKeyRef.value ?: return true
    val info = listState.layoutInfo
    return info.visibleItemsInfo.isEmpty() || info.visibleItemsInfo.any { it.key == k }
  }

  suspend fun stickToNewest() {
    if (hasItemsRef.value) listState.scrollToItem(index = 0)
    latestKeyRef.value?.let { lastSeenKey = it }
  }

  // 用户手势生命周期：只有「用户驱动的滚动」才有资格改变跟随意图。
  LaunchedEffect(listState) {
    listState.interactionSource.interactions.collect { interaction ->
      when (interaction) {
        is DragInteraction.Start -> userDragging = true
        is DragInteraction.Stop, is DragInteraction.Cancel -> {
          userDragging = false
          // 惯性滑动完全停下后按落点决定接下来跟不跟随：
          //   最新一条还在视野里 -> 用户已经滑回底部了，恢复跟随并立刻贴正
          //   翻走了            -> 停下来让他安静看历史，直到他自己滑回来
          if (newestVisible()) {
            autoFollow = true
            stickToNewest()
          } else {
            autoFollow = false
          }
        }
      }
    }
  }

  // 拖拽进行中，一旦最新一条滑出视野就就地关掉跟随，不必等松手。
  // 这里刻意只「关」不「开」：开的那一路交给上面的 Stop 分支 —— 只有「布局已稳定、
  // 人已停手」时的落点才是可信的，否则刚夸下 touch slop 就会被判定成「已翻走」。
  LaunchedEffect(listState) {
    snapshotFlow { if (userDraggingRef.value) newestVisible() else true }
      .distinctUntilChanged()
      .collect { visible -> if (!visible) autoFollow = false }
  }

  // 自动跟随：每来一批新弹幕就贴回最新一条
  //（reverseLayout = true 时 index 0 就是视觉最底部，也就是最新那一条）。
  LaunchedEffect(revision) {
    if (autoFollowRef.value && !userDraggingRef.value) stickToNewest()
  }

  // 翻历史期间攒下的新弹幕条数。key 是全局单调递增的序列号，
  // 所以「比上次看到的 key 大」的条数就是这段时间新进来的。
  val unseen = remember(keyedDisplay, lastSeenKey) {
    keyedDisplay.count { it.first > lastSeenKey }
  }

  // 跟随意图由 false 翻回 true 时补一次贴正（例如点下面的「回到最新」、
  // 或者列表在下没有 DragInteraction 的路径上恢复了跟随）。
  LaunchedEffect(autoFollow) {
    if (autoFollow && !userDraggingRef.value) stickToNewest()
  }

  Box(modifier = modifier.fillMaxWidth().fillMaxSize()) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 10.dp, vertical = 6.dp),
      state = listState,
      reverseLayout = true,
      contentPadding = PaddingValues(0.dp),
      verticalArrangement = Arrangement.spacedBy(itemSpacing),
    ) {
      items(
        keyedDisplay,
        key = { it.first },
        // 所有弹幕行同构：声明 contentType 让 LazyColumn 复用组合树/测量结果，
        // 高频房间弹幕每秒十几条上新时的重组开销更低。
        contentType = { "danmaku_row" },
      ) { (_, msg) ->
        HubDanmakuRow(
          user = msg.user.trim().ifBlank { "匿名" },
          content = msg.content.trim(),
          enhancedPortrait = enhancedPortrait,
          textScale = textScale,
        )
      }
    }

    // 翻看历史时的「回到最新」。没有这个入口的话，用户一旦滑上去就完全不知道
    // 弹幕还在源源不断地来（面板会静止得像弹幕挂了），也找不到回来的路。
    if (unseen > 0 && !autoFollow) {
      Box(
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(end = 12.dp, bottom = 10.dp)
          .clip(RoundedCornerShape(999.dp))
          .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.92f))
          .clickable { autoFollow = true }
          .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = "↓ $unseen 条新弹幕",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onPrimary,
        )
      }
    }
  }
}

@Composable
private fun HubDanmakuRow(
  user: String,
  content: String,
  enhancedPortrait: Boolean = false,
  textScale: Float = 1f,
  modifier: Modifier = Modifier,
) {
  val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
  val userChipBg = if (isDark) Color.White.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface
  val userChipBorder = if (isDark) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
  val userFg = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.72f else 0.90f)
  val contentFg = if (isDark) Color.White.copy(alpha = 0.84f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)

  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(if (enhancedPortrait) 8.dp else 6.dp),
  ) {
    Surface(
      shape = RoundedCornerShape(8.dp),
      color = userChipBg,
      border = userChipBorder,
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
    ) {
      Row(
        modifier = Modifier.padding(horizontal = if (enhancedPortrait) 8.dp else 6.dp, vertical = if (enhancedPortrait) 4.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          text = user,
          style = scaleTextStyle(
            if (enhancedPortrait) MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
            else MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            textScale,
          ),
          color = userFg,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }

    Text(
      text = content,
      style = scaleTextStyle(
        if (enhancedPortrait) MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
        else MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
        textScale,
      ),
      color = contentFg,
      modifier = Modifier
        .weight(1f)
        .padding(top = 0.dp),
      maxLines = if (enhancedPortrait) 4 else 3,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun ScrollingDanmakuOverlay(
  resetKey: Any?,
  messages: List<DanmakuMessage>,
  // revision 每合并一批自增；newCount 是本批新增条数。
  // 两者配合取代原先的「按 messages.size 差值取新增」——缓冲区满后 size 恒为
  // danmakuMax，差值恒为 0，滚动弹幕会彻底停止上新。
  revision: Int,
  newCount: Int,
  showUser: Boolean,
  areaFraction: Float,
  textScale: Float = 1f,
  opacity: Float = 1f,
  modifier: Modifier = Modifier,
) {
  data class Active(
    val id: Long,
    val user: String,
    val content: String,
    val track: Int,
  )

  var lastRevision by remember(resetKey) { mutableIntStateOf(-1) }
  val maxActive = 48

  BoxWithConstraints(modifier = modifier) {
    val density = LocalDensity.current
    val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
    val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
    val regionTopPx = heightPx * 0.04f
    val regionHeightPx = (heightPx * areaFraction).coerceAtLeast(1f)
    val usableHeightPx = (regionHeightPx - regionTopPx).coerceAtLeast(1f)

    // Avoid overlap when text size changes: compute track count based on estimated bubble height.
    val base = MaterialTheme.typography.bodySmall
    val fontSizePx = with(density) {
      (if (base.fontSize == TextUnit.Unspecified) 12.sp else base.fontSize).toPx()
    }
    val lineHeightPx = with(density) {
      if (base.lineHeight == TextUnit.Unspecified) fontSizePx * 1.2f else base.lineHeight.toPx()
    }
    val vPadPx = with(density) { 4.dp.toPx() }
    val hPadPx = with(density) { 8.dp.toPx() }
    val trackGapPx = with(density) { 2.dp.toPx() }
    val minTrackHeightPx = ((lineHeightPx * textScale.coerceAtLeast(0.85f)) + vPadPx * 2f)
      .coerceAtLeast(1f)
    val trackStepPx = (minTrackHeightPx + trackGapPx).coerceAtLeast(1f)
    val trackCount = (usableHeightPx / trackStepPx).toInt().coerceIn(1, 24)
    val active = remember(resetKey, trackCount) { mutableStateListOf<Active>() }
    val laneAvailableAt = remember(resetKey, trackCount) { MutableList(trackCount) { 0L } }
    // 弹幕 key 的自增序号：随轨道数一起重置即可，保证同一批次内不会撞 key。
    val activeIdSeq = remember(resetKey, trackCount) { longArrayOf(0L) }

    fun estimatedTextWidthPx(user: String, content: String): Float {
      val text = if (showUser) "$user  $content" else content
      val weightedChars = text.sumOf { ch ->
        when {
          ch.code <= 0x007F -> 1
          else -> 2
        }.toInt()
      }
      return weightedChars * fontSizePx * textScale.coerceAtLeast(0.85f) * 0.56f + hPadPx * 2f
    }

    fun chooseTrack(now: Long): Int? {
      var bestReadyTrack = -1
      var bestReadyAt = Long.MAX_VALUE
      laneAvailableAt.forEachIndexed { index, readyAt ->
        if (readyAt <= now) return index
        if (readyAt < bestReadyAt) {
          bestReadyAt = readyAt
          bestReadyTrack = index
        }
      }
      return bestReadyTrack.takeIf { bestReadyAt <= now + 120L }
    }

    LaunchedEffect(revision, trackCount, widthPx, textScale) {
      // 只有 revision 推进（真的来了新弹幕）才投放；
      // 宽度/字号变化但 revision 未变时不重复投放同一批，避免旋转屏幕后弹幕翻倍。
      if (revision <= lastRevision) return@LaunchedEffect
      lastRevision = revision
      if (newCount <= 0) return@LaunchedEffect
      // 新弹幕一定位于列表尾部（合并用的是 takeLast）
      val newItems = messages.takeLast(newCount.coerceAtMost(messages.size))
      newItems.forEach { msg ->
        val user = msg.user.trim().ifBlank { "匿名" }
        val content = msg.content.trim()
        if (content.isNotEmpty()) {
          val now = System.nanoTime() / 1_000_000L
          val track = chooseTrack(now)
          if (track != null) {
            if (active.size >= maxActive) active.removeAt(0)
            // 不能用 System.nanoTime() 当 key：同一帧里连续投放多条弹幕时，
            // 部分设备的时钟分辨率不足会返回相同值，重复 key 会让 Compose
            // 直接抛 "Key was already used" 崩溃。改用单调递增计数器保证唯一。
            val nextId = activeIdSeq[0] + 1L
            activeIdSeq[0] = nextId
            active.add(
              Active(
                id = nextId,
                user = user,
                content = content,
                track = track,
              ),
            )
            val textWidthPx = estimatedTextWidthPx(user, content).coerceAtLeast(1f)
            val travelWidthPx = widthPx + textWidthPx
            val minDelayMs = ceil((textWidthPx / travelWidthPx) * 9000f).toLong() + 80L
            laneAvailableAt[track] = now + minDelayMs
          }
        }
      }
    }

    active.forEach { item ->
      key(item.id) {
        ScrollingDanmakuItem(
          user = item.user,
          content = item.content,
          showUser = showUser,
          transparentBackground = true,
          textScale = textScale,
          opacity = opacity,
          startX = widthPx,
          endX = -widthPx,
          y = regionTopPx + trackStepPx * (item.track % trackCount),
          onFinished = { active.remove(item) },
        )
      }
    }
  }
}

@Composable
private fun ScrollingDanmakuItem(
  user: String,
  content: String,
  showUser: Boolean,
  transparentBackground: Boolean,
  textScale: Float,
  opacity: Float,
  startX: Float,
  endX: Float,
  y: Float,
  onFinished: () -> Unit,
) {
  val x = remember { Animatable(startX) }
  LaunchedEffect(user, content, startX, endX) {
    x.snapTo(startX)
    x.animateTo(
      targetValue = endX,
      animationSpec = tween(durationMillis = 9000, easing = LinearEasing),
    )
    onFinished()
  }

  // 用 graphicsLayer 而不是 Modifier.offset：offset 每帧触发布局/测量重排，
  // graphicsLayer 只更新图层平移属性（渲染管线最廉价的一档），
  // 同屏几十条滚动弹幕时的 CPU 占用明显更低。
  Box(
    modifier = Modifier.graphicsLayer {
      translationX = x.value
      translationY = y
    },
  ) {
    DanmakuBubble(
      user = user,
      content = content,
      showUser = showUser,
      transparentBackground = transparentBackground,
      maxLines = 1,
      compact = true,
      textScale = textScale,
      opacity = opacity,
    )
  }
}

private fun scaleTextStyle(
  base: TextStyle,
  scale: Float,
): TextStyle {
  val s = scale.coerceIn(0.85f, 1.3f)
  if (s == 1f) return base
  return base.copy(
    fontSize = if (base.fontSize == TextUnit.Unspecified) base.fontSize else base.fontSize * s,
    lineHeight = if (base.lineHeight == TextUnit.Unspecified) base.lineHeight else base.lineHeight * s,
  )
}

@Composable
private fun SpacerLine(height: androidx.compose.ui.unit.Dp = 10.dp) {
  androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(height))
}

@Composable
private fun RowWrap(
  items: List<Pair<String, String?>>,
  selected: String?,
  onSelect: (String?) -> Unit,
) {
  LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    // 用下标当 key 而不是标签文本：画质/线路可能出现同名档位（如 usher 返回两个
    // 720P 变体），重复 key 会让 LazyLayout 直接抛 "Key was already used" 崩溃。
    // 这类 chip 列表条目固定、不会重排，用 index 做 key 既唯一又稳定。
    items(items.size, key = { it }) { index ->
      val (label, value) = items[index]
      val isSelected = value == selected || (value == null && selected == null)
      FilterChip(
        selected = isSelected,
        onClick = { onSelect(value) },
        label = { Text(label) },
      )
    }
  }
}

@Composable
private fun RowWrapInt(
  items: List<Pair<String, Int?>>,
  selected: Int?,
  onSelect: (Int?) -> Unit,
) {
  LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    // 用下标当 key 而不是标签文本：画质/线路可能出现同名档位（如 usher 返回两个
    // 720P 变体），重复 key 会让 LazyLayout 直接抛 "Key was already used" 崩溃。
    // 这类 chip 列表条目固定、不会重排，用 index 做 key 既唯一又稳定。
    items(items.size, key = { it }) { index ->
      val (label, value) = items[index]
      val isSelected = value == selected || (value == null && selected == null)
      FilterChip(
        selected = isSelected,
        onClick = { onSelect(value) },
        label = { Text(label) },
      )
    }
  }
}

@Composable
private fun RowWrapFloat(
  items: List<Pair<String, Float>>,
  selected: Float,
  onSelect: (Float) -> Unit,
) {
  LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    items(items.size, key = { it }) { index ->
      val (label, value) = items[index]
      val isSelected = (value - selected).let { if (it < 0f) -it else it } < 0.0001f
      FilterChip(
        selected = isSelected,
        onClick = { onSelect(value) },
        label = { Text(label) },
      )
    }
  }
}

/** 抖音清晰度，从低到高 */
private val DOUYIN_QUALITY_ASC = listOf("SD1", "HD1", "FULL_HD1", "ORIGIN")

/** B站清晰度 qn，从低到高 */
private val BILIBILI_QN_ASC = listOf(80, 150, 250, 400, 10000)

/**
 * 把全局画质档位映射为斗鱼解析器能识别的「语义档位名」。
 * 注意：斗鱼的 原画 对应 rate=0（数值最小却是最高画质），因此不能按 rate 数值大小排序取档位，
 * 必须用语义名交给解析器按名称匹配，才能确保「最高」= 原画。
 */
private fun pickDouyuQualityName(quality: VideoQuality): String = when (quality) {
  VideoQuality.Highest -> "原画"
  VideoQuality.High -> "蓝光"
  VideoQuality.Medium -> "高清"
  VideoQuality.Low -> "标清"
}

/**
 * 根据语义画质名，从斗鱼清晰度列表里反查对应 rate（字符串），用于设置面板高亮当前档位。
 * 解析器内部挑选 rate 的规则优先于这里的兜底，二者尽量保持一致。
 */
private fun matchDouyuRate(variants: List<DouyuPlayVariant>, name: String): String? {
  if (variants.isEmpty()) return null
  return when (name) {
    "原画" -> variants.firstOrNull { it.rate == 0 }?.rate?.toString()
      ?: variants.firstOrNull { it.name.contains("原画") }?.rate?.toString()
      ?: variants.minOfOrNull { it.rate }?.toString()
    "蓝光" -> variants.firstOrNull { it.name.contains("蓝光") }?.rate?.toString()
      ?: variants.maxByOrNull { it.bit ?: 0 }?.rate?.toString()
    "高清" -> variants.firstOrNull { it.name.contains("高清") }?.rate?.toString()
      ?: variants.firstOrNull { it.name.contains("超清") }?.rate?.toString()
      ?: variants.filter { it.rate != 0 }.maxByOrNull { it.bit ?: 0 }?.rate?.toString()
    else -> variants.firstOrNull { it.name.contains("标清") || it.name.contains("流畅") || it.name.contains("普清") }?.rate?.toString()
      ?: variants.filter { it.rate != 0 }.minByOrNull { it.bit ?: Int.MAX_VALUE }?.rate?.toString()
      ?: variants.minOfOrNull { it.rate }?.toString()
  }
}

private fun pickDouyinQuality(quality: VideoQuality): String = when (quality) {
  VideoQuality.Low -> "SD1"
  VideoQuality.Medium -> "HD1"
  VideoQuality.High -> "FULL_HD1"
  VideoQuality.Highest -> "ORIGIN"
}

private fun pickBilibiliQn(quality: VideoQuality): Int = when (quality) {
  VideoQuality.Low -> 80
  VideoQuality.Medium -> 250
  VideoQuality.High -> 400
  VideoQuality.Highest -> 10000
}

/**
 * 抖音：从首选档位开始，失败则逐级降低清晰度，返回 (播放地址, 实际生效档位)。
 */
private suspend fun resolveDouyinWithFallback(
  repo: DtvRepository,
  webRid: String,
  preferred: String,
): Pair<String, String> {
  // DOUYIN_QUALITY_ASC 是「低 -> 高」升序，降档必须往下标小的方向走。
  // 原来是 `until size`（往上升）：默认首选 ORIGIN 取到末位，循环只跑一次就结束，
  // 等于完全没有降档 —— 原画需要权限而未授权时直接抛错、根本进不去房间。
  // 首选档位不在列表里时，从最高档开始往下试（而不是落到最低档）。
  val start = DOUYIN_QUALITY_ASC.indexOf(preferred)
    .let { if (it >= 0) it else DOUYIN_QUALITY_ASC.lastIndex }
  var lastErr: Throwable? = null
  for (i in start downTo 0) {
    val q = DOUYIN_QUALITY_ASC[i]
    runCatching { repo.resolveDouyinStreamUrl(webRid = webRid, desiredQuality = q) }
      .onSuccess { return it to q }
      .onFailure { lastErr = it }
  }
  throw lastErr ?: IllegalStateException("获取抖音播放地址失败")
}

/**
 * B站：从首选 qn 开始，失败则逐级降低清晰度，返回 (播放地址, 实际生效 qn)。
 * 原画需要登录/大会员，未登录时会自动降到蓝光等可用档位。
 */
private suspend fun resolveBilibiliWithFallback(
  repo: DtvRepository,
  roomId: String,
  preferred: Int,
): Pair<String, Int> {
  // 同抖音：BILIBILI_QN_ASC 是「低 -> 高」升序（80,150,250,400,10000），
  // 降到低档要往下标小的方向走。原写法在默认的 10000（原画）上只试一档，
  // 未登录/非大会员时原画不可用就直接失败，「自动降到蓝光」这句注释从未生效。
  val start = BILIBILI_QN_ASC.indexOf(preferred)
    .let { if (it >= 0) it else BILIBILI_QN_ASC.lastIndex }
  var lastErr: Throwable? = null
  for (i in start downTo 0) {
    val qn = BILIBILI_QN_ASC[i]
    runCatching { repo.resolveBilibiliStreamUrl(roomId = roomId, qn = qn) }
      .onSuccess { return it to qn }
      .onFailure { lastErr = it }
  }
  throw lastErr ?: IllegalStateException("获取B站播放地址失败")
}

