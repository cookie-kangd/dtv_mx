package dtv.mobile.platform.twitch

import dtv.mobile.model.Platform
import dtv.mobile.model.Streamer
import dtv.mobile.net.createHttpClient
import dtv.mobile.platform.Env6
import dtv.mobile.repo.TwitchCate
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private const val TAG = "DTV-Twitch"

/** Twitch GQL 本地化语言：让分类名与网页版中文界面一致（Just Chatting → 谈天说地）。 */
private const val ACCEPT_LANGUAGE = "zh-CN"

// master m3u8 解析用正则：提前编译为常量，避免每次解析都重复编译（解析在切画质时触发）
private val VIDEO_ATTR_REGEX = Regex("VIDEO=\"([^\"]+)\"")
private val VARIANT_HEIGHT_REGEX = Regex("(\\d+)p(\\d+)")

/**
 * Twitch 语言过滤。
 *
 * ⚠️ 踩坑记录：`streams(languages: [ZH])` 这种**平铺参数**会被服务端静默忽略——
 * 传与不传返回的东西一模一样（实测仍是 EN/ES/RU 榜单），看起来"参数生效了"其实没有。
 * 真正生效的写法是把语言塞进 `options` 对象：`streams(options: {languages: [ZH]})`，
 * 此时返回的每条 node.language 都是 ZH。
 *
 * 另外 Language 枚举里中文只有 `ZH`（没有 ZH-CN / ZH-TW / ZH-HK，传了会直接报
 * "Value does not exist in Language enum"）。
 *
 * 注意 `Query.streams` 与 `Game.streams` 的 options 是**两个不同类型**
 * （StreamOptions / GameStreamOptions），GraphQL 变量必须声明对应类型，
 * 否则报 "used in position expecting type GameStreamOptions"。
 */
private val ZH_ONLY_OPTIONS: JsonObject = buildJsonObject {
  put("languages", JsonArray(listOf(JsonPrimitive("ZH"))))
}

/** 不加任何过滤（全语言），比传 null 更省得踩「可空输入类型」的坑。 */
private val NO_OPTIONS: JsonObject = buildJsonObject { }

/**
 * 「推荐」聚合用的补充分类：中文观众最聚集的板块（谈天说地 = Just Chatting、IRL）。
 * 网页版推荐流里这类内容占比很高，单靠人气总榜会把它们挤掉，故并进来一起按人气重排。
 */
