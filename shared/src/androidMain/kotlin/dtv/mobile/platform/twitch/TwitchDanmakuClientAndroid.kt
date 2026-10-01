package dtv.mobile.platform.twitch

import dtv.mobile.platform.Env6
import dtv.mobile.repo.DanmakuMessage
import dtv.mobile.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.math.min
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Twitch IRC 弹幕（匿名 justinfan 登录）。
 *
 * 协议：wss 文本帧；连接后 CAP REQ + NICK justinfanXXXXX + JOIN #<login>，
 * 服务端定期 PING 需回 PONG；弹幕为 PRIVMSG 行，tags 里带 display-name/color。
 */
// tags 解析正则：PRIVMSG 是高频路径（热门直播间每秒几十条），预编译避免每条消息重复编译
private val DISPLAY_NAME_REGEX = Regex("display-name=([^;]*)")
private val NICK_REGEX = Regex("nick=([^;\\s]+)")
private val COLOR_REGEX = Regex("color=([^;]*)")

private const val TAG = "DTV-Twitch-IRC"


class TwitchDanmakuClientAndroid(
  // 弹幕 WS 专用客户端：readTimeout 关掉（IRC 长连接常态就是长时间没数据），并发 ping 做半开检测。
  // 不开 ping 时，网络切换 / 弱网丢包形成的半开连接不会触发 onFailure，本端 PONG 回应也照样
  // 发得出去，`sessionClosed` 会永久挂起 —— 弹幕从此静默且永不重连。
  // （与抖音/虎牙客户端同款配置）
  private val okHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .writeTimeout(10, TimeUnit.SECONDS)
    .pingInterval(20, TimeUnit.SECONDS)
    .build(),
) {
  fun observe(login: String): Flow<DanmakuMessage> = callbackFlow {
    val channel = login.trim().lowercase()
    launch {
      var backoff = 1000L
      while (isActive) {
        val done = CompletableDeferred<Unit>()
        var socket: WebSocket? = null
        // 本次连接是否成功建立过：OkHttp 的回调在它自己的线程上跑，用原子量保证可见性。
        // 退避策略：成功连上过 → 断开后立刻用最短间隔重连（不掉线观感）；
        // 连续失败（一次都没连上）→ 指数退避，避免疯狂重试。
        val connected = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
          val req = Request.Builder().url(Env6.DANMU_WS).build()
          val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
              socket = webSocket
              connected.set(true)
              webSocket.send("CAP REQ :twitch.tv/tags twitch.tv/commands")
              webSocket.send("NICK justinfan${(10_000..99_999).random()}")
              webSocket.send("JOIN #$channel")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
              text.split("\r\n").forEach { line ->
                val trimmed = line.trim()
                when {
                  trimmed.isEmpty() -> Unit
                  trimmed.startsWith("PING") -> webSocket.send("PONG :tmi.twitch.tv")
                  trimmed.contains("PRIVMSG") -> {
                    val body = trimmed.substringAfter("PRIVMSG", "")
                    val content = body.substringAfter(" :", "").trim()
                    if (content.isNotEmpty()) {
                      val user = DISPLAY_NAME_REGEX.find(trimmed)
                        ?.groupValues?.get(1)?.trim().orEmpty()
                        .ifBlank {
                          NICK_REGEX.find(trimmed)
                            ?.groupValues?.get(1).orEmpty().ifBlank { "unknown" }
                        }
                      val color = COLOR_REGEX.find(trimmed)
                        ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
                      trySend(
                        DanmakuMessage(
                          roomId = channel,
                          user = user,
                          content = content,
                          color = color,
                        ),
                      )
                    }
                  }
                }
              }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
              webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
              done.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
              // 不再静默吞掉：弹幕空白时至少能从日志看出是连不上还是被拒。
              AppLog.w(TAG, "twitch irc 连接失败 channel=$channel code=${response?.code}", t)
              done.complete(Unit)
            }
          }

          socket = okHttp.newWebSocket(req, listener)
          done.await()
        } catch (ce: CancellationException) {
          throw ce
        } catch (t: Throwable) {
          AppLog.w(TAG, "twitch irc 会话异常 channel=$channel", t)
        } finally {
          socket?.cancel()
        }
        delay(backoff)
        backoff = if (connected.get()) 1_000L else min(backoff * 2, 30_000L)
      }
    }

    awaitClose {
      // Child coroutines are cancelled automatically when the flow collector is cancelled.
    }
  }
}
