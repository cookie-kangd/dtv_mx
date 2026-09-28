package dtv.mobile.ui.player
import dtv.mobile.platform.Env2
import dtv.mobile.platform.Env1
import dtv.mobile.platform.Env3
import dtv.mobile.platform.Env4

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dtv.mobile.state.LocalVideoQuality
import dtv.mobile.state.VideoQuality
import dtv.mobile.util.AppLog
import java.io.File

/**
 * 应用级媒体缓存（仅用于点播/回放，直播不使用磁盘缓存以免增加延迟与磁盘占用）。
 * 作为单例长期持有：SimpleCache 可能被多个播放器共享，不能在单个播放器释放时关闭。
 */
private object PlayerCacheHolder {
  private const val MAX_CACHE_BYTES = 128L * 1024L * 1024L

  private var cache: SimpleCache? = null

  @Synchronized
  fun get(context: Context): SimpleCache {
    cache?.let { return it }
    val dir = File(context.cacheDir, "dtv_media_cache")
    val created = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES))
    cache = created
    return created
  }

  @Synchronized
  fun release() {
    cache?.let { runCatching { it.release() } }
    cache = null
  }
}

/** 释放播放器磁盘缓存（退出清理前调用，避免在缓存仍被持有时直接删目录导致索引损坏）。 */
fun releaseDtvMediaCache() {
  PlayerCacheHolder.release()
}

/**
 * 播放器可选增强配置：音频焦点、耳机断开暂停、GPU 友好缩放。
 * 逐项 try/catch，任何一项失败都不会影响正常起播。
 *
 * 明确不做的事：
 * - 不调用 setWakeMode(...)：它需要 android.permission.WAKE_LOCK，
 *   缺权限时会在起播获取锁的瞬间抛 SecurityException 直接闪退；
 *   PlayerView 的 keepScreenOn 已能保证播放期间屏幕常亮，无需唤醒锁。
 */
private fun ExoPlayer.applyOptionalPlayerConfig() {
  runCatching {
    // 音频焦点：来电/其他媒体播放时自动让出，避免声音叠加
    setAudioAttributes(
      AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build(),
      true,
    )
  }.onFailure { AppLog.w("DTV-Player", "setAudioAttributes failed: ${it.message}") }

  runCatching {
    // 耳机断开等"噪音"场景自动暂停，避免继续解码耗电
    setHandleAudioBecomingNoisy(true)
  }.onFailure { AppLog.w("DTV-Player", "setHandleAudioBecomingNoisy failed: ${it.message}") }

  runCatching {
    // GPU 友好的缩放模式，降低渲染开销
    videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
  }.onFailure { AppLog.w("DTV-Player", "setVideoScalingMode failed: ${it.message}") }
}

