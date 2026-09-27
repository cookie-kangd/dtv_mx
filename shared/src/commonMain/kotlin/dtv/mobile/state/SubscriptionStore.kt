package dtv.mobile.state

import dtv.mobile.model.Streamer
import dtv.mobile.model.Platform
import kotlinx.serialization.Serializable

@Serializable
data class RememberedCategoryEntry(
  val platform: Platform,
  val partitionId: String,
)

interface SubscriptionStore {
  fun loadThemeMode(): ThemeMode
  fun saveThemeMode(value: ThemeMode)

  fun loadFollowedStreamers(): List<Streamer>
  fun saveFollowedStreamers(items: List<Streamer>)

  fun loadLandscapeDanmakuFontScale(): Float
  fun saveLandscapeDanmakuFontScale(value: Float)

  fun loadDanmakuFontScale(): Float
  fun saveDanmakuFontScale(value: Float)

  fun loadDanmakuOpacity(): Float
  fun saveDanmakuOpacity(value: Float)

  fun loadDanmakuAreaFraction(): Float
  fun saveDanmakuAreaFraction(value: Float)

  fun loadSubscribedPartitions(): List<SubscribedPartition>
  fun saveSubscribedPartitions(items: List<SubscribedPartition>)

  fun loadDanmuBlockKeywords(): List<String>
  fun saveDanmuBlockKeywords(items: List<String>)

  fun loadRememberCategoryEnabled(): Boolean
  fun saveRememberCategoryEnabled(value: Boolean)

  fun loadRememberedCategoryByPlatform(): List<RememberedCategoryEntry>
  fun saveRememberedCategoryByPlatform(items: List<RememberedCategoryEntry>)

  fun loadAccentColorHex(): String
  fun saveAccentColorHex(hex: String)

  fun loadCompactCardEnabled(): Boolean
  fun saveCompactCardEnabled(value: Boolean)

  fun loadVideoQuality(): String
  fun saveVideoQuality(value: String)

  fun loadLandscapeEnabled(): Boolean
  fun saveLandscapeEnabled(value: Boolean)

  // 退出清理：默认开启。开启后每次退出 App 清理缓存与临时文件，
  // 但不会影响登录态（如 B站 Cookie）、设置、关注列表等持久数据。
  fun loadExitCleanupEnabled(): Boolean
  fun saveExitCleanupEnabled(value: Boolean)

  // 屏幕刷新率：默认开启 = 跟随系统最高刷新率（高刷屏更流畅）；
  // 关闭 = 锁定 60Hz 标准刷新率（更省电）。
  fun loadHighRefreshEnabled(): Boolean
  fun saveHighRefreshEnabled(value: Boolean)

  // 平台设置：底部切换栏显示哪些平台，以及它们的排列顺序。
  // 两者都按"平台枚举名"持久化，避免 Platform 枚举顺序变动导致的历史数据错位。
  fun loadPlatformOrder(): List<String>
  fun savePlatformOrder(items: List<String>)

  fun loadPlatformDisabled(): List<String>
  fun savePlatformDisabled(items: List<String>)

  // 海外平台（Twitch）推荐只看中文：默认开启。
  // 开启后「推荐」列表只取中文（ZH）频道并按中文观众常看的板块聚合，
  // 与网页版中文推荐流一致；关闭则回到全语言人气总榜。
  fun loadTwitchChineseOnly(): Boolean
  fun saveTwitchChineseOnly(value: Boolean)
}

object InMemorySubscriptionStore : SubscriptionStore {
  private var themeMode: ThemeMode = ThemeMode.System
  private var followed: List<Streamer> = emptyList()
  private var landscapeDanmakuFontScale: Float = 1.2f
  private var danmakuFontScale: Float = 1.0f
  private var danmakuOpacity: Float = 1.0f
  private var danmakuAreaFraction: Float = 0.5f
  private var partitions: List<SubscribedPartition> = emptyList()
  private var danmuBlockKeywords: List<String> = emptyList()
  private var rememberCategoryEnabled: Boolean = true
  private var rememberedCategories: List<RememberedCategoryEntry> = emptyList()
  private var accentColorHex: String = ""
  private var compactCardEnabled: Boolean = true
  private var videoQuality: String = VideoQuality.Highest.name
  private var landscapeEnabled: Boolean = false
  private var exitCleanupEnabled: Boolean = true
  private var highRefreshEnabled: Boolean = true
  private var platformOrder: List<String> = emptyList()
  private var platformDisabled: List<String> = emptyList()
  private var twitchChineseOnly: Boolean = true

