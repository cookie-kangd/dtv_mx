package dtv.mobile.model

import kotlinx.serialization.Serializable

@Serializable
enum class Platform(val title: String) {
  Custom("自定义"),
  Douyu("斗鱼"),
  Huya("虎牙"),
  Douyin("抖音"),
  Bilibili("B站"),
  Twitch("Twitch"),
}

