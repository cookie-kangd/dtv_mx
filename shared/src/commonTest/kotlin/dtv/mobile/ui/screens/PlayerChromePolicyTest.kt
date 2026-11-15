package dtv.mobile.ui.screens

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerChromePolicyTest {
  @Test
  fun portraitFullscreenHidesDock() {
    assertTrue(
      shouldHidePlayerDock(
        fullscreen = true,
        isVerticalFullBleed = false,
        isInPictureInPicture = false,
      ),
    )
  }

  @Test
  fun landscapeFullscreenHidesDock() {
    assertTrue(
      shouldHidePlayerDock(
        fullscreen = true,
        isVerticalFullBleed = false,
        isInPictureInPicture = false,
      ),
    )
  }

  @Test
  fun verticalFullBleedHidesDock() {
    assertTrue(
      shouldHidePlayerDock(
        fullscreen = false,
        isVerticalFullBleed = true,
        isInPictureInPicture = false,
      ),
    )
  }

  @Test
  fun normalPortraitPlaybackKeepsDock() {
    assertFalse(
      shouldHidePlayerDock(
        fullscreen = false,
        isVerticalFullBleed = false,
        isInPictureInPicture = false,
      ),
    )
  }

  @Test
  fun pictureInPictureAlwaysHidesDock() {
    assertTrue(
      shouldHidePlayerDock(
        fullscreen = false,
        isVerticalFullBleed = false,
        isInPictureInPicture = true,
      ),
    )
  }

  @Test
  fun fullscreenButtonStaysVisibleWhenAspectIsUnknown() {
    // 关键回归：横屏重连后 videoAspectRatio 尚未上报时，旧判定会把
    // 「退出全屏」按钮整个藏掉（右侧 5 个按钮变 4 个），用户没法退出全屏。
    assertTrue(
      shouldShowFullscreenButton(
        fullscreen = true,
        isVideoAspectKnown = false,
        isVerticalVideo = false,
      ),
    )
  }

  @Test
  fun fullscreenButtonAppearsForKnownHorizontalVideo() {
    assertTrue(
      shouldShowFullscreenButton(
        fullscreen = false,
        isVideoAspectKnown = true,
        isVerticalVideo = false,
      ),
    )
  }

  @Test
  fun fullscreenButtonHiddenForVerticalVideo() {
    assertFalse(
      shouldShowFullscreenButton(
        fullscreen = false,
        isVideoAspectKnown = true,
        isVerticalVideo = true,
      ),
    )
  }

  @Test
  fun fullscreenButtonHiddenWhileAspectUnknownInPortrait() {
    assertFalse(
      shouldShowFullscreenButton(
        fullscreen = false,
        isVideoAspectKnown = false,
        isVerticalVideo = false,
      ),
    )
  }
}
