package dtv.mobile.platform.douyu
import dtv.mobile.platform.Env1

import dtv.mobile.repo.DanmakuMessage
import dtv.mobile.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.min

class DouyuDanmakuClientAndroid(
  // 弹幕 WS 专用客户端：readTimeout 关掉（长连接常态就是长时间没数据），并发 ping 做半开检测。
  // 不开 ping 时，网络切换 / 弱网丢包形成的半开连接不会触发 onFailure，斗鱼自带的 app 层心跳
  // send() 也照样返回 true，`done.await()` 会永久挂起 —— 弹幕从此静默且永不重连。
  // （与抖音/虎牙客户端同款配置）
  private val okHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .writeTimeout(10, TimeUnit.SECONDS)
    .pingInterval(20, TimeUnit.SECONDS)
    .build(),
) {
  fun observe(roomId: String): Flow<DanmakuMessage> = callbackFlow {
    fun encode(msg: String): ByteString {
      val msgBytes = msg.toByteArray(Charsets.UTF_8)
      val packetLen = msgBytes.size + 9
      val buf = ByteBuffer.allocate(4 + 4 + 2 + 1 + 1 + msgBytes.size + 1)
      buf.order(ByteOrder.LITTLE_ENDIAN)
      buf.putInt(packetLen)
      buf.putInt(packetLen)
      buf.putShort(689.toShort())
      buf.put(0)
      buf.put(0)
      buf.put(msgBytes)
      buf.put(0)
      return ByteString.of(*buf.array())
    }

    fun decodeDouyuEscapes(value: String): String {
      return value.replace("@S", "/").replace("@A", "@")
    }

    fun parseMap(content: String): Map<String, String> {
      val map = HashMap<String, String>(16)
      content.split('/').forEach { item ->
        if (item.isBlank()) return@forEach
        val idx = item.indexOf("@=")
        if (idx <= 0) return@forEach
        val k = item.substring(0, idx)
        val v = item.substring(idx + 2)
        map[k] = decodeDouyuEscapes(v)
      }
      return map
    }

    // Reconnect loop with backoff (matches desktop behavior)
    launch {
      var backoff = 1000L
      while (isActive) {
        val done = CompletableDeferred<Unit>()
        var socket: WebSocket? = null
        var heartbeatJob: kotlinx.coroutines.Job? = null
        var connected = false
        try {
          val req = Request.Builder()
            .url(Env1.DANMU_WS)
            .addHeader("Sec-WebSocket-Protocol", "binary")
            .build()

          val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
              socket = webSocket
              connected = true
              webSocket.send(encode("type@=loginreq/roomid@=$roomId/"))
              webSocket.send(encode("type@=joingroup/rid@=$roomId/gid@=1/"))

              heartbeatJob = launch {
                while (isActive) {
                  delay(45_000)
                  socket?.send(encode("type@=mrkl/")) ?: break
                }
              }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
              val data = bytes.toByteArray()
              if (data.size < 13) return
              val payload = data.copyOfRange(12, data.size - 1)
              val text = runCatching { payload.toString(Charsets.UTF_8) }.getOrNull() ?: return
              val m = parseMap(text)

              if (m["type"] == "chatmsg") {
                val user = m["nn"].orEmpty().ifBlank { "unknown" }
                val content = m["txt"].orEmpty()
                val userLevel = m["level"]?.toIntOrNull() ?: 0
                val fans = m["bl"]?.toIntOrNull() ?: 0
                val color = m["col"]
                trySend(
                  DanmakuMessage(
                    roomId = roomId,
                    user = user,
                    content = content,
                    userLevel = userLevel,
                    fansClubLevel = fans,
                    color = color,
                  ),
                )
              }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
              webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
              done.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
              done.complete(Unit)
            }
          }

          socket = okHttp.newWebSocket(req, listener)
          done.await()
          // 连上过说明链路本身没问题，退避立刻复位；否则一次断线累积会把重连间隔
          // 推到 30s 并永久停留（成功连接也不回落），网络恢复后还要白等半分钟。
          if (connected) backoff = 1000L
        } catch (ce: CancellationException) {
          throw ce
        } catch (t: Throwable) {
          // 不再静默吞掉：弹幕空白时至少能从日志判断是连不上、被拒还是解析失败。
          AppLog.w("DTV-Douyu-Danmaku", "douyu 弹幕会话异常 roomId=$roomId", t)
        } finally {
          heartbeatJob?.cancel()
          socket?.cancel()
        }
        delay(backoff)
        backoff = min(backoff * 2, 30_000L)
      }
    }

    awaitClose {
      // Child coroutines are cancelled automatically when the flow collector is cancelled.
    }
  }
}
