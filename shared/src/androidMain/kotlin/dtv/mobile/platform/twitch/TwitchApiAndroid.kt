package dtv.mobile.platform.twitch

import dtv.mobile.model.Platform
import dtv.mobile.model.Streamer
import dtv.mobile.net.createHttpClient
import dtv.mobile.platform.Env6
import dtv.mobile.repo.TwitchCate
import dtv.mobile.repo.TwitchPage
import dtv.mobile.repo.TwitchPlayInfo
import dtv.mobile.repo.TwitchVariant
import dtv.mobile.util.AppLog
import dtv.mobile.util.formatViewerCountWanIfNeeded
import dtv.mobile.util.normalizeHttpUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val TAG = "DTV-Twitch"

/** usher master m3u8 里的画质代号 -> 展示名 */
private fun variantDisplayName(raw: String): String = when (raw.lowercase()) {
  "chunked" -> "原画"
  "audio_only" -> "仅音频"
  else -> raw.uppercase()
}

class TwitchApiAndroid(
  private val client: HttpClient = createHttpClient(),
) {
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  // GQL 完整性头：匿名请求带一个稳定的设备随机串即可，进程内保持不变
  private val deviceId: String by lazy { UUID.randomUUID().toString().replace("-", "") }

  private suspend fun gql(query: String, variables: JsonObject = buildJsonObject {}): JsonObject? {
    return runCatching {
      val body = buildJsonObject {
        put("query", JsonPrimitive(query))
        put("variables", variables)
      }.toString()
      val resp = client.post(Env6.GQL) {
        header("Client-ID", Env6.CLIENT_ID)
        header("X-Device-Id", deviceId)
        contentType(ContentType.Application.Json)
        setBody(body)
      }.bodyAsText()
      val root = json.parseToJsonElement(resp).jsonObject
      if (root.containsKey("errors")) {
        AppLog.w(TAG, "gql errors: ${root["errors"]?.toString()?.take(200)}")
        return@runCatching null
      }
      root["data"]?.jsonObject
    }.onFailure { AppLog.e(TAG, "gql request failed", it) }.getOrNull()
  }

  private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

  private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

  private fun nodeToStreamer(node: JsonObject): Streamer? {
    val broadcaster = node.obj("broadcaster") ?: return null
    val login = broadcaster.str("login")?.trim().orEmpty()
    if (login.isEmpty()) return null
    val display = broadcaster.str("displayName")?.trim()?.ifBlank { null } ?: login
    val title = node.str("title")?.trim().orEmpty()
    val gameName = node.obj("game")?.str("displayName")
    val viewers = node.str("viewersCount")?.toLongOrNull() ?: 0L
    return Streamer(
      platform = Platform.Twitch,
      roomId = login,
      name = display,
      title = title.ifBlank { gameName?.let { "正在直播 $it" } ?: "直播中" },
      viewerText = formatViewerCountWanIfNeeded(viewers.toString()),
      avatarUrl = broadcaster.str("profileImageURL")?.let(::normalizeHttpUrl),
      coverUrl = node.str("previewImageURL")?.let(::normalizeHttpUrl),
      isLive = true,
    )
  }

  private val streamsQuery = """
    query(${"$"}first:Int,${"$"}cursor:Cursor){
      streams(first:${"$"}first,after:${"$"}cursor){
        edges{ node{ id title viewersCount previewImageURL(width:320,height:180)
          game{ displayName slug }
          broadcaster{ login displayName profileImageURL(width:70) } } }
        pageInfo{ hasNextPage }
      }
    }
  """.trimIndent()

  private val gameStreamsQuery = """
    query(${"$"}slug:String!,${"$"}first:Int,${"$"}cursor:Cursor){
      game(slug:${"$"}slug){
        id displayName
        streams(first:${"$"}first,after:${"$"}cursor){
          edges{ node{ id title viewersCount previewImageURL(width:320,height:180)
            game{ displayName slug }
            broadcaster{ login displayName profileImageURL(width:70) } } }
          pageInfo{ hasNextPage }
        }
      }
    }
  """.trimIndent()

  private fun parseStreams(data: JsonObject?): TwitchPage {
    val conn = data?.obj("streams")
      ?: data?.obj("game")?.obj("streams")
      ?: return TwitchPage(emptyList(), null, false)
    val edges = (conn["edges"] as? kotlinx.serialization.json.JsonArray).orEmpty()
    val items = edges.mapNotNull { e ->
      (e as? JsonObject)?.obj("node")?.let(::nodeToStreamer)
    }
    val cursor = edges.lastOrNull()
      ?.let { (it as? JsonObject)?.str("cursor") }
    val hasNext = conn.obj("pageInfo")?.str("hasNextPage") == "true"
    return TwitchPage(items = items, cursor = cursor, hasMore = hasNext && items.isNotEmpty())
  }

  suspend fun fetchTopStreams(cursor: String?, first: Int = 30): TwitchPage {
    val data = gql(streamsQuery, buildJsonObject {
      put("first", first)
      put("cursor", cursor?.let { JsonPrimitive(it) } ?: JsonNull)
    })
    return parseStreams(data)
  }

  suspend fun fetchGameStreams(slug: String, cursor: String?, first: Int = 30): TwitchPage {
    val data = gql(gameStreamsQuery, buildJsonObject {
      put("slug", slug)
      put("first", first)
      put("cursor", cursor?.let { JsonPrimitive(it) } ?: JsonNull)
    })
    return parseStreams(data)
  }

  suspend fun fetchCategories(first: Int = 40): List<TwitchCate> {
    val data = gql("""
      query(${"$"}first:Int){
        games(first:${"$"}first){ edges{ node{ slug displayName } } }
      }
    """.trimIndent(), buildJsonObject { put("first", first) })
      ?: return emptyList()
    val edges = (data.obj("games")?.get("edges") as? kotlinx.serialization.json.JsonArray).orEmpty()
    return edges.mapNotNull { e ->
      val node = (e as? JsonObject)?.obj("node") ?: return@mapNotNull null
      val slug = node.str("slug")?.trim().orEmpty()
      val name = node.str("displayName")?.trim().orEmpty()
      if (slug.isEmpty() || name.isEmpty()) null else TwitchCate(id = slug, name = name)
    }
  }

  suspend fun searchChannels(keyword: String): List<Streamer> {
    val data = gql("""
      query(${"$"}q:String!){
        searchFor(userQuery:${"$"}q, platform:"web"){
          channels{ items{ login displayName profileImageURL(width:70)
            stream{ id title viewersCount previewImageURL(width:320,height:180) game{ displayName } } } }
        }
      }
    """.trimIndent(), buildJsonObject { put("q", keyword) })
      ?: return emptyList()
    val items = (data.obj("searchFor")?.obj("channels")?.get("items") as? kotlinx.serialization.json.JsonArray).orEmpty()
    return items.mapNotNull { e ->
      val obj = e as? JsonObject ?: return@mapNotNull null
      val login = obj.str("login")?.trim().orEmpty()
      if (login.isEmpty()) return@mapNotNull null
      val stream = obj.obj("stream")
      Streamer(
        platform = Platform.Twitch,
        roomId = login,
        name = obj.str("displayName")?.trim()?.ifBlank { null } ?: login,
        title = stream?.str("title")?.trim().orEmpty().ifBlank { "未开播" },
        viewerText = stream?.str("viewersCount")?.let { formatViewerCountWanIfNeeded(it) }.orEmpty(),
        avatarUrl = obj.str("profileImageURL")?.let(::normalizeHttpUrl),
        coverUrl = stream?.str("previewImageURL")?.let(::normalizeHttpUrl),
        isLive = stream != null,
      )
    }
  }

  /** 关注卡片：单频道元数据 + 开播状态。未开播时 stream 为 null。 */
  suspend fun fetchUserSnapshot(login: String): Streamer? {
    val data = gql("""
      query(${"$"}l:String!){
        user(login:${"$"}l){
          login displayName profileImageURL(width:70)
          stream{ id title viewersCount previewImageURL(width:320,height:180) game{ displayName } }
        }
      }
    """.trimIndent(), buildJsonObject { put("l", login) })
      ?: return null
    val user = data.obj("user") ?: return null
    val id = user.str("login")?.trim().orEmpty().ifEmpty { login }
    val stream = user.obj("stream")
    return Streamer(
      platform = Platform.Twitch,
      roomId = id,
      name = user.str("displayName")?.trim()?.ifBlank { null } ?: id,
      title = stream?.str("title")?.trim().orEmpty().ifBlank { "未开播" },
      viewerText = stream?.str("viewersCount")?.let { formatViewerCountWanIfNeeded(it) }.orEmpty(),
      avatarUrl = user.str("profileImageURL")?.let(::normalizeHttpUrl),
      coverUrl = stream?.str("previewImageURL")?.let(::normalizeHttpUrl),
      isLive = stream != null,
    )
  }

  /** 匿名拿 playback token（ usher 凭据）。 */
  private suspend fun fetchPlaybackToken(login: String): Pair<String, String> {
    val body = """
      {"operationName":"PlaybackAccessToken","extensions":{"persistedQuery":{"version":1,
      "sha256Hash":"0828119ded1c13477966434e15800ff57ddacf13ba1911c129dc2200705b0712"}},
      "variables":{"isLive":true,"login":"$login","isVod":false,"vodID":"","playerType":"site","platform":"web"}}
    """.trimIndent().replace("\n", "")
    val resp = client.post(Env6.GQL) {
      header("Client-ID", Env6.CLIENT_ID)
      header("X-Device-Id", deviceId)
      contentType(ContentType.Application.Json)
      setBody(body)
    }.bodyAsText()
    val root = json.parseToJsonElement(resp).jsonObject
    val sa = root.obj("data")?.obj("streamPlaybackAccessToken")
      ?: error("未获取到 Twitch 播放凭据")
    val token = sa.str("value") ?: error("Twitch 播放凭据缺失")
    val sig = sa.str("signature") ?: error("Twitch 播放凭据缺失")
    return token to sig
  }

  /** 拉 master m3u8 并解析全部画质变体（含仅音频）。 */
  suspend fun fetchPlayInfo(login: String): TwitchPlayInfo {
    val masterUrl = buildUsherUrl(login)
    val text = runCatching {
      client.get(masterUrl) { header("Referer", Env6.HOST + "/") }.bodyAsText()
    }.getOrElse { e ->
      AppLog.e(TAG, "fetch master m3u8 failed login=$login", e)
      error("获取 Twitch 画质列表失败")
    }
    val variants = parseMasterPlaylist(text)
    if (variants.isEmpty()) error("该频道未返回可用画质")
    return TwitchPlayInfo(variants = variants)
  }

  /**
   * 解析播放地址。
   * quality == null 时返回 master m3u8（ExoPlayer 自适应码率）；
   * 否则匹配 master 里的对应变体直链。
   */
  suspend fun resolveStreamUrl(login: String, quality: String? = null): String {
    if (quality.isNullOrBlank()) return buildUsherUrl(login)
    val masterUrl = buildUsherUrl(login)
    val text = runCatching {
      client.get(masterUrl) { header("Referer", Env6.HOST + "/") }.bodyAsText()
    }.getOrElse { e ->
      AppLog.e(TAG, "fetch master m3u8 failed login=$login", e)
      error("获取 Twitch 播放地址失败")
    }
    val variants = parseMasterPlaylist(text)
    val matched = variants.firstOrNull { it.name.equals(quality, ignoreCase = true) }
      ?: variants.firstOrNull()
      ?: error("该频道未返回可用画质")
    return matched.url
  }

  private suspend fun buildUsherUrl(login: String): String {
    val (token, sig) = fetchPlaybackToken(login)
    return "${Env6.USHER}$login.m3u8" +
      "?token=${token.encodeURLParameter()}&sig=$sig" +
      "&allow_source=true&allow_audio_only=true&platform=web&player=site"
  }

  private fun parseMasterPlaylist(text: String): List<TwitchVariant> {
    val out = ArrayList<TwitchVariant>(8)
    var pendingVideo: String? = null
    text.lineSequence().forEach { raw ->
      val line = raw.trim()
      if (line.isEmpty()) return@forEach
      if (line.startsWith("#EXT-X-STREAM-INF")) {
        pendingVideo = Regex("VIDEO=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
      } else if (!line.startsWith("#")) {
        val video = pendingVideo
        pendingVideo = null
        if (video != null) {
          out.add(TwitchVariant(name = video, display = variantDisplayName(video), url = line))
        }
      }
    }
    // 原画排前、仅音频垫底，其余按分辨率高度降序
    fun sortKey(v: TwitchVariant): Int = when (v.name.lowercase()) {
      "chunked" -> 0
      "audio_only" -> 99
      else -> {
        val height = Regex("(\\d+)p(\\d+)").find(v.name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        (1000 - height).coerceIn(1, 98)
      }
    }
    return out.sortedBy(::sortKey).distinctBy { it.name }
  }
}
