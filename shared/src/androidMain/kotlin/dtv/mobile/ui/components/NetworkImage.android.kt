package dtv.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
actual fun NetworkImage(
  url: String?,
  contentDescription: String?,
  modifier: Modifier,
  contentScale: ContentScale,
) {
  if (url.isNullOrBlank()) return
  val context = LocalContext.current
  // ImageRequest 按 url 记忆：滚动列表里卡片重组时不再重复做 Uri.parse、
  // header 解析和 builder 分配；Coil 内部对相同请求命中缓存也更快。
  // 部分平台图床（如抖音）会对不带 Referer / User-Agent 的请求返回 403，
  // 导致封面/头像加载不出来。补充自源站 Referer 与移动端 UA，提升图片加载成功率。
  val model = remember(url) {
    val origin = runCatching {
      val u = android.net.Uri.parse(url)
      "${u.scheme ?: "https"}://${u.host ?: "live.douyin.com"}"
    }.getOrDefault("https://live.douyin.com")
    // 不指定 size：Coil 会按 Modifier 约束解码，避免把整张大图读进内存。
    // allowRgb565：封面/头像都是不透明照片，用 RGB_565 解码内存占用减半，
    // 大列表（几十张卡片）滚动时的 GC 压力显著降低；crossfade 缩短到 120ms，
    // 减少列表快速滚动时的过渡动画开销。
    ImageRequest.Builder(context)
      .data(url)
      .crossfade(120)
      .allowRgb565(true)
      .setHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
      .setHeader("Referer", origin)
      .build()
  }
  AsyncImage(
    model = model,
    contentDescription = contentDescription,
    modifier = modifier,
    contentScale = contentScale,
  )
}
