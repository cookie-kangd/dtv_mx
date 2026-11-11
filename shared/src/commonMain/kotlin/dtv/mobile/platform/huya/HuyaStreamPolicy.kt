package dtv.mobile.platform.huya

/**
 * 虎牙候选流的择优规则（纯逻辑，便于回归测试）。
 *
 * 直播间页面的 `stream` 块会列出多个 CDN（al / hs / tx / 其它），
 * 原实现把排序规则内联在 `resolve()` 里，既无法单测，也没法在不改代码的
 * 情况下屏蔽某个已经失效的 CDN（平台侧 CDN 轮换时只能发新版）。
 *
 * 排序依据沿用原实现的经验值：
 * - `al` 最稳（阿里系，HTTPS 正常）；
 * - `hs` 次之；
 * - `tx` 排后：Android 上部分 CDN 会 302 到带 `_` 的域名，触发 TLS/SNI 校验失败；
 * - 其它未知 CDN 放最后，作为兜底。
 *
 * VIP 房间（4K 需要会员）在当前实现里没有单独字段可判别——页面对普通用户
 * 与会员下发的 `sFlvUrl` 是同一套，区别只在 `iBitRate`。因此这里不臆造
 * 「VIP 白名单」，改为提供 [excludedCdns] 让调用方按实测结果屏蔽，
 * 默认空集合（行为与旧版一致）。
 */
object HuyaStreamPolicy {
  fun rank(cdn: String): Int = when (cdn.trim().lowercase()) {
    "al" -> 0
    "hs" -> 1
    "tx" -> 2
    else -> 3
  }

  /** 选出一路可用流；全部被排除或列表为空时返回 null，由调用方决定报错方式。 */
  fun select(
    candidates: List<Pair<String, String>>,
    excludedCdns: Set<String> = emptySet(),
  ): Pair<String, String>? {
    if (candidates.isEmpty()) return null
    val excluded = excludedCdns.map { it.trim().lowercase() }.toSet()
    return candidates
      // 只要求 URL 非空：基线会把「CDN 字段缺失」的候选当兜底播出去，
      // 这里若一并过滤，页面少个字段就会从「能播」变成「虎牙未找到可用播放地址」。
      .filter { (cdn, url) -> url.isNotBlank() && cdn.trim().lowercase() !in excluded }
      .sortedBy { (cdn, _) -> rank(cdn) }
      .firstOrNull()
  }
}
