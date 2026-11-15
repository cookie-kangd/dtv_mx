package dtv.mobile.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 卡帧看门狗判定回归测试。
 *
 * 这些用例对应「画面不动但有弹幕」的各类成因，以及不能误伤的正常场景。
 */
class PlaybackWatchdogTest {

  private fun decide(
    nowMs: Long = 100_000L,
    lastFrameAtMs: Long = nowMs - 1_000L,
    framesEverRendered: Boolean = true,
    playWhenReady: Boolean = true,
    isPlaying: Boolean = true,
    hasPlayerError: Boolean = false,
    appForeground: Boolean = true,
    listenOnly: Boolean = false,
    lastHealthyAtMs: Long = nowMs,
    stallTimeoutMs: Long = 12_000L,
    firstFrameTimeoutMs: Long = 30_000L,
  ) = decideWatchdogAction(
    nowMs = nowMs,
    lastFrameAtMs = lastFrameAtMs,
    framesEverRendered = framesEverRendered,
    playWhenReady = playWhenReady,
    isPlaying = isPlaying,
    hasPlayerError = hasPlayerError,
    appForeground = appForeground,
    listenOnly = listenOnly,
    lastHealthyAtMs = lastHealthyAtMs,
    stallTimeoutMs = stallTimeoutMs,
    firstFrameTimeoutMs = firstFrameTimeoutMs,
  )

  @Test
  fun normalPlaybackWithFreshFramesIsHealthy() {
    assertEquals(WatchdogAction.Healthy, decide(lastFrameAtMs = 100_000L - 500L))
  }

  @Test
  fun cdnSilentDisconnectStopsFramesAndTriggersRecovery() {
    // 时间一长直播直链被掐断：帧停在 13 秒前，弹幕仍在刷新
    assertEquals(
      WatchdogAction.Recover,
      decide(lastFrameAtMs = 100_000L - 13_000L),
    )
  }

  @Test
  fun justBeforeTimeoutDoesNotTrigger() {
    assertEquals(
      WatchdogAction.Healthy,
      decide(lastFrameAtMs = 100_000L - 11_000L),
    )
  }

  @Test
  fun audioFocusLossInForegroundIsTreatedAsStall() {
    // AUDIOFOCUS_LOSS 会把 playWhenReady 永久置 false 且不再自动恢复：
    // 旧看门狗把它当成「预期暂停」跳过，画面会一直定格。
    assertEquals(
      WatchdogAction.Recover,
      decide(
        lastFrameAtMs = 100_000L - 13_000L,
        playWhenReady = false,
        isPlaying = false,
      ),
    )
  }

  @Test
  fun backgroundedAppDoesNotTrigger() {
    assertEquals(
      WatchdogAction.ResetGrace,
      decide(
        lastFrameAtMs = 100_000L - 60_000L,
        playWhenReady = false,
        isPlaying = false,
        appForeground = false,
      ),
    )
  }

  @Test
  fun listenOnlyModeDoesNotTrigger() {
    assertEquals(
      WatchdogAction.ResetGrace,
      decide(lastFrameAtMs = 100_000L - 60_000L, listenOnly = true),
    )
  }

  @Test
  fun playerErrorIsLeftToOnPlayerError() {
    assertEquals(
      WatchdogAction.ResetGrace,
      decide(lastFrameAtMs = 100_000L - 60_000L, hasPlayerError = true),
    )
  }

  @Test
  fun initialBufferingGetsLongerGrace() {
    // 起播 25 秒还没出帧，但仍未超过 30 秒宽限，不打断解析/缓冲
    assertEquals(
      WatchdogAction.Healthy,
      decide(
        framesEverRendered = false,
        lastFrameAtMs = 0L,
        isPlaying = false,
        lastHealthyAtMs = 100_000L - 25_000L,
      ),
    )
  }

  @Test
  fun neverRenderedAFrameAndNeverPlayedTriggersRecovery() {
    assertEquals(
      WatchdogAction.Recover,
      decide(
        framesEverRendered = false,
        lastFrameAtMs = 0L,
        isPlaying = false,
        lastHealthyAtMs = 100_000L - 31_000L,
      ),
    )
  }

  @Test
  fun tunnelingPlaybackWithoutFrameCallbackIsNotMisjudged() {
    // 逐帧回调在隧道解码下不触发，但音频/播放状态正常：不能反复重建播放器
    assertEquals(
      WatchdogAction.Healthy,
      decide(
        framesEverRendered = false,
        lastFrameAtMs = 0L,
        isPlaying = true,
        lastHealthyAtMs = 100_000L - 120_000L,
      ),
    )
  }

  @Test
  fun freshHeartbeatAfterRecoveryDoesNotImmediatelyRetrigger() {
    assertEquals(
      WatchdogAction.Healthy,
      decide(lastFrameAtMs = 100_000L - 100L, lastHealthyAtMs = 100_000L),
    )
  }
}
