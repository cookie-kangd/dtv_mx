package dtv.mobile.platform.douyu

/**
 * 斗鱼源的可替换参数。
 *
 * 这些值原先以 `const val` 硬编码在 `DouyuStreamUrlResolverAndroid` 里，
 * 其中 `webVer` 携带 2023-06 的版本日期、`did` 是固定设备号、`userAgent`
 * 是 2023 年的 Chrome/114。平台风控参数会随网页版更新，硬编码后只能靠
 * 发新 APK 才能修复。统一收口到这里，调用方可从远程配置 / 本地存储注入，
 * 默认值保持与旧行为一致，避免升级后产生回归。
 *
 * 参考 SlotSun/dart_simple_live 的 `DouyuUtils.setDyDid` + `refreshCookie`：
 * did 与 cookie 都应当是运行时可刷新、可配置的凭据。
 */
data class DouyuSourceConfig(
  val did: String = DEFAULT_DID,
  val webVer: String = DEFAULT_WEB_VER,
  val userAgent: String = DEFAULT_USER_AGENT,
  val defaultCdn: String = DEFAULT_CDN,
  /**
   * 显式指定的 cookie（登录态 / 刷新后的凭据）；返回 null 或不注入时，
   * 解析器会按 [did] 生成匿名态标准 cookie `dy_did=<did>; acf_did=<did>`。
   *
   * 注意：这里刻意不把「按 did 生成 cookie」写成默认 lambda —— 那样
   * `config.copy(did = ...)` 之后 lambda 仍捕获旧 did，cookie 与签名参数会不一致。
   */
  val cookieProvider: () -> String? = { null },
) {
  companion object {
    const val DEFAULT_DID = "10000000000000000000000000001501"
    const val DEFAULT_WEB_VER = "Douyu_223061205"
    const val DEFAULT_CDN = "ws-h5"
    const val DEFAULT_USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 Edg/114.0.1823.43"
  }
}
