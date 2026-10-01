package dtv.mobile.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * commonMain 纯函数回归测试。
 *
 * 这些函数原先包含 JVM 专属 API（java.util.Locale / System.currentTimeMillis），
 * 已改写为平台无关实现。测试锁定改写后的行为与旧实现一致（尤其是"截断而非四舍五入"）。
 */
class PureUtilsTest {

  // ---------- formatViewerCountWanIfNeeded ----------

  @Test
  fun viewerCount_truncatesToTenthsNotRounds() {
    // 旧实现 String.format(Locale.US, "%.1f万", 173.9892f) 四舍五入会得到 174.0万，
    // 现实现刻意改为向下截断（与项目其它人气显示口径一致）。
    assertEquals("173.9万", formatViewerCountWanIfNeeded("1739892"))
    assertEquals("12.3万", formatViewerCountWanIfNeeded("123456"))
    assertEquals("1.9万", formatViewerCountWanIfNeeded("19999"))
  }

  @Test
  fun viewerCount_exactBoundaries() {
    assertEquals("1.0万", formatViewerCountWanIfNeeded("10000"))
    assertEquals("9999", formatViewerCountWanIfNeeded("9999"))
    assertEquals("10.0万", formatViewerCountWanIfNeeded("100000"))
  }

  @Test
  fun viewerCount_passthroughCases() {
    // 已含"万"的服务端字符串原样保留
    assertEquals("12.3万", formatViewerCountWanIfNeeded("12.3万"))
    // 非纯数字原样保留
    assertEquals("1.2w", formatViewerCountWanIfNeeded("1.2w"))
    assertEquals("abc", formatViewerCountWanIfNeeded("abc"))
    // 空串 / 空白
    assertEquals("", formatViewerCountWanIfNeeded(""))
    assertEquals("", formatViewerCountWanIfNeeded("   "))
    // 超长数字溢出 Long 时原样返回
    assertEquals("99999999999999999999", formatViewerCountWanIfNeeded("99999999999999999999"))
  }

  @Test
  fun viewerCount_trimsWhitespace() {
    assertEquals("1.2万", formatViewerCountWanIfNeeded(" 12345 "))
  }

  // ---------- normalizeHttpUrl ----------

  @Test
  fun normalizeHttpUrl_addsHttpsForProtocolRelative() {
    assertEquals("https://example.com/a", normalizeHttpUrl("//example.com/a"))
  }

  @Test
  fun normalizeHttpUrl_trimsAndKeepsAbsolute() {
    assertEquals("http://a.com/x", normalizeHttpUrl("  http://a.com/x  "))
  }

  @Test
  fun normalizeHttpUrl_nullOrBlank() {
    assertNull(normalizeHttpUrl(null))
    assertNull(normalizeHttpUrl(""))
    assertNull(normalizeHttpUrl("   "))
  }

  // ---------- decodeHtmlEntities ----------

  @Test
  fun decodeHtmlEntities_commonEntities() {
    assertEquals("a&b<c>d\"e'f", decodeHtmlEntities("a&amp;b&lt;c&gt;d&quot;e&#39;f"))
  }

  @Test
  fun decodeHtmlEntities_noEntityUntouched() {
    assertEquals("plain text", decodeHtmlEntities("plain text"))
  }
}
