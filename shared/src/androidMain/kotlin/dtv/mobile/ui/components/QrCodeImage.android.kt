package dtv.mobile.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun QrCodeImage(
  data: String,
  modifier: Modifier,
) {
  // 二维码编码 + 逐像素填充是纯 CPU 活：以前直接写在 remember 里、在组合阶段同步跑，
  // 720×720 要调用 51.8 万次 Bitmap.setPixel()（还包括一次 ARGB_8888 的 2MB 分配），
  // 打开登录弹窗时主线程会被整个卡住。挪到后台线程并改成一次性写入像素。
  val bitmap by produceState<Bitmap?>(initialValue = null, key1 = data) {
    value = withContext(Dispatchers.Default) { generateQrBitmap(data) }
  }
  val ready = bitmap ?: return
  Image(
    bitmap = ready.asImageBitmap(),
    contentDescription = "QR",
    modifier = modifier,
  )
}

private val QR_BLACK = 0xFF000000.toInt()
private val QR_WHITE = 0xFFFFFFFF.toInt()

private fun generateQrBitmap(data: String, size: Int = 512): Bitmap? {
  if (data.isBlank()) return null
  val writer = QRCodeWriter()
  val matrix = runCatching { writer.encode(data, BarcodeFormat.QR_CODE, size, size) }.getOrNull() ?: return null
  val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
  // 先填 IntArray 再一次性写入，比逐像素 setPixel() 快一个数量级 —— 后者每个像素都要过
  // 一次 JNI 边界做边界检查和颜色转换。
  val pixels = IntArray(size * size)
  for (y in 0 until size) {
    val row = y * size
    for (x in 0 until size) {
      pixels[row + x] = if (matrix.get(x, y)) QR_BLACK else QR_WHITE
    }
  }
  bmp.setPixels(pixels, 0, size, 0, 0, size, size)
  return bmp
}

