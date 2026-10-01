package dtv.mobile.platform.douyin

import dtv.mobile.util.currentTimeMillis
import kotlin.math.ceil

private fun rc4Encrypt(plaintext: String, key: String): String {
  val pbytes = plaintext.map { (it.code and 0xFF).toByte() }
  val kbytes = key.map { (it.code and 0xFF).toByte() }
  if (kbytes.isEmpty()) return plaintext

  val s = IntArray(256) { it }
  var j = 0
  for (i in 0 until 256) {
    j = (j + s[i] + (kbytes[i % kbytes.size].toInt() and 0xFF)) and 0xFF
    val tmp = s[i]
    s[i] = s[j]
    s[j] = tmp
  }

  var i = 0
  j = 0
  val out = StringBuilder(pbytes.size)
  for (ch in pbytes) {
    i = (i + 1) and 0xFF
    j = (j + s[i]) and 0xFF
    val tmp = s[i]
    s[i] = s[j]
    s[j] = tmp
    val t = (s[i] + s[j]) and 0xFF
    val o = (ch.toInt() and 0xFF) xor s[t]
    out.append((o and 0xFF).toChar())
  }
  return out.toString()
}

private fun leftRotate(x: Int, n: Int): Int {
  val r = n and 31
  if (r == 0) return x
  return (x shl r) or (x ushr (32 - r))
}

private fun getTJ(j: Int): Int = if (j < 16) 0x79CC4519 else 0x7A879D8A

private fun ffJ(j: Int, x: Int, y: Int, z: Int): Int = if (j < 16) x xor y xor z else (x and y) or (x and z) or (y and z)

private fun ggJ(j: Int, x: Int, y: Int, z: Int): Int = if (j < 16) x xor y xor z else (x and y) or (x.inv() and z)

private class Sm3 {
  private var reg = intArrayOf(
    1937774191,
    1226093241,
    388252375,
    -628488704,
    -1452330820,
    372324522,
    -477237683,
    -1325724082,
  )

  private var chunk = ArrayList<Byte>(64)
  private var size = 0

  private fun reset() {
    reg = intArrayOf(
      1937774191,
      1226093241,
      388252375,
      -628488704,
      -1452330820,
      372324522,
      -477237683,
      -1325724082,
    )
    chunk.clear()
    size = 0
  }

  private fun write(data: ByteArray) {
    size += data.size
    var offset = 0
    while (offset < data.size) {
      val needed = 64 - chunk.size
      val take = minOf(needed, data.size - offset)
      for (i in 0 until take) chunk.add(data[offset + i])
      offset += take
      if (chunk.size == 64) {
        compressBlock(chunk.toByteArray())
        chunk.clear()
      }
    }
  }

  private fun fill() {
    val bitLength = size.toLong() * 8L
    chunk.add(0x80.toByte())
    while ((chunk.size % 64) != 56) chunk.add(0)
    val lenBytes = ByteArray(8)
    for (i in 0 until 8) {
      lenBytes[7 - i] = ((bitLength ushr (i * 8)) and 0xFF).toByte()
    }
    for (b in lenBytes) chunk.add(b)
  }

  private fun compressBlock(block: ByteArray) {
    if (block.size < 64) return

    val w = IntArray(132)
    for (t in 0 until 16) {
      val i = 4 * t
      w[t] =
        ((block[i].toInt() and 0xFF) shl 24) or
          ((block[i + 1].toInt() and 0xFF) shl 16) or
          ((block[i + 2].toInt() and 0xFF) shl 8) or
          (block[i + 3].toInt() and 0xFF)
    }
    for (j in 16 until 68) {
      var a = w[j - 16] xor w[j - 9] xor leftRotate(w[j - 3], 15)
      a = a xor leftRotate(a, 15) xor leftRotate(a, 23)
      w[j] = a xor leftRotate(w[j - 13], 7) xor w[j - 6]
    }
    for (j in 0 until 64) {
      w[j + 68] = w[j] xor w[j + 4]
    }

    var a = reg[0]
    var b = reg[1]
    var c = reg[2]
    var d = reg[3]
    var e = reg[4]
    var f = reg[5]
    var g = reg[6]
    var h = reg[7]

    for (j in 0 until 64) {
      val ss1 = leftRotate(leftRotate(a, 12) + e + leftRotate(getTJ(j), j), 7)
      val ss2 = ss1 xor leftRotate(a, 12)
      val tt1 = ffJ(j, a, b, c) + d + ss2 + w[j + 68]
      val tt2 = ggJ(j, e, f, g) + h + ss1 + w[j]

      d = c
      c = leftRotate(b, 9)
      b = a
      a = tt1
      h = g
      g = leftRotate(f, 19)
      f = e
      e = tt2 xor leftRotate(tt2, 9) xor leftRotate(tt2, 17)
    }

    reg[0] = reg[0] xor a
    reg[1] = reg[1] xor b
    reg[2] = reg[2] xor c
    reg[3] = reg[3] xor d
    reg[4] = reg[4] xor e
    reg[5] = reg[5] xor f
    reg[6] = reg[6] xor g
    reg[7] = reg[7] xor h
  }

