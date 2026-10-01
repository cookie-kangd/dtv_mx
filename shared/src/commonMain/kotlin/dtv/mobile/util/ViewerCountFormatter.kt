package dtv.mobile.util

/**
 * For raw viewer counts like "1739892", format to "173.9万" (truncate to 1 decimal).
 * If the server already returns a "万" string, keep it as-is.
 */
fun formatViewerCountWanIfNeeded(raw: String): String {
  val t = raw.trim()
  if (t.isBlank()) return t
  if (t.contains('万')) return t
  if (!t.all { it.isDigit() }) return t

  val value = t.toLongOrNull() ?: return t
  if (value < 10_000L) return t

  // 全程整数运算：tenths 是「万」的十倍值（截断而非四舍五入，与旧行为一致）。
  // 原实现是 String.format(Locale.US, "%.1f万", ...) —— java.util.Locale 是 JVM 专属
  // API，写在 commonMain 会让这一层在 Android 之外编译不过（典型的 JVM 依赖泄漏）。
  val tenths = value / 1_000L
  return "${tenths / 10L}.${tenths % 10L}万"
}
