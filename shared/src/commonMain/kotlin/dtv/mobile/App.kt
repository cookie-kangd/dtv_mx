package dtv.mobile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import dtv.mobile.repo.DtvRepository
import dtv.mobile.repo.fake.FakeDtvRepository
import dtv.mobile.state.InMemorySubscriptionStore
import dtv.mobile.state.LocalVideoQuality
import dtv.mobile.state.Screen
import dtv.mobile.state.rememberAppState
import dtv.mobile.state.SubscriptionStore
import dtv.mobile.theme.DtvTheme
import dtv.mobile.ui.components.CompactCardMetrics
import dtv.mobile.ui.components.DtvBackground
import dtv.mobile.ui.components.LocalCardMetrics
import dtv.mobile.ui.components.NormalCardMetrics
import dtv.mobile.ui.RootScaffold
import dtv.mobile.ui.system.HighRefreshRateEffect

@Composable
fun App(
  repo: DtvRepository = FakeDtvRepository(),
  subscriptionStore: SubscriptionStore = InMemorySubscriptionStore,
) {
  val appState = rememberAppState(repo = repo, subscriptionStore = subscriptionStore)
  val cardMetrics = if (appState.compactCardEnabled) CompactCardMetrics else NormalCardMetrics
  DtvTheme(themeMode = appState.themeMode, accentColorHex = appState.accentColorHex) {
    CompositionLocalProvider(
      LocalCardMetrics provides cardMetrics,
      LocalVideoQuality provides appState.videoQuality,
    ) {
      Surface(modifier = Modifier.fillMaxSize()) {
        // 屏幕刷新率适配：
        // - 普通页面按用户设置决定是否跟随系统最高刷新率；
        // - 播放页固定 60Hz。直播绝大多数是 30/60fps，继续以 90/120/144Hz
        //   扫描输出只会增加 GPU/显示功耗，不会增加实际画面帧数。
        HighRefreshRateEffect(
          enabled = appState.highRefreshEnabled && appState.currentScreen != Screen.Player,
        )
        DtvBackground { RootScaffold(appState = appState) }
      }
    }
  }
}