  fun sumBytes(data: ByteArray): ByteArray {
    reset()
    write(data)
    fill()
    val all = chunk.toByteArray()
    for (block in all.asList().chunked(64)) {
      compressBlock(block.toByteArray())
    }
    val out = ByteArray(32)
    var offset = 0
    for (c in reg) {
      out[offset++] = ((c ushr 24) and 0xFF).toByte()
      out[offset++] = ((c ushr 16) and 0xFF).toByte()
      out[offset++] = ((c ushr 8) and 0xFF).toByte()
      out[offset++] = (c and 0xFF).toByte()
    }
    reset()
    return out
  }
}

private fun sm3Sum(data: ByteArray): ByteArray = Sm3().sumBytes(data)

private fun getLongInt(roundNum: Int, longStr: String): Int {
  val chars = longStr.map { it.code }
  val i = roundNum * 3
  val b1 = chars.getOrNull(i) ?: 0
  val b2 = chars.getOrNull(i + 1) ?: 0
  val b3 = chars.getOrNull(i + 2) ?: 0
  return (b1 shl 16) or (b2 shl 8) or b3
}

private fun resultEncrypt(longStr: String, tableNum: String): String {
  val encodingTables = mapOf(
    "s0" to "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=",
    "s1" to "Dkdpgh4ZKsQB80/Mfvw36XI1R25+WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe=",
    "s2" to "Dkdpgh4ZKsQB80/Mfvw36XI1R25-WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe=",
    "s3" to "ckdp1h4ZKsUB80/Mfvw36XIgR25+WQAlEi7NLboqYTOPuzmFjJnryx9HVGDaStCe",
    "s4" to "Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe",
  )
  val masks = intArrayOf(0xFC0000, 0x3F000, 0xFC0, 0x3F)
  val shifts = intArrayOf(18, 12, 6, 0)
  val table = encodingTables[tableNum]?.encodeToByteArray() ?: encodingTables.getValue("s0").encodeToByteArray()

  val charLen = longStr.length
  var roundNum = 0
  var longInt = getLongInt(roundNum, longStr)
  val totalChars = ceil((charLen / 3.0) * 4.0).toInt()
  val out = StringBuilder(totalChars)
  for (i in 0 until totalChars) {
    if (i / 4 != roundNum) {
      roundNum += 1
      longInt = getLongInt(roundNum, longStr)
    }
    val idx = i % 4
    val charIndex = ((longInt and masks[idx]) ushr shifts[idx]) and 0x3F
    out.append((table[charIndex].toInt() and 0xFF).toChar())
  }
  return out.toString()
}

private fun generRandom(randomNum: Int, option: IntArray): ByteArray {
  val byte1 = randomNum and 255
  val byte2 = (randomNum shr 8) and 255
  return byteArrayOf(
    ((byte1 and 170) or (option[0] and 85)).toByte(),
    ((byte1 and 85) or (option[0] and 170)).toByte(),
    ((byte2 and 170) or (option[1] and 85)).toByte(),
    ((byte2 and 85) or (option[1] and 170)).toByte(),
  )
}

private fun generateRandomStr(): String {
  val vals = doubleArrayOf(0.123456789, 0.987654321, 0.555555555)
  val bytes = ArrayList<Byte>(12)
  bytes.addAll(generRandom((vals[0] * 10000.0).toInt(), intArrayOf(3, 45)).toList())
  bytes.addAll(generRandom((vals[1] * 10000.0).toInt(), intArrayOf(1, 0)).toList())
  bytes.addAll(generRandom((vals[2] * 10000.0).toInt(), intArrayOf(1, 5)).toList())
  return bytes.map { (it.toInt() and 0xFF).toChar() }.joinToString("")
}

