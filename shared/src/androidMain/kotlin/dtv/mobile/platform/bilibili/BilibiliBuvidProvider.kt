package dtv.mobile.platform.bilibili

import dtv.mobile.platform.Env4
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * B 站设备指纹 cookie（buvid3 / buvid4）的获取与补齐。
 *
 * 背景：B 站在没有 buvid 的请求上会走更强的风控（搜索返回 -412、分区列表
 * 返回空数组），表现为「列表页一直空着」。原实现有两处问题：
 * 1. `BilibiliLiveListApiAndroid` 写死了 `Cookie: buvid3=i;` —— 这是个无意义的
 *    占位值，既不是合法 buvid，又覆盖了真实 cookie，等于主动触发风控；
 * 2. `BilibiliSearchApiAndroid` 每次搜索都可能重新拉一次指纹接口（只在
 *    cookie 里缺 buvid 时），并发搜索会放大请求。
 *
 * 这里统一收口：优先复用已持久化的 cookie（`BilibiliCookieStoreAndroid`），
 * 缺失时拉 `finger/spi` 补齐并写回，之后整台设备都命中快路径。
 */
class BilibiliBuvidProvider(
  private val client: HttpClient,
  private val cookieStore: BilibiliCookieStoreAndroid,
) {
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }
  private val mutex = Mutex()

  /** 返回带 buvid 的完整 cookie 头；拿不到时返回已有的（可能为空）cookie。 */
  suspend fun ensureCookieHeader(): String {
    val existing = cookieStore.getCookie().orEmpty()
    if (hasBuvid(existing)) return existing

    return mutex.withLock {
      val current = cookieStore.getCookie().orEmpty()
      if (hasBuvid(current)) return@withLock current

      // 显式重抛 CancellationException：裸 runCatching 会把「页面已关闭 / 协程被取消」
      // 当成普通失败吞掉，调用方会误以为拿到了可用的空 cookie 继续发请求。
      val fetched = try {
        fetchFingerSpi(current)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        null
      }
      if (!fetched.isNullOrBlank()) cookieStore.mergeFromCookieHeader(fetched)
      cookieStore.getCookie().orEmpty()
    }
  }

  private fun hasBuvid(cookie: String): Boolean {
    if (cookie.isBlank()) return false
    return cookie.split(';').any { seg ->
      val kv = seg.trim()
      kv.startsWith("buvid3=") && kv.substringAfter('=', "").isNotBlank()
    }
  }

  private suspend fun fetchFingerSpi(cookie: String): String? {
    val text = client.get(Env4.FINGER_SPI) {
      headers {
        append("User-Agent", USER_AGENT)
        append("Referer", Env4.LIVE_HOST + "/")
        if (cookie.isNotBlank()) append("Cookie", cookie)
      }
    }.bodyAsText()

    val data = runCatching { json.parseToJsonElement(text).jsonObject["data"]?.jsonObject }.getOrNull()
      ?: return null
    val b3 = data["b_3"]?.jsonPrimitive?.content?.trim().orEmpty()
    val b4 = data["b_4"]?.jsonPrimitive?.content?.trim().orEmpty()
    if (b3.isBlank()) return null

    return buildString {
      append("buvid3=").append(b3)
      if (b4.isNotBlank()) append("; buvid4=").append(b4)
    }
  }

  companion object {
    const val USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
  }
}
