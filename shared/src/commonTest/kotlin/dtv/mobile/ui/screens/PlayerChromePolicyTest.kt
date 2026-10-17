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
}
