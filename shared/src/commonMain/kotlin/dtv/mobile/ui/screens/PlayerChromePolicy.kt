package dtv.mobile.ui.screens

/**
 * 播放页是否隐藏全局悬浮底栏。
 *
 * 横屏全屏、竖屏全屏、竖屏视频铺满以及系统画中画都隐藏。
 */
internal fun shouldHidePlayerDock(
  fullscreen: Boolean,
  isVerticalFullBleed: Boolean,
  isInPictureInPicture: Boolean,
): Boolean = isInPictureInPicture || fullscreen || isVerticalFullBleed
