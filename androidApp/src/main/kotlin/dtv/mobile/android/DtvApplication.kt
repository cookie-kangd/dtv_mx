package dtv.mobile.android

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dtv.mobile.state.SubscriptionStoreAndroid
import dtv.mobile.util.AppCacheCleaner
import dtv.mobile.util.AppLog
import dtv.mobile.util.CrashFileLogger
import java.util.concurrent.Executors

class DtvApplication : Application(), ImageLoaderFactory {
  private val mainHandler = Handler(Looper.getMainLooper())
  private val subscriptionStore by lazy { SubscriptionStoreAndroid(this) }
  private val cleanupExecutor by lazy { Executors.newSingleThreadExecutor() }

  /**
   * 退出清理的实际动作：先清 Coil 内存/磁盘缓存（进程内缓存，不随退出生效），
   * 再清 cache 分区（直播缓存、临时下载等）。始终在后台线程执行。
   */
  private val cleanupRunnable = Runnable { runCleanup() }

  override fun onCreate() {
    super.onCreate()
    AppLog.init(this)
    CrashFileLogger.install(this)

    // ① 兜底：上一次退出没来得及清理（最近任务划掉卡片、系统直接杀进程、
    //    或 2 秒延迟没跑完进程就被回收），本次启动立即补清一次。
    //    记录方式是「进入后台时打标记，清理成功后再清标记」，
    //    因此只有异常退出才会遗留标记，正常退出不会重复清理。
    if (consumeCleanupPending()) {
      AppLog.i("DTV-Cleanup", "pending cleanup from last session: clearing now")
      cleanupExecutor.execute { runCleanupInternal() }
    }

    // ② 返回键退出：最后一个 Activity finish 后立刻清理（不等延迟，最可靠）。
    //    判断 isFinishing + 非配置变更（旋转屏幕会重建 Activity，不能误清）。
    registerActivityLifecycleCallbacks(
      object : ActivityLifecycleCallbacks {
        private var aliveCount = 0

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
          aliveCount += 1
        }

        override fun onActivityDestroyed(activity: Activity) {
          aliveCount = (aliveCount - 1).coerceAtLeast(0)
          val finishingForGood = activity.isFinishing && !activity.isChangingConfigurations
          if (finishingForGood && aliveCount == 0) {
            AppLog.i("DTV-Cleanup", "last activity finished: clearing cache")
            markCleanupPending()
            mainHandler.removeCallbacks(cleanupRunnable)
            cleanupExecutor.execute { runCleanupInternal() }
          }
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
      },
    )

    // ③ 切到后台（Home 键/切应用）：延迟 2 秒再清，短暂切出去又回来会被取消，
    //    避免「只是临时切后台」就把缓存清掉影响体验。
    ProcessLifecycleOwner.get().lifecycle.addObserver(
      object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
          markCleanupPending()
          mainHandler.postDelayed(cleanupRunnable, EXIT_CLEANUP_DELAY_MS)
        }

        override fun onStart(owner: LifecycleOwner) {
          mainHandler.removeCallbacks(cleanupRunnable)
          clearCleanupPending()
        }
      },
    )
  }

  private fun runCleanup() {
    cleanupExecutor.execute { runCleanupInternal() }
  }

  private fun runCleanupInternal() {
    if (!subscriptionStore.loadExitCleanupEnabled()) {
      clearCleanupPending()
      return
    }
    AppLog.i("DTV-Cleanup", "clearing cache on app exit")
    runCatching {
      // 图片缓存：内存缓存不会随退出生效（进程可能仍在），磁盘缓存在 cacheDir 内，
      // 都显式清一遍，保证「退出即清」名副其实。
      coilImageLoader.memoryCache?.clear()
      coilImageLoader.diskCache?.clear()
    }.onFailure { AppLog.e("DTV-Cleanup", "clear image cache failed", it) }
    runCatching { AppCacheCleaner.clearOnExit(this) }
      .onFailure { AppLog.e("DTV-Cleanup", "clear failed", it) }
    clearCleanupPending()
  }

  // ---- 遗留清理标记（用于兜底「划掉卡片 / 被系统杀进程」这类来不及清理的退出）----

  private fun prefs() = getSharedPreferences("dtv_session", MODE_PRIVATE)

  private fun markCleanupPending() {
    runCatching { prefs().edit().putBoolean(KEY_CLEANUP_PENDING, true).apply() }
  }

  private fun clearCleanupPending() {
    runCatching { prefs().edit().remove(KEY_CLEANUP_PENDING).apply() }
  }

  private fun consumeCleanupPending(): Boolean {
    val p = prefs()
    val pending = p.getBoolean(KEY_CLEANUP_PENDING, false)
    if (pending) runCatching { p.edit().remove(KEY_CLEANUP_PENDING).apply() }
    return pending
  }

  // Coil 单实例：Application 自己持有，退出时才能拿到并清掉内存/磁盘缓存
  private val coilImageLoader: ImageLoader by lazy { buildImageLoader() }

  override fun newImageLoader(): ImageLoader = coilImageLoader

  private fun buildImageLoader(): ImageLoader =
    ImageLoader.Builder(this)
      // 内存缓存占可用内存 20%，长列表滚动复用位图不反复解码
      .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
      // 直播平台 CDN 普遍下发 no-store/短缓存头；忽略这些头让磁盘缓存始终生效，
      // 二次进入分区/搜索页封面直接命中缓存，滚动明显更流畅
      .respectCacheHeaders(false)
      .diskCache {
        DiskCache.Builder()
          .directory(cacheDir.resolve("image_cache"))
          .maxSizeBytes(256L * 1024 * 1024)
          .build()
      }
      .build()

  companion object {
    private const val EXIT_CLEANUP_DELAY_MS = 2000L
    private const val KEY_CLEANUP_PENDING = "cleanup_pending"
  }
}