private val RECOMMEND_EXTRA_SLUGS = listOf("just-chatting", "irl")

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
        // Twitch GQL 按 Accept-Language 返回本地化文案：带上中文后
        // 分类名会变成网页版那套中文（Just Chatting → 谈天说地、IRL → IRL），
        // 不带则一律英文，与网页版体验不一致。
        header("Accept-Language", ACCEPT_LANGUAGE)
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
      viewerCount = viewers,
    )
  }

  // ⚠️ 不要再加 `after:$cursor`：匿名 Client-ID 带游标翻页会被服务端
  // "failed integrity check" 直接拒绝（IntegrityCheckFailed），第二页永远拿不到。
  // 且 first 上限被限制在 30（>30 报 "argument 'first' value must be between 1 and 30"）。
  // 因此 Twitch 列表按「单页 30 条」处理，「推荐」用多来源聚合来补足内容量。
  private val streamsQuery = """
    query(${"$"}first:Int,${"$"}options:StreamOptions){
      streams(first:${"$"}first,options:${"$"}options){
        edges{ node{ id title language viewersCount previewImageURL(width:320,height:180)
          game{ displayName slug }
          broadcaster{ login displayName profileImageURL(width:70) } } }
      }
    }
  """.trimIndent()

  private val gameStreamsQuery = """
    query(${"$"}slug:String!,${"$"}first:Int,${"$"}options:GameStreamOptions){
      game(slug:${"$"}slug){
        id displayName
        streams(first:${"$"}first,options:${"$"}options){
          edges{ node{ id title language viewersCount previewImageURL(width:320,height:180)
            game{ displayName slug }
            broadcaster{ login displayName profileImageURL(width:70) } } }
        }
      }
    }
  """.trimIndent()

  private fun parseStreams(data: JsonObject?, zhOnly: Boolean = false): List<Streamer> {
    val conn = data?.obj("streams")
      ?: data?.obj("game")?.obj("streams")
      ?: return emptyList()
    val edges = (conn["edges"] as? JsonArray).orEmpty()
    return edges.mapNotNull { e ->
      val node = (e as? JsonObject)?.obj("node") ?: return@mapNotNull null
      // 服务端按 options.languages 过滤偶有漏网（实测中文「谈天说地」里会混进 1 条 EN），
      // 客户端按 node.language 再兜一层，保证「只看中文」时列表是干净的。
      if (zhOnly && node.str("language") != "ZH") return@mapNotNull null
      nodeToStreamer(node)
    }
  }

  /**
   * 人气总榜。
   * @param zhOnly true 时只返回中文（ZH）频道，与网页版中文推荐一致。
   */
  suspend fun fetchTopStreams(first: Int = 30, zhOnly: Boolean = false): List<Streamer> {
    val data = gql(streamsQuery, buildJsonObject {
      put("first", first)
      put("options", if (zhOnly) ZH_ONLY_OPTIONS else NO_OPTIONS)
    })
    return parseStreams(data, zhOnly = zhOnly)
  }

  /**
   * 单个分类下的直播列表。
   *
   * 默认**不做语言过滤**：用户点进某个具体游戏时想看的是这个游戏最好看的内容，
   * 强行只留中文会经常出现空列表（很多分类里没有中文主播）；网页版分类页默认也是全语言。
   * @param zhOnly 仅供「推荐」聚合内部使用，传 true 只取中文频道。
   */
  suspend fun fetchGameStreams(slug: String, first: Int = 30, zhOnly: Boolean = false): List<Streamer> {
    val data = gql(gameStreamsQuery, buildJsonObject {
      put("slug", slug)
      put("first", first)
      put("options", if (zhOnly) ZH_ONLY_OPTIONS else NO_OPTIONS)
    })
    return parseStreams(data, zhOnly = zhOnly)
  }

  /**
   * 「推荐」列表。
   *
   * zhOnly = true 时把「中文人气总榜 + 中文谈天说地 + 中文IRL」三路并发拉取后
   * 去重、按观众数重排。原因：匿名接口单页最多 30 条且游标翻页被拒，
   * 单路只有 30 条、内容很单薄；三路合并后最多 90 条，且天然带上了
   * 中文观众最常看的谈天说地/IRL 内容，观感更接近网页版推荐流。
   */
  suspend fun fetchRecommendedStreams(first: Int = 30, zhOnly: Boolean = true): List<Streamer> {
    if (!zhOnly) return fetchTopStreams(first = first, zhOnly = false)
    val merged = coroutineScope {
      val topDeferred = async { runCatching { fetchTopStreams(first = first, zhOnly = true) }.getOrDefault(emptyList()) }
      val extraDeferred = RECOMMEND_EXTRA_SLUGS.map { slug ->
        async { runCatching { fetchGameStreams(slug = slug, first = first, zhOnly = true) }.getOrDefault(emptyList()) }
      }
      topDeferred.await() + extraDeferred.flatMap { it.await() }
    }
    val seen = HashSet<String>(merged.size)
    return merged
      .filter { it.roomId.isNotEmpty() && seen.add(it.roomId) }
      .sortedByDescending { it.viewerCount ?: 0L }
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
    val trimmed = keyword.trim()
    if (trimmed.isEmpty()) return emptyList()
    // searchFor 固定只返回 10 条按相关度排序的结果，搜具体频道名时经常排不进去；
    // 补一发 user(login) 精确直查（login 一般就是频道名小写），命中则置顶合并。
    // 两个 GQL 请求并发执行（此前写法 async 后立即 await 实为串行，白丢一个往返时延）。
    val lowered = trimmed.lowercase()
    val (exact, data) = coroutineScope {
      val exactDeferred = async { runCatching { fetchUserSnapshot(lowered) }.getOrNull() }
      val dataDeferred = async {
        gql("""
          query(${"$"}q:String!){
            searchFor(userQuery:${"$"}q, platform:"web"){
              channels{ items{ login displayName profileImageURL(width:70)
                stream{ id title viewersCount previewImageURL(width:320,height:180) game{ displayName } } } }
            }
          }
        """.trimIndent(), buildJsonObject { put("q", trimmed) })
      }
      exactDeferred.await() to dataDeferred.await()
    }
    if (data == null) return listOfNotNull(exact)
    val items = (data.obj("searchFor")?.obj("channels")?.get("items") as? kotlinx.serialization.json.JsonArray).orEmpty()
    val list = items.mapNotNull { e ->
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
    if (exact != null && list.none { it.roomId.equals(exact.roomId, ignoreCase = true) }) {
      return listOf(exact) + list
    }
    return list
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
    // buildUsherUrl 内部要先请求一次播放凭据，属于网络调用，
    // 必须一起纳入 runCatching，否则凭据失败会抛出原始异常、绕过下面的统一文案。
    val text = runCatching {
      client.get(buildUsherUrl(login)) { header("Referer", Env6.HOST + "/") }.bodyAsText()
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
    val masterUrl = runCatching { buildUsherUrl(login) }.getOrElse { e ->
      AppLog.e(TAG, "fetch playback token failed login=$login", e)
      error("获取 Twitch 播放地址失败")
    }
    if (quality.isNullOrBlank()) return masterUrl
    val text = runCatching {
      client.get(masterUrl) { header("Referer", Env6.HOST + "/") }.bodyAsText()
    }.getOrElse { e ->
      AppLog.e(TAG, "fetch master m3u8 failed login=$login", e)
      error("获取 Twitch 播放地址失败")
    }
    val variants = parseMasterPlaylist(text)
    val matched = variants.firstOrNull { it.name.equals(quality, ignoreCase = true) }
      ?: variants.firstOrNull()?.also {
        // 主播中途换档/换设备时旧档位会消失，这里回落到最高档并留一条日志，
        // 免得用户以为「选了 720P 却播了别的档」而找不到原因。
        AppLog.w(TAG, "quality $quality missing for $login, fallback to ${it.name}")
      }
      ?: error("该频道未返回可用画质")
    return matched.url
  }

  private suspend fun buildUsherUrl(login: String): String {
    val (token, sig) = fetchPlaybackToken(login)
    return "${Env6.USHER}$login.m3u8" +
      "?token=${token.encodeURLParameter()}&sig=${sig.encodeURLParameter()}" +
      "&allow_source=true&allow_audio_only=true&platform=web&player=site"
  }

  private fun parseMasterPlaylist(text: String): List<TwitchVariant> {
    val out = ArrayList<TwitchVariant>(8)
    var pendingVideo: String? = null
    text.lineSequence().forEach { raw ->
      val line = raw.trim()
      if (line.isEmpty()) return@forEach
      if (line.startsWith("#EXT-X-STREAM-INF")) {
        pendingVideo = VIDEO_ATTR_REGEX.find(line)?.groupValues?.get(1)
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
        val height = VARIANT_HEIGHT_REGEX.find(v.name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        (1000 - height).coerceIn(1, 98)
      }
    }
    return out.sortedBy(::sortKey).distinctBy { it.name }
  }
}
