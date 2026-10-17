package dtv.mobile.ui.screens

/**
 * 播放页是否隐藏全局悬浮底栏。
 *
 * 竖屏视频全屏铺满时仍保留底栏；只有横屏全屏和系统画中画隐藏。
 */
internal fun shouldHidePlayerDock(
  fullscreen: Boolean,
  isLandscape: Boolean,
  isInPictureInPicture: Boolean,
): Boolean = isInPictureInPicture || (fullscreen && isLandscape)
