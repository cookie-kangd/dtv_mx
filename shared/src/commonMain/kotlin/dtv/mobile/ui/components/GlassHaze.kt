package dtv.mobile.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import dev.chrisbanes.haze.HazeState

/**
 * RootScaffold 在根部注册的共享 HazeState（整个内容层都暴露给它）。
 *
 * 深层的浮层组件（播放器设置抽屉等）通过读取它来把自身渲染成
 * 「真·毛玻璃」：背后的视频/页面内容会被实时模糊透出。
 * 未提供时（值为 null）组件需退回半透明纯色，保证低版本系统
 * 或非浮岛场景下的表现一致。
 */
val LocalGlassHaze = staticCompositionLocalOf<HazeState?> { null }
