package dtv.mobile.state

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import dtv.mobile.model.Streamer

class SubscriptionStoreAndroid(
  appContext: Context,
) : SubscriptionStore {
  private val prefs = appContext.getSharedPreferences("dtv_subscriptions", Context.MODE_PRIVATE)
  private val json = Json { ignoreUnknownKeys = true }

  override fun loadThemeMode(): ThemeMode {
    val raw = prefs.getString("theme_mode", null)?.trim().orEmpty()
    return runCatching { ThemeMode.valueOf(raw) }.getOrElse { ThemeMode.System }
  }

  override fun saveThemeMode(value: ThemeMode) {
    prefs.edit().putString("theme_mode", value.name).apply()
  }

  override fun loadFollowedStreamers(): List<Streamer> {
    val raw = prefs.getString("followed_streamers", null)?.takeIf { it.isNotBlank() } ?: return emptyList()
    return runCatching { json.decodeFromString(ListSerializer(Streamer.serializer()), raw) }.getOrElse { emptyList() }
  }

  override fun saveFollowedStreamers(items: List<Streamer>) {
    val raw = json.encodeToString(ListSerializer(Streamer.serializer()), items)
    prefs.edit().putString("followed_streamers", raw).apply()
  }

  override fun loadLandscapeDanmakuFontScale(): Float {
    val raw = prefs.getFloat("landscape_danmaku_font_scale", 1.2f)
    return raw.coerceIn(0.85f, 2.0f)
  }

  override fun saveLandscapeDanmakuFontScale(value: Float) {
    prefs.edit().putFloat("landscape_danmaku_font_scale", value.coerceIn(0.85f, 2.0f)).apply()
  }

  override fun loadDanmakuFontScale(): Float {
    val raw = prefs.getFloat("danmaku_font_scale", 1.0f)
    return raw.coerceIn(0.85f, 1.3f)
  }

  override fun saveDanmakuFontScale(value: Float) {
    prefs.edit().putFloat("danmaku_font_scale", value.coerceIn(0.85f, 1.3f)).apply()
  }

  override fun loadDanmakuOpacity(): Float {
    val raw = prefs.getFloat("danmaku_opacity", 1.0f)
    return raw.coerceIn(0.35f, 1.0f)
  }

  override fun saveDanmakuOpacity(value: Float) {
    prefs.edit().putFloat("danmaku_opacity", value.coerceIn(0.35f, 1.0f)).apply()
  }

  override fun loadDanmakuAreaFraction(): Float {
    val raw = prefs.getFloat("danmaku_area_fraction", 0.5f)
    return raw.coerceIn(0.25f, 1.0f)
  }

  override fun saveDanmakuAreaFraction(value: Float) {
    prefs.edit().putFloat("danmaku_area_fraction", value.coerceIn(0.25f, 1.0f)).apply()
  }

  override fun loadSubscribedPartitions(): List<SubscribedPartition> {
    val raw = prefs.getString("subscribed_partitions", null)?.takeIf { it.isNotBlank() } ?: return emptyList()
    return runCatching { json.decodeFromString(ListSerializer(SubscribedPartition.serializer()), raw) }.getOrElse { emptyList() }
  }

  override fun saveSubscribedPartitions(items: List<SubscribedPartition>) {
    val raw = json.encodeToString(ListSerializer(SubscribedPartition.serializer()), items)
    prefs.edit().putString("subscribed_partitions", raw).apply()
  }

  override fun loadDanmuBlockKeywords(): List<String> {
    val raw = prefs.getString("danmu_block_keywords", null)?.takeIf { it.isNotBlank() } ?: return emptyList()
    return runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }.getOrElse { emptyList() }
  }

  override fun saveDanmuBlockKeywords(items: List<String>) {
    val raw = json.encodeToString(ListSerializer(String.serializer()), items)
    prefs.edit().putString("danmu_block_keywords", raw).apply()
  }

  // 「记住栏目」默认开启：首次安装即为开启状态
  override fun loadRememberCategoryEnabled(): Boolean = prefs.getBoolean("remember_category_enabled", true)

  override fun saveRememberCategoryEnabled(value: Boolean) {
    prefs.edit().putBoolean("remember_category_enabled", value).apply()
  }

  override fun loadRememberedCategoryByPlatform(): List<RememberedCategoryEntry> {
    val raw = prefs.getString("remembered_category_by_platform", null)?.takeIf { it.isNotBlank() } ?: return emptyList()
    return runCatching { json.decodeFromString(ListSerializer(RememberedCategoryEntry.serializer()), raw) }.getOrElse { emptyList() }
  }

  override fun saveRememberedCategoryByPlatform(items: List<RememberedCategoryEntry>) {
    val raw = json.encodeToString(ListSerializer(RememberedCategoryEntry.serializer()), items)
    prefs.edit().putString("remembered_category_by_platform", raw).apply()
  }

  override fun loadAccentColorHex(): String = prefs.getString("accent_color_hex", "") ?: ""

  override fun saveAccentColorHex(hex: String) {
    prefs.edit().putString("accent_color_hex", hex).apply()
  }

  // 小卡片模式默认开启
  override fun loadCompactCardEnabled(): Boolean = prefs.getBoolean("compact_card_enabled", true)

  override fun saveCompactCardEnabled(value: Boolean) {
    prefs.edit().putBoolean("compact_card_enabled", value).apply()
  }

  // 默认取最高画质
  override fun loadVideoQuality(): String =
    prefs.getString("video_quality", null)?.takeIf { it.isNotBlank() } ?: VideoQuality.Highest.name

  override fun saveVideoQuality(value: String) {
    prefs.edit().putString("video_quality", value).apply()
  }

  // 默认横屏默认关闭
  override fun loadLandscapeEnabled(): Boolean = prefs.getBoolean("landscape_enabled", false)

  override fun saveLandscapeEnabled(value: Boolean) {
    prefs.edit().putBoolean("landscape_enabled", value).apply()
  }

  override fun loadExitCleanupEnabled(): Boolean = prefs.getBoolean("exit_cleanup_enabled", true)

  override fun saveExitCleanupEnabled(value: Boolean) {
    prefs.edit().putBoolean("exit_cleanup_enabled", value).apply()
  }

  // 屏幕刷新率：默认开启（跟随系统最高刷新率）
  override fun loadHighRefreshEnabled(): Boolean = prefs.getBoolean("high_refresh_enabled", true)

  override fun saveHighRefreshEnabled(value: Boolean) {
    prefs.edit().putBoolean("high_refresh_enabled", value).apply()
  }

  // 平台排列顺序（按枚举名保存）。为空表示尚未自定义，由 AppState 回落到默认顺序。
  override fun loadPlatformOrder(): List<String> {
    val raw = prefs.getString("platform_order", null)?.takeIf { it.isNotBlank() } ?: return emptyList()
    return runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }.getOrElse { emptyList() }
  }

  override fun savePlatformOrder(items: List<String>) {
    val raw = json.encodeToString(ListSerializer(String.serializer()), items)
    prefs.edit().putString("platform_order", raw).apply()
  }

  // 被关闭的平台（按枚举名保存）。从未保存过时默认关闭 Twitch（新平台需手动开启）；
  // 用户在平台设置里操作过任意开关后会写入完整列表，此后以用户保存的为准。
  override fun loadPlatformDisabled(): List<String> {
    val raw = prefs.getString("platform_disabled", null)?.takeIf { it.isNotBlank() }
      ?: return listOf(dtv.mobile.model.Platform.Twitch.name)
    return runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }.getOrElse { emptyList() }
  }

  override fun savePlatformDisabled(items: List<String>) {
    val raw = json.encodeToString(ListSerializer(String.serializer()), items)
    prefs.edit().putString("platform_disabled", raw).apply()
  }
}
