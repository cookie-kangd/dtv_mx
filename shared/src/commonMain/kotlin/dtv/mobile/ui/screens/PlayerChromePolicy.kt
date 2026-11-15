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

/**
 * 是否显示右侧的「全屏 / 退出全屏」按钮。
 *
 * 旧判定是 `isVideoAspectKnown && !isVerticalVideo`：重连后视频宽高比尚未上报时
 * 按钮会整个消失（5 个按钮变 4 个），横屏全屏下用户甚至没有退出全屏的入口。
 * 已经在全屏时必须始终给出退出按钮；竖屏视频不提供全屏入口。
 */
internal fun shouldShowFullscreenButton(
  fullscreen: Boolean,
  isVideoAspectKnown: Boolean,
  isVerticalVideo: Boolean,
): Boolean = fullscreen || (isVideoAspectKnown && !isVerticalVideo)