/** 构建带全部优化配置的播放器；调用方负责兜底异常。 */
private fun buildPlayer(
  context: Context,
  url: String,
  liveMode: Boolean,
  quality: VideoQuality,
): ExoPlayer {
  val headers = buildMap {
    put(
      "User-Agent",
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
    )
    val u = url.lowercase()
    when {
      "huya" in u -> put("Referer", Env2.HOST + "/")
      "bilibili" in u -> put("Referer", Env4.LIVE_HOST + "/")
      "douyin" in u -> put("Referer", Env3.HOST + "/")
      "douyu" in u -> put("Referer", Env1.HOST + "/")
    }
  }

  val httpFactory = DefaultHttpDataSource.Factory()
    .setAllowCrossProtocolRedirects(true)
    .setDefaultRequestProperties(headers)
    // 连接/读取超时从默认 8s/8s 保持紧凑：坏线路（CDN 抖动、签名失效）能更快
    // 走到 onError → 换线路/换画质重连，而不是长时间卡在无响应的连接上。
    .setConnectTimeoutMs(8_000)
    .setReadTimeoutMs(8_000)

  // 直播走纯网络源（避免磁盘缓存带来的延迟与空间占用）；点播/回放启用 128MB 磁盘缓存。
  // 缓存初始化失败时回退到纯网络源，绝不影响起播。
  val dataSourceFactory = if (liveMode) {
    httpFactory
  } else {
    runCatching {
      CacheDataSource.Factory()
        .setCache(PlayerCacheHolder.get(context.applicationContext))
        .setUpstreamDataSourceFactory(httpFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }.getOrElse {
      AppLog.w("DTV-Player", "disk cache unavailable, fallback to http: ${it.message}")
      httpFactory
    }
  }

  // 缓冲策略：直播偏小（降低延迟、快速起播），点播偏大（抗网络抖动）。
  val loadControl = if (liveMode) {
    DefaultLoadControl.Builder()
      .setBufferDurationsMs(12_000, 24_000, 1_500, 3_000)
      .setPrioritizeTimeOverSizeThresholds(true)
      .build()
  } else {
    DefaultLoadControl.Builder()
      .setBufferDurationsMs(30_000, 60_000, 2_500, 5_000)
      .setPrioritizeTimeOverSizeThresholds(true)
      .build()
  }

  // 画质档位决定允许选择的视频轨分辨率上限。
  // 「最高」档不再限制 1080p：解除分辨率上限并强制取流中最高码率，优先保证画面清晰。
  // 注意：绝不在此处按听播状态关闭视频轨，也绝不在运行期切换轨道——
  // 任何形式的重建/切轨都会重新请求直播流地址，斗鱼等平台的直链带一次性
  // 签名 token 且 CDN 对并发连接敏感，第二次连接会被拒绝或限流
  // （表现为开启听播无限加载/黑屏无声音，关闭后画面过一会卡死）。
  // 听播只做「摘掉/挂回画面」，见下方 update 逻辑。
  val trackSelector = DefaultTrackSelector(context).apply {
    setParameters(
      buildUponParameters()
        .setMaxVideoSize(quality.maxWidth, quality.maxHeight)
        .setForceHighestSupportedBitrate(quality.forceHighestSupportedBitrate)
        .build(),
    )
  }

  return ExoPlayer.Builder(context)
    // 解码器回退：个别机型硬解该直播编码（如部分 HEVC 流）失败时，
    // 自动回退到软解继续播，而不是直接抛错误黑屏。只影响异常路径，正常机型零开销。
    .setRenderersFactory(
      DefaultRenderersFactory(context).setEnableDecoderFallback(true),
    )
    .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
    .setLoadControl(loadControl)
    .setTrackSelector(trackSelector)
    .build()
    .apply {
      applyOptionalPlayerConfig()

      setMediaItem(MediaItem.fromUri(Uri.parse(url)))
      playWhenReady = true
      prepare()
    }
}

@Composable
actual fun StreamPlayer(
  url: String,
  fullscreen: Boolean,
  liveMode: Boolean,
  zoomToFill: Boolean,
  backgroundAudio: Boolean,
  onVideoAspectRatioChanged: (Float?) -> Unit,
  onError: (String) -> Unit,
  modifier: Modifier,
) {
  val context = LocalContext.current
  val quality = LocalVideoQuality.current
  // 兜底：构建播放器的任何一步失败都不允许把 App 带崩，
  // 失败时降级为"零可选配置"的最简播放器继续起播。
  // 画质档位变化时重建播放器，使新的分辨率/码率策略立即生效。
  //
  // 听播（backgroundAudio）不参与 remember key：开关听播绝不重建播放器。
  // 直播直链（尤其斗鱼）带一次性签名 token，CDN 对同一 token 的并发/二次
  // 连接敏感，重建=重新请求同一地址 → 被拒或限流（无限加载/黑屏/画面卡死）。
  // 听播只摘掉/挂回视频画面，播放器实例与网络连接完全不动。
  val player = remember(url, quality) {
    runCatching {
      buildPlayer(
        context = context,
        url = url,
        liveMode = liveMode,
        quality = quality,
      )
    }
      .getOrElse { t ->
        AppLog.e("DTV-Player", "build player failed, fallback to minimal player: url=$url", t)
        ExoPlayer.Builder(context).build().apply {
          setMediaItem(MediaItem.fromUri(Uri.parse(url)))
          playWhenReady = true
          prepare()
        }
      }
  }

  // 「熄屏听播」：前台服务保活。视频画面的摘除/挂回在下方 AndroidView 中完成，
  // 这里只负责服务生命周期。
  DisposableEffect(player, backgroundAudio) {
    if (backgroundAudio) {
      BackgroundAudioController.acquire(context)
    }
    onDispose {
      if (backgroundAudio) {
        BackgroundAudioController.release(context)
      }
    }
  }

  DisposableEffect(player, url) {
    val listener = object : Player.Listener {
      override fun onPlayerError(error: PlaybackException) {
        AppLog.e("DTV-Player", "ExoPlayer error url=$url code=${error.errorCodeName}", error)
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
          // live HLS sometimes falls behind; reset and retry
          runCatching {
            player.seekToDefaultPosition()
            player.prepare()
          }
          return
        }

        val causeMsg = generateSequence(error.cause) { it.cause }
          .mapNotNull { it.message }
          .firstOrNull { it.contains("Invalid input to toASCII") }

        if (causeMsg != null && url.startsWith("https://")) {
          // Workaround for some CDN hosts containing '_' which breaks IDN/SNI on Android.
          AppLog.w("DTV-Player", "retrying with http due to toASCII failure. url=$url")
          onError("__retry_http__:" + url.replaceFirst("https://", "http://"))
          return
        }

        onError(error.message ?: "播放器错误: ${error.errorCodeName}")
      }

      override fun onTracksChanged(tracks: Tracks) {
        val selectedVideo = tracks.groups
          .filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
          .flatMap { g -> (0 until g.length).mapNotNull { idx -> if (g.isTrackSelected(idx)) g.getTrackFormat(idx) else null } }
        val selectedAudio = tracks.groups
          .filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
          .flatMap { g -> (0 until g.length).mapNotNull { idx -> if (g.isTrackSelected(idx)) g.getTrackFormat(idx) else null } }

        val videoDesc = selectedVideo.firstOrNull()?.let { f ->
          "mime=${f.sampleMimeType} codecs=${f.codecs} ${f.width}x${f.height}"
        } ?: "none"
        val audioDesc = selectedAudio.firstOrNull()?.let { f ->
          "mime=${f.sampleMimeType} codecs=${f.codecs} ch=${f.channelCount} sr=${f.sampleRate}"
        } ?: "none"
        AppLog.i("DTV-Player", "tracks url=$url video=$videoDesc audio=$audioDesc")
      }

      override fun onVideoSizeChanged(videoSize: VideoSize) {
        val rawW = videoSize.width
        val rawH = videoSize.height
        val rotation = videoSize.unappliedRotationDegrees
        val pixelRatio = videoSize.pixelWidthHeightRatio

        val (w, h) = if (rotation == 90 || rotation == 270) rawH to rawW else rawW to rawH
        val aspect = if (w > 0 && h > 0) (w.toFloat() * pixelRatio) / h.toFloat() else null

        AppLog.i(
          "DTV-Player",
          "video size url=$url size=${rawW}x${rawH} rotation=$rotation pixelRatio=$pixelRatio aspect=$aspect",
        )
        onVideoAspectRatioChanged(aspect)
      }

      override fun onRenderedFirstFrame() {
        AppLog.i("DTV-Player", "rendered first frame url=$url")
      }
    }
    player.addListener(listener)
    onDispose {
      // 1) 先摘除监听：销毁时 stop()/release() 仍会派发 onVideoSizeChanged 等回调，
      //    若回调回播放页会造成退出瞬间的状态抖动，甚至重新拉起播放器（表现为要点两次返回）。
      player.removeListener(listener)
      // 2) 再彻底销毁播放器：停止播放 → 清空媒体源 → 释放解码器/音频等底层资源，
      //    确保关闭播放页后不再残留任何播放资源占用。
      AppLog.i("DTV-Player", "destroying player url=$url")
      runCatching {
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        player.release()
      }
      AppLog.i("DTV-Player", "player destroyed url=$url")
    }
  }

  // key(player)：播放器重建（换画质/换线路）时，把 PlayerView 与其内部
  // SurfaceView 一并换新，避免旧 surface 复用带来的渲染隐患。
  //
  // 听播的「纯音频」通过 update 里的 clearVideoSurface 实现：
  // - 开启听播 → view.player 置 null 并 clearVideoSurface()，视频解码停止出画面，
  //   音频/网络连接完全不受影响（不重连、不切轨、不重建）；
  // - 关闭听播 → view.player 重新挂回，ExoPlayer 用全新 surface 恢复视频渲染。
  // 这是 ExoPlayer 官方的后台播放姿势，对所有平台/流一致，不存在斗鱼直链
  // 二次请求被 CDN 拒绝的问题。
  key(player) {
    AndroidView(
      modifier = modifier,
      factory = {
        PlayerView(it).apply {
          useController = false
          controllerAutoShow = false
          setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
          resizeMode = if (zoomToFill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
          // Keep screen on during playback (some Android 16 devices will otherwise follow a short system timeout).
          keepScreenOn = true
          this.player = player
        }
      },
      onRelease = { view ->
        // 复位屏幕常亮，避免离开播放页后系统仍保持常亮耗电
        view.keepScreenOn = false
        // Detach the player before the view is recycled so the composable-level
        // release below is the only owner of the ExoPlayer lifecycle.
        if (view.player === player) {
          view.player = null
        }
      },
      update = { view ->
        // 先判断是否真的有变化：播放页每 120ms 弹幕批就会重组一次，
        // 无条件的 requestLayout+invalidate 会让播放视图整帧白做一次
        // 测量/布局/重绘（CPU 白烧）。仅在画质档位（resizeMode）、
        // 听播开关（keepScreenOn）、播放器实例任一变化时才触发。
        val targetResizeMode =
          if (zoomToFill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        val targetKeepScreenOn = !backgroundAudio
        val targetPlayer: Player? = if (backgroundAudio) null else player
        val changed = view.resizeMode != targetResizeMode ||
          view.keepScreenOn != targetKeepScreenOn ||
          view.player !== targetPlayer

        view.resizeMode = targetResizeMode
        view.useController = false
        view.controllerAutoShow = false
        // 听播时允许熄屏（画面已摘除，屏幕常亮毫无意义）
        view.keepScreenOn = targetKeepScreenOn
        if (view.player !== targetPlayer) {
          view.player = targetPlayer
          if (targetPlayer == null) {
            // 显式清掉播放器上的输出 surface：视频解码停止出画面，
            // 音频与网络连接保持原样（不重连直播流）。
            runCatching { player.clearVideoSurface() }
          }
        }
        if (changed && targetPlayer != null) {
          view.post {
            view.requestLayout()
            view.invalidate()
          }
        }
      },
    )
  }
}
