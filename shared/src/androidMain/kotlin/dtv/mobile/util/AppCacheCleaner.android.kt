package dtv.mobile.util

import android.content.Context
import dtv.mobile.ui.player.releaseDtvMediaCache
import java.io.File

/**
 * 退出清理：清理可随时安全删除的缓存与临时文件。
 *
 * 刻意只清理 [Context.getCacheDir]（系统设计的「可清除」区域），
 * 不触碰 SharedPreferences（登录态、设置、关注列表都存那里），也不触碰 filesDir 下的日志，
 * 因此不会影响稳定性、流畅度与任何账号登录状态（如 B站 Cookie）。
 */
object AppCacheCleaner {
  /** 退出时调用：先安全释放 ExoPlayer 媒体缓存，再清空 cache 分区（直播缓存、图片缓存、临时下载等）。 */
  fun clearOnExit(context: Context) {
    // 先释放仍被持有的 SimpleCache，避免直接删目录造成缓存索引损坏
    runCatching { releaseDtvMediaCache() }
    deleteRecursive(context.cacheDir)
  }

  private fun deleteRecursive(dir: File?) {
    val files = dir?.listFiles() ?: return
    for (f in files) {
      // 原来子目录删完内容后目录本身留着，反复清理会累积一批空目录；
      // 顶层 cacheDir 保留（进程可能还在用），子目录随内容一起删掉。
      if (f.isDirectory) deleteRecursive(f)
      runCatching { f.delete() }
    }
  }
}
