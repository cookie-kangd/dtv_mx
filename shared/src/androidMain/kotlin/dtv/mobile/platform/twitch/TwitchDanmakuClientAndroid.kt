package dtv.mobile.platform.twitch

import dtv.mobile.platform.Env6
import dtv.mobile.repo.DanmakuMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

class TwitchDanmakuClientAndroid(
  private val okHttp: OkHttpClient = OkHttpClient(),
) {
  fun observe(login: String): Flow<DanmakuMessage> = callbackFlow {
    val channel = login.trim().lowercase()
    launch {
      var backoff = 1000L
      while (isActive) {
        val done = CompletableDeferred<Unit>()
        var socket: WebSocket? = null
        try {
          val req = Request.Builder().url(Env6.DANMU_WS).build()
          val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
              socket = webSocket
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
              done.complete(Unit)
            }
          }

          socket = okHttp.newWebSocket(req, listener)
          done.await()
        } catch (ce: CancellationException) {
          throw ce
        } catch (t: Throwable) {
          // ignore and retry
        } finally {
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
