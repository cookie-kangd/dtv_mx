package dtv.mobile.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
import androidx.core.view.WindowCompat
import dtv.mobile.App
import dtv.mobile.state.SubscriptionStoreAndroid
import dtv.mobile.ui.player.ACTION_PIP_CLOSE
import dtv.mobile.ui.player.PictureInPicture
import dtv.mobile.util.AppCacheCleaner
import dtv.mobile.util.AppLog

class MainActivity : ComponentActivity() {
  private lateinit var subscriptionStore: SubscriptionStoreAndroid
  private val pipCloseReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action != ACTION_PIP_CLOSE) return
      // 画中画「退出」按钮：直接关闭整个 App，并触发「设置-退出时清理缓存」。
      // 清理逻辑与 App 正常退出的路径一致（尊重开关），放后台线程避免阻塞。
      if (subscriptionStore.loadExitCleanupEnabled()) {
        val appCtx = this@MainActivity.applicationContext
        Thread {
          runCatching { AppCacheCleaner.clearOnExit(appCtx) }
            .onFailure { AppLog.e("DTV-PiP", "pip exit cleanup failed", it) }
        }.start()
      }
      // 关闭前把单例画中画状态复位：Activity 在 PiP 中 finish 不会触发系统的
      // onPictureInPictureModeChanged(false) 回调，不主动复位会残留 true。
      // （bind() 在下次 onCreate 时也会兜底复位，这里提前同步保持一致。）
      PictureInPicture.setInPip(false)
      this@MainActivity.finish()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    subscriptionStore = SubscriptionStoreAndroid(this)
    AppLog.init(applicationContext)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    // 让画中画控制器能拿到当前 Activity（弱引用，离开即释放，不会泄漏）。
    // bind 内部同时会复位单例画中画状态（修复重进直播间控件消失）。
    PictureInPicture.bind(this)
    // 接收画中画窗口内「退出」按钮的广播（仅本应用内，不导出，安全）。
    ContextCompat.registerReceiver(
      this,
      pipCloseReceiver,
      IntentFilter(ACTION_PIP_CLOSE),
      RECEIVER_NOT_EXPORTED,
    )
    setContent {
      // repository 由 Application 持有（进程级单例），这里只取引用不新建。
      // 之前 remember { AndroidDtvRepository(...) } 每次 Activity 重建都会造一整套
      // HTTP 客户端 + 线程池且从不关闭，旋转/分屏/进出画中画都在泄漏。
      val repo = remember { (application as DtvApplication).repository }
      val store = remember { SubscriptionStoreAndroid(applicationContext) }
      App(repo = repo, subscriptionStore = store)
    }
  }

  override fun onDestroy() {
    runCatching { unregisterReceiver(pipCloseReceiver) }
    super.onDestroy()
  }

  // 进入/退出画中画时同步状态，使播放器叠加层在画中画窗口里只显示视频画面。
  override fun onPictureInPictureModeChanged(
    isInPictureInPictureMode: Boolean,
    newConfig: Configuration,
  ) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    PictureInPicture.setInPip(isInPictureInPictureMode)
  }
}
