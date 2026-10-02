package dtv.mobile.platform.douyin
import dtv.mobile.platform.Env3

import android.content.Context
import dtv.mobile.repo.DanmakuMessage
import dtv.mobile.util.AppLog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * 抖音弹幕（Android, 纯 Kotlin/OkHttp）：
 * - GET `https://live.douyin.com/<webRid>` 解析 `user_unique_id` / `room id_str` / Cookie
 * - 拼接 WebSocket 参数 + WebMsSDK `signature`
 * - OkHttp WebSocket 收包：解 PushFrame / gzip / ack / chat message
 *
 * 参考 `kotlin-danmaku-android` 的实现结构重写；并移除对 Rust/JNI 的桥接依赖。
 */
class DouyinDanmakuClientAndroid(
  appContext: Context,
  private val webApi: DouyinWebApiAndroid,
  private val http: OkHttpClient = defaultHttpClient(),
  private val wsClient: OkHttpClient = defaultWsClient(),
) {
  data class PrimedContext(
    val roomId: String,
    val msToken: String?,
  )

  private data class RoomInit(
    val roomId: String,
    val userUniqueId: String,
    val cookieHeader: String,
  )

  companion object {
    private const val TAG = "DTV-Douyin"

    /** 单条弹幕帧解压后的字节上限，超过即丢弃该帧（正常帧远小于 1MB）。 */
    private const val GZIP_MAX_BYTES = 8 * 1024 * 1024

    private const val USER_AGENT = DouyinWebApiAndroid.DEFAULT_USER_AGENT
    private const val REFERER_BASE = Env3.HOST

    private val WS_BASES = Env3.DANMU_WS_HOSTS

    // 与 kotlin-danmaku-android 保持一致的参数组合（移动端已验证可用）。
    private const val VERSION_CODE = "180800"
    private const val WEBCAST_SDK_VERSION = "1.3.0"
    private const val UPDATE_VERSION_CODE = "1.3.0"

    private const val HEARTBEAT_MS: Long = 10_000

    // 取弹幕参数用的正则预编译：extractUserUniqueId / extractRoomId 在每次建连都要跑一遍，
    // 现场 Regex(...) 等于每次重编译 4 个正则。（虎牙 HuyaDtvHerouiAlgo、Twitch 客户端同款做法）
    private val RE_UNIQUE_ID_ESCAPED = Regex("""\\\"user_unique_id\\\":\\\"(\d+)\\\"""")
    private val RE_UNIQUE_ID_PLAIN = Regex(""""user_unique_id"\s*:\s*"(\d+)"""")
    private val RE_ROOM_ID_ESCAPED = Regex("""\\\"roomInfo\\\":\{\\\"room\\\":\{\\\"id_str\\\":\\\"(\d+)\\\"""")
    private val RE_ROOM_ID_PLAIN = Regex(""""id_str"\s*:\s*"(\d+)"""")
    // 「最近观看房间上下文」缓存的条目上限，超出后丢弃最旧的若干条（见 prime()）。
    private const val PRIMED_MAX_ENTRIES = 32
    private val RANDOM = SecureRandom()

    private fun defaultHttpClient(): OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private fun defaultWsClient(): OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        // ping 必须开：弹幕 WS 的 readTimeout 是 0（永不超时），一旦网络切换 / 弱网丢包
        // 形成半开连接（对方不再回包、本端也不报错），onFailure 永远不会触发，
        // done.await() 挂死，弹幕从此静默且永不重连（虎牙客户端有看门狗可自愈，
        // 抖音这条路径原来没有任何兜底）。OkHttp 会周期性发 ping 帧，
        // 对端不回 pong 时主动以 onFailure 关闭连接，把控制权交回重连循环。
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
  }

  private val webMsdkSigner = DouyinWebMsdkSignatureAndroid(appContext)
  private val primed = ConcurrentHashMap<String, PrimedContext>()

  fun prime(webRid: String, roomId: String, msToken: String?) {
    val rid = webRid.trim()
    val rId = roomId.trim()
    if (rid.isBlank() || rId.isBlank()) return
    primed[rid] = PrimedContext(roomId = rId, msToken = msToken)
    // 这个缓存只用来记住最近观看房间的上下文，没有长期保留的必要；不设上限的话
    // 长期浏览大量抖音直播间会单调增长（单条不大，但属无界缓存）。
    // 超限时按迭代顺序丢弃最旧的若干条，被丢掉的房间下次进房会重新 prime。
    if (primed.size > PRIMED_MAX_ENTRIES) {
      val iterator = primed.keys.iterator()
      var excess = primed.size - PRIMED_MAX_ENTRIES
      while (excess > 0 && iterator.hasNext()) {
        iterator.next()
        iterator.remove()
        excess--
      }
    }
    AppLog.i(TAG, "prime douyin danmaku webRid=$rid roomId=$rId msToken=${!msToken.isNullOrBlank()}")
  }

  fun observe(webRid: String): Flow<DanmakuMessage> = callbackFlow {
    val rid = webRid.trim()
    if (rid.isBlank()) {
      close(IllegalArgumentException("webRid is blank"))
      return@callbackFlow
    }

    val wsRef = AtomicReference<WebSocket?>(null)
    val job = launch(Dispatchers.IO) {
      val hb = DouyinProtoLite.encodePushFrame(payloadType = "hb", logId = 0L, payload = ByteArray(0))
      var backoffMs = 1000L

      while (isActive) {
        val primedCtx = primed[rid]

        val init = runCatching { resolveRoomInit(webRid = rid, primedRoomId = primedCtx?.roomId) }
          .onFailure { AppLog.e(TAG, "resolve room init failed webRid=$rid", it) }
          .getOrNull()

        if (init == null) {
          delay(backoffMs)
          backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
          continue
        }

        var connectedAny = false
        val bases = WS_BASES.shuffled(RANDOM)
        for (base in bases) {
          if (!isActive) break
          val done = CompletableDeferred<Unit>()
          var connected = false
          val wsUrl = runCatching { buildWsUrl(base = base, init = init) }.getOrNull().orEmpty()
          if (wsUrl.isBlank()) continue

          AppLog.i(TAG, "douyin danmaku connecting webRid=$rid roomId=${init.roomId} uid=${init.userUniqueId.take(24)} base=$base")

          val req = Request.Builder()
            .url(wsUrl)
            .addHeader("accept", "application/json, text/plain, */*")
            .addHeader("accept-language", "zh-CN,zh;q=0.9,en;q=0.8")
            .addHeader("cache-control", "no-cache")
            .addHeader("pragma", "no-cache")
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Cookie", init.cookieHeader)
            .addHeader("Origin", REFERER_BASE)
            .addHeader("Referer", "$REFERER_BASE/$rid")
            .build()

          val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
              connected = true
              wsRef.set(webSocket)
              AppLog.i(TAG, "douyin danmaku ws opened webRid=$rid roomId=${init.roomId} base=$base")
              this@callbackFlow.launch(Dispatchers.IO) {
                while (isActive && !done.isCompleted) {
                  webSocket.send(hb.toByteString())
                  delay(HEARTBEAT_MS)
                }
              }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
              // 整条解码链必须包runCatching：DouyinProtoLite 的解包器对畸形帧是
              // error() 直接抛（protobuf eof / bad len= / unsupported wire=），
              // 而这里跑在 OkHttp 的回调线程上 —— 一抛出去没人接，
              // done 不会被 complete、异常也不会让重连循环重来，
              // 结果就是「连接还在、弹幕永远不再来」且没有任何日志线索。
              // 单帧解不开就丢掉这一帧，不影响后续（弹幕流允许丢包）。
              runCatching {
                val push = DouyinProtoLite.decodePushFrame(bytes.toByteArray()) ?: return@runCatching
                if (push.payloadType != "msg" || push.payload.isEmpty()) return@runCatching

                val decompressed = gunzipOrNull(push.payload) ?: return@runCatching
                val resp = DouyinProtoLite.decodeResponse(decompressed) ?: return@runCatching

                if (resp.needAck) {
                  val ack = DouyinProtoLite.encodePushFrame(
                    payloadType = "ack",
                    logId = push.logId,
                    payload = resp.internalExt.toByteArray(Charsets.UTF_8),
                  )
                  webSocket.send(ack.toByteString())
                }

                for (m in resp.messages) {
                  if (m.method != "WebcastChatMessage") continue
                  val chat = DouyinProtoLite.decodeChatMessage(m.payload) ?: continue
                  trySend(
                    DanmakuMessage(
                      roomId = rid,
                      user = chat.nick,
                      content = chat.content,
                      userLevel = chat.userLevel,
                      fansClubLevel = chat.fansClubLevel,
                    ),
                  )
                }
              }.onFailure { e ->
                AppLog.w(TAG, "douyin danmaku frame decode failed webRid=$rid bytes=${bytes.size}", e)
              }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
              AppLog.w(TAG, "douyin danmaku ws closing code=$code reason=$reason webRid=$rid")
              done.complete(Unit)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
              AppLog.w(TAG, "douyin danmaku ws closed code=$code reason=$reason webRid=$rid")
              done.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
              val respCode = response?.code
              val server = response?.header("server").orEmpty()
              val ct = response?.header("content-type").orEmpty()
              val location = response?.header("location").orEmpty()
              val peek = runCatching {
                response?.peekBody(2048)?.string()
                  ?.replace('\n', ' ')
                  ?.replace('\r', ' ')
                  ?.take(260)
                  .orEmpty()
              }.getOrDefault("")
              AppLog.e(
                TAG,
                "douyin danmaku ws failure webRid=$rid respCode=$respCode server=$server ct=$ct location=$location peek=$peek",
                t,
              )
              done.complete(Unit)
            }
          }

          val socket = wsClient.newWebSocket(req, listener)
          wsRef.set(socket)
          done.await()
          runCatching { wsRef.getAndSet(null)?.cancel() }

          if (connected) {
            connectedAny = true
            // 连上过就复位退避：否则经历几次断线后 backoffMs 会一路翻倍并永久停在 30s
            // （连接成功也不回落），此后哪怕网络早已恢复，每次重连都要白等半分钟，
            // 弹幕恢复极慢。Twitch / B站 客户端都是这个做法。
            backoffMs = 1000L
            break
          }
        }

        if (!isActive) break
        delay(backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
      }
    }

    awaitClose {
      runCatching { wsRef.getAndSet(null)?.cancel() }
      job.cancel()
    }
  }

  private suspend fun resolveRoomInit(webRid: String, primedRoomId: String?): RoomInit {
    val request = Request.Builder()
      .url("$REFERER_BASE/$webRid")
      .header("User-Agent", USER_AGENT)
      .header("Referer", REFERER_BASE)
      .header("Cookie", DouyinWebApiAndroid.DEFAULT_COOKIE)
      .build()

    http.newCall(request).execute().use { resp ->
      // Align with kotlin-danmaku-android: only take the first Set-Cookie pair.
      val cookieHeader = resp.headers("Set-Cookie")
        .firstOrNull()
        ?.substringBefore(";")
        ?.trim()
        .orEmpty()
        .ifBlank { DouyinWebApiAndroid.DEFAULT_COOKIE }

      val html = resp.body?.string().orEmpty()
      val rawUserUniqueId = extractUserUniqueId(html)
      val userUniqueId = rawUserUniqueId.ifBlank { randomDigits(12) }

      val roomIdFromHtml = extractRoomId(html)
      // 惰性求值：fetchRoomEnter 内部要算一次 ABogus 签名（5 次 SM3 + RC4）再解析整份
      // JSON。之前是无条件调用、再用 ifBlank 兜底 —— 即使 primedRoomId 或 HTML 里
      // 已经拿到房间号，这次带签名的请求也是白跑白算。改成按需调用。
      val roomId = primedRoomId?.trim().orEmpty().ifBlank { roomIdFromHtml }
        .ifBlank {
          runCatching { webApi.fetchRoomEnter(webRid).roomId?.trim().orEmpty() }.getOrDefault("")
        }
      if (roomId.isBlank()) error("Cannot resolve roomId for webRid=$webRid")

      AppLog.i(
        TAG,
        "resolveRoomInit webRid=$webRid http=${resp.code} setCookie=${resp.headers("Set-Cookie").size} " +
          "uidFromHtml=${rawUserUniqueId.isNotBlank()} roomIdFromHtml=${roomIdFromHtml.isNotBlank()} " +
          "roomIdFromPrimed=${primedRoomId?.isNotBlank() == true}",
      )
      return RoomInit(roomId = roomId, userUniqueId = userUniqueId, cookieHeader = cookieHeader)
    }
  }

  private suspend fun buildWsUrl(base: String, init: RoomInit): String {
    val ts = System.currentTimeMillis()
    val urlPrefix =
      base +
        listOf(
          "app_name=douyin_web",
          "version_code=$VERSION_CODE",
          "webcast_sdk_version=$WEBCAST_SDK_VERSION",
          "update_version_code=$UPDATE_VERSION_CODE",
          "compress=gzip",
          "cursor=${encode("h-1_t-${ts}_r-1_d-1_u-1")}",
          "host=${encode(Env3.HOST)}",
          "aid=6383",
          "live_id=1",
          "did_rule=3",
          "debug=false",
          "maxCacheMessageNumber=20",
          "endpoint=live_pc",
          "support_wrds=1",
          "im_path=${encode("/webcast/im/fetch/")}",
          "user_unique_id=${init.userUniqueId}",
          "device_platform=web",
          "cookie_enabled=true",
          "screen_width=1920",
          "screen_height=1080",
          "browser_language=zh-CN",
          "browser_platform=Win32",
          "browser_name=Mozilla",
          "browser_version=${encode(USER_AGENT.removePrefix("Mozilla/"))}",
          "browser_online=true",
          "tz_name=Asia/Shanghai",
          "identity=audience",
          "room_id=${init.roomId}",
          "heartbeatDuration=0",
        ).joinToString("&")

    val sig = runCatching {
      webMsdkSigner.signature(
        roomId = init.roomId,
        userUniqueId = init.userUniqueId,
        webcastSdkVersion = WEBCAST_SDK_VERSION,
        userAgent = USER_AGENT,
      )
    }.getOrNull().orEmpty()

    if (sig.isBlank()) return ""
    return "$urlPrefix&signature=${encode(sig)}"
  }

  private fun extractUserUniqueId(html: String): String {
    val patterns = listOf(
      // Matches kotlin-danmaku-android demo parsing (escaped JSON in HTML).
      RE_UNIQUE_ID_ESCAPED,
      // Unescaped JSON in HTML.
      RE_UNIQUE_ID_PLAIN,
    )
    for (re in patterns) {
      val m = re.find(html) ?: continue
      val id = m.groupValues.getOrNull(1).orEmpty()
      if (!id.isNullOrBlank()) return id.trim()
    }
    return ""
  }

  private fun extractRoomId(html: String): String {
    val patterns = listOf(
      // Escaped JSON in HTML.
      RE_ROOM_ID_ESCAPED,
      // Unescaped JSON in HTML.
      RE_ROOM_ID_PLAIN,
    )
    for (re in patterns) {
      val m = re.find(html) ?: continue
      val id = m.groupValues.getOrNull(1).orEmpty()
      if (!id.isNullOrBlank()) return id.trim()
    }
    return ""
  }

  private fun gunzipOrNull(data: ByteArray): ByteArray? {
    if (data.isEmpty()) return null
    return runCatching {
      // 不用 readBytes()：它对解压后长度没有任何上限，一路读到 EOF。
      // 压缩比 10:1 意味着 1MB 的压缩帧能展开成 10MB+，异常帧理论上可到 GB 级 ——
      // 这是全项目唯一一处无上限的内存分配，一个包就足以 OOM。
      // 改成边解压边计数，超阈值立刻停止并按失败处理（弹幕丢一帧无感）。
      val out = ByteArrayOutputStream(minOf(data.size * 4, GZIP_MAX_BYTES))
      val buf = ByteArray(8 * 1024)
      GZIPInputStream(ByteArrayInputStream(data)).use { gzip ->
        var total = 0
        while (true) {
          val n = gzip.read(buf)
          if (n < 0) break
          total += n
          if (total > GZIP_MAX_BYTES) return null
          out.write(buf, 0, n)
        }
      }
      out.toByteArray()
    }.getOrNull()
  }

  private fun randomDigits(n: Int): String {
    val sb = StringBuilder(n)
    repeat(n) { sb.append(('0'.code + RANDOM.nextInt(10)).toChar()) }
    return sb.toString()
  }

  private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

  // cookie merge removed: follow kotlin-danmaku-android behavior.
}
