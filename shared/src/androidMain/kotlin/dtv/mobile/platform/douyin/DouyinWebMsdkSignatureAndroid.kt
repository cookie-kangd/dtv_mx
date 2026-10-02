package dtv.mobile.platform.douyin

import android.content.Context
import app.cash.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * WebMsSDK signature generator aligned with `kotlin-danmaku-android`:
 * - Loads `shared/src/androidMain/resources/douyin/webmssdk.js`
 * - Evaluates `getMSSDKSignature(msStub, userAgent)` via QuickJs (same engine as demo)
 */
internal class DouyinWebMsdkSignatureAndroid(
  private val appContext: Context,
) {
  companion object {
    private const val WEB_MSSDK_JS_RESOURCE = "douyin/webmssdk.js"

    private val MS_STUB_KEYS = listOf(
      "live_id",
      "aid",
      "version_code",
      "webcast_sdk_version",
      "room_id",
      "sub_room_id",
      "sub_channel_id",
      "did_rule",
      "user_unique_id",
      "device_platform",
      "device_type",
      "ac",
      "identity",
    )

    /** 625KB 的 webmssdk.js 只读一次，进程内复用（见 loadJs 的说明）。 */
    private val jsSource: String by lazy {
      val cl = DouyinWebMsdkSignatureAndroid::class.java.classLoader
      val stream = cl.getResourceAsStream(WEB_MSSDK_JS_RESOURCE)
        ?: error("Missing resource $WEB_MSSDK_JS_RESOURCE")
      stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
  }

  private fun md5Hex(input: String): String {
    val md = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
    return md.joinToString("") { "%02x".format(it) }
  }

  private fun loadJs(): String {
    // 资源是打包进 APK 的静态文件，进程内永不变。之前每次 signature() 都从
    // classloader 重新读一遍 625KB 资源（ByteArray + UTF-16 String ≈ 1.9MB 瞬时分配），
    // 而签名在每次建连/重连时都要算一次 —— 重连循环最长 30s 退避永不停歇，
    // 于是这 1.9MB 在后台反复 churn。与 ExoPlayer 的解码缓冲叠加时是真实 OOM 点。
    // 缓存字符串后每次签名只剩 QuickJS 求值本身的开销。
    return jsSource
  }

  private fun msStub(roomId: String, userUniqueId: String, webcastSdkVersion: String): String {
    val params = linkedMapOf(
      "live_id" to "1",
      "aid" to "6383",
      "version_code" to "180800",
      "webcast_sdk_version" to webcastSdkVersion,
      "room_id" to roomId,
      "sub_room_id" to "",
      "sub_channel_id" to "",
      "did_rule" to "3",
      "user_unique_id" to userUniqueId,
      "device_platform" to "web",
      "device_type" to "",
      "ac" to "",
      "identity" to "audience",
    )
    val toSign = MS_STUB_KEYS.joinToString(",") { k -> "$k=${params[k].orEmpty()}" }
    return md5Hex(toSign)
  }

  suspend fun signature(roomId: String, userUniqueId: String, webcastSdkVersion: String, userAgent: String): String =
    withContext(Dispatchers.Default) {
      val stub = msStub(roomId = roomId, userUniqueId = userUniqueId, webcastSdkVersion = webcastSdkVersion)
      val js = loadJs()

      QuickJs.create().use { qjs ->
        qjs.evaluate(js)
        var out = ""
        var attempt = 0
        while (attempt < 12) {
          attempt += 1
          val expr = "getMSSDKSignature(${stub.toJsStringLiteral()}, ${userAgent.toJsStringLiteral()})"
          val v = qjs.evaluate(expr)
          val sig = (v as? String).orEmpty().trim()
          if (sig.isNotBlank() && !sig.contains('-') && !sig.contains('=')) {
            out = sig
            break
          }
        }
        if (out.isBlank()) error("empty/invalid douyin WebMsSDK signature")
        out
      }
    }

  private fun String.toJsStringLiteral(): String =
    buildString(this.length + 2) {
      append('"')
      for (ch in this@toJsStringLiteral) {
        when (ch) {
          '\\' -> append("\\\\")
          '"' -> append("\\\"")
          '\n' -> append("\\n")
          '\r' -> append("\\r")
          '\t' -> append("\\t")
          else -> append(ch)
        }
      }
      append('"')
    }
}

