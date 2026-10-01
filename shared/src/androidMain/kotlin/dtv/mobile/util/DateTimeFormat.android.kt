package dtv.mobile.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// formatter 只按系统默认 locale 建一次，可重复使用（DateTimeFormatter 线程安全）。
// java.time 依赖已开启的 core library desugaring（见 build.gradle.kts），minSdk 24 可用。
private val LOCAL_DATE_TIME: DateTimeFormatter =
  DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())

actual fun formatIsoUtcToLocal(iso: String): String = runCatching {
  Instant.parse(iso).atZone(ZoneId.systemDefault()).format(LOCAL_DATE_TIME)
}.getOrElse {
  // 解析失败（格式异常/非 ISO 串）：退化成原来的简单显示，不让 UI 出错。
  iso.replace("T", " ").removeSuffix("Z")
}