private fun generateRc4BbStr(
  urlSearchParams: String,
  userAgent: String,
  windowEnvStr: String,
  suffix: String,
  arguments: IntArray,
): String {
  // 签名要把绝对时间写进 b[10]（endTime）/ b[16]（startTime），所以必须是墙上时钟，
  // 不能用单调时钟顶替。经 expect/actual 取，避免 commonMain 直接依赖 JVM 的 System。
  val startTime = currentTimeMillis()
  val urlList = sm3Sum(sm3Sum((urlSearchParams + suffix).encodeToByteArray()))
  val cusOnce = sm3Sum(suffix.encodeToByteArray())
  val cus = sm3Sum(cusOnce)
  val uaKey = byteArrayOf(0, 1, 14).decodeToString()
  val ua = sm3Sum(resultEncrypt(rc4Encrypt(userAgent, uaKey), "s3").encodeToByteArray())
  val endTime = startTime + 100

  val b = LongArray(80)
  b[8] = 3
  b[10] = endTime
  b[16] = startTime
  b[18] = 44

  fun splitToBytes(num: Long): LongArray {
    return longArrayOf(
      (num shr 24) and 255,
      (num shr 16) and 255,
      (num shr 8) and 255,
      num and 255,
    )
  }

  val stBytes = splitToBytes(b[16])
  b[20] = stBytes[0]
  b[21] = stBytes[1]
  b[22] = stBytes[2]
  b[23] = stBytes[3]
  b[24] = (b[16] / 256 / 256 / 256 / 256) and 255
  b[25] = (b[16] / 256 / 256 / 256 / 256 / 256) and 255

  val arg0Bytes = splitToBytes(arguments[0].toLong())
  b[26] = arg0Bytes[0]
  b[27] = arg0Bytes[1]
  b[28] = arg0Bytes[2]
  b[29] = arg0Bytes[3]

  b[30] = (arguments[1] / 256).toLong() and 255
  b[31] = (arguments[1] % 256).toLong() and 255

  val arg1Bytes = splitToBytes(arguments[1].toLong())
  b[32] = arg1Bytes[0]
  b[33] = arg1Bytes[1]

  val arg2Bytes = splitToBytes(arguments[2].toLong())
  b[34] = arg2Bytes[0]
  b[35] = arg2Bytes[1]
  b[36] = arg2Bytes[2]
  b[37] = arg2Bytes[3]

  b[38] = (urlList.getOrNull(21)?.toInt() ?: 0).toLong() and 255
  b[39] = (urlList.getOrNull(22)?.toInt() ?: 0).toLong() and 255
  b[40] = (cus.getOrNull(21)?.toInt() ?: 0).toLong() and 255
  b[41] = (cus.getOrNull(22)?.toInt() ?: 0).toLong() and 255
  b[42] = (ua.getOrNull(23)?.toInt() ?: 0).toLong() and 255
  b[43] = (ua.getOrNull(24)?.toInt() ?: 0).toLong() and 255

  val etBytes = splitToBytes(b[10])
  b[44] = etBytes[0]
  b[45] = etBytes[1]
  b[46] = etBytes[2]
  b[47] = etBytes[3]
  b[48] = b[8]
  b[49] = (b[10] / 256 / 256 / 256 / 256) and 255
  b[50] = (b[10] / 256 / 256 / 256 / 256 / 256) and 255

  val pageId = 110624L
  b[51] = pageId
  val pageBytes = splitToBytes(pageId)
  b[52] = pageBytes[0]
  b[53] = pageBytes[1]
  b[54] = pageBytes[2]
  b[55] = pageBytes[3]

  val aid = 6383L
  b[56] = aid
  b[57] = aid and 255
  b[58] = (aid shr 8) and 255
  b[59] = (aid shr 16) and 255
  b[60] = (aid shr 24) and 255

  val windowEnvList = windowEnvStr.map { it.code.toLong() }
  b[64] = windowEnvList.size.toLong()
  b[65] = b[64] and 255
  b[66] = (b[64] shr 8) and 255
  b[69] = 0
  b[70] = 0
  b[71] = 0

  val checksum =
    b[18] xor b[20] xor b[26] xor b[30] xor b[38] xor b[40] xor b[42] xor b[21] xor b[27] xor b[31] xor b[35] xor
      b[39] xor b[41] xor b[43] xor b[22] xor b[28] xor b[32] xor b[36] xor b[23] xor b[29] xor b[33] xor b[37] xor
      b[44] xor b[45] xor b[46] xor b[47] xor b[48] xor b[49] xor b[50] xor b[24] xor b[25] xor b[52] xor b[53] xor
      b[54] xor b[55] xor b[57] xor b[58] xor b[59] xor b[60] xor b[65] xor b[66] xor b[70] xor b[71]
  b[72] = checksum

  val bb = ArrayList<Long>(44 + windowEnvList.size + 1)
  bb.addAll(
    listOf(
      b[18], b[20], b[52], b[26], b[30], b[34], b[58], b[38], b[40], b[53], b[42], b[21],
      b[27], b[54], b[55], b[31], b[35], b[57], b[39], b[41], b[43], b[22], b[28], b[32],
      b[60], b[36], b[23], b[29], b[33], b[37], b[44], b[45], b[59], b[46], b[47], b[48],
      b[49], b[50], b[24], b[25], b[65], b[66], b[70], b[71],
    ),
  )
  bb.addAll(windowEnvList)
  bb.add(checksum)

  val plaintext = bb.map { (it.toInt() and 0xFF).toChar() }.joinToString("")
  return rc4Encrypt(plaintext, (121).toChar().toString())
}

fun generateABogus(query: String, userAgent: String): String {
  val windowEnvStr = "1920|1080|1920|1040|0|30|0|0|1872|92|1920|1040|1857|92|1|24|Win32"
  val bb = generateRc4BbStr(query, userAgent, windowEnvStr, "cus", intArrayOf(0, 1, 14))
  return resultEncrypt(generateRandomStr() + bb, "s4") + "="
}
