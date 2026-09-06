package dtv.mobile.ui.system

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
actual fun FullscreenEffect(
  enabled: Boolean,
  lockLandscape: Boolean,
  exitToPortrait: Boolean,
  forcePortrait: Boolean,
) {
  val view = LocalView.current
  val activity = view.context.findActivity() ?: return
  val window = activity.window

  DisposableEffect(view, window, enabled, lockLandscape, exitToPortrait, forcePortrait) {
    val controller = WindowCompat.getInsetsController(window, view)
    val prevBehavior = controller.systemBarsBehavior
    // 记录进入前的方向以便退出时还原。若拿到的是横屏值（例如上一个效果异常结束留下的），
    // 则回落到 UNSPECIFIED，否则会把"横屏"固化成 App 的静止方向，
    // 导致退出 App 后桌面/系统栏也跟着横屏。
    val prevOrientation = activity.requestedOrientation
      .takeUnless { it.isLandscapeOrientation() }
      ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

    if (forcePortrait) {
      // 强制竖屏：未开启「默认横屏」且进房时设备处于横屏（平板等）时，
      // 直接锁定竖屏，不跟随系统方向；系统栏保持显示（普通竖屏播放器样式）。
      controller.show(WindowInsetsCompat.Type.systemBars())
      activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    } else if (enabled) {
      controller.hide(WindowInsetsCompat.Type.systemBars())
      // Some devices ignore hide() during rotation/layout. Post a second attempt.
      view.post { controller.hide(WindowInsetsCompat.Type.systemBars()) }
      if (lockLandscape) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      }
    } else {
      controller.show(WindowInsetsCompat.Type.systemBars())
      view.post { controller.show(WindowInsetsCompat.Type.systemBars()) }
      if (exitToPortrait) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
      } else if (lockLandscape) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
      }
    }

    onDispose {
      controller.systemBarsBehavior = prevBehavior
      controller.show(WindowInsetsCompat.Type.systemBars())
      activity.requestedOrientation = prevOrientation
    }
  }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}

/** 判断某个 requestedOrientation 是否会把界面锁定为横屏。 */
private fun Int.isLandscapeOrientation(): Boolean = when (this) {
  ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
  ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
  ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
  ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
  -> true

  else -> false
}