  override fun loadThemeMode(): ThemeMode = themeMode

  override fun saveThemeMode(value: ThemeMode) {
    themeMode = value
  }

  override fun loadFollowedStreamers(): List<Streamer> = followed

  override fun saveFollowedStreamers(items: List<Streamer>) {
    followed = items.toList()
  }

  override fun loadLandscapeDanmakuFontScale(): Float = landscapeDanmakuFontScale

  override fun saveLandscapeDanmakuFontScale(value: Float) {
    landscapeDanmakuFontScale = value
  }

  override fun loadDanmakuFontScale(): Float = danmakuFontScale

  override fun saveDanmakuFontScale(value: Float) {
    danmakuFontScale = value
  }

  override fun loadDanmakuOpacity(): Float = danmakuOpacity

  override fun saveDanmakuOpacity(value: Float) {
    danmakuOpacity = value
  }

  override fun loadDanmakuAreaFraction(): Float = danmakuAreaFraction

  override fun saveDanmakuAreaFraction(value: Float) {
    danmakuAreaFraction = value
  }

  override fun loadSubscribedPartitions(): List<SubscribedPartition> = partitions

  override fun saveSubscribedPartitions(items: List<SubscribedPartition>) {
    partitions = items.toList()
  }

  override fun loadDanmuBlockKeywords(): List<String> = danmuBlockKeywords

  override fun saveDanmuBlockKeywords(items: List<String>) {
    danmuBlockKeywords = items.toList()
  }

  override fun loadRememberCategoryEnabled(): Boolean = rememberCategoryEnabled

  override fun saveRememberCategoryEnabled(value: Boolean) {
    rememberCategoryEnabled = value
  }

  override fun loadRememberedCategoryByPlatform(): List<RememberedCategoryEntry> = rememberedCategories

  override fun saveRememberedCategoryByPlatform(items: List<RememberedCategoryEntry>) {
    rememberedCategories = items.toList()
  }

  override fun loadAccentColorHex(): String = accentColorHex

  override fun saveAccentColorHex(hex: String) {
    accentColorHex = hex
  }

  override fun loadCompactCardEnabled(): Boolean = compactCardEnabled

  override fun saveCompactCardEnabled(value: Boolean) {
    compactCardEnabled = value
  }

  override fun loadVideoQuality(): String = videoQuality

  override fun saveVideoQuality(value: String) {
    videoQuality = value
  }

  override fun loadLandscapeEnabled(): Boolean = landscapeEnabled

  override fun saveLandscapeEnabled(value: Boolean) {
    landscapeEnabled = value
  }

  override fun loadExitCleanupEnabled(): Boolean = exitCleanupEnabled

  override fun saveExitCleanupEnabled(value: Boolean) {
    exitCleanupEnabled = value
  }

  override fun loadHighRefreshEnabled(): Boolean = highRefreshEnabled

  override fun saveHighRefreshEnabled(value: Boolean) {
    highRefreshEnabled = value
  }

  override fun loadPlatformOrder(): List<String> = platformOrder

  override fun savePlatformOrder(items: List<String>) {
    platformOrder = items.toList()
  }

  override fun loadPlatformDisabled(): List<String> = platformDisabled

  override fun savePlatformDisabled(items: List<String>) {
    platformDisabled = items.toList()
  }

  override fun loadTwitchChineseOnly(): Boolean = twitchChineseOnly

  override fun saveTwitchChineseOnly(value: Boolean) {
    twitchChineseOnly = value
  }
}
