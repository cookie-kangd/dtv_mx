package dtv.mobile.sync

import dtv.mobile.model.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanSyncTransferTest {

  // ---------- platformFromDesktop ----------

  @Test
  fun platformFromDesktop_knownPlatformsCaseInsensitive() {
    assertEquals(Platform.Douyu, platformFromDesktop("douyu"))
    assertEquals(Platform.Douyu, platformFromDesktop("DOUYU"))
    assertEquals(Platform.Douyin, platformFromDesktop(" Douyin "))
    assertEquals(Platform.Huya, platformFromDesktop("HUYA"))
    assertEquals(Platform.Bilibili, platformFromDesktop("bilibili"))
    assertEquals(Platform.Twitch, platformFromDesktop("TWITCH"))
  }

  @Test
  fun platformFromDesktop_unknownReturnsNull() {
    assertNull(platformFromDesktop("youtube"))
    assertNull(platformFromDesktop(""))
    assertNull(platformFromDesktop("custom"))
  }

  // ---------- desktopPlatformFromMobile ----------

  @Test
  fun desktopPlatformFromMobile_roundTripsKnownPlatforms() {
    for (p in listOf(Platform.Douyu, Platform.Douyin, Platform.Huya, Platform.Bilibili, Platform.Twitch)) {
      val desktop = desktopPlatformFromMobile(p)
      assertEquals(p, platformFromDesktop(desktop), "round-trip failed for $p -> $desktop")
    }
  }

  @Test
  fun desktopPlatformFromMobile_customFallsBackToUpperName() {
    assertEquals("CUSTOM", desktopPlatformFromMobile(Platform.Custom))
  }

  // ---------- normalizeStreamerKey ----------

  @Test
  fun normalizeStreamerKey_validKey() {
    assertEquals("DOUYU" to "123", normalizeStreamerKey("douyu:123"))
    assertEquals("HUYA" to "abc", normalizeStreamerKey(" huya : abc "))
  }

  @Test
  fun normalizeStreamerKey_invalidKeys() {
    assertNull(normalizeStreamerKey("no-colon"))
    assertNull(normalizeStreamerKey(":123"))
    assertNull(normalizeStreamerKey("douyu:"))
    assertNull(normalizeStreamerKey("   :   "))
  }
}
