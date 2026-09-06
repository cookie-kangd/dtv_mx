package dtv.mobile.update

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import dtv.mobile.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** 检查更新所指向的仓库（发布 Release 的地方） */
private const val UPDATE_REPO_OWNER = "cookie-kangd"
private const val UPDATE_REPO_NAME = "dtv_mx"

/** 下载到系统 Download 目录的安装包文件名前缀 / 后缀（应用内更新按此命名匹配） */
private const val APK_PREFIX = "dtv-mx-"
private const val APK_SUFFIX = ".apk"

@Serializable
private data class GitHubRelease(
  @SerialName("tag_name") val tagName: String = "",
  val name: String = "",
  val body: String = "",
  @SerialName("published_at") val publishedAt: String = "",
  @SerialName("html_url") val htmlUrl: String = "",
  val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
  val name: String = "",
  val size: Long = 0,
  @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)

class AndroidUpdateManager(
  private val appContext: Context,
) : UpdateManager {
  override var state: UpdateState by mutableStateOf<UpdateState>(UpdateState.Idle)
    private set

  override val currentVersionName: String = runCatching {
    val pm = appContext.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      pm.getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
      @Suppress("DEPRECATION")
      pm.getPackageInfo(appContext.packageName, 0)
    }
    info.versionName
  }.getOrNull().orEmpty()

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var downloadJob: Job? = null
  private val json = Json { ignoreUnknownKeys = true }
  private val client = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS)
    .build()

  override fun check() {
    if (state is UpdateState.Checking || state is UpdateState.Downloading) return
    state = UpdateState.Checking
    scope.launch {
      val result = runCatching { fetchLatestRelease() }
      state = result.fold(
        onSuccess = { release ->
          val asset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: release.assets.firstOrNull()
          if (asset == null || asset.browserDownloadUrl.isBlank()) {
            UpdateState.Failed("最新 Release 中没有可下载的 APK")
          } else {
            val latestVersion = normalizeVersion(release.tagName).ifBlank { release.name }
            if (compareVersion(latestVersion, currentVersionName) > 0) {
              AppLog.i("DTV-Update", "new version available: $latestVersion (current $currentVersionName)")
              UpdateState.Available(
                AppUpdateInfo(
                  versionName = latestVersion,
                  tagName = release.tagName,
                  releaseName = release.name,
                  downloadUrl = asset.browserDownloadUrl,
                  notes = release.body,
                  publishedAt = release.publishedAt,
                  sizeBytes = asset.size,
                  downloadSources = buildDownloadSources(asset.browserDownloadUrl),
                ),
              )
            } else {
              UpdateState.UpToDate
            }
          }
        },
        onFailure = { e ->
          AppLog.e("DTV-Update", "check failed", e)
          UpdateState.Failed(e.message ?: "检查更新失败")
        },
      )
    }
  }

  override fun download(info: AppUpdateInfo, url: String) {
    if (state is UpdateState.Downloading) return
    state = UpdateState.Downloading(0f)
    downloadJob = scope.launch {
      val result = runCatching {
        withContext(Dispatchers.IO) {
          // 缓存到系统「Download」公共目录（用户在文件管理器/下载管理中可见），
          // 而不是应用私有目录；这样即使在设置里授权安装权限后返回，也无需重新下载。
          val target = createDownloadTarget(info.versionName)
            ?: error("无法在 Download 目录创建安装包文件")
          target.outputStream.use { out ->
            val request = Request.Builder()
              .url(url)
              .header("User-Agent", "DTV-Mobile")
              .build()

            client.newCall(request).execute().use { response ->
              if (!response.isSuccessful) error("下载失败：HTTP ${response.code}")
              val body = response.body ?: error("下载失败：空响应")
              val total = body.contentLength()
              body.byteStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                var downloaded = 0L
                while (true) {
                  // 响应取消：写循环里定期检查，让「停止下载」即时生效
                  ensureActive()
                  val read = input.read(buffer)
                  if (read == -1) break
                  out.write(buffer, 0, read)
                  downloaded += read
                  if (total > 0) {
                    val progress = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    withContext(Dispatchers.Main.immediate) { state = UpdateState.Downloading(progress) }
                  }
                }
              }
            }
          }
          // API 29+ 写完后必须把 IS_PENDING 置 0，否则文件对外不可见、无法安装
          finishDownloadTarget(target)
          target.contentUri
        }
      }

      result.fold(
        onSuccess = { uri ->
          AppLog.i("DTV-Update", "downloaded: $uri")
          state = UpdateState.Downloaded(uri.toString())
          install(uri.toString())
        },
        onFailure = { e ->
          // 「停止下载」触发的取消：静默返回，不报错（缓存清理由 cancelDownload 负责）
          if (e is CancellationException) return@fold
          AppLog.e("DTV-Update", "download failed", e)
          state = UpdateState.Failed(e.message ?: "下载失败")
        },
      )
    }
  }

  override fun cancelDownload() {
    downloadJob?.cancel()
    downloadJob = null
    // 立即复位状态，UI 马上退出「下载中」
    if (state is UpdateState.Downloading) state = UpdateState.Idle
    // 删除 Download 目录里所有本应用的安装包缓存（含本次未完成的下载残留）
    scope.launch {
      withContext(Dispatchers.IO) { deleteCachedApks() }
    }
  }

  override fun install(fileUri: String) {
    runCatching {
      val uri = Uri.parse(fileUri)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        !appContext.packageManager.canRequestPackageInstalls()
      ) {
        // 未授权安装未知来源应用：跳转系统设置。
        // 关键点：保留「已下载」状态（不置为 Failed），用户授权后返回本页再次点击
        // 「安装」即可直接安装，不会再触发重新下载。
        val settingsIntent = Intent(
          android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
          android.net.Uri.parse("package:${appContext.packageName}"),
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        appContext.startActivity(settingsIntent)
        state = UpdateState.Downloaded(fileUri)
        return
      }

      val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      appContext.startActivity(intent)
    }.onFailure { e ->
      AppLog.e("DTV-Update", "install failed", e)
      state = UpdateState.Failed(e.message ?: "无法打开安装界面")
    }
  }

  override fun reset() {
    state = UpdateState.Idle
  }

  internal fun dispose() {
    runCatching { client.dispatcher.executorService.shutdown() }
    runCatching { scope.cancel() }
  }

  /**
   * 在系统「Download」公共目录为指定版本创建安装包写入目标。
   * - API 29+ 走 MediaStore.Downloads（作用域存储正确姿势，文件对用户可见）；
   * - API < 29 走应用专属外部目录（getExternalFilesDir）+ FileProvider，无需存储权限。
   * 返回可直接写入的 OutputStream 以及用于安装的 content:// Uri。
   */
  private fun createDownloadTarget(versionName: String): DownloadTarget? {
    val resolver = appContext.contentResolver
    val filename = "$APK_PREFIX$versionName$APK_SUFFIX"
    val authority = "${appContext.packageName}.fileprovider"

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
      // 先清掉同名旧文件，避免堆积
      resolver.query(
        collection,
        arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
        arrayOf(filename),
        null,
      )?.use { c ->
        while (c.moveToNext()) {
          val id = c.getLong(0)
          resolver.delete(ContentUris.withAppendedId(collection, id), null, null)
        }
      }
      val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      }
      val uri = resolver.insert(collection, values) ?: return null
      val out = resolver.openOutputStream(uri) ?: return null
      DownloadTarget(uri, out)
    } else {
      // API 23~28：写公共 Download 目录需要 WRITE_EXTERNAL_STORAGE 运行时权限，
      // 应用从未申请该权限，直接写会 Permission denied（安卓 7 平板实测）。
      // 改写应用专属外部目录（Android/data/<pkg>/files/Download）：任何系统版本都
      // 无需存储权限；安装走 FileProvider（file_paths.xml 已含 external-files-path）。
      val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: appContext.getExternalFilesDir(null)
        ?: return null
      if (!dir.exists()) dir.mkdirs()
      val file = File(dir, filename)
      if (file.exists()) file.delete()
      val uri = FileProvider.getUriForFile(appContext, authority, file)
      DownloadTarget(uri, file.outputStream())
    }
  }

  private fun finishDownloadTarget(target: DownloadTarget) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
      runCatching { appContext.contentResolver.update(target.contentUri, values, null, null) }
    }
  }

  /**
   * 清理已安装（版本 <= 当前版本）的缓存安装包。
   * 刚下载、等待安装的新版本（版本 > 当前版本）会保留，确保用户在授予安装权限返回后
   * 无需重新下载；安装完成、再次打开 App 时该缓存文件版本 == 当前版本，在此处被清理掉。
   */
  fun cleanupInstalledApks() {
    if (currentVersionName.isBlank()) return
    runCatching {
      val resolver = appContext.contentResolver
      val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Downloads.EXTERNAL_CONTENT_URI
      } else {
        MediaStore.Files.getContentUri("external")
      }
      val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
      val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
      val selectionArgs = arrayOf("$APK_PREFIX%$APK_SUFFIX")
      resolver.query(collection, projection, selection, selectionArgs, null)?.use { c ->
        val idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val nameIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        while (c.moveToNext()) {
          val name = c.getString(nameIdx)
          val ver = name.removePrefix(APK_PREFIX).removeSuffix(APK_SUFFIX)
          if (compareVersion(ver, currentVersionName) <= 0) {
            val id = c.getLong(idIdx)
            resolver.delete(ContentUris.withAppendedId(collection, id), null, null)
          }
        }
      }
      // API < 29：安装包在应用专属目录里，按文件名比对版本后清理
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        dir?.listFiles { f -> f.name.startsWith(APK_PREFIX) && f.name.endsWith(APK_SUFFIX) }
          ?.forEach { f ->
            val ver = f.name.removePrefix(APK_PREFIX).removeSuffix(APK_SUFFIX)
            if (compareVersion(ver, currentVersionName) <= 0) f.delete()
          }
      }
    }
  }

  /**
   * 删除 Download 目录里所有本应用的安装包缓存（dtv-mx-*.apk），
   * 包括未完成的下载残留（IS_PENDING 文件）。「停止下载」时调用，清除下载垃圾。
   */
  private fun deleteCachedApks() {
    runCatching {
      val resolver = appContext.contentResolver
      val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Downloads.EXTERNAL_CONTENT_URI
      } else {
        MediaStore.Files.getContentUri("external")
      }
      val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
      val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
      val selectionArgs = arrayOf("$APK_PREFIX%$APK_SUFFIX")
      resolver.query(collection, projection, selection, selectionArgs, null)?.use { c ->
        val idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        while (c.moveToNext()) {
          val id = c.getLong(idIdx)
          resolver.delete(ContentUris.withAppendedId(collection, id), null, null)
        }
      }
      // API < 29：安装包缓存在应用专属目录里（不在 MediaStore 索引），直接按文件名清理
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        dir?.listFiles { f -> f.name.startsWith(APK_PREFIX) && f.name.endsWith(APK_SUFFIX) }
          ?.forEach { it.delete() }
      }
    }
  }

  /** 组装下载来源：第一个是 github 原始地址，其余是 gh-proxy.org 系列加速镜像转换后的地址。 */
  private fun buildDownloadSources(rawUrl: String): List<DownloadSource> {
    val original = DownloadSource("github", rawUrl)
    val proxied = PROXY_MIRRORS.map { m ->
      DownloadSource(label = m.label, url = m.base + rawUrl)
    }
    return listOf(original) + proxied
  }

  private suspend fun fetchLatestRelease(): GitHubRelease = withContext(Dispatchers.IO) {
    val url = "https://api.github.com/repos/$UPDATE_REPO_OWNER/$UPDATE_REPO_NAME/releases/latest"
    val request = Request.Builder()
      .url(url)
      .header("Accept", "application/vnd.github+json")
      .header("User-Agent", "DTV-Mobile")
      .build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) error("检查更新失败：HTTP ${response.code}")
      val payload = response.body?.string() ?: error("检查更新失败：空响应")
      json.decodeFromString<GitHubRelease>(payload)
    }
  }
}

@Composable
actual fun rememberUpdateManager(): UpdateManager {
  val context = LocalContext.current
  val manager = remember(context) { AndroidUpdateManager(context.applicationContext) }
  DisposableEffect(manager) {
    onDispose { manager.dispose() }
  }
  // 进入设置页即清理掉「已安装版本」的残留缓存包，避免 Download 目录堆积
  LaunchedEffect(manager) { manager.cleanupInstalledApks() }
  return manager
}

/** 下载写入目标：用于写入字节的流 + 用于安装（content://）的 Uri */
private data class DownloadTarget(val contentUri: Uri, val outputStream: OutputStream)
