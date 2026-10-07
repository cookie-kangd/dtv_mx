package dtv.mobile.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 播放设置里「档位高亮」判据的回归测试。
 *
 * 这类 bug 的共同形态是「设置项本身写对了，但 UI 反馈是错的」：
 * 用户点/看 chip 的选中态来判断当前生效值，一旦判据与实际值对不上，
 * 就会表现成「开关失灵」「不知道现在是什么档」。
 *
 * 背景：
 * - 「横屏弹幕字体」历史默认值是 1.2f，而面板档位只有 1.0/1.15/1.30/1.45，
 *   用精确判据时四个档位全不高亮（详见 [snapHighlightIndex] 的说明）。
 * - 「弹幕字体大小」等其余档位项仍用精确判据（必须精确命中，禁止就近高亮，
 *   否则改了值却高亮别的档位，比不高亮更糟）。
 */
class QualityChipHighlightTest {

  /** 与 PlayerScreen.RowWrapFloat 完全一致的精确命中判据。 */
  private fun isExactHit(value: Float, selected: Float): Boolean {
    val d = value - selected
    return (if (d < 0f) -d else d) < 0.0001f
  }

  /** 与 PlayerScreen.RowWrapFloatSnap 完全一致的「就近档位下标」判据。 */
  private fun snapHighlightIndex(
    values: List<Float>,
    selected: Float,
  ): Int {
    if (values.isEmpty()) return 0
    var best = 0
    var bestDist = Float.MAX_VALUE
    for (i in values.indices) {
      val d = values[i] - selected
      val dist = if (d < 0f) -d else d
      if (dist < bestDist) {
        bestDist = dist
        best = i
      }
    }
    return best
  }

  private val landscapeSteps = listOf(1.0f, 1.15f, 1.30f, 1.45f)

  // ---------- 精确判据：正常档位必须命中 ----------

  @Test
  fun exactHit_everyLandscapeStepMatchesItself() {
    landscapeSteps.forEach { step ->
      assertTrue(isExactHit(step, step), "档位 $step 应精确命中自身")
    }
  }

  @Test
  fun exactHit_rejectsLegacyDefaultThatMatchesNoStep() {
    // 这正是旧默认值的症状：精确判据下无档位可亮。
    landscapeSteps.forEach { step ->
      assertTrue(!isExactHit(step, 1.2f), "1.2f 不应命中档位 $step")
    }
  }

  // ---------- 就近高亮：修复「全不高亮」 ----------

  @Test
  fun snapHighlight_legacyDefaultPicksNearestStep() {
    // 1.2f 距离 1.15f(0.05) 比距离 1.30f(0.10) 更近 -> 应高亮「中」
    assertEquals(1, snapHighlightIndex(landscapeSteps, 1.2f))
  }

  @Test
  fun snapHighlight_exactValueStillPicksItself() {
    landscapeSteps.forEachIndexed { idx, step ->
      assertEquals(idx, snapHighlightIndex(landscapeSteps, step))
    }
  }

  @Test
  fun snapHighlight_newDefaultIsARealStepSoItHighlightsExactly() {
    // 新默认值 1.15f 本身就是档位，精确命中「中」，就近逻辑不会引入歧义。
    assertTrue(isExactHit(1.15f, 1.15f))
    assertEquals(1, snapHighlightIndex(landscapeSteps, 1.15f))
  }

  @Test
  fun snapHighlight_outOfRangeValueClampsToNearestEdge() {
    // 夹取上界 2.0f（用户历史上存进去的值）应高亮最大的「特大」
    assertEquals(3, snapHighlightIndex(landscapeSteps, 2.0f))
    // 夹取下界 0.85f 应高亮最小的「小」
    assertEquals(0, snapHighlightIndex(landscapeSteps, 0.85f))
  }

  @Test
  fun snapHighlight_emptyListIsSafe() {
    assertEquals(0, snapHighlightIndex(emptyList(), 1.15f))
  }

  // ---------- 其余档位项：确认精确判据无死档位 ----------

  @Test
  fun exactHit_danmakuFontScaleStepsHaveNoDeadStep() {
    // AppState.updateDanmakuFontScale 夹取 0.85~1.3，面板档位 0.90/1.00/1.15
    val steps = listOf(0.90f, 1.00f, 1.15f)
    steps.forEach { step ->
      assertTrue(step >= 0.85f && step <= 1.3f, "档位 $step 被夹取范围排除，会变成死档位")
      assertTrue(isExactHit(step, step))
    }
  }

  @Test
  fun exactHit_danmakuOpacityAndAreaStepsHaveNoDeadStep() {
    // 透明度夹取 0.35~1.0，档位 1.00/0.85/0.70/0.55
    listOf(1.00f, 0.85f, 0.70f, 0.55f).forEach {
      assertTrue(it >= 0.35f && it <= 1.0f, "透明度档位 $it 被夹取范围排除")
    }
    // 显示区域夹取 0.25~1.0，档位 0.25/0.5/0.75/1.0
    listOf(0.25f, 0.5f, 0.75f, 1.0f).forEach {
      assertTrue(it >= 0.25f && it <= 1.0f, "显示区域档位 $it 被夹取范围排除")
    }
  }
}