package dtv.mobile.platform.huya
import dtv.mobile.platform.Env2

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.nio.charset.Charset

internal data class HuyaWsInfo(
  val wsUrl: String,
  val registerPayload: ByteArray,
  val ayyuid: String,
)

internal data class HuyaChatDecoded(
  val user: String,
  val content: String,
)

/**
 * Huya danmaku algorithm ported 1:1 from `huya-danmaku-kotlin` (DTV-heroui aligned).
 */
internal object HuyaDtvHerouiAlgo {
  private const val WS_URL = Env2.DANMU_WS

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  // resolveAyyuid 里逐条内联的 Regex：进房/回退都会走一遍，其中两条还带 [\s\S]*? 要扫整页 HTML，
  // 每次都重新编译纯属浪费。提到 object 里预编译（与 BilibiliLiveListApiAndroid.accessIdRegex 同款做法）。
  private val TT_PROFILE_INFO_REGEX = Regex("""var\s+TT_PROFILE_INFO\s*=\s*(\{[\s\S]*?\});""")
  private val LP_ESCAPED_REGEX = Regex("""\\\"lp\\\"\s*:\s*\\\"?(\d+)\\\"?""")
  private val AYYUID_ESCAPED_REGEX = Regex("""\\\"ayyuid\\\"\s*:\s*\\\"?(\d+)\\\"?""")
  private val YYUID_ESCAPED_REGEX = Regex("""\\\"yyuid\\\"\s*:\s*\\\"?(\d+)\\\"?""")

  suspend fun fetchWsInfo(client: OkHttpClient, roomIdOrUrl: String): HuyaWsInfo {
    val rid = roomIdOrUrl.toRoomId()
    val page = fetchText(
      client,
      "${Env2.HOST}/$rid",
      headers = mapOf(
        "User-Agent" to genUa(),
        "Referer" to (Env2.HOST + "/"),
      ),
    )

    val ayyuid = resolveAyyuid(client, rid, page)
    val topics = listOf("live:$ayyuid", "chat:$ayyuid")

    val inner = Tars.Output()
      .writeStringList(0, topics)
      .writeString(1, "")
      .toByteArray()

    val reg = Tars.Output()
      .writeInt32(0, 16)
      .writeBytes(1, inner)
      .toByteArray()

    return HuyaWsInfo(wsUrl = WS_URL, registerPayload = reg, ayyuid = ayyuid)
  }

  fun decodeChat(data: ByteArray): HuyaChatDecoded? {
    val ios = Tars.Input(data)
    val top = runCatching { ios.readInt32(0, required = false, defaultValue = -1) }.getOrDefault(-1)
    if (top != 7) return null

    val b1 = runCatching { ios.readBytes(1, required = false, defaultValue = ByteArray(0)) }.getOrDefault(ByteArray(0))
    if (b1.isEmpty()) return null

    val inner = Tars.Input(b1)
    val nested = runCatching { inner.readInt32(1, required = false, defaultValue = -1) }.getOrDefault(-1)
    val b2 = runCatching { inner.readBytes(2, required = false, defaultValue = ByteArray(0)) }.getOrDefault(ByteArray(0))
    if (nested != 1400 || b2.isEmpty()) return null

    val user = runCatching {
      val p = Tars.Input(b2)
      p.readStruct(0, required = false) { sr ->
        val nameBytes = sr.readStringBytes(2, defaultValue = ByteArray(0))
        decodeBestEffort(nameBytes)
      } ?: ""
    }.getOrDefault("")

    val text = runCatching {
      val p = Tars.Input(b2)
      val bytes = p.readStringBytes(3, required = false, defaultValue = ByteArray(0))
      decodeBestEffort(bytes)
    }.getOrDefault("")

    if (text.isBlank()) return null
    val nick = if (user.isNotBlank()) user else "匿名"
    return HuyaChatDecoded(user = nick, content = text)
  }

  fun peekCmds(data: ByteArray): Pair<Int?, Int?> {
    val ios = Tars.Input(data)
    val top = ios.readInt32(0, required = false, defaultValue = -1)
    val b1 = ios.readBytes(1, required = false, defaultValue = ByteArray(0))
    val nested = if (b1.isNotEmpty()) {
      val inner = Tars.Input(b1)
      inner.readInt32(1, required = false, defaultValue = -1)
    } else {
      null
    }
    return top to nested
  }

  private fun decodeBestEffort(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val utf8 = bytes.toString(Charsets.UTF_8)
    if (!utf8.contains('\uFFFD')) return utf8
    val gbk = bytes.toString(Charset.forName("GBK"))
    val utf8Bad = utf8.count { it == '\uFFFD' }
    val gbkBad = gbk.count { it == '\uFFFD' }
    return if (gbkBad < utf8Bad) gbk else utf8
  }

  private suspend fun resolveAyyuid(client: OkHttpClient, rid: String, page: String): String {
    TT_PROFILE_INFO_REGEX
      .find(page)
      ?.groupValues
      ?.getOrNull(1)
      ?.let { raw ->
        runCatching {
          val j = json.parseToJsonElement(raw)
          (j as? JsonObject)?.get("lp")?.asString()?.takeIf { it.isNotBlank() }
        }.getOrNull()
      }
      ?.let { return it }

    LP_ESCAPED_REGEX
      .find(page)
      ?.groupValues
      ?.getOrNull(1)
      ?.takeIf { it.isNotBlank() }
      ?.let { return it }

    AYYUID_ESCAPED_REGEX.find(page)?.groupValues?.getOrNull(1)?.let { return it }
    YYUID_ESCAPED_REGEX.find(page)?.groupValues?.getOrNull(1)?.let { return it }

    val api = "${Env2.PROFILE_ROOM}$rid"
    val body = fetchText(client, api, headers = mapOf("User-Agent" to genUa()))
    runCatching {
      val j = json.parseToJsonElement(body)
      findUidInJson(j)
    }.getOrNull()?.let { return it }

    return rid
  }

  private fun findUidInJson(v: JsonElement): String? {
    return when (v) {
      is JsonObject -> {
        for ((k, value) in v) {
          val key = k.lowercase()
          if (key == "ayyuid" || key == "yyuid" || key == "lp" || key == "uid") {
            value.asString()?.takeIf { it.isNotBlank() }?.let { return it }
          }
          findUidInJson(value)?.let { return it }
        }
        null
      }
      is JsonArray -> v.firstNotNullOfOrNull { findUidInJson(it) }
      else -> null
    }
  }

  private fun JsonElement.asString(): String? = when (this) {
    is JsonPrimitive -> if (isString) content else content
    else -> null
  }

  private fun genUa(): String =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

  private suspend fun fetchText(client: OkHttpClient, url: String, headers: Map<String, String> = emptyMap()): String {
    return withContext(Dispatchers.IO) {
      val reqBuilder = Request.Builder().url(url)
      headers.forEach { (k, v) -> reqBuilder.header(k, v) }
      client.newCall(reqBuilder.build()).execute().use { resp ->
        if (!resp.isSuccessful) error("HTTP ${resp.code} for $url")
        resp.body?.string() ?: ""
      }
    }
  }

  private fun String.toRoomId(): String {
    if (startsWith("http", ignoreCase = true)) {
      val u = URI(this)
      val p = u.path.trim('/').split('/').lastOrNull().orEmpty()
      if (p.isNotBlank()) return p
    }
    return this.trim().trim('/').ifBlank { error("Invalid room id/url: $this") }
  }
}

