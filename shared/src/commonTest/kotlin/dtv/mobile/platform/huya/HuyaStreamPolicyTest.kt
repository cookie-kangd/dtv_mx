package dtv.mobile.platform.huya

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 虎牙 CDN 择优规则的纯逻辑回归测试。 */
class HuyaStreamPolicyTest {

  private val alFlv = "al" to "http://al.flv.huya.com/src/1.flv?wsSecret=a"
  private val hsFlv = "hs" to "http://hs.flv.huya.com/src/1.flv?wsSecret=b"
  private val txFlv = "tx" to "http://tx.flv.huya.com/src/1.flv?wsSecret=c"
  private val otherFlv = "hw" to "http://hw.flv.huya.com/src/1.flv?wsSecret=d"

  @Test
  fun rank_prefersAlThenHsThenTx() {
    assertTrue(HuyaStreamPolicy.rank("al") < HuyaStreamPolicy.rank("hs"))
    assertTrue(HuyaStreamPolicy.rank("hs") < HuyaStreamPolicy.rank("tx"))
    assertTrue(HuyaStreamPolicy.rank("tx") < HuyaStreamPolicy.rank("whatever"))
  }

  @Test
  fun rank_isCaseInsensitive() {
    assertEquals(HuyaStreamPolicy.rank("AL"), HuyaStreamPolicy.rank("al"))
  }

  @Test
  fun select_prefersAlRegardlessOfInputOrder() {
    val picked = HuyaStreamPolicy.select(listOf(otherFlv, txFlv, hsFlv, alFlv))
    assertEquals("al", picked?.first)
  }

  @Test
  fun select_skipsExcludedCdn() {
    val picked = HuyaStreamPolicy.select(listOf(alFlv, hsFlv, txFlv), excludedCdns = setOf("al"))
    assertEquals("hs", picked?.first)
  }

  @Test
  fun select_exclusionIsCaseInsensitive() {
    val picked = HuyaStreamPolicy.select(listOf(alFlv, hsFlv), excludedCdns = setOf(" AL "))
    assertEquals("hs", picked?.first)
  }

  @Test
  fun select_returnsNullWhenAllExcluded() {
    val all = listOf(alFlv, hsFlv, txFlv)
    assertNull(HuyaStreamPolicy.select(all, excludedCdns = setOf("al", "hs", "tx")))
  }

  @Test
  fun select_returnsNullOnEmptyList() {
    assertNull(HuyaStreamPolicy.select(emptyList()))
  }

  @Test
  fun select_skipsBlankUrl_butKeepsMissingCdnAsFallback() {
    // CDN 字段缺失的候选仍保留（作为兜底），只是排在有名字的 CDN 之后。
    val picked = HuyaStreamPolicy.select(listOf("al" to "", "" to "http://x/1.flv", txFlv))
    assertEquals("tx", picked?.first)
    // 只剩兜底项时不能返回 null：页面改版少字段时应尽力播出去，而不是直接报错。
    assertEquals("", HuyaStreamPolicy.select(listOf("" to "http://x/1.flv"))?.first)
    // URL 为空的候选必须丢弃（无法起播）。
    assertNull(HuyaStreamPolicy.select(listOf("al" to "  ")))
  }
}
