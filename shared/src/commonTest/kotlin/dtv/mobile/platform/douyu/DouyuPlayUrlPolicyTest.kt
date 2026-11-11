package dtv.mobile.platform.douyu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 斗鱼 5 分钟断流兜底的纯逻辑回归测试。 */
class DouyuPlayUrlPolicyTest {

  private val shortLeaseWs =
    "https://hls3-a.douyucdn.cn/live/1234rXxyz.flv?fcdn=ws&expire=300&sign=abc"

  @Test
  fun shortLeaseWs_isDetected() {
    assertTrue(DouyuPlayUrlPolicy.isShortLivedWsLease(shortLeaseWs))
  }

  @Test
  fun shortLeaseWs_getsExpireZero() {
    val stabilized = DouyuPlayUrlPolicy.stabilize(shortLeaseWs)
    assertTrue(stabilized.endsWith("&expire=0"))
    assertTrue(stabilized.contains("expire=300"), "保留原参数，仅追加覆盖值")
  }

  @Test
  fun nonWsCdn_isLeftAlone() {
    val url = "https://hls3-a.douyucdn.cn/live/x.flv?fcdn=hw&expire=300&sign=abc"
    assertFalse(DouyuPlayUrlPolicy.isShortLivedWsLease(url))
    assertEquals(url, DouyuPlayUrlPolicy.stabilize(url))
  }

  @Test
  fun otherExpireValue_isNotMisdetected() {
    // expire=3000 不能被 expire=300 的子串匹配误判
    val url = "https://hls3-a.douyucdn.cn/live/x.flv?fcdn=ws&expire=3000"
    assertFalse(DouyuPlayUrlPolicy.isShortLivedWsLease(url))
    assertEquals(url, DouyuPlayUrlPolicy.stabilize(url))
  }

  @Test
  fun alreadyStabilized_isNotDuplicated() {
    val url = "$shortLeaseWs&expire=0"
    assertEquals(url, DouyuPlayUrlPolicy.stabilize(url))
  }

  @Test
  fun normalUrl_isUntouched() {
    val url = "https://hls3-a.douyucdn.cn/live/x.flv?sign=abc"
    assertEquals(url, DouyuPlayUrlPolicy.stabilize(url))
    assertNull(DouyuPlayUrlPolicy.leaseExpiresAt(url, 1_000L))
  }

  @Test
  fun lease_andRefreshWindow() {
    val issuedAt = 10_000L
    assertEquals(
      issuedAt + 300_000L,
      DouyuPlayUrlPolicy.leaseExpiresAt(shortLeaseWs, issuedAt),
    )
    assertFalse(DouyuPlayUrlPolicy.shouldRefresh(shortLeaseWs, issuedAt + 100_000L, issuedAt))
    assertTrue(DouyuPlayUrlPolicy.shouldRefresh(shortLeaseWs, issuedAt + 270_000L, issuedAt))
    assertTrue(DouyuPlayUrlPolicy.shouldRefresh(shortLeaseWs, issuedAt + 299_000L, issuedAt))
    assertFalse(DouyuPlayUrlPolicy.shouldRefresh("https://x/y.flv?sign=abc", issuedAt + 999_999L, issuedAt))
  }

  @Test
  fun urlWithoutExpireParameter_isNotStabilized() {
    val url = "https://x/y.flv?fcdn=ws&sign=abc"
    assertFalse(DouyuPlayUrlPolicy.isShortLivedWsLease(url))
    assertEquals(url, DouyuPlayUrlPolicy.stabilize(url))
  }
}
