package dtv.mobile.platform.bilibili

import dtv.mobile.platform.Env4
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import kotlin.math.min

/**
 * B 站 WBI 签名（https://github.com/SocialSisterYi/bilibili-API-collect）。
 *
 * 原先这套实现只以 private 方法的形式存在于 `BilibiliDanmakuClientAndroid` 里，
 * 仅供弹幕 `getDanmuInfo` 使用；而同样需要签名的「直播搜索」 (`search/type`)
 * 一直裸调接口。B 站对未签名的 web 接口会返回 `code=-412`（请求被拦截），
 * 表现为搜索永远空列表 —— 而且因为没有错误码判断，界面上看不出是「真的没结果」
 * 还是「被拦截」。抽成独立类后，弹幕与搜索共用同一份 mixin key 缓存，
 * 既消除重复实现，也让签名行为只有一处需要维护。
 *
 * 缓存策略：nav 接口返回的 img_key/sub_key 每日轮换，缓存 6 小时后重取；
 * 并发调用走 Mutex 单飞，避免多个页面同时拉 nav。
 */
internal class BilibiliWbiSigner(
  private val client: HttpClient,
  private val cookieProvider: () -> String?,
  private val userAgent: String = DEFAULT_USER_AGENT,
) {
  private data class NavInfo(
    val imgKey: String,
    val subKey: String,
    val mid: Long?,
  )

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }
  private val mutex = Mutex()

  @Volatile private var cachedNav: NavInfo? = null

  @Volatile private var cachedAtMs: Long = 0L

  /** 登录用户 mid；未登录为 null。弹幕鉴权需要。 */
  suspend fun navMid(): Long? = navInfo().mid

  /**
   * 按 WBI 规则签名并返回可直接拼在 `?` 之后的查询串（含 `wts` 与 `w_rid`）。
   *
   * 参数必须走这里统一编码：调用方若同时用 ktor 的 `parameter()` 追加参数，
   * 那些参数不会进入签名，服务端会判定签名不匹配。
   */
  suspend fun signQuery(params: List<Pair<String, String>>): String {
    val nav = navInfo()
    val mixinKey = mixinKey(nav.imgKey + nav.subKey)
    val wts = (System.currentTimeMillis() / 1000L).toString()
    val query = (params + ("wts" to wts))
      .sortedBy { it.first }
      .joinToString("&") { (k, v) -> "${encodeComponent(k)}=${encodeComponent(v)}" }
    return "$query&w_rid=${md5Hex(query + mixinKey)}"
  }

  private suspend fun navInfo(): NavInfo {
    val cached = cachedNav
    if (cached != null && System.currentTimeMillis() - cachedAtMs < NAV_TTL_MS) return cached

    return mutex.withLock {
      val again = cachedNav
      if (again != null && System.currentTimeMillis() - cachedAtMs < NAV_TTL_MS) {
        return@withLock again
      }

      val cookie = cookieProvider()
      val text = client.get(Env4.NAV) {
        headers {
          append("User-Agent", userAgent)
          append("Referer", Env4.HOST + "/")
          if (!cookie.isNullOrBlank()) append("Cookie", cookie)
        }
      }.bodyAsText()

      val root = json.parseToJsonElement(text).jsonObject
      val data = root["data"]?.jsonObject ?: error("B站 nav data 为空")
      val mid = data["mid"]?.jsonPrimitive?.content?.toLongOrNull()
      val wbi = data["wbi_img"]?.jsonObject ?: error("B站 nav wbi_img 为空")
      val imgUrl = wbi["img_url"]?.jsonPrimitive?.content?.trim().orEmpty()
      val subUrl = wbi["sub_url"]?.jsonPrimitive?.content?.trim().orEmpty()

      val nav = NavInfo(
        imgKey = takeFilename(imgUrl) ?: error("B站 img_key 解析失败"),
        subKey = takeFilename(subUrl) ?: error("B站 sub_key 解析失败"),
        mid = mid,
      )
      cachedNav = nav
      cachedAtMs = System.currentTimeMillis()
      nav
    }
  }

  private fun takeFilename(url: String): String? {
    val slash = url.lastIndexOf('/')
    if (slash < 0) return null
    val tail = url.substring(slash + 1)
    val dot = tail.lastIndexOf('.')
    if (dot <= 0) return null
    return tail.substring(0, dot)
  }

  private fun mixinKey(orig: String): String {
    val bytes = orig.encodeToByteArray()
    val take = min(32, MIXIN_KEY_ENC_TAB.size)
    val sb = StringBuilder(32)
    for (i in 0 until take) {
      val idx = MIXIN_KEY_ENC_TAB[i]
      val b = bytes.getOrNull(idx) ?: 0
      sb.append((b.toInt() and 0xFF).toChar())
    }
    return sb.toString()
  }

  private fun md5Hex(s: String): String {
    val bytes = MessageDigest.getInstance("MD5").digest(s.encodeToByteArray())
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) sb.append(String.format("%02x", b))
    return sb.toString()
  }

  /**
   * WBI 规定的百分号编码：仅 `字母数字 - _ . ~` 原样保留，
   * `! ' ( ) *` 直接丢弃（不做 %XX），其余按 UTF-8 逐字节转义。
   */
  private fun encodeComponent(input: String): String {
    val out = StringBuilder(input.length + 16)
    for (ch in input) {
      when {
        ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~' -> out.append(ch)
        ch == '!' || ch == '\'' || ch == '(' || ch == ')' || ch == '*' -> Unit
        else -> {
          for (b in ch.toString().encodeToByteArray()) out.append(String.format("%%%02X", b.toInt() and 0xFF))
        }
      }
    }
    return out.toString()
  }

  internal companion object {
    private const val NAV_TTL_MS = 6 * 60 * 60 * 1000L

    private const val DEFAULT_USER_AGENT =
      "Mozilla/5.0 (X11; Linux x86_64; rv:138.0) Gecko/20100101 Firefox/138.0"

    // 与服务端约定的混排表，改动会直接导致所有签名接口 403。
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
      46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
      27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
      37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
      22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
    )
  }
}
