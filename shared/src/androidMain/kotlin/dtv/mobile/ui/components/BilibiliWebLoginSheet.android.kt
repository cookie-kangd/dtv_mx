package dtv.mobile.ui.components

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dtv.mobile.state.AppState
import dtv.mobile.util.AppLog
import kotlinx.coroutines.delay
import dtv.mobile.repo.BilibiliQrStatus

private const val BILIBILI_LOGIN_URL = "https://passport.bilibili.com/login"
private const val BILIBILI_COOKIE_PROBE_URL = "https://www.bilibili.com"
private const val BILIBILI_COOKIE_PROBE_URL_2 = "https://passport.bilibili.com"

private enum class LoginMode { QR, AccountPassword }

private fun hasRequiredBilibiliCookies(cookieHeader: String?): Boolean {
  val raw = cookieHeader?.lowercase().orEmpty()
  return raw.contains("sessdata=") && raw.contains("bili_jct=")
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun BilibiliWebLoginSheet(
  appState: AppState,
  onDismissRequest: () -> Unit,
  onCookieCaptured: (cookieHeader: String) -> Unit,
) {
  var mode by remember { mutableStateOf(LoginMode.QR) }

  // skipPartiallyExpanded = true：弹窗直接完整展开到顶部，不再停在半屏需要手动上拖
  val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
    skipPartiallyExpanded = true,
  )

  ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    sheetState = sheetState,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 6.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text("登录 B站", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black))

      SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth(),
      ) {
        SegmentedButton(
          selected = mode == LoginMode.QR,
          onClick = { mode = LoginMode.QR },
          shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text("二维码登录") }
        SegmentedButton(
          selected = mode == LoginMode.AccountPassword,
          onClick = { mode = LoginMode.AccountPassword },
          shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text("账号密码") }
      }

      AnimatedVisibility(visible = mode == LoginMode.QR) {
        QrLoginPanel(
          appState = appState,
          onCookieCaptured = onCookieCaptured,
          onDismissRequest = onDismissRequest,
        )
      }
      AnimatedVisibility(visible = mode == LoginMode.AccountPassword) {
        AccountPasswordLoginPanel(
          onCookieCaptured = onCookieCaptured,
          onDismissRequest = onDismissRequest,
        )
      }
    }
  }
}

/** 二维码登录：用 B站 App 扫描页面上的二维码，登录态由仓库 polling 实时检测 */
@Composable
private fun QrLoginPanel(
  appState: AppState,
  onCookieCaptured: (cookieHeader: String) -> Unit,
  onDismissRequest: () -> Unit,
) {
  var qrUrl by remember { mutableStateOf<String?>(null) }
  var qrKey by remember { mutableStateOf<String?>(null) }
  var status by remember { mutableStateOf<QrStatusUi>(QrStatusUi.Loading) }
  var errMsg by remember { mutableStateOf<String?>(null) }

  // 首次进入面板：申请新的二维码
  LaunchedEffect(Unit) {
    runCatching { appState.repo.generateBilibiliQrCode() }
      .onSuccess { code ->
        qrUrl = code.url
        qrKey = code.qrcodeKey
        status = QrStatusUi.Waiting
      }
      .onFailure {
        status = QrStatusUi.Failed
        errMsg = it.message ?: "申请二维码失败"
      }
  }

  // 轮询登录状态
  LaunchedEffect(qrKey) {
    val key = qrKey ?: return@LaunchedEffect
    while (true) {
      delay(1500)
      val r = runCatching { appState.repo.pollBilibiliQrCode(key) }
        .onFailure {
          // 单次失败（如网络）不打断轮询
          AppLog.w("BilibiliLogin", "poll failed: ${it.message}")
        }
        .getOrNull() ?: continue
      status = when (r.status) {
        BilibiliQrStatus.Waiting -> QrStatusUi.Waiting
        BilibiliQrStatus.Scanned -> QrStatusUi.Scanned
        BilibiliQrStatus.Confirmed -> {
          // 服务端确认登录：尝试取出已写入仓库的 cookie
          val cookie = runCatching { appState.repo.getBilibiliCookie() }.getOrNull()
          if (!cookie.isNullOrBlank() && hasRequiredBilibiliCookies(cookie)) {
            onCookieCaptured(cookie)
            onDismissRequest()
            return@LaunchedEffect
          }
          // 部分实现会在 poll 响应内联 cookie；这里兜底再读一次 + 让用户手动点"完成"
          QrStatusUi.Confirmed
        }
        BilibiliQrStatus.Expired -> QrStatusUi.Expired
        BilibiliQrStatus.Failed -> QrStatusUi.Failed
      }
      if (r.status == BilibiliQrStatus.Expired || r.status == BilibiliQrStatus.Failed) {
        errMsg = r.message
        // 二维码已失效/失败：必须停止轮询。否则会拿着同一个死 key 每 1.5 秒
        // 继续打接口（界面已经显示"已失效"却还在请求），既耗电又可能触发服务端风控。
        // 用户点「刷新二维码」会更新 qrKey，本协程随之重启并重新开始轮询。
        break
      }
    }
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // 二维码卡片
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = Color.White,
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
      modifier = Modifier
        .padding(top = 4.dp)
        .size(220.dp),
    ) {
      Box(contentAlignment = Alignment.Center) {
        when {
          qrUrl != null -> QrCodeImage(
            data = qrUrl!!,
            modifier = Modifier
              .size(204.dp)
              .padding(8.dp),
          )
          else -> Text(
            text = errMsg ?: "申请二维码中…",
            color = Color(0xFF374151),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(16.dp),
          )
        }
      }
    }

    // 状态点 + 提示
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      StatusDot(status)
      Text(
        text = statusText(status, errMsg),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
      )
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      TextButton(onClick = onDismissRequest) { Text("关闭") }
      TextButton(
        enabled = status == QrStatusUi.Expired || status == QrStatusUi.Failed,
        onClick = {
          // 重新申请二维码
          status = QrStatusUi.Loading
          errMsg = null
          qrUrl = null
          qrKey = null
        },
      ) { Text("刷新二维码") }
    }
    Spacer(modifier = Modifier.height(6.dp))
  }
}

