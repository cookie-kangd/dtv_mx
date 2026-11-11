package dtv.mobile.platform.huya

/**
 * 虎牙源的可替换参数。
 *
 * 原先 `generateWebAntiCode()` 里写死 `sdkVersion = 2403051612`（2024-03 的客户端版本），
 * UA 与 `huya_ua` cookie 也散落在 `fetchHtml` 的两个分支里。虎牙的 anti-code
 * 校验会随客户端版本推进，硬编码意味着「平台一升级，只能等新 APK」。
 * 统一收口到这里后，可由远程配置 / 本地设置注入新值，默认值与旧行为完全一致。
 *
 * 关于 VIP 房间：当前实现只从直播间页面取「一路」流（按 CDN 择优），
 * 不做 4K/码率分档，因此参考项目 `simple_live_core` 里「VIP 房间跳过 4K」
 * 的分支在这里没有对应位置；需要屏蔽某个 CDN 时用 [excludedCdns]。
 */
data class HuyaSourceConfig(
  /** anti-code 里 `sv` 参数：虎牙客户端版本号（整型时间戳样式）。 */
  val sdkVersion: Long = DEFAULT_SDK_VERSION,
  /** anti-code 里 `t` 参数；虎牙网页端固定 100。 */
  val paramsT: Long = DEFAULT_PARAMS_T,
  val desktopUserAgent: String = DEFAULT_DESKTOP_UA,
  val iosUserAgent: String = DEFAULT_IOS_UA,
  /** 页面请求附带的 `huya_ua` cookie，缺失时部分房间会返回无 stream 块的降级页。 */
  val webH5Cookie: String = DEFAULT_WEBH5_COOKIE,
  /** 实测已失效、需要跳过的 CDN 名（如 `tx`）；大小写不敏感。默认不排除任何 CDN。 */
  val excludedCdns: Set<String> = emptySet(),
) {
  companion object {
    const val DEFAULT_SDK_VERSION = 2_403_051_612L
    const val DEFAULT_PARAMS_T = 100L
    const val DEFAULT_WEBH5_COOKIE = "huya_ua=webh5&0.1.0&websocket"
    const val DEFAULT_DESKTOP_UA =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    const val DEFAULT_IOS_UA =
      "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"
  }
}
