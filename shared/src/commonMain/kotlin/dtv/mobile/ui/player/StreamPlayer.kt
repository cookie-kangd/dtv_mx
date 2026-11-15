package dtv.mobile.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun StreamPlayer(
  url: String,
  playerGeneration: Int = 0,
  fullscreen: Boolean = false,
  liveMode: Boolean = true,
  zoomToFill: Boolean = false,
  backgroundAudio: Boolean = false,
  onVideoAspectRatioChanged: (Float?) -> Unit = {},
  onError: (String) -> Unit = {},
  // 播放进度长时间没有推进（画面卡死但播放器没报错）时回调，由播放页重取地址重建播放器
  onStalled: () -> Unit = {},
  modifier: Modifier = Modifier,
)
