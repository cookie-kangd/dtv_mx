package dtv.mobile.ui.system

import androidx.compose.runtime.Composable

/**
 * 监听应用整体是否处于前台（STARTED 及以上）。
 *
 * 播放页用它暂停后台播放并断开弹幕；系统画中画仍属于 STARTED，不会被误暂停。
 */
@Composable
expect fun AppForegroundEffect(onForegroundChanged: (Boolean) -> Unit)
