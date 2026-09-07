package dtv.mobile.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dtv.mobile.model.Platform
import dtv.mobile.model.Streamer
import dtv.mobile.repo.DtvRepository
import dtv.mobile.repo.fake.FakeDtvRepository
import kotlinx.serialization.Serializable

@Serializable
data class SubscribedPartition(
  val id: String,
  val name: String,
  val platform: Platform? = null,
)

/** 会占用底部切换栏入口的平台（「自定义」不占底部栏），默认顺序即枚举声明顺序。 */
private val DOCK_PLATFORMS: List<Platform> = Platform.entries.filter { it != Platform.Custom }

/**
 * 平台板块页顶栏右侧「板块下拉菜单」的状态快照：
 * @param options    一级分区名称列表（如：网游竞技 / 单机热游 / 手游休闲…）
 * @param selectedIndex 当前选中项下标，-1 表示未选中
 * @param onSelect   选中某个一级分区（下标）后的回调，由板块页内部切换 cate1/cate2
 */
data class CategoryMenuState(
  val options: List<String>,
  val selectedIndex: Int,
  val onSelect: (Int) -> Unit,
)

class AppState(
  val repo: DtvRepository,
  private val subscriptionStore: SubscriptionStore,
) {
  var themeMode: ThemeMode by mutableStateOf(ThemeMode.System)
  var landscapeDanmakuFontScale: Float by mutableStateOf(1.2f)
  var danmakuFontScale: Float by mutableStateOf(1.0f)
  var danmakuOpacity: Float by mutableStateOf(1.0f)
  var danmakuAreaFraction: Float by mutableStateOf(0.5f)
  var rememberCategoryEnabled: Boolean by mutableStateOf(true)
  var compactCardEnabled: Boolean by mutableStateOf(true)
  var videoQuality: VideoQuality by mutableStateOf(VideoQuality.Highest)
  var landscapeEnabled: Boolean by mutableStateOf(false)
  var exitCleanupEnabled: Boolean by mutableStateOf(true)
  var highRefreshEnabled: Boolean by mutableStateOf(true)
  var accentColorHex: String by mutableStateOf("")
  var platformSwitchLoading: Boolean by mutableStateOf(false)
  var selectedPlatform: Platform by mutableStateOf(Platform.Douyu)

  /** 底部切换栏的完整排列顺序（含被关闭的平台，用于重新启用时还原位置）。 */
  var platformOrder: List<Platform> by mutableStateOf(DOCK_PLATFORMS)
    private set

  /** 被用户关闭的平台集合，默认全部开启。 */
  var platformDisabled: Set<Platform> by mutableStateOf(emptySet())
    private set

  /** 底部切换栏实际展示的平台：按用户自定义顺序，仅含已启用的平台。 */
  val visiblePlatforms: List<Platform>
    get() = platformOrder.filter { it !in platformDisabled }
  var currentScreen: Screen by mutableStateOf(Screen.Home)
  var currentStreamer: Streamer? by mutableStateOf(null)
  private var playerReturnScreen: Screen? by mutableStateOf(null)
  private var settingsReturnScreen: Screen? by mutableStateOf(null)
  var playerFullscreen: Boolean by mutableStateOf(false)
  var currentPartition: SubscribedPartition? by mutableStateOf(null)

  /** 顶栏右侧「板块下拉菜单」状态，由当前平台页在组合期间写入，HubTopBar 读取渲染。 */
  var categoryMenu: CategoryMenuState? by mutableStateOf(null)

  val followedStreamers = mutableStateListOf<Streamer>()
  val subscribedPartitions = mutableStateListOf<SubscribedPartition>()
  val danmuBlockKeywords = mutableStateListOf<String>()
  private val rememberedCategoryByPlatform = mutableStateMapOf<Platform, String>()

  /**
   * 分类胶囊条的横向滚动位置（按「平台 + 分区 id」记忆）。
   *
   * 进直播间时平台页会被整页销毁（根布局按 screen 做 AnimatedContent 切换），
   * 组合内的 LazyListState 随之丢失，返回后胶囊条会滚回最前。
   * 这里把位置提到 AppState 保存，返回后按同一分区还原。
   */
  private val pillScrollPositions = HashMap<String, Pair<Int, Int>>()

  fun pillScrollPosition(key: String): Pair<Int, Int>? = pillScrollPositions[key]

  fun savePillScrollPosition(key: String, index: Int, offset: Int) {
    pillScrollPositions[key] = index to offset
  }

  init {
    themeMode = subscriptionStore.loadThemeMode()
    followedStreamers.addAll(subscriptionStore.loadFollowedStreamers())
    subscribedPartitions.addAll(subscriptionStore.loadSubscribedPartitions())
    danmuBlockKeywords.addAll(subscriptionStore.loadDanmuBlockKeywords())
    landscapeDanmakuFontScale = subscriptionStore.loadLandscapeDanmakuFontScale()
    danmakuFontScale = subscriptionStore.loadDanmakuFontScale()
    danmakuOpacity = subscriptionStore.loadDanmakuOpacity()
    danmakuAreaFraction = subscriptionStore.loadDanmakuAreaFraction()
    rememberCategoryEnabled = subscriptionStore.loadRememberCategoryEnabled()
    compactCardEnabled = subscriptionStore.loadCompactCardEnabled()
    videoQuality = VideoQuality.fromNameOrHighest(subscriptionStore.loadVideoQuality())
    landscapeEnabled = subscriptionStore.loadLandscapeEnabled()
    exitCleanupEnabled = subscriptionStore.loadExitCleanupEnabled()
    highRefreshEnabled = subscriptionStore.loadHighRefreshEnabled()
    accentColorHex = subscriptionStore.loadAccentColorHex()
    platformOrder = loadPlatformOrder()
    platformDisabled = loadPlatformDisabled()
    // 历史数据里选中的平台可能已被关闭，启动时校正一次
    if (selectedPlatform in platformDisabled) switchToFirstVisiblePlatform()

    subscriptionStore.loadRememberedCategoryByPlatform().forEach { entry ->
      rememberedCategoryByPlatform[entry.platform] = entry.partitionId
    }
  }

  val dockSelectedScreen: Screen
    get() = currentScreen

  private fun streamerKey(streamer: Streamer): String = "${streamer.platform.name}:${streamer.roomId}"

  fun isFollowed(streamer: Streamer): Boolean {
    val key = streamerKey(streamer)
    return followedStreamers.any { streamerKey(it) == key }
  }

  fun toggleFollow(streamer: Streamer) {
    val key = streamerKey(streamer)
    val index = followedStreamers.indexOfFirst { streamerKey(it) == key }
    if (index >= 0) {
      followedStreamers.removeAt(index)
    } else {
      followedStreamers.add(streamer)
    }
    subscriptionStore.saveFollowedStreamers(followedStreamers.toList())
  }

  fun updateLandscapeDanmakuFontScale(value: Float) {
    landscapeDanmakuFontScale = value.coerceIn(0.85f, 2.0f)
    subscriptionStore.saveLandscapeDanmakuFontScale(landscapeDanmakuFontScale)
  }

  fun updateDanmakuFontScale(value: Float) {
    danmakuFontScale = value.coerceIn(0.85f, 1.3f)
    subscriptionStore.saveDanmakuFontScale(danmakuFontScale)
  }

  fun updateDanmakuOpacity(value: Float) {
    danmakuOpacity = value.coerceIn(0.35f, 1.0f)
    subscriptionStore.saveDanmakuOpacity(danmakuOpacity)
  }

  fun updateDanmakuAreaFraction(value: Float) {
    danmakuAreaFraction = value.coerceIn(0.25f, 1.0f)
    subscriptionStore.saveDanmakuAreaFraction(danmakuAreaFraction)
  }

  fun toggleTheme() {
    themeMode = when (themeMode) {
      ThemeMode.System -> ThemeMode.Dark
      ThemeMode.Dark -> ThemeMode.Light
      ThemeMode.Light -> ThemeMode.System
    }
    subscriptionStore.saveThemeMode(themeMode)
  }

  fun toggleDayNight() {
    themeMode = when (themeMode) {
      ThemeMode.Dark -> ThemeMode.Light
      ThemeMode.Light, ThemeMode.System -> ThemeMode.Dark
    }
    subscriptionStore.saveThemeMode(themeMode)
  }

  fun updateThemeMode(mode: ThemeMode) {
    themeMode = mode
    subscriptionStore.saveThemeMode(mode)
  }

  fun updateRememberCategoryEnabled(enabled: Boolean) {
    rememberCategoryEnabled = enabled
    subscriptionStore.saveRememberCategoryEnabled(enabled)
  }

  fun rememberedCategoryId(platform: Platform): String? = rememberedCategoryByPlatform[platform]

  fun saveRememberedCategory(platform: Platform, id: String) {
    if (!rememberCategoryEnabled) return
    if (id.isBlank()) return
    rememberedCategoryByPlatform[platform] = id
    val entries = rememberedCategoryByPlatform.entries.map { (p, pid) -> RememberedCategoryEntry(platform = p, partitionId = pid) }
    subscriptionStore.saveRememberedCategoryByPlatform(entries)
  }

  fun setAccentColor(hex: String) {
    accentColorHex = hex.trim()
    subscriptionStore.saveAccentColorHex(accentColorHex)
  }

  fun updateCompactCardEnabled(enabled: Boolean) {
    compactCardEnabled = enabled
    subscriptionStore.saveCompactCardEnabled(enabled)
  }

  fun updateVideoQuality(quality: VideoQuality) {
    videoQuality = quality
    subscriptionStore.saveVideoQuality(quality.name)
  }

  fun updateLandscapeEnabled(enabled: Boolean) {
    landscapeEnabled = enabled
    subscriptionStore.saveLandscapeEnabled(enabled)
  }

  fun updateExitCleanupEnabled(enabled: Boolean) {
    exitCleanupEnabled = enabled
    subscriptionStore.saveExitCleanupEnabled(enabled)
  }

  fun updateHighRefreshEnabled(enabled: Boolean) {
    highRefreshEnabled = enabled
    subscriptionStore.saveHighRefreshEnabled(enabled)
  }

  private fun loadPlatformOrder(): List<Platform> {
    val saved = subscriptionStore.loadPlatformOrder()
      .mapNotNull { name -> runCatching { Platform.valueOf(name) }.getOrNull() }
      .filter { it != Platform.Custom }
      .distinct()
    val merged = saved.toMutableList()
    // 兼容后续新增的平台：历史数据里没有的一律追加到末尾
    DOCK_PLATFORMS.forEach { platform -> if (platform !in merged) merged.add(platform) }
    return merged
  }

  private fun loadPlatformDisabled(): Set<Platform> =
    subscriptionStore.loadPlatformDisabled()
      .mapNotNull { name -> runCatching { Platform.valueOf(name) }.getOrNull() }
      .toSet()

  /** 开关某个平台在底部切换栏的显示。关闭后该平台会立即从切换栏移除。 */
  fun updatePlatformEnabled(platform: Platform, enabled: Boolean) {
    if (platform == Platform.Custom) return
    val next = platformDisabled.toMutableSet()
    if (enabled) next.remove(platform) else next.add(platform)
    platformDisabled = next
    subscriptionStore.savePlatformDisabled(next.map { it.name })
    // 当前正停留在该平台时，切到第一个仍可见的平台，避免停留在空页面
    if (selectedPlatform in platformDisabled) switchToFirstVisiblePlatform()
  }

  /**
   * 拖拽排序（索引基于完整平台列表，含被关闭的平台）。
   * 排序列表展示全部平台：关闭中的平台置灰但仍可拖动位置，
   * 重新开启时按此顺序加回底栏——否则被关闭的平台在排序页「消失」。
   */
  fun movePlatform(fromIndex: Int, toIndex: Int) {
    if (fromIndex == toIndex) return
    if (fromIndex !in platformOrder.indices) return
    if (toIndex !in platformOrder.indices) return

    val next = platformOrder.toMutableList()
    val item = next.removeAt(fromIndex)
    next.add(toIndex, item)
    platformOrder = next
    subscriptionStore.savePlatformOrder(next.map { it.name })
  }

  private fun switchToFirstVisiblePlatform() {
    val first = visiblePlatforms.firstOrNull()
    if (first == null) {
      // 所有平台都被关闭：退回首页，避免停留在无内容的平台页
      currentPartition = null
      currentScreen = Screen.Home
      return
    }
    selectedPlatform = first
    currentPartition = null
    if (currentScreen == Screen.Platform) {
      currentScreen = Screen.Home
    }
  }


  suspend fun refreshFollowedLiveStatus() {
    val snapshot = followedStreamers.toList()
    snapshot.forEach { streamer ->
      val live = repo.fetchLiveStatus(streamer) ?: return@forEach
      val key = streamerKey(streamer)
      val index = followedStreamers.indexOfFirst { streamerKey(it) == key }
      if (index >= 0) {
        val current = followedStreamers[index]
        if (current.isLive != live) {
          followedStreamers[index] = current.copy(isLive = live)
        }
      }
    }
  }

  suspend fun refreshFollowedStreamerCards() {
    val snapshot = followedStreamers.toList()
    val updated = snapshot.map { s ->
      repo.fetchFollowedStreamerSnapshot(s)?.let { it.copy(platform = s.platform, roomId = s.roomId) } ?: s
    }
    followedStreamers.clear()
    followedStreamers.addAll(updated)
    subscriptionStore.saveFollowedStreamers(followedStreamers.toList())
  }

  fun moveFollowedStreamer(fromIndex: Int, toIndex: Int) {
    if (fromIndex == toIndex) return
    if (fromIndex !in 0 until followedStreamers.size) return
    if (toIndex !in 0 until followedStreamers.size) return

    val item = followedStreamers.removeAt(fromIndex)
    followedStreamers.add(index = toIndex, element = item)
    subscriptionStore.saveFollowedStreamers(followedStreamers.toList())
  }

  private fun partitionKey(p: SubscribedPartition): String = "${p.platform?.name ?: "any"}:${p.id}"

  private fun normalizeDanmuBlockKeywords(keywords: List<String>): List<String> {
    val seen = HashSet<String>()
    val out = ArrayList<String>()
    for (raw in keywords) {
      val trimmed = raw.trim()
      if (trimmed.isEmpty()) continue
      val normalized = trimmed.take(40)
      val key = normalized.lowercase()
      if (!seen.add(key)) continue
      out.add(normalized)
      if (out.size >= 40) break
    }
    return out
  }

  fun setDanmuBlockKeywords(keywords: List<String>) {
    val next = normalizeDanmuBlockKeywords(keywords)
    danmuBlockKeywords.clear()
    danmuBlockKeywords.addAll(next)
    subscriptionStore.saveDanmuBlockKeywords(next)
  }

  fun mergeDanmuBlockKeywords(keywords: List<String>): Int {
    val existing = danmuBlockKeywords.mapTo(HashSet()) { it.lowercase() }
    val merged = danmuBlockKeywords.toMutableList()
    var added = 0
    for (raw in keywords) {
      val trimmed = raw.trim()
      if (trimmed.isEmpty()) continue
      val normalized = trimmed.take(40)
      val key = normalized.lowercase()
      if (!existing.add(key)) continue
      merged.add(normalized)
      added += 1
      if (merged.size >= 40) break
    }
    if (added > 0) setDanmuBlockKeywords(merged)
    return added
  }

  fun mergeFollowedStreamers(incoming: List<Streamer>): Int {
    if (incoming.isEmpty()) return 0
    val existing = followedStreamers.asSequence().map { streamerKey(it) }.toHashSet()
    val added = incoming.filter { existing.add(streamerKey(it)) }
    if (added.isEmpty()) return 0
    followedStreamers.addAll(added)
    subscriptionStore.saveFollowedStreamers(followedStreamers.toList())
    return added.size
  }

  fun mergeSubscribedPartitions(incoming: List<SubscribedPartition>): Int {
    if (incoming.isEmpty()) return 0
    val existing = subscribedPartitions.asSequence().map { partitionKey(it) }.toHashSet()
    val added = incoming.filter { existing.add(partitionKey(it)) }
    if (added.isEmpty()) return 0
    subscribedPartitions.addAll(added)
    subscriptionStore.saveSubscribedPartitions(subscribedPartitions.toList())
    return added.size
  }

  fun openHome() {
    currentScreen = Screen.Home
  }

  fun selectPlatform(platform: Platform) {
    // 注意：此处不再设置 platformSwitchLoading = true。
    // 该全局锁曾用于在切换平台时锁定底部栏，但因只在各平台首页
    // loadPage(reset=true) 成功结束时才复位，一旦分类/列表接口异常或
    // 子分类为空（selectedCate2 为 null 提前返回），锁会永远停在 true，
    // 导致底部栏彻底卡死、再也无法切换平台。
    // 骨架屏由各平台首页自身的 loading 状态驱动，无需此全局锁。
    val wasInPlayer = currentScreen == Screen.Player
    selectedPlatform = platform
    currentPartition = null
    if (wasInPlayer) {
      // 看直播时直接切平台：立即清掉播放页残留状态。
      // ExoPlayer 本体会随播放页组合销毁被 stop()/release() 释放（StreamPlayer
      // 的 DisposableEffect 兜底），这里负责把 AppState 侧的引用一并清干净，
      // 避免旧直播间信息/全屏状态跨平台残留占用内存或误导后续返回逻辑。
      playerReturnScreen = null
      playerFullscreen = false
    }
    currentScreen = Screen.Platform
  }

  fun openPlayer(streamer: Streamer, partition: SubscribedPartition? = null) {
    playerReturnScreen = currentScreen
    currentStreamer = streamer
    currentPartition = partition
    currentScreen = Screen.Player
    playerFullscreen = false
  }

  fun openSettings() {
    settingsReturnScreen = currentScreen
    currentScreen = Screen.Settings
  }

  fun back() {
    when (currentScreen) {
      Screen.Home -> Unit
      Screen.Platform -> currentScreen = Screen.Home
      Screen.Player -> {
        currentScreen = playerReturnScreen ?: Screen.Home
        playerReturnScreen = null
        currentStreamer = null
        playerFullscreen = false
      }
      Screen.Settings -> {
        currentScreen = settingsReturnScreen ?: Screen.Home
        settingsReturnScreen = null
      }
    }
  }
}

enum class ThemeMode { System, Light, Dark }

enum class Screen { Home, Platform, Player, Settings }

@Composable
fun rememberAppState(
  repo: DtvRepository = FakeDtvRepository(),
  subscriptionStore: SubscriptionStore = InMemorySubscriptionStore,
): AppState {
  return remember(repo, subscriptionStore) { AppState(repo = repo, subscriptionStore = subscriptionStore) }
}
