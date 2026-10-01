package dtv.mobile.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionCompareTest {

  @Test
  fun normalize_stripsPrefixAndSpaces() {
    assertEquals("1.2.3", normalizeVersion(" v1.2.3 "))
    assertEquals("1.2.3", normalizeVersion("V1.2.3"))
    assertEquals("1.2.3", normalizeVersion("1.2.3"))
  }

  @Test
  fun equalVersions() {
    assertEquals(0, compareVersion("1.0.0", "1.0.0"))
    assertEquals(0, compareVersion("v1.0.0", "1.0.0"))
    // 缺段按 0 补齐
    assertEquals(0, compareVersion("1.0", "1.0.0"))
  }

  @Test
  fun numericSegmentsCompareNumericallyNotLexically() {
    // 字典序会误判 "1.0.9" > "1.0.10"，必须按数字比
    assertTrue(compareVersion("1.0.10", "1.0.9") > 0)
    assertTrue(compareVersion("2.0.0", "10.0.0") < 0)
  }

  @Test
  fun majorMinorPatchOrdering() {
    assertTrue(compareVersion("v0.2.16", "0.2.15") > 0)
    assertTrue(compareVersion("0.2.15", "0.2.16") < 0)
    assertTrue(compareVersion("1.0.0", "0.9.9") > 0)
  }

  @Test
  fun nonNumericSegmentsFallBackToLexical() {
    // 含非数字段时走字符串比较，不抛异常
    assertTrue(compareVersion("1.0.0-beta", "1.0.0-alpha") > 0)
    assertEquals(0, compareVersion("1.0.0-beta", "1.0.0-beta"))
  }

  @Test
  fun emptyInputsAreSafe() {
    assertTrue(compareVersion("1.0.0", "") > 0)
    assertEquals(0, compareVersion("", ""))
  }
}
