package dtv.mobile.ui.system

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

@Composable
actual fun AppForegroundEffect(onForegroundChanged: (Boolean) -> Unit) {
  val lifecycleOwner = LocalContext.current.findActivity() as? LifecycleOwner
  val latestCallback by rememberUpdatedState(onForegroundChanged)

  DisposableEffect(lifecycleOwner) {
    if (lifecycleOwner == null) {
      onDispose {}
    } else {
      val observer = LifecycleEventObserver { _, event ->
        when (event) {
          Lifecycle.Event.ON_START -> latestCallback(true)
          Lifecycle.Event.ON_STOP -> latestCallback(false)
          else -> Unit
        }
      }
      lifecycleOwner.lifecycle.addObserver(observer)
      // 首帧主动同步一次，避免从后台恢复后短暂沿用旧状态。
      latestCallback(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))

      onDispose {
        lifecycleOwner.lifecycle.removeObserver(observer)
      }
    }
  }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}
