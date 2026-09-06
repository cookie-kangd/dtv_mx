package dtv.mobile.ui.system

import androidx.compose.runtime.Composable

@Composable
expect fun FullscreenEffect(
  enabled: Boolean,
  lockLandscape: Boolean,
  exitToPortrait: Boolean,
  /**
   * 强制竖屏：优先级高于其它方向控制。用于「平板等进房时设备就是横屏」的场景——
   * 未开启「默认横屏」时进入直播间必须保持竖屏，不能跟随系统当前方向进横屏。
   */
  forcePortrait: Boolean,
)

