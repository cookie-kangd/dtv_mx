package dtv.mobile.platform.douyu

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Scriptable

class DouyuJsSignerAndroid(
  private val appContext: Context,
) {
  private val cryptoJs: String by lazy {
    appContext.assets.open("douyu/cryptojs.min.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
  }

  // cryptojs 只跟 assets 走，进程内不变，装一次就够。
  // 之前每次签名都 initStandardObjects() + 重新解释 48KB cryptojs（Rhino 里最重的两步，
  // 标准对象作用域通常 300KB~1MB），而签名在「进房间 / 切画质 / 换线路」时都会触发，
  // 正好和 ExoPlayer 起播抢内存峰值。缓存 scope 后只重跑 ub98484234(...) 这一次调用。
  private val cryptoScope: Scriptable by lazy {
    val ctx = RhinoContext.enter()
    try {
      ctx.optimizationLevel = -1
      ctx.initStandardObjects().also { scope ->
        ctx.evaluateString(scope, cryptoJs, "cryptojs.min.js", 1, null)
      }
    } finally {
      RhinoContext.exit()
    }
  }

  suspend fun signParams(
    homeH5EncScript: String,
    roomId: String,
    did: String,
    tsSeconds: Long,
  ): String = withContext(Dispatchers.Default) {
    // cryptoScope 的懒初始化本身会进/出一次 Rhino 上下文，这里再进一次做实际调用。
    val base = cryptoScope
    val ctx = RhinoContext.enter()
    try {
      // Android 上必须禁用优化，否则容易触发 bytecode 生成限制
      ctx.optimizationLevel = -1
      // 每次签名用的 homeH5Enc.js 是按房间下发的、每次都不同，必须在**新作用域**里求值，
      // 否则脚本里的全局变量会跨签名残留（房号/时间戳被上一次覆盖）。
      // 做法：以缓存的 cryptoScope 为原型建子作用域，cryptojs 通过原型链可见，
      // 但本轮的全局写入落在子作用域里，不污染缓存。
      val scope: Scriptable = ctx.newObject(base)
      ctx.evaluateString(scope, homeH5EncScript, "homeH5Enc.js", 1, null)

      val ridJs = jsString(roomId)
      val didJs = jsString(did)
      val call = "ub98484234($ridJs,$didJs,$tsSeconds);"
      val result = ctx.evaluateString(scope, call, "sign-call", 1, null)
      RhinoContext.toString(result)
    } finally {
      RhinoContext.exit()
    }
  }
}

private fun jsString(input: String): String {
  val escaped = buildString {
    input.forEach { ch ->
      when (ch) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> append(ch)
      }
    }
  }
  return "\"$escaped\""
}

