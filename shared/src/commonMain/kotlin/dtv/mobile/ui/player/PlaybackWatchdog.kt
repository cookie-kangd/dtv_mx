package dtv.mobile.ui.player

/**
 * 播放健康判定的结果。
 *
 * - [Healthy]：播放正常，继续采样。
 * - [ResetGrace]：此时「没有画面」是预期行为，需要把心跳与帧时间戳一并刷新，
 *   避免恢复播放后把暂停期间的空白误判成卡死。
 * - [Recover]：判定画面卡死，上层需要重取播放地址并重建播放器。
 */
internal enum class WatchdogAction {
  Healthy,
  ResetGrace,
  Recover,
}

/**
 * 直播卡帧判定（纯逻辑，不依赖 Android / Media3，便于单测覆盖）。
 *
 * 背景：直播直链失效 / CDN 静默断链时，ExoPlayer 常常既不回调 onPlayerError，
 * 也不退出 BUFFERING，最后一帧一直挂在屏幕上；弹幕是独立 WebSocket，仍在刷新。
 * 单靠「有没有 error」判断不了，必须看「还有没有新视频帧」。
 *
 * @param lastFrameAtMs 最近一次真实视频帧渲染的时刻（SystemClock.elapsedRealtime）。
 * @param lastHealthyAtMs 最近一次「确实在播」或「预期跳过」的时刻；只有它停止刷新，
 *   「从未出帧」的兜底才会累计超时。
 */
internal fun decideWatchdogAction(
  nowMs: Long,
  lastFrameAtMs: Long,
  framesEverRendered: Boolean,
  playWhenReady: Boolean,
  isPlaying: Boolean,
  hasPlayerError: Boolean,
  appForeground: Boolean,
  listenOnly: Boolean,
  lastHealthyAtMs: Long,
  stallTimeoutMs: Long = 12_000L,
  firstFrameTimeoutMs: Long = 30_000L,
): WatchdogAction {
  // 预期内没有画面：切后台（lifecycle 已 pause）、熄屏听播（主动摘掉画面）、
  // 或播放器已抛错（交给 onPlayerError 恢复，避免重复触发）。
  // 注意不要把「前台 playWhenReady=false」也算进来：AUDIOFOCUS_LOSS 会把
  // playWhenReady 永久置 false 且不再自动恢复，正是画面定格的成因之一。
  if (!appForeground || listenOnly || hasPlayerError) return WatchdogAction.ResetGrace

  if (framesEverRendered) {
    // 已经出过画面：以「最后一帧」为准。CDN 断链、解码器卡住、直播窗口落后，
    // 以及音频焦点被永久抢走导致帧停止，都会落到这里。
    return if (nowMs - lastFrameAtMs >= stallTimeoutMs) {
      WatchdogAction.Recover
    } else {
      WatchdogAction.Healthy
    }
  }

  // 一帧都没渲染过：Media3 的逐帧回调在少数解码路径（隧道模式）不触发。
  // 给起播 / 软解回退更长的宽限，且只在「确实没在播」时才恢复，
  // 避免把隧道模式下的正常播放误判成卡死、造成反复重新缓冲。
  val noPlaybackTooLong = nowMs - lastHealthyAtMs >= firstFrameTimeoutMs
  return if (noPlaybackTooLong && (!isPlaying || !playWhenReady)) {
    WatchdogAction.Recover
  } else {
    WatchdogAction.Healthy
  }
}
