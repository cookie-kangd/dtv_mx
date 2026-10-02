package dtv.mobile.platform.huya
import dtv.mobile.platform.Env2

import dtv.mobile.repo.DanmakuMessage
import dtv.mobile.util.AppLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class HuyaDanmakuClientAndroid(
  private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .proxy(Proxy.NO_PROXY)
    .build(),
) {
  private val wsClient: HttpClient = HttpClient(CIO) {
    install(WebSockets)
  }

  companion object {
    // Copied from DTV-heroui desktop backend (src-tauri/src/platforms/huya/danmaku.rs).
    // Do not modify unless upstream changes.
    private val HEARTBEAT: ByteArray = byteArrayOf(
      0x00, 0x03, 0x1d, 0x00, 0x00, 0x69, 0x00, 0x00, 0x00, 0x69, 0x10, 0x03, 0x2c, 0x3c, 0x4c, 0x56,
      0x08, 0x6f, 0x6e, 0x6c, 0x69, 0x6e, 0x65, 0x75, 0x69, 0x66, 0x0f, 0x4f, 0x6e, 0x55, 0x73, 0x65,
      0x72, 0x48, 0x65, 0x61, 0x72, 0x74, 0x42, 0x65, 0x61, 0x74, 0x7d, 0x00, 0x00, 0x3c, 0x08, 0x00,
      0x01, 0x06, 0x04, 0x74, 0x52, 0x65, 0x71, 0x1d, 0x00, 0x00, 0x2f, 0x0a, 0x0a, 0x0c, 0x16, 0x00,
      0x26, 0x00, 0x36, 0x07, 0x61, 0x64, 0x72, 0x5f, 0x77, 0x61, 0x70, 0x46, 0x00, 0x0b, 0x12, 0x03,
      0xae.toByte(), 0xf0.toByte(), 0x0f, 0x22, 0x03, 0xae.toByte(), 0xf0.toByte(), 0x0f, 0x3c, 0x42,
      0x6d, 0x52, 0x02, 0x60, 0x5c, 0x60, 0x01, 0x7c, 0x82.toByte(), 0x00, 0x0b, 0xb0.toByte(), 0x1f,
      0x9c.toByte(), 0xac.toByte(), 0x0b, 0x8c.toByte(), 0x98.toByte(), 0x0c, 0xa8.toByte(), 0x0c,
    )

    private const val UA =
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
  }

  fun observe(roomId: String): Flow<DanmakuMessage> = callbackFlow {
    val heartbeatJobRef = AtomicReference<Job?>(null)
    val watchdogJobRef = AtomicReference<Job?>(null)
    val statsJobRef = AtomicReference<Job?>(null)
    val recvCount = AtomicLong(0)
    val decodedCount = AtomicLong(0)
    val decodeErrCount = AtomicLong(0)
    val droppedCount = AtomicLong(0)
    val lastBinaryAtMs = AtomicLong(0)
    val resubscribeSent = AtomicBoolean(false)
    val resubscribeAtMs = AtomicLong(0)

    // 见下方「缓存已拿到的 ayyuid」的说明：只在连接建立失败（包可能过期）时清空，
    // 正常断开重连沿用缓存，避免每秒重下一次整页 HTML。
    var cachedWsInfo: HuyaWsInfo? = null

    val job = launch(Dispatchers.IO) {
      var backoffMs = 1000L
      while (isActive) {
        // 本次连接是否建立过：OkHttp 的回调在它自己的线程上跑，用原子量保证可见性。
        val connected = AtomicBoolean(false)

        // 缓存已拿到的 ayyuid / 注册包：fetchWsInfo 要下载整页房间 HTML（数百 KB~1MB）
        // 并跑 4 条正则（含 DOTALL 惰性匹配，需扫全页）。ayyuid 是浏览器长期标识，
        // 一次会话内不会变，只有连接被服务端拒绝（包过期）时才需要重新取。
        // 之前每轮重连都无条件重下一次 —— 退避下限 1s 时就是「每秒下载一次整页 HTML」。
        val wsInfo = cachedWsInfo ?: runCatching { HuyaDtvHerouiAlgo.fetchWsInfo(http, roomId) }
          .onFailure { AppLog.e("DTV-Huya", "fetch huya ws info failed roomId=$roomId", it) }
          .getOrNull()
          ?.also { cachedWsInfo = it }

        if (wsInfo == null) {
          delay(backoffMs)
          backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
          continue
        }

        AppLog.i("DTV-Huya", "huya danmaku connecting roomId=$roomId ayyuid=${wsInfo.ayyuid} regLen=${wsInfo.registerPayload.size}")

        val done = CompletableDeferred<Unit>()
        try {
          wsClient.webSocket(
            urlString = Env2.DANMU_WS,
            request = {
              header("Origin", Env2.HOST)
              header("Referer", Env2.HOST + "/")
              header("User-Agent", UA)
            },
          ) {
            AppLog.i("DTV-Huya", "huya danmaku ws opened roomId=$roomId")
            connected.set(true)
            val session = this
            session.send(Frame.Binary(fin = true, data = wsInfo.registerPayload))
            lastBinaryAtMs.set(System.currentTimeMillis())
            resubscribeSent.set(false)
            resubscribeAtMs.set(0)

            heartbeatJobRef.getAndSet(
              this@callbackFlow.launch(Dispatchers.IO) {
                var hb = 0
                while (isActive && !done.isCompleted) {
                  hb += 1
                  try {
                    session.send(Frame.Binary(fin = true, data = HEARTBEAT))
                  } catch (t: Throwable) {
                    AppLog.w("DTV-Huya", "huya heartbeat send failed roomId=$roomId hb=$hb", t)
                    done.complete(Unit)
                    break
                  }
                  if (hb <= 3 || hb % 6 == 0) {
                    AppLog.i("DTV-Huya", "huya heartbeat sent roomId=$roomId hb=$hb")
                  }
                  delay(20_000L)
                }
              },
            )?.cancel()

            watchdogJobRef.getAndSet(
              this@callbackFlow.launch(Dispatchers.IO) {
                while (isActive && !done.isCompleted) {
                  delay(3_000L)
                  val last = lastBinaryAtMs.get()
                  if (last <= 0L) continue
                  val idleMs = System.currentTimeMillis() - last
                  val sinceResub = resubscribeAtMs.get().let { if (it <= 0L) Long.MAX_VALUE else (System.currentTimeMillis() - it) }

                  if (idleMs >= 12_000L && !resubscribeSent.get() && recvCount.get() > 0) {
                    resubscribeSent.set(true)
                    resubscribeAtMs.set(System.currentTimeMillis())
                    AppLog.w(
                      "DTV-Huya",
                      "huya danmaku idle, re-send subscribe roomId=$roomId idleMs=$idleMs recv=${recvCount.get()} decoded=${decodedCount.get()}",
                    )
                    try {
                      session.send(Frame.Binary(fin = true, data = wsInfo.registerPayload))
                    } catch (t: Throwable) {
                      // 发不出去说明连接已死，记一条日志，弹幕空白时才有线索可查
                      AppLog.w("DTV-Huya", "huya danmaku re-subscribe failed roomId=$roomId", t)
                    }
                    try {
                      session.send(Frame.Binary(fin = true, data = HEARTBEAT))
                    } catch (t: Throwable) {
                      AppLog.w("DTV-Huya", "huya danmaku heartbeat send failed roomId=$roomId", t)
                    }
                    continue
                  }

                  if (idleMs >= 18_000L && sinceResub >= 6_000L) {
                    AppLog.w(
                      "DTV-Huya",
                      "huya danmaku idle too long, force reconnect roomId=$roomId idleMs=$idleMs recv=${recvCount.get()} decoded=${decodedCount.get()} decodeErr=${decodeErrCount.get()} dropped=${droppedCount.get()}",
                    )
                    runCatching { session.cancel("idle") }
                    done.complete(Unit)
                    break
                  }
                }
              },
            )?.cancel()

            statsJobRef.getAndSet(
              this@callbackFlow.launch(Dispatchers.IO) {
                while (isActive && !done.isCompleted) {
                  delay(10_000L)
                  val last = lastBinaryAtMs.get()
                  val idleMs = if (last <= 0L) -1L else (System.currentTimeMillis() - last)
                  AppLog.i(
                    "DTV-Huya",
                    "huya danmaku stats roomId=$roomId recv=${recvCount.get()} decoded=${decodedCount.get()} decodeErr=${decodeErrCount.get()} dropped=${droppedCount.get()} idleMs=$idleMs",
                  )
                }
              },
            )?.cancel()

            var peeked = 0
            runCatching {
              for (frame in incoming) {
                if (done.isCompleted) break
                when (frame) {
                  is Frame.Binary -> {
                    lastBinaryAtMs.set(System.currentTimeMillis())
                    val recv = recvCount.incrementAndGet()
                    val data = frame.data
                    if (peeked < 8 || recv % 200L == 0L) {
                      peeked += 1
                      runCatching { HuyaDtvHerouiAlgo.peekCmds(data) }
                        .onSuccess { (top, nested) ->
                          AppLog.i("DTV-Huya", "huya ws msg len=${data.size} top=$top nested=$nested roomId=$roomId")
                        }
                        .onFailure { e ->
                          AppLog.w("DTV-Huya", "huya ws msg peek failed len=${data.size} roomId=$roomId err=${e::class.java.simpleName}:${e.message}")
                        }
                    }

                    val chat = runCatching { HuyaDtvHerouiAlgo.decodeChat(data) }
                      .onFailure {
                        val err = decodeErrCount.incrementAndGet()
                        if (err <= 5 || err % 50L == 0L) {
                          AppLog.w("DTV-Huya", "huya danmaku decode error roomId=$roomId recv=$recv errCount=$err len=${data.size}", it)
                        }
                      }
                      .getOrNull()
                      ?: continue

                    decodedCount.incrementAndGet()
                    val r = trySend(DanmakuMessage(roomId = roomId, user = chat.user, content = chat.content))
                    if (r.isFailure) {
                      val dropped = droppedCount.incrementAndGet()
                      if (dropped <= 5 || dropped % 100L == 0L) {
                        AppLog.w("DTV-Huya", "huya danmaku dropped (channel backpressure) roomId=$roomId dropped=$dropped")
                      }
                    }
                  }
                  is Frame.Close -> {
                    done.complete(Unit)
                    break
                  }
                  else -> Unit
                }
              }
            }.onFailure { err ->
              AppLog.e("DTV-Huya", "huya danmaku ws receive loop failed roomId=$roomId", err)
            }

            done.complete(Unit)
          }
        } catch (t: Throwable) {
          AppLog.e("DTV-Huya", "huya danmaku ws connect failed roomId=$roomId", t)
          // 没连上过就说明这份注册包/ayyuid 已被服务端拒绝（过期或失效），继续用缓存
          // 重试没有意义，下一轮必须重新取 —— 否则会拿着废包一直连到天荒地老。
          if (!connected.get()) cachedWsInfo = null
        }

        statsJobRef.getAndSet(null)?.cancel()
        watchdogJobRef.getAndSet(null)?.cancel()
        heartbeatJobRef.getAndSet(null)?.cancel()

        if (!isActive) break
        // 连上过说明链路本身没问题，退避立刻复位；否则一次断线累积会把重连间隔
        // 推到 30s 并永久停留（成功连接也不回落），网络恢复后还要白等半分钟。
        if (connected.get()) backoffMs = 1000L
        AppLog.i(
          "DTV-Huya",
          "huya danmaku reconnecting roomId=$roomId backoffMs=$backoffMs recv=${recvCount.get()} decoded=${decodedCount.get()} decodeErr=${decodeErrCount.get()} dropped=${droppedCount.get()}",
        )
        delay(backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
      }
    }

    awaitClose {
      statsJobRef.getAndSet(null)?.cancel()
      watchdogJobRef.getAndSet(null)?.cancel()
      heartbeatJobRef.getAndSet(null)?.cancel()
      job.cancel()
      AppLog.i(
        "DTV-Huya",
        "huya danmaku flow closed roomId=$roomId recv=${recvCount.get()} decoded=${decodedCount.get()} decodeErr=${decodeErrCount.get()} dropped=${droppedCount.get()}",
      )
    }
  }.buffer(capacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}
