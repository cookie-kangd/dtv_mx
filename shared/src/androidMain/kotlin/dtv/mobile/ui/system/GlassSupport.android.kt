package dtv.mobile.ui.system

import android.os.Build
import androidx.compose.runtime.Composable

/**
 * Android 实现：RenderEffect 从 API 31（Android 12）起才可用，
 * 之下 haze 无法产生实时模糊，浮岛组件退回实色底。
 */
@Composable
actual fun rememberGlassSupported(): Boolean =
  Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
