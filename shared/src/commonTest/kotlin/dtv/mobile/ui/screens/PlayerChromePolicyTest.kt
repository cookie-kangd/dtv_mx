package dtv.mobile.ui.screens

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerChromePolicyTest {
  @Test
  fun portraitFullscreenKeepsDock() {
    assertFalse(
      shouldHidePlayerDock(
        fullscreen = true,
        isLandscape = false,
        isInPictureInPicture = false,
      ),
    )
  }

  @Test
  fun landscapeFullscreenHidesDock() {
    assertTrue(
      shouldHidePlayerDock(
        fullscreen = true,
        isLandscape = true,
        isInPictureInPicture = false,
      ),
    )
  }

  @Test
  fun landscapeWithoutFullscreenKeepsDock() {
    assertFalse(
      shouldHidePlayerDock(
        fullscreen = false,
        isLandscape = true,
        isInPictureInPicture = false,
      ),
    )
  }

  @Test
  fun pictureInPictureAlwaysHidesDock() {
    assertTrue(
      shouldHidePlayerDock(
        fullscreen = false,
        isLandscape = false,
        isInPictureInPicture = true,
      ),
    )
  }
}