@Composable
private fun StatusDot(status: QrStatusUi) {
  val color = when (status) {
    QrStatusUi.Loading -> Color(0xFF9CA3AF)
    QrStatusUi.Waiting -> Color(0xFF22D3EE)
    QrStatusUi.Scanned -> Color(0xFFF59E0B)
    QrStatusUi.Confirmed -> Color(0xFF22C55E)
    QrStatusUi.Expired -> Color(0xFFEF4444)
    QrStatusUi.Failed -> Color(0xFFEF4444)
  }
  Box(
    modifier = Modifier
      .size(8.dp)
      .clip(CircleShape)
      .background(color),
  )
}

private fun statusText(status: QrStatusUi, msg: String?): String = when (status) {
  QrStatusUi.Loading -> msg ?: "申请二维码中…"
  QrStatusUi.Waiting -> "请打开 B站 App 扫一扫登录"
  QrStatusUi.Scanned -> "已扫码，请在手机上确认登录"
  QrStatusUi.Confirmed -> "登录成功，正在关闭…"
  QrStatusUi.Expired -> msg ?: "二维码已失效，点'刷新二维码'重试"
  QrStatusUi.Failed -> msg ?: "登录失败，请重试"
}

private enum class QrStatusUi { Loading, Waiting, Scanned, Confirmed, Expired, Failed }

/** 账号密码登录：在 Sheet 内嵌 WebView，加载 B站 passprot 登录页，登录态通过 Cookie 自动捕获 */
@Composable
private fun AccountPasswordLoginPanel(
  onCookieCaptured: (cookieHeader: String) -> Unit,
  onDismissRequest: () -> Unit,
) {
  val context = LocalContext.current
  val cookieManager = remember {
    CookieManager.getInstance().apply { setAcceptCookie(true) }
  }

  val webView = remember(context) {
    WebView(context).apply {
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.userAgentString = settings.userAgentString + " DTV-Mobile"
    }
  }

  var status by remember { mutableStateOf("请在网页登录 B站 账号") }
  var lastCookie by remember { mutableStateOf<String?>(null) }

  fun probeAndMaybeFinish() {
    val merged = listOfNotNull(
      cookieManager.getCookie(BILIBILI_COOKIE_PROBE_URL)?.trim()?.takeIf { it.isNotBlank() },
      cookieManager.getCookie(BILIBILI_COOKIE_PROBE_URL_2)?.trim()?.takeIf { it.isNotBlank() },
    ).distinct().joinToString("; ").trim().ifBlank { null }

    if (merged != null && merged != lastCookie) {
      lastCookie = merged
    }
    if (hasRequiredBilibiliCookies(merged)) {
      status = "已获取登录 Cookie"
      onCookieCaptured(merged!!)
      onDismissRequest()
    }
  }

  DisposableEffect(Unit) {
    webView.webViewClient = object : WebViewClient() {
      override fun onPageFinished(view: WebView?, url: String?) {
        probeAndMaybeFinish()
      }
    }
    runCatching { cookieManager.setAcceptThirdPartyCookies(webView, true) }
    webView.loadUrl(BILIBILI_LOGIN_URL)

    onDispose {
      runCatching { webView.stopLoading() }
      runCatching { webView.destroy() }
    }
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
      text = "在下方页面输入账号密码完成登录。Cookie 会被自动保存。",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
    )
    Text(
      text = status,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
    )
    AndroidView(
      factory = { webView },
      modifier = Modifier
        .fillMaxWidth()
        .height(480.dp),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      TextButton(onClick = onDismissRequest) { Text("关闭") }
      TextButton(onClick = { probeAndMaybeFinish() }) { Text("完成") }
    }
    Spacer(modifier = Modifier.height(8.dp))
  }
}
