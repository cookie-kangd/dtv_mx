package dtv.mobile.platform.douyin

import android.content.Context
import dtv.mobile.platform.Env3
import dtv.mobile.util.AppLog
import dtv.mobile.util.currentTimeMillis
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 抖音 Web 凭据源：运行时获取、可缓存、可回写。需为 public，作为 WebApi 的构造参数类型。 */
interface DouyinCookieSource {
  suspend fun get(): String?
  fun remember(cookie: String)
}

/**
 * 动态获取抖音直播间 Cookie（ttwid / __ac_nonce / msToken），替代硬编码凭据。
 *
 * 原先 `DouyinWebApiAndroid.DEFAULT_COOKIE` 里固化了一个 ttwid，其内嵌时间戳
 * 为 2025-10-21，属于会随平台风控失效的静态凭据；仓库里虽然已有
 * `DouyinWebViewCookieAndroid`，但没有任何调用点。这里把它接入请求链：
 *
 * 1. WebView 访问直播间域名，取得真实浏览器下发的 Cookie；
 * 2. 结果在进程内缓存 20 分钟，并用 Mutex 做 single-flight，避免并发进房
 *    时重复创建 WebView（每次最多等待 8 秒）；
 * 3. HTTP 响应里的 Set-Cookie 可通过 [remember] 回写，形成自愈；
 * 4. 获取失败时返回 null —— 请求不带 Cookie，让服务端下发新凭据，
 *    不再回退到过期的硬编码值。
 *
 * 参考 SlotSun/dart_simple_live 的 `douyin_site.dart:_getWebCookie`。
 */
internal class DouyinCookieProvider(
  private val appContext: Context,
) : DouyinCookieSource {
  private companion object {
    const val TAG = "DTV-Douyin"
    const val TTL_MILLIS = 20 * 60 * 1000L
    const val FETCH_TIMEOUT_MILLIS = 8_000L
  }

  private val webViewCookies = DouyinWebViewCookieAndroid(appContext)
  private val lock = Mutex()

  @Volatile private var cached: String? = null
  @Volatile private var cachedAtMillis: Long = 0L

  override suspend fun get(): String? {
    val snapshot = cached
    if (!snapshot.isNullOrBlank() && currentTimeMillis() - cachedAtMillis < TTL_MILLIS) {
      return snapshot
    }
    return lock.withLock {
      val again = cached
      if (!again.isNullOrBlank() && currentTimeMillis() - cachedAtMillis < TTL_MILLIS) {
        return@withLock again
      }
      val fresh = runCatching {
        webViewCookies.fetch(
          url = Env3.HOST + "/",
          userAgent = DouyinWebApiAndroid.DEFAULT_USER_AGENT,
          timeoutMs = FETCH_TIMEOUT_MILLIS,
        )
      }.onFailure { t ->
        AppLog.w(TAG, "douyin dynamic cookie failed: ${t.message}")
      }.getOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }

      cached = fresh
      cachedAtMillis = currentTimeMillis()
      if (fresh == null) {
        AppLog.w(TAG, "douyin cookie unavailable, sending request without Cookie")
      } else {
        AppLog.i(TAG, "douyin dynamic cookie refreshed len=${fresh.length}")
      }
      fresh
    }
  }

  /** 把接口响应里的 Set-Cookie 回写为当前凭据，供后续请求复用。 */
  override fun remember(cookie: String) {
    val value = cookie.trim().takeIf { it.isNotBlank() } ?: return
    cached = value
    cachedAtMillis = currentTimeMillis()
  }
}
