package dtv.mobile.platform.douyu

/**
 * 斗鱼播放直链的租约策略（平台无关的纯逻辑，便于回归测试）。
 *
 * 背景：斗鱼对部分 CDN（fcdn=ws）会下发带 `expire=300` 的临时直链，
 * 播放器起播 5 分钟后链路被服务端切断，表现为「看着看着断流」。
 * 参考 SlotSun/dart_simple_live 的 `simple_live_core/douyu_site.dart`：
 * 对该组合追加 `expire=0`，让链路按会话有效期播放；同时该仓库在
 * v1.8.13 / v1.8.14（2026-09）连续两版修正此问题，DTV 桌面端也在
 * 2026-09-22 单独做了「直链到期前自动续流」。
 *
 * 本对象只做 URL 层面的兜底，不发起网络请求；主动续期所需的到期时间
 * 由 [leaseExpiresAt] / [shouldRefresh] 提供给播放层使用。
 */
object DouyuPlayUrlPolicy {
  private val SHORT_LEASE = Regex("""[?&]expire=300(?:[&#]|$)""")
  private val WS_CDN = Regex("""[?&]fcdn=ws(?:[&#]|$)""")
  private val EXPIRE_ZERO = Regex("""[?&]expire=0(?:[&#]|$)""")

  const val SHORT_LEASE_MILLIS: Long = 300_000L

  /** 是否为「5 分钟短租约 + ws CDN」这条会断流的组合。 */
  fun isShortLivedWsLease(url: String): Boolean =
    SHORT_LEASE.containsMatchIn(url) && WS_CDN.containsMatchIn(url)

  /**
   * 为短租约直链追加 `expire=0`。其余情况原样返回。
   * URL 已带 `expire=0`、或不是 ws CDN 时不重复追加。
   */
  fun stabilize(url: String): String {
    if (!isShortLivedWsLease(url)) return url
    if (EXPIRE_ZERO.containsMatchIn(url)) return url
    val joiner = if (url.contains('?')) "&" else "?"
    return url + joiner + "expire=0"
  }

  /** 直链预期到期时间（绝对毫秒）；非短租约返回 null。 */
  fun leaseExpiresAt(url: String, issuedAtMillis: Long): Long? =
    if (isShortLivedWsLease(url)) issuedAtMillis + SHORT_LEASE_MILLIS else null

  /**
   * 是否应提前重取播放地址。默认提前 30 秒，避免卡在断流瞬间。
   * 即使已用 [stabilize] 追加 `expire=0`，仍建议在租约窗口内重取一次，
   * 因为 CDN 侧策略可能变化。
   */
  fun shouldRefresh(
    url: String,
    nowMillis: Long,
    issuedAtMillis: Long,
    leadMillis: Long = 30_000L,
  ): Boolean {
    val expiresAt = leaseExpiresAt(url, issuedAtMillis) ?: return false
    return nowMillis >= expiresAt - leadMillis
  }
}
