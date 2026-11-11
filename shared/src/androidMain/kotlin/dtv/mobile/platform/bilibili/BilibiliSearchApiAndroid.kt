package dtv.mobile.platform.bilibili
import dtv.mobile.platform.Env4

import dtv.mobile.model.Platform
import dtv.mobile.model.Streamer
import dtv.mobile.util.AppLog
import dtv.mobile.util.normalizeHttpUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

class BilibiliSearchApiAndroid(
  private val client: HttpClient,
  private val cookieStore: BilibiliCookieStoreAndroid,
  private val buvidProvider: BilibiliBuvidProvider,
) {
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }
  private val emTagPattern = Regex("(?i)</?em[^>]*>")

  // 直播搜索同样要求 WBI 签名，与弹幕共用同一套 signer 实现（各自一份 nav 缓存）。
  private val wbi = BilibiliWbiSigner(client) { cookieStore.getCookie() }

  private fun JsonElement?.stringValueOrNull(): String? {
    val p = this as? kotlinx.serialization.json.JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content
  }

  private fun JsonElement?.longValueOrNull(): Long? {
    val p = this as? kotlinx.serialization.json.JsonPrimitive ?: return null
    return p.longOrNull ?: p.content.toLongOrNull()
  }

  private fun stripEmTags(input: String): String {
    return input.replace(emTagPattern, "")
  }

  private fun normalizeImage(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val full = if (trimmed.startsWith("http")) trimmed else "https:$trimmed"
    return if (full.contains('@')) full else "$full@400w.jpg"
  }

  private suspend fun getSearchText(url: String, cookieHeader: String): String =
    client.get(url) {
      headers {
        append("User-Agent", BilibiliBuvidProvider.USER_AGENT)
        append("Referer", Env4.LIVE_HOST + "/")
        if (cookieHeader.isNotBlank()) append("Cookie", cookieHeader)
      }
    }.bodyAsText()

  /**
   * 搜索直播房间。
   *
   * 签名说明：B 站 `x/web-interface/search/type` 对未签名请求返回 `-412`，
   * 因此这里必须走 WBI。签名串一旦被 ktor 的 `parameter()` 二次拼接就会失配，
   * 所以带签名时所有参数都拼在 URL 里，不再用 `parameter()`。
   * nav 取不到（离线/风控）时退回不带签名的旧路径，至少保留「可能成功」的机会。
   */
  suspend fun searchRooms(keyword: String, page: Int = 1): List<Streamer> {
    val trimmed = keyword.trim()
    if (trimmed.isEmpty()) return emptyList()

    // 取不到指纹就退回已持久化的 cookie：搜索本身还能靠 WBI 签名尝试一次。
    // CancellationException 必须重抛，不能当成「取指纹失败」吞掉。
    val cookieHeader = try {
      buvidProvider.ensureCookieHeader()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      cookieStore.getCookie().orEmpty()
    }

    val params = listOf(
      "context" to "",
      "search_type" to "live",
      "cover_type" to "user_cover",
      "order" to "",
      "keyword" to trimmed,
      "category_id" to "",
      "__refresh__" to "",
      "_extra" to "",
      "highlight" to "0",
      "single_column" to "0",
      "page" to page.toString(),
      "platform" to "web",
      "web_location" to "1430654",
    )
    val signed = try {
      wbi.signQuery(params)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    }
    val text = if (signed != null) {
      getSearchText("${Env4.SEARCH_TYPE}?$signed", cookieHeader)
    } else {
      val fallback = params.joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }
      getSearchText("${Env4.SEARCH_TYPE}?$fallback", cookieHeader)
    }

    val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
    val code = root["code"].longValueOrNull() ?: -1L
    if (code == -412L) {
      // 风控拦截：签名失效或 buvid 缺失。原来这里静默返回空列表，
      // 界面上与「真的没有搜索结果」无法区分，排查时只能靠抓包。
      AppLog.w("DTV-Bili", "search blocked (-412) keyword=$trimmed page=$page")
      return emptyList()
    }
    if (code != 0L) return emptyList()

    val liveUsers = root["data"]?.jsonObject
      ?.get("result")?.jsonObject
      ?.get("live_user")?.jsonArray
      ?: return emptyList()

    return liveUsers.mapNotNull { el ->
      val obj = el.jsonObject
      val roomId = obj["roomid"].longValueOrNull()?.toString().orEmpty()
      if (roomId.isBlank()) return@mapNotNull null

      val title = stripEmTags(obj["title"].stringValueOrNull().orEmpty()).trim().ifBlank { "暂无标题" }
      val name = stripEmTags(obj["uname"].stringValueOrNull().orEmpty()).trim().ifBlank { "B站主播" }
      val cover = normalizeHttpUrl(normalizeImage(obj["cover"].stringValueOrNull()))
      val avatar = normalizeHttpUrl(normalizeImage(obj["uface"].stringValueOrNull()))

      val watching = obj["online"]?.let { online ->
        when (online) {
          is kotlinx.serialization.json.JsonPrimitive -> online.content
          else -> ""
        }
      }.orEmpty()

      val isLive = (obj["live_status"].longValueOrNull() ?: 0L) == 1L

      Streamer(
        platform = Platform.Bilibili,
        roomId = roomId,
        name = name,
        title = title,
        viewerText = watching,
        avatarUrl = avatar,
        coverUrl = cover,
        isLive = isLive,
      )
    }
  }

  /**
   * 兜底路径的轻量 URL 编码：只保留 WBI 同款的非转义字符集合，
   * 保证中文关键词与空格不会破坏查询串。
   */
  private fun urlEncode(input: String): String {
    val out = StringBuilder(input.length + 8)
    for (ch in input) {
      when {
        ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~' -> out.append(ch)
        else -> for (b in ch.toString().encodeToByteArray()) out.append(String.format("%%%02X", b.toInt() and 0xFF))
      }
    }
    return out.toString()
  }
}
