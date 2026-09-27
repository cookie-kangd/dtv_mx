package dtv.mobile.model

import kotlinx.serialization.Serializable

@Serializable
data class Streamer(
  val platform: Platform,
  val roomId: String,
  val name: String,
  val title: String,
  val viewerText: String,
  val avatarUrl: String? = null,
  val coverUrl: String? = null,
  val isLive: Boolean = true,
  /**
   * 观众数原始值（仅部分平台填充，目前是 Twitch）。
   * [viewerText] 是格式化后的展示串（如 "1.2万"），无法用于排序；
   * Twitch「推荐」会把多个来源的列表合并后按人气重排，因此需要保留原始数值。
   * 老数据里没有这个字段，反序列化后为 null，不影响兼容。
   */
  val viewerCount: Long? = null,
)
