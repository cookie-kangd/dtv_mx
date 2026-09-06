package dtv.mobile.ui.system

import androidx.compose.runtime.Composable

/**
 * 当前设备是否支持实时模糊（毛玻璃）。
 *
 * Android 上毛玻璃依赖 RenderEffect（API 31+ 才提供）；API 31 以下 haze 只会
 * 渲染半透明 tint 而没有模糊，浮岛底栏直接透出滚动内容，视觉上等同样式异常。
 * 不支持时组件应退回高不透明度实色底（接近毛玻璃观感），保证可读性。
 */
@Composable
expect fun rememberGlassSupported(): Boolean
